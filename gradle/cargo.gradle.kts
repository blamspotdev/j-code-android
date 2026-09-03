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

            val outputDir = project.layout.buildDirectory.dir("generated/cargoJniLibs/$buildMode").get().asFile
            if (!cargoAvailable(project)) {
                // Anything left here is from a build made when cargo was still installed. The root
                // build script has already decided this machine gets the stub, so a stale library
                // would be a second one of the same name beside it.
                if (outputDir.exists()) outputDir.deleteRecursively()
                logger.warn("cargo not available; skipping cargo-ndk build for $cargoPackageName and relying on native stub output.")
                return@doLast
            }

            // One target for every variant, matching the app's arm64-only packaging (the ABI
            // filter and the reason for it live in the root build.gradle.kts). Building a second
            // one would only hand the jniLibs merger a library no JCode APK can carry.
            project.exec {
                workingDir = manifest.parentFile
                environment("CARGO_HOME", cargoHome ?: "")
                environment("RUSTUP_HOME", rustupHome ?: "")
                commandLine(
                    "cargo",
                    "ndk",
                    "-t", "aarch64-linux-android",
                    "-o", outputDir.absolutePath,
                    "build",
                    "--manifest-path", manifest.absolutePath,
                    *(if (buildMode == "release") arrayOf("--release") else emptyArray())
                )
            }
        }
    }

registerCargoNdkBuildTask("cargoBuildDebugJniLibs", "debug")
registerCargoNdkBuildTask("cargoBuildReleaseJniLibs", "release")
