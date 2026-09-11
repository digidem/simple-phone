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

rootProject.name = "field-kiosk"

// The wire protocol between the two apps: config, report, PIN hash, certificates.
include(":shared")

// The generated colour scheme both apps paint themselves with.
include(":design")

// The Device Owner app installed on every field device.
include(":kiosk:app")
include(":kiosk:policy")
include(":kiosk:launcher")

// The trainer's app that provisions field devices over a hotspot.
include(":provision")

// Not shipped. A real APK for the end-to-end provisioning test to install.
include(":sample")
