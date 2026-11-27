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
2. 装配套的 mod jar（`mcef-forge-*-android.jar`，见 1.20.1 分支的 Releases）
3. 用原版 Zalith Launcher 2 启动 Minecraft，放置 WebDisplays 屏幕

**不需要手动启动承载进程。** 默认开启「自动启动承载进程」——
启动器读插件库路径时会拉起本插件的 `Application`，那一刻起前台服务
就常驻了，MC 连上来时 host 已就绪。

想手动控制就在设置页关掉那个开关，之后每次点「启动承载进程」。

### 界面

- **引擎**：Cefrium / Chromium 版本、`libcef.so` 与 `libmcef.so` 状态
- **行为**：桌面化身份（伪装 Windows Chrome）、允许自动播放
- **服务**：自动启动开关、运行状态入口、手动启动
- **运行状态**：帧计数、MC 连接状态、最近 40 行日志

### 运行状态页的数据怎么来的

host 跑在 `.cefhost` 进程，界面在主进程 —— **两个 JVM，内存不共享**。
所以状态不走 static 字段（那样 UI 永远读到初始值），而是 host 每 2 秒写
`files/status.json`，界面轮询该文件。

两个实现细节值得记住：

- **写临时文件再 rename**：UI 可能正好在读，直接覆盖会读到截断的 JSON
- **用 `updatedAt` 判活**：host 被系统杀掉时来不及把 `running` 改 false，
  只认 10 秒内更新过的状态，否则界面会永远显示「运行中」

状态行会区分：`承载进程未启动` / `承载进程已停止` / `等待首帧` /
`有画面但未送达` / `正常`。最后两种的含义：`paints` 涨说明 CEF 在画，
`framesSent` 涨说明帧送到了 MC。

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

### 握手（必须先过这一关）

`39090` 是本机回环端口，同机任何应用都能连。协议里 `input` 能注入事件、
`eval` 能跑任意 JS、`queryReply` 能伪造结果 —— 不校验等于交出浏览器控制权。

连接建立后首包必须是 hello，3 秒内不到或不匹配就断开：

```
{"cmd":"hello","protocol":2,"token":"mcef-ipc-v2"}
```

校验通过后 host 回：

```
{"cmd":"hello","protocol":2,"hostVersion":"<包版本>"}
```

| 校验点 | 不符时的日志 |
|---|---|
| `cmd == "hello"` | `handshake rejected: not hello` |
| `token == "mcef-ipc-v2"` | `handshake rejected: bad token` |
| `protocol == PROTOCOL_VERSION` | `handshake rejected: protocol N != 2 (mod 与插件版本不匹配，需同时更新)` |
| 3 秒内收到首包 | `handshake timeout` |

**改协议字段或语义时必须同步 bump `PROTOCOL_VERSION`**（以及 mod 侧
`kProtocolVersion`）。mod 与插件分开发布，版本对不上时宁可拒绝连接。

### 命令表

| 方向 | 命令 |
|---|---|
| MC → host | `hello` / `create` / `destroy` / `resize` / `loadUrl` / `eval` / `input` / `domSnapshot` / `queryReply` / `reload` … |
| host → MC | `hello` / `frame`（JSON 头 + payload）、`query`、`title`、`url` … |

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
