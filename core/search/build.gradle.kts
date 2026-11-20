plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.blamspot.jcode.core.search"

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    implementation(libs.coroutines.android)
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(":native:ripgrep-ffi:cargoBuildDebugJniLibs")
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(":native:ripgrep-ffi:cargoBuildReleaseJniLibs")
}
