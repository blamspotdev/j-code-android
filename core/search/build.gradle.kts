plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.jcode.core.search"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
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
