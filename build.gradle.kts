// AGP 由 buildSrc 的 dependencies 带到 classpath，这里不声明版本。
// 注意：AGP 9.2 内建 Kotlin 2.2，而 miuix 0.9.4 用 Kotlin 2.4 编译 →
// 元数据版本不兼容。buildSrc 带 AGP 9.2 时无法升级内建 Kotlin，
// 因此用 XskipMetadataVersionCheck 放行（miuix 只是库，元数据仅影响签名解析）。
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
