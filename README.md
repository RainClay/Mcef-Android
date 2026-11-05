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

## Java 版本

```
MC 1.20.1  →  class v61 (Java 17)   ← 运行时必须能加载
libmcef.so →  纯 native，无字节码   ← 无冲突
```

现在这条已经不是问题：本模块不再依赖 Cefrium 的 v69 类，ForgeGradle 解析来的
依赖全是 v61。用任意 JDK 17+ 运行 Gradle 即可，输出固定为 Java 17：

```gradle
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType(JavaCompile).configureEach {
    options.release = null      // 关键：禁用 --release
}
```

**为什么不用 `--release 17`**：它把 API 视图切回 Java 17，一旦 classpath 里有
更高版本的类就直接报 `class file has wrong version`。
`-source/-target` 只约束输出，不限制输入。

产物验证：

```
$ od -An -tx1 -N8 build/classes/java/main/com/cinemamod/mcef/MCEFBrowser.class
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

驱动链路有三道闸，缺一条就会退化成递归或每帧数百次 JNI：

1. `RenderTickEvent.START` 只做 `MCEFRenderer.beginMinecraftFrame()`（纯 Java 计数，
   不碰 JNI、不碰 GL）。
2. `getTextureID()` 用帧号去重：**同一浏览器同一 MC 帧最多 pump 一次**。
3. `tick()` 内部一律用 `peekTextureID()` 检查纹理状态，**绝不回调 `getTextureID()`**，
   并用 `AtomicBoolean` 防重入；`version <= 0` 或与 `lastVersion` 相同时直接返回，
   不 copy、不 swap、不上传。

```
getFrameInfo() → 版本号变了才 copyFrame() → MCEFRenderer.onPaint()
```

版本没变就零开销返回（省掉一次 3.5 MB 拷贝）。直接缓冲按需扩容并复用。

### 2. 纹理上传：`GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV` + CPU 侧 R/B 交换

CEF 给的是 BGRA。当前代码在 `MCEFRenderer.onPaint` 里先把每个像素的
R/B 字节互换，再用 `glTexImage2D(..., GL_RGBA, w, h, 0, GL_BGRA,
GL_UNSIGNED_INT_8_8_8_8_REV, buffer)` 上传。

这个组合不是"按规范推出来"的，是实测出来的：

- `GL_RGBA + GL_UNSIGNED_BYTE` 在 MobileGlues / gl4es 上会崩在驱动内部拷贝
  （`__memcpy_aarch64_simd`，`R2=0x1400` 正好是 1280×4 一行）。
- `GL_BGRA + INT_8_8_8_8_REV` 是这条链路上不崩的组合。
- 但直接传 BGRA 颜色是反的，说明该驱动路径实际按 RGBA 解释；因此先在 CPU
  侧交换 R/B，让"按 RGBA 读"的结果正确。

`swapRgbaInPlace` 按 `int` 批量交换（小端序 `0xAARRGGBB` → `0xAABBGGRR`）。

> **注意**：`MCEFBrowser.onPaint` 覆写点拿到的 buffer **已经过 R/B 交换**，
> 与 JCEF 文档里描述的原始 BGRA 不同。

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
| **纹理尺寸** | ✅ 默认按请求尺寸 | 可用 `-Dmcef.maxDim=N` 限制最长边，按比例等比缩放（1280×720 → 512×288） |
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

// 4) 帧由 getTextureID() 内部按 MC 渲染帧限流拉取，无需手动 tick

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
./gradlew build          # 产物：build/libs/mcef-forge-2.2.0-1.20.1-android.jar
```

运行 Gradle 的 JVM 需要 **JDK 17 或更高**；输出始终是 Java 17 字节码（MC 1.20.1 的 class 是 v61）。

### 干净 clone 为什么能构建

早先这棵树依赖一个被 `.gitignore` 排除的 `libs/` 目录，干净 clone 直接构建失败。
现在改为 ForgeGradle 6.0.54 从 Maven 解析依赖：

```gradle
plugins {
    id 'java-library'
    id 'net.minecraftforge.gradle' version '6.0.54'
}
minecraft { mappings channel: 'official', version: '1.20.1' }
dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.4.0'
}
```

`libs/` 保留为**可选**的本地覆盖入口（例如临时替换某个 jar），目录不存在也不会报错。
Gradle wrapper 已进版本库（`gradle-wrapper.jar` 在 `.gitignore` 里单独放行），
首次构建会下载 Gradle 8.8 与 MC/Forge 依赖。

