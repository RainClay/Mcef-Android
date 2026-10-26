package com.cinemamod.mcef;

import com.cinemamod.mcef.listeners.MCEFInitListener;
import com.cinemamod.mcef.nativebridge.McefNative;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * MCEF —— 库的入口。
 *
 * <p><b>与上游 CinemaMod MCEF 的区别</b>：上游自己下载/解压/加载 libcef 并调
 * {@code CefApp.initialize()}；这里引擎在<b>启动器的 .cefhost 进程</b>里跑，
 * MC 侧只通过 {@code libmcef.so} 的 IPC 客户端跟它通信。所以本类做的事是：</p>
 *
 * <ol>
 *   <li>初始化桥（{@link McefNative#init}），把 IPC 端点告诉它；</li>
 *   <li>提供与上游同名的静态方法，让 WebDisplays 的调用点不用改。</li>
 * </ol>
 *
 * <p><b>关键：本类零 android 依赖。</b> MC 跑在纯 OpenJDK JVM 里，
 * 任何 {@code android.*} 引用都是运行期 {@code NoClassDefFoundError}。</p>
 */
public final class MCEF {

    private static final Logger LOGGER = LogManager.getLogger("MCEF");

    /** 默认 IPC 端点，与启动器 CefriumHostService 的监听地址一致。 */
    public static final String DEFAULT_IPC_ENDPOINT = "127.0.0.1:39090";

    private static volatile String ipcEndpoint = DEFAULT_IPC_ENDPOINT;
    private static volatile boolean initialized = false;
    private static volatile MCEFClient client;

    private static final List<MCEFInitListener> PENDING_INIT = new ArrayList<>();

    /**
     * 当前活着的浏览器。
     *
     * <p>必须有人每帧驱动它们 {@link MCEFBrowser#tick()} —— 帧是从 IPC 缓存里
     * <b>轮询</b>取出来的，不是回调推过来的。WebDisplays 以为 mcef 自己管渲染
     * （上游是 CEF 主动 onPaint），它不会调 tick()，所以如果没人调，
     * 纹理就永远是空的、屏幕上一片黑。</p>
     *
     * <p>驱动入口在 {@code MCEFMod} 的 RenderTickEvent 监听里。</p>
     */
    private static final List<MCEFBrowser> ACTIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    private MCEF() {
    }

    /**
     * 初始化桥。幂等。
     *
     * @param endpoint IPC 地址（{@code host:port}）；传 null 用默认 127.0.0.1:39090
     * @return 是否就绪
     */
    public static synchronized boolean initialize(String endpoint) {
        if (initialized) return true;

        final String ep = (endpoint == null || endpoint.isEmpty()) ? DEFAULT_IPC_ENDPOINT : endpoint;
        ipcEndpoint = ep;

        try {
            // cacheDir 传 null：native 侧会用进程工作目录兜底。
            // enableIpc 必须 true —— 浏览器在启动器进程里，不走 IPC 就没有帧。
            final int rc = McefNative.init(null, null, null, true, ep);
            if (rc != McefNative.OK) {
                LOGGER.error("McefNative.init failed rc={} endpoint={} lastError={}",
                        rc, ep, safeLastError());
                return false;
            }
            initialized = McefNative.isInitialized();
            LOGGER.info("bridge initialized: endpoint={} so={}",
                    ep, McefNative.getLoadedFrom());
        } catch (Throwable t) {
            LOGGER.error("bridge init threw (so={})", McefNative.getLoadedFrom(), t);
            return false;
        }

        if (initialized) {
            firePendingInit();
        }
        return initialized;
    }

    /** 用默认端点初始化。 */
    public static boolean initialize() {
        return initialize(DEFAULT_IPC_ENDPOINT);
    }

    /**
     * 确保桥已初始化；没初始化就尝试一次。
     *
     * <p>调用时机是第一次 {@link #createBrowser} 之前 —— 那时启动器的 Cefrium 宿主
     * 通常已经起来了。失败只记日志，不抛异常：让 mod 的其它部分还能工作，
     * 由上层看到 browser 创建失败后再排查。</p>
     */
    public static synchronized void ensureInitialized() {
        if (isInitialized()) return;
        initialize(DEFAULT_IPC_ENDPOINT);
    }

    /** 桥是否就绪。 */
    public static boolean isInitialized() {
        return initialized && McefNative.isInitialized();
    }

    /** 当前 IPC 端点。 */
    public static String getIpcEndpoint() {
        return ipcEndpoint;
    }

    private static String safeLastError() {
        try {
            final String e = McefNative.getLastError();
            return e == null ? "" : e;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 全局 CefClient（与上游同名）。
     *
     * <p>上游里 client 挂在 {@code org.cef.CefApp} 上；这里直接返回我们自己的
     * {@link CefClient}，因为 CefApp 在本实现里只是门面。</p>
     */
    public static MCEFClient getClient() {
        MCEFClient c = client;
        if (c == null) {
            synchronized (MCEF.class) {
                if (client == null) {
                    // MCEFClient 是上游 MCEF 的包装类型，WebDisplays 的
                    // WDClientBrowser 构造签名要求的就是它（不是 org.cef.CefClient）。
                    client = new MCEFClient(new CefClient());
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
        ensureInitialized();
        return new MCEFBrowser(getClient(), url, transparent);
    }

    /**
     * 创建浏览器并指定初始渲染尺寸。
     *
     * <p>比无尺寸版本更适合 WebDisplays：它按屏幕方块的像素尺寸建浏览器，
     * 尺寸对了首帧就不用等 resize（而 native 侧 resize 目前是空操作）。</p>
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent, int width, int height) {
        ensureInitialized();
        return new MCEFBrowser(getClient(), url, transparent, width, height, true);
    }

    /** 当前 CefSettings（只读用途；改它对 Android 侧不生效，见类注释）。 */
    public static CefSettings getSettings() {
        return CefApp.getInstance().getSettings();
    }

    private static volatile MCEFApp app;

    /** 取 MCEFApp 门面（与上游同名）。 */
    public static MCEFApp getApp() {
        MCEFApp a = app;
        if (a == null) {
            synchronized (MCEF.class) {
                if (app == null) {
                    app = new MCEFApp(CefApp.getInstance());
                }
                a = app;
            }
        }
        return a;
    }

    /**
     * 桥就绪后回调（与上游同名）。
     *
     * <p>上游用它把初始化推迟到引擎起来之后。这里的桥是<b>同步</b>初始化的
     * （{@code McefNative.init} 返回即代表结果），所以：已就绪就当场回调，
     * 否则登记等待 {@link #initialize} 成功时再触发。</p>
     */
    public static void scheduleForInit(MCEFInitListener listener) {
        if (listener == null) return;

        if (isInitialized()) {
            try {
                listener.onInit(true);
            } catch (Throwable t) {
                LOGGER.error("init listener failed", t);
            }
            return;
        }

        synchronized (PENDING_INIT) {
            PENDING_INIT.add(listener);
        }
        LOGGER.info("scheduleForInit: bridge not ready, listener queued");
    }

    private static void firePendingInit() {
        List<MCEFInitListener> pending;
        synchronized (PENDING_INIT) {
            pending = new ArrayList<>(PENDING_INIT);
            PENDING_INIT.clear();
        }
        for (MCEFInitListener l : pending) {
            try {
                l.onInit(true);
            } catch (Throwable t) {
                LOGGER.error("queued init listener failed", t);
            }
        }
    }

    /** 注册一个活着的浏览器（构造时调）。 */
    static void registerActive(MCEFBrowser browser) {
        if (browser != null && !ACTIVE.contains(browser)) {
            ACTIVE.add(browser);
        }
    }

    /** 注销（关闭时调）。 */
    static void unregisterActive(MCEFBrowser browser) {
        ACTIVE.remove(browser);
    }

    /** 当前活跃浏览器数量（诊断用）。 */
    public static int getActiveBrowserCount() {
        return ACTIVE.size();
    }

    /**
     * 每帧驱动所有浏览器：拉帧 + 上传纹理。
     *
     * <p>由 {@code MCEFMod} 在 {@code RenderTickEvent} 的 START 阶段调用 ——
     * 必须在 MC 真正绘制之前把纹理准备好，否则画的是上一帧或空纹理。</p>
     */
    public static void tickAll() {
        if (ACTIVE.isEmpty()) return;
        for (MCEFBrowser b : ACTIVE) {
            try {
                b.tick();
            } catch (Throwable t) {
                LOGGER.error("browser tick failed", t);
            }
        }
    }

    /** 释放引用。不停止启动器侧的 CEF —— 那会影响同启动器里的其它实例。 */
    public static synchronized void shutdown() {
        try {
            McefNative.shutdown();
        } catch (Throwable t) {
            LOGGER.warn("shutdown threw", t);
        }
        initialized = false;
        client = null;
    }
}
