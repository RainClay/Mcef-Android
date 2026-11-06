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
 * PROVENANCE: independent implementation, not a derivative work of
 * CinemaMod MCEF (LGPL-2.1). Names are kept identical only to satisfy
 * WebDisplays bytecode references; no upstream source, comments or
 * implementation logic is included. See README "与上游的关系".
 */

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
 *
 * <p><b>没有全局浏览器注册表</b>：早先版本有 {@code ACTIVE} 列表和
 * {@code tickAll()}，现已删除 —— 帧上传由
 * {@link MCEFRenderer#getTextureID()} 按 MC 帧号驱动，不需要集中轮询。
 * 每帧的 {@code ++} 由 {@code MCEFMod} 在 {@code RenderTickEvent.START} 完成。</p>
 *
 * <h3>初始化时机</h3>
 * <p>{@code McefNative.init} 是<b>同步</b>的（返回即代表结果），所以
 * {@link #scheduleForInit} 在桥已就绪时会当场回调，否则登记到
 * {@code PENDING_INIT}，等 {@link #initialize} 成功时统一触发。
 * {@link MCEFBrowser} 的构造函数也会兜底调 {@link #ensureInitialized()}。</p>
 */
public final class MCEF {

    private static final Logger LOGGER = LogManager.getLogger("MCEF");

    /** 默认 IPC 端点，与启动器 CefriumHostService 的监听地址一致。 */
    public static final String DEFAULT_IPC_ENDPOINT = "127.0.0.1:39090";

    private static volatile String ipcEndpoint = DEFAULT_IPC_ENDPOINT;
    private static volatile boolean initialized = false;
    private static volatile MCEFClient client;

    private static final List<MCEFInitListener> PENDING_INIT = new ArrayList<>();

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
     * <p>尺寸要在<b>这里</b>给定：native 侧 {@code resize} 返回
     * {@code ERR_UNSUPPORTED}（Cefrium SDK 没有 resize API，CEF 浏览器尺寸在
     * create 时固定），之后再改是无效的。</p>
     *
     * <p>注意 WebDisplays 并不走本方法 —— 它是
     * {@code new WDClientBrowser(...) → super(...)} 直接进
     * {@link MCEFBrowser} 的构造函数，用的是 1280×720 默认值。</p>
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent, int width, int height) {
        ensureInitialized();
        return new MCEFBrowser(getClient(), url, transparent, width, height, true);
    }

    /**
     * 当前 {@link CefSettings}。
     *
     * <p><b>改了不生效</b>：真正的 CEF 在启动器进程里，它的设置在那边初始化时
     * 就已固定，MC 侧拿到的只是本地的一个空壳对象。改这里的字段不会有任何效果。</p>
     */
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

    /**
     * 释放引用。
     *
     * <p><b>不停止启动器侧的 CEF</b> —— 那个进程可能还被同启动器里的其它实例用着。
     * 这里只关掉 MC 侧的 JNI 客户端并清空 {@link #client}，桥标记回未初始化状态，
     * 之后可以再 {@link #initialize} 一次。</p>
     */
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
