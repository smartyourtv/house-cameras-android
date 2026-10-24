plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartyourtv.housecameras"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "com.smartyourtv.housecameras"
        minSdk = 26
        targetSdk = 34
        versionCode = 102
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val kf = System.getenv("KEYSTORE_FILE_PATH"); val kp = System.getenv("KEYSTORE_PASSWORD")
    val ka = System.getenv("KEYSTORE_KEY_ALIAS"); val kpw = System.getenv("KEYSTORE_KEY_PASSWORD")
    val hasSigning = !kf.isNullOrEmpty() && !kp.isNullOrEmpty() && !ka.isNullOrEmpty() && !kpw.isNullOrEmpty()
    signingConfigs {
        if (hasSigning) create("release") {
            storeFile = file(kf); storePassword = kp; keyAlias = ka; keyPassword = kpw
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    testImplementation("junit:junit:4.13.2")
}
