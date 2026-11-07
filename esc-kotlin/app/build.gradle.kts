plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sk.escpos.kotlin"
    compileSdk = 36

    defaultConfig {
        applicationId = "sk.escpos.kotlin"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // USB-serial driver for PL2303 / CH340 / FTDI / CP210x – no root needed
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
}
