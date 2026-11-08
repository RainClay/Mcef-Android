# MCEF for Android

把 **Cefrium**（Chromium Embedded Framework 的原生 Android 移植）包装成
**JCEF 的 Java API**，让依赖 `org.cef.*` / `com.cinemamod.mcef.*` 的 Minecraft mod
能在 Android 上运行，**不改一行消费方代码**。

首个目标：[**WebDisplays**](https://github.com/CinemaMod/webdisplays) —— 在 MC 世界里
创建浏览器屏幕的 mod。

> **想直接用**：取 [`v2.2.0-1.20.1-android`](../../releases/tag/v2.2.0-1.20.1-android)
> 的测试包（mod jar + 启动器 APK + `libmcef.so`），或按下表切到对应分支自行构建。
> 注意 release 是 2.2.0 测试包，`1.20.1` 分支源码已到 **2.3.1**（so 不再进 jar），
> 两者打包方式不同，以分支 README 为准。

---

## 分支说明

| 分支 | 内容 | 状态 |
|---|---|---|
| `main` | 项目总览与架构说明（本分支） | — |
| [`1.20.1`](../../tree/1.20.1) | MC 1.20.1 Forge 侧的 `org.cef.*` / `com.cinemamod.mcef.*` 兼容层 | **实机验证可用**，最完整 |
| [`libmcef-source`](../../tree/libmcef-source) | `libmcef.so` 的 C++/JNI 源码（CMake + NDK） | 可构建 |
| [`launcher-integration`](../../tree/launcher-integration) | Zalith Launcher 侧的 `.cefhost` 集成源码（overlay，非独立工程） | 可集成 |
| [`26.1.2`](../../tree/26.1.2) | 直连 Cefrium 的早期探索版 | 接口不全，**不能用于 MC** |

四个实现分支各有独立 README；`1.20.1` 那份最详细（架构、帧驱动、踩坑记录、能力边界）。

---

## 为什么需要这一层

WebDisplays 依赖 CinemaMod 的 MCEF，而 MCEF 继承自 JCEF 的 `CefBrowserOsr`：

```
WebDisplays
   └──依赖──▶  com.cinemamod.mcef.MCEFBrowser
                    └──extends──▶  org.cef.browser.CefBrowserOsr   (JCEF)
                                        └── JNI ──▶  libcef（桌面 C++）
```

JCEF 只是 libcef 的 Java 胶水，Android 上没有它的运行时。所以本项目做的事是：
**保持上层 API 形状不变，把最底下那层换掉。**

但换法有个硬约束：**Minecraft 跑在纯 OpenJDK JVM 里，不是 ART。**
它加载不到 `android.*` 类 —— 而 Cefrium 的 API 签名里就有：

```java
public static synchronized boolean Cefrium.initialize(android.content.Context);
public android.os.Bundle CefriumBrowser.saveState();
public void CefriumBrowser.sendKeyEvent(android.view.KeyEvent);
```

**所以 MC 侧那一层必须零 `android.*` 依赖。** 验证方法：官方 `mcef-3.3.1-26.1.jar`
里 `android/` 引用数是 **0**。

---

## 最终架构

```
MC JVM（纯 OpenJDK，加载不到 android.*）
  └── com.cinemamod.mcef.*        零 android 依赖
        ├── org.cef.*             JCEF API 兼容层
        └── McefNative（JNI）
              └── libmcef.so      ← 打进启动器 APK 的 lib/arm64-v8a/
                    └── IPC  socket 127.0.0.1:39090
                          └── 启动器 .cefhost 进程
                                （ART + Cefrium 0.17.1 + libcef）
```

**Cefrium 只跑在启动器进程里，MC 侧完全不接触它。**

帧驱动链（2.3.x）：

```
MCEFMod @ RenderTickEvent.START
  └── MCEFRenderer.beginMinecraftFrame()   // 只 ++ 帧号，不碰 JNI/GL
        └── WebDisplays → getTextureID()
              └── claimPumpForFrame(帧号)  // 同一 MC 帧只放行一次
                    └── tick()
                          ├── peekTextureID()      // 不触发 pump，防自递归
                          ├── dispatchPendingQueries()
                          ├── getFrameInfo()       // version<=0 或没变就返回
                          ├── copyFrame()
                          └── onPaint()            // R/B 交换 + glTexImage2D
```

---

## 关键设计决定（都有实测依据）

### 1. `org.cef.*` 只实现被真正调用到的成员

不照抄 JCEF 全集。依据是从消费方 jar 里提取字节码引用：

```bash
find wd_extract -name "*.class" | xargs -I{} javap -p -c {} \
  | grep -oE "// (Method|InterfaceMethod|Field) (org/cef|com/cinemamod/mcef)[^ ]*"
```

WebDisplays 2.0.2 的结果：**22 个类引用、42 个成员引用**。多写的成员只会让人
误以为「已经支持了」。**宁可缺，不要假。**

### 2. JNI 用动态注册，符号名不含包名

原来的 `libmcef.so` 每个函数都叫
`Java_net_ccbluex_liquidbounce_mcef_nativebridge_McefNative_init` ——
**包名被编译进符号名**，换个包名就 `UnsatisfiedLinkError`。

改成 `JNI_OnLoad` + `RegisterNatives`，`kTargetClasses` 里同时登记 LiquidBounce 与
本项目两个类路径。加新包名只需往列表加一行，不新增导出符号。

### 3. 帧上传由 `getTextureID()` 驱动，不是 RenderTickEvent

**这是最关键的一条。** 试过在 `RenderTickEvent.START` 上传，那是帧的最开头，
MC 可能还在切 FBO、用别的 texture unit，此时 `glTexImage2D` 在 MobileGlues 上
直接段错误（`libmobileglues+0x5971f0`）。

改成在 `getTextureID()` 里按需上传：WebDisplays 的 `ScreenRenderer` 调用它之后
会立刻 `_setShaderTexture(0, texId)` 采样 —— **那一刻 GL 正处于「准备画这张纹理」
的完整状态**，最安全。

配套的限流有三道闸，缺一不可：

- `peekTextureID()` 供 `tick()` 内部检查纹理是否创建，不触发 pump；
  否则就是 `getTextureID → beforeUse → tick → getTextureID` 自递归
  （实测 `-Xss2040k` 下 6.2 万层、`-Xss512k` 下 7331 层）。
- `MCEFBrowser.pumping`（`AtomicBoolean`）兜底防重入。
- `claimPumpForFrame()` 按 `RenderTickEvent.START` 递增的帧号去重；
  不去重则 WebDisplays 每个屏幕面、每个渲染 pass 都会触发一次
  `getFrameInfo`（实测过 **62258 次/帧**）。

`version <= 0` 是 native 初始化时的全透明占位帧，不复制、不上传；
`lastVersion` 只在 GL 上传成功后推进，失败时下一帧允许重试。

### 4. 纹理格式用 `GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV`，但 CPU 侧要换 R/B

三种组合的实测结果：

| 组合 | 结果 |
|---|---|
| `GL_RGBA + GL_UNSIGNED_BYTE` | gl4es 崩在 `__memcpy_aarch64_simd` |
| `GL_BGRA + REV`，不交换 | 不崩，但画面红蓝反转 |
| `GL_BGRA + REV`，先交换 | **正确（本实现）** |

`GL_BGRA` 这条路径上驱动并没有按 BGRA 语义处理，而是当 RGBA 读了。解法：CPU 侧
先把字节换成 `[R][G][B][A]`，让「按 RGBA 读」的结果正好正确，同时保留 `GL_BGRA`
格式参数走不崩的那条路。

### 5. so 放 APK 里，不塞 jar

Android 上 `/data/local/tmp` 之类临时目录常挂 `noexec`，从 jar 解压 `.so` 再
`System.load` 会失败。启动器把 `libmcef.so` 打进 APK 的 `lib/arm64-v8a/`，
`System.loadLibrary("mcef")` 即可 —— 权限和 SELinux 上下文都是对的。

**当前代码里 jar 不带 so**（2.3.0 起删掉解压回退与 `-PwithNative`）。这样可避免
mod 版本与启动器 so 版本错配 —— JNI 是动态注册，错配只报 `UnsatisfiedLinkError`，
看不出是版本问题。

### 6. resize 只转发，不在本地伪造帧

Cefrium ≥0.15.0 提供 `CefriumBrowser.setSize()`。native 侧只发 `{"cmd":"resize"}`
给 host，**不动已发布的帧快照、不改本地宽高**。若在本地先换成一张新尺寸的全透明帧
并 `frame_version++`，客户端会把这张白帧上传，而页面静止时 CEF 不再回调
`OnPaint`，白屏会一直留着。

---

## 已知限制

| 功能 | 状态 | 原因 |
|---|---|---|
| **`resize`** | ✅ 支持（Cefrium ≥0.15.0） | native 转发命令，host 调 `setSize()`，新尺寸帧由 host 推回 |
| **鼠标输入** | ✅ 通 | move/down/up/wheel 直连 `CefriumBrowser` 鼠标 API；坐标是 OSR 画布像素 |
| **`cefQuery` / `sendQuery`** | ✅ 已通（2.3.0） | 页面 → host → native 队列 → `CefQueryCallbackHandler` → `queryReply` → 页面 |
| **键盘输入** | ❌ 不支持 | `CefriumBrowser` 没有键盘 API。`sendKey*` 会发出去，host 记录「后端不支持」后丢弃 |
| **标题回调** | ❌ `getTitle()` 恒空串 | IPC 桥没有取标题的通道 |
| **光标监听** | ❌ 不会触发 | 两侧都没有 cursor 命令，Cefrium 也没暴露回调 |
| **资源拦截** | ⚠️ 只能放行/拦截 | 桥只给 URL，不能改写响应体 |
| **子 frame** | ⚠️ 只支持主 frame | OSR 未区分 frame |
| **页面视频** | ⚠️ 取决于 Cefrium 版本 | 0.15.0 修了 OSR 视频不显示与全屏崩溃（[cef-android#16](https://codeberg.org/cefrium/cef-android/issues/16)）；0.11.0 及更早仍是有声音无画面 |
| **投屏（MediaRouter）** | ⚠️ 需 host 侧依赖 | 用本地 AAR 依赖时 `androidx.mediarouter` 会丢失；host 侧已接媒体自动播放开关 |

`libcef.so` 本身带 H.264 / AAC（`H264Decoder` / `avc1` / `mp4a` 符号都在），
所以视频问题不是编解码器缺失，而是 OSR 下硬解走 GPU overlay 路径。

---

## 构建与发布

- `1.20.1`：`./gradlew build`（ForgeGradle 6 + Maven 解析 MC/Forge/LWJGL，
  JDK 17+，输出 Java 17 字节码）。`libs/` 是可选的本地覆盖入口，不存在也不报错。
- `libmcef-source`：CMake 3.22+ / NDK / `arm64-v8a` / `android-24`，产物约 122 KB。
- `launcher-integration`：Zalith Launcher 的 **source overlay**，需按 README 里给的
  基线 commit 覆盖到对应工程树，不是独立 Android 项目。
- 预编译产物见 [Releases](../../releases)。

---

## 版权

- 本仓库代码：见 [LICENSE](LICENSE)（GPL-3.0）
- JCEF 的 API 形状来自 Chromium/CEF（BSD）
- 上游 [CinemaMod MCEF](https://github.com/CinemaMod/mcef) 为 LGPL-2.1
- `launcher-integration` 分支的 Zalith Launcher 源码：GPL-3.0，保留原版权声明

`org.cef.*` 是**按 JCEF 的公开 API 重新写的实现**，不是拷贝；
`com.cinemamod.mcef.*` 保持同名同签名以兼容消费方，实现为全新编写。
