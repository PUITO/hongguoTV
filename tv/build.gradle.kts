plugins {
    id("com.android.application")
}
android {
    namespace = "com.hongguotv.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hongguotv.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 200
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
dependencies {
    implementation(project(":shared"))
    implementation("com.google.zxing:core:3.5.3")
}
