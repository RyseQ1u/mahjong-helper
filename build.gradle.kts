// 根构建脚本：仅声明插件版本，不在此处配置 Android 模块。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // KSP 版本与 Kotlin 1.9.24 对应（Room 注解处理）。
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
