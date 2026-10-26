package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;

/**
 * 查询处理器的适配基类：把 4 参数（无 frame / 无 persistent）的简化写法
 * 桥接到 6 参数接口。
 *
 * <p>JCEF 的 {@code CefMessageRouterHandlerAdapter} 就是这个角色，WebDisplays
 * 直接继承它并覆写 {@code onQuery(CefBrowser, CefFrame, long, String, boolean, CefQueryCallback)}。
 * 我们把同名类放在同一包路径下，保证 WebDisplays 的 import 不用改。</p>
 */
public abstract class CefMessageRouterHandlerAdapter implements CefMessageRouterHandler {

    @Override
    public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                           String request, boolean persistent, CefQueryCallback callback) {
        return onQuery(browser, frame, queryId, request, callback);
    }

    /**
     * 简化版：不带 persistent 参数。
     *
     * <p>子类通常覆写这个方法。默认返回 false，表示不处理 —— 与 JCEF 的
     * Adapter 行为一致（不处理时页面收到失败回调，而不是静默挂起）。</p>
     */
    public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                           String request, CefQueryCallback callback) {
        return false;
    }
}
