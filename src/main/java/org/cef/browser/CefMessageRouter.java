package org.cef.browser;

import org.cef.handler.CefMessageRouterConfig;
import org.cef.handler.CefMessageRouterHandler;

/**
 * JS 查询路由器。
 *
 * <p><b>在 Android 侧它只是个容器</b>：真正把 {@code window.cefQuery} 接住的是
 * {@code libmcef.so} → IPC → 启动器里的 Cefrium（那边用
 * {@code CefriumBrowser.setQueryHandler} 注册）。这里保持 JCEF 的形状
 * （{@code create(...)} + {@code addHandler(...)}），让消费方代码不用改。</p>
 *
 * <p>注册路径：{@code client.addMessageRouter(CefMessageRouter.create(handler))}
 * → {@code CefClient} 把 handler 放进自己的查询处理器列表。</p>
 */
public class CefMessageRouter {

    private final CefMessageRouterConfig config;
    private volatile CefMessageRouterHandler handler;

    private CefMessageRouter(CefMessageRouterConfig config, CefMessageRouterHandler handler) {
        this.config = config == null ? new CefMessageRouterConfig() : config;
        this.handler = handler;
    }

    /** 用默认配置创建路由器。 */
    public static CefMessageRouter create(CefMessageRouterHandler handler) {
        return new CefMessageRouter(new CefMessageRouterConfig(), handler);
    }

    /** 带配置创建。配置在 Android 侧只记录，不影响实际查询函数名。 */
    public static CefMessageRouter create(CefMessageRouterConfig config, CefMessageRouterHandler handler) {
        return new CefMessageRouter(config, handler);
    }

    /** 当前持有的处理器。 */
    public CefMessageRouterHandler getHandler() {
        return handler;
    }

    /** 配置。 */
    public CefMessageRouterConfig getConfig() {
        return config;
    }

    /**
     * 追加处理器。
     *
     * <p>Android 侧一个路由器只留一个处理器（后注册的替换先注册的）——
     * 与 Cefrium 的 {@code setQueryHandler} 单槽语义一致。</p>
     */
    public boolean addHandler(CefMessageRouterHandler handler, boolean first) {
        if (handler == null) return false;
        this.handler = handler;
        return true;
    }

    /** 移除处理器。 */
    public boolean removeHandler(CefMessageRouterHandler handler) {
        if (this.handler == handler) {
            this.handler = null;
            return true;
        }
        return false;
    }

    /** 取消所有待处理查询。Android 侧由 IPC 层处理，这里无操作。 */
    public void cancelPending() {
        // no-op
    }
}
