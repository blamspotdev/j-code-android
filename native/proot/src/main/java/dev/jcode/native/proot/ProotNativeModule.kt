package dev.jcode.native.proot

/**
 * Marker class for the proot native module.
 *
 * The proot binary is a standalone executable (not a shared library)
 * that uses ptrace to intercept syscalls. It is pre-compiled for
 * arm64-v8a and x86_64 and bundled as an asset.
 *
 * Extraction to app-private storage (appContext.filesDir/bin/proot)
 * is handled at runtime by :core:distro's ProotManager.
 */
object ProotNativeModule {
    /** Asset path inside the APK where the proot binary is stored. */
    const val PROOT_ASSET_PATH = "bin/proot"

    /** Relative path under appContext.filesDir where proot is extracted. */
    const val PROOT_EXTRACTED_PATH = "bin/proot"
}
