/*
 * MCEF-Android (Forge 1.20.1)
 * Copyright (C) 2026 Rain_Clay and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * PROVENANCE: independent implementation, not a derivative work of
 * CinemaMod MCEF (LGPL-2.1). Names are kept identical only to satisfy
 * WebDisplays bytecode references; no upstream source, comments or
 * implementation logic is included. See README "与上游的关系".
 */

package com.cinemamod.mcef;

import com.cinemamod.mcef.listeners.MCEFCursorChangeListener;
import com.cinemamod.mcef.nativebridge.McefNative;
import org.cef.misc.CefCursorType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefQueryCallbackHandler;
import org.cef.browser.CefBrowserOsr;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * <h3>生命周期</h3>
 * <p>构造函数里就 {@code MCEF.ensureInitialized()} 再 {@code createBrowser}，
 * 失败时 {@code handle <= 0} 并记一条 error。渲染完全靠 {@link #getRenderer()}
 * 取纹理，本类不注册任何渲染事件 —— 帧上传的驱动点是
 * {@link MCEFRenderer#getTextureID()}，详见 {@link #tick()}。</p>
 *
 * <h3>已知空缺</h3>
 * <ul>
 *   <li>{@link #getTitle}：恒空串（IPC 无取标题通道）</li>
 *   <li>{@link #sendQuery}：只支持「页面 → Java」方向</li>
 *   <li>键盘输入：会发出去，host 侧明确不支持</li>
 *   <li>光标：{@link MCEFCursorChangeListener} 不会被触发</li>
 * </ul>
 */
public class MCEFBrowser extends CefBrowserOsr {

    private static final Logger LOGGER = LogManager.getLogger("MCEF/Browser");

    /**
     * 画布默认尺寸。
     *
     * 必须覆盖 WebDisplays 允许的最大屏幕分辨率，否则鼠标坐标会越界。
     * WebDisplays 给方块屏幕算出的坐标用的是该屏幕自己设的分辨率
     * （ScreenData.resolution），上限由 common 配置的
     * max_resolution_x / max_resolution_y 决定，默认 1920x1080。
     * 它把这些坐标原样交给 sendMouse*，不做任何缩放。
     *
     * 以前这里是 1280x720：屏幕按 1920x1080 算出的坐标一旦超过 1280 或 720，
     * CEF 直接丢弃整个事件，表现为屏幕右侧和下半部分完全点不动。
     * 早年画布是 512x512、屏幕分辨率也低，两者量级接近，所以那时是好的。
     *
     * 取上限而不是让画布跟随每块屏幕：MCEFBrowser 在 WebDisplays 的构造链里
     * 拿不到 ScreenData.resolution，而画布偏大只会让页面按更大的视口排版
     * （正好也符合"按桌面版渲染"的目标），偏小则会丢输入。
     */
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;

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

    /** frame pump 防重入：texture getter → tick → 纹理上传期间不得再次进入 tick。 */
    private final AtomicBoolean pumping = new AtomicBoolean(false);

    /** 上次成功上传的帧版本；相同则跳过 JNI copyFrame 和纹理上传。 */
    private int lastVersion = -1;

    /** 单次 tick 最多分发多少条查询，防止页面刷屏把渲染线程拖住。 */
    private static final int MAX_QUERIES_PER_TICK = 8;

    /** JS 查询不可用只警告一次，避免每帧刷屏。 */
    private boolean queryUnsupportedLogged;

    /** 查询分发防重入：处理器可能同步回调，也可能自己触发新的查询。 */
    private final AtomicBoolean dispatchingQueries = new AtomicBoolean(false);

    public MCEFBrowser(MCEFClient client, String url, boolean transparent) {
        this(client, url, transparent, DEFAULT_WIDTH, DEFAULT_HEIGHT, true);
    }

    public MCEFBrowser(MCEFClient client, String url, boolean transparent, boolean autoDSF) {
        this(client, url, transparent, DEFAULT_WIDTH, DEFAULT_HEIGHT, autoDSF);
    }

    /** 扩展构造：指定初始渲染尺寸（WebDisplays 走的是 3 参数版本，这个是给上层自用的）。 */
    public MCEFBrowser(MCEFClient client, String url, boolean transparent,
                       int width, int height, boolean autoDSF) {
        super(transparent);
        this.client = client;
        this.autoDSF = autoDSF;
        this.pendingUrl = url == null ? "" : url;
        this.viewWidth = Math.max(1, width);
        this.viewHeight = Math.max(1, height);

        // 可选的尺寸上限，默认不限（-Dmcef.maxDim=0）。
        // 设了就按最长边等比缩放，绝不把宽高分别 clamp —— 那样 1280x720
        // 会变成 512x512，网页被压成正方形。
        final int maxDim = Integer.getInteger("mcef.maxDim", 0);
        if (maxDim > 0) {
            final int longest = Math.max(this.viewWidth, this.viewHeight);
            if (longest > maxDim) {
                final double scale = (double) maxDim / longest;
                this.viewWidth = Math.max(1, (int) Math.round(this.viewWidth * scale));
                this.viewHeight = Math.max(1, (int) Math.round(this.viewHeight * scale));
            }
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
     * 拉取并上传最新帧。由 renderer 的 frame-gated beforeUse 回调调用，
     * 同一浏览器同一 MC 渲染帧最多进入一次。
     *
     * <h3>调用链与三道闸</h3>
     * <pre>
     * MCEFRenderer.getTextureID()
     *   └── claimPumpForFrame(minecraftFrameId)     // 同帧只放行一次
     *         └── tick()                            // ← 本方法
     *               ├── peekTextureID()              // 不触发 pump，防递归
     *               ├── getFrameInfo()               // version 没变直接返回
     *               ├── copyFrame()                  // 复用直接缓冲
     *               └── MCEFRenderer.onPaint()       // swap + glTexImage2D
     * </pre>
     *
     * <ul>
     *   <li>本方法内<b>只能用 {@code peekTextureID()}</b>。调 {@code getTextureID()}
     *       就是 {@code getTextureID → beforeUse → tick → getTextureID} 自递归
     *       （实测 -Xss2040k 下 6.2 万层，-Xss512k 下 7331 层）。</li>
     *   <li>{@code pumping} 是第二道防线：即便上面两道闸都漏了，也不会重入。</li>
     *   <li>版本号守门：{@code version <= 0}（native 初始化的全透明占位帧）
     *       或 {@code == lastVersion} 时直接返回，不 copy、不 swap、不上传。</li>
     * </ul>
     */
    /**
     * 取走并分发 native 队列里的 JS 查询。
     *
     * <p>链路：页面 {@code cefQuery} → host 的 {@code setQueryHandler} 收到 request
     * → IPC {@code {"event":"query"}} → native 入队 → 这里取出 → 交给
     * {@link CefQueryCallbackHandler} 处理 → 结果经 {@code replyQuery} 回 host
     * → host 调 Cefrium 的 callback 答复页面。</p>
     *
     * <p>为什么必须绕回 host：只有 host 进程持有 Cefrium 的 callback 实例，
     * MC 侧算出结果也没法直接回页面。</p>
     *
     * <p>处理器同步返回的值决定这次查询是否被受理：{@code true} 表示已处理
     * （可能异步，稍后自己调 callback），{@code false} 表示不认这条查询，
     * 立刻按失败回填，免得页面一直等。</p>
     */
    private void dispatchPendingQueries() {
        if (handle <= 0) return;
        if (!dispatchingQueries.compareAndSet(false, true)) return; // 处理器回调里重入直接跳过
        try {
            for (int processed = 0; processed < MAX_QUERIES_PER_TICK; processed++) {
                final String[] q;
                try {
                    q = McefNative.takeQuery(handle);
                } catch (Throwable t) {
                    LOGGER.error("takeQuery failed handle={}", handle, t);
                    return;
                }
                if (q == null || q.length < 4) return; // 队列空了

                final long queryId;
                final boolean persistent;
                try {
                    queryId = Long.parseLong(q[0]);
                    persistent = !"0".equals(q[1]);
                } catch (NumberFormatException e) {
                    LOGGER.warn("malformed query header: {} {}", q[0], q[1]);
                    continue;
                }
                final String request = q[2];
                final String origin = q[3];

                if (!deliverQuery(queryId, request, persistent, origin)) {
                    // 没人认领：立刻失败回填，否则页面的 cefQuery 要等到超时。
                    replyFailure(queryId, McefNative.ERR_NOT_FOUND,
                            "no query handler accepted this request");
                }
            }
        } finally {
            dispatchingQueries.set(false);
        }
    }

    /**
     * 把一条查询交给 client 注册的处理器，返回是否有处理器受理。
     *
     * <p>按注册顺序遍历，第一个返回 {@code true} 的处理器算认领。</p>
     *
     * <p>{@code queryHandlers} 是普通 ArrayList，而这里跑在渲染线程；注册虽发生在
     * 初始化阶段，但处理器内部也可能再注册 —— 先复制一份再遍历，
     * 避免 ConcurrentModificationException。</p>
     */
    private boolean deliverQuery(long queryId, String request, boolean persistent, String origin) {
        final java.util.List<CefQueryCallbackHandler> handlers;
        try {
            handlers = new java.util.ArrayList<>(client.getHandle().getQueryHandlers());
        } catch (Throwable t) {
            LOGGER.error("cannot read query handlers", t);
            return false;
        }
        if (handlers.isEmpty()) return false;

        final CefQueryCallback callback = new QueryCallback(queryId, origin);
        for (CefQueryCallbackHandler h : handlers) {
            try {
                if (h.onQuery(this, null, queryId, request, persistent, callback)) {
                    return true;
                }
            } catch (Throwable t) {
                LOGGER.error("query handler {} threw for queryId={}",
                        h.getClass().getName(), queryId, t);
            }
        }
        return false;
    }

    /** 把失败结果回填给 host。 */
    private void replyFailure(long queryId, int errorCode, String message) {
        try {
            McefNative.replyQuery(handle, queryId, false, message, errorCode);
        } catch (Throwable t) {
            LOGGER.error("replyQuery(failure) failed queryId={}", queryId, t);
        }
    }

    /**
     * 交给处理器的回调：把结果转成 IPC 回填命令发回 host。
     *
     * <p>页面侧看到的就是这次 {@code cefQuery} 的 onSuccess / onFailure。
     * 重复调用只认第一次 —— Cefrium 那边一条查询只能答复一次。</p>
     */
    private final class QueryCallback implements CefQueryCallback {
        private final long queryId;
        private final String origin;
        private final AtomicBoolean replied = new AtomicBoolean(false);

        QueryCallback(long queryId, String origin) {
            this.queryId = queryId;
            this.origin = origin;
        }

        @Override
        public void success(String response) {
            if (!replied.compareAndSet(false, true)) return;
            try {
                McefNative.replyQuery(handle, queryId, true,
                        response == null ? "" : response, 0);
            } catch (Throwable t) {
                LOGGER.error("replyQuery(success) failed queryId={}", queryId, t);
            }
        }

        @Override
        public void failure(int errorCode, String errorMessage) {
            if (!replied.compareAndSet(false, true)) return;
            try {
                McefNative.replyQuery(handle, queryId, false,
                        errorMessage == null ? "" : errorMessage, errorCode);
            } catch (Throwable t) {
                LOGGER.error("replyQuery(failure) failed queryId={}", queryId, t);
            }
        }
    }

    /**
     * 游戏是否处于暂停状态（单机暂停 / 多人暂停菜单）。
     *
     * <p>任何 Throwable 都当作"没暂停"：判断本身不能成为渲染中断的理由。</p>
     */
    private static boolean isGamePaused() {
        try {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return mc != null && mc.isPaused();
        } catch (Throwable t) {
            return false;
        }
    }

    public void tick() {
        if (handle <= 0 || !pumping.compareAndSet(false, true)) return;
        try {
            // 游戏暂停时丢弃整帧任务：暂停期间 GL 上下文处于半冻结状态，
            // 此时 glTexImage2D 上传会在驱动内部崩（libmobileglues memcpy）。
            // renderer.getTextureID() 里也有同样的门，这里是第二道 ——
            // 暂停时可能还有别的路径（如 query 回调）触发 tick。
            if (isGamePaused()) return;

            if (renderer.peekTextureID() == 0) {
                renderer.initialize();
                if (renderer.peekTextureID() == 0) return;
            }

            // 查询分发必须放在帧版本门控之前：没有新帧时 version 不变会提前
            // return，查询就会一直压在 native 队列里等到下一次重绘才处理。
            //
            // 整段包 try/catch(Throwable)：libmcef.so 当前版本没有 takeQuery /
            // replyQuery 的 native 实现（2.3.0 新增，C++ 侧未跟进），调用会抛
            // UnsatisfiedLinkError。那是 Error 不是 Exception，会穿透 tick() 一路
            // 炸到渲染线程 —— MC 侧随即关闭 IPC socket，host 有画面却发不出来，
            // 表现为 paints 一直涨、frames 恒为 0、日志刷 "Socket closed"。
            //
            // 这里吞掉只影响 JS 查询（cefQuery）功能，帧链路不受牵连。
            // 等 libmcef.so 补上实现后，这段 catch 可以退回精确捕获。
            try {
                dispatchPendingQueries();
            } catch (Throwable t) {
                if (!queryUnsupportedLogged) {
                    queryUnsupportedLogged = true;
                    LOGGER.warn("JS 查询暂不可用（libmcef.so 缺 native 实现），已跳过：{}", t.toString());
                }
            }

            final int[] info = McefNative.getFrameInfo(handle);
            if (info == null || info.length < 5 || info[0] != McefNative.OK) return;
            final int w = info[1];
            final int h = info[2];
            final int stride = info[3];
            final int version = info[4];
            if (w <= 0 || h <= 0 || stride < w * 4) return;

            // version=0 是 native bridge 初始化时的全透明占位帧，不复制、不上传。
            if (version <= 0 || version == lastVersion) return;

            final long bytesLong = (long) stride * h;
            if (bytesLong <= 0 || bytesLong > Integer.MAX_VALUE) return;
            final int bytes = (int) bytesLong;
            if (frameBuffer == null || frameBufferCapacity < bytes) {
                frameBuffer = ByteBuffer.allocateDirect(bytes);
                frameBufferCapacity = bytes;
            }
            frameBuffer.clear();
            frameBuffer.limit(bytes);

            final int rc = McefNative.copyFrame(handle, frameBuffer);
            if (rc != McefNative.OK) {
                if (rc != McefNative.ERR_NOT_FOUND) LOGGER.warn("copyFrame failed rc={}", rc);
                return;
            }
            frameBuffer.position(0);
            frameBuffer.limit(bytes);
            renderer.onPaint(frameBuffer, w, h);

            try {
                onPaint(this, false, new Rectangle[]{new Rectangle(0, 0, w, h)}, frameBuffer, w, h);
            } catch (Throwable t) {
                LOGGER.error("onPaint hook failed", t);
            }
            // 只在成功上传后推进版本；若 GL 上传失败，下次取纹理时允许重试同一帧。
            lastVersion = version;
        } catch (Throwable t) {
            LOGGER.error("browser frame pump failed handle={}", handle, t);
        } finally {
            pumping.set(false);
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
            // 会真的生效：Cefrium 0.15.0 起有 CefriumBrowser.setSize()，
            // native 把命令转发给 host。新尺寸的帧由 host 那边 OnPaint 推回来，
            // 这里不伪造任何本地帧 —— 否则会出现「白屏且不再重绘」。
            //
            // viewWidth/viewHeight 立即更新是为了让 getBounds() 跟上；
            // 缓存里的帧尺寸要等新帧到达才会变（见 tick() 的版本门控）。
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
        if (handle > 0) {
            McefNative.destroyBrowser(handle);
        }
        renderer.close();
    }

    @Override
    public void setFocus(boolean enable) {
        // IPC 桥没有 focus 命令（native 与 host 两侧都没有对应 cmd）。
        // 焦点由输入事件隐含：点到页面里就相当于聚焦。
    }

    /**
     * 当前 URL。优先问 native，拿不到就退回最后一次 loadURL 的目标。
     *
     * <p>native 侧返回的是 host 上报的真实地址（跟随重定向后的），
     * {@code pendingUrl} 是请求地址。两者都可能为空串。</p>
     */
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

    /**
     * 页面标题。
     *
     * <p><b>恒为空串</b>：IPC 桥没有取标题的通道。要支持得在 host 侧加
     * {@code onTitleChange} 上报 + native 侧加缓存与查询命令。</p>
     */
    @Override
    public String getTitle() {
        return "";
    }

    /**
     * 向页面发起查询。
     *
     * <p><b>空实现</b>：只有「页面 → Java」这个方向可用（{@code window.cefQuery}
     * 经 host 转发）。反方向没有对应 IPC 命令，所以这里只记日志。</p>
     */
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
    //
    // WebDisplays 传进来的坐标已经是它给该屏幕设定的分辨率坐标
    // （ScreenData.resolution），量纲与 CEF 画布一致，不需要缩放。
    // 它把这些坐标原样透传给 sendMouse*，自己不做换算。
    //
    // 真正要保证的是「画布不小于屏幕分辨率」。画布曾经是 1280x720，而屏幕按
    // 1920x1080 上限算出的坐标能到 x=1536 / y=894，越界后 CEF 直接丢弃整个
    // 事件 —— 表现为屏幕右侧和下半部分完全点不动。现在 DEFAULT_WIDTH/HEIGHT
    // 已提到 1920x1080 覆盖上限，这里的 clampX/clampY 只是最后一道保险。
    //
    // 能力边界：鼠标 move/down/up/wheel host 侧有真实实现（CefriumBrowser 的
    // sendMouse* API）。键盘 down/up/char 会发出去，但 host 侧当前记录
    // 「后端不支持」并丢弃（见 CefriumHostService 的 handleInput）。

    /**
     * 把坐标夹进画布范围。
     *
     * 这里不做缩放：WebDisplays 传进来的已经是它给该屏幕设定的分辨率坐标
     * （setResolution 是按方块屏幕单独设的），量纲和 CEF 画布一致。
     * 早先 512x512 画布时代点击正常，正是因为它原样透传就够用。
     *
     * 需要做的只有一件事：把偶尔越界的值夹住，避免 CEF 直接丢弃整个事件。
     */
    private int clampX(int x) {
        final int w = viewWidth > 0 ? viewWidth : 1;
        if (x < 0) return 0;
        return x >= w ? w - 1 : x;
    }

    private int clampY(int y) {
        final int h = viewHeight > 0 ? viewHeight : 1;
        if (y < 0) return 0;
        return y >= h ? h - 1 : y;
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        sendInput(McefNative.INPUT_MOUSE_MOVE, clampX(mouseX), clampY(mouseY), 0, 0, 0, 0, 0, 0, 0);
    }

    public void sendMousePress(int mouseX, int mouseY, int button) {
        // 诊断：logic 是 WebDisplays 给的，canvas 是发给 CEF 的（只夹边界）。
        // 两者相差过大说明越界被夹了，等于点在画布外。
        LOGGER.info("mousePress logic=({},{}) canvas=({},{}) viewport={}x{}",
                mouseX, mouseY, clampX(mouseX), clampY(mouseY), viewWidth, viewHeight);
        sendInput(McefNative.INPUT_MOUSE_DOWN, clampX(mouseX), clampY(mouseY),
                button, 0, 0, 0, 0, 0, 0);
    }

    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        sendInput(McefNative.INPUT_MOUSE_UP, clampX(mouseX), clampY(mouseY),
                button, 0, 0, 0, 0, 0, 0);
    }

    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        sendInput(McefNative.INPUT_MOUSE_WHEEL, clampX(mouseX), clampY(mouseY),
                0, 0, 0, modifiers, 0, amount, 0);
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
        // ERR_UNSUPPORTED 是预期内的（例如键盘），不当错误刷屏。
        // 之前用 debug，正式包的日志级别下根本看不到，导致“点了没反应”查不到原因。
        if (rc != McefNative.OK && rc != McefNative.ERR_UNSUPPORTED) {
            LOGGER.info("sendInput type={} rc={}", type, rc);
        }
    }
}
