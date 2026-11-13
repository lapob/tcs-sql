package it.topcash.studio.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.PortalInput
import it.topcash.studio.data.PortalView
import it.topcash.studio.data.StationState
import it.topcash.studio.data.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Portals kept by the server (owner only): the same as the site's Sincronizzazione → Portali sul server.
 *  The server reads each portal with a browser of its own, so no office PC or Windows app is needed. */
@Composable fun PortalsScreen(vm: AppViewModel, nav: NavHostController) {
    val list by vm.portals.collectAsState()
    var viewing by remember { mutableStateOf<PortalView?>(null) }
    var ask by remember { mutableStateOf<Pair<String, PortalView>?>(null) }
    LaunchedEffect(viewing) { while (viewing == null) { vm.loadPortals(); delay(8000) } }
    Page(vm, "Portali", "Chi li legge: il server o il PC dell’ufficio", onBack = { nav.popBackStack() }) {
        val l = list
        if (l == null) { item(key = "load") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }; return@Page }
        if (!l.browser) item(key = "nobrowser") { Text("Sul server non c’è Microsoft Edge o Google Chrome: serve uno dei due per leggere i portali.", style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.of(Tone.Bad), modifier = Modifier.padding(bottom = 10.dp)) }
        // two groups: online on the server (works with the office PC off) and on the office PC (Windows app)
        val online = l.portals.filter { it.enabled && !it.blocked }
        val pc = l.portals.filter { !it.enabled || it.blocked }
        val order = online + pc
        if (online.isNotEmpty()) {
            item(key = "g-server") { GroupHead(Icons.Rounded.Cloud, Tone.Ok, "Online sul server", online.size, "Si leggono da soli, anche con il PC dell’ufficio spento. L’accesso lo fai tu una volta sola: la password va al portale e il server non la salva.") }
            items(online.size, key = { online[it].id }) { i -> val p = online[i]
                PortalCard(p, Modifier.padding(bottom = 10.dp).appear(order.indexOf(p)),
                    onEnable = { ask = "on" to p }, onSync = { vm.portalAction(p.id, "sync", "Lettura avviata sul server") }, onOpen = { viewing = p },
                    onStop = { vm.portalAction(p.id, "stop", "Lettura interrotta: dati precedenti conservati") }, onOff = { ask = "off" to p }, onForget = { ask = "forget" to p }) }
        }
        if (pc.isNotEmpty()) {
            item(key = "g-pc") {
                GroupHead(Icons.Rounded.Computer, Tone.Info, "Sul PC dell’ufficio", pc.size,
                    "Li legge l’app Windows" + (if (pc.any { it.blocked }) ": il portale chiede la verifica anti-robot di Cloudflare, che il server non può superare" else "") + ". Funzionano finché il PC resta acceso con l’app aperta.",
                    l.station)
            }
            items(pc.size, key = { pc[it].id }) { i -> val p = pc[i]
                PortalCard(p, Modifier.padding(bottom = 10.dp).appear(order.indexOf(p)),
                    onEnable = { ask = "on" to p }, onSync = { vm.portalAction(p.id, "sync", "Lettura avviata sul server") }, onOpen = { viewing = p },
                    onStop = { vm.portalAction(p.id, "stop", "Lettura interrotta: dati precedenti conservati") }, onOff = { ask = "off" to p }, onForget = { ask = "forget" to p }) }
        }
    }
    ask?.let { (k, p) ->
        val (title, text, ok) = when (k) {
            "on" -> Triple("Spostare ${p.name} sul server?", "Il server apre il portale con un browser tutto suo e lo legge da solo, anche con il PC dell’ufficio spento o l’app disinstallata. Serve un tuo accesso, una volta sola: la password la scrivi tu nel portale e non viene salvata.", "Sposta sul server")
            "off" -> Triple("Togliere ${p.name} dal server?", "Il server smette di leggerlo; l’accesso resta salvato e puoi rimetterlo quando vuoi. Senza server lo legge solo la postazione dell’ufficio, se è accesa.", "Togli dal server")
            else -> Triple("Scollegare ${p.name}?", "Il server cancella la sessione salvata del portale: per leggerlo di nuovo dovrai accedere un’altra volta. I dati già letti restano.", "Scollega")
        }
        AlertDialog(onDismissRequest = { ask = null }, title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton({
                ask = null
                when (k) { "on" -> vm.portalEnabled(p.id, true) { viewing = p }; "off" -> vm.portalEnabled(p.id, false); else -> vm.portalAction(p.id, "forget", "${p.name} scollegato") }
            }) { Text(ok, color = if (k == "forget") LocalStatus.current.of(Tone.Bad) else MaterialTheme.colorScheme.primary) } },
            dismissButton = { TextButton({ ask = null }) { Text("Annulla") } })
    }
    viewing?.let { p -> PortalLiveView(vm, p) { done -> viewing = null; if (done) vm.portalDone(p.id) } }
}

