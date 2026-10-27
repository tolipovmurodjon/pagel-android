// Top-level build file. Plugin versions resolved from gradle/libs.versions.toml.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 compiles Kotlin itself and brings KGP 2.2.10; this lifts it to the catalog's Kotlin (AGP 9 release
        // notes, "Upgrade to a higher KGP version"). Keep the version equal to `kotlin` in libs.versions.toml.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.roborazzi) apply false
}
