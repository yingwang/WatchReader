import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val verMajor: Int by rootProject.extra
val verMinor: Int by rootProject.extra
val verPatch: Int by rootProject.extra
val verBase = verMajor * 10000 + verMinor * 100 + verPatch

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "com.watchreader.wear"
    compileSdk = 35

    signingConfigs {
        create("release") {
            // Signing credentials live in local.properties (never committed):
            //   RELEASE_STORE_FILE=watchreader-release.keystore
            //   RELEASE_STORE_PASSWORD=...
            //   RELEASE_KEY_ALIAS=watchreader
            //   RELEASE_KEY_PASSWORD=...
            storeFile = rootProject.file(localProps.getProperty("RELEASE_STORE_FILE", "watchreader-release.keystore"))
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS", "watchreader")
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    defaultConfig {
        applicationId = "com.watchreader"
        minSdk = 30
        targetSdk = 35
        // Watch bundles end in 2, phone bundles in 1, so the two never collide on Play.
        versionCode = verBase * 10 + 2
        versionName = "$verMajor.$verMinor.$verPatch"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// A missing keystore surfaces as an opaque error deep inside the signing step;
// fail up front with a message that says what to put where.
tasks.matching { it.name.startsWith("package") && it.name.contains("Release") }.configureEach {
    doFirst {
        val store = rootProject.file(localProps.getProperty("RELEASE_STORE_FILE", "watchreader-release.keystore"))
        require(store.isFile && localProps.getProperty("RELEASE_STORE_PASSWORD", "").isNotEmpty()) {
            "Release signing is not configured: put the keystore at ${store.path} and " +
                "RELEASE_STORE_PASSWORD / RELEASE_KEY_PASSWORD in local.properties"
        }
    }
}

dependencies {
    implementation(project(":shared"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    implementation("androidx.wear:wear:1.3.0")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
