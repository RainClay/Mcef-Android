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
