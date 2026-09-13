package org.awana.kiosk.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The launcher half of the config document: how a schemaVersion 2 document from
 * a phone already in the field turns into launcher entries, and the one-hero
 * invariant.
 */
class KioskConfigTest {

    private fun v2(visible: String) = """
        {
          "schemaVersion": 2,
          "deploymentId": "d1",
          "deploymentName": "Rio Negro",
          "adminPinHash": "pbkdf2_sha256${'$'}120000${'$'}c2FsdA==${'$'}aGFzaA==",
          "packages": [
            { "packageName": "app.comapeo", "certSha256": "${"a".repeat(64)}" },
            { "packageName": "org.telegram.messenger", "certSha256": "${"b".repeat(64)}" },
            { "packageName": "org.example.share", "certSha256": "${"c".repeat(64)}" }
          ],
          "visibleInLauncher": [$visible]
        }
    """.trimIndent()

    @Test
    fun `a version 2 document becomes a hero and the rest small`() {
        val config = KioskConfig.parse(v2(""""app.comapeo", "org.telegram.messenger""""))

        assertEquals(
            listOf(
                LauncherEntry("app.comapeo", LauncherRole.HERO),
                LauncherEntry("org.telegram.messenger", LauncherRole.SMALL),
            ),
            config.launcher,
        )
        // An app that was installed but never on the home screen stays off it.
        assertTrue(config.launcher.none { it.packageName == "org.example.share" })
    }

    @Test
    fun `the migrated document is written back out as version 3`() {
        val config = KioskConfig.parse(v2(""""app.comapeo""""))

        assertEquals(KioskConfig.SCHEMA_VERSION, config.schemaVersion)
        val encoded = config.encode()
        assertTrue(!encoded.contains("visibleInLauncher"))
        assertEquals(config.launcher, KioskConfig.parse(encoded).launcher)
    }

    @Test
    fun `a version 2 document with nothing visible migrates to an empty launcher`() {
        val config = KioskConfig.parse(v2(""))

        assertTrue(config.launcher.isEmpty())
        assertEquals(3, config.packages.size)
    }

    @Test
    fun `entries win over the version 2 list if a document carries both`() {
        val both = v2(""""app.comapeo"""")
            .replace("\"visibleInLauncher\"", "\"launcher\": [{\"packageName\": \"org.example.share\"}], \"visibleInLauncher\"")

        val config = KioskConfig.parse(both)

        assertEquals(listOf(LauncherEntry("org.example.share", LauncherRole.SMALL)), config.launcher)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two heroes are refused`() {
        val text = KioskConfig(
            deploymentId = "d1",
            deploymentName = "Rio Negro",
            adminPinHash = "x",
            packages = listOf(
                PackageSpec("app.comapeo", "a".repeat(64)),
                PackageSpec("org.telegram.messenger", "b".repeat(64)),
            ),
            launcher = listOf(
                LauncherEntry("app.comapeo", LauncherRole.HERO),
                LauncherEntry("org.telegram.messenger", LauncherRole.HERO),
            ),
        ).encode()

        KioskConfig.parse(text)
    }

    @Test
    fun `no hero at all is legal`() {
        val config = KioskConfig(
            deploymentId = "d1",
            deploymentName = "Rio Negro",
            adminPinHash = "x",
            packages = listOf(PackageSpec("app.comapeo", "a".repeat(64))),
            launcher = listOf(LauncherEntry("app.comapeo", LauncherRole.SMALL)),
        )

        assertNull(KioskConfig.parse(config.encode()).hero)
    }

    @Test
    fun `promoting an app demotes the app that held the role`() {
        val launcher = listOf(
            LauncherEntry("app.comapeo", LauncherRole.HERO),
            LauncherEntry("org.telegram.messenger", LauncherRole.SMALL),
        )

        val promoted = launcher.withEntry(
            LauncherEntry("org.telegram.messenger", LauncherRole.HERO),
        )

        assertEquals(
            listOf(
                LauncherEntry("app.comapeo", LauncherRole.SMALL),
                LauncherEntry("org.telegram.messenger", LauncherRole.HERO),
            ),
            promoted,
        )
    }

    @Test
    fun `entries naming apps this config does not install are ignored`() {
        val config = KioskConfig(
            deploymentId = "d1",
            deploymentName = "Rio Negro",
            adminPinHash = "x",
            packages = listOf(PackageSpec("app.comapeo", "a".repeat(64))),
            launcher = listOf(
                LauncherEntry("app.comapeo", LauncherRole.HERO),
                LauncherEntry("org.gone", LauncherRole.SMALL),
            ),
        )

        assertEquals(listOf("app.comapeo"), config.launcherEntries.map { it.packageName })
        assertTrue(config.smallEntries.isEmpty())
    }

    /**
     * A v3 config written before the lock screen existed must still parse, and
     * must keep the behaviour it was deployed with: no lock screen.
     */
    @Test
    fun `a config with no screen lock field keeps the phone unlocked`() {
        val json = """
            {"schemaVersion":3,"deploymentId":"rio-negro","deploymentName":"Rio Negro",
             "adminPinHash":"x","packages":[],"launcher":[]}
        """.trimIndent()

        assertFalse(KioskConfig.parse(json).screenLock)
    }

    @Test
    fun `asking for a screen lock survives a round trip`() {
        val config = KioskConfig(
            deploymentId = "rio-negro",
            deploymentName = "Rio Negro",
            adminPinHash = "x",
            screenLock = true,
        )

        assertTrue(KioskConfig.parse(KioskJson.pretty.encodeToString(KioskConfig.serializer(), config)).screenLock)
    }
}
