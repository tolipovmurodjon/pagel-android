plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin): no org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "org.pagel.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.pagel.ai"
        minSdk = 30
        targetSdk = 36
        // CI passes -PpagelVersionCode=<run number>; a local build stays at 1. The updater only moves forward.
        versionCode = (project.findProperty("pagelVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("pagelVersionName") as String?) ?: "0.1-A1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // Ship only the ABIs we run on (real Samsung = arm64, emulator = x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release signing comes only from the environment CI sets; the key never sits in the repo.
    signingConfigs {
        val ks = System.getenv("PAGEL_KEYSTORE_FILE")
        if (ks != null) {
            create("pagel") {
                storeFile = file(ks)
                storeType = "pkcs12"
                storePassword = System.getenv("PAGEL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PAGEL_KEY_ALIAS")
                keyPassword = System.getenv("PAGEL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("pagel")?.let { signingConfig = it }
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
        // Robolectric (Compose UI, Room and screenshot tests on the JVM) needs the merged resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// Room writes its schema here so every migration can be tested against the real old shape.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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

    // Navigation 3 + ViewModels per entry; hiltViewModel() without Navigation 2
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Real liquid glass on the chrome (bars, sheets, buttons)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.compose.ui.util)
    // JankStats: the black-hole lab shows its janky % on the phone.
    implementation(libs.androidx.metrics.performance)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Persistence: Room is the phone's copy of pagel.uz; DataStore only for small settings
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // AnchorWatchdogWorker (service/) — plain CoroutineWorker, so no androidx.hilt:hilt-work and no
    // Configuration.Provider; the default WorkManager initializer is in play.
    implementation(libs.androidx.work.runtime.ktx)
    // "Today's 3" home-screen widget (skills glance-canonical-layouts + glance-appwidget-updates-and-memory).
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

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

    // Namoz times on the phone (round D): batoulapps Adhan, MIT, ★413, pure Java, no dependencies.
    implementation(libs.adhan)

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
    // Android on the JVM: Room in-memory tests, Compose UI tests, Roborazzi screenshots
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    // Instrumented test
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
