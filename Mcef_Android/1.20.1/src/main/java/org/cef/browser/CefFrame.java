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

package org.cef.browser;

/**
 * CefFrame —— JCEF 里代表页面内的一个 frame。
 *
 * <p>Android 侧的 Cefrium 目前只把主 frame 暴露出来（OSR 模式下没有对子 frame 做区分），
 * 所以这里只保留最小形状：标识、URL、加载、执行脚本。</p>
 */
public interface CefFrame {

    /** frame 标识。主 frame 恒为 0。 */
    long getIdentifier();

    /** 是否主 frame。 */
    boolean isMain();

    /** 是否处于焦点。Android 侧只有主 frame，恒等于 {@link #isMain()}。 */
    boolean isFocused();

    /** frame 名称；主 frame 为空串。 */
    String getName();

    /** 当前 URL。 */
    String getURL();

    /** 在此 frame 内加载 URL。 */
    void loadURL(String url);

    /** 在此 frame 内执行 JavaScript。 */
    void executeJavaScript(String code, String url, int line);

    /** 所属浏览器。 */
    CefBrowser getBrowser();
}
