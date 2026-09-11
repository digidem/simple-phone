package org.awana.provision

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.WifiNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole

@RunWith(AndroidJUnit4::class)
class LibraryAndProfileTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "apk-library.json").delete()
        File(context.filesDir, "profiles.json").delete()
        File(context.filesDir, "apks").deleteRecursively()
    }

    // --- APK library ---------------------------------------------------------

    @Test
    fun importingThisAppsOwnApkReadsItsIdentityAndFingerprint() = runBlocking {
        val library = ApkLibrary(context)
        val ownApk = File(context.applicationInfo.sourceDir)

        val entry = library.add(ownApk.toUri()).getOrThrow()

        assertEquals(context.packageName, entry.packageName)
        assertEquals(64, entry.certSha256.length)
        assertTrue(entry.certSha256.all { it in "0123456789abcdef" })
        assertTrue(entry.sizeBytes > 0)
        assertTrue(library.fileFor(entry).isFile)
    }

    @Test
    fun theFingerprintIsShownInReadableGroups() = runBlocking {
        val entry = ApkLibrary(context).add(File(context.applicationInfo.sourceDir).toUri()).getOrThrow()

        // Grouped so a trainer can read it aloud to check it against a source.
        val readable = entry.readableFingerprint
        assertEquals(entry.certSha256, readable.replace(" ", ""))
        assertTrue(readable.contains(" "))
    }

    @Test
    fun theRuntimePermissionsAnApkAsksForAreRecordedInGrantOrder() = runBlocking {
        val entry = ApkLibrary(context).add(File(context.applicationInfo.sourceDir).toUri()).getOrThrow()

        // This app asks for fine location so it can start a hotspot, and that
        // is a runtime permission, so it has to be carried to the kiosk.
        assertTrue(
            "recorded ${entry.permissions}",
            android.Manifest.permission.ACCESS_FINE_LOCATION in entry.permissions,
        )
        // Only runtime permissions: a Device Owner cannot pre-grant the rest.
        assertFalse(android.Manifest.permission.INTERNET in entry.permissions)
        // Foreground location first, because the background grant is refused
        // unless the foreground one has already been made.
        val background = entry.permissions.indexOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (background >= 0) {
            assertTrue(
                entry.permissions.indexOf(android.Manifest.permission.ACCESS_FINE_LOCATION) < background,
            )
        }
    }

    @Test
    fun somethingThatIsNotAnApkIsRejectedWithAReadableReason() = runBlocking {
        val notAnApk = File(context.cacheDir, "notes.txt").apply { writeText("hello") }

        val result = ApkLibrary(context).add(notAnApk.toUri())

        assertTrue(result.isFailure)
        assertEquals("That file is not an Android app (APK).", result.exceptionOrNull()?.message)
    }

    @Test
    fun reimportingAPackageReplacesItRatherThanDuplicating() = runBlocking {
        val library = ApkLibrary(context)
        val ownApk = File(context.applicationInfo.sourceDir).toUri()

        library.add(ownApk).getOrThrow()
        library.add(ownApk).getOrThrow()

        assertEquals(1, library.entries().count { it.packageName == context.packageName })
    }

    @Test
    fun stagingSaysWhatItWouldReplaceWithoutReplacingIt() = runBlocking {
        val library = ApkLibrary(context)
        val ownApk = File(context.applicationInfo.sourceDir).toUri()
        val first = library.add(ownApk).getOrThrow()

        val staged = library.stage(ownApk).getOrThrow()

        assertEquals(first.packageName, staged.replaces?.packageName)
        // Same key, so nothing to warn about, and nothing committed yet either.
        assertFalse(staged.keyChanged)
        library.discard(staged)
        assertEquals(1, library.entries().size)
    }

    @Test
    fun aDifferentSigningKeyIsCaughtBeforeTheFileLands() = runBlocking {
        val library = ApkLibrary(context)
        val ownApk = File(context.applicationInfo.sourceDir).toUri()
        library.add(ownApk).getOrThrow()
        // The stored fingerprint is what a phone checks against, so pretending
        // the library holds a different one is exactly the case in the field.
        val entry = library.entries().single()
        library.replaceIndexForTest(listOf(entry.copy(certSha256 = "f".repeat(64))))

        val staged = library.stage(ownApk).getOrThrow()

        assertTrue(staged.keyChanged)
        assertEquals("f".repeat(64), staged.replaces?.certSha256)
        library.discard(staged)
    }

    @Test
    fun theLibraryCanNameTheDeploymentsThatInstallAnApp() {
        val store = ProfileStore(context)
        store.save(profile("Rio Negro"))
        store.save(profile("Xingu").copy(packages = listOf("org.telegram.messenger")))

        assertEquals(listOf("Rio Negro"), store.using("org.example.fieldapp").map { it.name })
        assertEquals(2, store.using("org.telegram.messenger").size)
        assertTrue(store.using("org.nobody.uses.this").isEmpty())
    }

    @Test
    fun removingAnEntryDeletesItsFile() = runBlocking {
        val library = ApkLibrary(context)
        val entry = library.add(File(context.applicationInfo.sourceDir).toUri()).getOrThrow()
        val file = library.fileFor(entry)

        library.remove(entry.packageName)

        assertNull(library.find(entry.packageName))
        assertFalse(file.exists())
    }

    // --- Deployment profiles -------------------------------------------------

    private fun profile(name: String = "Rio Negro") = DeploymentProfile(
        name = name,
        adminPinHash = AdminPin.hash("2468"),
        packages = listOf("org.example.fieldapp", "org.telegram.messenger"),
        launcher = listOf(LauncherEntry("org.example.fieldapp", LauncherRole.HERO)),
        locale = "pt_BR",
        timeZone = "America/Manaus",
    )

    @Test
    fun aProfileSavedBeforeRolesExistedKeepsItsHomeScreen() {
        // Written by a build that only knew visibleInLauncher. Losing it would
        // silently blank the home screen of every deployment on this phone.
        File(context.filesDir, "profiles.json").writeText(
            """
            [{
              "id": "old",
              "name": "Rio Negro",
              "adminPinHash": "x",
              "packages": ["org.example.fieldapp", "org.telegram.messenger", "org.example.share"],
              "visibleInLauncher": ["org.example.fieldapp", "org.telegram.messenger"]
            }]
            """.trimIndent(),
        )

        val profile = ProfileStore(context).get("old")!!

        assertEquals(
            listOf(
                LauncherEntry("org.example.fieldapp", LauncherRole.HERO),
                LauncherEntry("org.telegram.messenger", LauncherRole.SMALL),
            ),
            profile.launcher,
        )
    }

    @Test
    fun promotingAnAppToHeroDemotesTheOneThatHadIt() {
        val profile = profile().copy(
            launcher = listOf(
                LauncherEntry("org.example.fieldapp", LauncherRole.HERO),
                LauncherEntry("org.telegram.messenger", LauncherRole.SMALL),
            ),
        )

        val promoted = profile.withEntry(
            LauncherEntry("org.telegram.messenger", LauncherRole.HERO),
        )

        assertEquals(1, promoted.launcher.count { it.role == LauncherRole.HERO })
        assertEquals("org.telegram.messenger", promoted.launcher.single { it.role == LauncherRole.HERO }.packageName)
        // Order is the home screen's order, so promoting must not reshuffle it.
        assertEquals(
            listOf("org.example.fieldapp", "org.telegram.messenger"),
            promoted.launcher.map { it.packageName },
        )
    }

    @Test
    fun aChosenIconIsShrunkUntilItFitsTheConfigDocument() {
        // A gallery photo is megapixels; the icon rides inside the config the
        // QR hashes and a phone fetches over a hotspot.
        val big = android.graphics.Bitmap.createBitmap(2400, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until 2400 step 3) {
            for (y in 0 until 1600 step 3) big.setPixel(x, y, (x * 31 + y * 17) or -0x1000000)
        }
        val file = File(context.cacheDir, "icon.png").apply {
            outputStream().use { big.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }

        val encoded = EntryIcon.encode(context, file.toUri()).getOrThrow()

        assertTrue("encoded to ${encoded.length} chars", encoded.length <= 40_000)
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        // Square, because a launcher tile is and a photo is not.
        assertEquals(decoded.width, decoded.height)
    }

    @Test
    fun somethingThatIsNotAPictureIsRefused() {
        val notAnImage = File(context.cacheDir, "notes.txt").apply { writeText("hello") }

        assertTrue(EntryIcon.encode(context, notAnImage.toUri()).isFailure)
    }

    @Test
    fun profilesRoundTripThroughStorage() {
        val store = ProfileStore(context)
        val saved = profile()

        store.save(saved)

        assertEquals(saved, store.get(saved.id))
        assertEquals(1, store.all().size)
    }

    @Test
    fun savingTwiceUpdatesRatherThanAppends() {
        val store = ProfileStore(context)
        val saved = profile()
        store.save(saved)

        store.save(saved.copy(name = "Rio Negro 2"))

        assertEquals(1, store.all().size)
        assertEquals("Rio Negro 2", store.get(saved.id)?.name)
    }

    @Test
    fun duplicatingGivesANewIdentity() {
        val original = profile()
        val copy = original.duplicate()

        assertNotEquals(original.id, copy.id)
        assertEquals("Rio Negro (copy)", copy.name)
        assertEquals(original.packages, copy.packages)
        assertEquals(original.adminPinHash, copy.adminPinHash)
    }

    @Test
    fun exportAndImportCarryTheDeploymentBetweenTrainers() {
        val store = ProfileStore(context)
        val original = profile()

        val imported = store.import(store.export(original)).getOrThrow()

        assertEquals(original.name, imported.name)
        assertEquals(original.packages, imported.packages)
        assertEquals(original.launcher, imported.launcher)
        assertEquals(original.adminPinHash, imported.adminPinHash)
        assertEquals(original.showNotificationShade, imported.showNotificationShade)
        // A new id, so importing a colleague's export cannot silently overwrite
        // a local profile that happens to share one.
        assertNotEquals(original.id, imported.id)
    }

    @Test
    fun preApprovedWifiNetworksSurviveStorageAndExport() {
        val store = ProfileStore(context)
        val networks = listOf(
            WifiNetwork("Sync network", "correcthorsebattery"),
            WifiNetwork("Open network", null),
        )
        val original = profile().copy(wifiNetworks = networks)

        store.save(original)
        val imported = store.import(store.export(original)).getOrThrow()

        // Without these a deployed device can never join a network again: users
        // cannot configure Wi-Fi themselves.
        assertEquals(networks, store.get(original.id)?.wifiNetworks)
        assertEquals(networks, imported.wifiNetworks)
    }

    @Test
    fun importingRubbishFailsRatherThanThrowing() {
        assertTrue(ProfileStore(context).import("{ not json").isFailure)
    }

    @Test
    fun theShadeIsOffOnANewProfile() {
        // The default is the whole point of the project; a regression here would
        // ship every deployment with a pullable shade.
        assertFalse(DeploymentProfile(name = "x", adminPinHash = "y").showNotificationShade)
    }

    @Test
    fun thePinIsStoredOnlyAsAHash() {
        val stored = profile().adminPinHash

        assertFalse(stored.contains("2468"))
        assertTrue(stored.startsWith("pbkdf2_sha256$"))
        // Same encoding the kiosk verifies against: prefix, iterations, salt, hash.
        assertEquals(4, stored.split("$").size)
    }
}
