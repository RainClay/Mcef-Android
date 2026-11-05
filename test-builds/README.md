# 测试包（可直接下载使用）

这里放已编译好的构建产物，方便不装 Gradle/NDK 的环境直接取用。
源码与产物一一对应，版本号见目录名。

## 2.2.0（2026-11-05）

| 文件 | 说明 |
|---|---|
| [`mcef-forge-2.2.0-1.20.1-android.jar`](2.2.0/mcef-forge-2.2.0-1.20.1-android.jar) | Forge 1.20.1 mod，**已内含** `natives/arm64-v8a/libmcef.so`，丢进 `mods/` 即用 |
| [`libmcef-arm64-v8a.so`](2.2.0/libmcef-arm64-v8a.so) | 单独一份 so，给需要把它打进启动器 APK 的场景 |

### 这一版改了什么

- **递归修复**：`getTextureID() → beforeUse → tick() → getTextureID()` 自递归（实测
  6.2 万层栈）已封死。`tick()` 内部改用 `peekTextureID()`，另有 `AtomicBoolean` 防重入。
- **每帧一次**：`RenderTickEvent.START` 只递增纯 Java 帧号；`getFrameInfo`/`copyFrame`/GL
  上传每个浏览器每 MC 帧最多一次（原先实测 62258 次/帧）。
- **宽高比**：去掉 512×512 分别 clamp。默认不限；`-Dmcef.maxDim=512` 时 1280×720 → 512×288。
- **so 加载**：先 `System.loadLibrary("mcef")`（走 APK 的 lib 目录）；失败则从 jar 的
  `natives/arm64-v8a/` 解压到 `java.io.tmpdir` 再 `System.load`。

### 安装

```bash
adb push mcef-forge-2.2.0-1.20.1-android.jar \
  "/storage/emulated/0/Minecraft/versions/<版本>/mods/"
```

不需要单独放 so —— jar 里已经带了。若启动器已把 so 打进 APK，会优先用 APK 里那份。

### 校验

```
jar: 87 KB, 52 entries
so : 122.4 KB (125384 bytes), aarch64
     JNI_OnLoad 已导出；kTargetClasses 含
       net/ccbluex/liquidbounce/mcef/nativebridge/McefNative
       com/cinemamod/mcef/nativebridge/McefNative
class: Java 17 (0x3d)
```

### 已知未解决

- 视频有声音无画面（media router 打桩，未验证）
- `resize` 是空操作（Cefrium 无 resize API）
- 键盘输入未实现
- 标题回调、光标类型回调、native→页面方向的 `sendQuery` 仍是空实现

### 构建方式

源码在 [`1.20.1`](../tree/1.20.1) 分支；so 源码在 [`libmcef-source`](../tree/libmcef-source) 分支。
本目录的 jar 是用 `javac -source 17 -target 17` 加 `aarch64-linux-android-clang++` 直接产出的
（本机 Gradle 因 `libnative-platform.so` 加载失败跑不起来），所以没有走 `reobfJar`。
本项目只调用 4 个 MC 方法（`GlStateManager._bindTexture`、`RenderSystem._setShaderTexture`、
`RenderSystem.texParameter`），在 Forge 1.20.1 的 official 命名下可以直接加载；
如果你在有 Gradle 的机器上跑 `./gradlew build`，产物会额外经过 reobf，两者都能用。
