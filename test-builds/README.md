# 测试包（可直接下载使用）

这里放已编译好的构建产物，方便不装 Gradle/NDK 的环境直接取用。
源码与产物一一对应，版本号见目录名。

## 2.2.0（2026-11-05）

| 文件 | 说明 |
|---|---|
| [`libmcef-arm64-v8a.so`](2.2.0/libmcef-arm64-v8a.so) | 唯一产物。打进启动器 APK 的 `lib/arm64-v8a/` |

**jar 不进这个目录。** mod jar 里不带 so —— so 由启动器打进 APK，
`System.loadLibrary("mcef")` 命中。早先那个「jar 内含 `natives/`」的版本已撤下：
两个版本源一旦漂移，JNI 动态注册报的是 `UnsatisfiedLinkError`，
从堆栈上判断不出是版本对不上。

### 这一版改了什么

- **递归修复**：`getTextureID() → beforeUse → tick() → getTextureID()` 自递归（实测
  6.2 万层栈）已封死。`tick()` 内部改用 `peekTextureID()`，另有 `AtomicBoolean` 防重入。
- **每帧一次**：`RenderTickEvent.START` 只递增纯 Java 帧号；`getFrameInfo`/`copyFrame`/GL
  上传每个浏览器每 MC 帧最多一次（原先实测 62258 次/帧）。
- **宽高比**：去掉 512×512 分别 clamp。默认不限；`-Dmcef.maxDim=512` 时 1280×720 → 512×288。
- **so 加载**：只有 `System.loadLibrary("mcef")` 一条路（走 APK 的 lib 目录）。

### 已知限制

见仓库根 README 的「已知限制」表。要点：`resize` 无效（Cefrium 无此 API）、
键盘输入不支持（host 侧无键盘 API）、`getTitle()` 恒空串、
`CefQueryCallbackHandler` 不会被调用。鼠标 move/down/up/wheel 是通的。

### 安装

1. mod jar 放进 `mods/`（从 [`1.20.1`](../tree/1.20.1) 分支自行构建，见文末）
2. `libmcef-arm64-v8a.so` 打进启动器 APK 的 `lib/arm64-v8a/`

两个都要，缺一不可：jar 只有 Java 侧，so 只有 native 侧。

### 校验

```
so : 122.4 KB (125384 bytes), aarch64
     JNI_OnLoad 已导出；kTargetClasses 含
       net/ccbluex/liquidbounce/mcef/nativebridge/McefNative
       com/cinemamod/mcef/nativebridge/McefNative
class: Java 17 (0x3d)
```

### 已知未解决

- 键盘输入未实现（CefriumBrowser 无键盘 API）
- 标题回调、光标类型回调仍是空实现
- `resize` 在 0.11.0 上是空操作；Cefrium 0.15.0 起有 `setSize`，升级后可接

### 构建方式

源码在 [`1.20.1`](../tree/1.20.1) 分支；so 源码在 [`libmcef-source`](../tree/libmcef-source) 分支。
so 用 `aarch64-linux-android-clang++ --target=aarch64-linux-android24` 直接编出：

```bash
cmake -S native/mcef_bridge -B build/arm64-v8a \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/arm64-v8a
```

jar 用 `./gradlew build` 构建（`javac -source 17 -target 17`，产物 class 版本 0x3d）。
本项目只调用 4 个 MC 方法（`GlStateManager._bindTexture`、`RenderSystem._setShaderTexture`、
`RenderSystem.texParameter`），在 Forge 1.20.1 的 official 命名下可以直接加载。

**jar 里不带 so**，所以 `./gradlew build` 出来的产物就是完整可用的 —— 不需要额外开关。
