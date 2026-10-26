package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;

/**
 * JS 查询处理器（{@code window.cefQuery} 的服务端）。
 *
 * <p>JCEF 里这个接口由 {@code CefMessageRouter} 持有，通过
 * {@code client.addMessageRouter(CefMessageRouter.create(handler))} 注册。
 * 本项目的 {@link CefMessageRouterHandlerAdapter} 实现它，WebDisplays 的
 * {@code WDRouter} 又继承那个 Adapter。</p>
 *
 * <p>继承 {@link CefQueryCallbackHandler}：本项目的桥接层只认后者，
 * 这样注册进来的 handler 不需要再包一层。</p>
 */
public interface CefMessageRouterHandler extends CefQueryCallbackHandler {

    @Override
    boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                    String request, boolean persistent, CefQueryCallback callback);
}
