# mcef-forge-1.20.1

让 **WebDisplays** 这类依赖 JCEF API 的 mod，在 **Android + MC 1.20.1 Forge** 上运行。

思路：按 JCEF 的公开 API 重写一层 `org.cef.*` / `com.cinemamod.mcef.*`，
底层不接 JCEF 的 libcef，而是接启动器里已经在跑的 Cefrium（通过 `libmcef.so` 的 IPC）。

---

## 架构（决定了整个设计的约束）

```
MC JVM（纯 OpenJDK，加载不到 android.*）
  └── com.cinemamod.mcef.*        本项目
        ├── org.cef.*             本项目（JCEF API 兼容层）
        └── McefNative（JNI）
              └── libmcef.so
                    └── IPC  socket 127.0.0.1:39090
                          └── 启动器 .cefhost 进程（ART + Cefrium + libcef）
```

### 为什么不能直接调 Cefrium

MC 跑在**纯 OpenJDK JVM** 里，不是 ART：

```
JRE version: OpenJDK Android Runtime Environment (25.0.5)
Java VM: OpenJDK 64-Bit Server VM ... linux-aarch64
```

而 Cefrium 的 API 签名里就有 Android 类型：

```java
public static synchronized boolean Cefrium.initialize(android.content.Context);
public android.os.Bundle CefriumBrowser.saveState();
public void CefriumBrowser.sendKeyEvent(android.view.KeyEvent);
public static CefriumBrowser CefriumBrowser.createWithSurface(android.app.Activity);
```

在 MC 里 `import com.cefrium.Cefrium` 就是运行期 `NoClassDefFoundError: android/content/Context`。

**所以 MC 侧这一整套必须零 `android.*` 依赖**，只能走 JNI + IPC。
（可以验证：LiquidBounce 的 `mcef-3.3.1-26.1.jar` 里 `android/` 引用数是 **0**。）

---

## 桥的改造：动态注册 JNI

原来的 `libmcef.so` 每个函数都叫：

```
Java_net_ccbluex_liquidbounce_mcef_nativebridge_McefNative_init
Java_net_ccbluex_liquidbounce_mcef_nativebridge_McefNative_createBrowser
...
```

**符号名里把 Java 包名硬编码了**，换包名就 `UnsatisfiedLinkError`。

改成 `JNI_OnLoad` + `RegisterNatives`：

```cpp
const char* const kTargetClasses[] = {
    "net/ccbluex/liquidbounce/mcef/nativebridge/McefNative",   // MC 26.1.2 侧（既有）
    "com/cinemamod/mcef/nativebridge/McefNative",              // 本项目（新增）
};

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    for (const char* name : kTargetClasses) {
        jclass cls = env->FindClass(name);
        if (!cls) { env->ExceptionClear(); continue; }
        env->RegisterNatives(cls, kMethods, kCount);
        env->DeleteLocalRef(cls);
    }
    return JNI_VERSION_1_6;
}
```

**收益**：注册靠「类名 + 方法名 + 签名」，符号名不再参与匹配。
- 加新包名 → 往 `kTargetClasses` 加一行，不新增导出符号
- 下载的 `.so` 里已经搜不到任何 `Java_` 前缀的符号

**代价**：`kMethods` 里的签名必须和 Java 的 native 声明逐字一致，
否则 `RegisterNatives` 返回 `JNI_ERR`，运行期调用抛 `UnsatisfiedLinkError`。
改 Java 声明时这份表必须同步。

---

## Java 版本死锁与解法

```
MC 1.20.1  →  class v61 (Java 17)   ← 运行时必须能加载
libmcef.so →  纯 native，无字节码   ← 无冲突
```

现在这条其实不再是问题（不依赖 Cefrium 的 v69 类了），但构建仍**用 JDK 25 编译、
输出 Java 17**：

```gradle
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType(JavaCompile).configureEach {
    options.release = null      // 关键：禁用 --release
}
```

