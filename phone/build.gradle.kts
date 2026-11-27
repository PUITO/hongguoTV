import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hongguotv.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hongguotv.phone"
        minSdk = 24
        targetSdk = 35
        versionCode = 960
        versionName = "0.9.9"
    }
    signingConfigs {
        create("release") {
            val store = keystoreProps.getProperty("storeFile")
            if (store != null) {
                storeFile = rootProject.file("keystore/$store")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
