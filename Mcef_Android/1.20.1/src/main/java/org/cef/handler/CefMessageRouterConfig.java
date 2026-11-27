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

/**
 * JS 查询的配置（查询函数名、取消函数名）。
 *
 * <p>JCEF 默认用 {@code cefQuery} / {@code cefQueryCancel}，
 * WebDisplays 的页面脚本也是这么调的，所以默认值够用。
 * 保留这个类是为了让 {@code CefMessageRouter.create(config, handler)} 的
 * 重载存在（消费方可能按 JCEF 的写法传）。</p>
 */
public class CefMessageRouterConfig {

    /** 页面里发起查询的函数名。 */
    public String jsQueryFunction = "cefQuery";

    /** 页面里取消查询的函数名。 */
    public String jsQueryCancelFunction = "cefQueryCancel";

    public CefMessageRouterConfig() {
    }

    public CefMessageRouterConfig(String jsQueryFunction, String jsQueryCancelFunction) {
        if (jsQueryFunction != null) this.jsQueryFunction = jsQueryFunction;
        if (jsQueryCancelFunction != null) this.jsQueryCancelFunction = jsQueryCancelFunction;
    }
}
