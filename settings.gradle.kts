pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "JCode"

include(":app")

// Core modules
include(":core:design")
include(":core:adaptive")
include(":core:resource")
include(":core:fs")
include(":core:buffer")
include(":core:editor")
include(":core:editor-decor")
include(":core:editor-completion")
include(":core:treesitter")
include(":core:term")
include(":core:distro")
include(":core:lsp")
include(":core:ctags")
include(":core:debug")
include(":core:vcs")
include(":core:search")
include(":core:config")
include(":core:ext")
include(":core:state")

// Feature modules
include(":feature:explorer")
include(":feature:editor-pane")
include(":feature:terminal-pane")
include(":feature:problems")
include(":feature:scm")
include(":feature:search")
include(":feature:debug")
include(":feature:settings")
include(":feature:sdk-manager")
include(":feature:lsp-manager")
include(":feature:marketplace")
include(":feature:onboarding")

// Native modules
include(":native:core")
include(":native:buffer")
include(":native:editor-render")
include(":native:tree-sitter")
include(":native:libgit2")
include(":native:ripgrep-ffi")
include(":native:pty")
include(":native:vt")
include(":native:wasmtime-ffi")
include(":native:grammars")
include(":native:proot")
