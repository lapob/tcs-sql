package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Outbox
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UMachine
import it.topcash.studio.data.norm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// ───────────────────────── photos of the intervention
private const val PHOTO_MAX = 1600
private const val PHOTOS_LIMIT = 30

/** A photo shown in the intervention: on the server ([pending] false) or still on the phone. */
private data class Shot(val id: String, val pending: Boolean, val at: String, val by: String)

/** Shrinks a picture to at most 1600 px (JPEG 82 %, upright): a few hundred KB, sharp enough for a plate or a display. */
private fun shrink(ctx: android.content.Context, uri: Uri): ByteArray? = runCatching {
    val bmp: Bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val s = info.size; val k = maxOf(s.width, s.height).toFloat() / PHOTO_MAX
            if (k > 1f) d.setTargetSize((s.width / k).toInt().coerceAtLeast(1), (s.height / k).toInt().coerceAtLeast(1))
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= PHOTO_MAX) sample *= 2
        var b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rot = ctx.contentResolver.openInputStream(uri)?.use { when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 } } ?: 0
        val k = maxOf(b.width, b.height).toFloat() / PHOTO_MAX
        val m = android.graphics.Matrix().apply { if (k > 1f) postScale(1 / k, 1 / k); if (rot != 0) postRotate(rot.toFloat()) }
        if (k > 1f || rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        b
    }
    ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }.takeIf { it.size > 200 }
}.getOrNull()

/** Small decoded preview (the list never holds full-size pictures in memory). */
private fun thumb(bytes: ByteArray, px: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1; while (minOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

@Composable private fun rememberShot(vm: AppViewModel, s: Shot, px: Int): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, s.id, px) {
        value = withContext(Dispatchers.IO) {
            val b = if (s.pending) runCatching { Outbox.photoFile(s.id).readBytes() }.getOrNull() else vm.photo(s.id)
            b?.let { thumb(it, px) }
        }
    }
    return img
}

/**
 * Photos of the intervention: the plate of the register, the damage, the work done. They go into the PDF report.
 * Taken without network they stay on the phone ("Da inviare") and leave by themselves. Only before the signature.
 */
@Composable fun PhotosSection(vm: AppViewModel, a: Archive, interventionId: String?, editable: Boolean) {
    val ctx = LocalContext.current
    val me by vm.me.collectAsStateWithLifecycle()
    val queue by vm.outbox.collectAsStateWithLifecycle()
    val shots = remember(a, queue, interventionId) {
        if (interventionId == null) emptyList()
        else a.photos(interventionId).map { Shot(it.id, false, it.at, it.by) } + queue.filter { it.kind == Outbox.PHOTO && it.ref == interventionId }.map { Shot(it.id, true, "", me?.name.orEmpty()) }
    }
    var busy by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shotFile = remember { File(ctx.cacheDir, "camera/shot.jpg") }
    fun send(uris: List<Uri>) {
        if (interventionId == null || uris.isEmpty()) return
        val room = PHOTOS_LIMIT - shots.size
        if (room <= 0) { vm.notify("Massimo $PHOTOS_LIMIT foto per intervento"); return }
        busy = true
        scope.launch {
            val jpgs = withContext(Dispatchers.Default) { uris.take(room).mapNotNull { shrink(ctx, it) } }
            if (jpgs.size < uris.take(room).size) vm.notify("Una foto non si apre: riprova con un'altra")
            vm.addPhotos(interventionId, jpgs).join(); busy = false
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) send(listOf(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shotFile)))
    }
    fun shoot() {
        shotFile.parentFile?.mkdirs(); shotFile.delete()
        runCatching { camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shotFile)) }.onFailure { vm.notify("Nessuna fotocamera disponibile") }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) shoot() else vm.notify("Consenti l'uso della fotocamera a Top Cash per scattare le foto") }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { send(it) }

    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Foto" + if (shots.isNotEmpty()) " (${shots.size})" else "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    if (shots.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shots.size, key = { shots[it].id }) { i -> val s = shots[i]
            val img = rememberShot(vm, s, 240)
            val alpha by animateFloatAsState(if (img != null) 1f else 0f, tween(260), label = "thumb")
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { viewing = i }.animateItem()) {
                img?.let { Image(it, "Foto ${i + 1}", Modifier.fillMaxSize().padding(0.dp), contentScale = ContentScale.Crop, alpha = alpha) }
                if (s.pending) Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CloudUpload, "Da inviare", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
    if (interventionId == null) Text("Salva l'intervento per aggiungere le foto.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    else if (editable) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton({
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot() else askCamera.launch(Manifest.permission.CAMERA)
        }, Modifier.weight(1f).height(48.dp), enabled = !busy, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(20.dp)); Text("Scatta", Modifier.padding(start = 8.dp), maxLines = 1)
        }
        OutlinedButton({ gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f).height(48.dp), enabled = !busy, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(20.dp)); Text("Galleria", Modifier.padding(start = 8.dp), maxLines = 1)
        }
    } else if (shots.isEmpty()) Text("Nessuna foto.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    viewing?.let { start ->
        if (shots.isEmpty()) { viewing = null; return@let }
        PhotoViewer(vm, shots, start.coerceIn(0, shots.size - 1), canDelete = { s -> editable && (s.pending || me?.role != "tecnico" || norm(s.by) == norm(me?.name)) }, onClose = { viewing = null })
    }
}

