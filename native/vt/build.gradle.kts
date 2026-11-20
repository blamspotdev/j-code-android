plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.blamspot.jcode.nativeffi.vt"

    defaultConfig {
        minSdk = 33
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments(
                    "-DJCODE_NATIVE_MODULE=vt",
                    "-DJCODE_JNI_OUTPUT_DIR=${layout.buildDirectory.get().asFile}/intermediates/merged_native_libs",
                    "-DJCODE_VARIANT_DIR=debug",
                )
                cFlags += listOf("-std=c11", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("${rootProject.projectDir}/native/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DJCODE_NATIVE_MODULE=vt",
                        "-DJCODE_JNI_OUTPUT_DIR=${layout.buildDirectory.get().asFile}/intermediates/merged_native_libs",
                        "-DJCODE_VARIANT_DIR=release",
                    )
                }
            }
        }
    }
}
