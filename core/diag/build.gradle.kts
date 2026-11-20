plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.blamspot.jcode.core.diag"

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
}
