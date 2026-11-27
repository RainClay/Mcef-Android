# MCEF for Android

把 **Cefrium**（Chromium Embedded Framework 的原生 Android 移植）包装成
**JCEF 的 Java API**，让依赖 `org.cef.*` / `com.cinemamod.mcef.*` 的 mod
能跑在 Android 上的 Minecraft 里，不改一行消费方代码。

已在 **WebDisplays + MC 1.20.1 Forge + Zalith Launcher 2** 上实机验证。

---

## 仓库结构

```
Mcef_Android/
├── 1.20.1/        MC 1.20.1 Forge 侧的 org.cef.* / com.cinemamod.mcef.* 兼容层
└── 26.1.2/        直连 Cefrium 的早期探索版（不能用于 MC，仅作参考）
plugin/            引擎插件 APK：host 服务 + Cefrium + libcef.so
native/
└── mcef_bridge/   libmcef.so 的 C++/JNI 源码（CMake + NDK）
docs/
├── mod.md         1.20.1 的详细文档（架构、帧驱动、踩坑记录、能力边界）
├── plugin.md      插件的安装、构建、IPC 协议与握手
├── native.md      native 层的设计决定与握手协议
└── archive-26.1.2.md  26.1.2 的说明
```

四个工程各自独立构建，互不依赖 —— 只有 IPC 协议（握手版本号）需要同步。

---

## 架构

```
MC JVM（纯 OpenJDK，加载不到 android.*）
  └── com.cinemamod.mcef.*        零 android 依赖
        ├── org.cef.*             JCEF API 兼容层
        └── McefNative（JNI）
              └── libmcef.so      ← 引擎插件 APK 的 lib/arm64-v8a/
                    └── IPC  socket 127.0.0.1:39090
                          └── host 进程（引擎插件内）
                                （ART + Cefrium + libcef）
```

**Cefrium 只跑在引擎插件的 host 进程里，MC 侧完全不接触它。**

为什么必须这样分层：Cefrium 的 API 签名里就有 `android.content.Context`、
`android.view.KeyEvent` 这些类型，而 MC 跑在纯 OpenJDK JVM 里，加载不到
`android.*` 类，直接引用就是 `NoClassDefFoundError`。

---

## 构建

每个版本目录是独立工程，各自带 gradle wrapper。

**1.20.1 的 mod**

```bash
cd Mcef_Android/1.20.1
./gradlew build
# 产物：build/libs/mcef-forge-2.3.1-1.20.1-android.jar
```

Java 17+，Maven 解析 MC/Forge/LWJGL（ForgeGradle 6）。
`libs/` 是可选本地覆盖入口，不存在也不报错。

**native 桥**

```bash
cd native/mcef_bridge
clang++ -shared -fPIC -O2 -std=c++17 -fvisibility=hidden \
  -I include src/*.cpp -llog -o libmcef.so
```

CMake 3.22+ / NDK / `arm64-v8a` / `android-24`，产物约 134 KB。

**引擎插件**

```bash
cd plugin
bash gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 153 MB）
```

预编译产物见 [Releases](../../releases)。

---

## 能力边界

> 下表是**本项目当前实现**的能力边界，不代表 Cefrium 的上限。
> 标注「本项目未升级」的项，引擎侧已有对应 API，只是 host 还没接。

