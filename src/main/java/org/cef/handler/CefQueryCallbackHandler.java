package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;

/**
 * JS 查询处理器（{@code window.cefQuery} 的服务端）。
 *
 * <p>WebDisplays 用它实现 "取焦点元素位置"、"取指针锁元素" 等查询：
 * 页面里调 {@code cefQuery({request, onSuccess, onFailure})}，
 * 这里收到 request、算完结果后调 {@link CefQueryCallback#success} 回传。</p>
 *
 * <p>Android 侧接法：{@code CefriumBrowser.setQueryHandler(policy, onQuery)}，
 * 其中 onQuery 的签名是 {@code (long queryId, String request, String origin, QueryCallback cb)}。
 * 桥接层负责把它包装成本接口的 {@link #onQuery}。</p>
 */
public interface CefQueryCallbackHandler {

    /**
     * @param browser  发起查询的浏览器
     * @param frame    发起查询的 frame
     * @param queryId  查询标识（回填时原样带回）
     * @param request  页面传入的 request 字符串
     * @param callback 结果回调
     * @return true 表示已处理（可能异步），false 表示不处理（走默认失败）
     */
    boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                    String request, boolean persistent, CefQueryCallback callback);

    /** 查询被取消（页面提前离开等）。 */
    default void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
    }
}
