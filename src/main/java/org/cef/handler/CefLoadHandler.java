package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;

/**
 * 页面加载事件回调。
 *
 * <p>Android 侧由 Cefrium 的 {@code setOnLoadingStateChangedListener} 与
 * {@code setOnUrlChangedListener} 驱动：状态翻转时调 onLoadingStateChange，
 * URL 变化时调 onAddressChange，加载完成后调 onLoadEnd。</p>
 */
public interface CefLoadHandler {

    /** 加载状态变化。 */
    void onLoadingStateChange(CefBrowser browser, boolean isLoading,
                              boolean canGoBack, boolean canGoForward);

    /** 地址变化。 */
    void onAddressChange(CefBrowser browser, CefFrame frame, String url);

    /** 加载完成；httpStatusCode 未知时传 0。 */
    void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode);

    /** 加载出错。 */
    void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode,
                     String errorText, String failedUrl);

    /** 与 JCEF 一致的错误码集合（只列常用项，够判断即可）。 */
    enum ErrorCode {
        NONE,
        FAILED,
        ABORTED,
        INVALID_URL,
        CONNECTION_FAILED,
        CONNECTION_TIMED_OUT,
        CONNECTION_REFUSED,
        CONNECTION_RESET,
        CONNECTION_ABORTED,
        NAME_NOT_RESOLVED,
        INTERNET_DISCONNECTED
    }
}
