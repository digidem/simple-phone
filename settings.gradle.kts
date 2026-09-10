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

rootProject.name = "comapeo-kiosk"

include(":app")
include(":policy")
include(":launcher")

// Not shipped. A real APK for the end-to-end provisioning test to install.
include(":sample")
