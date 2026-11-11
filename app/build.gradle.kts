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
     * - 正式号只在推送 GitHub 打 tag 时递增（CI 传 -PversionName=vX.Y.Z，去掉 v）；
     * - 本地包不占正式号：一律 `<下一个正式版本>-dev.<序号>`，序号每次本地 assemble 自增；
     *   序号存在根目录 .dev-version（已 gitignore），IDE 同步/单测不触发自增；
     * - versionCode = major*10000 + minor*100 + patch，例如 0.2.6 -> 206，0.3.0 -> 300。
     *   dev 包与对应正式版共用同一个 versionCode，-r 覆盖安装不会触发降级。
     * release.yml 会用 aapt 断言包内版本与 tag 一致，versionCode 与上式一致。
     */
    fun versionCodeOf(name: String): Int {
        val parts = name.substringBefore('-').split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
    }

    // 最后一个正式 tag 是 v0.3.8；下一个正式版是 0.3.9，发新 tag 后这里改成再下一个。
    val nextReleaseName = "0.3.9"

    val releaseVersionName = (project.findProperty("versionName") as String?)
        ?.removePrefix("v")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    val devSeqFile = rootProject.file(".dev-version")
    val localVersionName = run {
        val forced = (project.findProperty("devSeq") as String?)?.toIntOrNull()
        val last = devSeqFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        // 只有真正本地打包才自增：IDE 同步、跑单测不消耗序号
        val assembling = gradle.startParameter.taskNames.any { it.contains("assemble", ignoreCase = true) }
        val seq = forced ?: if (assembling) last + 1 else last.coerceAtLeast(1)
        if (assembling && forced == null) devSeqFile.writeText(seq.toString())
        "$nextReleaseName-dev.$seq"
    }

    val tagVersionName = releaseVersionName ?: localVersionName
    val tagVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
        ?: versionCodeOf(tagVersionName)

    sourceSets {
        // 演示包专用资源（合成人像）只在 -Pdemo=true 时参与打包；
        // 正式构建的 res.srcDirs 不含 src/demo/res，APK 里根本没有这张图。
        if (demoBuild) getByName("main").res.srcDir("src/demo/res")
    }

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
            // R8 + 资源压缩：material-icons-extended 里未引用的图标类会被整类裁掉
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    lint {
        // CI 只跑 lintDebug，用来拦新引入的 error；存量问题固定在基线里，不靠关闭检查变绿。
        // 基线内已知项（3 个 error）：
        // 1) ExtIdleService.notify(MissingPermission)：Android 13+ 无通知权限时只是被系统压制，不会抛异常；
        // 2/3) RecipientSuggest 的 android.icu.text.Transliterator（NewApi，API 29+）：调用处已有 runCatching，
        //    低版本取不到图标/拼音首字母时返回空串，不影响补全结果。
        baseline = file("lint-baseline.xml")
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
    // Passkey 免密身份验证：Android 官方凭据管理器（Credential Manager）
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    // 扫码登录：CameraX 只做预览/取帧，识别用 ZXing core（纯 Java，不接 GMS/ML Kit）
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // FCM：版本交给 BOM 管理
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // 只跑不依赖 Android 运行时的纯函数（服务器地址解析、HTML 清洗）
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 只是桩，单测运行时换成真实实现（只进测试 classpath，不进 APK）
    testImplementation("org.json:json:20240303")
}
