package dev.jcode.debug

import java.io.File

/**
 * Android application project detection for the debugger, mirroring `ProjectRunner.androidAppModule`
 * so Debug and Run agree on what an Android app project is (and on which module the APK comes from).
 */
internal object AndroidAppProject {

    private val AGP_ALIAS_RE = Regex("""alias\s*\([^)]*[Aa]ndroid[^)]*[Aa]pplication[^)]*\)""")
    private const val SCAN_DEPTH = 8
    private const val SCAN_FILE_CAP = 8_000
    private const val ROOT_WALK_UP_LIMIT = 8
    private val SCAN_SKIP_DIRS = setOf("node_modules", "bin", "obj", "build", "dist", "out", "target")

    /**
     * The Android application module for a debug target: [projectDir] when it is the Gradle root,
     * else the nearest Gradle root above [hostPath] (the editor's active file may sit in a project
     * whose root the caller only knows by convention). Null when neither is an Android app project.
     */
    fun appModuleFor(hostPath: String, projectDir: String?): File? =
        listOfNotNull(projectDir?.let(::File), gradleRoot(hostPath)).firstNotNullOfOrNull(::appModule)

    /** The module directory of an Android *application* project rooted at [root], or null. */
    fun appModule(root: File): File? {
        if (!File(root, "gradlew").isFile) return null
        val files = root.walkTopDown()
            .maxDepth(SCAN_DEPTH)
            .onEnter { dir -> dir == root || (dir.name !in SCAN_SKIP_DIRS && !dir.name.startsWith(".")) }
            .filter { it.isFile }
            .take(SCAN_FILE_CAP)
            .toList()

        val catalogNamesAgp = File(root, "gradle/libs.versions.toml").takeIf(File::isFile)
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.contains("com.android.application") == true

        for (buildFile in files) {
            if (buildFile.name != "build.gradle" && buildFile.name != "build.gradle.kts") continue
            val text = runCatching { buildFile.readText() }.getOrNull() ?: continue
            // The ROOT build script also names the plugin, as `id("com.android.application") ... apply
            // false` — declaring the version for subprojects without applying it. Matching the file as a
            // whole would pick the root as the app module, so `apply false` is excluded line by line.
            val appliesAgp = text.lineSequence().any { line ->
                !line.contains("apply false") &&
                    (line.contains("com.android.application") || (catalogNamesAgp && AGP_ALIAS_RE.containsMatchIn(line)))
            }
            if (appliesAgp) return buildFile.parentFile
        }

        return files.firstOrNull { f ->
            f.name == "AndroidManifest.xml" &&
                runCatching { f.readText() }.getOrNull()?.contains("android.intent.category.LAUNCHER") == true
        }?.parentFile?.parentFile?.parentFile
    }

    /** The nearest directory at or above [hostPath] holding a `gradlew`, or null. */
    fun gradleRoot(hostPath: String): File? {
        var dir = File(hostPath).parentFile
        var depth = 0
        while (dir != null && depth++ < ROOT_WALK_UP_LIMIT) {
            if (File(dir, "gradlew").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** The newest debug APK Gradle produced for [module] (what the Run flow installs), or null. */
    fun debugApk(module: File): File? = File(module, APK_DIR).takeIf(File::isDirectory)
        ?.walkTopDown()
        ?.filter { it.isFile && it.extension == "apk" }
        ?.maxByOrNull { it.lastModified() }

    /** The module's JVM source roots, for stack-frame -> file resolution in the adapter. */
    fun sourceRoots(module: File): List<File> =
        listOf("src/main/java", "src/main/kotlin").map { File(module, it) }.filter(File::isDirectory)

    const val APK_DIR: String = "build/outputs/apk/debug"
}