@Composable private fun GroupHead(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Tone, title: String, count: Int, text: String, station: StationState? = null) {
    val c = LocalStatus.current.of(tone)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = c) }
            Spacer(Modifier.size(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(count.toString(), Tone.Neutral)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (station != null) {
            val (t, s) = when {
                station.online -> Tone.Ok to "PC acceso · sentito ${agoText(station.lastContact)}"
                station.lastContact.isNotBlank() -> Tone.Bad to "PC spento o app chiusa da ${agoText(station.lastContact, bare = true)}"
                else -> Tone.Neutral to "In attesa del PC dell’ufficio"
            }
            Box(Modifier.padding(top = 8.dp)) { Pill(s, t) }
        }
    }
}

private fun agoText(iso: String, bare: Boolean = false): String = runCatching {
    val m = java.time.Duration.between(java.time.OffsetDateTime.parse(iso), java.time.OffsetDateTime.now()).toMinutes()
    val t = when { m < 1 -> "adesso"; m < 60 -> "$m min"; m < 1440 -> "${m / 60} ore"; else -> "${m / 1440} giorni" }
    if (bare || m < 1) t else "$t fa"
}.getOrDefault("")

@Composable private fun PortalCard(p: PortalView, modifier: Modifier, onEnable: () -> Unit, onSync: () -> Unit, onOpen: () -> Unit, onStop: () -> Unit, onOff: () -> Unit, onForget: () -> Unit) {
    val (tone, state) = when {
        !p.enabled || p.blocked -> Tone.Neutral to (if (p.lastSync.isNotBlank()) "Aggiornato dal PC " + whenText(p.lastSync) else "Ancora nessuna lettura dal PC") + (if (p.blocked) " · verifica Cloudflare" else "")
        p.running -> Tone.Info to p.status.ifBlank { "Lettura in corso…" }
        p.connected -> Tone.Ok to "Sul server · ${if (p.lastSync.isNotBlank()) "aggiornato " + whenText(p.lastSync) else "prima lettura in arrivo"} · ${p.every}"
        else -> Tone.Bad to p.error.ifBlank { "Sul server · serve il tuo accesso" }
    }
    var menu by remember { mutableStateOf(false) }
    CardBox(modifier, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(state, style = MaterialTheme.typography.bodySmall, color = if (tone == Tone.Bad) LocalStatus.current.of(Tone.Bad) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(when { !p.enabled || p.blocked -> "PC ufficio"; p.running -> "In lettura"; p.connected -> "Collegato"; else -> "Da collegare" }, tone)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                !p.enabled -> FilledTonalButton(onEnable) { Icon(Icons.Rounded.Cloud, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Sposta sul server") }
                p.blocked -> OutlinedButton(onOpen) { Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Riprova sul server") }
                p.connected -> OutlinedButton(onSync, enabled = !p.running) { Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(if (p.running) "In lettura" else "Aggiorna ora") }
                else -> Button(onOpen) { Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Accedi") }
            }
            Spacer(Modifier.weight(1f))
            if (p.enabled) Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Altre azioni") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Apri il portale") }, { menu = false; onOpen() })
                    if (p.running) DropdownMenuItem({ Text("Interrompi la lettura") }, { menu = false; onStop() })
                    DropdownMenuItem({ Text("Togli dal server") }, { menu = false; onOff() })
                    DropdownMenuItem({ Text("Scollega", color = LocalStatus.current.of(Tone.Bad)) }, { menu = false; onForget() })
                }
            }
        }
    }
}

