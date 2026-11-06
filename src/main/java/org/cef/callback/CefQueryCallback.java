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

/**
 * JS 查询（{@code window.cefQuery}）的响应回调。
 *
 * <p>形状取自 Cefrium 的 {@code CefriumBrowser.QueryCallback}：</p>
 * <pre>
 * Cefrium: callback.success(String)             → 本接口 success(String)
 * Cefrium: callback.failure(int, String)        → 本接口 failure(int, String)
 * </pre>
 *
 * <p><b>但 Cefrium 那边的回调在宿主进程里，MC 侧拿不到它的实例。</b>
 * host 目前直接 {@code callback.success("ok")} 就算完事，真正的数据是用
 * IPC 的 {@code {"event":"dom"}} 单独回传的。所以本接口在 MC 侧没有生产者 ——
 * 见 {@link org.cef.handler.CefQueryCallbackHandler} 的类注释。</p>
 */
public interface CefQueryCallback {

    /** 成功，把 response 回传给页面的 onSuccess。 */
    void success(String response);

    /** 失败，把 errorCode / errorMessage 回传给页面的 onFailure。 */
    void failure(int errorCode, String errorMessage);
}
