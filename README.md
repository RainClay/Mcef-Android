# mcef-forge-1.20.1

让 **WebDisplays** 这类依赖 JCEF API 的 mod，在 **Android + MC 1.20.1 Forge** 上跑起来。

做法：按 JCEF 的公开 API 重写 `org.cef.*` / `com.cinemamod.mcef.*` 两层，
底层不接 JCEF 的 libcef，而是接启动器里已经在跑的 **Cefrium**（通过 `libmcef.so` 的 IPC）。

- 版本：`2.2.1-1.20.1-android`
- 许可证：[GPL-3.0-or-later](LICENSE)
- 目标：Forge 1.20.1（`47.4.0`），Java 17 字节码
- modId：`mcef`（与上游同名，见下方「与上游的关系」）

---

## 架构

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

而 Cefrium 的 API 签名里带 Android 类型：

```java
public static synchronized boolean Cefrium.initialize(android.content.Context);
public android.os.Bundle CefriumBrowser.saveState();
public void CefriumBrowser.sendKeyEvent(android.view.KeyEvent);
public static CefriumBrowser CefriumBrowser.createWithSurface(android.app.Activity);
```

在 MC 里 `import com.cefrium.Cefrium` 就是运行期 `NoClassDefFoundError: android/content/Context`。

所以 **MC 侧这一整套必须零 `android.*` 依赖**，只能走 JNI + IPC。
可以验证：LiquidBounce 的 `mcef-3.3.1-26.1.jar` 里 `android/` 引用数是 **0**。

---

## 帧驱动：三道闸

这是本项目最容易出错的地方，改之前先读完这一节。

```
RenderTickEvent.START
  └── MCEFRenderer.beginMinecraftFrame()      // 只 ++，不碰 JNI/GL
        │
WebDisplays 渲染屏幕时调 getTextureID()
  └── claimPumpForFrame(minecraftFrameId)     // 同帧只放行一次
        └── MCEFBrowser.tick()
              ├── peekTextureID()             // 不触发 pump，防递归
              ├── getFrameInfo()              // version 没变直接返回
              ├── copyFrame()                 // 复用直接缓冲
              └── MCEFRenderer.onPaint()      // swap + glTexImage2D
```

**三个约束，缺一个就退化：**

1. **`RenderTickEvent.START` 只递增纯 Java 帧号。** 不在这里做 JNI、取帧或 GL。
   在帧最开头上传会和驱动内部状态打架（MobileGlues `libmobileglues.so+0x5971f0` 段错误）。
2. **`getTextureID()` 用帧号去重。** WebDisplays 每个屏幕面、每个渲染 pass 都会调它；
   不去重就是每帧几十上百次 `getFrameInfo`（实测过 62258 次/帧）。
3. **`tick()` 内部一律用 `peekTextureID()`。** 它绝不能回调 `getTextureID()` ——
   那就是 `getTextureID → beforeUse → tick → getTextureID` 自递归，
   实测栈深 6.2 万层，`-Xss512k` 时 7331 层就崩。另用 `AtomicBoolean` 防重入。

版本号守门：`version <= 0`（native 初始化的全透明占位帧）或 `== lastVersion` 时直接返回，
不 copy、不 swap、不上传。`lastVersion` 只在**上传成功后**推进，GL 失败下次还能重试同一帧。

### 宽高比

默认**不限制尺寸**（`-Dmcef.maxDim=0`）。设了上限就按最长边**等比**缩放：

```
1280×720  + -Dmcef.maxDim=512  →  512×288   （保持 16:9）
```

早期版本把宽高分别 clamp 到 512，1280×720 会变成 512×512 —— 网页被压成正方形，已修。

---

## 纹理上传：实测出来的组合

CEF 给的是 BGRA。`MCEFRenderer.onPaint` 做两件事：

```java
swapRgbaInPlace(buffer, width * height);          // CPU 侧 R/B 交换
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
             GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
```

**这个组合不是按规范推的，是试出来的：**

| 组合 | 结果 |
|---|---|
| `GL_RGBA` + `GL_UNSIGNED_BYTE` | gl4es 崩在 `__memcpy_aarch64_simd`（`R2=0x1400` = 1280×4，正好一行） |
| `GL_BGRA` + `INT_8_8_8_8_REV` 直传 | 不崩，但**红蓝反转** |
| `GL_BGRA` + `INT_8_8_8_8_REV` + CPU 交换 | ✅ 当前方案 |

