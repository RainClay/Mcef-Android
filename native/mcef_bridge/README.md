# libmcef native bridge

`libmcef.so` 的 C++ / JNI 源码。本分支只发布桥源码，不包含预编译 `.so`，也不包含启动器工程。

## 位置与职责

```text
Minecraft JVM（纯 OpenJDK）
  └── McefNative.java
        └── JNI: libmcef.so
              └── TCP IPC: 127.0.0.1:39090
                    └── Zalith .cefhost（Android/ART + Cefrium + libcef）
```

MC 侧不能直接调用 Cefrium：Cefrium 的 Java API 引用了 `android.*`，而 Minecraft 运行在独立 OpenJDK JVM 中。桥提供稳定的 C ABI、浏览器句柄、帧/DOM 缓存、输入和控制消息；Android 宿主负责实际创建 Cefrium 浏览器并回填帧。

## 源码布局

- `include/mcef_bridge.h`：导出的 C ABI、错误码和数据结构。
- `include/mcef_ipc.h`：IPC 客户端接口。
- `src/mcef_bridge.cpp`：句柄管理、帧/DOM 缓存及 C ABI 实现。
- `src/mcef_ipc_client.cpp`：本地 TCP IPC、重连、控制消息与帧接收。
- `src/mcef_bridge_jni.cpp`：Java 到 C ABI 的 JNI 适配。
- `java/net/ccbluex/liquidbounce/mcef/nativebridge/McefNative.java`：LiquidBounce 使用的 JNI 声明参考。

## JNI 动态注册

JNI 在 `JNI_OnLoad` 中通过 `RegisterNatives` 注册，不依赖导出符号名称中的 Java 包名。当前注册目标：

- `net/ccbluex/liquidbounce/mcef/nativebridge/McefNative`
- `com/cinemamod/mcef/nativebridge/McefNative`

接入新 Java 包名时，在 `src/mcef_bridge_jni.cpp` 的 `kTargetClasses` 中添加类路径，并确保 Java native 方法名、签名与 `kMethods` 一致。

## 构建

需要 Android NDK、CMake 3.22+ 和 arm64-v8a 工具链：

```bash
cmake -S . -B build/arm64-v8a \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release

cmake --build build/arm64-v8a --config Release
```

产物通常位于 `build/arm64-v8a/libmcef.so`。由启动器 Android APK 将 so 放入 `jniLibs/arm64-v8a/`；Minecraft mod jar 不应捆绑该 so。

## 校验建议

构建后检查 JNI 动态注册和两个目标类名：

```bash
strings build/arm64-v8a/libmcef.so | grep -E 'JNI_OnLoad|McefNative'
```

排查帧接收时检查 `frame hdr` 的 `consistent` 与 `host_set_frame` 返回码；`MCEF_OK` 表示帧匹配到本地浏览器句柄并写入缓存。

## IPC 协议

协议实现位于 `src/mcef_ipc_client.cpp` 和启动器侧 `CefriumHostService`。本分支不复制旧的 `docs/ipc_protocol.md`，以源码中的当前发送与解析逻辑为准。帧头解析会校验 `size == stride * height`，并调用 `mcef_host_set_frame` 更新缓存和版本号。
