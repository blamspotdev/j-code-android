plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.blamspot.jcode.core.buffer"

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    // The differential fuzz needs internal access to Buffer(useNative=) plus libjcodebuffer.so
    // in the test APK — the app gets the .so via :native:buffer, so the androidTest must too.
    androidTestImplementation(project(":native:buffer"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
