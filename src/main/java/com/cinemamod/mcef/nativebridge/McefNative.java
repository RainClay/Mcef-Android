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

package com.cinemamod.mcef.nativebridge;

import java.nio.ByteBuffer;

/**
 * libmcef.so 的 JNI 绑定。
 *
 * <h3>为什么这个类必须零 android 依赖</h3>
 * <p>MC 跑在<b>纯 OpenJDK JVM</b> 里（日志：{@code OpenJDK 64-Bit Server VM ... linux-aarch64}），
 * 不是 ART。它加载不到 {@code android.content.Context} 之类的类 —— 碰了就是
 * {@code NoClassDefFoundError}。所以 MC 侧这一整套只能通过 JNI 跟 native 通信，
 * native 再通过 IPC 跟启动器的 {@code .cefhost} 进程（那个才是真 Android + Cefrium）
 * 打交道。</p>
 *
 * <pre>
 * MC JVM（纯 OpenJDK）
 *   └── McefNative（本类，JNI）
 *         └── libmcef.so
 *               └── IPC socket 127.0.0.1:39090
 *                     └── 启动器 .cefhost 进程（ART + Cefrium + libcef）
 * </pre>
 *
 * <h3>so 的加载方式</h3>
 * <p>只有一条：启动器把 libmcef.so 打进 APK 的 {@code lib/arm64-v8a/}，
 * {@code System.loadLibrary("mcef")} 直接命中。mod jar 里不带 so ——
 * 两个版本源一旦漂移，JNI 动态注册报的是 {@link UnsatisfiedLinkError}，
 * 从堆栈上判断不出是版本对不上。</p>
 *
 * <h3>包名为什么能随便定</h3>
 * <p>libmcef.so 用 {@code JNI_OnLoad + RegisterNatives} 动态注册，符号名里不含包名。
 * 它注册的目标类里就有 {@code com/cinemamod/mcef/nativebridge/McefNative}。
 * 新增包名只需改 so 源码里的 {@code kTargetClasses} 列表。</p>
 */
public final class McefNative {

    private McefNative() {
    }

    // ── 结果码（与 mcef_result_t 对齐）────────────────────────────────────
    public static final int OK = 0;
    public static final int ERR_NOT_INITIALIZED = -1;
    public static final int ERR_INVALID_ARGUMENT = -2;
    public static final int ERR_NOT_FOUND = -3;
    public static final int ERR_BUFFER_TOO_SMALL = -4;
    public static final int ERR_CLOSED = -5;
    public static final int ERR_UNSUPPORTED = -6;
    public static final int ERR_INTERNAL = -7;

    // ── 输入事件类型（与 mcef_input_type_t 对齐）──────────────────────────
    public static final int INPUT_MOUSE_MOVE = 0;
    public static final int INPUT_MOUSE_DOWN = 1;
    public static final int INPUT_MOUSE_UP = 2;
    public static final int INPUT_MOUSE_WHEEL = 3;
    public static final int INPUT_KEY_DOWN = 4;
    public static final int INPUT_KEY_UP = 5;
    public static final int INPUT_CHAR = 6;

    /** 已加载的 so 版本标记，供诊断用。 */
    private static volatile String loadedFrom = null;

    static {
        load();
    }

    /**
     * 加载 libmcef.so。
     *
     * <p>只有一条路：{@code System.loadLibrary("mcef")}。启动器把 so 打进 APK 的
     * {@code lib/arm64-v8a/}，而 {@code java.library.path} 里就包含它。</p>
     *
     * <p>jar 里不带 so。早先有个「从 jar 内 {@code /natives/arm64-v8a/} 解压到
     * 临时目录再 load」的回退，已经删掉：</p>
     * <ul>
     *   <li>so 是启动器管的，mod jar 再带一份等于有两个版本源；</li>
     *   <li>JNI 走 {@code RegisterNatives} 动态注册，版本错配报的是
     *       {@link UnsatisfiedLinkError}，从堆栈上看不出是版本对不上，
     *       排查成本远高于省下的那点便利。</li>
     * </ul>
     *
     * <p>失败时抛 {@link UnsatisfiedLinkError}，消息里带上 {@code java.library.path}
     * 的实际取值，免得只看到一句干巴巴的 "no mcef in java.library.path"。</p>
     */
    private static void load() {
        try {
            System.loadLibrary("mcef");
            loadedFrom = "java.library.path";
        } catch (UnsatisfiedLinkError e) {
            final UnsatisfiedLinkError err = new UnsatisfiedLinkError(
                    "libmcef.so 加载失败：启动器没有把 so 放进 APK 的 lib/arm64-v8a/。"
                            + "java.library.path=" + System.getProperty("java.library.path")
                            + "；原因：" + e.getMessage());
            err.initCause(e);
            throw err;
        }
    }

