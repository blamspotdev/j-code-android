plugins {
    `java-library`
}

apply(from = rootProject.file("gradle/android-platform.gradle.kts"))

val androidPlatformJar = extra["androidPlatformJar"]!!

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // Deliberately the only entry, and compileOnly at that: everything in this jar is dexed into a
    // single file that `app_process` loads standalone as the shell user, with no classpath beyond the
    // platform itself. A real dependency here would silently fail to resolve on the device.
    compileOnly(files(androidPlatformJar))
}
