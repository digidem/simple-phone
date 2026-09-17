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

rootProject.name = "simple-phone"

// The wire protocol between the two apps: config, report, PIN hash, certificates.
include(":shared")

// The generated colour scheme both apps paint themselves with.
include(":design")

// Simple Phone: the Device Owner app installed on every field phone.
include(":kiosk:app")
include(":kiosk:policy")
include(":kiosk:launcher")

// Phone Setup: the trainer's app, which provisions field phones over a hotspot.
include(":setup")

// Not shipped. A real APK for the end-to-end provisioning test to install.
include(":sample")
