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
}
