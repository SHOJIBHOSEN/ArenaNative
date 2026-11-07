plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
android {
    namespace = "com.arenanative"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.arenanative"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
