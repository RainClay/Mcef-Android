package org.cef.handler;

import org.cef.callback.CefCallback;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

/**
 * 资源拦截处理器（自定义协议、离线资源等）。
 *
 * <p><b>Android 限制</b>：Cefrium 的 {@code setOnRequestInterceptedListener}
 * 只能决定"是否拦截"和"改成哪个 URL"，<b>不能</b>返回自定义响应体。
 * 因此本接口在 Android 上只有 {@link #processRequest} 的返回值有意义：
 * 返回 true = 拦下该请求（页面看到加载失败），false = 放行。</p>
 *
 * <p>{@link #getResponseHeaders} / {@link #readResponse} 保留是为了 API 兼容，
 * 在 Android 上不会被调用 —— 桥接层不会去调它们，因为调了也只能拿到占位数据。</p>
 */
public interface CefResourceHandler {

    /** 是否要处理该请求。返回 true 表示由本 handler 负责（Android 上等于拦截）。 */
    boolean processRequest(CefRequest request, CefCallback callback);

    /** 回填响应头。Android 上不生效。 */
    void getResponseHeaders(CefResponse response, IntRefAccessor responseLength, StringRefAccessor redirectUrl);

    /** 读取响应体。Android 上不生效。 */
    boolean readResponse(byte[] dataOut, int bytesToRead, IntRefAccessor bytesRead, CefCallback callback);

    /** 取消。 */
    void cancel();

    /** 出参适配：JCEF 用 {@code org.cef.misc.IntRef}，这里用同样的语义但避免循环依赖。 */
    interface IntRefAccessor {
        void set(int value);
    }

    /** 出参适配：JCEF 用 {@code org.cef.misc.StringRef}。 */
    interface StringRefAccessor {
        void set(String value);
    }
}
