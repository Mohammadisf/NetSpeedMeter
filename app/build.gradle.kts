plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

/** Optional production signing config, read from local.properties:
 *  ks.path / ks.storePass / ks.keyAlias / ks.keyPass. When absent the
 *  release APK falls back to the debug keystore (local install/testing). */
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.netspeed.meter"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netspeed.meter"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "2.4"
    }

    signingConfigs {
        val ksPath = keystoreProps.getProperty("ks.path")
        if (ksPath != null) {
            create("prod") {
                storeFile = file(ksPath)
                storePassword = keystoreProps.getProperty("ks.storePass")
                keyAlias = keystoreProps.getProperty("ks.keyAlias")
                keyPassword = keystoreProps.getProperty("ks.keyPass")
            }
        }
    }

    buildTypes {
        release {
            // R8 full mode: obfuscation + optimization + shrinking.
            isMinifyEnabled = true
            // Remove unused resources so extracted assets cannot be studied.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No debug metadata in release APKs.
            isDebuggable = false
            isJniDebuggable = false
            // Production keystore when configured (ks.* in local.properties),
            // otherwise the debug keystore keeps the APK installable for
            // local testing. Replace before Play distribution.
            signingConfig =
                signingConfigs.findByName("prod") ?: signingConfigs.getByName("debug")
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
        viewBinding = true
        // No BuildConfig fields with version/flag info baked into the APK,
        // and no debug let-outs.
        buildConfig = false
    }

    packaging {
        // Strip leftover metadata not needed at runtime (tiny attack/
        // fingerprinting surface), keep APK lean.
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/*.version"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

// Offline builds: pin transitive deps of Material to the versions already
// in the local Gradle cache (caches/modules-2), so no network access is needed.
configurations.all {
    resolutionStrategy {
        force("androidx.constraintlayout:constraintlayout:2.1.4")
        force("androidx.recyclerview:recyclerview:1.3.2")
    }
}
