plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.jcode.nativeffi.proot"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    // Note: proot is a standalone executable, not a JNI library, but it and its ELF
    // loaders are shipped as prebuilt jniLibs (src/main/jniLibs/arm64-v8a/libproot*.so)
    // so they land in nativeLibraryDir — the only app-owned location execve() is
    // allowed from at targetSdk 29+ (W^X). The mmap-only support libs (libtalloc,
    // libandroid-shmem) stay in src/main/assets/bin, extracted at runtime by
    // :core:distro's ProotManager.
}
