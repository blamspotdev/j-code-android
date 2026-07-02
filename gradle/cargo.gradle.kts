import java.io.ByteArrayOutputStream

val cargoManifestPath = extra["cargoManifestPath"] as String
val cargoPackageName = extra["cargoPackageName"] as String

fun cargoAvailable(project: org.gradle.api.Project): Boolean {
    return runCatching {
        val output = ByteArrayOutputStream()
        project.exec {
            commandLine("cargo", "--version")
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }.exitValue == 0
    }.getOrDefault(false)
}

fun registerCargoNdkBuildTask(taskName: String, buildMode: String) =
    tasks.register(taskName) {
        group = "build"
        description = "Builds $buildMode cargo JNI libs for $cargoPackageName"

        doLast {
            val manifest = project.file(cargoManifestPath)
            val cargoHome = System.getenv("CARGO_HOME")
            val rustupHome = System.getenv("RUSTUP_HOME")

            if (!cargoAvailable(project)) {
                logger.warn("cargo not available; skipping cargo-ndk build for $cargoPackageName and relying on native stub output.")
                return@doLast
            }

            val targets = when (buildMode) {
                "release" -> listOf("arm64-v8a")
                else -> listOf("arm64-v8a", "x86_64")
            }

            targets.forEach { abi ->
                val cargoTarget = when (abi) {
                    "arm64-v8a" -> "aarch64-linux-android"
                    "x86_64" -> "x86_64-linux-android"
                    else -> error("Unsupported ABI $abi")
                }

                project.exec {
                    workingDir = manifest.parentFile
                    environment("CARGO_HOME", cargoHome ?: "")
                    environment("RUSTUP_HOME", rustupHome ?: "")
                    commandLine(
                        "cargo",
                        "ndk",
                        "-t", cargoTarget,
                        "-o", project.layout.buildDirectory.dir("generated/cargoJniLibs/$buildMode").get().asFile.absolutePath,
                        "build",
                        "--manifest-path", manifest.absolutePath,
                        *(if (buildMode == "release") arrayOf("--release") else emptyArray())
                    )
                }
            }
        }
    }

registerCargoNdkBuildTask("cargoBuildDebugJniLibs", "debug")
registerCargoNdkBuildTask("cargoBuildReleaseJniLibs", "release")
