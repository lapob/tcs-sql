package it.topcash.studio.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen barcode scanner (CameraX + ML Kit, on-device: no data leaves the phone).
 * EAN-13/8, UPC, Code 128/39, QR and Data Matrix. Returns the first code read, with a haptic tick.
 */
@Composable fun BarcodeScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) CameraScanner(onResult)
            else Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (denied) "Serve il permesso della fotocamera per leggere i codici a barre." else "Richiesta del permesso fotocamera…", color = Color.White, textAlign = TextAlign.Center)
                if (denied) Button({ ask.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp)) { Text("Consenti") }
            }
            IconButton(onDismiss, Modifier.safeDrawingPadding().padding(12.dp).align(Alignment.TopStart),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White)) { Icon(Icons.Rounded.Close, "Chiudi") }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable private fun CameraScanner(onResult: (String) -> Unit) {
    val ctx = LocalContext.current; val owner = LocalLifecycleOwner.current; val haptic = LocalHapticFeedback.current
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX).build())
    }
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }
    DisposableEffect(Unit) { onDispose { runCatching { providerFuture.get().unbindAll() }; scanner.close(); executor.shutdown() } }
    AndroidView(factory = { c ->
        val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || done.get()) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }?.let { v ->
                        if (done.compareAndSet(false, true)) view.post { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onResult(v.trim()) } } }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching { provider.unbindAll(); camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, ContextCompat.getMainExecutor(c))
        view
    }, modifier = Modifier.fillMaxSize())
    // viewfinder with an animated scan line
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(0.1f, 0.9f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "line")
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width * 0.78f; val h = w * 0.62f; val tl = Offset((size.width - w) / 2, (size.height - h) / 2.3f)
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, tl, Size(w, h), CornerRadius(28.dp.toPx()), blendMode = BlendMode.Clear)
        drawRoundRect(Color.White.copy(alpha = 0.9f), tl, Size(w, h), CornerRadius(28.dp.toPx()), style = Stroke(3.dp.toPx()))
        drawLine(BrandRed.copy(alpha = 0.9f), Offset(tl.x + 18.dp.toPx(), tl.y + h * y), Offset(tl.x + w - 18.dp.toPx(), tl.y + h * y), strokeWidth = 3.dp.toPx())
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Inquadra il codice a barre", color = Color.White, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.Center) {
            IconButton({ torch = !torch; camera?.cameraControl?.enableTorch(torch) }, Modifier.size(60.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.18f), contentColor = Color.White)) {
                Icon(if (torch) Icons.Rounded.FlashlightOff else Icons.Rounded.FlashlightOn, if (torch) "Spegni torcia" else "Accendi torcia")
            }
        }
    }
    Box(Modifier.size(0.dp).background(Color.Transparent, CircleShape))
}
