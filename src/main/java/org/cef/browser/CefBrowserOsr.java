package org.cef.browser;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * CefBrowserOsr —— JCEF 里"离屏渲染浏览器"的基类，这里是 Android 版的重写。
 *
 * <p><b>它在整条链上的位置</b>：</p>
 * <pre>
 * WebDisplays  ──依赖──▶  com.cinemamod.mcef.MCEFBrowser
 *                              └──extends──▶  CefBrowserOsr（本类）
 *                                                  └── JNI ──▶ CefriumBrowser
 * </pre>
 *
 * <p>JCEF 原本的实现会调用 libcef 的 C++ 接口；Android 上换成 Cefrium 的 JNI。
 * 本类负责保存<b>渲染回调</b>（{@link #onPaint}、{@link #onPopupShow}、
 * {@link #onPopupSize}）所需的公共状态，以及把输入事件、JS 执行、尺寸变更
 * 交给子类实现。</p>
 *
 * <p>设计取舍：这里<b>不</b>按 JCEF 那样把 onPaint 做成内部回调链，因为 Cefrium 的
 * 帧分发方式是 {@code CefriumBrowser.setRenderHandler(handler)} —— 一个浏览器一个
 * 回调。子类（MCEFBrowser）在构造时注册该回调，回调里再调用本类的
 * {@link #onPaint}，从而保持与 JCEF 相同的供 WebDisplays 覆写的入口。</p>
 */
public abstract class CefBrowserOsr implements CefBrowser {

    /** 主 frame 对象；懒创建，因为构造期 Cefrium 浏览器还没建好。 */
    protected CefFrame mainFrame;

    /** JS 查询处理器表。WebDisplays 通过 {@link #getQueryHandlers()} 往里塞。 */
    protected final HashMap<String, Object> queryHandlers = new HashMap<>();

    /** 浏览器标识；Android 单窗口模型下固定 0。 */
    protected int identifier = 0;

    /** 是否已请求关闭。 */
    protected volatile boolean closing = false;

    /** 是否允许关闭（JCEF 用来卡住关闭流程）。这里只记录状态。 */
    protected volatile boolean closeAllowed = false;

    /**
     * 是否启用透明背景。
     *
     * <p>WebDisplays 创建屏幕时传 true，希望网页的透明区域能露出后面的方块。
     * Cefrium 里对应 {@code setBackgroundColor(0)}，且<b>必须在首次 loadUrl 之前</b>
     * 调用，否则会抛 IllegalStateException。</p>
     */
    protected final boolean transparent;

    protected CefBrowserOsr(boolean transparent) {
        this.transparent = transparent;
    }

    // ── 渲染回调 ────────────────────────────────────────────────────────────
    // 由子类在 CefriumRenderHandler 里转发进来。签名刻意保持 JCEF 原样，
    // 这样 WebDisplays 里对 MCEFBrowser.onPaint 的覆写/调用都不需要改。

    /**
     * 一帧渲染完成。
     *
     * @param browser   发起渲染的浏览器（即 this）
     * @param popup     是否是弹出层（下拉框、select 等）
     * @param dirtyRects 脏区域
     * @param buffer    BGRA 像素，尺寸为 width*height*4
     * @param width     像素宽
     * @param height    像素高
     */
    public abstract void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                                 ByteBuffer buffer, int width, int height);

    /** 弹出层显示状态变化。 */
    public abstract void onPopupShow(CefBrowser browser, boolean show);

    /** 弹出层尺寸变化。 */
    public abstract void onPopupSize(CefBrowser browser, Rectangle size);

    /** 地址栏等浏览器控件是否被使用。Android 没有浏览器控件，恒 false。 */
    public boolean usingBrowserControls() {
        return false;
    }

    /** 是否允许关闭。 */
    public boolean isCloseAllowed() {
        return closeAllowed;
    }

    // ── CefBrowser 的通用实现 ──────────────────────────────────────────────

    @Override
    public int getIdentifier() {
        return identifier;
    }

    @Override
    public CefFrame getMainFrame() {
        // 懒创建：构造期 Cefrium 浏览器尚未建立，此时取 frame 没有意义。
        if (mainFrame == null) {
            mainFrame = new CefOsrFrame(this);
        }
        return mainFrame;
    }

    @Override
    public boolean isClosing() {
        return closing;
    }

    @Override
    public void setCloseAllowed() {
        this.closeAllowed = true;
    }

    @Override
    public HashMap<String, Object> getQueryHandlers() {
        return queryHandlers;
    }

    // ── 主 frame 的最小实现 ─────────────────────────────────────────────────

    /**
     * 主 frame。Android 侧 Cefrium 只暴露主 frame，因此所有"frame 级"操作
     * 都直接转发到所属浏览器。
     */
    private static final class CefOsrFrame implements CefFrame {
        private final CefBrowserOsr browser;

        CefOsrFrame(CefBrowserOsr browser) {
            this.browser = browser;
        }

        @Override public long getIdentifier() { return 0L; }
        @Override public boolean isMain() { return true; }
        @Override public boolean isFocused() { return true; }
        @Override public String getName() { return ""; }
        @Override public String getURL() { return browser.getURL(); }

        @Override
        public void loadURL(String url) {
            browser.loadURL(url);
        }

        @Override
        public void executeJavaScript(String code, String url, int line) {
            browser.executeJavaScript(code, url, line);
        }

        @Override
        public CefBrowser getBrowser() {
            return browser;
        }
    }
}