| 功能 | 状态 | 说明 |
|---|---|---|
| **画面渲染** | ✅ | OSR，帧由 `getTextureID()` 驱动 |
| **鼠标** | ✅ | move/down/up/wheel；坐标是屏幕逻辑分辨率，由 `clampX/clampY` 换算 |
| **键盘** | ✅ 支持（Cefrium ≥0.18.0） | 字符走 `imeCommitText`，按键走 `sendKeyEvent(KeyEvent)`。**五参版要 Windows 键码，别用** |
| **JS 查询** | ✅ | `cefQuery` 往返，见 `docs/mod.md` |
| **IPC 握手** | ✅ | 首包必须 `{cmd:hello, protocol, token}`，3 秒超时断开 |
| **resize** | ✅ | native 转发命令，host 调 `setSize()` |
| **暂停保护** | ✅ | 暂停时不上传纹理 —— 半冻结的 GL 上下文里 `glTexImage2D` 会崩 |
| **标题回调** | ❌ `getTitle()` 恒空串 | Cefrium 0.21.0 已提供 `getTitle()`，本项目未升级 |
| **光标监听** | ❌ 不会触发 | 两侧都没有 cursor 命令 |
| **资源拦截** | ⚠️ 只能放行/拦截 | Cefrium 0.21.0 的 `CefriumResourceInterceptor` 可改写响应，本项目未升级 |
| **子 frame** | ⚠️ 只支持主 frame | Cefrium 0.19.0 起有 `PopupHandler.onCreateWindow`，本项目未接入 |

---

## 踩过的坑（挑几个）

**画布尺寸不是坐标空间。** WebDisplays 的 `hit2pixels()` 按
`方块尺寸 * 16 - 4 再 * 8` 算出 resolution（1×1 屏幕是 96×96），
交给鼠标方法的坐标范围是 `0..resolution`，不是画布像素。
两者不换算就是「点了乱跑」。

**画布尺寸也可能变。** 引擎插件的画质档会改画布边长，而 MC 侧构造时
只知道自己的默认值。帧头的 `w/h` 是唯一可信来源，要据此校正 `viewWidth`。

**`sendKeyEvent` 有两个重载。** 五参版要的是 Windows 虚拟键码；
按 `(keyCode, scanCode, modifiers, action, char)` 猜着传会让 `type` 位置
收到键码值，非法 type 让 native 直接崩 —— 表现为按特殊键整个进程被杀、
无 Java 异常、无 `hs_err`。用 `sendKeyEvent(android.view.KeyEvent)`。

**GL_UNPACK_* 会被污染。** MobileGlues 的 `mg_upload_fix_t` 按
`ROW_LENGTH` / `ALIGNMENT` / `SKIP_ROWS` / `SKIP_PIXELS` 推算源步长，
别的 mod 留下的非默认值会让它按错误步长读，越过 buffer 末尾。
上传前后必须隔离并还原。

更多见 `docs/mod.md` 的「实战踩坑记录」。

---

## 与上游的关系

| 项 | 上游 CinemaMod mcef-forge | 本项目 |
|---|---|---|
| 底层 | JCEF → libcef（桌面 C++） | libmcef.so → IPC → 引擎插件里的 Cefrium |
| 平台 | 桌面（Windows/Mac/Linux） | **仅 Android** |
| 引擎初始化 | MCEF 自己下载/加载 libcef | 引擎插件负责，MC 侧只连 IPC |
| JNI 绑定 | 无 | `JNI_OnLoad` 动态注册（符号名不含包名） |

`org.cef.*` 是**按 JCEF 的公开 API 重新写的实现**，不是拷贝；
`com.cinemamod.mcef.*` 保持同名同签名以兼容消费方，实现为全新编写。
上游代码一行没用。

---

## 版权

- 本仓库代码：见 [LICENSE](LICENSE)（GPL-3.0）
- JCEF 的 API 形状来自 Chromium/CEF（BSD）
- 上游 [CinemaMod MCEF](https://github.com/CinemaMod/mcef) 为 LGPL-2.1
- Cefrium 二进制：**不分发**，构建时由 `cefrium-sdk` AAR 解析

---

## 相关项目

- [引擎插件源码](https://github.com/RainClay/Mcef-Android/tree/host-plugin) —— Cefrium Host 侧
- [Releases](https://github.com/RainClay/Mcef-Android/releases) —— 预编译的 jar、so 与插件 APK
- [WebDisplays](https://github.com/CinemaMod/webdisplays)（MIT）—— 消费方，API 面按它的字节码引用裁剪
- [CinemaMod MCEF](https://github.com/CinemaMod/mcef)（LGPL-2.1）—— 同类项目的桌面实现，本项目未复用其代码
