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

package org.cef.network;

import java.util.HashMap;
import java.util.Map;

/**
 * 网络请求表示。WebDisplays 只在资源拦截里用到它读取 URL / 方法 / 头。
 *
 * <p>Android 侧 Cefrium 的请求拦截回调只给 URL（{@code setOnRequestInterceptedListener}），
 * 因此这里能填的字段有限：URL 一定有，method/headers 视回调信息而定，
 * 取不到时保持空值而不是臆造。</p>
 */
public class CefRequest {

    /** 请求方法。 */
    public static final int RT_GET = 0;
    public static final int RT_POST = 1;

    /** 资源类型（JCEF 的 RT_* 语义）。 */
    public static final int RT_MAIN_FRAME = 0;
    public static final int RT_SUB_FRAME = 1;
    public static final int RT_STYLESHEET = 2;
    public static final int RT_SCRIPT = 3;
    public static final int RT_IMAGE = 4;
    public static final int RT_FONT_RESOURCE = 5;
    public static final int RT_SUB_RESOURCE = 6;
    public static final int RT_OBJECT = 7;
    public static final int RT_MEDIA = 8;
    public static final int RT_WORKER = 9;
    public static final int RT_SHARED_WORKER = 10;
    public static final int RT_PREFETCH = 11;
    public static final int RT_FAVICON = 12;
    public static final int RT_XHR = 13;
    public static final int RT_PING = 14;
    public static final int RT_SERVICE_WORKER = 15;
    public static final int RT_CSP_REPORT = 16;
    public static final int RT_PLUGIN_RESOURCE = 17;
    public static final int RT_NAVIGATION_RESOURCE = 18;

    private String url = "";
    private String method = "GET";
    private int resourceType = RT_MAIN_FRAME;
    private final Map<String, String> headers = new HashMap<>();

    public CefRequest() {
    }

    public CefRequest(String url, String method, int resourceType) {
        this.url = url == null ? "" : url;
        this.method = method == null ? "GET" : method;
        this.resourceType = resourceType;
    }

    public String getURL() {
        return url;
    }

    public void setURL(String url) {
        this.url = url == null ? "" : url;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method == null ? "GET" : method;
    }

    public int getResourceType() {
        return resourceType;
    }

    public void setResourceType(int type) {
        this.resourceType = type;
    }

    public Map<String, String> getHeaderMap() {
        return headers;
    }

    public void setHeaderByName(String name, String value, boolean overwrite) {
        if (name == null) return;
        if (!overwrite && headers.containsKey(name)) return;
        headers.put(name, value == null ? "" : value);
    }
}
