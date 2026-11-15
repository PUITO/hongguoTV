import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hongguotv.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hongguotv.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 730
        versionName = "0.7.3"
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
    implementation("com.google.zxing:core:3.5.3")
}
