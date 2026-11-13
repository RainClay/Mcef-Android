plugins {
    `groovy`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // CefriumPlugin / GenerateCefriumRTask 引用 AGP 的 Variant API 类型。
    implementation("com.android.tools.build:gradle:9.2.0")
}