也就是说这条驱动路径实际按 RGBA 解释数据，所以先交换再交给它。

`swapRgbaInPlace` 按 `int` 批量处理（小端序 `0xAARRGGBB` → `0xAABBGGRR`）。

> **`MCEFBrowser.onPaint` 覆写点拿到的 buffer 已经交换过**，不是 JCEF 文档里的原始 BGRA。

### GL 状态卫生

- 不设 `GL_UNPACK_ROW_LENGTH`：CEF 的缓冲是紧凑的（`stride == width*4`），GL 默认值本来就对。
  设了 `= width` 不还原会污染 MC 所有 GUI 贴图的上传。
- 上传前后 `GlStateManager._bindTexture(0)` 解绑，不把绑定位留给 MC 后续绘制。
- 纹理创建用 `GlStateManager._bindTexture` + `RenderSystem.texParameter` 而不是裸 GL ——
  它们会同步 MC 自己的 GL 状态缓存。

---

## 用法

```java
// 1) 初始化桥（默认端点 127.0.0.1:39090）
MCEF.initialize();

// 2) 或在桥就绪后做事
MCEF.scheduleForInit(app -> { /* ready */ });

// 3) 建浏览器（WebDisplays 的实际入口）
MCEFBrowser browser = MCEF.createBrowser("https://example.com", true, 1280, 720);

// 4) 渲染：取 GL 纹理 id 直接画
int texId = browser.getRenderer().getTextureID();
RenderSystem._setShaderTexture(0, texId);
// ... 手写 quad ...
```

`getRenderer().getTextureID()` 是唯一需要的入口 —— 它内部会按帧限流拉取并上传最新帧，
**不需要手动调 `tick()`**。WebDisplays 的 `ScreenRenderer` 就是这么用的：
绑原始 GL 纹理 id 直接画，不经过 MC 的 `TextureSetup` / `GuiGraphicsExtractor`。

输入：

```java
browser.setScreenResolution(res.x, res.y); // 先告知屏幕的逻辑分辨率
browser.sendMouseMove(x, y);              // 坐标是 0..resolution，不是画布像素
browser.sendMousePress(x, y, 0);          // button: 0=左 1=中 2=右
browser.sendMouseRelease(x, y, 0);
browser.sendMouseWheel(x, y, 0, -3);      // deltaX, deltaY

browser.sendKeyPress(glfwKey, scanCode, modifiers);   // GLFW 键码
browser.sendKeyRelease(glfwKey, scanCode, modifiers);
browser.sendKeyTyped('a', 0);                          // 直接提交字符
```

坐标为什么不是画布像素：WebDisplays 的 `ScreenBlock.hit2pixels()` 按
`屏幕方块尺寸 * 16 - 4 再 * 8` 算出 resolution（1×1 屏幕是 96×96，
2×1 是 224×96），命中点归一化后乘上它。画布默认 1920×1080，
两边空间不一致，所以由 `clampX/clampY` 按 `screenResolution` 换算。
`resize()` 在 WebDisplays 语境下只记录坐标空间，
真要改画布尺寸用 `setCanvasSize()`。

键盘键码也是两套：Minecraft 在 Android 上仍用 GLFW 键码
（`GLFW_KEY_A = 65`），Cefrium 要 Android KeyEvent 键码
（`KEYCODE_A = 29`）。`GlfwToAndroidKey` 负责换算，未收录的键直接跳过。

导航与脚本：

```java
browser.loadURL("https://example.com");
browser.executeJavaScript("document.title", "", 0);
String url = browser.getURL();
```

---

## 构建

```bash
./gradlew build          # → build/libs/mcef-forge-2.2.1-1.20.1-android.jar
```

运行 Gradle 的 JVM 需要 **JDK 17 或更高**；输出固定为 Java 17 字节码（MC 1.20.1 的 class 是 v61）。

### 依赖

ForgeGradle 6.0.54 从 Maven 解析，干净 clone 可直接构建：

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

`libs/` 保留为**可选**的本地覆盖入口，目录不存在也不报错。
Gradle wrapper 已进版本库（`gradle-wrapper.jar` 在 `.gitignore` 里单独放行），
首次构建会下 Gradle 8.8 与 MC/Forge 依赖。

### 为什么不用 `--release 17`

