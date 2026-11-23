/*
 * MCEF Engine Plugin —— Minecraft 内置浏览器的引擎侧
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
 */

package net.rainclay.mcefplugin

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cefrium.Cefrium
import net.rainclay.mcefplugin.host.CefriumHostService
import net.rainclay.mcefplugin.host.HostSettings
import org.chromium.base.ApplicationStatus

/**
 * 插件 Application。
 *
 * **存在的唯一目的是在 host Activity 起来之前预置 Chromium 的进程级状态。**
 *
 * 反汇编 Cefrium/ApplicationStatus 确认：`ApplicationStatus` 的 Window.Callback
 * 代理只在 Activity 进入 CREATED 时安装，而 onPause / onStop / onDestroy 等
 * 都用一句断言校验"它装过"。没装就抛：
 *
 *     java.lang.AssertionError
 *       at org.chromium.base.ApplicationStatus$2.onStateChanged(ApplicationStatus.java:342)
 *
 * 表现为 host Activity 每次 pause/stop 都 SWALLOWED 一条断言，CEF 状态机错乱。
 *
 * 用 SDK 自己的 `Cefrium.setupProcess()` 而不是手抄它内部那五项：
 * 手抄踩过两次（顺序错、只做一半），setupProcess 是 public 且幂等的，
 * 顺序与只做一次都由 SDK 负责。
 */
class McefPluginApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CefriumHostLog.currentAppContext = this

        // 进程名用点号形式（net.rainclay.mcefplugin.cefhost）：
        // Cefrium.initialize 遇到带冒号的进程名会直接 return false。
        val pn = runCatching { Application.getProcessName() }
            .getOrElse {
                runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }
                    .getOrDefault("")
            }

        if (pn.endsWith(".cefhost")) {
            setupCefriumProcess(pn)
        } else {
            // 主进程：按设置自动拉起承载进程。
            //
            // 必须放在主进程。放在 .cefhost 里会形成死循环：
            // 服务启动才创建 .cefhost 进程，而 .cefhost 的 onCreate 才判断
            // 要不要启动服务 —— 服务没起时那段代码永远不执行。
            //
            // 触发时机：用户打开插件的任何界面（设置页 / HUD）都会走到这里。
            // 不能指望启动器 —— 它读插件库路径用 PackageManager，不拉进程。
            maybeAutoStartHost()
        }
    }

    /**
     * 承载进程的初始化：预置 Chromium 的进程级状态。
     *
     * 反汇编 Cefrium/ApplicationStatus 确认：`ApplicationStatus` 的 Window.Callback
     * 代理只在 Activity 进入 CREATED 时安装，而 onPause / onStop / onDestroy
     * 都用一句断言校验"它装过"。没装就抛：
     *
     *     java.lang.AssertionError
     *       at org.chromium.base.ApplicationStatus$2.onStateChanged(ApplicationStatus.java:342)
     *
     * 表现为 host Activity 每次 pause/stop 都 SWALLOWED 一条断言，CEF 状态机错乱。
     *
     * 用 SDK 自己的 `Cefrium.setupProcess()` 而不是手抄它内部那五项：
     * 手抄踩过两次（顺序错、只做一半），setupProcess 是 public 且幂等的。
     */
    private fun setupCefriumProcess(pn: String) {
        runCatching {
            Cefrium.setupProcess(this)
            CefriumHostLog.mirror(
                "setupProcess ok; ApplicationStatus.isInitialized=${ApplicationStatus.isInitialized()}; proc=$pn"
            )
        }.onFailure {
            CefriumHostLog.mirror("setupProcess 失败: ${it.javaClass.name}: ${it.message}")
        }
    }

}

/**
 * 按设置自动拉起承载进程。
 *
 * <p>时机：启动器的 NativeLibPlugin 会拉起本插件的 Application 来读
 * library 路径，这一刻起服务就能常驻 —— MC 真正连上来时 host 已就绪，
 * 不必等用户手动点。</p>
 *
 * <p>只针对承载进程判断：设置页进程也会走 onCreate，那里不该重复启动。
 * 用 {@link HostSettings} 而不是读什么全局态 —— SharedPreferences
 * 在同应用的多进程间最终一致，够用（toggle 后下一次启动生效）。</p>
 */
private fun Application.maybeAutoStartHost() {
    runCatching {
        HostSettings.init(this)
        if (!HostSettings.autoStartHost) {
            CefriumHostLog.mirror("autoStart: 已关闭，跳过")
            return
        }
        // 已经在跑就别再发一次 startForegroundService —— 否则每次打开
        // 设置页都会打一发 Intent，服务那边虽然幂等，但日志会被刷。
        val alive = CefriumHostService.readSnapshot(this)?.running == true
        if (alive) {
            CefriumHostLog.mirror("autoStart: 已在运行，跳过")
            return
        }
        val intent = Intent(this, CefriumHostService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        CefriumHostLog.mirror("autoStart: 已拉起 CefriumHostService")
    }.onFailure {
        // Android 12+ 对后台启动前台服务有限制（BAL）。用户刚点开界面时
        // 通常还在前台允许窗口内，但如果是从桌面图标冷启动、
        // 或者 ROM 有自己的省电策略，仍可能抛 ForegroundServiceStartNotAllowedException。
        // 这种情况只能记日志 + 让用户手动点 —— 前台点按钮一定允许。
        val name = it.javaClass.simpleName
        val hint = if (name.contains("ForegroundServiceStartNotAllowed")) {
            "（系统限制后台启动前台服务，请手动点「启动承载进程」）"
        } else {
            ""
        }
        CefriumHostLog.mirror("autoStart: 失败 $name: ${it.message}$hint")
    }
}

/**
 * 极简日志镜像：Application 阶段 host 还没起来，用不了它的日志设施。
 * 直接写应用外部目录，失败就算了 —— 这只是诊断，不能影响启动。
 */
object CefriumHostLog {
    fun mirror(line: String) {
        runCatching {
            val ctx = currentAppContext ?: return@runCatching
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            if (!dir.exists() && !dir.mkdirs()) return@runCatching
            java.io.File(dir, "mcef-cefrium.log").appendText(line + "\n")
        }
    }

    @Volatile
    var currentAppContext: Context? = null
}
