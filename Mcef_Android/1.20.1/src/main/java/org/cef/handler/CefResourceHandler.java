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

import org.cef.callback.CefCallback;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

/**
 * 资源拦截处理器（自定义协议、离线资源等）。
 *
 * <p><b>签名必须与 JCEF 逐字一致</b>：消费方（WebDisplays 的 {@code WDScheme}）
 * 覆写的是
 * {@code getResponseHeaders(CefResponse, IntRef, StringRef)} 和
 * {@code readResponse(byte[], int, IntRef, CefCallback)} ——
 * 这里如果用自定义的出参类型，运行期就是 {@code NoSuchMethodError}。</p>
 *
 * <p><b>Android 限制</b>：Cefrium 的请求拦截只能决定「是否拦截」，
 * 不能返回自定义响应体。所以 {@link #getResponseHeaders} /
 * {@link #readResponse} 在当前实现里<b>不会被调用</b> ——
 * 保留是为了让消费方代码能编译、并通过 JVM 的链接检查。</p>
 */
public interface CefResourceHandler {

    /**
     * 是否要处理该请求。
     *
     * @return true 表示由本 handler 负责。Android 侧等价于「拦截这个请求」。
     */
    boolean processRequest(CefRequest request, CefCallback callback);

    /**
     * 回填响应头。
     *
     * @param response      响应对象，设置 mime/status 等
     * @param responseLength 出参：响应体总长度
     * @param redirectUrl    出参：重定向地址（null 表示不重定向）
     */
    void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl);

    /**
     * 读取响应体。
     *
     * @param dataOut     输出缓冲
     * @param bytesToRead 本次最多读多少字节
     * @param bytesRead   出参：实际读了多少
     * @return true 表示还有后续数据
     */
    boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback);

    /** 取消。 */
    void cancel();
}
