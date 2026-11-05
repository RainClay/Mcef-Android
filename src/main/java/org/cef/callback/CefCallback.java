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
 * 异步操作的结果回调（JCEF 里用于 GetAuthCredentials、RunFileDialog 等）。
 *
 * <p>Android 侧 Cefrium 的对应能力有限，这个接口主要为了让消费方代码能编译、
 * 且在未来接上异步结果时不用改签名。</p>
 */
public interface CefCallback {

    /** 继续已暂停的请求。 */
    void Continue();

    /** 取消已暂停的请求。 */
    void cancel();
}
