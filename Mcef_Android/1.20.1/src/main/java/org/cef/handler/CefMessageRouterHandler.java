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
 * PROVENANCE: the API shape follows JCEF / Chromium Embedded Framework
 * (BSD-3-Clause). This file is an independent Android implementation and
 * contains no JCEF source code. Class, package and method names are kept
 * identical only to satisfy bytecode references from consumers such as
 * WebDisplays.
 */

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;

/**
 * JS 查询处理器（{@code window.cefQuery} 的服务端）。
 *
 * <p>JCEF 里这个接口由 {@code CefMessageRouter} 持有，通过
 * {@code client.addMessageRouter(CefMessageRouter.create(handler))} 注册。
 * 本项目的 {@link CefMessageRouterHandlerAdapter} 实现它，WebDisplays 的
 * {@code WDRouter} 又继承那个 Adapter。</p>
 *
 * <p>继承 {@link CefQueryCallbackHandler}：本项目的桥接层只认后者，
 * 这样注册进来的 handler 不需要再包一层。</p>
 */
public interface CefMessageRouterHandler extends CefQueryCallbackHandler {

    @Override
    boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                    String request, boolean persistent, CefQueryCallback callback);
}
