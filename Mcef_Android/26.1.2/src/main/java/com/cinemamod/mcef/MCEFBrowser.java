package com.cinemamod.mcef;

import android.util.Log;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.cef.CefClient;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefQueryCallbackHandler;
import org.cef.callback.CefQueryCallback;

import org.cef.misc.CefRect;
import java.nio.ByteBuffer;

/**
 * MCEFBrowser —— 把 Cefrium 的 OSR 浏览器包装成 JCEF 的 {@link CefBrowserOsr}。
 *
 * <p><b>这是整个 Android 适配的核心</b>。它保持与上游 CinemaMod MCEF 的 API 形状，
 * 让 WebDisplays 一行都不用改；底层全部换成 Cefrium 的 JNI。</p>
 *
 * <h3>生命周期（重要）</h3>
 * <pre>
 * new MCEFBrowser(client, url, transparent)
 *   → new CefriumBrowser(width, height, scale)   // 只建 Java 对象，不建浏览器
 *   → setBackgroundColor(0) 当 transparent       // 必须在首次 loadUrl 之前
 *   → 注册 render/url/title/loading 回调
 *   → 消费方调 createImmediately() 或 loadURL()   // 这时才真正 CreateBrowser
 * </pre>
 *
 * <p>Cefrium 的 {@code nativeCreate} 只是分配 {@code SdkBrowserData}，
 * 真正的浏览器在<b>第一次 loadUrl</b> 时才创建（见 cefrium_sdk_jni.cc 里
 * “browser not yet created”那条日志）。所以 {@code setBackgroundColor}
 * 必须发生在这之前，否则抛 IllegalStateException。</p>
 *
 * <h3>线程模型</h3>
 * <p>Cefrium 的 {@code setRenderHandler} 回调在 UI 线程；{@code evaluateJavaScript}
 * 等必须在 UI 线程调用。本类的公开方法都是线程安全的（内部切到主线程），
 * 因为它会被 MC 的渲染线程调用。</p>
 */
public class MCEFBrowser extends CefBrowserOsr {

    private static final String TAG = "MCEF/Browser";

    /** 默认渲染尺寸；WebDisplays 会用 resize() 覆盖。 */
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    private final CefClient client;
    private final CefriumBrowser browser;

    /** 当前承载的 URL，用于 getURL()（Cefrium 的 getUrl 在创建前为 null）。 */
    private volatile String pendingUrl = "";

    /** 浏览器是否已经真正创建（第一次 loadUrl 之后）。 */
    private volatile boolean created = false;

    /** 当前尺寸，用于 getBounds()。 */
    private volatile int viewWidth = DEFAULT_WIDTH;
    private volatile int viewHeight = DEFAULT_HEIGHT;

    /**
     * 纹理上传器。WebDisplays 的 ScreenRenderer 靠
     * {@code getRenderer().getTextureID()} 拿到 GL 纹理再直接画 ——
     * 这条路径绕开了 MC 的 GUI 渲染管线，正是它在 Windows 上能工作的原因。
     */
    private final MCEFRenderer renderer;

