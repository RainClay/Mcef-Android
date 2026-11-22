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
 * 从启动器的 AllSettings 抽出来 —— host 搬进插件后不能再依赖启动器代码，
 * 只保留 host 真正用到的两个开关。用 SharedPreferences 存，
 * 设置页（miuix）写、host 读，进程间靠 MODE_WORLD_READABLE 外的默认同步机制
 * （同 UID 同应用，SharedPreferences 直接可用）。
 */
object HostSettings {

    private const val PREF_NAME = "mcef_host_settings"
    private const val KEY_DESKTOP_UA = "desktop_user_agent"
    private const val KEY_MEDIA_GESTURE = "media_playback_without_gesture"
    private const val KEY_AUTO_START = "auto_start_host"

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
}