> 不需要 Cefrium SDK —— 它跑在启动器进程里，MC 侧只认 `libmcef.so`。

### so 的加载

`McefNative` 只走 `System.loadLibrary("mcef")`：启动器已把 so 放进 APK 的
`lib/arm64-v8a/`，`java.library.path` 包含它。**不从 jar 解压** —— Android 上
临时目录常挂 `noexec`，解压出来的 `.so` 会 `dlopen` 失败。

加载来源记录在 `McefNative.getLoadedFrom()`，诊断时先看它。

---

## 与上游 mcef-forge 的差异

| 项 | 上游 CinemaMod mcef-forge | 本项目 |
|---|---|---|
| 底层 | JCEF → libcef（桌面 C++） | libmcef.so → IPC → 启动器 Cefrium |
| 平台 | 桌面（Windows/Mac/Linux） | **仅 Android**（需 Android 启动器） |
| 纹理格式 | `GL_BGRA + INT_8_8_8_8_REV` 直传 | 同组合 + CPU 侧 R/B 交换（驱动读法不同） |
| `onPaint` 的 buffer | BGRA | 已交换 R/B |
| 引擎初始化 | MCEF 自己下载/加载 libcef | 启动器负责，MC 侧只连 IPC |
| JNI 绑定 | 无 | `JNI_OnLoad` 动态注册（符号名不含包名） |
| 编译 | JDK 17 | **JDK 25 编译 → 输出 Java 17** |

---

## 相关项目

- [libmcef 源码](https://github.com/RainClay/Mcef-Android/tree/libmcef-source) —— `libmcef.so` 的 C++/JNI 实现（本仓库 `libmcef-source` 分支）
- [启动器集成源码](https://github.com/RainClay/Mcef-Android/tree/launcher-integration) —— Cefrium Host 侧（本仓库 `launcher-integration` 分支）
- [WebDisplays](https://github.com/CinemaMod/webdisplays)（MIT）—— 消费方，API 面按它的字节码引用裁剪
- [CinemaMod MCEF](https://github.com/CinemaMod/mcef)（LGPL-2.1）—— 同类项目的桌面实现，本项目未复用其代码

---

## 与上游的关系

- `org.cef.*`（22 类）：按 JCEF（Chromium/CEF，BSD-3-Clause）的公开 API
  独立实现，非拷贝。Android 上没有 JCEF 的 Java 运行时，这一层必须重写。
- `com.cinemamod.mcef.*`（7 类）：**独立实现**，不是 [CinemaMod MCEF]
  (https://github.com/CinemaMod/mcef)（LGPL-2.1）的衍生作品。
  类名、包名、方法名刻意保持与上游一致，只为满足 WebDisplays 的字节码引用；
  不包含上游任何源码、注释或实现逻辑。

  兼容面的确定方式：从 WebDisplays 2.0.2 的 jar 中提取字节码引用，
  得到 22 类 / 42 成员；本项目实现的公共 API 面即以此为界。

  如需自行核验：`javap -p -c` 提取 WebDisplays 的引用，与本项目 public 成员比对；
  或对比本仓库与上游的私有字段集合、字符串字面量集合（应为空交集）。
- `libmcef`（C++/JNI）与 `launcher-integration`（源与上游的部分更改后代码）：本项目原创。

---

## 许可证

| 组件 | 上游许可证 | 本仓库状态 | 处理 |
|---|---|---|---|
| `org.cef.*`（22 类） | JCEF / CEF，BSD-3-Clause | 独立实现 | 标 GPL-3.0 + 独立性声明 |
| `com.cinemamod.mcef.*`（7 类） | LGPL-2.1-or-later | 独立实现（API 兼容） | 标 GPL-3.0 + 独立性声明，不写 LGPL |
| `MCEFMod` / `McefNative` | — | 原创 | 标 GPL-3.0 |
| `native/mcef_bridge/` | — | 原创 | 标 GPL-3.0 |
| `launcher-integration` 3 个 Zalith 原文件 | GPL-3.0 | 改编 | 保留原头 + `Modified by` |
| `launcher-integration` 3 个新文件 | — | 原创 | 标 GPL-3.0 |
| Cefrium 二进制 | 未知 | **不分发** | 不随仓库分发，由启动器运行时下载 |
| WebDisplays | MIT | 消费方 | 无需动作 |

本分支整体按 [GPL-3.0](LICENSE) 发布。第三方组件的原始版权与许可声明保留在各文件头中。

