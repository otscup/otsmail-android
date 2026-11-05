import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 演示构建开关：`./gradlew assembleDebug -Pdemo=true`。
 * 只用于拍 README 截图——全部邮件/账户/头像都是本地虚构数据，不连真实服务器；
 * 演示包 applicationId 带 .demo 后缀，可与正式包并存。默认 false，正式包不受影响。
 */
val demoBuild = (project.findProperty("demo") as String?)?.toBoolean() == true

// 没有 google-services.json 就不启用推送配置：APK 照常构建，只是收不到推送
// 演示包 applicationId 带后缀，google-services.json 里没有对应包名，直接跳过推送插件
if (!demoBuild && file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.otscup.otsmail"
    compileSdk = 35

    // 发布签名：本地优先读仓库根 keystore.properties（已 gitignore，jks 放仓库外）；
    // CI 没有这个文件，回退读环境变量（KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）
    // 或同名的 -P 属性。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }

    fun signOpt(key: String, env: String): String? =
        keystoreProps.getProperty(key)
            ?: System.getenv(env)
            ?: (project.findProperty(key) as String?)

    val signStoreFile = signOpt("storeFile", "KEYSTORE_FILE")
    val signStorePwd = signOpt("storePassword", "KEYSTORE_PASSWORD")
    val signKeyAlias = signOpt("keyAlias", "KEY_ALIAS")
    val signKeyPwd = signOpt("keyPassword", "KEY_PASSWORD")
    val signingReady =
        signStoreFile != null && signStorePwd != null && signKeyAlias != null && signKeyPwd != null

    /**
     * 版本号规则（确定性、可校验）：
     * - versionName 由 tag 派生（CI 传 -PversionName=vX.Y.Z 去掉 v），本地默认 0.2.3；
     * - versionCode = major*10000 + minor*100 + patch，例如 0.2.3 -> 203，0.3.0 -> 300。
     *   只升不降：major/minor/patch 任一位递增后都比旧值大。
     * release.yml 会用 aapt 断言包内版本与 tag 一致，versionCode 与上式一致。
     */
    fun versionCodeOf(name: String): Int {
        val parts = name.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
    }

    val tagVersionName = (project.findProperty("versionName") as String?)
        ?.removePrefix("v")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "0.2.3"
    val tagVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
        ?: versionCodeOf(tagVersionName)

    defaultConfig {
        applicationId = "com.otscup.otsmail"
        minSdk = 26          // targetSdk 35；26 起步可以直接用 adaptive icon 与深色主题
        targetSdk = 35
        versionCode = tagVersionCode
        versionName = tagVersionName
        buildConfigField("boolean", "DEMO", demoBuild.toString())
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = file(signStoreFile!!)
                storePassword = signStorePwd
                keyAlias = signKeyAlias
                keyPassword = signKeyPwd
            }
        }
    }

    buildTypes {
        debug {
            // 演示包单独 applicationId，安装时不会顶掉用户正在用的正式包
            if (demoBuild) applicationIdSuffix = ".demo"
        }
        release {
            isMinifyEnabled = false
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Custom Tabs：OIDC 授权页放到 Chrome，WebAuthn（通行密钥）才可能被调起
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // FCM：版本交给 BOM 管理
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
