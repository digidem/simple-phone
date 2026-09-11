package org.awana.provision

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole

/**
 * The home screen a deployment produces, at two-thirds size.
 *
 * A trainer checks this before committing six phones to it, so it has to be the
 * launcher's own layout rather than an impression of it: hero pinned at the top,
 * three-column grid under it, and the same theme roles.
 */
@Composable
fun HomePreview(
    entries: List<LauncherEntry>,
    apps: Map<String, ApkEntry>,
    modifier: Modifier = Modifier,
) {
    val shown = entries.filter { it.role != LauncherRole.HIDDEN }
    val hero = shown.firstOrNull { it.role == LauncherRole.HERO }
    val small = shown.filter { it.role == LauncherRole.SMALL }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag(TAG_PREVIEW),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SCALED_GAP),
            modifier = Modifier.padding(SCALED_PADDING),
        ) {
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.preview_nothing_shown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }

            hero?.let { HeroPreview(it, apps[it.packageName]) }

            if (small.isNotEmpty()) {
                // Three fixed columns, wrapping to new rows, exactly as the
                // launcher lays them out.
                small.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(SCALED_GRID_GAP)) {
                        row.forEach { entry ->
                            SmallPreview(
                                entry = entry,
                                app = apps[entry.packageName],
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Keeps one app in one column instead of stretching it.
                        repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPreview(entry: LauncherEntry, app: ApkEntry?) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(90.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(13.dp),
        ) {
            IconTile(entry, app, size = 64.dp, corner = 16.dp)
            Column(modifier = Modifier.padding(start = 11.dp)) {
                Text(
                    text = entry.displayLabel(app),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallPreview(entry: LauncherEntry, app: ApkEntry?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(70.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp),
        ) {
            IconTile(entry, app, size = 37.dp, corner = 11.dp)
            Text(
                text = entry.displayLabel(app),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IconTile(entry: LauncherEntry, app: ApkEntry?, size: Dp, corner: Dp) {
    val icon = rememberAppIcon(app, entry.iconPng)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        icon?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

private fun LauncherEntry.displayLabel(app: ApkEntry?): String =
    label?.takeIf { it.isNotBlank() } ?: app?.label ?: packageName

private fun decodeIcon(base64: String): ImageBitmap? = try {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (e: IllegalArgumentException) {
    Log.w("HomePreview", "Icon override is not readable", e)
    null
}

/**
 * The deployment's own icon if it set one, otherwise the app's.
 *
 * Off the main thread: reading an icon means opening the APK, and a field app
 * can be well over 100 MB.
 */
@Composable
fun rememberAppIcon(app: ApkEntry?, override: String? = null): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, app?.packageName, override) {
        value = withContext(Dispatchers.IO) {
            override?.let(::decodeIcon) ?: app?.let { ApkIcons.of(context, it) }
        }
    }.value
}

/** A library entry's icon at list size, off the main thread like every other. */
@Composable
fun AppTile(app: ApkEntry, size: Dp = 40.dp, corner: Dp = 10.dp) {
    val icon = rememberAppIcon(app)
    Box(modifier = Modifier.size(size).clip(RoundedCornerShape(corner))) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}

/** App icons read straight out of the library's APK files. */
object ApkIcons {

    private const val ICON_PX = 144

    // Loaded from several IO coroutines at once, and a miss is worth caching
    // too: re-parsing an APK that has no readable icon costs the same as one
    // that does.
    private val cache = java.util.Collections.synchronizedMap(HashMap<String, Holder>())

    private class Holder(val icon: ImageBitmap?)

    fun of(context: Context, entry: ApkEntry): ImageBitmap? {
        cache[entry.packageName]?.let { return it.icon }
        val file = ApkLibrary(context).fileFor(entry)
        val pm = context.packageManager
        val icon = runCatching {
            val info = pm.getPackageArchiveInfo(file.path, 0)?.applicationInfo
                ?: return@runCatching null
            // getPackageArchiveInfo leaves both source dirs unset, and the
            // resource loader cannot find the icon without them.
            info.sourceDir = file.path
            info.publicSourceDir = file.path
            info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.onFailure { Log.w("ApkIcons", "No icon in ${file.path}", it) }.getOrNull()
        cache[entry.packageName] = Holder(icon)
        return icon
    }

    fun forget(packageName: String) {
        cache.remove(packageName)
    }
}

private val SCALED_PADDING = 11.dp
private val SCALED_GAP = 11.dp
private val SCALED_GRID_GAP = 5.dp

const val TAG_PREVIEW = "home-preview"
