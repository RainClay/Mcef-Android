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
 * <p>WebDisplays 用它实现 "取焦点元素位置"、"取指针锁元素" 等查询：
 * 页面里调 {@code cefQuery({request, onSuccess, onFailure})}，
 * 这里收到 request、算完结果后调 {@link CefQueryCallback#success} 回传。</p>
 *
 * <p>调用链（2.3.0 起接通）：</p>
 * <pre>
 * 页面 cefQuery(request)
 *   └── host: CefriumBrowser.setQueryHandler（anyOrigin 策略）
 *         └── IPC {"event":"query", queryId, request, origin}
 *               └── native: mcef_host_push_query → 入队
 *                     └── MCEFBrowser.tick() → McefNative.takeQuery(handle)
 *                           └── 本接口的 onQuery(...)          ← 你在这里
 *                                 └── callback.success(result)
 *                                       └── IPC {"cmd":"queryReply"}
 *                                             └── host 调 Cefrium 真正的 callback
 * </pre>
 *
 * <p>必须绕回 host 的原因：只有 host 进程持有 Cefrium 的回调实例，
 * MC 侧算出结果也没法直接答复页面。</p>
 *
 * <p>分发实现在 {@code MCEFBrowser.dispatchPendingQueries()}：每帧最多取 8 条，
 * 按注册顺序遍历，第一个返回 {@code true} 的处理器算认领；全都不认领时
 * 立刻按 {@code ERR_NOT_FOUND} 回填，免得页面干等到超时。</p>
 */
public interface CefQueryCallbackHandler {

    /**
     * @param browser  发起查询的浏览器
     * @param frame    发起查询的 frame
     * @param queryId  查询标识（回填时原样带回）
     * @param request  页面传入的 request 字符串
     * @param callback 结果回调
     * @return true 表示已处理（可能异步），false 表示不处理（走默认失败）
     */
    boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                    String request, boolean persistent, CefQueryCallback callback);

    /** 查询被取消（页面提前离开等）。 */
    default void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
    }
}
