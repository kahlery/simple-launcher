import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Enable Banking (banka bakiyeleri) anahtarları: repoya girmeyen secrets.properties'ten okunur.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String, default: String = "") = secrets.getProperty(key, default).trim()
val enableBankingKey = secret("ENABLE_BANKING_KEY_FILE")
    .takeIf { it.isNotEmpty() }
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
    ?.readText()
    .orEmpty()

android {
    namespace = "com.example.simplelauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.simplelauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "EB_APP_ID", "\"${secret("ENABLE_BANKING_APP_ID")}\"")
        buildConfigField("String", "EB_PRIVATE_KEY", "\"${enableBankingKey.replace("\r", "").replace("\n", "\\n")}\"")
        buildConfigField(
            "String",
            "EB_REDIRECT_URL",
            "\"${secret("ENABLE_BANKING_REDIRECT_URL", "https://example.com/simplelauncher/callback")}\"",
        )
        // Ana ekranda gösterilecek bankalar, Enable Banking'deki adlarıyla ve bu sırayla.
        buildConfigField("String", "EB_ASPSPS", "\"${secret("ENABLE_BANKING_ASPSPS", "N26,Revolut,Wise")}\"")
        buildConfigField("String", "EB_ASPSP_COUNTRY", "\"${secret("ENABLE_BANKING_COUNTRY", "DE")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
}
