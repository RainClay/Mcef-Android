package com.cinemamod.mcef;

import android.content.Context;
import android.util.Log;

import com.cefrium.Cefrium;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;

/**
 * MCEF —— 库的入口，Android 版。
 *
 * <p><b>与上游 CinemaMod MCEF 的区别</b>：上游会自己下载、解压、加载 libcef 并调
 * {@code CefApp.initialize()}；Android 上这些全部交给 Cefrium SDK（它的
 * {@code CefriumInitProvider} 在进程启动时自动初始化引擎），本类只做两件事：</p>
 *
 * <ol>
 *   <li>确保 Cefrium 已初始化（{@link Cefrium#initialize(Context)}，幂等）；</li>
 *   <li>提供与上游同名的静态方法，让消费方（WebDisplays）不用改调用点。</li>
 * </ol>
 *
 * <p><b>必须在 Application.onCreate 里调 {@link #initialize(Context)}</b>，
 * 或者保证它早于任何 {@code createBrowser} —— 引擎没起来时 Cefrium 建浏览器
 * 会一直失败。</p>
 */
public final class MCEF {

    private static final String TAG = "MCEF/Android";

    private static volatile Context appContext;
    private static volatile CefClient client;

    private MCEF() {
    }

    /**
     * 初始化。幂等，可在任意线程调，但建议在 Application.onCreate。
     *
     * @param context 任意 context（内部取 applicationContext，不会泄漏）
     * @return 引擎是否就绪
     */
    public static boolean initialize(Context context) {
        if (context == null) {
            Log.e(TAG, "initialize(null) ignored");
            return false;
        }
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        appContext = app;

        try {
            // Cefrium.initialize 内部会跑 setupProcess（ContextUtils / ApplicationStatus
            // 等 per-process 状态）并 LibraryLoader.ensureInitialized()，是幂等的。
            Cefrium.initialize(app);
        } catch (Throwable t) {
            Log.e(TAG, "Cefrium.initialize threw", t);
            return false;
        }

        boolean ok = Cefrium.isInitialized();
        Log.i(TAG, "initialize: ready=" + ok);

        if (ok) {
            // 引擎起来了，把之前登记的回调放出去（主线程）。
            java.util.List<InitListener> pending;
            synchronized (PENDING_INIT) {
                pending = new java.util.ArrayList<>(PENDING_INIT);
                PENDING_INIT.clear();
            }
            if (!pending.isEmpty()) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                for (InitListener l : pending) {
                    main.post(() -> {
                        try {
                            l.onCefInit(getApp());
                        } catch (Throwable t) {
                            Log.e(TAG, "queued init listener failed", t);
                        }
                    });
                }
            }
        }
        return ok;
    }

    /** 引擎是否已就绪。 */
    public static boolean isInitialized() {
        return Cefrium.isInitialized();
    }

    /**
     * 全局 CefClient（与上游同名）。
     *
     * <p>上游里 client 挂在 {@code org.cef.CefApp} 上；这里直接返回我们自己的
     * {@link CefClient}，因为 CefApp 在我们这套实现里只是门面。</p>
     */
    public static CefClient getClient() {
        CefClient c = client;
        if (c == null) {
            synchronized (MCEF.class) {
                if (client == null) {
                    client = new CefClient();
                }
                c = client;
            }
        }
        return c;
    }

    /**
     * 创建浏览器（WebDisplays 的实际入口）。
     *
     * @param url         起始地址
     * @param transparent 是否要透明背景（WebDisplays 的屏幕传 true）
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent) {
        return new MCEFBrowser(getClient(), url, transparent);
    }

    /**
     * 创建浏览器并指定初始渲染尺寸。
     *
     * <p>比无尺寸版本更适合 WebDisplays：它按屏幕方块的像素尺寸建浏览器，
     * 尺寸对了首帧就不用等 resize。</p>
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent, int width, int height) {
        return new MCEFBrowser(getClient(), url, transparent, width, height);
    }

    /** 当前 CefSettings（只读用途；改它对 Android 侧不生效，见类注释）。 */
    public static CefSettings getSettings() {
        return CefApp.getInstance().getSettings();
    }

    /**
     * 取 CefApp 门面（与上游同名）。
     *
     * <p>上游的 CefApp 负责加载 libcef 并启动消息循环；Android 上这些由 Cefrium
     * 的 ContentProvider 在进程启动时完成，所以这个对象只是个"已就绪"的门面。</p>
     */
    public static CefApp getApp() {
        return CefApp.getInstance();
    }

    /**
     * 引擎就绪后回调——上游 MCEF 用它把初始化推迟到引擎起来之后。
     *
     * <p>Android 上 Cefrium 是<b>同步</b>初始化的（{@code Cefrium.initialize} 返回即代表
     * 结果），所以这里可以立即判断：已就绪就当场回调，否则登记等待
     * {@link #initialize(Context)} 完成时再触发。</p>
     *
     * <p>回调在主线程执行，避免消费方在渲染线程里做 UI 操作。</p>
     */
    public static void scheduleForInit(InitListener listener) {
        if (listener == null) return;

        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        if (isInitialized()) {
            main.post(() -> {
                try {
                    listener.onCefInit(getApp());
                } catch (Throwable t) {
                    Log.e(TAG, "init listener failed", t);
                }
            });
            return;
        }

        // 尚未初始化：挂起来，等 initialize() 成功时统一触发。
        synchronized (PENDING_INIT) {
            PENDING_INIT.add(listener);
        }
        Log.i(TAG, "scheduleForInit: engine not ready yet, listener queued");
    }

    /** 初始化完成回调。 */
    public interface InitListener {
        void onCefInit(CefApp app);
    }

    /** 已登记、等待引擎就绪的回调。 */
    private static final java.util.List<InitListener> PENDING_INIT = new java.util.ArrayList<>();

    /** 释放引用。不调 Cefrium.shutdown() —— 那会让同进程其它浏览器一起失效。 */
    public static void shutdown() {
        client = null;
        appContext = null;
    }
}
