# MCEF for Android

把 **Cefrium**（Chromium Embedded Framework 的原生 Android 移植）包装成
**JCEF 的 Java API**，让依赖 `org.cef.*` / `com.cinemamod.mcef.*` 的 Minecraft mod
能在 Android 上运行，**不改一行消费方代码**。

首个目标：[**WebDisplays**](https://github.com/CinemaMod/webdisplays) —— 在 MC 世界里
创建浏览器屏幕的 mod。

---

## 分支说明

| 分支 | 目标平台 | 状态 | 说明 |
|---|---|---|---|
| `main` | — | — | 文档 + 架构说明（本分支） |
| [`1.20.1`](../../tree/mcef-1.20.1) | MC 1.20.1 Forge | **实机验证可用** | 走 JNI + IPC，是最完整的实现 |
| [`26.1.2`](../../tree/mcef-26.1.2) | MC 26.1.2 | 早期探索 | 直连 Cefrium，接口尚不完整 |

**想直接能用 → 取 `1.20.1`。**

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

## 最终架构（`1.20.1` 分支）

```
MC JVM（纯 OpenJDK，加载不到 android.*）
  └── com.cinemamod.mcef.*        零 android 依赖
        ├── org.cef.*             JCEF API 兼容层
        └── McefNative（JNI）
              └── libmcef.so      ← 打进启动器 APK 的 lib/arm64-v8a/
                    └── IPC  socket 127.0.0.1:39090
                          └── 启动器 .cefhost 进程
                                （ART + Cefrium + libcef）
```

**Cefrium 只跑在启动器进程里，MC 侧完全不接触它。**

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

改成 `JNI_OnLoad` + `RegisterNatives`：

```cpp
const char* const kTargetClasses[] = {
    "net/ccbluex/liquidbounce/mcef/nativebridge/McefNative",   // MC 26.1.2 侧
    "com/cinemamod/mcef/nativebridge/McefNative",              // 本项目
};
```

加新包名只需往列表加一行，不新增导出符号。

### 3. 帧上传由 `getTextureID()` 驱动，不是 RenderTickEvent

**这是最关键的一条。** 试过在 `RenderTickEvent.START` 上传，那是帧的最开头，
MC 可能还在切 FBO、用别的 texture unit，此时 `glTexImage2D` 在 MobileGlues 上
直接段错误（`libmobileglues+0x5971f0`）。

改成在 `getTextureID()` 里按需上传：WebDisplays 的 `ScreenRenderer` 调用它之后
会立刻 `_setShaderTexture(0, texId)` 采样 —— **那一刻 GL 正处于「准备画这张纹理」
的完整状态**，最安全。

### 4. 纹理格式用 `GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV`，但 CPU 侧要换 R/B

`GL_RGBA + GL_UNSIGNED_BYTE`（GLES 原生组合）在 MobileGlues / gl4es 上会崩在
驱动内部 memcpy（`R2 = 1280*4`，正好一行；源地址有效，崩的是驱动内部拷贝目标）。

而 `GL_BGRA` 这条路径上驱动**并没有按 BGRA 语义处理**，而是当 RGBA 读了 ——
所以颜色是反的。解法：CPU 侧先把字节换成 `[R][G][B][A]`，让「按 RGBA 读」的结果
正好正确，同时保留 `GL_BGRA` 格式参数走不崩的那条路。

### 5. so 放 APK 里，不塞 jar

Android 上 `/data/local/tmp` 之类临时目录常挂 `noexec`，从 jar 解压 `.so` 再
`System.load` 会失败。启动器把 `libmcef.so` 打进 APK 的 `lib/arm64-v8a/`，
`System.loadLibrary("mcef")` 即可 —— 权限和 SELinux 上下文都是对的。

---

## 已知限制

| 功能 | 状态 | 原因 |
|---|---|---|
| **投屏（MediaRouter）** | ⚠️ 需 stub | Cefrium 0.11.0 把 `androidx.mediarouter` 声明为 Maven 依赖；用本地 AAR 文件依赖时该依赖丢失，播放视频会因 `NoClassDefFoundError` 崩溃。需补 10 个 stub 类 |
| **视频画面** | ❌ 有声音无画面 | OSR 模式下硬件解码的视频帧走 GPU overlay 路径，`OnPaint` 拿不到。已尝试 `disable-accelerated-video-decode`，仍未解决 |
| **`resize`** | ⚠️ 仅本地记录 | Cefrium 无 resize API，帧尺寸固定于创建时 |
| **键盘输入** | ⚠️ 部分 | Cefrium 有 `sendKeyEvent(KeyEvent)`，但需把 GLFW 键码转成 Android KeyEvent，尚未实现 |

`libcef.so` 本身**带** H.264 / AAC（`H264Decoder` / `avc1` / `mp4a` 符号都在），
所以视频问题不是编解码器缺失。

---

## 版权

- 本仓库代码：见 [LICENSE](LICENSE)（GPL-3.0）
- JCEF 的 API 形状来自 Chromium/CEF（BSD）
- 上游 [CinemaMod MCEF](https://github.com/CinemaMod/mcef) 为 LGPL-2.1

`org.cef.*` 是**按 JCEF 的公开 API 重新写的实现**，不是拷贝；
`com.cinemamod.mcef.*` 保持同名同签名以兼容消费方，实现为全新编写。
