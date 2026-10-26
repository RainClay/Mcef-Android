# MCEF for Android — MC 26.1.2

把 **Cefrium**（Chromium Embedded Framework 的原生 Android 移植）包装成
**JCEF 的 Java API**，让依赖 `org.cef.*` / `com.cinemamod.mcef.*` 的 mod 能跑在
Android 上，不改一行消费方代码。

**目标平台：MC 26.1.2**

---

## ⚠️ 这是早期探索版，不能直接用于 MC

本分支的实现**直连 Cefrium**，因此带 `android.*` 依赖：

```java
import com.cefrium.Cefrium;
import com.cefrium.CefriumBrowser;
import android.content.Context;
import android.util.Log;
```

**而 Minecraft 跑在纯 OpenJDK JVM 里（不是 ART），加载不到 `android.*` 类。**
在 MC 里引用这些代码就是 `NoClassDefFoundError: android/content/Context`。

**要能实际使用的版本，请切到 [`1.20.1`](../tree/1.20.1) 分支** ——
那版走 JNI + IPC，MC 侧零 `android.*` 依赖，已实机验证。

---

## 本分支的价值

当作**协议与 API 形状的参考实现**：

- `org.cef.*` 的接口定义（后续两个分支都基于它）
- `Cefrium` 的完整 API 用法（渲染回调、输入事件、查询桥）
- 各种签名对齐的踩坑记录

---

## 为什么 MC 侧不能直连 Cefrium

Cefrium 的 API 签名里就有 Android 类型：

```java
public static synchronized boolean Cefrium.initialize(android.content.Context);
public android.os.Bundle CefriumBrowser.saveState();
public void CefriumBrowser.sendKeyEvent(android.view.KeyEvent);
public static CefriumBrowser CefriumBrowser.createWithSurface(android.app.Activity);
```

标准 JVM 上没有这些类。验证方法：官方 `mcef-3.3.1-26.1.jar` 里 `android/` 引用数是 **0**。

**正确的架构**（见 `1.20.1` 分支）：

```
MC JVM（纯 OpenJDK，零 android 依赖）
  └── com.cinemamod.mcef.* → org.cef.* → McefNative(JNI)
        └── libmcef.so → IPC 127.0.0.1:39090
              └── 启动器 .cefhost 进程（ART + Cefrium + libcef）
```

---

## 本分支实现的内容

### 1. `org.cef.*` 兼容层（16 个类型）

`CefApp` / `CefClient` / `CefSettings` / `CefBrowser` / `CefBrowserOsr` / `CefFrame` /
`CefCallback` / `CefQueryCallback` / `CefDisplayHandler` / `CefLoadHandler` /
`CefQueryCallbackHandler` / `CefMessageRouterHandlerAdapter` / `CefResourceHandler` /
`CefCursorType` / `CefRect` / `IntRef` / `StringRef` / `CefRequest` / `CefResponse`

**不照抄 JCEF 全集**，只保留消费方实际调用的成员。依据是字节码引用扫描：

```bash
find wd_extract -name "*.class" | xargs -I{} javap -p -c {} \
  | grep -oE "// (Method|InterfaceMethod|Field) (org/cef|com/cinemamod/mcef)[^ ]*"
```

### 2. `CefRect` —— Android 没有 `java.awt.Rectangle`

`android.jar` 里只有 `java.awt.font`，**没有 Rectangle**。所以自带一个字段同名
（`x` / `y` / `width` / `height`）的最小实现。

### 3. `MCEFRenderer` 用 LWJGL 裸 GL，不依赖 blaze3d

这样对 MC 版本完全不敏感 —— 升级 MC 不用改这里。

---

## 已知问题

| 问题 | 说明 |
|---|---|
| **无法在 MC 里运行** | 直连 Cefrium，需要 ART 环境 |
| **接口不全** | 相比 `1.20.1` 分支缺 `MCEFApp` / `MCEFInitListener` / `CefMessageRouter` / `CefSchemeHandlerFactory` 等 8 个类 |
| **帧上传时机** | 用 `RenderTickEvent.START`，会在 MobileGlues 上段错误（`1.20.1` 分支已改为 `getTextureID()` 驱动） |

---

## 构建

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew jar
```

依赖（`libs/` 下，`compileOnly`，不进产物）：

| 文件 | 用途 |
|---|---|
| `cefrium-sdk-classes.jar` | Cefrium SDK（v69 字节码） |
| `minecraft-*.jar` | MC 本体（blaze3d） |
| `lwjgl*.jar` | GL 调用 |
| `android.jar` | `Context` / `Log` |

需要 JDK 25 编译（MC 26.1.2 的 class 是 version 69）。

---

## 版权

- 本仓库代码：见 [LICENSE](LICENSE)（GPL-3.0）
- JCEF 的 API 形状来自 Chromium/CEF（BSD）
- 上游 [CinemaMod MCEF](https://github.com/CinemaMod/mcef) 为 LGPL-2.1
