plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.jcode.feature.marketplace"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    sourceSets {
        getByName("main").assets.srcDir("../../marketplace")
    }

    androidResources {
        ignoreAssetsPattern = "!README.md"
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
    implementation(libs.snakeyaml.engine)
    implementation(project(":core:distro"))
}
