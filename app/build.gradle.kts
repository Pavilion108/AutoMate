import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.automate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.automate"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A checked-in debug key keeps every CI build upgrade-compatible. GitHub runners
        // generate a fresh debug.keystore per job, which made every build fail
        // `adb install -r` with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        create("stableDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Release signing is external to the repo. The keystore lives only on this
        // machine (back it up!). Missing it fails the release build loudly rather than
        // silently shipping an unsigned or debug APK.
        create("release") {
            // Release signing. Order: explicit env vars (CI), then the dev machine's
            // keystore.properties. Missing credentials fail the release build loudly
            // rather than silently shipping an unsigned or debug APK.
            val envStoreFile = System.getenv("AUTOMATE_STORE_FILE")
            val envStorePassword = System.getenv("AUTOMATE_STORE_PASSWORD")
            val envKeyAlias = System.getenv("AUTOMATE_KEY_ALIAS")
            val envKeyPassword = System.getenv("AUTOMATE_KEY_PASSWORD")
            if (envStoreFile != null && envStorePassword != null &&
                envKeyAlias != null && envKeyPassword != null
            ) {
                storeFile = file(envStoreFile)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            } else {
                val keyFile = file("/home/polzovatel/.automate-release/keystore.properties")
                val props = Properties()
                if (keyFile.exists()) {
                    keyFile.inputStream().use { props.load(it) }
                }
                storeFile = file(props.getProperty("storeFile", "DOES_NOT_EXIST"))
                storePassword = props.getProperty("storePassword", "DOES_NOT_EXIST")
                keyAlias = props.getProperty("keyAlias", "DOES_NOT_EXIST")
                keyPassword = props.getProperty("keyPassword", "DOES_NOT_EXIST")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-android-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security (encrypted storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Location / Geofencing
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // WorkManager (for accessibility watchdog)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
