import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register("detekt") {
    group = "verification"
    description = "Bootstrap placeholder detekt task."
}

private val duplicateManifestResource = "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
private val desiredCmakeVersion = "3.28.3"
// native/CMakeLists.txt uses $<LINK_LIBRARY:WHOLE_ARCHIVE,…> (CMake 3.24+), so anything older
// cannot configure it — 3.22.1, the version the SDK installs by default, is below the floor.
// Numeric ordering, not lexical — "3.9" sorts after "3.28" as a string. Prefer 3.x over 4.x only
// because 4.x additionally needs the CMAKE_POLICY_VERSION_MINIMUM argument passed below for the
// FetchContent'd deps; both ranges are known to work.
private fun cmakeOrdinal(version: String): Long = version.split(".", "-")
    .take(3)
    .fold(0L) { acc, part -> acc * 100_000 + (part.takeWhile(Char::isDigit).toLongOrNull() ?: 0L) }
// Is cargo on this machine? A file lookup rather than running `cargo --version`, so it stays cheap
// and leaves the configuration cache alone. Checked once here because the native modules need the
// answer while Gradle configures — see the cargo notes in the library block below.
private val cargoOnPath: Boolean = run {
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    val names = if (windows) listOf("cargo.exe", "cargo.bat", "cargo.cmd") else listOf("cargo")
    val dirs = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOfNotNull(
        System.getenv("CARGO_HOME")?.plus("${File.separator}bin"),
        System.getProperty("user.home")?.plus("${File.separator}.cargo${File.separator}bin"),
    )).filter(String::isNotBlank)
    dirs.any { dir -> names.any { File(dir, it).isFile } }
}

private val configuredCmakeVersion = System.getenv("ANDROID_HOME")
    ?.let(::File)
    ?.resolve("cmake")
    ?.takeIf(File::exists)
    ?.listFiles()
    ?.map(File::getName)
    ?.let { versions ->
        when {
            desiredCmakeVersion in versions -> desiredCmakeVersion
            else -> {
                val usable = versions.filter { cmakeOrdinal(it) >= cmakeOrdinal("3.24.0") }
                usable.filter { it.startsWith("3.") }.maxByOrNull(::cmakeOrdinal)
                    ?: usable.maxByOrNull(::cmakeOrdinal)
                    ?: desiredCmakeVersion
            }
        }
    }
    ?: desiredCmakeVersion
extra["jcodeCmakeVersion"] = configuredCmakeVersion

private val nativeModuleIds = mapOf(
    ":native:buffer" to "buffer",
    ":native:editor-render" to "editor-render",
    ":native:ripgrep-ffi" to "ripgrep-ffi",
    ":native:pty" to "pty",
    ":native:vt" to "vt",
    ":native:wasmtime-ffi" to "wasmtime-ffi"
)

subprojects {
    tasks.matching { it.name.startsWith("hiltJavaCompile") }.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
        options.release.set(17)
    }

    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }

            packaging {
                resources {
                    excludes += duplicateManifestResource
                }
            }

            lint {
                // NullSafeMutableLiveData crashes lintVitalRelease (androidx.lifecycle detector vs
                // Kotlin 2.1 analysis API). ExpiredTargetSdkVersion is a Play-Store rule (wants 34+);
                // we distribute outside Play and hold targetSdk at 33 until the 34+ gates
                // (FGS types, receiver export flags) are handled.
                disable += setOf("NullSafeMutableLiveData", "ExpiredTargetSdkVersion")
            }
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            nativeModuleIds[path]?.let { nativeModuleId ->
                val jniOutputRoot = layout.buildDirectory.dir("generated/jniLibs").get().asFile.absolutePath.replace("\\", "/")

                compileSdk = 36

                defaultConfig {
                    minSdk = 33

                    // JCode is an arm64-only app, and the embedded Linux runtime is why: proot, its
                    // ELF loaders and its support libs are prebuilt for arm64-v8a alone (see
                    // native/proot/src/main/{jniLibs,assets}), and without them no environment,
                    // toolchain or terminal starts. A second ABI would package a shell of an app
                    // that installs and then cannot run, so every variant is filtered to the one.
                    ndk {
                        abiFilters.add("arm64-v8a")
                    }

                    externalNativeBuild {
                        cmake {
                            arguments.addAll(
                                listOf(
                                    "-DANDROID_STL=c++_static",
                                    "-DJCODE_NATIVE_MODULE=$nativeModuleId",
                                    "-DJCODE_JNI_OUTPUT_DIR=$jniOutputRoot",
                                    // CMake 4 removed compatibility with the < 3.5 minimums some
                                    // FetchContent'd deps still declare (yaml-cpp); this raises
                                    // their floor instead of failing configure. 3.x ignores it.
                                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
                                )
                            )
                        }
                    }
                }

                // Rust FFI modules build their real library with cargo (see gradle/cargo.gradle.kts)
                // and carry a CMake stub for machines without it. Whether cargo will deliver decides
                // two things below — which srcDir is registered, and whether CMake builds the stub as
                // a shared library at all — and they have to agree, so it is asked once.
                //
                // The question is "will cargo build this?", not "has it already?". Both answers are
                // needed while Gradle configures, before any task has run: CMake is handed its half
                // as a flag. Asking whether the output exists yet answers "no" on every clean tree —
                // the stub then gets built AND registered, cargo writes the real library during
                // execution, and two libraries of the same name reach the packager. That made a
                // fresh clone fail and the immediate re-run succeed, since by then the output was
                // there to be found. Whether the machine has cargo is the same answer both times.
                val cargoModule = path == ":native:ripgrep-ffi" || path == ":native:wasmtime-ffi"
                val cargoWillBuild = cargoModule && cargoOnPath

                buildTypes {
                    getByName("debug") {
                        externalNativeBuild {
                            cmake {
                                arguments.add("-DJCODE_VARIANT_DIR=debug")
                                if (cargoWillBuild) arguments.add("-DJCODE_REAL_LIB_PRESENT=ON")
                            }
                        }
                    }

                    getByName("release") {
                        externalNativeBuild {
                            cmake {
                                arguments.add("-DJCODE_VARIANT_DIR=release")
                                if (cargoWillBuild) arguments.add("-DJCODE_REAL_LIB_PRESENT=ON")
                            }
                        }
                    }
                }

                externalNativeBuild {
                    cmake {
                        path = rootProject.file("native/CMakeLists.txt")
                        version = configuredCmakeVersion
                    }
                }

                // Where the CMake stub is picked up from. With cargo on the machine the stub is
                // static and nothing is written here, so registering it would only offer the jniLibs
                // merger a second library of the same name as cargo's.
                if (!cargoWillBuild) {
                    listOf("debug", "release").forEach { variant ->
                        sourceSets.getByName(variant).jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs/$variant"))
                    }
                }

                ndkVersion = "27.2.12479018"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }

            packaging {
                resources {
                    excludes += duplicateManifestResource
                }
            }

            lint {
                // Crashes lintVitalRelease: androidx.lifecycle detector vs Kotlin 2.1 analysis API.
                disable += "NullSafeMutableLiveData"
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(21)
        }
    }
}
