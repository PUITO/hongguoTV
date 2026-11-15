plugins {
    id("com.android.application")
}
android {
    namespace = "com.hongguotv.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hongguotv.phone"
        minSdk = 24
        targetSdk = 35
        versionCode = 400
        versionName = "0.4.0"
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
