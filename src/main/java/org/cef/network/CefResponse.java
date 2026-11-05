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
 * 网络响应表示。WebDisplays 在资源拦截里用它回填内容。
 *
 * <p>Android 侧 Cefrium 的拦截能力只能"放行/拦截 + 替换 URL"，不能自定义响应体，
 * 所以这里设置的 status/mime/数据在 Android 上<b>不生效</b>。字段保留是为了让
 * 消费方代码能编译，且将来 Cefrium 若开放响应改写可以直接接上。</p>
 */
public class CefResponse {

    private int status = 200;
    private String statusText = "OK";
    private String mimeType = "text/html";
    private String charset = "utf-8";
    private final Map<String, String> headers = new HashMap<>();

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText == null ? "" : statusText;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType == null ? "" : mimeType;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset == null ? "" : charset;
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
