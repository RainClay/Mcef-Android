# MCEF Engine Plugin

Minecraft 内置浏览器（MCEF / WebDisplays）的**引擎插件**。

把 Cefrium（Chromium Embedded Framework 的 Android 移植）连同整套 host 服务
打包成一个独立 APK，通过 Zalith Launcher 2 的 NativeLibPlugin 协议接入 ——
**启动器保持上游原版，不用打任何补丁**。

---

## 为什么是插件

原方案的 host 服务（Cefrium 初始化、OSR 浏览器、IPC 监听）住在启动器里，
意味着每次改动都要重编 400MB+ 的启动器 APK。

而这个插件同时承担两件事：

| 角色 | 内容 | 谁用 |
|---|---|---|
| **native 库提供者** | `libcef.so` + `libmcef.so` | MC 侧的 `System.loadLibrary`、启动器侧的 `dlopen` |
| **host 进程** | `CefriumHostService` + `CefriumHostActivity` | MC 通过 `127.0.0.1:39090` 连进来 |

关键约束：**`libcef.so` 必须住在本 APK 里。** Cefrium 的 renderer 跑在
`isolatedProcess="true"` 的子进程，隔离 UID 只能读本 APK 的
`nativeLibraryDir` —— 放在插件目录或 `filesDir` 里，renderer 都读不到，
表现为 `initialize` 成功但 `OnPaint` 永不回调（`paints` 恒 0）。

---

## 安装

1. 装本插件（APK 见 Releases，或自行构建）
2. 打开「MCEF 引擎」，点「启动承载进程」
3. 用原版 Zalith Launcher 2 启动 Minecraft，放置 WebDisplays 屏幕

插件装好后**不用每次手动点** —— 前台服务保活，通知栏显示运行状态
（待命 / 运行中 · N 个页面），点通知回到插件界面。

### 界面

- **引擎**：Cefrium / Chromium 版本、`libcef.so` 与 `libmcef.so` 状态
- **行为**：桌面化身份（伪装 Windows Chrome）、允许自动播放
- **运行状态**：帧计数、MC 连接状态、最近 40 行日志

---

## 构建

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 153 MB）

要求：

- JDK 17+（SDK 的类文件是版本 69，需 JDK 25 才能编译 SDK 侧；本工程用 AGP 内建 Kotlin）
- Android SDK，`compileSdk 37`
- 首次构建会解析 Cefrium 的 Maven 仓库（Codeberg）

### 几个坑

**`com.cefrium` 插件是必须的。** 它负责两件事：生成 `org.chromium.*.R` 常量、
保证引擎 assets（`icudtl.dat` / `*.pak`）以 `Stored` 方式打包。少了它，
`Cefrium.initialize` 会报 *engine assets are missing or compressed*。
实现在 `buildSrc/`，不用另外装。

**`useLegacyPackaging = true` 不能省。** `libcef.so` 有 209 MB，
必须以解压形式落到 `nativeLibraryDir`，否则隔离子进程读不到。

**manifest 的 `android:process` 必须用点号形式**（`net.rainclay.mcefplugin.cefhost`），
不能用 `:cefhost`。`Cefrium.initialize` 遇到带冒号的进程名会直接返回 `false`：

```java
if (processName != null && processName.contains(":")) return false;
```

**必须有 Application 类预置 `ApplicationStatus`。** host Activity 进入 CREATED
之前要先调 `Cefrium.setupProcess(this)`，否则每次 onPause/onStop 都抛
`AssertionError`（`ApplicationStatus$2.onStateChanged`）。

---

## 协议

跟 MC 侧 `libmcef.so` 的 IPC 是**行分隔 JSON + 二进制帧**，监听
`127.0.0.1:39090`。

| 方向 | 命令 |
|---|---|
| MC → host | `create` / `destroy` / `resize` / `loadUrl` / `eval` / `input` / `domSnapshot` / `queryReply` / `reload` … |
| host → MC | `frame`（JSON 头 + payload）、`query`、`title`、`url` … |

输入事件 `type`：`0` MOVE / `1` DOWN / `2` UP / `3` WHEEL / `4` KEY_DOWN /
`5` KEY_UP / `6` CHAR。

**键盘要注意：** Cefrium 有两个 `sendKeyEvent` 重载，别用错。

```java
// 错误：第三个参数是 Windows 虚拟键码，不是 Android 键码
sendKeyEvent(int type, int modifiers, int windowsKeyCode, int nativeKeyCode, char character)

// 正确：内部做 Android→Windows 映射，并发 RAWKEYDOWN(+CHAR)/KEYUP
sendKeyEvent(android.view.KeyEvent)
```

参数位置猜错（比如把 keyCode 塞进 type）会让 native 直接崩，
表现为按特殊键整个进程被杀、无 Java 异常、无 `hs_err`。

---

## 许可证

GPL-3.0，见 [LICENSE](LICENSE)。

- Cefrium / Chromium：BSD 等，见上游
- Zalith Launcher 2：GPL-3.0
- 本插件代码：GPL-3.0