    /** so 是从哪里加载的。目前恒为 {@code "java.library.path"}。 */
    public static String getLoadedFrom() {
        return loadedFrom;
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────

    /**
     * 初始化桥。
     *
     * @param runtimeDir     运行时目录（可传 null）
     * @param cacheDir       缓存目录
     * @param cefLibraryPath CEF 库路径（Android 侧由启动器处理，可传 null）
     * @param enableIpc      是否启用 IPC —— <b>必须 true</b>，
     *                       因为浏览器实际跑在启动器进程里
     * @param ipcEndpoint    IPC 地址，通常 {@code 127.0.0.1:39090}
     */
    public static native int init(String runtimeDir, String cacheDir, String cefLibraryPath,
                                  boolean enableIpc, String ipcEndpoint);

    public static native void shutdown();

    public static native boolean isInitialized();

    // ── 浏览器 ────────────────────────────────────────────────────────────

    /**
     * 创建浏览器，返回句柄（{@code >0} 成功，{@code <=0} 失败）。
     *
     * @param transparent 是否透明背景。true 时页面未绘制区域 alpha=0，
     *                    底层内容能透出来（WebDisplays 的屏幕需要）。
     */
    public static native long createBrowser(String url, int width, int height, float scale,
                                           boolean transparent);

    public static native int destroyBrowser(long browser);

    /** 调整尺寸。native 侧目前是空操作（Cefrium 无 resize API）。 */
    public static native int resize(long browser, int width, int height);

    public static native int loadUrl(long browser, String url);

    public static native int evaluateJavaScript(long browser, String script);

    public static native int requestDomSnapshot(long browser);

    public static native int requestElementsAtPoint(long browser, int x, int y);

    public static native int clickDom(long browser, String selector);

    // ── 输入 ──────────────────────────────────────────────────────────────

    /**
     * 发送输入事件。
     *
     * <p><b>协议是鼠标语义</b>：x/y 是渲染目标（OSR 画布）内的像素坐标，
     * 不是屏幕坐标。上层要自己按 viewport 换算。</p>
     */
    public static native int sendInput(long browser, int type, int x, int y, int button,
                                       int keyCode, int scanCode, int modifiers,
                                       double deltaX, double deltaY, int codepoint);

    // ── 帧 ────────────────────────────────────────────────────────────────

    /** 返回 {@code {结果码, 宽, 高, stride, 帧版本低 31 位}}。 */
    public static native int[] getFrameInfo(long browser);

    /**
     * 把当前帧拷进 directBuffer（BGRA8888，大小必须 ≥ stride*height）。
     * buffer 必须用 {@link ByteBuffer#allocateDirect} 分配。
     */
    public static native int copyFrame(long browser, ByteBuffer directBuffer);

    // ── DOM / URL ─────────────────────────────────────────────────────────

    public static native String getDomJson(long browser);

    public static native String getUrl(long browser);

    public static native String getLastError();

    // ── Host 侧回填（仅启动器进程用，MC 侧不需要）─────────────────────────

    public static native int hostSetFrame(long browser, ByteBuffer directBuffer,
                                          int width, int height, int stride);

    public static native int hostSetDomJson(long browser, String json);

    /**
     * 取一条待处理的 JS 查询。
     *
     * <p>返回 {@code String[4]}：{@code [queryId, persistent, request, origin]}，
     * 没有待处理查询时返回 {@code null}。用 String 数组而不是自定义类，
     * 是因为 JNI 侧构造对象要 FindClass + 构造器签名，动一下就静默失败。</p>
     */
    public static native String[] takeQuery(long browser);

    /**
     * 回填查询结果给 host 进程。
     *
     * <p>MC 侧算完结果必须回到 host 才能答复页面 —— 只有 host 持有 Cefrium 的
     * callback 实例。</p>
     *
     * @param success {@code true} 走 {@code callback.success(result)}，
     *                {@code false} 走 {@code callback.failure(errorCode, result)}
     */
    public static native int replyQuery(long browser, long queryId, boolean success,
                                        String result, int errorCode);
}
