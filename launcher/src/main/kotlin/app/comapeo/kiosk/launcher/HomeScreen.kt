package app.comapeo.kiosk.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A single screen of large icons. No dock, no pinned row, no second page, no
 * app drawer.
 */
@Composable
fun HomeScreen(
    apps: List<LaunchableApp>,
    onLaunch: (String) -> Unit,
    onAdminGesture: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (apps.isEmpty()) {
            Text(
                text = stringResource(R.string.launcher_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .testTag(TAG_EMPTY),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 48.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppIcon(app = app, onLaunch = onLaunch)
                }
            }
        }

        AdminCorner(onTriggered = onAdminGesture, modifier = Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun AppIcon(app: LaunchableApp, onLaunch: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            // clickable and nothing else: long-press has no action, so there
            // is no drag to reorder, no uninstall, no app-info shortcut.
            .clickable { onLaunch(app.packageName) }
            .testTag(tagFor(app.packageName)),
    ) {
        Image(
            bitmap = app.icon.toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
            contentDescription = app.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(96.dp),
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
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

private const val ICON_PX = 192

/** Long enough that it cannot be reached by an accidental press. */
private const val ADMIN_HOLD_MS = 5_000L

const val TAG_EMPTY = "launcher-empty"
const val TAG_ADMIN_CORNER = "launcher-admin-corner"

fun tagFor(packageName: String) = "launcher-app-$packageName"
