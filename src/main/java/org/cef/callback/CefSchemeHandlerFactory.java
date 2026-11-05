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

package org.cef.callback;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefResourceHandler;
import org.cef.network.CefRequest;

/**
 * 自定义协议（scheme）的处理器工厂。
 *
 * <p>WebDisplays 用它注册 {@code webdisplays://} 协议。
 * <b>Android 限制</b>：Cefrium 的请求拦截只能「放行/拦截」，不能注入自定义响应体，
 * 所以本工厂在 Android 上不会被真正调用。保留接口是为了让消费方通过链接检查。</p>
 */
public interface CefSchemeHandlerFactory {

    CefResourceHandler create(CefBrowser browser, CefFrame frame,
                              String schemeName, CefRequest request);
}
