plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.jcode.nativeffi.pty"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments(
                    "-DJCODE_NATIVE_MODULE=pty",
                    "-DJCODE_JNI_OUTPUT_DIR=${project.buildDir}/intermediates/merged_native_libs",
                    "-DJCODE_VARIANT_DIR=debug",
                )
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("${rootProject.projectDir}/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DJCODE_NATIVE_MODULE=pty",
                        "-DJCODE_JNI_OUTPUT_DIR=${project.buildDir}/intermediates/merged_native_libs",
                        "-DJCODE_VARIANT_DIR=release",
                    )
                }
            }
        }
    }
}
