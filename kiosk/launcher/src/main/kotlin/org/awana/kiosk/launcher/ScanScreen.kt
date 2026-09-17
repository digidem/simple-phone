package org.awana.kiosk.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.shared.SetupCode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the trainer's provisioning code.
 *
 * The same code the setup wizard reads on a factory-fresh phone, so a trainer
 * holds up one code for a room containing both new phones and phones already in
 * service, and cannot pick the wrong one.
 *
 * Anything that is not one of our codes is reported as such and scanning
 * continues: in the field a camera sees posters, price tags and other people's
 * Wi-Fi codes long before it sees the right one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onCode: (SetupCode) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Granted here rather than at provisioning time, so phones already in the
    // field can be updated without being set up again first.
    val allowed = remember { context.canUseCamera() }
    var wrongCode by remember { mutableStateOf(false) }
    var taken by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_SCAN_BACK)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.fillMaxSize().testTag(TAG_SCAN),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!allowed) {
                Text(
                    text = stringResource(R.string.scan_no_camera),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                        .testTag(TAG_SCAN_NO_CAMERA),
                )
                return@Box
            }

            Viewfinder(lifecycleOwner) { text ->
                if (taken) return@Viewfinder
                val code = SetupCode.parse(text)
                if (code == null) {
                    wrongCode = true
                } else {
                    taken = true
                    onCode(code)
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.scan_help),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                if (wrongCode) {
                    Text(
                        text = stringResource(R.string.scan_not_our_code),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(TAG_SCAN_WRONG_CODE),
                    )
                }
            }
        }
    }
}

@Composable
private fun Viewfinder(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onText: (String) -> Unit,
) {
    val context = LocalContext.current
    val view = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

    // Held rather than fetched again on the way out: `getInstance(..).get()`
    // blocks, and the way out runs on the main thread.
    val held = remember { AtomicReference<ProcessCameraProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            held.set(provider)
            val preview = Preview.Builder().build()
                .also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, CodeAnalyzer(onText)) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure { Log.w(TAG, "The camera could not be started", it) }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))

        onDispose {
            held.getAndSet(null)?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
}

/**
 * Decodes the luminance plane straight out of the frame.
 *
 * `rowStride` rather than `width` as the data width: the two differ on plenty
 * of budget cameras, and using the width silently skews every row.
 */
private class CodeAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                bytes,
                plane.rowStride,
                image.height,
                0,
                0,
                minOf(plane.rowStride, image.width),
                image.height,
                false,
            )
            onText(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text)
        } catch (e: NotFoundException) {
            // Most frames have no code in them. Not worth a log line each.
        } catch (e: Exception) {
            Log.w(TAG, "Could not read this frame", e)
        } finally {
            reader.reset()
            image.close()
        }
    }
}

/**
 * The kiosk is the device owner, so it grants itself the camera rather than
 * asking: under lock task a permission dialog is one more thing that can go
 * wrong in front of someone who cannot read it.
 */
private fun Context.canUseCamera(): Boolean {
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    DevicePolicy(this).grantSelf(Manifest.permission.CAMERA)
    return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

private const val TAG = "ScanScreen"

const val TAG_SCAN = "scan"
const val TAG_SCAN_BACK = "scan-back"
const val TAG_SCAN_NO_CAMERA = "scan-no-camera"
const val TAG_SCAN_WRONG_CODE = "scan-wrong-code"
