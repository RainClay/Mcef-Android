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

package net.rainclay.mcefplugin.host

import android.content.Context
import android.content.SharedPreferences

/**
 * 插件本地设置。
 *
 * <p>用 SharedPreferences 存：设置页（miuix）写、host 读。两者同 UID 同应用，
 * 默认同步机制可用。</p>
 *
 * <p>历史注记：host 服务曾是启动器的一部分，那时配置取自启动器的
 * AllSettings。搬进插件后这个依赖已完全去掉 —— 现在只保留 host 真正的
 * 三项开关，全部由本类自管。别再去启动器里找配置来源。</p>
 */
object HostSettings {

    private const val PREF_NAME = "mcef_host_settings"
    private const val KEY_DESKTOP_UA = "desktop_user_agent"
    private const val KEY_MEDIA_GESTURE = "media_playback_without_gesture"
    private const val KEY_AUTO_START = "auto_start_host"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_CANVAS_QUALITY = "canvas_quality"
    private const val KEY_FRAME_RATE_LIMIT = "frame_rate_limit"
    private const val KEY_WD_COMPAT = "wd_compatibility"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences? = prefs

    /** 桌面化 UA / Client Hints 伪装。默认开。 */
    var desktopUserAgent: Boolean
        get() = p()?.getBoolean(KEY_DESKTOP_UA, true) ?: true
        set(value) { p()?.edit()?.putBoolean(KEY_DESKTOP_UA, value)?.apply() }

    /** 媒体自动播放不要求用户手势。默认开。 */
    var mediaPlaybackWithoutGesture: Boolean
        get() = p()?.getBoolean(KEY_MEDIA_GESTURE, true) ?: true
        set(value) { p()?.edit()?.putBoolean(KEY_MEDIA_GESTURE, value)?.apply() }

    /**
     * 打开 Minecraft 时自动拉起承载进程。默认开。
     *
     * <p>「打开 Minecraft」指的是**插件被拉起**这个时机 —— 启动器的
     * NativeLibPlugin 会把插件的 Application 拉起来读 library 路径，
     * 那一刻起一份前台服务就能常驻，MC 连上来时 host 已经就绪。</p>
     *
     * <p>为什么要这个开关：常驻前台服务会一直显示通知、占内存。
     * 只偶尔玩一次的用户可以关掉，改成手动点「启动承载进程」。</p>
     */
    var autoStartHost: Boolean
        get() = p()?.getBoolean(KEY_AUTO_START, true) ?: true
        set(value) { p()?.edit()?.putBoolean(KEY_AUTO_START, value)?.apply() }

    /**
     * 日志级别。
     *
     * <p>默认 [LogLevel.ERRORS]：只写失败与异常。原因很实际 —— host 的心跳
     * 每 2 秒一行、帧诊断每次上传一条，跑半小时日志就上百 MB，
     * 而真出问题时需要的往往只是那几行报错。</p>
     *
     * <p>排查连接问题调 [LogLevel.INFO]（补上握手、生命周期、命令分发），
     * 复现卡死这类才需要 [LogLevel.VERBOSE]（再加心跳与帧诊断）。</p>
     */
    var logLevel: LogLevel
        get() = runCatching {
            LogLevel.valueOf(
                p()?.getString(KEY_LOG_LEVEL, LogLevel.ERRORS.name) ?: LogLevel.ERRORS.name
            )
        }.getOrDefault(LogLevel.ERRORS)
        set(value) { p()?.edit()?.putString(KEY_LOG_LEVEL, value.name)?.apply() }

    /**
     * 画质档位：决定浏览器的画布边长。
     *
     * <p>画布尺寸是"画质"最有效的旋钮 —— 它直接决定每帧的像素量。
     * 1920×1080 一帧 8.3 MB，1280×720 只要 3.7 MB，带宽与 GPU 上传开销
     * 都按面积降。WebDisplays 的屏幕实际显示尺寸通常远小于 1080p，
     * 高画质档在手机上是白烧性能。</p>
     *
     * <p>改动需要重建浏览器才生效（画布在 create 时确定）。</p>
     */
    var canvasQuality: CanvasQuality
        get() = runCatching {
            CanvasQuality.valueOf(
                p()?.getString(KEY_CANVAS_QUALITY, CanvasQuality.HD.name) ?: CanvasQuality.HD.name
            )
        }.getOrDefault(CanvasQuality.HD)
        set(value) { p()?.edit()?.putString(KEY_CANVAS_QUALITY, value.name)?.apply() }

    enum class CanvasQuality(val width: Int, val height: Int, val label: String) {
        SMOOTH(1280, 720, "流畅 720p"),
        BALANCED(1600, 900, "标准 900p"),
        HD(1920, 1080, "高清 1080p"),
        ULTRA(2560, 1440, "超清 1440p"),
    }

    /**
     * 帧率上限。
     *
     * <p>超出的帧直接丢弃，不排队 —— 已有的单槽管道本来就"新帧覆盖旧帧"，
     * 排队只会把延迟越积越大。</p>
     *
     * <p>只影响从 host 发往 MC 的帧；CEF 的 OnPaint 该回调仍回调，
     * 页面自身的动画/脚本不受影响。</p>
     */
    var frameRateLimit: FrameRateLimit
        get() = runCatching {
            FrameRateLimit.valueOf(
                p()?.getString(KEY_FRAME_RATE_LIMIT, FrameRateLimit.FPS_30.name)
                    ?: FrameRateLimit.FPS_30.name
            )
        }.getOrDefault(FrameRateLimit.FPS_30)
        set(value) { p()?.edit()?.putString(KEY_FRAME_RATE_LIMIT, value.name)?.apply() }

    /**
     * @property fps 上限帧率，0 表示不限
     */
    enum class FrameRateLimit(val fps: Int, val label: String) {
        FPS_15(15, "省电 15 fps"),
        FPS_30(30, "均衡 30 fps"),
        FPS_60(60, "流畅 60 fps"),
        UNLIMITED(0, "不限"),
    }

    /**
     * WebDisplays 兼容：按屏幕逻辑分辨率换算鼠标坐标。
     *
     * <p>WD 的 {@code ScreenBlock.hit2pixels()} 按
     * {@code 方块尺寸 * 16 - 4 再 * 8} 算出 resolution（1×1 屏幕是 96×96），
     * 交给鼠标方法的坐标范围是 {@code 0..resolution}，不是画布像素。
     * 不换算就是「点了乱跑、激光笔指哪儿哪儿不对」。</p>
     *
     * <p>关掉后坐标原样透传（只夹边界）。给两类场景用：消费方不是
     * WebDisplays 而自己发画布坐标，或 WD 改了坐标语义的版本。</p>
     *
     * <p>默认开。改动即时生效 —— host 在握手后下发，重连会自动重发。</p>
     */
    var wdCompatibility: Boolean
        get() = p()?.getBoolean(KEY_WD_COMPAT, true) ?: true
        set(value) { p()?.edit()?.putBoolean(KEY_WD_COMPAT, value)?.apply() }

    /** 日志级别。序号越大越啰嗦；写日志时用 {@code level <= 当前档} 判断。 */
    enum class LogLevel {
        /** 只写失败、异常、握手拒绝。 */
        ERRORS,
        /** 再加生命周期、命令分发、握手成功。 */
        INFO,
        /** 再加心跳、帧诊断等高频输出。 */
        VERBOSE,
    }
}
