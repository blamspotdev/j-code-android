plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.jcode.nativeffi.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments(
                    "-DJCODE_NATIVE_MODULE=core",
                    "-DJCODE_JNI_OUTPUT_DIR=${project.buildDir}/intermediates/merged_native_libs",
                    "-DJCODE_VARIANT_DIR=debug",
                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                )
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("${rootProject.projectDir}/native/CMakeLists.txt")
            version = rootProject.extra["jcodeCmakeVersion"] as String
        }
    }

    // Without this the module falls back to AGP's default NDK — a second multi-gigabyte download
    // and a different compiler than every sibling in nativeModuleIds, which all pin this version
    // through the root convention.
    ndkVersion = "27.2.12479018"

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DJCODE_NATIVE_MODULE=core",
                        "-DJCODE_JNI_OUTPUT_DIR=${project.buildDir}/intermediates/merged_native_libs",
                        "-DJCODE_VARIANT_DIR=release",
                    )
                }
            }
        }
    }
}
