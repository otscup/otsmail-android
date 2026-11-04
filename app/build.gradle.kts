import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 没有 google-services.json 就不启用推送配置：APK 照常构建，只是收不到推送
if (file("google-services.json").exists()) {
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

    defaultConfig {
        applicationId = "com.otscup.otsmail"
        minSdk = 26          // targetSdk 35；26 起步可以直接用 adaptive icon 与深色主题
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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
