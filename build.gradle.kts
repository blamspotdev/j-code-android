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
private val configuredCmakeVersion = System.getenv("ANDROID_HOME")
    ?.let(::File)
    ?.resolve("cmake")
    ?.takeIf(File::exists)
    ?.listFiles()
    ?.map(File::getName)
    ?.sorted()
    ?.let { versions ->
        when {
            desiredCmakeVersion in versions -> desiredCmakeVersion
            versions.isNotEmpty() -> versions.last()
            else -> desiredCmakeVersion
        }
    }
    ?: desiredCmakeVersion
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
                // Kotlin 2.1 analysis API). ExpiredTargetSdkVersion is a Play-Store rule; targetSdk
                // stays 28 deliberately so the proot runtime may exec binaries from app storage.
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
                    minSdk = 28

                    externalNativeBuild {
                        cmake {
                            arguments.addAll(
                                listOf(
                                    "-DANDROID_STL=c++_static",
                                    "-DJCODE_NATIVE_MODULE=$nativeModuleId",
                                    "-DJCODE_JNI_OUTPUT_DIR=$jniOutputRoot"
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
