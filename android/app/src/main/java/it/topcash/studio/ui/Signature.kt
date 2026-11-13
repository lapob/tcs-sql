package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Intervention
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

private val ink = Color(0xFF101840)

/** Smooth path through the midpoints of the sampled points (same curve as the PC pad). */
private fun List<Offset>.toPath(): Path = Path().also { p ->
    if (isEmpty()) return@also
    p.moveTo(first().x, first().y)
    if (size < 3) { forEach { p.lineTo(it.x, it.y) }; if (size == 1) p.lineTo(first().x + 0.5f, first().y + 0.5f); return@also }
    for (i in 1 until size - 1) { val a = this[i]; val b = this[i + 1]; p.quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
    p.lineTo(last().x, last().y)
}

/** Renders the strokes on white as a compact PNG (max 700 px wide) for the archive and the PDF. */
private fun exportPng(strokes: List<List<Offset>>, size: IntSize, strokePx: Float): ByteArray {
    val scale = minOf(1f, 700f / size.width)
    val bmp = Bitmap.createBitmap((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp); c.drawColor(android.graphics.Color.WHITE); c.scale(scale, scale)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101840.toInt(); style = Paint.Style.STROKE; strokeWidth = strokePx; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    strokes.forEach { c.drawPath(it.toPath().asAndroidPath(), paint) }
    return ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle(); it.toByteArray() }
}

/** Customer signs the intervention report with a finger; the signed report gets its yearly number on the server. */
@Composable fun SignatureSheet(vm: AppViewModel, iv: Intervention, clientName: String, emails: List<String> = emptyList(), mailOn: Boolean = false, onDismiss: () -> Unit, onSigned: (Intervention) -> Unit) {
    val sheet = rememberSheet()
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var signer by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf(iv.hours.replace('.', ',')) }
    var closeIt by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var sendMail by remember { mutableStateOf(mailOn && emails.isNotEmpty()) }
    var mailTo by remember { mutableStateOf(emails.firstOrNull().orEmpty()) }
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, sheetGesturesEnabled = false, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        val sheetScroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().fadeEdges(sheetScroll, vertical = true).verticalScroll(sheetScroll).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Firma del rapportino", style = MaterialTheme.typography.headlineSmall)
            Text(clientName + " · " + iv.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Gap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(hours, { v -> hours = v.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(5) }, Modifier.weight(0.4f), label = { Text("Ore") }, placeholder = { Text("1,5") },
                    singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(signer, { v -> signer = v.take(80) }, Modifier.weight(1f), label = { Text("Nome di chi firma") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            }
            Gap(12.dp)
            // the pad stays white in dark mode too: it is what ends up on paper
            Box(Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.large).background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large).onSizeChanged { s -> padSize = s }) {
                if (strokes.isEmpty() && current.isEmpty())
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Draw, null, tint = Color(0xFF8A90A8)); Text("Firma qui con il dito", Modifier.padding(start = 8.dp), color = Color(0xFF8A90A8))
                    }
                Canvas(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(); down.consume()
                        val pts = mutableListOf(down.position); current = pts.toList()
                        while (true) {
                            val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { c -> c.id == down.id } ?: break
                            ch.historical.forEach { h -> pts += h.position }
                            pts += ch.position; ch.consume(); current = pts.toList()
                            if (!ch.pressed) break
                        }
                        strokes += pts.toList(); current = emptyList()
                    }
                }) {
                    val st = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    strokes.forEach { s -> drawPath(s.toPath(), ink, style = st) }
                    if (current.isNotEmpty()) drawPath(current.toPath(), ink, style = st)
                }
                if (strokes.isNotEmpty()) TextButton({ strokes.clear() }, Modifier.align(Alignment.TopEnd)) { Icon(Icons.Rounded.Close, null, tint = Color(0xFF5A607A)); Text(" Cancella", color = Color(0xFF5A607A)) }
            }
            Gap(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(closeIt, { v -> closeIt = v }); Text("Segna l'intervento come chiuso", style = MaterialTheme.typography.bodyMedium)
            }
            if (mailOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sendMail, { v -> sendMail = v }); Text("Invia il PDF firmato al cliente via email", style = MaterialTheme.typography.bodyMedium)
                }
                if (sendMail) OutlinedTextField(mailTo, { v -> mailTo = v.take(400) }, Modifier.fillMaxWidth().padding(bottom = 6.dp), label = { Text("Email del cliente") }, singleLine = true,
                    shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            }
            Text("Dopo la firma il rapportino non è più modificabile (l'ufficio può annullare la firma). Subito dopo puoi stamparlo o condividerlo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(16.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(close) { Text("Annulla") }
                Box(Modifier.weight(1f))
                val px = with(androidx.compose.ui.platform.LocalDensity.current) { 2.6.dp.toPx() }
                Button({
                    sending = true
                    val png = exportPng(strokes.toList(), padSize, px)
                    vm.signIntervention(iv.id, signer.trim(), hours, png, closeIt) { r -> if (sendMail) vm.sendReport(r.id, mailTo.trim()); onSigned(r); close() }.invokeOnCompletion { sending = false }
                }, enabled = !sending && strokes.isNotEmpty() && signer.trim().length >= 2 && padSize.width > 0 && (!sendMail || mailTo.contains('@')), shape = MaterialTheme.shapes.medium) {
                    Text(if (sending) "Invio…" else "Conferma firma")
                }
            }
        }
    }
}
