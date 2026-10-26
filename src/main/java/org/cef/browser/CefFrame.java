package org.cef.browser;

/**
 * CefFrame —— JCEF 里代表页面内的一个 frame。
 *
 * <p>Android 侧的 Cefrium 目前只把主 frame 暴露出来（OSR 模式下没有对子 frame 做区分），
 * 所以这里只保留最小形状：标识、URL、加载、执行脚本。</p>
 */
public interface CefFrame {

    /** frame 标识。主 frame 恒为 0。 */
    long getIdentifier();

    /** 是否主 frame。 */
    boolean isMain();

    /** 是否处于焦点。Android 侧只有主 frame，恒等于 {@link #isMain()}。 */
    boolean isFocused();

    /** frame 名称；主 frame 为空串。 */
    String getName();

    /** 当前 URL。 */
    String getURL();

    /** 在此 frame 内加载 URL。 */
    void loadURL(String url);

    /** 在此 frame 内执行 JavaScript。 */
    void executeJavaScript(String code, String url, int line);

    /** 所属浏览器。 */
    CefBrowser getBrowser();
}
