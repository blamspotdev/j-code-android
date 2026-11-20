plugins {
    alias(libs.plugins.android.library)
}

extra["cargoLibraryName"] = "jcode_wasm"
extra["cargoManifestPath"] = "rust/Cargo.toml"
extra["cargoPackageName"] = "native:wasmtime-ffi"
apply(from = rootProject.file("gradle/cargo.gradle.kts"))

android {
    namespace = "dev.blamspot.jcode.nativeffi.wasmtime.ffi"

    sourceSets.getByName("debug").jniLibs.directories += layout.buildDirectory.dir("generated/cargoJniLibs/debug").get().asFile.path
    sourceSets.getByName("release").jniLibs.directories += layout.buildDirectory.dir("generated/cargoJniLibs/release").get().asFile.path
}
