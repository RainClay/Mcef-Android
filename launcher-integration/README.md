# Zalith Launcher MCEF/Cefrium integration source

This directory contains the selected source files changed or added in the Zalith Launcher host integration used by the MCEF Android work. It is an **integration overlay**, not a standalone Android project. The source baseline inspected was Zalith Launcher commit `c04ba9ba1b48214637b533c7fcb5434323a8d58e`; apply these files at the matching paths in that project tree.

## Included

- `ZLApplication.kt`: early Cefrium native-library preloader installation and conditional host startup.
- `mcef/CefriumNativeLib.kt`: runtime `libcef.so` readiness, preload and installation helpers.
- `mcef/CefriumDownloader.kt`: download/extract/install helper for the Cefrium native library.
- `mcef/CefriumHostService.kt`: Cefrium host service, IPC protocol handling, browser setup, frame/DOM forwarding, diagnostics and flags.
- `setting/AllSettings.kt`, `ui/.../LauncherSettingsScreen.kt`, `ui/.../CefEngineSettingsCard.kt`: browser-engine setting and download UI.
- `game/download/game/DownloadPolicy.kt`: game-download policy source added alongside the launcher changes.

The replacement `libmcef.so` binary is intentionally not included. Its C++/JNI source is in the `libmcef-source` branch of this repository. The launcher packages the built library under `jniLibs/arm64-v8a/`.

## Integration notes

These files are extracted from the Zalith Launcher source tree and depend on that tree's existing packages, Gradle catalog, settings framework, and Android app configuration. Copying them into an unrelated Android app is not sufficient.

The host listens on `127.0.0.1:39090`. The Minecraft JVM side is a regular OpenJDK process and talks to it through `libmcef.so`; only the launcher's Android host process calls Cefrium directly.

## License and attribution

These files are copied or adapted from Zalith Launcher, whose repository is licensed
under the **GNU General Public License v3.0 (GPL-3.0)**. This launcher integration
overlay is provided under GPL-3.0; see [`LICENSE-ZalithLauncher-GPL-3.0`](LICENSE-ZalithLauncher-GPL-3.0).

| 文件 | 来源 | 处理 |
|---|---|---|
| `ZLApplication.kt` | Zalith 原文件 | 保留原头 + `Modified by` |
| `setting/AllSettings.kt` | Zalith 原文件 | 保留原头 + `Modified by` |
| `ui/.../LauncherSettingsScreen.kt` | Zalith 原文件 | 保留原头 + `Modified by` |
| `mcef/CefriumHostService.kt` | Zalith 原文件 | 保留原头 + `Modified by` |
| `mcef/CefriumNativeLib.kt` | 新增 | GPL-3.0 头 |
| `mcef/CefriumDownloader.kt` | 新增 | GPL-3.0 头 |
| `ui/.../CefEngineSettingsCard.kt` | 新增 | GPL-3.0 头 |

GPL-3.0 §5(a) 要求标注修改：改编文件头部均带 `Modified by Rain_Clay, 2026-11`，
原始版权与许可声明原样保留。

第三方依赖（Cefrium、Chromium/CEF）仍按其自身许可证执行，本 overlay 不改变其许可，
也不分发 Cefrium 二进制。
