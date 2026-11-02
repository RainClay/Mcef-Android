package com.movtery.zalithlauncher.ui.screens.content.settings.layouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.mcef.CefriumDownloader
import com.movtery.zalithlauncher.mcef.CefriumNativeLib
import kotlinx.coroutines.launch

/**
 * 内置浏览器（CEF）引擎状态与下载入口。
 *
 * libcef.so 有 209MB，不再打进 APK，改成用户在这里按需下载 ——
 * 这样 APK 从 611MB 降到 418MB，不用内置浏览器的人不必为它付出体积。
 *
 * 已存在且大小正确时只显示「已就绪」，不会重复下载；
 * 所以也可以手工把 libcef.so 放到 filesDir/ceflib/ 跳过这一步。
 */
@Composable
fun CefEngineSettingsCard(
    modifier: Modifier = Modifier,
    position: CardPosition = CardPosition.Middle
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ready by remember { mutableStateOf(CefriumNativeLib.isReady(context)) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    SettingsCard(
        modifier = modifier.fillMaxWidth(),
        position = position
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "内置浏览器引擎（CEF）",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (ready) {
                    "已就绪，内置浏览器可用。"
                } else {
                    "未安装。内置浏览器的渲染引擎（约 200MB）不随安装包分发，需要时在此下载。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (!ready) {
                Button(
                    onClick = {
                        error = null
                        downloading = true
                        progress = 0
                        scope.launch {
                            runCatching {
                                CefriumDownloader.downloadAndInstall(context) { p ->
                                    progress = p.percent
                                }
                            }.onSuccess {
                                ready = CefriumNativeLib.isReady(context)
                            }.onFailure {
                                error = "下载失败：${it.message}"
                            }
                            downloading = false
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text("下载引擎")
                }
            }
        }
    }
}