@Composable private fun PhotoViewer(vm: AppViewModel, shots: List<Shot>, start: Int, canDelete: (Shot) -> Boolean, onClose: () -> Unit) {
    var i by remember { mutableStateOf(start) }
    var ask by remember { mutableStateOf(false) }
    val s = shots[i.coerceIn(0, shots.size - 1)]
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val img = rememberShot(vm, s, 1600)
            androidx.compose.animation.AnimatedContent(img, Modifier.fillMaxSize(), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "photo") { b ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (b == null) CircularProgressIndicator(color = Color.White) else Image(b, "Foto ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Rounded.Close, "Chiudi", tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Foto ${i + 1} di ${shots.size}", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(if (s.pending) "Da inviare · resta sul telefono finché torna la rete" else listOf(Dates.short(Dates.parse(s.at.take(10))), s.by).filter { it.isNotBlank() && it != "—" }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (canDelete(s)) IconButton({ ask = true }) { Icon(Icons.Rounded.Delete, "Togli la foto", tint = Color.White) }
            }
            if (shots.size > 1) Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                IconButton({ i = (i - 1 + shots.size) % shots.size }, Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Precedente", tint = Color.White) }
                IconButton({ i = (i + 1) % shots.size }, Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Successiva", tint = Color.White) }
            }
        }
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false }, title = { Text("Togliere la foto?") }, text = { Text("Sparisce dall'intervento e dal rapportino.") },
        confirmButton = { TextButton({ ask = false; vm.deletePhoto(s.id, s.pending); if (shots.size <= 1) onClose() else if (i >= shots.size - 1) i-- }) { Text("Togli", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ ask = false }) { Text("Annulla") } })
}

// ───────────────────────── changes waiting for the network
/** "3 modifiche da inviare": the work done without network, sent by itself when the connection is back. */
@Composable fun PendingBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val queue by vm.outbox.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    AnimatedVisibility(queue.isNotEmpty(), modifier, enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.96f), exit = fadeOut(tween(160))) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                val n = queue.size
                Text(if (n == 1) "1 modifica da inviare" else "$n modifiche da inviare", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(if (online) "Invio in corso…" else "Salvate sul telefono: partono da sole appena torna la connessione.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f))
            }
            if (online) TextButton({ vm.flushOutbox() }) { Text("Invia ora") }
        }
    }
}

// ───────────────────────── machines to check
/**
 * Machines whose verification is overdue for over a year: often already gone from the customer. The office says
 * "dismessa" (out of the round, the deadlines and, without other machines, the customer leaves the list) or
 * "ancora attiva" (back in the deadlines). Mastro is never touched; every choice can be undone.
 */
