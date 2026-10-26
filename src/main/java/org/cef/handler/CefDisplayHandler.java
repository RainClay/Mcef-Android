package org.cef.handler;

import org.cef.browser.CefBrowser;

/**
 * 标题 / 光标 / 状态栏文本等显示相关回调。
 *
 * <p>Android 侧由 Cefrium 的 {@code setOnTitleChangedListener} 驱动 onTitleChange；
 * 其余（光标、状态栏）Cefrium 未暴露，保留空实现即可 —— WebDisplays 注册了
 * 这些 handler 但只关心标题。</p>
 */
public interface CefDisplayHandler {

    /** 标题变化。 */
    void onTitleChange(CefBrowser browser, String title);

    /** 状态栏文本变化。Cefrium 未暴露，默认空实现。 */
    default void onStatusMessage(CefBrowser browser, String value) {
    }

    /** 控制台消息。Cefrium 未暴露，默认空实现。 */
    default boolean onConsoleMessage(CefBrowser browser, ConsoleMessageLevel level, String message, String sourceId, int line) {
        return false;
    }

    /** 控制台消息级别。 */
    enum ConsoleMessageLevel {
        VERBOSE, INFO, WARNING, ERROR
    }
}
