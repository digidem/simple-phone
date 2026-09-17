package org.awana.kiosk.setup

import org.awana.kiosk.shared.SetupReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one status line the session screen shows. What it says is the whole
 * feature: there is no total anywhere, so the only claim it can make is what is
 * in flight and what has reported.
 */
class SessionStateTest {

    private val now = 1_700_000_000_000L

    private fun report(id: String, ok: Boolean, at: Long = now) = SetupReport(
        deviceId = id,
        deviceLabel = id.uppercase(),
        deploymentId = "d1",
        deploymentName = "Rio Negro",
        manufacturer = "Nokia",
        model = "G22",
        androidVersion = "12",
        apiLevel = 31,
        kioskVersion = "0.1.0 (1)",
        buildVariant = "sig:abc",
        isDeviceOwner = true,
        failures = if (ok) emptyList() else listOf("One app could not be installed"),
        reportedAtEpochMs = at,
    )

    private fun downloading(address: String, agoMs: Long = 0, percent: Int = 40) =
        Downloading(address = address, step = "CoMapeo", percent = percent, lastSeenEpochMs = now - agoMs)

    @Test
    fun `nothing at all is idle`() {
        assertEquals(SessionStatus.Idle, SessionState().status(now))
    }

    @Test
    fun `a phone mid-download is in progress`() {
        val state = SessionState(downloading = listOf(downloading("10.0.0.2")))

        assertEquals(SessionStatus.InProgress, state.status(now))
    }

    @Test
    fun `reports with nothing in flight is complete`() {
        val state = SessionState(reports = listOf(report("a", ok = true)))

        assertEquals(SessionStatus.Complete, state.status(now))
    }

    @Test
    fun `a problem outranks everything else`() {
        val state = SessionState(
            reports = listOf(report("a", ok = true), report("b", ok = false)),
            downloading = listOf(downloading("10.0.0.2")),
        )

        assertEquals(SessionStatus.Problem, state.status(now))
        // The second line carries what the headline displaced, so no count is lost.
        assertEquals(1, state.succeeded)
        assertEquals(1, state.failed)
    }

    @Test
    fun `in progress outranks complete`() {
        val state = SessionState(
            reports = listOf(report("a", ok = true)),
            downloading = listOf(downloading("10.0.0.2")),
        )

        assertEquals(SessionStatus.InProgress, state.status(now))
    }

    @Test
    fun `a phone that went quiet is not in flight, so stopping is safe`() {
        val state = SessionState(
            reports = listOf(report("a", ok = true)),
            downloading = listOf(downloading("10.0.0.2", agoMs = Downloading.SILENT_AFTER_MS + 1)),
        )

        assertTrue(state.inFlight(now).isEmpty())
        assertEquals(SessionStatus.Complete, state.status(now))
    }

    @Test
    fun `silence is what makes a phone stopped responding`() {
        val state = SessionState(
            downloading = listOf(
                downloading("10.0.0.2", agoMs = 1_000),
                downloading("10.0.0.3", agoMs = Downloading.SILENT_AFTER_MS + 1, percent = 60),
            ),
        )

        val phones = state.phones(now)

        assertTrue(phones.any { it is Phone.Copying })
        val silent = phones.filterIsInstance<Phone.Silent>().single()
        assertEquals("10.0.0.3", silent.device.address)
        assertEquals(60, silent.device.percent)
    }

    @Test
    fun `a phone that reported is no longer anonymous`() {
        // The session drops the address half when the report arrives; what is
        // left is one row per phone, not two.
        val state = SessionState(reports = listOf(report("a", ok = true)))

        assertEquals(1, state.phones(now).size)
        assertTrue(state.phones(now).single() is Phone.Reported)
    }
}
