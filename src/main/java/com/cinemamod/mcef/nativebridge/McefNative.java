package com.cinemamod.mcef.nativebridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * <p>由启动器把 libmcef.so 打进 APK 的 {@code lib/arm64-v8a/}，本类只调
 * {@code System.loadLibrary("mcef")}。不在 jar 里塞 so —— Android 上临时目录
 * 常挂 noexec，从 jar 解压再 System.load 会失败。</p>
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
     * <p>两条路，按顺序试：</p>
     * <ol>
     *   <li>{@code System.loadLibrary("mcef")} —— 启动器已经把 so 放进 APK 的
     *       {@code lib/arm64} 目录，而 {@code java.library.path} 里就包含它，
     *       这是最快也最省事的路径；</li>
     *   <li>从 jar 内 {@code /natives/arm64-v8a/libmcef.so} 解压到临时目录再
     *       {@code System.load(绝对路径)} —— 适用于 so 跟 mod 一起打包的场景。</li>
     * </ol>
     *
     * <p>两条都失败时抛 {@link UnsatisfiedLinkError}，把已尝试的路径一并带上，
     * 避免只看到一个干巴巴的 "no mcef in java.library.path"。</p>
     */
    private static void load() {
        // so 由启动器打进 APK 的 lib/arm64-v8a/，java.library.path 里就能找到。
        //
        // 不从 jar 解压：Android 上 /data/local/tmp 之类常用临时目录常挂 noexec，
        // System.load 解压出来的 .so 会失败；而 APK 里的 so 是安装时由系统解压到
        // app 私有 lib 目录的，权限和数据目录都正确。
        System.loadLibrary("mcef");
        loadedFrom = "java.library.path";
    }

    /** so 是从哪里加载的（"java.library.path" 或临时文件路径）。 */
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
     * 创建浏览器，返回句柄（>0 成功，<=0 失败）。
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
}
