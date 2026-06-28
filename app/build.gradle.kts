plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.jcode"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.jcode"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    ndkVersion = "27.2.12479018"
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material.icons.extended)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)

    // Core modules
    implementation(project(":core:design"))
    implementation(project(":core:adaptive"))
    implementation(project(":core:resource"))
    implementation(project(":core:fs"))
    implementation(project(":core:buffer"))
    implementation(project(":core:editor"))
    implementation(project(":core:editor-decor"))
    implementation(project(":core:editor-completion"))
    implementation(project(":core:treesitter"))
    implementation(project(":core:term"))
    implementation(project(":core:distro"))
    implementation(project(":core:lsp"))
    implementation(project(":core:ctags"))
    implementation(project(":core:debug"))
    implementation(project(":core:vcs"))
    implementation(project(":core:search"))
    implementation(project(":core:config"))
    implementation(project(":core:ext"))
    implementation(project(":core:state"))

    // Feature modules
    implementation(project(":feature:explorer"))
    implementation(project(":feature:editor-pane"))
    implementation(project(":feature:terminal-pane"))
    implementation(project(":feature:problems"))
    implementation(project(":feature:scm"))
    implementation(project(":feature:search"))
    implementation(project(":feature:debug"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:sdk-manager"))
    implementation(project(":feature:lsp-manager"))
    implementation(project(":feature:marketplace"))
    implementation(project(":feature:onboarding"))

    // Native modules
    implementation(project(":native:core"))
    implementation(project(":native:buffer"))
    implementation(project(":native:editor-render"))
    implementation(project(":native:tree-sitter"))
    implementation(project(":native:libgit2"))
    implementation(project(":native:ripgrep-ffi"))
    implementation(project(":native:pty"))
    implementation(project(":native:vt"))
    implementation(project(":native:wasmtime-ffi"))
    implementation(project(":native:grammars"))
    implementation(project(":native:proot"))

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.ui.test.manifest)

    // Test
    testImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
