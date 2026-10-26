package org.cef.callback;

/**
 * JS 查询（{@code window.cefQuery}）的响应回调。
 *
 * <p>Android 侧映射到 Cefrium 的 {@code CefriumBrowser.QueryCallback}：</p>
 * <pre>
 * Cefrium: callback.success(String)             → 本接口 success(String)
 * Cefrium: callback.failure(int, String)        → 本接口 failure(int, String)
 * </pre>
 *
 * <p>注意 {@code query_id} 在 Cefrium 侧是由 SDK 分配的（{@code onQuery} 的第一个参数），
 * 消费方拿到的 queryId 只是透传，回填时应当用 SDK 给的那个，否则页面收不到结果。</p>
 */
public interface CefQueryCallback {

    /** 成功，把 response 回传给页面的 onSuccess。 */
    void success(String response);

    /** 失败，把 errorCode / errorMessage 回传给页面的 onFailure。 */
    void failure(int errorCode, String errorMessage);
}
