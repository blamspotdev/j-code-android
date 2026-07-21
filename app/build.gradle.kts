plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The app version — actual Android metadata, single source of truth (VERSION.txt is gone).
// The release scripts parse the `val jcodeVersion = "…"` line below, and `-PjcodeVersionName=…`
// still overrides for pre-release tagging (the Beta build passes e.g. 1.3.3-beta).
// versionCode is derived from the semver as MAJOR*10000 + MINOR*100 + PATCH — monotonic,
// deterministic, offline, and independent of git history (a squash-merge collapsed the old
// git-commit-count scheme and produced downgrades). Pre-release suffixes (e.g. -beta) are
// ignored by the derivation. The formula must match scripts/build-release.ps1 ($Code) and
// build-release-common.sh (CODE).
val jcodeVersion = "1.3.9"

val jcodeVersionName: String =
    (project.findProperty("jcodeVersionName") as? String)?.trim()?.takeIf { it.isNotBlank() }
        ?: jcodeVersion

val jcodeVersionCode: Int = runCatching {
    val (major, minor, patch) = Regex("""^(\d+)\.(\d+)\.(\d+)""")
        .find(jcodeVersionName)!!.destructured
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}.getOrNull()?.takeIf { it > 0 } ?: 10000

// A non-empty `-PjcodeIdSuffix` (e.g. ".beta") gives this build its own applicationId AND launcher
// label so it installs ALONGSIDE the normal release app instead of replacing it (the release script
// passes ".beta" for a Beta build). Its private data (Linux rootfs, settings, sessions) is isolated
// under the suffixed package; only the shared /storage/emulated/0/JCode projects folder is common.
// Empty (the default) keeps the normal dev.jcode / "JCode" release identity. namespace is unchanged
// (compile-time R/BuildConfig package), so no source references break.
val jcodeIdSuffix: String =
    (project.findProperty("jcodeIdSuffix") as? String)?.trim().orEmpty()

android {
    namespace = "dev.jcode"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.jcode"
        minSdk = 33
        targetSdk = 33
        versionCode = jcodeVersionCode
        versionName = jcodeVersionName

        // Launcher name (AndroidManifest android:label="${appLabel}"). The Beta build overrides this
        // to "JCode.beta" in the release block below.
        manifestPlaceholders["appLabel"] = "JCode"

        // Launcher icon (AndroidManifest android:icon/roundIcon). Debug and Beta builds swap in a
        // tinted adaptive icon (red gradient / purple gradient background) below so they're
        // distinguishable on the home screen; the plain release build keeps ic_launcher.
        manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        manifestPlaceholders["appIconRound"] = "@mipmap/ic_launcher_round"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Dev builds install as a separate app (dev.jcode.debug / "JCode (debug)") so an
            // `installDebug` never overwrites an installed release or beta build.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "JCode (debug)"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_debug"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_launcher_debug_round"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Side-by-side Beta: a distinct applicationId (dev.jcode.beta) + launcher label
            // ("JCode (beta)") so the Beta APK never overwrites an installed release build.
            if (jcodeIdSuffix.isNotEmpty()) {
                applicationIdSuffix = jcodeIdSuffix
                manifestPlaceholders["appLabel"] = "JCode (${jcodeIdSuffix.removePrefix(".")})"
                manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_beta"
                manifestPlaceholders["appIconRound"] = "@mipmap/ic_launcher_beta_round"
            }
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            // proot + its loaders are exec'd as files from nativeLibraryDir (the only app-owned
            // location W^X allows execve from at targetSdk >= 29), so native libs must be
            // extracted to disk rather than loaded from the APK.
            useLegacyPackaging = true
            // Prebuilt Termux binaries, not JNI libraries — llvm-strip could corrupt them
            // (the loader is a hand-rolled minimal ELF).
            keepDebugSymbols += "**/libproot*.so"
        }
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
