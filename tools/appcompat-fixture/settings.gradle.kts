pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Standalone on purpose: this is a guest to test JCode with, not a part of it, and it must be
// buildable (and breakable) without touching the IDE's own build.
rootProject.name = "appcompat-fixture"
