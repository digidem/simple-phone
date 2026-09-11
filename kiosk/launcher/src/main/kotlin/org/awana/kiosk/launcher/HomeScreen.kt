package org.awana.kiosk.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the home screen has to say. An empty app list is three different
 * situations for the person holding the phone, and only one of them is a
 * problem with the deployment.
 */
sealed interface HomeState {

    data class Apps(val hero: LaunchableApp?, val small: List<LaunchableApp>) : HomeState

    /** No config and nothing recorded: made Device Owner without a scan. */
    data class NotSetUp(val canSetUpAgain: Boolean) : HomeState

    /** No config, and the last attempt recorded why. */
    data class SetupUnfinished(val canSetUpAgain: Boolean) : HomeState

    /** Set up, but the deployment shows nothing here. */
    data object NoApps : HomeState
}

/**
 * A single screen: one hero app across the top and a three-column grid under
 * it. No dock, no second page, no app drawer.
 *
 * It drifts from Material in three places, deliberately — cards sized for
 * recognition rather than list density, a hero that is a layout role rather
 * than a component, and a grid instead of a list. Everything else is stock.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onLaunch: (String) -> Unit,
    onSetUpAgain: () -> Unit,
    onRemoveLock: () -> Unit,
    onAdminGesture: () -> Unit,
    /** Debug builds only; see [TestSetupScreen]. */
    onSetUpForTesting: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (state) {
            is HomeState.Apps -> Apps(state, onLaunch)

            is HomeState.NotSetUp -> Recovery(
                testTag = TAG_NOT_SET_UP,
                glyph = R.drawable.ic_phone_blank,
                glyphContainer = MaterialTheme.colorScheme.surfaceVariant,
                glyphColor = MaterialTheme.colorScheme.onSurfaceVariant,
                headline = stringResource(R.string.launcher_not_set_up),
                body = stringResource(R.string.launcher_not_set_up_body),
                setUpAgainLabel = stringResource(R.string.launcher_set_up)
                    .takeIf { state.canSetUpAgain },
                onSetUpAgain = onSetUpAgain,
                onRemoveLock = onRemoveLock,
                onSetUpForTesting = onSetUpForTesting,
            )

            is HomeState.SetupUnfinished -> Recovery(
                testTag = TAG_SETUP_UNFINISHED,
                glyph = R.drawable.ic_setup_problem,
                glyphContainer = MaterialTheme.colorScheme.errorContainer,
                glyphColor = MaterialTheme.colorScheme.onErrorContainer,
                headline = stringResource(R.string.launcher_setup_unfinished),
                body = stringResource(R.string.launcher_setup_unfinished_body),
                setUpAgainLabel = stringResource(R.string.launcher_set_up_again)
                    .takeIf { state.canSetUpAgain },
                onSetUpAgain = onSetUpAgain,
                onRemoveLock = onRemoveLock,
                onSetUpForTesting = onSetUpForTesting,
            )

            // No recovery buttons: the phone is provisioned, so the PIN works
            // and admin is reachable. This is a pointer, not a way out.
            HomeState.NoApps -> Message(
                testTag = TAG_NO_APPS,
                glyph = R.drawable.ic_no_apps,
                glyphContainer = MaterialTheme.colorScheme.surfaceVariant,
                glyphColor = MaterialTheme.colorScheme.onSurfaceVariant,
                headline = stringResource(R.string.launcher_no_apps),
                body = stringResource(R.string.launcher_no_apps_body),
            ) {
                Text(
                    text = stringResource(R.string.launcher_no_apps_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        AdminCorner(onTriggered = onAdminGesture, modifier = Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun Apps(state: HomeState.Apps, onLaunch: (String) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(SCREEN_GAP),
        modifier = Modifier.fillMaxSize().padding(SCREEN_PADDING),
    ) {
        // Pinned to the top and never stretched: a single app is a hero alone,
        // not a hero grown to fill the screen.
        state.hero?.let { HeroCard(it, onLaunch) }

        if (state.small.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_GAP),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(state.small, key = { it.packageName }) { SmallCard(it, onLaunch) }
            }
        }
    }
}

@Composable
private fun HeroCard(app: LaunchableApp, onLaunch: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
            // clickable and nothing else: long-press has no action, so there is
            // no drag to reorder, no uninstall, no app-info shortcut.
            .clickable { onLaunch(app.packageName) }
            .testTag(tagFor(app.packageName)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(20.dp),
        ) {
            IconTile(app, size = 96.dp, corner = 24.dp)
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = app.label,
                    // Off the M3 ramp on purpose: it jumps from titleLarge at 22
                    // to headlineSmall at 24, and neither reads at arm's length
                    // on a cheap screen.
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.26).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                app.subtitle?.let {
                    Text(
                        text = it,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallCard(app: LaunchableApp, onLaunch: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .height(104.dp)
            .clickable { onLaunch(app.packageName) }
            .testTag(tagFor(app.packageName)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            IconTile(app, size = 56.dp, corner = 16.dp)
            Text(
                text = app.label,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The one place an app's own colour appears; the card around it is a theme role. */
@Composable
private fun IconTile(app: LaunchableApp, size: androidx.compose.ui.unit.Dp, corner: androidx.compose.ui.unit.Dp) {
    Image(
        bitmap = app.icon,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(RoundedCornerShape(corner)),
    )
}

@Composable
private fun Recovery(
    testTag: String,
    glyph: Int,
    glyphContainer: Color,
    glyphColor: Color,
    headline: String,
    body: String,
    setUpAgainLabel: String?,
    onSetUpAgain: () -> Unit,
    onRemoveLock: () -> Unit,
    onSetUpForTesting: (() -> Unit)?,
) {
    Message(testTag, glyph, glyphContainer, glyphColor, headline, body) {
        setUpAgainLabel?.let {
            Button(
                onClick = onSetUpAgain,
                modifier = Modifier.fillMaxWidth().testTag(TAG_SET_UP_AGAIN),
            ) { Text(it) }
        }
        OutlinedButton(
            onClick = onRemoveLock,
            modifier = Modifier.fillMaxWidth().testTag(TAG_REMOVE_LOCK),
        ) { Text(stringResource(R.string.launcher_remove_lock)) }
        Text(
            // Not an oversight: with no config there is no admin PIN to check,
            // so a gate here could never open, and there is nothing on the
            // phone yet to protect.
            text = stringResource(R.string.launcher_no_pin_needed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        onSetUpForTesting?.let {
            TextButton(onClick = it, modifier = Modifier.testTag(TAG_SET_UP_FOR_TESTING)) {
                Text(stringResource(R.string.launcher_test_setup))
            }
        }
    }
}

@Composable
private fun Message(
    testTag: String,
    glyph: Int,
    glyphContainer: Color,
    glyphColor: Color,
    headline: String,
    body: String,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .testTag(testTag),
    ) {
        Surface(
            color = glyphContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(glyph),
                    contentDescription = null,
                    tint = glyphColor,
                    modifier = Modifier.size(52.dp),
                )
            }
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        actions()
    }
}

/**
 * The admin entry: a five-second press on an unlabelled corner. No visual
 * affordance at all — it must not be discoverable by accident, and a user who
 * cannot read must not be able to stumble into it.
 */
@Composable
private fun AdminCorner(onTriggered: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .testTag(TAG_ADMIN_CORNER)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // A null result means the hold outlasted the timeout rather
                    // than ending in a lift or a cancel.
                    val endedEarly = withTimeoutOrNull(ADMIN_HOLD_MS) {
                        waitForUpOrCancellation()
                    }
                    if (endedEarly == null) onTriggered()
                }
            },
    )
}

private val SCREEN_PADDING = 16.dp
private val SCREEN_GAP = 16.dp
private val GRID_GAP = 8.dp

/** Long enough that it cannot be reached by an accidental press. */
private const val ADMIN_HOLD_MS = 5_000L

const val TAG_NOT_SET_UP = "launcher-not-set-up"
const val TAG_SETUP_UNFINISHED = "launcher-setup-unfinished"
const val TAG_NO_APPS = "launcher-no-apps"
const val TAG_SET_UP_AGAIN = "launcher-set-up-again"
const val TAG_REMOVE_LOCK = "launcher-remove-lock"
const val TAG_SET_UP_FOR_TESTING = "launcher-test-setup"
const val TAG_ADMIN_CORNER = "launcher-admin-corner"

fun tagFor(packageName: String) = "launcher-app-$packageName"
