plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.blamspot.jcode.ext.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

// Everything here is a compatibility commitment: an extension compiled against it runs inside
// JCode's own process, so anything added is something a future release cannot casually change.
// Keep the surface small, and take the `compileOnly` note in JCodeNativeExtension seriously.
dependencies {
    implementation(platform(libs.compose.bom))
    api(libs.compose.runtime)

    // The adb service seam the virtual-device contract hands back (AdbServiceHandler / AdbStream).
    // compileOnly because JCode supplies it at runtime exactly as it supplies Compose: an extension
    // that bundled a second copy would implement an interface the daemon does not accept.
    compileOnly(project(":core:distro"))
}
