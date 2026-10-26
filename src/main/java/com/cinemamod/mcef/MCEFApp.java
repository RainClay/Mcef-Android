package com.cinemamod.mcef;

import org.cef.CefApp;

/**
 * MCEFApp —— 上游 MCEF 里的「应用」包装。
 *
 * <p>WebDisplays 的调用形状是
 * {@code MCEF.getApp().getHandle().registerSchemeHandlerFactory(...)}，
 * 即 MCEFApp 持有真正的 {@link CefApp}。</p>
 */
public class MCEFApp {

    private final CefApp handle;

    public MCEFApp(CefApp handle) {
        this.handle = handle == null ? CefApp.getInstance() : handle;
    }

    /** 内部的 {@link CefApp}。 */
    public CefApp getHandle() {
        return handle;
    }
}
