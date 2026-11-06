/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 *
 * Modified by Rain_Clay, 2026-11: MCEF/Cefrium 内置浏览器宿主集成
 *   （Cefrium 原生库预加载、IPC host、按需下载与设置项）。
 *   原始版权与 GPL-3.0 许可声明见上方，修改部分同样按 GPL-3.0 发布。
*/

package com.movtery.zalithlauncher

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Process
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.cefrium.Cefrium
import com.kyant.fishnet.Fishnet
import com.movtery.zalithlauncher.context.refreshContext
import com.movtery.zalithlauncher.coroutine.TaskSystem
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.path.GamePathManager
import com.movtery.zalithlauncher.mcef.CefriumHostService
import com.movtery.zalithlauncher.mcef.CefriumNativeLib
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.loadAllSettings
import com.movtery.zalithlauncher.ui.activities.showFatalError
import com.movtery.zalithlauncher.ui.activities.showLauncherCrash
import com.movtery.zalithlauncher.utils.device.Architecture
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.writeCrashFile
import com.tencent.mmkv.MMKV
import org.chromium.base.ApplicationStatus
import org.chromium.base.ContextUtils
import org.chromium.base.PathUtils
import org.chromium.base.library_loader.LibraryLoader
import org.chromium.base.library_loader.LibraryProcessType
import org.chromium.ui.base.ResourceBundle
import dagger.hilt.android.HiltAndroidApp
import okio.Path.Companion.toOkioPath
import kotlin.properties.Delegates

