plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

extra["cargoLibraryName"] = "ripgrep_ffi"
extra["cargoManifestPath"] = "rust/Cargo.toml"
extra["cargoPackageName"] = "native:ripgrep-ffi"
apply(from = rootProject.file("gradle/cargo.gradle.kts"))

android {
    namespace = "dev.jcode.nativeffi.ripgrep.ffi"

    sourceSets.getByName("debug").jniLibs.srcDir(layout.buildDirectory.dir("generated/cargoJniLibs/debug"))
    sourceSets.getByName("release").jniLibs.srcDir(layout.buildDirectory.dir("generated/cargoJniLibs/release"))
}
