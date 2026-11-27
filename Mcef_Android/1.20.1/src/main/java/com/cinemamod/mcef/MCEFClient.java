/*
 * MCEF-Android (Forge 1.20.1)
 * Copyright (C) 2026 Rain_Clay and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * PROVENANCE: independent implementation, not a derivative work of
 * CinemaMod MCEF (LGPL-2.1). Names are kept identical only to satisfy
 * WebDisplays bytecode references; no upstream source, comments or
 * implementation logic is included. See README "与上游的关系".
 */

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
