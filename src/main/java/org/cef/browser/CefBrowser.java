package org.cef.browser;

import java.awt.Rectangle;
import java.util.HashMap;

/**
 * JCEF 的 CefBrowser 接口——本项目是<b>为 Android 重写的兼容壳</b>。
 *
 * <p><b>为什么需要它</b>：WebDisplays 这类 mod 直接依赖 JCEF 的 Java 绑定（{@code org.cef.*}）。
 * 但 JCEF 的作用只是把 libcef 包一层 Java API，Android 上我们又不能跑 JCEF —— 我们用 JNI
 * 直接接 Cefrium（{@code com.cefrium.CefriumBrowser}）。所以这一层只保留 <b>API 形状</b>，
 * 实现全部转发给 Cefrium 后端（见 {@code com.cinemamod.mcef.MCEFBrowser}）。</p>
 *
 * <p><b>方法范围</b>：只声明消费方真正调用的成员，不做"JCEF 全集"。依据是实际 grep 出来的调用：
 * WebDisplays 用 loadURL / executeJavaScript / resize / close / isPageLoading 等 14 个，
 * MCEF 内部用 close / resize / setFocus / sendMouse* / sendKey* 等 13 个。
 * 多写的成员只会让人误以为已经支持。</p>
 */
public interface CefBrowser {

    /** 主 frame。 */
    CefFrame getMainFrame();

    /** 是否正在加载。 */
    boolean isPageLoading();

    /** 加载 URL。 */
    void loadURL(String url);

    /** 执行 JavaScript。{@code url} 与 {@code line} 仅用于日志定位，Android 侧忽略。 */
    void executeJavaScript(String code, String url, int line);

    /** 调整大小（OSR 的渲染目标尺寸）。 */
    void resize(int width, int height);

    /** 关闭浏览器；{@code force} 为 true 时不等页面确认。 */
    void close(boolean force);

    /** 是否已进入关闭流程。 */
    boolean isClosing();

    /** 设置焦点。触摸/鼠标事件前应置 true。 */
    void setFocus(boolean enable);

    /** 当前 URL。 */
    String getURL();

    /** 当前页面标题。 */
    String getTitle();

    /** 窗口标识；Android 单窗口模型下恒为 0。 */
    int getIdentifier();

    /**
     * 视图边界。JCEF 里是 AWT 组件的位置，Android 上没有 AWT，
     * 返回一个 0,0,width,height 的矩形供调用方读取尺寸即可。
     */
    Rectangle getBounds();

    /** 页面内查询字符串（JCEF 的 window.cefQuery），结果走回调。 */
    void sendQuery(String query, int queryId);

    /** 页面内查询，带请求载荷与结果回调。 */
    void sendQuery(String query, int queryId, String request, boolean persistent);

    /** 已注册的 JS 查询处理器（WebDisplays 的 queryHandlers() 依赖它）。 */
    HashMap<String, Object> getQueryHandlers();

    // ── 输入事件 ───────────────────────────────────────────────────────────
    // MCEF 的 MCEFBrowser 会把它们转给 CefriumBrowser 的 sendMouse* / sendKey*。

    void sendMouseMove(int x, int y, int modifiers, boolean leave);

    void sendMousePress(int x, int y, int button, int clickCount, int modifiers, boolean release);

    void sendMouseRelease(int x, int y, int button, int clickCount, int modifiers);

    void sendMouseWheel(int x, int y, int deltaX, int deltaY, int modifiers);

    void sendKeyPress(int keyCode, int modifiers, long scanCode, char character);

    void sendKeyRelease(int keyCode, int modifiers, long scanCode, char character);

    void sendKeyTyped(char character, int modifiers, long scanCode);

    // ── OSR 生命周期 ───────────────────────────────────────────────────────

    /** 立即创建浏览器（JCEF 的延迟创建语义，Android 侧等价于"开始加载"）。 */
    void createImmediately();

    /** 允许关闭（JCEF 里用于接 onBeforeClose，Android 上仅记录状态）。 */
    void setCloseAllowed();

    /** 设置光标类型变更监听。 */
    void setCursorChangeListener(Object listener);
}
