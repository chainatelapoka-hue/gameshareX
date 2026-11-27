plugins {
  id("com.android.application")
}

android {
  namespace = "com.gameshare.x"
  compileSdk = 36
  // Native tunnel (hev-socks5-tunnel) is built with ndk-build; CI installs this exact NDK.
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.gameshare.x"
    // Android 7.0: the lowest version the target phones (Redmi Note 4) run.
    minSdk = 24
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"
    ndk {
      abiFilters += listOf("armeabi-v7a", "arm64-v8a")
    }
  }

  externalNativeBuild {
    ndkBuild {
      path = file("src/main/jni/Android.mk")
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/upload.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  // QR scanner (Apache-2.0)
  implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
