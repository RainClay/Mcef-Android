package org.cef.handler;

/**
 * JS 查询的配置（查询函数名、取消函数名）。
 *
 * <p>JCEF 默认用 {@code cefQuery} / {@code cefQueryCancel}，
 * WebDisplays 的页面脚本也是这么调的，所以默认值够用。
 * 保留这个类是为了让 {@code CefMessageRouter.create(config, handler)} 的
 * 重载存在（消费方可能按 JCEF 的写法传）。</p>
 */
public class CefMessageRouterConfig {

    /** 页面里发起查询的函数名。 */
    public String jsQueryFunction = "cefQuery";

    /** 页面里取消查询的函数名。 */
    public String jsQueryCancelFunction = "cefQueryCancel";

    public CefMessageRouterConfig() {
    }

    public CefMessageRouterConfig(String jsQueryFunction, String jsQueryCancelFunction) {
        if (jsQueryFunction != null) this.jsQueryFunction = jsQueryFunction;
        if (jsQueryCancelFunction != null) this.jsQueryCancelFunction = jsQueryCancelFunction;
    }
}
