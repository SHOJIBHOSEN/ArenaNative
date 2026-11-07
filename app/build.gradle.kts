plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.arenanative"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.arenanative"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
