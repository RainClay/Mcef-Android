package org.cef;

import android.content.Context;

import com.cefrium.Cefrium;

/**
 * CefApp —— JCEF 里的进程级入口，这里是 Android 版。
 *
 * <p><b>与 JCEF 的本质差别</b>：JCEF 里 {@code CefApp.getInstance()} 会去加载
 * libcef 并启动 C++ 侧的消息循环；Android 上这套由 Cefrium SDK 自己完成
 * （{@code CefriumInitProvider} 会在应用启动时自动初始化），
 * 所以本类<b>不启动引擎</b>，只提供一个"引擎是否就绪"的门面。</p>
 *
 * <p>用法（与 WebDisplays 里的一致）：</p>
 * <pre>
 * CefApp app = CefApp.getInstance(settings);   // 幂等
 * CefClient client = app.createClient();
 * </pre>
 *
 * <p>初始化时机：必须保证 {@link Cefrium#initialize(Context)} 已被调用过。
 * 在本项目里由 {@code MCEFAndroid.initialize(Context)} 负责 —— 消费方应先调它。</p>
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
     * 引擎是否已初始化。
     *
     * <p>对应 Cefrium 的 {@code Cefrium.isInitialized()}。返回 false 时创建浏览器
     * 会一直失败，所以调用方应在此处等待或提示，而不是继续往下建 browser。</p>
     */
    public boolean isInitialized() {
        return Cefrium.isInitialized();
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
     * 释放全局资源。
     *
     * <p>Android 上 Cefrium 的关闭由进程生命周期决定，这里只清掉单例引用，
     * 不调 {@code Cefrium.shutdown()} —— 那会让同进程其它浏览器一起失效。</p>
     */
    public void dispose() {
        synchronized (CefApp.class) {
            instance = null;
        }
    }
}
