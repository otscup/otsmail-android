// 版本号集中在 gradle/libs.versions.toml，等工具链装好后由 gradle wrapper 首次同步验证
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
