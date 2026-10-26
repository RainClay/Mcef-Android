package org.cef;

import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefQueryCallbackHandler;
import org.cef.handler.CefResourceHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * CefClient —— 每个浏览器一个的 handler 容器。
 *
 * <p>JCEF 里 handler 挂在 client 上，创建 browser 时把 client 传进去。
 * 这里保持同样的用法，但<b>不在这里分发事件</b>：事件由 MCEFBrowser 内部
 * 从 Cefrium 的回调转进来后，遍历本 client 上注册的 handler 逐个通知。
 * 这样与 JCEF 的语义一致（一个 client 可以挂多个 handler）。</p>
 */
public class CefClient {

    private final List<CefLoadHandler> loadHandlers = new ArrayList<>();
    private final List<CefDisplayHandler> displayHandlers = new ArrayList<>();
    private final List<CefQueryCallbackHandler> queryHandlers = new ArrayList<>();
    private final List<CefResourceHandler> resourceHandlers = new ArrayList<>();

    /** 添加页面加载回调。 */
    public void addLoadHandler(CefLoadHandler handler) {
        if (handler != null && !loadHandlers.contains(handler)) {
            loadHandlers.add(handler);
        }
    }

    /** 移除页面加载回调。 */
    public boolean removeLoadHandler(CefLoadHandler handler) {
        return loadHandlers.remove(handler);
    }

    /** 添加显示回调（标题等）。 */
    public void addDisplayHandler(CefDisplayHandler handler) {
        if (handler != null && !displayHandlers.contains(handler)) {
            displayHandlers.add(handler);
        }
    }

    /** 移除显示回调。 */
    public boolean removeDisplayHandler(CefDisplayHandler handler) {
        return displayHandlers.remove(handler);
    }

    /**
     * 添加 JS 查询处理器。
     *
     * <p>注意这个重载收的是 {@link CefMessageRouterHandlerAdapter}（WebDisplays 传的就是它）；
     * 内部同样存成 {@link CefQueryCallbackHandler}，因为 Adapter 实现了那个接口。</p>
     */
    public void addMessageRouterHandler(CefMessageRouterHandlerAdapter handler) {
        if (handler != null && !queryHandlers.contains(handler)) {
            queryHandlers.add(handler);
        }
    }

    /**
     * 添加消息路由器（JCEF 的注册方式）。
     *
     * <p>WebDisplays 的实际调用：
     * {@code client.getHandle().addMessageRouter(CefMessageRouter.create(WDRouter.INSTANCE))}。
     * 这里把路由器里的处理器取出来放进本 client 的查询列表 —— 桥接层只认列表。</p>
     */
    public void addMessageRouter(org.cef.browser.CefMessageRouter router) {
        if (router == null) return;
        CefQueryCallbackHandler h = router.getHandler();
        if (h != null && !queryHandlers.contains(h)) {
            queryHandlers.add(h);
        }
    }

    /** 移除消息路由器。 */
    public void removeMessageRouter(org.cef.browser.CefMessageRouter router) {
        if (router == null) return;
        CefQueryCallbackHandler h = router.getHandler();
        if (h != null) {
            queryHandlers.remove(h);
        }
    }

    /** 添加 JS 查询处理器（直接实现接口的写法）。 */
    public void addQueryHandler(CefQueryCallbackHandler handler) {
        if (handler != null && !queryHandlers.contains(handler)) {
            queryHandlers.add(handler);
        }
    }

    /** 移除 JS 查询处理器。 */
    public boolean removeMessageRouterHandler(CefQueryCallbackHandler handler) {
        return queryHandlers.remove(handler);
    }

    /** 添加资源拦截处理器。 */
    public void addResourceHandler(CefResourceHandler handler) {
        if (handler != null && !resourceHandlers.contains(handler)) {
            resourceHandlers.add(handler);
        }
    }

    /** 取已注册的加载回调（只读视图，供桥接层遍历）。 */
    public List<CefLoadHandler> getLoadHandlers() {
        return loadHandlers;
    }

    /** 取已注册的显示回调。 */
    public List<CefDisplayHandler> getDisplayHandlers() {
        return displayHandlers;
    }

    /** 取已注册的查询处理器。 */
    public List<CefQueryCallbackHandler> getQueryHandlers() {
        return queryHandlers;
    }

    /** 取已注册的资源处理器。 */
    public List<CefResourceHandler> getResourceHandlers() {
        return resourceHandlers;
    }
}
