package com.cinemamod.mcef;

import com.cinemamod.mcef.listeners.MCEFCursorChangeListener;
import com.cinemamod.mcef.nativebridge.McefNative;
import org.cef.misc.CefCursorType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/**
 * MCEFBrowser —— 把 {@link McefNative}（libmcef.so 的 JNI 绑定）包装成 JCEF 的
 * {@link CefBrowserOsr}，让 WebDisplays 这类依赖 JCEF API 的 mod 能直接用。
 *
 * <h3>签名必须与上游逐字一致</h3>
 * <p>WebDisplays 的 {@code WDClientBrowser extends MCEFBrowser}，构造里调
 * {@code super(client, url, transparent)} 且 client 类型是 {@link MCEFClient}
 * （不是 {@code org.cef.CefClient}）。它还会直接调
 * {@code setCursor(CefCursorType)}、{@code setCursorChangeListener(MCEFCursorChangeListener)}、
 * {@code sendMouseMove(int,int)} 等 —— <b>这些签名一个都不能改</b>，
 * 否则是运行期 {@code NoSuchMethodError}（编译期发现不了，因为消费方用的是官方 jar）。</p>
 *
 * <h3>架构（决定了本类为什么零 android 依赖）</h3>
 * <pre>
 * MC JVM（纯 OpenJDK，加载不到 android.*）
 *   └── 本类 → McefNative（JNI）→ libmcef.so
 *                                    └── IPC 127.0.0.1:39090
 *                                          └── 启动器 .cefhost 进程
 *                                                （ART + Cefrium + libcef）
 * </pre>
 */
public class MCEFBrowser extends CefBrowserOsr {

    private static final Logger LOGGER = LogManager.getLogger("MCEF/Browser");

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    private final MCEFClient client;

    /** native 浏览器句柄；<=0 表示创建失败。 */
    private final long handle;

    /** 纹理上传器；WebDisplays 靠 getRenderer().getTextureID() 直接绘制。 */
    private final MCEFRenderer renderer;

    private volatile String pendingUrl;
    private volatile int viewWidth;
    private volatile int viewHeight;

    /** 设备缩放因子。上游用它把 CSS 像素换算成物理像素。 */
    private volatile double deviceScaleFactor = 1.0;

    /** 是否让 SDK 自动决定 DSF。 */
    private volatile boolean autoDSF = true;

    /** 是否使用浏览器自带的控件（地址栏等）。Android 没有，恒 false。 */
    private volatile boolean browserControls = false;

    /** 光标变化监听。 */
    private volatile MCEFCursorChangeListener cursorChangeListener;

    /** 复用的直接缓冲，避免每帧 allocateDirect。 */
    private ByteBuffer frameBuffer;
    private int frameBufferCapacity;

    /** 上次已知的帧版本；相同则跳过拷贝。 */
    private int lastVersion = -1;

    /** tick 被调用次数；用于确认 getTextureID 还在驱动。 */
    private int tickCount = 0;

    /** 版本变化次数；用于确认 IPC 还在送新帧。 */
    private int versionChanges = 0;

    public MCEFBrowser(MCEFClient client, String url, boolean transparent) {
        this(client, url, transparent, DEFAULT_WIDTH, DEFAULT_HEIGHT, true);
    }

    public MCEFBrowser(MCEFClient client, String url, boolean transparent, boolean autoDSF) {
        this(client, url, transparent, DEFAULT_WIDTH, DEFAULT_HEIGHT, autoDSF);
    }