private fun whenText(iso: String): String = runCatching {
    val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
    val d = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), java.time.LocalDate.now())
    (if (d == 0L) "oggi" else if (d == 1L) "ieri" else "il " + t.dayOfMonth + "/" + t.monthValue) + " alle " + "%02d:%02d".format(t.hour, t.minute)
}.getOrDefault(iso)

/** Live picture of the portal page in the server's browser: a tap is a click, a vertical drag scrolls, what is typed goes
 *  straight into the portal (nothing is kept: the field is cleared after Invio/Tab and when the view closes). */
@Composable private fun PortalLiveView(vm: AppViewModel, p: PortalView, onClose: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    var url by remember { mutableStateOf("Apertura del portale…") }
    var fastUntil by remember { mutableStateOf(0L) }
    var typed by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun send(i: PortalInput) = scope.launch { vm.portalInput(p.id, i); fastUntil = System.currentTimeMillis() + 2500 }
    // the server draws the portal at this phone's size (its mobile page): readable and easy to tap
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val vw = conf.screenWidthDp.coerceIn(320, 600) - 24; val vh = (conf.screenHeightDp - 300).coerceIn(480, 900)
    LaunchedEffect(p.id) {
        while (true) {
            try { val (bytes, u) = vm.portalView(p.id, vw, vh); BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { img = it.asImageBitmap() }; url = u.ifBlank { url } }
            catch (e: Exception) { url = e.message ?: "Portale non disponibile" }
            delay(if (System.currentTimeMillis() < fastUntil) 250 else 900)
        }
    }
    Dialog({ typed = ""; onClose(false) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.systemBarsPadding().imePadding().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton({ send(PortalInput("back")) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Pagina precedente") }
                    IconButton({ send(PortalInput("reload")) }) { Icon(Icons.Rounded.Refresh, "Ricarica") }
                    IconButton({ typed = ""; onClose(false) }) { Icon(Icons.Rounded.Close, "Chiudi") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.TopCenter) {
                    val b = img
                    if (b == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else Image(b, "Pagina del portale: tocca per cliccare", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)
                            .pointerInput(b) { size = this.size.let { IntSize(it.width, it.height) }
                                detectTapGestures { o -> typed = ""; send(PortalInput("click", (o.x / size.width).toDouble(), (o.y / size.height).toDouble())) } }
                            .pointerInput(b) {
                                var acc = 0f; var start = androidx.compose.ui.geometry.Offset.Zero
                                detectDragGestures(onDragStart = { acc = 0f; start = it }, onDragEnd = {
                                    if (kotlin.math.abs(acc) > 14) send(PortalInput("scroll", (start.x / this.size.width).toDouble(), (start.y / this.size.height).toDouble(), (-acc * 860f / this.size.height).toDouble()))
                                }) { c, d -> c.consume(); acc += d.y }
                            })
                }
                OutlinedTextField(typed, { v ->
                    val last = typed
                    when {
                        v.startsWith(last) -> v.substring(last.length).takeIf { it.isNotEmpty() }?.let { send(PortalInput("text", text = it)) }
                        last.startsWith(v) -> scope.launch { repeat(last.length - v.length) { vm.portalInput(p.id, PortalInput("key", text = "Backspace")) }; fastUntil = System.currentTimeMillis() + 2500 }
                        else -> scope.launch { repeat(last.length) { vm.portalInput(p.id, PortalInput("key", text = "Backspace")) }; if (v.isNotEmpty()) vm.portalInput(p.id, PortalInput("text", text = v)); fastUntil = System.currentTimeMillis() + 2500 }
                    }
                    typed = v
                }, Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true, placeholder = { Text("Tocca un campo nella pagina, poi scrivi qui") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Nascondi il testo" else "Mostra il testo") } })
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ typed = typed.dropLast(1); send(PortalInput("key", text = "Backspace")) }) { Text("⌫") }
                    OutlinedButton({ typed = ""; send(PortalInput("key", text = "Tab")) }) { Text("Tab") }
                    OutlinedButton({ typed = ""; send(PortalInput("key", text = "Enter")) }) { Text("Invio") }
                }
                Text("Quello che scrivi va direttamente nel portale: il server non salva password né codici.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Button({ typed = ""; onClose(true) }, Modifier.fillMaxWidth().padding(vertical = 10.dp)) { Text("Ho fatto l’accesso") }
            }
        }
    }
}
