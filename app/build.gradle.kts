import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.pagel.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.pagel.ai"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-A1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // Ship only the ABIs we run on (real Samsung = arm64, emulator = x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // material-icons-*core* only (arrives with material3). The *extended* set was ~66 MB of dex in
    // the debug APK — thousands of vector icons for the single Icons.Rounded.ArrowBack we use.
    // Re-add it only if a screen genuinely needs an icon outside core's set.
    implementation(libs.androidx.compose.ui.text.google.fonts)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Persistence
    implementation(libs.androidx.datastore.preferences)

    // AnchorWatchdogWorker (service/) — plain CoroutineWorker, so no androidx.hilt:hilt-work and no
    // Configuration.Provider; the default WorkManager initializer is in play.
    implementation(libs.androidx.work.runtime.ktx)

    // Async / serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.jakewharton.timber)

    // Gateway device identity — Ed25519 keypair (BouncyCastle lightweight API, since minSdk 30
    // predates platform Ed25519 at API 33) persisted encrypted at rest.
    implementation(libs.bouncycastle)
    implementation(libs.androidx.security.crypto)

    // Gateway WebSocket transport (net/ws).
    implementation(libs.okhttp)

    // Charts/shimmer deferred to the dashboard (Task 7): Vico 3.x needs Kotlin 2.3 and breaks
    // this Kotlin-2.0.21 toolchain. Re-add then via Vico 2.x (Kotlin-2.0 safe) or a deliberate
    // Kotlin bump. Until then, all visuals are hand-rolled Canvas (no chart dependency needed).

    // Unit test
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.cash.turbine)
    // Local loopback WebSocket server — lets GatewayConnectionSocketTest drive the real handshake
    // over a real socket, without opening one to anything but localhost.
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented test
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
