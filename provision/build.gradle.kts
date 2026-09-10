import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Same key as the kiosk, from the same gitignored file. See the kiosk's KEYS.md. */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "org.awana.provision"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.awana.provision"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
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

    testOptions {
        unitTests.all {
            // Lets the golden fixture be regenerated in place:
            //   ./gradlew :app:testDebugUnitTest -Dgolden=write
            it.systemProperty("golden", System.getProperty("golden") ?: "")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // The kiosk APK is served byte for byte; compressing it in the assets
    // would change nothing on the wire and only slows the build.
    androidResources.noCompress += "apk"

    sourceSets {
        getByName("debug").assets.srcDirs(layout.buildDirectory.dir("generated/kioskAssets/debug"))
        getByName("release").assets.srcDirs(layout.buildDirectory.dir("generated/kioskAssets/release"))
    }
}

// The bundling tasks below reach into :kiosk:app's task graph, which Gradle
// only allows once that project has been configured.
evaluationDependsOn(":kiosk:app")

/**
 * Bundles the kiosk APK of the same build type into this app's assets, where
 * the provisioning server serves it from /dpc.apk. Debug provision builds
 * carry a debug kiosk and provision a debug fleet, which can never receive
 * production updates; see KEYS.md.
 */
listOf("debug", "release").forEach { variant ->
    val bundle = tasks.register<Copy>("bundleKiosk${variant.replaceFirstChar { it.uppercase() }}") {
        dependsOn(":kiosk:app:assemble${variant.replaceFirstChar { it.uppercase() }}")
        from(rootProject.file("kiosk/app/build/outputs/apk/$variant/app-$variant.apk"))
        into(layout.buildDirectory.dir("generated/kioskAssets/$variant"))
        rename { "kiosk.apk" }
    }
    tasks.matching { it.name == "merge${variant.replaceFirstChar { it.uppercase() }}Assets" }
        .configureEach { dependsOn(bundle) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