`--release` 把 API 视图锁回 Java 17，classpath 里一旦有更高版本的类就报
`class file has wrong version`。`-source/-target` 只约束输出，不限制输入。
`build.gradle` 里显式写了 `options.release = null`。

产物验证：

```
$ od -An -tx1 -N8 build/classes/java/main/com/cinemamod/mcef/MCEFBrowser.class
  ca fe ba be 00 00 00 3d      ← 0x3d = 61 = Java 17
```

---

## so 从哪来

`libmcef.so` 由 [`libmcef-source`](https://github.com/RainClay/Mcef-Android/tree/libmcef-source) 分支的
`native/mcef_bridge/` 编译（CMake + NDK，`aarch64-linux-android-clang++`）。约 122 KB。

`McefNative.load()` 只有一条路：**`System.loadLibrary("mcef")`**。
启动器把 so 放进 APK 的 `lib/arm64-v8a/`，`java.library.path` 里就有。
失败时抛出的 `UnsatisfiedLinkError` 会带上 `java.library.path` 的实际取值。

**jar 里不带 so。** 早先有过「从 jar 内 `natives/arm64-v8a/` 解压到 `java.io.tmpdir`
再 load」的回退，以及配套的 `-PwithNative` 打包开关，都已删除：

- so 是启动器管的，mod jar 再带一份等于有两个版本源；
- JNI 走 `RegisterNatives` 动态注册，版本错配报的是 `UnsatisfiedLinkError`，
  从堆栈上看不出是版本对不上，排查成本远高于省下的那点便利。

构建就是普通的 `./gradlew build`，产物完整可用。

### 桥的 JNI 注册

原来的 `libmcef.so` 每个函数都叫 `Java_net_ccbluex_liquidbounce_mcef_nativebridge_McefNative_init` ——
**符号名里硬编码了 Java 包名**，换包名就 `UnsatisfiedLinkError`。

改成 `JNI_OnLoad` + `RegisterNatives`：

```cpp
const char* const kTargetClasses[] = {
    "net/ccbluex/liquidbounce/mcef/nativebridge/McefNative",   // 既有消费方
    "com/cinemamod/mcef/nativebridge/McefNative",              // 本项目
};
```

注册靠「类名 + 方法名 + 签名」，符号名不再参与匹配。加新包名只需往列表加一行。
代价：`kMethods` 里的签名必须和 Java 的 `native` 声明逐字一致，改 Java 声明时要同步这张表。

---

## 已知限制

| 功能 | 状态 | 原因 |
|---|---|---|
| **`resize`** | ✅ 支持（Cefrium ≥0.15.0） | native 转发 `{"cmd":"resize"}`，host 调 `CefriumBrowser.setSize()`。新尺寸的帧由 host 推回，本地不伪造 —— 伪造会导致「白屏且不再重绘」 |
| **键盘输入** | ✅ 支持（Cefrium ≥0.18.0） | 字符走 `imeCommitText`，按键走 `sendKeyEvent(android.view.KeyEvent)`。GLFW 键码由 `GlfwToAndroidKey` 换算。**不要用五参 `sendKeyEvent`** —— 它要的是 Windows 虚拟键码，传 Android 键码会让页面收到 `key='' code=''`，猜错参数位置还会让 native 崩 |
| **标题回调** | ❌ `getTitle()` 恒空串 | IPC 桥没有取标题的通道 |
| **`sendQuery`** | ✅ 已通 | 2.3.0 起与 `cefriumQuery` 是同一条链路的两端，见下节 |
| **`CefQueryCallbackHandler`** | ✅ 会被调用 | 每帧从 native 队列取查询分发给处理器，结果经 `queryReply` 回 host |
| **光标监听** | ❌ 不会触发 | native 与 host 两侧都没有 cursor 命令，Cefrium 也没暴露回调 |
| **资源拦截** | ⚠️ 只能放行/拦截 | 桥只给 URL，不能改写响应体 |
| **子 frame** | ⚠️ 只支持主 frame | OSR 未区分 frame |
| **页面视频** | ⚠️ 取决于 Cefrium 版本 | 0.15.0 修了 OSR 视频不显示与全屏崩溃（[cef-android#16](https://codeberg.org/cefrium/cef-android/issues/16)）。0.11.0 及更早仍是有声音无画面 |

鼠标输入的坐标是 **屏幕逻辑分辨率**（`ScreenData.resolution`），
由 `clampX/clampY` 换算到画布。分辨率未知时退回只夹边界。

### 暂停与 GL 状态

游戏暂停时 `getTextureID()` 与 `tick()` 都会提前返回 —— 暂停期间 GL 上下文
处于半冻结状态，此时 `glTexImage2D` 会在 MobileGlues 里崩。

纹理更新走两条路：尺寸没变用 `glTexSubImage2D`（只更新像素），
尺寸变了才 `glTexImage2D`（重新分配）。每帧都重新分配会让驱动不断重算
内部行缓冲，resume 后尤其脆弱。

上传前还会把 `GL_UNPACK_*`（ROW_LENGTH / ALIGNMENT / SKIP_ROWS /
SKIP_PIXELS）显式设为紧凑布局并在 `finally` 还原 —— MobileGlues 的
`mg_upload_fix_t` 会按这些状态推算源数据步长，别的 mod 留下的非默认值
会让它按错误步长读、越过 buffer 末尾。

### 查询链路

页面调 `window.cefQuery(request)` 后，请求要走一个来回 —— 因为只有 host 进程
持有 Cefrium 的 callback 实例，MC 侧算出结果也没法直接回页面：

```
页面 cefQuery(request)
  └── host: setQueryHandler(anyOrigin)            └─ 存 callback 进 entry.pendingQueries
        └── sendQuery → IPC {"event":"query"}         queryId/request/origin/persistent
              └── native: mcef_host_push_query        入队（上限 64，超出丢最旧）
                    └── McefNative.takeQuery(handle)  每帧取，最多 8 条
                          └── CefQueryCallbackHandler.onQuery(...)   业务处理
                                └── McefNative.replyQuery(...)
                                      └── IPC {"cmd":"queryReply"}
                                            └── host: entry.pendingQueries.remove(queryId)
                                                  └── callback.success(result)   页面收到
```

三个设计点：

- **分发在帧版本门控之前**。`tick()` 里若先判 `version == lastVersion` 就返回，
  没有新帧时查询会一直压到下次重绘 —— 而查询本身常常不引起重绘。
- **没人认领就立刻失败**。处理器全返回 `false` 时直接按 `ERR_NOT_FOUND` 回填，
  否则页面的 `cefQuery` 要等到自己超时。
- **重复答复只认第一次**。Cefrium 一条查询只能答复一次，`QueryCallback` 用
  `AtomicBoolean` 挡住第二次。

历史上这里是断的：host 把 query 的 request 当成 DOM 快照 `sendDom` 出去，
再 `callback.success("ok")`，页面拿到的永远是字符串 `ok`，WebDisplays 解析失败后
请求直接作废。2.3.0 修掉了。

---

## 实战踩坑记录

每一条都是实际崩过或黑屏过的。编译期全都发现不了 —— 消费方用的是官方 jar，
链接错误只在运行期炸。

### 1. `constructed 0 mods: [], but had 1 mods specified: [mcef]`

**根因**：`mods.toml` 声明了 modId，但 jar 里没有带 `@Mod("mcef")` 的类。
Forge 不只看 toml，还要找入口类。

**修法**：加 `MCEFMod`。

### 2. `ClassNotFoundException: org.cef.handler.CefMessageRouterHandler`

**根因**：`org.cef.*` 和 `com.cinemamod.mcef.*` 的类没补齐。

**修法**：从 WebDisplays 的 jar 里提取全部引用再逐个核对：

```bash
find wd_extract -name "*.class" | xargs -I{} javap -p -c {} \
  | grep -oE "// (Method|InterfaceMethod|Field) (org/cef|com/cinemamod/mcef)[^ ]*"
```

**结论：不能靠猜，要靠字节码引用核对。**

### 3. `AbstractMethodError: ... 'abstract void onCefInit(MCEFApp)'`

**根因**：`MCEFInitListener` 签名写错。上游是 `void onInit(boolean successful)`，
我写成了 `onCefInit(MCEFApp)` —— 方法名和参数都错。WebDisplays 的 lambda 是针对上游签名生成的。

### 4. `createBrowser failed: handle=0 lastError=mcef is not initialized`

**根因**：WebDisplays 不走 `MCEF.createBrowser()`，而是
`new WDClientBrowser(...)` → `super(...)` → `MCEFBrowser` 构造，
绕开了 `MCEF.createBrowser()` 里的 `ensureInitialized()`。

**修法**：把 `ensureInitialized()` 放进 `MCEFBrowser` 构造函数。

### 5. 黑屏但宿主侧 `paints=8923 frames=768`

**根因**：`MCEFRenderer.initialize()` 从未被调用，
`onPaint` 开头 `if (textureId[0] == 0) return;` 每帧直接丢弃。

**修法**：在 `tick()` 里懒初始化。

### 6. `libmobileglues.so+0x5971f0` 段错误

**根因**：上传时机不对。原来在 `RenderTickEvent.START` 上传 —— 那是帧最开头，
MC 可能还在切 FBO、用别的 texture unit。

**修法**：改到 `getTextureID()` 里按需上传。WebDisplays 调它之后会立刻
`_setShaderTexture(0, texId)` 采样，那一刻 GL 正处于「准备画这张纹理」的完整状态。

### 7. gl4es 下的 `__memcpy_aarch64_simd` 崩溃

**根因**：启动器 GL 渲染器切成 `libng_gl4es.so`，它处理 1280×720（NPOT、3.6 MB）
纹理上传时内部临时缓冲越界（`R2=0x1400=1280*4`，正好一行）。

**修法**：换回 MobileGlues。gl4es 这条路上无解。

### 8. `getTextureID() → beforeUse → tick() → getTextureID()` 自递归

**根因**：`tick()` 里用 `renderer.getTextureID()` 检查纹理是否已创建，
而 `getTextureID()` 又调 `beforeUse`（也就是 `tick()`）。

**修法**：加 `peekTextureID()`（不触发 pump）供内部使用，`AtomicBoolean` 防重入，
`getTextureID()` 按 MC 帧号去重。

---

## 与上游 mcef-forge 的差异

| 项 | 上游 CinemaMod mcef-forge | 本项目 |
|---|---|---|
| 底层 | JCEF → libcef（桌面 C++） | libmcef.so → IPC → 启动器 Cefrium |
| 平台 | 桌面（Windows/Mac/Linux） | **仅 Android**（需 Android 启动器） |
| 纹理上传 | `GL_BGRA + INT_8_8_8_8_REV` 直传 | 同组合 + CPU 侧 R/B 交换 |
| `onPaint` 的 buffer | BGRA | 已交换 R/B |
| 引擎初始化 | MCEF 自己下载/加载 libcef | 启动器负责，MC 侧只连 IPC |
| JNI 绑定 | 无 | `JNI_OnLoad` 动态注册（符号名不含包名） |
| 帧驱动 | 回调 | 按帧号限流的轮询 |

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
| `org.cef.*`（22 类） | JCEF / CEF，BSD-3-Clause | 独立实现 | GPL-3.0 头 + 独立性声明 |
| `com.cinemamod.mcef.*`（7 类） | LGPL-2.1-or-later | 独立实现（API 兼容） | GPL-3.0 头 + 独立性声明 |
| `MCEFMod` / `McefNative` | — | 原创 | GPL-3.0 头 |
| `native/mcef_bridge/` | — | 原创 | GPL-3.0 头 |
| `launcher-integration` 4 个 Zalith 原文件 | GPL-3.0 | 改编 | 保留原头 + `Modified by` |
| `launcher-integration` 3 个新文件 | — | 原创 | GPL-3.0 头 |
| Cefrium 二进制 | 未知 | **不分发** | 由启动器运行时下载 |
| WebDisplays | MIT | 消费方 | 无需动作 |

本分支整体按 [GPL-3.0](LICENSE) 发布。第三方组件的原始版权与许可声明保留在各文件头中。

---

## 相关项目

- [libmcef 源码](https://github.com/RainClay/Mcef-Android/tree/libmcef-source) —— `libmcef.so` 的 C++/JNI 实现
- [启动器集成源码](https://github.com/RainClay/Mcef-Android/tree/launcher-integration) —— Cefrium Host 侧
- [测试包](https://github.com/RainClay/Mcef-Android/tree/1.20.1/test-builds) —— 已编译好的 jar 与 so（2.2.0 为最新发布产物）
- [WebDisplays](https://github.com/CinemaMod/webdisplays)（MIT）—— 消费方，API 面按它的字节码引用裁剪
- [CinemaMod MCEF](https://github.com/CinemaMod/mcef)（LGPL-2.1）—— 同类项目的桌面实现，本项目未复用其代码
