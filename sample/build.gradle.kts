plugins {
    alias(libs.plugins.android.application)
}

/**
 * A minimal, genuinely installable APK used as a provisioning payload in tests.
 *
 * The end-to-end test needs a real APK to download, verify and install: it
 * cannot install the kiosk over itself mid-run, and a hand-made file would not
 * exercise the certificate reader or PackageInstaller at all. Nothing ships
 * this — it exists only so `:app`'s androidTest has something to install.
 */
android {
    namespace = "org.awana.kiosk.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.awana.kiosk.sample"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}
