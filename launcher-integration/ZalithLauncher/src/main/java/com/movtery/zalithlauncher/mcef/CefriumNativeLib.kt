/*
 * MCEF Android/Cefrium host integration
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
 * PROVENANCE: original file written for this integration, GPL-3.0, to be
 * combined with Zalith Launcher (GPL-3.0). Not derived from any non-GPL
 * source.
 */

package com.movtery.zalithlauncher.mcef

import android.content.Context
import android.util.Log
import java.io.File
import org.chromium.base.library_loader.LibraryLoader
import org.chromium.base.library_loader.NativeLibraryPreloader

/**
 * libcef.so 的运行时加载与磁盘管理。
 *
 * 动机：libcef.so 单文件 209MB（未压缩），打进 APK 会让包体膨胀到 600MB+，
 * 而它只有真正用到内置浏览器时才需要。所以改成**不打包、按需下载**。
 *
 * 存放位置：`filesDir/ceflib/libcef.so`
 *   - 必须在应用私有目录：/storage 是 noexec，无法 dlopen；
 *     APK 的 nativeLibraryDir 只读，运行时写不进去。
 *   - 用绝对路径 System.load()，绕开 System.loadLibrary 对 nativeLibraryDir 的限定。
 *
 * 已经存在且大小匹配就跳过下载 —— 这样也可以手工把 libcef.so 拷进去直接用。
 */
object CefriumNativeLib {

    private const val TAG = "MCEF/CefNative"

    /** 0.11.0 的 libcef.so 字节数；用于判断落盘的文件是否完整。 */
    const val EXPECTED_SIZE: Long = 226_953_880L

    private const val DIR_NAME = "ceflib"
    private const val LIB_NAME = "libcef.so"

    /**
     * 可复现的下载源（cefrium-sdk AAR）。
     *
     * 0.11.0 只发在 Codeberg 的 Maven registry，Maven Central 还停在 0.10.2，
     * 所以这里用 Codeberg 的地址。实测下载速度约 6MB/s，比预想快得多。
     * 等它同步到 Maven Central 后可以换回 repo1.maven.org。
     */
    const val AAR_URL =
        "https://codeberg.org/api/packages/cefrium/maven/com/cefrium/cefrium-sdk/0.11.0/cefrium-sdk-0.11.0.aar"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun libFile(context: Context): File = File(dir(context), LIB_NAME)

    /**
     * libcef.so 是否可用。
     *
     * 两个来源都算数，顺序无关：
     *   1) APK 自带（nativeLibraryDir/libcef.so）—— 打包方案，优先；
     *   2) 私有目录 files/ceflib/libcef.so 且大小正确 —— 按需下载/手工拷贝方案。
     *
     * 早先只查 (2)，于是"APK 里明明有 so"却被判成未就绪，主进程直接跳过启动
     * cef host —— 而 installPreloader 那边又因为 APK 自带就跳过挂载。
     * 两处判据不一致，结果是浏览器怎么也起不来。现在统一到这里。
     *
     * 私有目录那份只比大小不校验哈希：200MB+ 做一次 SHA 要好几秒，而大小不符
     * （下载中断、半截文件）已经能覆盖绝大多数失败情形。
     */
    fun isReady(context: Context): Boolean {
        // 1) APK 自带
        val sysDir = context.applicationInfo?.nativeLibraryDir
        if (!sysDir.isNullOrEmpty()) {
            val inApk = File(sysDir, LIB_NAME)
            if (inApk.isFile && inApk.length() > 0) return true
        }

        // 2) 私有目录
        val f = libFile(context)
        val ok = f.isFile && f.length() == EXPECTED_SIZE
        if (!ok) {
            val why = when {
                !f.exists() -> "文件不存在"
                !f.isFile -> "不是普通文件"
                else -> "大小不符：实际 ${f.length()}，期望 $EXPECTED_SIZE"
            }
            val msg = "CefriumNativeLib: 未就绪($why) path=${f.absolutePath}"
            // 三路都写：logcat（进程无关）、公共镜像（免特权读取）、主日志文件。
            // 早先只写公共镜像，而它在主进程首次访问时可能拿到 null 并永久缓存，
            // 结果一条都看不到 —— 诊断代码本身必须先确保能出声。
            Log.w(TAG, msg)
            CefriumHostService.appendPublicMirror(msg)
        }
        return ok
    }

