plugins {
    alias(libs.plugins.android.library)
}

extra["cargoLibraryName"] = "ripgrep_ffi"
extra["cargoManifestPath"] = "rust/Cargo.toml"
extra["cargoPackageName"] = "native:ripgrep-ffi"
apply(from = rootProject.file("gradle/cargo.gradle.kts"))

android {
    namespace = "dev.blamspot.jcode.nativeffi.ripgrep.ffi"

    sourceSets.getByName("debug").jniLibs.directories += layout.buildDirectory.dir("generated/cargoJniLibs/debug").get().asFile.path
    sourceSets.getByName("release").jniLibs.directories += layout.buildDirectory.dir("generated/cargoJniLibs/release").get().asFile.path
}
