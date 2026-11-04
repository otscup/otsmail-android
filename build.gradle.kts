// 版本号集中在 gradle/libs.versions.toml，等工具链装好后由 gradle wrapper 首次同步验证
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // 4.4.2 与 AGP 8.7.2 / Gradle 8.11.1 兼容；不升 AGP
    id("com.google.gms.google-services") version "4.4.2" apply false
}
