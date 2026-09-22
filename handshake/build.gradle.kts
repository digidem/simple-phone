plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Not shipped. Plays the trainer's phone on the same emulator as the field
 * phone, so the setup wizard's own provisioning flow (ManagedProvisioning) can
 * be run against the kiosk without a camera or a hotspot. See
 * docs/handshake-and-api-matrix.md §2.
 *
 * Signed with the public AOSP platform key from tools/platform-key.sh: the
 * action the wizard uses to start provisioning is behind a signature-level
 * permission, and AOSP emulator images are built with that key.
 */
android {
    namespace = "org.awana.kiosk.handshake"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.awana.kiosk.handshake"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("platform") {
            storeFile = rootProject.file("build/platform-key/platform.p12")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("platform") }
        release { signingConfig = signingConfigs.getByName("platform"); isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("main").assets.srcDirs(layout.buildDirectory.dir("generated/handshakeAssets"))
    }

    // Served byte for byte; the wizard checks the kiosk's signature over the file.
    androidResources.noCompress += "apk"
}

tasks.register("checkPlatformKey") {
    doFirst {
        check(rootProject.file("build/platform-key/platform.p12").isFile) {
            "No platform key. Run tools/platform-key.sh first."
        }
    }
}

// As in :setup: the bundling task reaches into other projects' task graphs.
evaluationDependsOn(":kiosk:app")
evaluationDependsOn(":sample")

/** The debug kiosk and the sample payload, fetched by the wizard and the kiosk respectively. */
val bundleDeployment = tasks.register<Copy>("bundleDeployment") {
    dependsOn(":kiosk:app:assembleDebug", ":sample:assembleDebug")
    from(rootProject.file("kiosk/app/build/outputs/apk/debug/app-debug.apk")) { rename { "kiosk.apk" } }
    from(rootProject.file("sample/build/outputs/apk/debug/sample-debug.apk")) { rename { "sample.apk" } }
    into(layout.buildDirectory.dir("generated/handshakeAssets"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(bundleDeployment)
}
tasks.matching { it.name.startsWith("validateSigning") }.configureEach {
    dependsOn("checkPlatformKey")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nanohttpd)
}
