import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing comes from a gitignored `keystore.properties`. Without it the
 * release build falls back to debug signing so a fresh clone still builds —
 * `buildVariant` in the enrolment report is what tells you which key you
 * actually got.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "org.awana.kiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.awana.kiosk"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["sentryDsn"] = providers.gradleProperty("sentryDsn").getOrElse("")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // Signing lineage from the first release, so key rotation under
                // APK Signature Scheme v3 stays possible. A lost kiosk key means
                // no enrolled device can ever receive a DPC update again.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
        // The golden provisioning payload lives at the repository root: the
        // :provision unit tests generate it and this test suite parses it.
        // The sample APK is built by :sample for the end-to-end test to install.
        getByName("androidTest").assets.srcDirs(
            rootProject.file("testdata"),
            layout.buildDirectory.dir("generated/androidTestAssets"),
        )
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * Puts a real, installable APK where the end-to-end test can serve it. The test
 * cannot install the kiosk over itself mid-run, and a hand-made file would not
 * exercise the certificate reader or PackageInstaller.
 */
val copySamplePayload = tasks.register<Copy>("copySamplePayload") {
    dependsOn(":sample:assembleDebug")
    from(rootProject.file("sample/build/outputs/apk/debug/sample-debug.apk"))
    into(layout.buildDirectory.dir("generated/androidTestAssets"))
    rename { "sample-payload.apk" }
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(copySamplePayload)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":kiosk:policy"))
    implementation(project(":kiosk:launcher"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.nanohttpd)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