    /**
     * 安装一个 libcef.so（从下载完成的临时文件或用户手放的副本）。
     *
     * 先写临时文件再 rename，避免下载中断时留下一个"看起来存在但是坏的"文件。
     */
    @Throws(Exception::class)
    fun install(context: Context, source: File) {
        val target = libFile(context)
        val d = dir(context)
        if (!d.exists() && !d.mkdirs()) throw Exception("无法创建目录 ${d.absolutePath}")
        val tmp = File(d, "$LIB_NAME.part")
        source.copyTo(tmp, overwrite = true)
        if (tmp.length() != EXPECTED_SIZE) {
            tmp.delete()
            throw Exception("libcef.so 大小不符：${tmp.length()} != $EXPECTED_SIZE")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw Exception("无法重命名到 ${target.absolutePath}")
    }

    /**
     * 把 preloader 挂到 Chromium 的 LibraryLoader 上。
     *
     * 必须在 CefriumInitProvider.onCreate() 之前调用 —— 那个 ContentProvider 是系统
     * 自动实例化的，会在 Application.onCreate 之前就调 Cefrium.initialize()。
     * 所以调用点在 ZLApplication.attachBaseContext()，那是唯一更早的时机。
     *
     * **所有进程都要挂，不只是 cefhost。** SDK 的 provider 没有 android:process 属性，
     * 主进程同样会跑 Cefrium.initialize() → LibraryLoader.ensureInitialized()。
     * 而 Cefrium.java 里那段是 `catch (Exception e)`，捕获不到 UnsatisfiedLinkError
     * （它继承 LinkageError → Error），so 缺失时主进程会直接崩在启动阶段，
     * 连带整个应用起不来。
     *
     * 所以 so 不存在时也要挂一个 preloader，把加载挡下来并抛出可控的异常，
     * 而不是让 System.loadLibrary 抛 Error 穿过 SDK 的 catch。
     *
     * 返回值说明：反汇编确认 LibraryLoader 会把 loadLibrary() 的 int 返回值直接 pop 掉，
     * 真正的成败只看有没有抛异常。成功时返回 0。
     */
    /**
     * 顺序/调用探针：直接追加到公共目录，不依赖日志系统、不依赖 cefhost。
     * 排查 preloader 到底有没有被装上、有没有被调用，这是唯一稳的观测点。
     */
    private fun traceOrder(msg: String) {
        runCatching {
            val dir = File(
                android.os.Environment.getExternalStorageDirectory(), "mcef-host-logs"
            )
            if (dir.exists() || dir.mkdirs()) {
                File(dir, "mcef-order.log").appendText("${System.currentTimeMillis()} $msg\n")
            }
        }
    }

    fun installPreloader(context: Context) {
        // 若 APK 自带 libcef.so（nativeLibraryDir 里有），就完全不要挂 preloader，
        // 让 Chromium 自己的 LibraryLoader 走系统路径加载。这是唯一被 CEF 正式支持的
        // 加载方式，renderer/GPU 子进程也依赖它 —— 用 System.load(绝对路径) 顶替时，
        // 初始化能过，但 onPaint 不回调（实测 paints 恒为 0）。
        val systemLibDir = context.applicationInfo?.nativeLibraryDir
        if (!systemLibDir.isNullOrEmpty()) {
            val inApk = File(systemLibDir, "libcef.so")
            if (inApk.isFile) {
                traceOrder("installPreloader: APK 自带 so，跳过挂载 path=${inApk.absolutePath}")
                Log.i(TAG, "APK 自带 libcef.so，交给系统加载器: ${inApk.absolutePath}")
                return
            }
        }

        val ready = isReady(context)
        val path = libFile(context).absolutePath
        traceOrder("installPreloader: 开始挂载 ready=$ready path=$path")
        runCatching {
            LibraryLoader.getInstance().setNativeLibraryPreloader(object : NativeLibraryPreloader() {
                override fun loadLibrary(libraryName: String): Int {
                    traceOrder("preloader.loadLibrary 被调用 libraryName=$libraryName ready=$ready")
                    if (!ready) {
                        // 用 IllegalStateException（Exception 子类）而不是抛 Error：
                        // SDK 那边是 catch (Exception)，这样能被接住并 return false，
                        // Cefrium.initialize 正常返回失败，应用继续启动、内置浏览器不可用。
                        val msg = "libcef.so 未安装，跳过加载（$path）"
                        Log.w(TAG, msg)
                        CefriumHostService.appendPublicMirror("CefriumNativeLib: $msg")
                        throw IllegalStateException(msg)
                    }
                    // 用 ZLBridge.dlopen（裸 dlopen）而不是 System.load：
                    // System.load 走 JVM 的库加载器，受 linker namespace 约束；
                    // ZLBridge.dlopen 是 JNI 里直接调 dlopen()，语义更接近 Chromium
                    // 自己加载 so 的方式。先用它试，失败再退回 System.load。
                    val okDlopen = runCatching {
                        com.movtery.zalithlauncher.bridge.ZLBridge.dlopen(path)
                    }.getOrDefault(false)
                    traceOrder("preloader: ZLBridge.dlopen($path) = $okDlopen")
                    if (!okDlopen) {
                        traceOrder("preloader: dlopen 失败，退回 System.load")
                        System.load(path)
                    }
                    return 0
                }
            })
            traceOrder("installPreloader: 挂载完成")

            // 主动触发一次 preload。为什么必须这样做：
            // Cefrium.initialize() 调的是 ensureInitialized() → ensureMainDexInitialized()
            // → loadNonMainDex()，这条链**根本不经过 preloadAlreadyLocked**，
            // 而 preloader 只在那里被读。所以只挂 preloader 而不触发，等于没挂。
            //
            // preloadNowOverridePackageName 是唯一能在初始化前手动走一遍
            // preloadAlreadyLocked 的公开入口。它内部先判 useChromiumLinker()，
            // 而 NativeLibraries.sUseLinker 默认 false（该字段非 final，静态块里也没赋 true），
            // 所以这里能走进去，preloader.loadLibrary 会被调用。
            //
            // 传进去的 packageName 最终会作为 libraryName 交给 preloader，
            // 我们的实现忽略这个参数、只加载固定路径，所以随便传本包名即可。
            runCatching {
                LibraryLoader.getInstance()
                    .preloadNowOverridePackageName(context.packageName)
                traceOrder("installPreloader: preloadNowOverridePackageName 已调用")
            }.onFailure {
                traceOrder("installPreloader: 主动 preload 失败 ${it.javaClass.simpleName}: ${it.message}")
            }

            Log.i(TAG, "preloader 已挂载: $path ready=$ready")
        }.onFailure {
            traceOrder("installPreloader: 挂载失败 ${it.javaClass.name}: ${it.message}")
            Log.e(TAG, "挂载 preloader 失败", it)
        }
    }
}
