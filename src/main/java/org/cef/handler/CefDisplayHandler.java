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

/**
 * 标题 / 光标 / 状态栏文本等显示相关回调。
 *
 * <p>Android 侧由 Cefrium 的 {@code setOnTitleChangedListener} 驱动 onTitleChange；
 * 其余（光标、状态栏）Cefrium 未暴露，保留空实现即可 —— WebDisplays 注册了
 * 这些 handler 但只关心标题。</p>
 */
public interface CefDisplayHandler {

    /** 标题变化。 */
    void onTitleChange(CefBrowser browser, String title);

    /** 状态栏文本变化。Cefrium 未暴露，默认空实现。 */
    default void onStatusMessage(CefBrowser browser, String value) {
    }

    /** 控制台消息。Cefrium 未暴露，默认空实现。 */
    default boolean onConsoleMessage(CefBrowser browser, ConsoleMessageLevel level, String message, String sourceId, int line) {
        return false;
    }

    /** 控制台消息级别。 */
    enum ConsoleMessageLevel {
        VERBOSE, INFO, WARNING, ERROR
    }
}
