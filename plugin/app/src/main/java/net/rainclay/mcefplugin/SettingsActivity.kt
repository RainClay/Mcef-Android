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

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import net.rainclay.mcefplugin.host.CefriumHostService
import net.rainclay.mcefplugin.host.HostSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 插件主界面：引擎状态 + 开关。
 *
 * 插件不是纯数据载体 —— host（Cefrium + IPC 服务）就跑在本 APK 的 :cefhost 进程里，
 * 这样隔离子进程读的是本 APK 的 nativeLibraryDir，libcef.so 才能被 renderer 加载。
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HostSettings.init(this)
        setContent {
            MiuixTheme {
                SettingsPage()
            }
        }
    }
}

@Composable
private fun SettingsPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var showHud by remember { mutableStateOf(false) }

    if (showHud) {
        HudPage(context, onBack = { showHud = false })
        return
    }
    var desktopUa by remember { mutableStateOf(HostSettings.desktopUserAgent) }
    var mediaGesture by remember { mutableStateOf(HostSettings.mediaPlaybackWithoutGesture) }
    var autoStart by remember { mutableStateOf(HostSettings.autoStartHost) }
    var logLevel by remember { mutableStateOf(HostSettings.logLevel) }
    var showLevelDialog by remember { mutableStateOf(false) }
    var wdCompat by remember { mutableStateOf(HostSettings.wdCompatibility) }
    var quality by remember { mutableStateOf(HostSettings.canvasQuality) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var fpsLimit by remember { mutableStateOf(HostSettings.frameRateLimit) }
    var showFpsDialog by remember { mutableStateOf(false) }

    val libDir = remember(context) {
        runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0).nativeLibraryDir
        }.getOrDefault("")
    }
    val cefFile = remember { java.io.File(libDir, "libcef.so") }
    val mcefFile = remember { java.io.File(libDir, "libmcef.so") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "MCEF 引擎",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = paddingValues.calculateTopPadding()),
            overscrollEffect = null,
        ) {
            item { Spacer(Modifier.size(12.dp)) }

            item { SmallTitle("引擎") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    InfoRow("Cefrium", "0.18.0")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    InfoRow("Chromium", "156.0.8078.80")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    InfoRow(
                        "libcef.so",
                        if (cefFile.isFile) "${cefFile.length() / 1024 / 1024} MB" else "缺失",
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    InfoRow(
                        "libmcef.so",
                        if (mcefFile.isFile) "${mcefFile.length() / 1024} KB" else "缺失",
                    )
                }
            }

            item { SmallTitle("行为") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        title = "桌面化身份",
                        summary = "伪装成 Windows Chrome，避免站点跳转到移动版",
                        checked = desktopUa,
                        onCheckedChange = {
                            desktopUa = it
                            HostSettings.desktopUserAgent = it
                        },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    SwitchPreference(
                        title = "允许自动播放",
                        summary = "网页视频不要求先点一下",
                        checked = mediaGesture,
                        onCheckedChange = {
                            mediaGesture = it
                            HostSettings.mediaPlaybackWithoutGesture = it
                        },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    // WebDisplays 的坐标是屏幕逻辑分辨率（1x1 屏幕是 96x96），
                    // 不是画布像素。不换算就是「点了乱跑、激光笔指偏」。
                    SwitchPreference(
                        title = "WD 兼容",
                        summary = "按 WebDisplays 的屏幕分辨率换算鼠标坐标",
                        checked = wdCompat,
                        onCheckedChange = {
                            wdCompat = it
                            HostSettings.wdCompatibility = it
                        },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    BasicComponent(
                        title = "画质",
                        summary = "${quality.label} · 改动后需重开屏幕生效",
                        onClick = { showQualityDialog = true },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    BasicComponent(
                        title = "帧率上限",
                        summary = fpsLimit.label,
                        onClick = { showFpsDialog = true },
                    )
                }
            }

            item { SmallTitle("服务") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    // 自动启动：启动器读插件库路径时会拉起本插件的 Application，
                    // 那一刻起一份前台服务就能常驻 —— MC 连上来时 host 已就绪。
                    SwitchPreference(
                        title = "自动启动承载进程",
                        summary = "打开 Minecraft 时自动拉起，不用每次手动点",
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            HostSettings.autoStartHost = it
                        },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    BasicComponent(
                        title = "日志级别",
                        summary = when (logLevel) {
                            HostSettings.LogLevel.ERRORS -> "仅错误（默认）"
                            HostSettings.LogLevel.INFO -> "常规（含连接与生命周期）"
                            HostSettings.LogLevel.VERBOSE -> "详细（含心跳与帧诊断）"
                        },
                        onClick = { showLevelDialog = true },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    // 启动 host：点一下即可，前台服务保活。
                    // MC 侧 libmcef.so 连 127.0.0.1:39090，服务不在时浏览器起不来。
                    BasicComponent(
                        title = "运行状态",
                        summary = "帧计数与最近日志",
                        onClick = { showHud = true },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    BasicComponent(
                        title = "启动承载进程",
                        summary = "监听 127.0.0.1:39090，供 Minecraft 连接",
                        onClick = {
                            val ctx = context
                            val intent = Intent(ctx, CefriumHostService::class.java)
                            runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    ctx.startForegroundService(intent)
                                } else {
                                    ctx.startService(intent)
                                }
                            }
                        },
                    )
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    ChoiceDialog(
        show = showLevelDialog,
        title = "日志级别",
        options = listOf(
            Triple(
                HostSettings.LogLevel.ERRORS, "仅错误（默认）",
                "只写失败、异常、握手拒绝"
            ),
            Triple(
                HostSettings.LogLevel.INFO, "常规",
                "加上生命周期、命令分发、握手成功"
            ),
            Triple(
                HostSettings.LogLevel.VERBOSE, "详细",
                "再加上心跳与帧诊断（刷屏，只在排查时用）"
            ),
        ),
        selected = logLevel,
        onSelect = {
            logLevel = it
            HostSettings.logLevel = it
            showLevelDialog = false
        },
        onDismiss = { showLevelDialog = false },
    )

    ChoiceDialog(
        show = showQualityDialog,
        title = "画质",
        // 画布尺寸决定每帧像素量，是"画质"最有效的旋钮。
        // 尺寸在创建浏览器时确定，所以改完要重开屏幕。
        options = HostSettings.CanvasQuality.entries.map { q ->
            Triple(q, q.label, describeQuality(q))
        },
        selected = quality,
        onSelect = {
            quality = it
            HostSettings.canvasQuality = it
            showQualityDialog = false
        },
        onDismiss = { showQualityDialog = false },
    )

    ChoiceDialog(
        show = showFpsDialog,
        title = "帧率上限",
        options = HostSettings.FrameRateLimit.entries.map { f ->
            Triple(f, f.label, describeFps(f))
        },
        selected = fpsLimit,
        onSelect = {
            fpsLimit = it
            HostSettings.frameRateLimit = it
            showFpsDialog = false
        },
        onDismiss = { showFpsDialog = false },
    )
}

/** 画质档的说明文字：给出像素量对比，让用户能判断值不值。 */
private fun describeQuality(q: HostSettings.CanvasQuality): String {
    val mb = q.width.toLong() * q.height * 4 / 1048576.0
    return "${q.width}×${q.height} · 每帧约 ${"%.1f".format(mb)} MB"
}

private fun describeFps(f: HostSettings.FrameRateLimit): String = when (f) {
    HostSettings.FrameRateLimit.FPS_15 -> "省电，视频会有明显掉帧"
    HostSettings.FrameRateLimit.FPS_30 -> "均衡，日常浏览够用"
    HostSettings.FrameRateLimit.FPS_60 -> "流畅，接近满帧"
    HostSettings.FrameRateLimit.UNLIMITED -> "不限制，由页面自身重绘节奏决定"
}

/**
 * 通用单选对话框。
 *
 * <p>miuix 的 `DialogContent` 是 internal，外部拿不到，所以用标准 Compose
 * `Dialog` 套一层 miuix 的 `Card` —— 观感一致且不依赖非公开 API。</p>
 *
 * @param options 三元组：值、标题、说明
 */
@Composable
private fun <T> ChoiceDialog(
    show: Boolean,
    title: String,
    options: List<Triple<T, String, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title4,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            options.forEach { (value, label, desc) ->
                BasicComponent(
                    title = if (value == selected) "✓ $label" else label,
                    summary = desc,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    BasicComponent(
        title = title,
        summary = value,
    )
}
