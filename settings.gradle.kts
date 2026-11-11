pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Fetch"

// Platform-free code shared by the Android TV app and the desktop app.
include(":shared")
// The Linux (and later Windows/macOS) desktop app.
include(":desktop")

// `-Pfetch.desktopOnly=true` skips the Android modules so the desktop app builds on a
// machine without the Android SDK.
val desktopOnly = providers.gradleProperty("fetch.desktopOnly").orNull.toBoolean()
if (!desktopOnly) {
    include(":app")
    include(":baselineprofile")
    include(":ffmpeg-decoder-downmix")
}
