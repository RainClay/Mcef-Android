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
 * 查询处理器的适配基类：把 4 参数（无 frame / 无 persistent）的简化写法
 * 桥接到 6 参数接口。
 *
 * <p>JCEF 的 {@code CefMessageRouterHandlerAdapter} 就是这个角色，WebDisplays
 * 直接继承它并覆写 {@code onQuery(CefBrowser, CefFrame, long, String, boolean, CefQueryCallback)}。
 * 我们把同名类放在同一包路径下，保证 WebDisplays 的 import 不用改。</p>
 */
public abstract class CefMessageRouterHandlerAdapter implements CefMessageRouterHandler {

    @Override
    public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                           String request, boolean persistent, CefQueryCallback callback) {
        return onQuery(browser, frame, queryId, request, callback);
    }

    /**
     * 简化版：不带 persistent 参数。
     *
     * <p>子类通常覆写这个方法。默认返回 false，表示不处理 —— 与 JCEF 的
     * Adapter 行为一致（不处理时页面收到失败回调，而不是静默挂起）。</p>
     */
    public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                           String request, CefQueryCallback callback) {
        return false;
    }
}