    /** 扩展构造：指定初始渲染尺寸（WebDisplays 用不到，但保留便于上层控制）。 */
    public MCEFBrowser(MCEFClient client, String url, boolean transparent,
                       int width, int height, boolean autoDSF) {
        super(transparent);
        this.client = client;
        this.autoDSF = autoDSF;
        this.pendingUrl = url == null ? "" : url;
        this.viewWidth = Math.max(1, width);
        this.viewHeight = Math.max(1, height);

        // ── 纹理尺寸：向上取整到 2 的幂（POT）并限制上限 ────────────────
        //
        // 实测：1280x720（NPOT、3.6 MB）时 gl4es 的纹理上传会崩在内部 memcpy
        // （si_addr 落在页边界、R2=0x1400 正好是 1280*4 一行）。
        // 但崩溃前能显示出一帧白底，说明链路本身是通的，问题出在 gl4es 对
        // 大尺寸 NPOT 纹理的处理上。
        //
        // gl4es 对 POT 纹理走硬件路径（不需要临时缓冲做格式转换/降采样），
        // 因此这里统一取 POT 上限，规避它的 NPOT 分支。
        // 画面会被拉伸（WebDisplays 用 UV 0..1 采样整张纹理），但先保证不崩。
        //
        // 可用 -Dmcef.maxDim=N 调整上限（必须是 2 的幂才有效果）。
        // 固定 512：实测档位（同一份代码、MobileGlues 渲染器）
        //   512x512  → 正常渲染出网页内容
        //   1024x1024 → 崩
        //   1280x720  → 崩（libmobileglues glTexImage2D，R2=1280*4 正好一行）
        //
        // 也就是说 MobileGlues 对大纹理上传有硬上限。512 是已验证可用的档位，
        // 先固定在这里保证功能可用；提高清晰度需要等驱动修，或改成分块上传。
        //
        // 想自己试更大尺寸：-Dmcef.maxDim=768（崩了就退回 512）
        final int maxDim = Integer.getInteger("mcef.maxDim", 512);
        if (maxDim > 0) {
            if (this.viewWidth > maxDim) this.viewWidth = maxDim;
            if (this.viewHeight > maxDim) this.viewHeight = maxDim;
        }

        this.renderer = new MCEFRenderer(transparent);
        // 纹理被取用时同步最新帧（见 MCEFRenderer.getTextureID 的说明）
        this.renderer.setBeforeUse(this::tick);

        // 必须先确保桥已初始化。
        //
        // 不能只靠 MCEF.createBrowser() 里的 ensureInitialized：WebDisplays 走的是
        //   new WDClientBrowser(...) → super(...) → 本构造函数
        // 这条路完全绕开了 MCEF.createBrowser，所以初始化必须放在这里。
        // 不加的话 native 侧会回 "mcef is not initialized"，browser 句柄为 0。
        MCEF.ensureInitialized();

        long h = 0;
        try {
            h = McefNative.createBrowser(this.pendingUrl, viewWidth, viewHeight, 1.0f, transparent);
        } catch (Throwable t) {
            LOGGER.error("createBrowser threw", t);
        }
        this.handle = h;

        // 登记到 MCEF 的活跃表：RenderTickEvent 会每帧轮询它来拉帧 + 上传纹理。
        // 不登记的话纹理永远是空的（WebDisplays 不会主动 tick）。
        MCEF.registerActive(this);

        if (handle <= 0) {
            LOGGER.error("createBrowser failed: handle={} lastError={}", handle, safeLastError());
        } else {
            LOGGER.info("browser created handle={} {}x{} transparent={} autoDSF={} (requested {}x{})",
                    handle, viewWidth, viewHeight, transparent, autoDSF, width, height);
        }
    }

    // ── 访问器 ─────────────────────────────────────────────────────────────

    /** native 句柄，供诊断用。 */
    public long getHandle() {
        return handle;
    }

    /** 纹理上传器；WebDisplays 通过它取 GL 纹理 id 直接绘制。 */
    public MCEFRenderer getRenderer() {
        return renderer;
    }

    public void setAutoDSF(boolean autoDSF) {
        this.autoDSF = autoDSF;
    }

    public boolean isAutoDSF() {
        return autoDSF;
    }

    public void setDeviceScaleFactor(double deviceScaleFactor) {
        this.deviceScaleFactor = deviceScaleFactor <= 0 ? 1.0 : deviceScaleFactor;
    }

    public double getDeviceScaleFactor() {
        return deviceScaleFactor;
    }

