plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.phosphor.launcher"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.phosphor.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