**为什么不能用 `--release 17`**：它把 API 视图切回 Java 17，一旦 classpath 里有
更高版本的类就直接报 `class file has wrong version`。
`-source/-target` 只约束输出，不限制输入。

产物验证：

```
$ od -An -tx1 -N8 build/classes/.../MCEFBrowser.class
 ca fe ba be 00 00 00 3d      ← 0x3d = 61 = Java 17 ✅
```

---

## 实战踩坑记录（每一条都是实际崩过/黑屏过的）

这一节按「症状 → 根因 → 修法」记录移植过程中真正踩到的坑，
因为编译期全都发现不了（消费方用的是官方 jar，链接错误只在运行期炸）。

### 1. `constructed 0 mods: [], but had 1 mods specified: [mcef]`

**根因**：`mods.toml` 声明了 modId，但 jar 里没有带 `@Mod("mcef")` 的类。
Forge 不只看 toml，还要找入口类。

**修法**：加 `MCEFMod`（注解来自真 Forge jar：`javafmllanguage`）。

### 2. `ClassNotFoundException: org.cef.handler.CefMessageRouterHandler` / `MCEFInitListener` / `CefSchemeHandlerFactory`

**根因**：`org.cef.*` 和 `com.cinemamod.mcef.*` 的类没补齐。

**修法**：从 WebDisplays 的 jar 里提取全部引用再逐个核对：

```bash
# 22 个类引用、42 个成员引用
find wd_extract -name "*.class" | xargs -I{} javap -p -c {} \
  | grep -oE "// (Method|InterfaceMethod|Field) (org/cef|com/cinemamod/mcef)[^ ]*"
```

**结论：不能靠猜，要靠字节码引用核对。**

### 3. `AbstractMethodError: ... 'abstract void onCefInit(MCEFApp)'`

**根因**：`MCEFInitListener` 签名写错了。上游是：

```java
@FunctionalInterface
public interface MCEFInitListener {
    void onInit(boolean successful);   // 方法名 onInit，参数 boolean
}
```

我写成了 `onCefInit(MCEFApp)` —— 方法名和参数都错。
WebDisplays 的 lambda 是针对上游签名生成的，必须逐字匹配。

### 4. `createBrowser failed: handle=0 lastError=mcef is not initialized`

**根因**：WebDisplays 不走 `MCEF.createBrowser()`，而是
`new WDClientBrowser(...)` → `super(...)` → `MCEFBrowser` 构造，
绕开了放在 `MCEF.createBrowser()` 里的 `ensureInitialized()`。

**修法**：把 `ensureInitialized()` 放进 `MCEFBrowser` 构造函数。

### 5. 黑屏但宿主侧 `paints=8923 frames=768`

**根因**：`MCEFRenderer.initialize()` 从未被调用，
`onPaint` 开头 `if (textureId[0] == 0) return;` 每帧直接丢弃。

**修法**：在 `tick()` 里懒初始化（它由渲染事件驱动，GL 上下文一定有效）。

### 6. `libmobileglues.so+0x5971f0` 段错误（换了多种格式/尺寸都崩）

**根因**：**上传时机不对**。原来在 `RenderTickEvent.START` 上传 —— 那是帧最开头，
MC 可能还在切 FBO、用别的 texture unit，此时 `glTexImage2D` 会和驱动内部状态打架。

**修法**：改成在 `MCEFRenderer.getTextureID()` 里按需上传。
WebDisplays 的 `ScreenRenderer` 调它之后会立刻 `_setShaderTexture(0, texId)` 采样，
**那一刻 GL 正处于「准备画这张纹理」的完整状态**。

```java
public int getTextureID() {
    if (beforeUse != null) beforeUse.run();   // 同步最新帧
    return textureId[0];
}
```

**验证**：改完后 512×512 稳定运行、帧持续上传（#1→#5）、无崩溃。

### 7. gl4es 下的 `__memcpy_aarch64_simd` 崩溃

