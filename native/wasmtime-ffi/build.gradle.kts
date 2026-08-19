plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

extra["cargoLibraryName"] = "jcode_wasm"
extra["cargoManifestPath"] = "rust/Cargo.toml"
extra["cargoPackageName"] = "native:wasmtime-ffi"
apply(from = rootProject.file("gradle/cargo.gradle.kts"))

android {
    namespace = "dev.jcode.nativeffi.wasmtime.ffi"

    sourceSets.getByName("debug").jniLibs.srcDir(layout.buildDirectory.dir("generated/cargoJniLibs/debug"))
    sourceSets.getByName("release").jniLibs.srcDir(layout.buildDirectory.dir("generated/cargoJniLibs/release"))
}
