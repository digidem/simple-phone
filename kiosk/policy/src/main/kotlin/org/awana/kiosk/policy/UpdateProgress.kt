package org.awana.kiosk.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.awana.kiosk.shared.EnrolmentReport

/**
 * Where an update reports what it is doing, for the screen that shows it.
 *
 * A singleton rather than a return value because the work runs in a foreground
 * service and the screen watching it lives in another module: the download has
 * to survive the phone being put down, and under lock task the notification
 * that would normally carry the progress is behind a shade the user cannot
 * open.
 */
object UpdateProgress {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun report(state: UpdateState) {
        _state.value = state
    }

    /** Called when the screen has shown the outcome, so a later visit starts clean. */
    fun clear() {
        _state.value = UpdateState.Idle
    }
}

/**
 * Steps rather than sentences: the launcher owns the wording, and these have to
 * be translated alongside the rest of the screen.
 */
sealed interface UpdateState {

    data object Idle : UpdateState

    data object JoiningWifi : UpdateState

    data object FetchingSettings : UpdateState

    /** [at] counts from one, for "2 of 5". */
    data class Installing(val packageName: String, val at: Int, val of: Int) : UpdateState

    data object ApplyingSettings : UpdateState

    data class Done(val report: EnrolmentReport) : UpdateState

    data class Failed(val reason: String) : UpdateState

    val finished: Boolean get() = this is Done || this is Failed
}