    /** CSS 像素 → 设备像素。 */
    public int scaleX(int x) {
        return (int) Math.round(x * deviceScaleFactor);
    }

    /** CSS 像素 → 设备像素。 */
    public int scaleY(int y) {
        return (int) Math.round(y * deviceScaleFactor);
    }

    public MCEFCursorChangeListener getCursorChangeListener() {
        return cursorChangeListener;
    }

    public void setCursorChangeListener(MCEFCursorChangeListener cursorChangeListener) {
        this.cursorChangeListener = cursorChangeListener;
    }

    /**
     * {@link CefBrowser} 接口要求的 Object 重载（JCEF 原始形状）。
     *
     * <p>WebDisplays 调的是上面的强类型版本；这个重载只是为了满足接口，
     * 顺带兼容「传任意监听器」的调用方 —— 类型不符时静默忽略而不是抛异常。</p>
     */
    @Override
    public void setCursorChangeListener(Object listener) {
        if (listener instanceof MCEFCursorChangeListener) {
            this.cursorChangeListener = (MCEFCursorChangeListener) listener;
        }
    }

    public boolean usingBrowserControls() {
        return browserControls;
    }

    public MCEFBrowser useBrowserControls(boolean browserControls) {
        this.browserControls = browserControls;
        return this;
    }

    /**
     * 设置光标类型。
     *
     * <p>Android 侧只有一个系统光标（触屏基本看不到），所以这里只记录并转发给
     * 监听者，不真的改系统光标。保留方法是为了消费方代码能编译并通过链接。</p>
     */
    public void setCursor(CefCursorType cursorType) {
        final MCEFCursorChangeListener l = cursorChangeListener;
        if (l != null && cursorType != null) {
            try {
                l.onCursorChange(cursorType);
            } catch (Throwable t) {
                LOGGER.debug("cursor listener failed", t);
            }
        }
    }

