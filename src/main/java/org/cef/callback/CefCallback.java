package org.cef.callback;

/**
 * 异步操作的结果回调（JCEF 里用于 GetAuthCredentials、RunFileDialog 等）。
 *
 * <p>Android 侧 Cefrium 的对应能力有限，这个接口主要为了让消费方代码能编译、
 * 且在未来接上异步结果时不用改签名。</p>
 */
public interface CefCallback {

    /** 继续已暂停的请求。 */
    void Continue();

    /** 取消已暂停的请求。 */
    void cancel();
}
