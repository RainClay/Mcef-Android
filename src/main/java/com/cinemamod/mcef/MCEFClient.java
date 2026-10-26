package com.cinemamod.mcef;

import org.cef.CefClient;

/**
 * MCEFClient —— 上游 MCEF 里的 "客户端"，本质是 {@link CefClient} 的持有者。
 *
 * <p>保留独立类是为了 WebDisplays 的构造签名 {@code WDClientBrowser(MCEFClient, ...)}
 * 不用改。所有 handler 注册都转发给内部的 {@link CefClient}。</p>
 */
public class MCEFClient {

    private final CefClient delegate;

    public MCEFClient(CefClient delegate) {
        this.delegate = delegate == null ? new CefClient() : delegate;
    }

    /** 内部真正的 CefClient。 */
    public CefClient getHandle() {
        return delegate;
    }

    // ── 委托方法：WebDisplays 直接调 MCEFClient 上的这些 ──────────────────

    /** 添加显示回调（标题等）。 */
    public void addDisplayHandler(org.cef.handler.CefDisplayHandler handler) {
        delegate.addDisplayHandler(handler);
    }

    /** 添加页面加载回调。 */
    public void addLoadHandler(org.cef.handler.CefLoadHandler handler) {
        delegate.addLoadHandler(handler);
    }

    /** 移除显示回调。 */
    public boolean removeDisplayHandler(org.cef.handler.CefDisplayHandler handler) {
        return delegate.removeDisplayHandler(handler);
    }

    /** 移除加载回调。 */
    public boolean removeLoadHandler(org.cef.handler.CefLoadHandler handler) {
        return delegate.removeLoadHandler(handler);
    }
}
