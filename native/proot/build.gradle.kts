plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.jcode.nativeffi.proot"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    // Note: proot is a standalone executable bundled as an asset,
    // not a shared library built via CMake/JNI. The binary is
    // pre-compiled for arm64-v8a and x86_64 and placed in
    // src/main/assets/bin/proot. Extraction is handled at runtime
    // by :core:distro's ProotManager.
}
