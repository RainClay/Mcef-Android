package org.cef;

import com.cinemamod.mcef.nativebridge.McefNative;

/**
 * CefApp —— JCEF 里的进程级入口，这里是 Android 版门面。
 *
 * <p><b>与 JCEF 的本质差别</b>：JCEF 里 {@code CefApp.getInstance()} 会加载 libcef
 * 并启动 C++ 侧消息循环；本实现里引擎在<b>启动器的 .cefhost 进程</b>中运行，
 * MC 侧只通过 {@code libmcef.so} 的 IPC 客户端通信。所以本类<b>不启动引擎</b>，
 * 只提供「引擎是否就绪」的门面。</p>
 *
 * <p>用法（与 WebDisplays 里一致）：</p>
 * <pre>
 * CefApp app = CefApp.getInstance(settings);   // 幂等
 * CefClient client = app.createClient();
 * </pre>
 *
 * <p>真正让桥连上需要在更早的地方调
 * {@code com.cinemamod.mcef.MCEF.initialize(...)}（由 MCEF 的初始化路径完成）。</p>
 */
public class CefApp {

    private static volatile CefApp instance;

    private final CefSettings settings;

    private CefApp(CefSettings settings) {
        this.settings = settings == null ? new CefSettings() : settings;
    }

    /**
     * 取全局实例；首次调用时创建。
     *
     * <p>幂等：重复调用返回同一实例，忽略后续传入的 settings —— 与 JCEF 行为一致，
     * 避免运行期改全局配置带来的不确定状态。</p>
     */
    public static synchronized CefApp getInstance(CefSettings settings) {
        if (instance == null) {
            instance = new CefApp(settings);
        }
        return instance;
    }

    /** 取全局实例（必须先有过一次带参数的调用）。 */
    public static CefApp getInstance() {
        return getInstance(null);
    }

    /**
     * 引擎是否已就绪。
     *
     * <p>对应 {@code McefNative.isInitialized()}，即 IPC 桥是否已连上启动器的
     * CEF 宿主。返回 false 时创建浏览器会失败，所以调用方应在此判断。</p>
     */
    public boolean isInitialized() {
        try {
            return McefNative.isInitialized();
        } catch (Throwable t) {
            // so 没加载起来时 native 方法不可用，按未就绪处理。
            return false;
        }
    }

    /** 创建客户端（每个浏览器一个，内部持有若干 handler）。 */
    public CefClient createClient() {
        return new CefClient();
    }

    /** 当前设置。 */
    public CefSettings getSettings() {
        return settings;
    }

    /**
     * 注册自定义协议处理器。
     *
     * <p>WebDisplays 用它注册 {@code webdisplays://}：
     * {@code MCEF.getApp().getHandle().registerSchemeHandlerFactory("webdisplays", "", factory)}</p>
     *
     * <p><b>Android 上是 no-op</b>：Cefrium 的拦截层只能放行/拦截，不能注入自定义
     * 响应体。返回 true 表示「已记录」而非「已生效」—— 返回 false 会让消费方以为
     * 注册失败而提前 return，反而影响它后续的初始化逻辑。</p>
     */
    public boolean registerSchemeHandlerFactory(String schemeName, String domainName,
                                                org.cef.callback.CefSchemeHandlerFactory factory) {
        if (schemeName == null || schemeName.isEmpty()) return false;
        schemeHandlerFactories.put(schemeName, factory);
        return true;
    }

    /** 取消注册。 */
    public boolean unregisterSchemeHandlerFactory(String schemeName, String domainName) {
        return schemeHandlerFactories.remove(schemeName) != null;
    }

    /** 已注册的协议工厂（诊断用）。 */
    public java.util.Map<String, org.cef.callback.CefSchemeHandlerFactory> getSchemeHandlerFactories() {
        return schemeHandlerFactories;
    }

    private final java.util.Map<String, org.cef.callback.CefSchemeHandlerFactory> schemeHandlerFactories =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 释放全局资源。
     *
     * <p>只清掉单例引用，不调 {@code McefNative.shutdown()} —— 那会断开 IPC，
     * 让同进程里其它浏览器一起失效。</p>
     */
    public void dispose() {
        synchronized (CefApp.class) {
            instance = null;
        }
    }
}