@Composable fun MachinesCheckScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    val me by vm.me.collectAsStateWithLifecycle()
    val canFlag = me?.seesAccounting == true || me?.isAdmin == true
    var tab by rememberSaveable { mutableStateOf("Da controllare") }
    var q by rememberSaveable { mutableStateOf("") }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val flagged = remember(a) { a.machines.filter { it.flag.isNotEmpty() && it.mastroActive } }
    val list = remember(a, tab, q) {
        val n = norm(q.trim())
        when (tab) { "Dismesse" -> flagged.filter { it.flag == "dismessa" }; "Confermate" -> flagged.filter { it.flag == "attiva" }; else -> a.staleMachines }
            .filter { n.isEmpty() || norm(it.clientName + " " + it.m.model + " " + it.m.serial + " " + it.m.fiscalSerial).contains(n) }
    }
    LaunchedEffect(tab) { sel = emptySet() }
    val ids = list.map { it.m.id }.toSet()
    val chosen = sel.intersect(ids)
    Page(vm, "Macchine da controllare", "${a.staleMachines.size} con verificazione scaduta da oltre un anno", onBack = { nav.popBackStack() }) {
        item(key = "intro") {
            Text("Verificazione scaduta da più di un anno: spesso la cassa non c'è più. Segnala come dismessa (esce dal giro e dalle scadenze) o conferma che è ancora attiva. Mastro non viene toccato e ogni scelta si può annullare.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        }
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, modello o matricola") }
        item(key = "tabs") { ChoiceChips(listOf("Da controllare", "Dismesse", "Confermate"), tab, { tab = it }, Modifier.padding(vertical = 10.dp)) }
        if (canFlag && list.isNotEmpty()) item(key = "bulk") {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(chosen.size == ids.size && ids.isNotEmpty(), { on -> sel = if (on) ids else emptySet() })
                Text(if (chosen.isEmpty()) "Seleziona tutte (${ids.size})" else "${chosen.size} selezionate", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                AnimatedVisibility(chosen.isNotEmpty(), enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (tab == "Da controllare") {
                            TextButton({ confirm = "attiva" }) { Text("Ancora attive") }
                            Button({ confirm = "dismessa" }, shape = MaterialTheme.shapes.medium) { Text("Dismesse") }
                        } else Button({ confirm = "" }, shape = MaterialTheme.shapes.medium) { Text("Di nuovo da controllare") }
                    }
                }
            }
        }
        if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.TaskAlt, if (tab == "Da controllare") "Tutto controllato" else "Nessuna macchina", if (tab == "Da controllare") "Nessuna macchina con verificazione scaduta da oltre un anno." else "Qui compaiono le macchine già decise.") }
        items(list.size.coerceAtMost(600), key = { list[it].m.id }) { i -> val m = list[i]
            StaleRow(m, canFlag, m.m.id in chosen, Modifier.padding(bottom = 8.dp).animateItem(), onToggle = { sel = if (m.m.id in sel) sel - m.m.id else sel + m.m.id }) { m.client?.let { nav.openClient(it.code) } }
        }
        if (list.size > 600) item(key = "more") { Text("Altre ${list.size - 600}: usa la ricerca.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    confirm?.let { state ->
        val pick = list.filter { it.m.id in chosen }
        // customers left without any active machine leave the list by themselves: say it before
        val lose = if (state == "dismessa") pick.mapNotNull { it.client }.distinct().filter { c -> c.machines.none { it.m.active && it.m.id !in chosen } } else emptyList()
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(when (state) { "dismessa" -> "Segnare ${pick.size} come dismesse?"; "attiva" -> "Confermare ${pick.size} ancora attive?"; else -> "Rimettere ${pick.size} da controllare?" }) },
            text = { Text(when (state) {
                "dismessa" -> "Escono dal giro e dalle scadenze. In Mastro restano come sono." + if (lose.isNotEmpty()) "\n\n${if (lose.size == 1) "1 cliente resta" else "${lose.size} clienti restano"} senza macchine attive ed esce dall'elenco (ripristinabile): " + lose.take(4).joinToString(", ") { it.name } + if (lose.size > 4) "…" else "" else ""
                "attiva" -> "Tornano nelle scadenze con la verificazione da fare."
                else -> "Tornano nell'elenco delle macchine da controllare."
            }) },
            confirmButton = { Button({ confirm = null; vm.flagMachines(pick.map { it.m.id }, state) { sel = emptySet() } }) { Text("Conferma") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Annulla") } })
    }
}

@Composable private fun StaleRow(m: UMachine, canFlag: Boolean, checked: Boolean, modifier: Modifier, onToggle: () -> Unit, onClick: () -> Unit) {
    CardBox(modifier, onClick = onClick, padding = PaddingValues(start = if (canFlag) 4.dp else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canFlag) Checkbox(checked, { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(m.clientName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(m.m.model, m.m.serial).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Verificata il " + Dates.short(m.verification) + (m.verDays?.let { " · scaduta " + Dates.relative(it) } ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = LocalStatus.current.bad, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // in "Da controllare" the tab already says it: the room goes to the dates
            if (m.flag.isNotEmpty()) Pill(if (m.flag == "dismessa") "Dismessa" else "Attiva", if (m.flag == "dismessa") Tone.Neutral else Tone.Ok, Modifier.padding(start = 8.dp))
        }
    }
}

// ───────────────────────── global search
private const val SEARCH_SHOW = 6

/** One field for everything: customers, interventions, registers and (administrator) warehouse articles. */
@Composable fun SearchScreen(vm: AppViewModel, a: Archive, nav: NavHostController, onOpenIntervention: (String) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var more by remember { mutableStateOf(setOf<String>()) }
    val n = norm(q.trim())
    val words = remember(n) { n.split(' ').filter { it.isNotBlank() } }
    fun hit(s: String) = words.all { s.contains(it) }
    val clients = remember(a, words) { if (words.isEmpty()) emptyList() else a.clients.filter { hit(it.search) } }
    val ints = remember(a, words) { if (words.isEmpty()) emptyList() else a.interventions.filter { x -> hit(norm(listOf(x.clientName, x.i.title, x.i.work, x.i.technician, x.i.reportNo, x.i.status).joinToString(" "))) } }
    val machines = remember(a, words) { if (words.isEmpty()) emptyList() else a.machines.filter { m -> hit(norm(listOf(m.clientName, m.m.model, m.m.serial, m.m.fiscalSerial, m.m.type).joinToString(" "))) } }
    val articles = remember(a, words, me) { if (words.isEmpty() || me?.isAdmin != true) emptyList() else a.state.articles.filter { x -> x.active && hit(norm(listOf(x.code, x.name, x.barcode, x.category, x.supplierCode).joinToString(" "))) } }
    val total = clients.size + ints.size + machines.size + articles.size
    Page(vm, "Cerca", if (words.isEmpty()) "Clienti, interventi, casse" + if (me?.isAdmin == true) ", articoli" else "" else if (total == 1) "1 risultato" else "$total risultati", onBack = { nav.popBackStack() }) {
        item(key = "field") { SearchField(q, { q = it; more = emptySet() }, "Nome, P.IVA, matricola, rapportino, articolo…", focus = true) }
        if (words.isEmpty()) item(key = "hint") { Empty(Icons.Rounded.Search, "Cerca in tutto l'archivio", "Scrivi anche più parole: «rossi firenze», «rch 2023», il numero di un rapportino o di una matricola.") }
        else if (total == 0) item(key = "none") { Empty(Icons.Rounded.Search, "Nessun risultato", "Prova con meno parole o con una parte della matricola.") }
        fun <T> group(key: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, list: List<T>, id: (T) -> String, row: @Composable (T, Modifier) -> Unit) {
            if (list.isEmpty()) return
            item(key = "h$key") {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp).animateItem(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("$title · ${list.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
                }
            }
            val shown = if (key in more) list.take(200) else list.take(SEARCH_SHOW)
            items(shown.size, key = { "$key-" + id(shown[it]) }) { i -> row(shown[i], Modifier.padding(bottom = 8.dp).animateItem()) }
            if (list.size > SEARCH_SHOW) item(key = "m$key") {
                TextButton({ more = if (key in more) more - key else more + key }, Modifier.animateItem()) { Text(if (key in more) "Mostra meno" else "Mostra tutti (${list.size})") }
            }
        }
        group("c", "Clienti", Icons.Rounded.Groups, clients, { it.code }) { c, m -> ClientRow(c, m) { nav.openClient(c.code) } }
        group("i", "Interventi", Icons.Rounded.Build, ints, { it.i.id }) { x, m -> InterventionRow(x, a, m) { onOpenIntervention(x.i.id) } }
        group("m", "Casse e macchine", Icons.Rounded.PointOfSale, machines, { it.m.id }) { x, m -> MachineCard(x, m, showClient = true) { x.client?.let { nav.openClient(it.code) } } }
        group("a", "Articoli", Icons.Rounded.Inventory2, articles, { it.code }) { x, m ->
            CardBox(m, onClick = { nav.navigate("article/${Uri.encode(x.code)}") }, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                Text(x.name.ifBlank { x.code }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(x.code, x.category, x.barcode).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Home / Macchine: entry to the machines to check (only when there are some). */
@Composable fun StaleMachinesCard(a: Archive, nav: NavHostController, modifier: Modifier = Modifier) {
    val n = a.staleMachines.size
    if (n == 0) return
    CardBox(modifier, onClick = { nav.navigate("machinesCheck") { launchSingleTop = true } }, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(LocalStatus.current.due.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PointOfSale, null, tint = LocalStatus.current.due)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(if (n == 1) "1 macchina da controllare" else "$n macchine da controllare", style = MaterialTheme.typography.titleSmall)
                Text("Verificazione scaduta da oltre un anno: dismessa o ancora attiva?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}
