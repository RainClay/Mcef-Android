package org.cef.callback;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefResourceHandler;
import org.cef.network.CefRequest;

/**
 * 自定义协议（scheme）的处理器工厂。
 *
 * <p>WebDisplays 用它注册 {@code webdisplays://} 协议。
 * <b>Android 限制</b>：Cefrium 的请求拦截只能「放行/拦截」，不能注入自定义响应体，
 * 所以本工厂在 Android 上不会被真正调用。保留接口是为了让消费方通过链接检查。</p>
 */
public interface CefSchemeHandlerFactory {

    CefResourceHandler create(CefBrowser browser, CefFrame frame,
                              String schemeName, CefRequest request);
}