    /**
     * 页面报告光标变化（JCEF 的回调入口）。
     *
     * <p>拿 int 型 cursorType 转成枚举后交给 {@link #setCursor}。
     * 返回 true 表示已处理。</p>
     */
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        setCursor(CefCursorType.fromId(cursorType));
        return true;
    }

    private static String safeLastError() {
        try {
            final String e = McefNative.getLastError();
            return e == null ? "" : e;
        } catch (Throwable t) {
            return "";
        }
    }

    // ── 每帧驱动 ───────────────────────────────────────────────────────────

    /**
     * 拉取并上传最新帧。由渲染线程每帧调用。
     *
     * <p>先问版本号，没变就返回（省掉一次 3.5 MB 拷贝）；直接缓冲按需扩容并复用。</p>
     */
    public void tick() {
        if (handle <= 0) return;

        // 纹理由这里懒创建。
        //
        // GL 纹理必须在<b>渲染线程且 GL 上下文有效</b>时创建，而本方法正是被
        // RenderTickEvent(START) 驱动的 —— 那时上下文一定有效。
        // 放在构造里不行（构造可能发生在 mod 加载线程）。
        //
        // 漏了这一步的后果：onPaint 每次都在 textureId==0 时直接 return，
        // 于是 Host 那边渲染了几千帧，MC 侧一帧都没上传，屏幕永远是黑的。
        if (renderer.getTextureID() == 0) {
            renderer.initialize();
            if (renderer.getTextureID() == 0) {
                return;   // 创建失败，下一帧再试
            }
        }

        int[] info;
        try {
            info = McefNative.getFrameInfo(handle);
        } catch (Throwable t) {
            LOGGER.error("getFrameInfo threw", t);
            return;
        }
        if (info == null || info.length < 5 || info[0] != McefNative.OK) return;

        final int w = info[1];
        final int h = info[2];
        final int stride = info[3];
        final int version = info[4];

        if (w <= 0 || h <= 0 || stride < w * 4) return;
        tickCount++;
        if (tickCount % 60 == 0) {
            // 每 60 次 tick 一条。三个数一起看就能定位断点：
            //   tickCount 不涨        → getTextureID 没被调用（屏幕没在渲染）
            //   version == lastVersion → 桥没收到新帧（IPC 断了）
            //   version 在涨但没上传   → 上传被跳过（尺寸/防御检查）
            LOGGER.info("tick #{} handle={} ver={} last={} {}x{} stride={}",
                    tickCount, handle, version, lastVersion, w, h, stride);
        }
        if (version == lastVersion) return;

        if (versionChanges < 20) {
            versionChanges++;
            LOGGER.info("frame version {} -> {} ({}x{})", lastVersion, version, w, h);
        }

        final int bytes = stride * h;
        if (frameBuffer == null || frameBufferCapacity < bytes) {
            frameBuffer = ByteBuffer.allocateDirect(bytes);
            frameBufferCapacity = bytes;
        }
        frameBuffer.clear();
        frameBuffer.limit(bytes);

        final int rc = McefNative.copyFrame(handle, frameBuffer);
        if (rc != McefNative.OK) {
            if (rc != McefNative.ERR_NOT_FOUND) {
                LOGGER.warn("copyFrame failed rc={}", rc);
            }
            return;
        }
        lastVersion = version;

        frameBuffer.position(0);
        frameBuffer.limit(bytes);

        try {
            renderer.onPaint(frameBuffer, w, h);
        } catch (Throwable t) {
            LOGGER.error("texture upload failed", t);
            return;
        }

        try {
            onPaint(this, false, new Rectangle[]{new Rectangle(0, 0, w, h)}, frameBuffer, w, h);
        } catch (Throwable t) {
            LOGGER.error("onPaint hook failed", t);
        }
    }

    // ── CefBrowserOsr 的抽象渲染回调 ────────────────────────────────────────

    /**
     * 一帧渲染完成时的通知。默认空实现 —— 纹理已由 {@link MCEFRenderer} 上传。
     *
     * <p><b>注意 buffer 内容已经是 RGBA</b>（BGRA 在 MCEFRenderer 里被交换过）。</p>
     */
    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                        ByteBuffer buffer, int width, int height) {
        // 覆写者按需处理
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        // IPC 桥不区分 popup 通道
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        // 同上
    }

    // ── 尺寸 ───────────────────────────────────────────────────────────────

    @Override
    public void resize(int width, int height) {
        viewWidth = Math.max(1, width);
        viewHeight = Math.max(1, height);
        if (handle > 0) {
            // native 侧当前是空操作（Cefrium 无 resize API）；
            // 这里只让 getBounds() 反映最新值。
            McefNative.resize(handle, viewWidth, viewHeight);
        }
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(0, 0, viewWidth, viewHeight);
    }

    // ── CefBrowser 实现 ─────────────────────────────────────────────────────

    @Override
    public void loadURL(String url) {
        if (url == null || handle <= 0) return;
        pendingUrl = url;
        final int rc = McefNative.loadUrl(handle, url);
        if (rc != McefNative.OK) {
            LOGGER.warn("loadUrl failed rc={} url={}", rc, url);
        }
    }

    @Override
    public boolean isPageLoading() {
        return handle > 0 && pendingUrl != null && !pendingUrl.isEmpty();
    }

    @Override
    public void executeJavaScript(String code, String url, int line) {
        if (code == null || handle <= 0) return;
        final int rc = McefNative.evaluateJavaScript(handle, code);
        if (rc != McefNative.OK) {
            LOGGER.warn("evaluateJavaScript failed rc={}", rc);
        }
    }

    /** 上游 MCEFBrowser 的单参数版本（WebDisplays 不直接用，保留以对齐 API）。 */
    public void close() {
        close(true);
    }

    @Override
    public void close(boolean force) {
        closing = true;
        MCEF.unregisterActive(this);
        if (handle > 0) {
            McefNative.destroyBrowser(handle);
        }
        renderer.close();
    }

    @Override
    public void setFocus(boolean enable) {
        // 输入事件本身隐含聚焦
    }

    @Override
    public String getURL() {
        if (handle > 0) {
            try {
                final String u = McefNative.getUrl(handle);
                if (u != null && !u.isEmpty()) return u;
            } catch (Throwable ignored) {
                // 回退
            }
        }
        return pendingUrl == null ? "" : pendingUrl;
    }

    @Override
    public String getTitle() {
        return "";
    }

    @Override
    public void sendQuery(String query, int queryId) {
        LOGGER.debug("sendQuery ignored: page-initiated only");
    }

    @Override
    public void sendQuery(String query, int queryId, String request, boolean persistent) {
        sendQuery(query, queryId);
    }

    @Override
    public void createImmediately() {
        if (handle > 0 && pendingUrl != null && !pendingUrl.isEmpty()) {
            loadURL(pendingUrl);
        }
    }

    // ── 输入：上游 MCEF 的签名（WebDisplays 直接调这些）────────────────────

    public void sendMouseMove(int mouseX, int mouseY) {
        sendInput(McefNative.INPUT_MOUSE_MOVE, mouseX, mouseY, 0, 0, 0, 0, 0, 0, 0);
    }

    public void sendMousePress(int mouseX, int mouseY, int button) {
        sendInput(McefNative.INPUT_MOUSE_DOWN, mouseX, mouseY, button, 0, 0, 0, 0, 0, 0);
    }

    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        sendInput(McefNative.INPUT_MOUSE_UP, mouseX, mouseY, button, 0, 0, 0, 0, 0, 0);
    }

    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        sendInput(McefNative.INPUT_MOUSE_WHEEL, mouseX, mouseY, 0, 0, 0, modifiers, 0, amount, 0);
    }

    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        sendInput(McefNative.INPUT_KEY_DOWN, 0, 0, 0, keyCode, (int) scanCode, modifiers, 0, 0, 0);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        sendInput(McefNative.INPUT_KEY_UP, 0, 0, 0, keyCode, (int) scanCode, modifiers, 0, 0, 0);
    }

    public void sendKeyTyped(char c, int modifiers) {
        sendInput(McefNative.INPUT_CHAR, 0, 0, 0, 0, 0, modifiers, 0, 0, c);
    }

    // ── 输入：CefBrowser 接口要求的 JCEF 签名（重载，供通用路径使用）──────

    @Override
    public void sendMouseMove(int x, int y, int modifiers, boolean leave) {
        sendMouseMove(x, y);
    }

    @Override
    public void sendMousePress(int x, int y, int button, int clickCount, int modifiers, boolean release) {
        sendMousePress(x, y, button);
    }

    @Override
    public void sendMouseRelease(int x, int y, int button, int clickCount, int modifiers) {
        sendMouseRelease(x, y, button);
    }

    @Override
    public void sendMouseWheel(int x, int y, int deltaX, int deltaY, int modifiers) {
        // JCEF 用 XY 双轴增量，MCEF 用单值 amount；取 Y 轴。
        sendMouseWheel(x, y, deltaY, modifiers);
    }

    @Override
    public void sendKeyPress(int keyCode, int modifiers, long scanCode, char character) {
        sendKeyPress(keyCode, scanCode, modifiers);
    }

    @Override
    public void sendKeyRelease(int keyCode, int modifiers, long scanCode, char character) {
        sendKeyRelease(keyCode, scanCode, modifiers);
    }

    @Override
    public void sendKeyTyped(char character, int modifiers, long scanCode) {
        sendKeyTyped(character, modifiers);
    }

    private void sendInput(int type, int x, int y, int button, int keyCode, int scanCode,
                           int modifiers, double deltaX, double deltaY, int codepoint) {
        if (handle <= 0) return;
        final int rc = McefNative.sendInput(handle, type, x, y, button, keyCode, scanCode,
                modifiers, deltaX, deltaY, codepoint);
        if (rc != McefNative.OK && rc != McefNative.ERR_UNSUPPORTED) {
            LOGGER.debug("sendInput type={} rc={}", type, rc);
        }
    }
}
