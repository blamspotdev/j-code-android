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
// :native:core configures itself (its JNI output feeds merged_native_libs directly, not the
// generated/jniLibs convention) but must agree with everyone else on the cmake to run.
extra["jcodeCmakeVersion"] = configuredCmakeVersion

private val nativeModuleIds = mapOf(
    ":native:buffer" to "buffer",
    ":native:editor-render" to "editor-render",
    ":native:tree-sitter" to "tree-sitter",
    ":native:libgit2" to "libgit2",
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

                buildTypes {
                    getByName("debug") {
                        ndk {
                            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
                        }

                        externalNativeBuild {
                            cmake {
                                arguments.add("-DJCODE_VARIANT_DIR=debug")
                            }
                        }
                    }

                    getByName("release") {
                        ndk {
                            abiFilters.add("arm64-v8a")
                        }

                        externalNativeBuild {
                            cmake {
                                arguments.add("-DJCODE_VARIANT_DIR=release")
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

                // Rust FFI modules also register generated/cargoJniLibs (see gradle/cargo.gradle.kts).
                // Their CMake target is only a stub for cargo-less machines: once real cargo-built
                // libs exist for a variant, drop the stub dir or the jniLibs merger sees duplicates.
                val cargoModule = path == ":native:ripgrep-ffi" || path == ":native:wasmtime-ffi"
                listOf("debug", "release").forEach { variant ->
                    val cargoLibs = layout.buildDirectory.dir("generated/cargoJniLibs/$variant").get().asFile
                    val hasCargoLibs = cargoModule && cargoLibs.walkTopDown().any { it.extension == "so" }
                    if (!hasCargoLibs) {
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