    public MCEFBrowser(CefClient client, String url, boolean transparent) {
        this(client, url, transparent, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public MCEFBrowser(CefClient client, String url, boolean transparent, int width, int height) {
        super(transparent);
        this.client = client;
        this.pendingUrl = url == null ? "" : url;
        this.viewWidth = Math.max(1, width);
        this.viewHeight = Math.max(1, height);

        // Cefrium 的 OSR 构造：只有 (width, height, scale)。
        // scale 用 1.0 —— WebDisplays 的屏幕是按像素渲染的，不需要额外缩放。
        this.browser = new CefriumBrowser(viewWidth, viewHeight, 1.0f);
        this.renderer = new MCEFRenderer(transparent);

        // 透明背景必须在首次 loadUrl 之前设置（Cefrium 的硬约束）。
        // 传 0x00000000 = 全透明：页面未绘制处的 alpha 会是 0，
        // 底层方块才能透出来 —— 这正是 WebDisplays 屏幕要的效果。
        if (transparent) {
            try {
                browser.setBackgroundColor(0);
            } catch (Throwable t) {
                // 老版本 Cefrium 没有这个 API 时不应让整个浏览器创建失败：
                // 退化成不透明，功能仍可用（只是屏幕背景不透）。
                Log.w(TAG, "setBackgroundColor failed, falling back to opaque", t);
            }
        }

        registerCallbacks();
    }

    /** 把 Cefrium 的回调接到本类和 client 上的各个 handler。 */
    private void registerCallbacks() {
        // ── 渲染 ──────────────────────────────────────────────────────────
        // Cefrium 的 onPaint 签名是 (int w, int h, ByteBuffer pixels)，
        // 没有 dirtyRects / popup 区分 —— 每次都是整帧。
        // 为了让 JCEF 的调用方（WebDisplays 的覆写）行为正确，这里合成一个
        // 覆盖全屏的 dirty rect，并标 popup=false。
        browser.setRenderHandler(new CefriumRenderHandler() {
            @Override
            public void onPaint(int width, int height, ByteBuffer pixels) {
                // 1) 先上传到 GL 纹理：WebDisplays 的 ScreenRenderer 要的是
                //    renderer.getTextureID()，不经过 MC 的 GUI 管线。
                try {
                    renderer.onPaint(pixels, width, height);
                } catch (Throwable t) {
                    Log.e(TAG, "texture upload failed", t);
                    return;
                }

                // 2) 再通知 JCEF 风格的覆写点。Cefrium 不给 dirtyRects/popup，
                //    这里合成一个覆盖全屏的 rect。
                //    注意 pixels 这时已被交换过 R/B —— 覆写方若直接读 buffer
                //    会拿到 RGBA 而不是 BGRA，需按此约定处理（README 有说明）。
                try {
                    // 用 this.onPaint 而不是 super.onPaint：
                    // onPaint 在 CefBrowserOsr 里是 abstract，Java 不允许对抽象方法调 super。
                    // this.onPaint 会在运行期动态分派 —— 子类覆写时进子类，否则进本类的
                    // 默认空实现，效果和 JCEF 的钩子语义一致。
                    MCEFBrowser.this.onPaint(
                            MCEFBrowser.this,
                            false,
                            new CefRect[]{new CefRect(0, 0, width, height)},
                            pixels,
                            width,
                            height
                    );
                } catch (Throwable t) {
                    Log.e(TAG, "onPaint hook failed", t);
                }
            }
        });

        // ── 加载状态 ──────────────────────────────────────────────────────
        browser.setOnLoadingStateChangedListener((loading, canGoBack, canGoForward) -> {
            for (CefLoadHandler h : client.getLoadHandlers()) {
                try {
                    h.onLoadingStateChange(MCEFBrowser.this, loading, canGoBack, canGoForward);
                } catch (Throwable t) {
                    Log.e(TAG, "load handler failed", t);
                }
            }
            if (!loading) {
                for (CefLoadHandler h : client.getLoadHandlers()) {
                    try {
                        h.onLoadEnd(MCEFBrowser.this, getMainFrame(), 0);
                    } catch (Throwable t) {
                        Log.e(TAG, "onLoadEnd failed", t);
                    }
                }
            }
        });

        // ── URL 变化 ──────────────────────────────────────────────────────
        browser.setOnUrlChangedListener(url -> {
            pendingUrl = url == null ? "" : url;
            for (CefLoadHandler h : client.getLoadHandlers()) {
                try {
                    h.onAddressChange(MCEFBrowser.this, getMainFrame(), pendingUrl);
                } catch (Throwable t) {
                    Log.e(TAG, "onAddressChange failed", t);
                }
            }
        });

        // ── 标题变化 ──────────────────────────────────────────────────────
        browser.setOnTitleChangedListener(title -> {
            for (CefDisplayHandler h : client.getDisplayHandlers()) {
                try {
                    h.onTitleChange(MCEFBrowser.this, title);
                } catch (Throwable t) {
                    Log.e(TAG, "onTitleChange failed", t);
                }
            }
        });

        // ── JS 查询（window.cefQuery）──────────────────────────────────────
        // Cefrium 的 QueryOriginPolicy 决定哪些来源可以调这个桥。
        // 用 anyOrigin()：WebDisplays 的屏幕可以指向任意站点，策略不该拦它。
        // （认证/鉴权属于消费方自己的事，不靠这一层。）
        browser.setQueryHandler(
                CefriumBrowser.QueryOriginPolicy.anyOrigin(),
                (queryId, request, origin, callback) -> {
                    for (CefQueryCallbackHandler h : client.getQueryHandlers()) {
                        try {
                            boolean handled = h.onQuery(
                                    MCEFBrowser.this, getMainFrame(), queryId, request, false,
                                    new CefQueryCallback() {
                                        @Override
                                        public void success(String response) {
                                            callback.success(response == null ? "" : response);
                                        }

                                        @Override
                                        public void failure(int errorCode, String errorMessage) {
                                            callback.failure(errorCode,
                                                    errorMessage == null ? "" : errorMessage);
                                        }
                                    });
                            if (handled) return true;
                        } catch (Throwable t) {
                            Log.e(TAG, "query handler failed", t);
                        }
                    }
                    // 没人处理：回失败，别让页面一直挂着等回调。
                    callback.failure(-1, "no handler");
                    return true;
                });
    }

    /** 纹理上传器；WebDisplays 通过它取 GL 纹理 id 直接绘制。 */
    public MCEFRenderer getRenderer() {
        return renderer;
    }

    // ── CefBrowserOsr 的抽象渲染回调 ────────────────────────────────────────
    // 这三个是 JCEF 留给"消费方覆写"的钩子：WebDisplays 的 MCEFBrowser 子类
    // 会覆写 onPaint 来做自己的处理。本类给出无副作用的默认实现 —— 纹理上传
    // 已经在 CefriumRenderHandler 里做完了，这里默认什么都不做。

    /**
     * 一帧渲染完成时的通知。
     *
     * <p>默认空实现：纹理已由 {@link MCEFRenderer} 上传，消费方若需要原始像素
     * （例如自己做缓存或转码）可以覆写。</p>
     *
     * <p><b>注意 buffer 内容已经是 RGBA</b>（BGRA 在 MCEFRenderer 里被交换过），
     * 不再是 JCEF 文档里说的 BGRA。</p>
     */
    @Override
    public void onPaint(CefBrowser browser, boolean popup, CefRect[] dirtyRects,
                        ByteBuffer buffer, int width, int height) {
        // 默认不做事；覆写者按需处理。
    }

    /** 弹出层显示状态变化。Cefrium 不区分 popup，默认空实现。 */
    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        // Cefrium 的 OSR 没有独立的 popup 通道（下拉框直接画进主帧）。
    }

    /** 弹出层尺寸变化。Cefrium 不区分 popup，默认空实现。 */
    @Override
    public void onPopupSize(CefBrowser browser, CefRect size) {
        // 同上。
    }

    // ── CefBrowser 实现 ─────────────────────────────────────────────────────

    @Override
    public void loadURL(String url) {
        if (url == null) return;
        pendingUrl = url;
        created = true;
        runOnUi(() -> browser.loadUrl(url));
    }

    @Override
    public boolean isPageLoading() {
        // Cefrium 没有同步查询加载状态的方法，只提供回调。
        // 这里用"是否已发起加载"近似，够 WebDisplays 在渲染时判断是否该等待。
        return created;
    }

    @Override
    public void executeJavaScript(String code, String url, int line) {
        if (code == null) return;
        runOnUi(() -> browser.evaluateJavaScript(code));
    }

    @Override
    public void resize(int width, int height) {
        viewWidth = Math.max(1, width);
        viewHeight = Math.max(1, height);
        // Cefrium 没有 resize API（原生侧 mcef_resize 早改成空操作），
        // 所以这里只更新本地尺寸，让 getBounds() 反映最新值。
        // 帧尺寸仍由创建时的宽高决定 —— 这是当前限制，已在 README 记录。
        Log.d(TAG, "resize to " + viewWidth + "x" + viewHeight + " (frame size unchanged)");
    }

    @Override
    public void close(boolean force) {
        closing = true;
        runOnUi(() -> {
            try {
                browser.close();
            } catch (Throwable t) {
                Log.w(TAG, "close failed", t);
            }
        });
    }

    @Override
    public void setFocus(boolean enable) {
        // Cefrium 没有独立的 focus API；触摸/鼠标事件本身就隐含聚焦。
        // 这里不做动作，保留方法体是为了让消费方的调用链成立。
    }

    @Override
    public String getURL() {
        try {
            String u = browser.getUrl();
            if (u != null && !u.isEmpty()) return u;
        } catch (Throwable ignored) {
            // getUrl 在浏览器未创建时可能抛，回退到 pendingUrl。
        }
        return pendingUrl;
    }

    @Override
    public String getTitle() {
        // Cefrium 只通过回调给标题，不提供同步读取；返回空串而不是缓存，
        // 避免调用方拿到过期数据。
        return "";
    }

    @Override
    public CefRect getBounds() {
        return new CefRect(0, 0, viewWidth, viewHeight);
    }

    @Override
    public void sendQuery(String query, int queryId) {
        // 页面主动调 window.cefriumQuery 由 setQueryHandler 接；
        // 反向（native → 页面）没有对应能力，保留空实现避免误解。
        Log.d(TAG, "sendQuery ignored: page-initiated only on Android");
    }

    @Override
    public void sendQuery(String query, int queryId, String request, boolean persistent) {
        sendQuery(query, queryId);
    }

    // ── 输入事件 ───────────────────────────────────────────────────────────

    @Override
    public void sendMouseMove(int x, int y, int modifiers, boolean leave) {
        runOnUi(() -> browser.sendMouseMove(x, y));
    }

    @Override
    public void sendMousePress(int x, int y, int button, int clickCount, int modifiers, boolean release) {
        runOnUi(() -> browser.sendMouseDown(x, y, button, Math.max(1, clickCount)));
    }

    @Override
    public void sendMouseRelease(int x, int y, int button, int clickCount, int modifiers) {
        runOnUi(() -> browser.sendMouseUp(x, y, button, Math.max(1, clickCount)));
    }

    @Override
    public void sendMouseWheel(int x, int y, int deltaX, int deltaY, int modifiers) {
        // JCEF 的 wheel 用 rotation 语义，Cefrium 用像素增量；
        // WebDisplays 传的是滚动行数，这里放大到像素（一行 ≈ 40px）。
        runOnUi(() -> browser.sendMouseWheel(x, y, deltaX * 40, deltaY * 40));
    }

    @Override
    public void sendKeyPress(int keyCode, int modifiers, long scanCode, char character) {
        // Cefrium 没有暴露键盘事件 API（只有触摸/鼠标）。
        // 这里显式丢弃并记录一次，便于定位"键盘没反应"是被上游限制而不是本类 bug。
        Log.d(TAG, "key event dropped: Cefrium has no keyboard API");
    }

    @Override
    public void sendKeyRelease(int keyCode, int modifiers, long scanCode, char character) {
        // 同上。
    }

    @Override
    public void sendKeyTyped(char character, int modifiers, long scanCode) {
        // 同上。
    }

    // ── OSR 生命周期 ───────────────────────────────────────────────────────

    @Override
    public void createImmediately() {
        // Cefrium 的浏览器在首次 loadUrl 时创建；WebDisplays 的调用顺序是
        // createImmediately() 然后 loadURL()，所以这里主动触发一次加载，
        // 让浏览器尽早建立（否则第一帧要等到消费方真正 loadURL 才出现）。
        if (!created && pendingUrl != null && !pendingUrl.isEmpty()) {
            loadURL(pendingUrl);
        }
    }

    @Override
    public void setCursorChangeListener(Object listener) {
        // Cefrium 未暴露光标类型回调。保留方法以便消费方调用链成立。
    }

    // ── 内部工具 ───────────────────────────────────────────────────────────

    /**
     * 切到 Android 主线程执行。
     *
     * <p>Cefrium 的绝大多数 API 都要求在 UI 线程调用（内部有 looper 依赖），
     * 而 MC 的渲染线程不是 UI 线程，所以统一在这里切。</p>
     */
    private static void runOnUi(Runnable action) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.e(TAG, "ui action failed", t);
            }
        });
    }
}