**根因**：启动器里 GL 渲染器被切成了 `libng_gl4es.so`，
它处理 1280×720（NPOT、3.6 MB）纹理上传时内部临时缓冲越界
（`R2=0x1400=1280*4`，正好一行；源地址有效，崩在驱动内部拷贝目标）。

**修法**：换回 MobileGlues。gl4es 这条路上无解，是它的实现问题。

---

## 关键实现决定

### 1. 帧是轮询取的，不是回调

纯 JVM 里没有 Android 的 UI 线程模型，CEF 的帧由启动器经 IPC 推进 libmcef 的缓存。
所以 `MCEFBrowser.tick()` 每帧：

```
getFrameInfo() → 版本号变了才 copyFrame() → MCEFRenderer.onPaint()
```

版本没变就零开销返回（省掉一次 3.5 MB 拷贝）。直接缓冲按需扩容并复用。

### 2. 纹理上传：**不用** `GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV`

上游 MCEF 用这个组合，桌面 GL 上正确。但在 **Android + MobileGlues** 上：

```
signal 11 (SIGSEGV)
C  [libmobileglues.so+0x5971f0]
C  [libmobileglues.so+0x56b538]  glTexImage2D+0x9c
J  org.lwjgl.opengl.GL11C.nglTexImage2D
J  com.mojang.blaze3d.opengl.GlStateManager._texImage2D
j  com.cinemamod.mcef.MCEFRenderer.onPaint
```

`GL_BGRA` 不是 GLES core 的 format 枚举，MobileGlues 处理它时解引用空指针 ——
**每帧上传都可能崩**。

本实现：

```java
glTexImage2D(..., GL_RGBA, GL_UNSIGNED_BYTE, null);   // GLES 原生组合
swapRgbaInPlace(buffer, width * height);               // CPU 侧 BGRA→RGBA
glTexSubImage2D(..., GL_RGBA, GL_UNSIGNED_BYTE, buffer);
```

`swapRgbaInPlace` 按 `int` 批量交换（小端序 `0xAARRGGBB` → `0xAABBGGRR`），
88 万像素约 2~4ms。

> **注意**：`MCEFBrowser.onPaint` 覆写点拿到的 buffer **已经是 RGBA**，
> 不再是 JCEF 文档里说的 BGRA。

### 3. GL 状态卫生：不碰 `GL_UNPACK_ROW_LENGTH`

CEF 的 buffer 是紧凑的（`stride == width*4`），GL 默认值（`0` = 按纹理宽度）
本来就对。设了 `= width` 而不还原，会污染 MC **所有 GUI 贴图**的上传，
表现是整个界面贴图错位、撕裂。

同理上传前后 `bindTexture(0)` 解绑，不把绑定位留给 MC 的后续绘制。

### 4. 走 `RenderSystem` 而不是裸 GL

MC 1.20.1 的 `RenderSystem.bindTexture` / `texParameter` 会同步维护 MC 自己的
GL 状态缓存。绕过它直接用 `glBindTexture` 会让缓存与实际状态不一致。

---

## 已知限制（诚实清单）

| 功能 | 状态 | 原因 |
|---|---|---|
| **`resize`** | ⚠️ 仅本地记录 | Cefrium 无 resize API（`mcef_resize` 是空操作），帧尺寸固定于创建时 |
| **纹理尺寸** | ⚠️ 默认钳到 512×512 | 见下节「纹理尺寸限制」 |
| **标题回调** | ❌ 空实现 | IPC 桥没有同步/异步取标题的通道 |
| **光标类型回调** | ❌ 不触发 | 桥未暴露 cursor 回调 |
| **`sendQuery`（native→页面）** | ❌ 空实现 | 只有页面发起的方向可用 |
| **资源拦截（自定义响应体）** | ❌ 仅能拦截/放行 | 桥只给 URL，不能改写响应 |
| **子 frame** | ⚠️ 只支持主 frame | OSR 未区分 frame |

键盘/鼠标输入是**通的**（`sendInput` 走 `mcef_input_event_t`），
需要上层按 OSR 画布像素坐标换算。

