package dev.jcode.native.proot

/**
 * Marker class for the proot native module.
 *
 * proot and its ELF loaders ship as prebuilt jniLibs (libproot.so, libproot-loader.so,
 * libproot-loader32.so under src/main/jniLibs/arm64-v8a) so they install into the app's
 * native library dir — the only app-owned location execve() is allowed from at
 * targetSdk 29+ (W^X). The mmap-only support libraries (libtalloc, libandroid-shmem)
 * remain assets, extracted at runtime by :core:distro's ProotManager.
 */
object ProotNativeModule
