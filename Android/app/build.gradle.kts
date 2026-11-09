plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jinjingle.and"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jinjingle.and"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        val androidKey = providers.gradleProperty("amapAndroidKey").orElse("").get()
        manifestPlaceholders["amapAndroidKey"] = androidKey
        buildConfigField("String", "API_BASE_URL", "\"https://jinjingle.yixiangshe.com\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.amap.api:3dmap:10.0.600")
    implementation("com.amap.api:search:9.7.0")
}
