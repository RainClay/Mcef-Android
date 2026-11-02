package net.ccbluex.liquidbounce.mcef.nativebridge;

import java.nio.ByteBuffer;

/**
 * libmcef.so 的极薄 Java 门面。
 *
 * 目的：LiquidBounce/MCEF Mod 侧只调用这一组稳定 native 方法，
 * 不在 Mod 里堆 Cefrium、IPC、DOM 脚本和帧缓存逻辑。
 *
 * 当前版本是“后端中立”的：
 * - Mod 侧创建 browser handle；
 * - Cefrium Host 或 IPC 服务通过 hostSetFrame/hostSetDomJson 回填真实数据；
 * - 后续即使从 WebView 桥切到 Cefrium OSR，Mod API 也尽量保持不变。
 */
public final class McefNative {
    private McefNative() {}

    public static final int OK = 0;
    public static final int ERR_NOT_INITIALIZED = -1;
    public static final int ERR_INVALID_ARGUMENT = -2;
    public static final int ERR_NOT_FOUND = -3;
    public static final int ERR_BUFFER_TOO_SMALL = -4;
    public static final int ERR_CLOSED = -5;
    public static final int ERR_UNSUPPORTED = -6;
    public static final int ERR_INTERNAL = -7;

    public static final int INPUT_MOUSE_MOVE = 0;
    public static final int INPUT_MOUSE_DOWN = 1;
    public static final int INPUT_MOUSE_UP = 2;
    public static final int INPUT_MOUSE_WHEEL = 3;
    public static final int INPUT_KEY_DOWN = 4;
    public static final int INPUT_KEY_UP = 5;
    public static final int INPUT_CHAR = 6;

    static {
        // 对应最终产物 app/src/main/jniLibs/arm64-v8a/libmcef.so。
        System.loadLibrary("mcef");
    }

    public static native int init(String runtimeDir, String cacheDir, String cefLibraryPath,
                                  boolean enableIpc, String ipcEndpoint);
    public static native void shutdown();
    public static native boolean isInitialized();

    public static native long createBrowser(String url, int width, int height, float scale,
                                            boolean transparent);
    public static native int destroyBrowser(long browser);
    public static native int resize(long browser, int width, int height);
    public static native int loadUrl(long browser, String url);
    public static native int evaluateJavaScript(long browser, String script);
    public static native int requestDomSnapshot(long browser);
    public static native int requestElementsAtPoint(long browser, int x, int y);
    public static native int clickDom(long browser, String selector);

    public static native int sendInput(long browser, int type, int x, int y, int button,
                                       int keyCode, int scanCode, int modifiers,
                                       double deltaX, double deltaY, int codepoint);

    /** 返回 {结果码, 宽, 高, stride, 帧版本低 31 位}。 */
    public static native int[] getFrameInfo(long browser);

    /** 把最新 BGRA8888 帧复制到 DirectByteBuffer，供 MC 上传纹理。 */
    public static native int copyFrame(long browser, ByteBuffer directBuffer);

    public static native String getDomJson(long browser);
    public static native String getUrl(long browser);
    public static native String getLastError();

    /** Host 回填：把 Cefrium OnPaint 或 IPC 收到的 BGRA8888 像素写入 libmcef。 */
    public static native int hostSetFrame(long browser, ByteBuffer directBuffer,
                                          int width, int height, int stride);

    /** Host 回填：把 DOM 快照 / elementsFromPoint 的 JSON 结果写入 libmcef。 */
    public static native int hostSetDomJson(long browser, String json);
}
