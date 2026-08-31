// 根构建脚本：只声明插件，不声明具体依赖版本（版本统一放在 app/build.gradle.kts）
plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
}
