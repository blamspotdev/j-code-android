import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

val cargoManifestPath = extra["cargoManifestPath"] as String
val cargoPackageName = extra["cargoPackageName"] as String

// Gradle 9 removed Project.exec; ExecOperations replaces it, and a script plugin can only reach a
// service through an object Gradle instantiates and injects.
interface ExecOperationsHolder {
    @get:Inject
    val execOperations: ExecOperations
}

val execOperations = objects.newInstance<ExecOperationsHolder>().execOperations

fun cargoAvailable(): Boolean {
    return runCatching {
        val output = ByteArrayOutputStream()
        execOperations.exec {
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

        // Resolved now: Gradle 9 deprecates reaching the Project from a task while it runs.
        val manifest = file(cargoManifestPath)
        val outputDir = layout.buildDirectory.dir("generated/cargoJniLibs/$buildMode").get().asFile

        doLast {
            val cargoHome = System.getenv("CARGO_HOME")
            val rustupHome = System.getenv("RUSTUP_HOME")

            if (!cargoAvailable()) {
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
            execOperations.exec {
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