---

## 用法

```java
// 1) 初始化桥（默认端点 127.0.0.1:39090）
MCEF.initialize();

// 2) 或在桥就绪后做事
MCEF.scheduleForInit(app -> { /* ready */ });

// 3) 建浏览器（透明 + 指定尺寸，WebDisplays 的屏幕这样用）
MCEFBrowser browser = MCEF.createBrowser("https://example.com", true, 1280, 720);

// 4) 每帧驱动
browser.tick();

// 5) 渲染：取 GL 纹理 id 直接画
int texId = browser.getRenderer().getTextureID();
RenderSystem._setShaderTexture(0, texId);
// ... 手写 quad ...
```

`getRenderer().getTextureID()` 这条路是关键：WebDisplays 的 `ScreenRenderer`
就是直接绑原始 GL 纹理 id 画的，**不经过 MC 的 `TextureSetup` / `GuiGraphicsExtractor`**。

---

## 构建

```bash
# 必须 JDK 25（-source/-target 17 的输出靠它）
JAVA_HOME=/path/to/jdk-25 ./gradlew jar
```

产物：`build/libs/mcef-forge-2.1.1-1.20.1-android.jar`（约 79 KB，含 so）

`libs/` 里的依赖（全部 `compileOnly`，不进产物）：

| 文件 | 用途 |
|---|---|
| `minecraft-1.20.1-mapped.jar` | MC 1.20.1（v61），Forge 映射版 |
| `lwjgl-3.3.3.jar` / `lwjgl-opengl-3.3.3.jar` | GL 调用 |
| `log4j-api-*.jar` | 日志 |

> 不需要 Cefrium SDK —— 它跑在启动器进程里，MC 侧只认 `libmcef.so`。

### so 的加载

`McefNative` 静态块按两条路试：

1. `System.loadLibrary("mcef")` —— 启动器已把 so 放进 APK 的 `lib/arm64`，
   而 `java.library.path` 包含它，这是最快路径；
2. 从 jar 内 `/natives/arm64-v8a/libmcef.so` 解压到临时目录再 `System.load(绝对路径)`。

加载来源记录在 `McefNative.getLoadedFrom()`，诊断时先看它。

---

## 与上游 mcef-forge 的差异

| 项 | 上游 CinemaMod mcef-forge | 本项目 |
|---|---|---|
| 底层 | JCEF → libcef（桌面 C++） | libmcef.so → IPC → 启动器 Cefrium |
| 平台 | 桌面（Windows/Mac/Linux） | **仅 Android**（需 Android 启动器） |
| 纹理格式 | `GL_BGRA + INT_8_8_8_8_REV` | `GL_RGBA + UNSIGNED_BYTE` + CPU 交换 |
| `onPaint` 的 buffer | BGRA | **RGBA**（已交换） |
| 引擎初始化 | MCEF 自己下载/加载 libcef | 启动器负责，MC 侧只连 IPC |
| JNI 绑定 | 无 | `JNI_OnLoad` 动态注册（符号名不含包名） |
| 编译 | JDK 17 | **JDK 25 编译 → 输出 Java 17** |

---

## 相关项目

- `../ZalithLauncher2-main/native/mcef_bridge/` —— `libmcef.so` 源码（本项目改了 JNI 注册方式）
- `../WDmod-网页mod/` —— WebDisplays 源码（消费方）
- `../mcef-android/` —— 同一套 JCEF API 的 MC 26.1.2 版本（直连 Cefrium，仅作参考）

---

## 版权

本项目为**保留所有权利**（All rights reserved）。

JCEF 的 API 形状来自 Chromium/CEF（BSD 许可），上游 MCEF 为 LGPL-2.1。
本项目的 `org.cef.*` 是**按 JCEF 的公开 API 重新写的实现**，不是拷贝；
`com.cinemamod.mcef.*` 保持同名同签名以兼容消费方，实现为全新编写。
