plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The app version — actual Android metadata, single source of truth (VERSION.txt is gone).
//
// `val jcodeVersion` is the version being *prepared*, not the last one shipped: main carries the
// open release train (see docs/specifications/09-platform/02-build-variants-and-release.md), and
// merges no longer move it. The release scripts parse this line, and `-PjcodeVersionName=…`
// overrides it to add the pre-release label a Beta build carries (e.g. 1.7.3-beta.1).
val jcodeVersion = "1.7.3"

val jcodeVersionName: String =
    (project.findProperty("jcodeVersionName") as? String)?.trim()?.takeIf { it.isNotBlank() }
        ?: jcodeVersion

/**
 * versionCode = (MAJOR*10000 + MINOR*100 + PATCH) * 100 + tier.
 *
 * Monotonic, deterministic, offline, and independent of git history (a squash-merge collapsed the
 * old git-commit-count scheme and produced downgrades). The formula must match
 * scripts/build-release.ps1 ($Code) and build-release-common.sh (CODE).
 *
 * The trailing tier is what the old formula had no room for. It used to ignore the pre-release
 * suffix entirely, so 1.7.3-beta.1, 1.7.3-beta.2 and 1.7.3 all derived the *same* code — and
 * successive betas therefore never climbed, which is the one thing a version code has to do.
 * A tier is ordered the way SemVer orders the label it comes from, so a build never goes backwards
 * on its way from the first preview to the release:
 *
 *     1.7.3-alpha.1  1060101      alpha.N -> N
 *     1.7.3-beta.1   1060131      beta.N  -> 30 + N
 *     1.7.3-beta.2   1060132
 *     1.7.3-rc.1     1060161      rc.N    -> 60 + N
 *     1.7.3          1060199      release -> 99   (always above every preview of itself)
 *
 * An unrecognised or absent label reads as a release, which is the safe end of the range: a build
 * whose label could not be understood sorts above the previews rather than silently below them.
 */
val jcodeVersionCode: Int = runCatching {
    val (major, minor, patch) = Regex("""^(\d+)\.(\d+)\.(\d+)""")
        .find(jcodeVersionName)!!.destructured
    val base = major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
    val label = jcodeVersionName.substringAfter('-', "").substringBefore('+')
    val step = Regex("""\.?(\d+)$""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val tier = when {
        label.startsWith("alpha") -> step
        label.startsWith("beta") -> 30 + step
        label.startsWith("rc") -> 60 + step
        else -> 99
    }
    base * 100 + tier.coerceIn(0, 99)
}.getOrNull()?.takeIf { it > 0 } ?: 1000099

// A non-empty `-PjcodeIdSuffix` (e.g. ".beta") gives this build its own applicationId AND launcher
// label so it installs ALONGSIDE the normal release app instead of replacing it (the release script
// passes ".beta" for a Beta build). Its private data (Linux rootfs, settings, sessions) is isolated
// under the suffixed package; only the shared /storage/emulated/0/JCode projects folder is common.
// Empty (the default) keeps the normal dev.blamspot.jcode / "JCode" release identity.
val jcodeIdSuffix: String =
    (project.findProperty("jcodeIdSuffix") as? String)?.trim().orEmpty()

/**
 * Which GitHub release channel this build follows — see [dev.blamspot.jcode.UpdateChecker].
 *
 * Read off the id suffix rather than declared separately, because the two cannot be allowed to
 * disagree: the channel decides which APK the updater downloads, and an APK from the other channel
 * carries the other channel's `applicationId`, so installing it would be installing a *different
 * app* rather than an update. One derivation is what makes that impossible to get wrong.
 */
val jcodeUpdateChannel: String = if (jcodeIdSuffix == ".beta") "beta" else "stable"

/**
 * The base applicationId, overridable with `-PjcodeApplicationId`.
 *
 * Android keys an installed app by its package, so changing this is never an update — it is a
 * second app with an empty data directory. That is what happened at 1.7.3, when the app moved from
 * `dev.jcode` to `dev.blamspot.jcode`; the migration path exists for exactly that (see
 * [dev.blamspot.jcode.MigrationBundle]), and 1.6.1 shipped the export side of it so the release
 * before the rename could hand its environment over.
 *
 * The override is how that path stays exercisable: building an APK under a different id — most
 * usefully the old `-PjcodeApplicationId=dev.jcode` — is the only way to produce the "update that
 * changes the package" the updater has to detect and handle.
 */
val jcodeApplicationId: String =
    (project.findProperty("jcodeApplicationId") as? String)?.trim()?.takeIf { it.isNotBlank() }
        ?: "dev.blamspot.jcode"

android {
    namespace = "dev.blamspot.jcode"
    compileSdk = 36

    defaultConfig {
        applicationId = jcodeApplicationId
        minSdk = 33
        targetSdk = 33
        versionCode = jcodeVersionCode
        versionName = jcodeVersionName

        buildConfigField("String", "UPDATE_CHANNEL", "\"$jcodeUpdateChannel\"")

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
            // Dev builds install as a separate app (dev.blamspot.jcode.debug / "JCode (debug)") so an
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
            // Side-by-side Beta: a distinct applicationId (dev.blamspot.jcode.beta) + launcher label
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
        // The app-sandbox tab talks to the :guest process over a Binder interface that carries a
        // SurfaceControlViewHost.SurfacePackage and raw MotionEvents/KeyEvents.
        aidl = true
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
    implementation(project(":core:editor-completion"))
    implementation(project(":core:term"))
    implementation(project(":core:distro"))
    implementation(project(":core:lsp"))
    implementation(project(":core:debug"))
    implementation(project(":core:search"))
    implementation(project(":core:config"))
    implementation(project(":core:ext-api"))
    implementation(project(":core:diag"))

    // Feature modules
    implementation(project(":feature:explorer"))
    implementation(project(":feature:editor-pane"))
    implementation(project(":feature:debug"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:sdk-manager"))
    implementation(project(":feature:lsp-manager"))
    implementation(project(":feature:marketplace"))
    implementation(project(":feature:onboarding"))

    // Native modules
    implementation(project(":native:buffer"))
    implementation(project(":native:editor-render"))
    implementation(project(":native:ripgrep-ffi"))
    implementation(project(":native:pty"))
    implementation(project(":native:vt"))
    implementation(project(":native:wasmtime-ffi"))
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
