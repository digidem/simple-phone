package org.awana.kiosk.handshake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.awana.kiosk.shared.SetupReport

/** What the host reads back from `/state` after a run. */
object State {
    private val requested = mutableListOf<String>()
    private val reports = mutableListOf<String>()
    private val notes = mutableListOf<String>()

    @Synchronized fun requested(path: String) { requested += path }

    @Synchronized fun note(text: String) { notes += text }

    /**
     * Kept re-encoded when it parses and raw when it does not: a report the
     * setup app could not read is the failure worth seeing, not a dropped body.
     */
    @Synchronized fun report(body: String) {
        reports += runCatching { SetupReport.parse(body).encode() }
            .onFailure { notes += "unreadable report: ${it.message}" }
            .getOrDefault(body)
    }

    @Synchronized fun encode(): String = buildJsonObject {
        put("requested", JsonArray(requested.map(::JsonPrimitive)))
        put("reports", JsonArray(reports.map(::JsonPrimitive)))
        put("notes", JsonArray(notes.map(::JsonPrimitive)))
    }.toString()
}
