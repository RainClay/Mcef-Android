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

/**
 * 页面加载事件回调。
 *
 * <p>Android 侧由 Cefrium 的 {@code setOnLoadingStateChangedListener} 与
 * {@code setOnUrlChangedListener} 驱动：状态翻转时调 onLoadingStateChange，
 * URL 变化时调 onAddressChange，加载完成后调 onLoadEnd。</p>
 */
public interface CefLoadHandler {

    /** 加载状态变化。 */
    void onLoadingStateChange(CefBrowser browser, boolean isLoading,
                              boolean canGoBack, boolean canGoForward);

    /** 地址变化。 */
    void onAddressChange(CefBrowser browser, CefFrame frame, String url);

    /** 加载完成；httpStatusCode 未知时传 0。 */
    void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode);

    /** 加载出错。 */
    void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode,
                     String errorText, String failedUrl);

    /** 与 JCEF 一致的错误码集合（只列常用项，够判断即可）。 */
    enum ErrorCode {
        NONE,
        FAILED,
        ABORTED,
        INVALID_URL,
        CONNECTION_FAILED,
        CONNECTION_TIMED_OUT,
        CONNECTION_REFUSED,
        CONNECTION_RESET,
        CONNECTION_ABORTED,
        NAME_NOT_RESOLVED,
        INTERNET_DISCONNECTED
    }
}
