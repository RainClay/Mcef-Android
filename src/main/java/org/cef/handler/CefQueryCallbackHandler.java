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
 * <p><b>本接口目前不会被调用</b>。实际数据流是另一条路：</p>
 * <pre>
 * 页面 window.cefriumQuery(request)
 *   └── host: CefriumBrowser.setQueryHandler（anyOrigin 策略）
 *         └── sendDom(id, request)  →  IPC {"event":"dom", ...}
 *               └── native: mcef_host_set_dom_json → 存进 b->dom_json
 *                     └── McefNative.getDomJson(handle)   ← 目前无人调用
 * </pre>
 *
 * <p>也就是说 host 已经被注册成「把 request 当成 DOM 快照回来」，而
 * {@link org.cef.CefClient#getQueryHandlers()} 里注册的处理器从来没有被分发。
 * 要让本接口生效，需要在 {@code MCEFBrowser} 里加一条「收到 dom 事件 → 找处理器
 * → 调 {@link #onQuery}」的分发逻辑。保留本接口是为了让消费方代码编译通过。</p>
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
