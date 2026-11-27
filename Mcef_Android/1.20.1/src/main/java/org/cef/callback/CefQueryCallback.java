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
 * <p><b>Cefrium 的回调实例在宿主进程里，MC 侧拿不到。</b>所以这里不是直接
 * 转发，而是每帧从 native 队列取查询后现造一个 —— 见
 * {@code MCEFBrowser.QueryCallback}。它的 {@code success/failure} 会把结果
 * 编码成 IPC {@code {"cmd":"queryReply"}} 发回 host，host 再调真正的 Cefrium
 * callback 答复页面。</p>
 *
 * <p>一条查询只能答复一次：Cefrium 那边重复答复会被忽略，所以实现里用
 * {@code AtomicBoolean} 挡住第二次调用。</p>
 */
public interface CefQueryCallback {

    /** 成功，把 response 回传给页面的 onSuccess。 */
    void success(String response);

    /** 失败，把 errorCode / errorMessage 回传给页面的 onFailure。 */
    void failure(int errorCode, String errorMessage);
}
