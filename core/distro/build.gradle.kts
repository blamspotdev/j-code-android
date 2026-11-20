plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.blamspot.jcode.core.distro"

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
    implementation(libs.datastore.preferences)
    implementation(libs.snakeyaml.engine)
    implementation(libs.xz)
    implementation(libs.commons.compress)
    implementation(project(":core:config"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.kotlin.test.junit)
}
