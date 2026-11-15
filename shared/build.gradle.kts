plugins {
    id("com.android.library")
}
android {
    namespace = "com.hongguotv.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