@HiltAndroidApp
class ZLApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        @JvmStatic
        var DEVICE_ARCHITECTURE by Delegates.notNull<Int>()
    }

    /**
     * libcef.so 默认不进 APK，由运行时从 filesDir/ceflib 加载（见 CefriumNativeLib）。
     * preloader 的职责就是把这条路径告诉 SDK —— 若 APK 里恰好带了 so，
     * CefriumNativeLib.isReady 会优先认它，preloader 不介入。
     *
     * 必须在这里挂 preloader：SDK 的 CefriumInitProvider 是系统自动实例化的 ContentProvider，
     * 会在 Application.onCreate 之前就调 Cefrium.initialize()，attachBaseContext 是唯一更早的时机。
     *
     * **所有进程都要挂，不能只挂 cefhost。** provider 没有 android:process 属性，
     * 主进程同样会跑 initialize() → ensureInitialized() → System.loadLibrary("cef")；
     * 而 SDK 那里是 catch (Exception)，接不住 UnsatisfiedLinkError（Error 子类），
     * so 缺失时主进程会直接崩在启动阶段。所以这里不筛进程，一律挂上 ——
     * so 不在时 preloader 抛 IllegalStateException 让 SDK 正常走失败分支。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // 顺序探针：直接写公共目录，不依赖日志系统/Service/cefhost。
        // 目的：确认 attachBaseContext 与 CefriumInitProvider.onCreate 的先后 ——
        // preloader 必须早于 SDK 那次 initialize() 才有效，否则设了也白设。
        runCatching {
            val dir = java.io.File(
                android.os.Environment.getExternalStorageDirectory(), "mcef-host-logs"
            )
            if (dir.exists() || dir.mkdirs()) {
                val proc = runCatching { Application.getProcessName() }.getOrNull() ?: "?"
                java.io.File(dir, "mcef-order.log").appendText(
                    "${System.currentTimeMillis()} attachBaseContext proc=$proc\n"
                )
            }
        }

        runCatching {
            CefriumNativeLib.installPreloader(this)
        }.onFailure {
            Log.e("ZLApplication", "安装 cef 原生库 preloader 失败", it)
        }
    }

    override fun onCreate() {
        // Chromium renderer/GPU 子进程（:sandboxed_processN / :privileged_processN）同样会
        // 实例化 Application。这里若跑全套初始化（Fishnet/MMKV/设置/启动 CefriumHostService/
        // 弹 Activity 的崩溃处理器），renderer 会在 ChildProcessService 连上浏览器进程之前
        // 就崩掉或卡死——表现为页面永远 loading、无帧、无 JS、DOM 断链。
        // 子进程只保留最小启动，其余进程行为不变。
        val procName = runCatching { Application.getProcessName() }
            .getOrElse { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }
        if (procName.startsWith("$packageName:sandboxed_process") ||
            procName.startsWith("$packageName:privileged_process")
        ) {
            Log.i("ZLApplication", "chromium child process '$procName': skip app init")
            // 子进程启动时间线落文件：用于判断"页面重载卡死"是否伴随渲染子进程重建。
            runCatching {
                java.io.File(filesDir, "mcef-childprocs.log")
                    .appendText("${System.currentTimeMillis()} child start pid=${android.os.Process.myPid()} name=$procName\n")
            }
            // 渲染子进程崩掉时 Java 层看不到 native crash；这里挂一个 handler，
            // 让"页面卡死/重载"能留下最后一条时间线，而不是静默消失。
            Thread.setDefaultUncaughtExceptionHandler { t, th ->
                runCatching {
                    java.io.File(filesDir, "mcef-childprocs.log")
                        .appendText("${System.currentTimeMillis()} child CRASH pid=${android.os.Process.myPid()} name=$procName thread=${t.name} ${th}\n")
                }
                Process.killProcess(Process.myPid())
            }
            super.onCreate()
            return
        }

        refreshContext(this)

        Thread.setDefaultUncaughtExceptionHandler { _, th ->
            //停止所有任务
            TaskSystem.stopAll()

            val throwable = if (th is SplashException) th.cause!!
            else th

            Logger.error("Startup", "An exception occurred", throwable)

            writeCrashFile(
                file = PathManager.FILE_CRASH_REPORT,
                throwable = throwable
            ) { t ->
                Logger.error("AppCrash", "An exception occurred while saving the crash report", t)
            }

            showLauncherCrash(this@ZLApplication, throwable, th !is SplashException)
            Process.killProcess(Process.myPid())
        }

        super.onCreate()
        runCatching {
            Fishnet.init(this, PathManager.DIR_NATIVE_LOGS.absolutePath)

            MMKV.initialize(this)
            loadAllSettings(this)

            Logger.initialize(this)

            initializeData()
            PathManager.DIR_FILES_PRIVATE = getDir("files", MODE_PRIVATE)
            DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture()

            // ApplicationStatus 必须早于 host Activity 进入 CREATED：反汇编确认
            // Window.Callback 代理只在该状态安装，而其余所有状态（pause/stop/…）都靠一句
            // 断言校验它装过。Activity 正是由下面那行 startService 引出的，所以在这里之前做即可。
            //
            // 用 SDK 自己的 Cefrium.setupProcess()，不手抄它内部那五项。那五项里有断言一次性
            // setter，手抄已经踩过两次：一次顺序错（放在 super.attachBaseContext 之前），
            // 一次只做一半 —— 后者的后果尤其隐蔽：ContextUtils 设上了、setupProcess 短路，
            // ApplicationStatus 永远不注册；换成只调 initialize 又会让 setupProcess 二次调用
            // 它并撞上 assert !isInitialized()，Cefrium.initialize 直接失败、浏览器再也建不起来。
            // setupProcess 是 public 且幂等的，顺序与只做一次都由 SDK 负责。
            runCatching {
                val pn = runCatching { Application.getProcessName() }
                    .getOrElse { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }
                if (pn.endsWith(".cefhost") || pn.endsWith(":cefhost")) {
                    Cefrium.setupProcess(this@ZLApplication)
                    CefriumHostService.appendPublicMirror(
                        "ZLApplication: 已在 startHost 之前 setupProcess; " +
                            "ApplicationStatus.isInitialized=${ApplicationStatus.isInitialized()}"
                    )
                }
            }.onFailure {
                val sw = java.io.StringWriter()
                it.printStackTrace(java.io.PrintWriter(sw))
                CefriumHostService.appendPublicMirror(
                    "ZLApplication: setupProcess 失败: ${it.javaClass.name}: ${it.message}\n$sw"
                )
            }

            // 启动 MCEF/Cefrium Host：监听 127.0.0.1:39090，供 MC 侧 libmcef.so IPC 连接。
            //
            // 两个前置判断，缺一不可：
            //   1) libcef.so 必须已就绪。它不再打进 APK，没下载时启动 host 毫无意义 ——
            //      只会出现 client 连上后一路 create deferred cefReady=false，看起来像卡死。
            //   2) 用户允许自动启动（AllSettings.autoStartCefHost）。
            //      关掉后设置页没有手动启动入口，只有「下载引擎」按钮 ——
            //      要手动拉起得自己 startService。
            val cefReady = CefriumNativeLib.isReady(this)
            val allowAuto = AllSettings.autoStartCefHost.getValue()
            if (cefReady && allowAuto) {
                CefriumHostService.start(this)
            } else {
                CefriumHostService.appendPublicMirror(
                    "ZLApplication: 跳过启动 cef host —— libcef 就绪=$cefReady 允许自动启动=$allowAuto"
                )
            }

            //Force x86 lib directory for Asus x86 based zenfones
            if (Architecture.isx86Device() && Architecture.is32BitsDevice) {
                val originalJNIDirectory = applicationInfo.nativeLibraryDir
                applicationInfo.nativeLibraryDir = originalJNIDirectory.take(originalJNIDirectory.lastIndexOf("/")) + "/x86"
            }
        }.onFailure { launchTh ->
            writeCrashFile(
                file = PathManager.FILE_CRASH_REPORT,
                throwable = launchTh
            ) {
                Log.w("ZLApplication", "An exception occurred while saving the crash report", it)
            }
            showFatalError(this, launchTh)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshContext(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(20L * 1024 * 1024) // 20MB
                    .weakReferencesEnabled(true) //弱引用
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .maxSizeBytes(512L * 1024 * 1024) // 512MB
                    .directory(PathManager.DIR_IMAGE_CACHE.toOkioPath())
                    .build()
            }
            .components {
                add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    private fun initializeData() {
        AccountsManager.initialize(this)
        GamePathManager.initialize(this)
    }
}
