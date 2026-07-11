plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.jcode.feature.marketplace"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
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
    // Ed25519 signature verification for official (signed) .jext packages — used via BC's lightweight
    // crypto API (Ed25519Signer), no JCA provider registration needed.
    implementation(libs.bouncycastle.provider)
    implementation(project(":core:distro"))
}
