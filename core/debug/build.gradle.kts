plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.blamspot.jcode.core.debug"

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
    implementation(project(":core:term"))
    implementation(project(":core:distro"))
}
