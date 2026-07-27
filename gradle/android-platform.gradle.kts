import java.io.File
import java.util.Properties

// Locates the Android SDK for build scripts that cannot ask AGP for it (a plain java-library has no
// `android.sdkDirectory`), resolving it the way AGP does: `sdk.dir` from local.properties first, then
// the environment. Applying scripts read `androidSdkDir` / `androidPlatformJar` back out of `extra`.

val sdkDir: File = run {
    val fromLocalProperties = rootProject.file("local.properties").takeIf(File::isFile)?.let { file ->
        Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir")
    }
    val path = fromLocalProperties
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found — set sdk.dir in local.properties, or ANDROID_HOME.")
    File(path)
}

// The display-server dex has to load on a minSdk-33 device, so compile it against exactly that
// platform when it is installed. A newer platform is accepted as a fallback: the server touches only
// long-stable APIs, and requiring a second platform download to build the app would be gratuitous.
val platformJar: File = run {
    val platforms = sdkDir.resolve("platforms")
    platforms.resolve("android-33/android.jar").takeIf(File::isFile)
        ?: platforms.listFiles().orEmpty()
            .filter { it.resolve("android.jar").isFile }
            .maxByOrNull { it.name.substringAfter("android-").toIntOrNull() ?: 0 }
            ?.resolve("android.jar")
        ?: error("No Android platform installed under $platforms.")
}

extra["androidSdkDir"] = sdkDir
extra["androidPlatformJar"] = platformJar
