// Version-less: AGP and Kotlin are already on the build classpath via :app's plugins block, and
// re-stating a version here is rejected ("already on the classpath with an unknown version").
plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jcode.webengine.impl"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        // Mirrors the app's ABI set (build.gradle.kts root config): the split must cover every
        // ABI the base ships or the device may install a base without an engine for its arch.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(project(":app"))
    // The engine. Published on Mozilla's Maven; version tracks current Firefox release.
    implementation("org.mozilla.geckoview:geckoview:153.0.20260810162159") {
        // GeckoView's WebAuthn support rides on play-services-fido, whose manifest demands Play
        // Services resources the app doesn't ship. Gecko treats the dependency as optional at
        // runtime; excluding it costs passkeys in the browser, nothing else.
        exclude(group = "com.google.android.gms")
    }
}
