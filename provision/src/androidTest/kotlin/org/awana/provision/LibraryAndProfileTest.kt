package org.awana.provision

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.shared.AdminPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
        visibleInLauncher = listOf("org.example.fieldapp"),
        locale = "pt_BR",
        timeZone = "America/Manaus",
    )

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
        assertEquals(original.visibleInLauncher, imported.visibleInLauncher)
        assertEquals(original.adminPinHash, imported.adminPinHash)
        assertEquals(original.showNotificationShade, imported.showNotificationShade)
        // A new id, so importing a colleague's export cannot silently overwrite
        // a local profile that happens to share one.
        assertNotEquals(original.id, imported.id)
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
