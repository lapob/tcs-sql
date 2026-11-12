package it.topcash.studio.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Asset
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Deadline
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UAsset
import it.topcash.studio.data.UClient
import it.topcash.studio.data.norm
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

val assetKinds = listOf("PC", "Server", "Stampante", "Router / firewall", "NAS", "Tablet", "Licenza software", "Altro")
private val renStates = listOf("Da contattare", "Contattato", "Da sollecitare", "Rinnovato", "Perso")
private fun renTone(s: String) = when (s) { "Rinnovato" -> Tone.Ok; "Perso" -> Tone.Bad; "Da sollecitare" -> Tone.Due; "Contattato" -> Tone.Accent; "" -> Tone.Neutral; else -> Tone.Info }

// ───────────────────────── PC e sistemi
@Composable fun InventoryScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf("Tutto") }
    var q by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<UAsset?>(null) }; var creating by rememberSaveable { mutableStateOf(false) }
    editing?.let { x -> AssetSheet(vm, a, x.a, x.client) { editing = null } }
    if (creating) AssetSheet(vm, a, null, null) { creating = false }
    val tabs = listOf("Tutto", "In scadenza") + assetKinds.filter { k -> a.assets.any { it.a.kind == k && it.a.active } } + "Dismessi"
    val list = remember(a, tab, q) {
        val n = norm(q.trim())
        when (tab) { "Tutto" -> a.assets.filter { it.a.active }; "In scadenza" -> a.assetsDue; "Dismessi" -> a.assets.filter { !it.a.active }; else -> a.assets.filter { it.a.kind == tab && it.a.active } }
            .filter { n.isEmpty() || it.search.contains(n) }.sortedWith(compareBy({ it.clientName.lowercase() }, { it.a.name.lowercase() }))
    }
    Page(vm, "PC e sistemi", "${a.assets.count { it.a.active }} in uso · ${a.assetsDue.size} garanzie o scadenze vicine", onBack = { nav.popBackStack() },
        fab = { FloatingActionButton({ creating = true }) { Icon(Icons.Rounded.Add, "Aggiungi") } }) {
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, modello, matricola, IP") }
        item(key = "tabs") { ChoiceChips(tabs, tab, { tab = it }, Modifier.padding(vertical = 10.dp)) }
        if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.Computer, if (a.assets.isEmpty()) "Inventario vuoto" else "Nessun elemento", if (a.assets.isEmpty()) "Tocca + per aggiungere PC, stampanti e apparati dei clienti. Le password non si salvano qui." else "Cambia filtro o ricerca.") }
        items(list.size, key = { list[it].a.id }) { i -> AssetCard(list[i], Modifier.padding(bottom = 8.dp).animateItem(), showClient = true) { editing = list[i] } }
    }
}

@Composable fun AssetCard(x: UAsset, modifier: Modifier = Modifier, showClient: Boolean = true, onClick: () -> Unit) {
    CardBox(modifier, onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(x.a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (showClient) x.clientName else null, x.a.kind, x.a.brand.takeIf { it.isNotBlank() }, x.a.serial.takeIf { it.isNotBlank() }, x.a.ip.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when {
                !x.a.active -> Pill("Dismesso", Tone.Neutral)
                x.warDays != null && x.warDays < 0 -> Pill("Garanzia scaduta", Tone.Bad)
                x.expDays != null && x.expDays <= 60 -> Pill("Scade ${Dates.relative(x.expDays)}", if (x.expDays < 0) Tone.Bad else Tone.Due)
                x.warDays != null && x.warDays <= 60 -> Pill("Garanzia ${Dates.relative(x.warDays)}", Tone.Due)
                x.warDays != null -> Pill("In garanzia", Tone.Ok)
                else -> {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AssetSheet(vm: AppViewModel, a: Archive, asset: Asset?, client: UClient?, fixedClient: Boolean = false, onDismiss: () -> Unit) {
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    fun close() = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    val x = asset ?: Asset(clientCode = client?.code.orEmpty())
    var who by remember { mutableStateOf(client) }; var clientQ by remember { mutableStateOf(client?.name.orEmpty()) }; var pickOpen by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(x.kind.ifBlank { "PC" }) }
    val f = remember { listOf("name", "brand", "serial", "location", "ip", "os", "notes").associateWith { k -> mutableStateOf(when (k) { "name" -> x.name; "brand" -> x.brand; "serial" -> x.serial; "location" -> x.location; "ip" -> x.ip; "os" -> x.os; else -> x.notes }) } }
    var purchased by remember { mutableStateOf(Dates.parse(x.purchased)) }; var warranty by remember { mutableStateOf(Dates.parse(x.warrantyTo)) }; var expiry by remember { mutableStateOf(Dates.parse(x.expiry)) }
    var active by remember { mutableStateOf(x.active) }; var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(if (asset == null) "Nuovo elemento" else asset.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Le password non si salvano qui: tienile nel gestore delle password.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (!fixedClient) {
                OutlinedTextField(clientQ, { clientQ = it; who = null; pickOpen = true }, Modifier.fillMaxWidth(), label = { Text("Cliente") }, singleLine = true,
                    supportingText = { if (who == null) Text("Scrivi e scegli dall'elenco") else Text(who?.city.orEmpty()) })
                val n = norm(clientQ.trim()); val matches = remember(n, who) { if (who != null || n.length < 2) emptyList() else a.clients.filter { it.search.contains(n) }.take(6) }
                androidx.compose.animation.AnimatedVisibility(pickOpen && matches.isNotEmpty()) {
                    Column {
                        matches.forEach { c ->
                            TextButton({ who = c; clientQ = c.name; pickOpen = false }, Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface); if (c.city.isNotBlank()) Text(c.city, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { assetKinds.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k) }) } }
            listOf("name" to "Nome o modello", "brand" to "Marca", "serial" to "Matricola / seriale", "location" to "Dove si trova", "ip" to "Indirizzo IP", "os" to "Sistema / versione", "notes" to "Note").forEach { (k, l) ->
                val v = f.getValue(k)
                OutlinedTextField(v.value, { if (it.length <= (if (k == "notes") 1000 else 120)) v.value = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text(l) }, singleLine = k != "notes", minLines = if (k == "notes") 2 else 1,
                    keyboardOptions = KeyboardOptions(keyboardType = if (k == "ip") KeyboardType.Uri else KeyboardType.Text))
            }
            DateRow("Acquisto", purchased) { purchased = it }
            DateRow("Garanzia fino al", warranty) { warranty = it }
            DateRow("Scadenza (licenza, contratto)", expiry) { expiry = it }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("In uso", Modifier.weight(1f)); Switch(active, { active = it }) }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (asset != null) IconButton({ confirm = true }) { Icon(Icons.Rounded.Delete, "Elimina", tint = LocalStatus.current.bad) }
                Spacer(Modifier.weight(1f))
                Button({
                    val c = who ?: client
                    if (c == null) { vm.notify("Scegli il cliente dall'elenco"); return@Button }
                    if (f.getValue("name").value.isBlank()) { vm.notify("Scrivi nome o modello"); return@Button }
                    vm.saveAsset(Asset(x.id, c.code, kind, f.getValue("name").value.trim(), f.getValue("brand").value.trim(), f.getValue("serial").value.trim(), f.getValue("location").value.trim(),
                        f.getValue("ip").value.trim(), f.getValue("os").value.trim(), purchased?.let(Dates::iso).orEmpty(), warranty?.let(Dates::iso).orEmpty(), expiry?.let(Dates::iso).orEmpty(), f.getValue("notes").value.trim(), active))
                    close()
                }) { Text(if (asset == null) "Aggiungi" else "Salva") }
            }
        }
    }
    if (confirm && asset != null) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Togliere ${asset.name}?") }, text = { Text("Sparisce dall'inventario del cliente.") },
        confirmButton = { TextButton({ confirm = false; vm.deleteAsset(asset.id); close() }) { Text("Elimina", color = LocalStatus.current.bad) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Annulla") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DateRow(label: String, value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(if (value == null) "—" else Dates.short(value), style = MaterialTheme.typography.bodyLarge) }
        if (value != null) TextButton({ onChange(null) }) { Text("Togli") }
        OutlinedButton({ pick = true }) { Icon(Icons.Rounded.Event, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (value == null) "Scegli" else "Cambia") }
    }
    if (pick) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (value ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { pick = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; pick = false }) { Text("OK") } },
            dismissButton = { TextButton({ pick = false }) { Text("Annulla") } }) { DatePicker(st) }
    }
}

/** Section in the customer's sheet. */
@Composable fun ClientAssets(vm: AppViewModel, a: Archive, c: UClient) {
    var editing by remember { mutableStateOf<UAsset?>(null) }; var creating by remember { mutableStateOf(false) }
    editing?.let { x -> AssetSheet(vm, a, x.a, c, fixedClient = true) { editing = null } }
    if (creating) AssetSheet(vm, a, null, c, fixedClient = true) { creating = false }
    SectionTitle("PC e sistemi${if (c.assets.isNotEmpty()) " (${c.assets.size})" else ""}") { TextButton({ creating = true }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("Aggiungi", Modifier.padding(start = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } }
    if (c.assets.isEmpty()) Text("Nessun PC, stampante o apparato registrato.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    c.assets.forEach { x -> AssetCard(x, Modifier.padding(bottom = 8.dp), showClient = false) { editing = x } }
}

// ───────────────────────── Rinnovi
@Composable fun RenewalsScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf("Da gestire") }
    var q by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<Deadline?>(null) }
    open?.let { d -> RenewalSheet(vm, a, d, nav) { open = null } }
    val all = remember(a) { a.deadlines(-90, 120).filter { it.key.isNotBlank() } }
    fun st(d: Deadline) = a.state.renewals[d.key]?.status.orEmpty()
    fun isOpen(d: Deadline) = st(d) !in setOf("Rinnovato", "Perso")
    val list = remember(a, tab, q) {
        val n = norm(q.trim())
        all.filter { d -> when (tab) { "Da gestire" -> isOpen(d); "Scadute" -> isOpen(d) && d.days < 0; "Da richiamare" -> isOpen(d) && (Dates.daysFromToday(Dates.parse(a.state.renewals[d.key]?.nextContact)) ?: 1) <= 0; "Rinnovati" -> st(d) == "Rinnovato"; "Persi" -> st(d) == "Perso"; else -> true } }
            .filter { n.isEmpty() || norm(it.clientName + " " + it.detail).contains(n) }
    }
    Page(vm, "Rinnovi", "${all.count(::isOpen)} da gestire · ${all.count { st(it) == "Rinnovato" }} rinnovati", onBack = { nav.popBackStack() }) {
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente o matricola") }
        item(key = "tabs") { ChoiceChips(listOf("Da gestire", "Scadute", "Da richiamare", "Rinnovati", "Persi", "Tutte"), tab, { tab = it }, Modifier.padding(vertical = 10.dp)) }
        if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.CheckCircle, "Niente da fare qui", "Nessuna scadenza in questo elenco.") }
        val shown = list.take(150)
        items(shown.size, key = { shown[it].key }) { i ->
            val d = shown[i]; val m = a.state.renewals[d.key]
            CardBox(Modifier.padding(bottom = 8.dp).animateItem(), onClick = { open = d }, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.clientName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${d.kind} · ${d.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Dates.short(d.date)} · ${Dates.relative(d.days)}${m?.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall,
                            color = if (d.days < 0) LocalStatus.current.bad else if (d.days <= 30) LocalStatus.current.due else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Pill(m?.status?.ifBlank { null } ?: "Nessun esito", renTone(m?.status.orEmpty()))
                }
            }
        }
        if (list.size > shown.size) item(key = "more") { Text("Altre ${list.size - shown.size}: usa la ricerca per trovarle.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RenewalSheet(vm: AppViewModel, a: Archive, d: Deadline, nav: NavHostController, onDismiss: () -> Unit) {
    val sheet = rememberSheet(); val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    fun close(after: () -> Unit = {}) = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); after() }
    val m = a.state.renewals[d.key]
    var status by remember { mutableStateOf(m?.status?.ifBlank { null } ?: "Da contattare") }
    var note by remember { mutableStateOf(m?.note.orEmpty()) }; var next by remember { mutableStateOf(Dates.parse(m?.nextContact)) }
    val c = d.client
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(d.clientName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${d.kind} · ${d.detail} · scade il ${Dates.short(d.date)} (${Dates.relative(d.days)})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                c?.phone?.takeIf { it.isNotBlank() }?.let { tel -> FilledTonalButton({ ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.replace(" ", "")))) }) { Icon(Icons.Rounded.Call, null, Modifier.size(18.dp)); Text("Chiama", Modifier.padding(start = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } }
                FilledTonalButton({
                    val subj = "${d.kind} in scadenza il ${Dates.short(d.date)}"
                    val body = "Buongiorno,\n\nle ricordiamo che il ${Dates.short(d.date)} scade: ${d.kind.lowercase()} (${d.detail}).\nPer rinnovare senza interruzioni può rispondere a questa email o chiamarci: ci pensiamo noi.\n\nCordiali saluti\nTop Cash Service S.r.l."
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(c?.email.orEmpty()) + "?subject=" + Uri.encode(subj) + "&body=" + Uri.encode(body))))
                }) { Icon(Icons.Rounded.Mail, null, Modifier.size(18.dp)); Text("Email", Modifier.padding(start = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if (c != null) OutlinedButton({ close { nav.openClient(c.code) } }) { Text("Scheda cliente") }
            }
            Text("Esito", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { renStates.forEach { s -> FilterChip(status == s, { status = s }, { Text(s) }) } }
            OutlinedTextField(note, { if (it.length <= 300) note = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Nota") }, placeholder = { Text("Es. vuole il preventivo") })
            DateRow("Richiama il", next) { next = it }
            m?.steps?.takeIf { it.isNotEmpty() }?.let { steps ->
                Text("Storia", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                steps.reversed().take(10).forEach { s -> Text("${Dates.short(Dates.parse(s.at.take(10)))} · ${s.status.ifBlank { "Nota" }}${if (s.note.isNotBlank()) " · " + s.note else ""} · ${s.by}", style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Spacer(Modifier.weight(1f))
                Button({ vm.saveRenewal(d.key, status, note.trim(), next?.let(Dates::iso).orEmpty()); close() }) { Text("Salva esito") }
            }
        }
    }
}

// ───────────────────────── Giro di oggi
/** One stop of the day. [rank] orders the work: 0 urgent (urgent job, overdue verification), 1 soon (high priority,
 *  verification within 30 days, journal almost full), 2 normal job, 3 register reporting errors (often solved remotely). */
private class Stop(val c: UClient?, val kind: String, val tone: Tone, val text: String, val tech: String, val rank: Int, val key: String, val open: () -> Unit) { var done = false }
private const val ROUTE_SHOW = 6
private fun urgentLabel(n: Int) = if (n == 1) "1 urgente" else "$n urgenti"
private const val ROUTE_SPLIT = 20
private val routeKinds = listOf("Intervento" to "Interventi", "Verificazione" to "Verificazioni", "DGFE quasi pieno" to "DGFE pieni", "Errore DTR" to "Errori cassa")

/**
 * Giro di oggi: the stops grouped by place, the most urgent places first and "Senza località" last. A big city is
 * split by postcode so each group is a real round (Firenze 50127, 50142…). Every stop is its own list item, so a
 * place with hundreds of stops scrolls as smoothly as one with three. A technician starts on his own stops.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun RouteScreen(vm: AppViewModel, a: Archive, nav: NavHostController, onOpenIntervention: (String) -> Unit) {
    val ctx = LocalContext.current
    val me by vm.me.collectAsStateWithLifecycle()
    val stops = remember(a) {
        buildList {
            a.openInterventions.forEach { i ->
                val r = when (i.i.priority) { "Urgente" -> 0; "Alta" -> 1; else -> 2 }
                add(Stop(i.client, "Intervento", when (r) { 0 -> Tone.Bad; 1 -> Tone.Due; else -> Tone.Info }, i.i.title.ifBlank { "Intervento" }, i.i.technician, r, "i" + i.i.id) { onOpenIntervention(i.i.id) })
            }
            // a verification overdue for over a year waits for the office's decision (Macchine da controllare), not in the round
            a.machines.filter { it.m.active && !it.stale && it.verDays != null && it.verDays <= 30 }.forEach { m ->
                val late = (m.verDays ?: 0) < 0
                add(Stop(m.client, "Verificazione", if (late) Tone.Bad else Tone.Due, "${m.m.model} · ${m.m.serial} · ${Dates.relative(m.verDays)}", "", if (late) 0 else 1, "m" + m.m.id) { m.client?.let { nav.openClient(it.code) } })
            }
            a.italLow.forEach { r -> add(Stop(r.client, "DGFE quasi pieno", Tone.Due, "${r.r.serial} · ${r.d?.dgfeFree}% libero", "", 1, "it" + r.r.serial) { r.client?.let { nav.openClient(it.code) } }) }
            a.rchLow.forEach { r -> add(Stop(r.client, "DGFE quasi pieno", Tone.Due, "${r.r.serial} · ${r.r.dgfeFree}% libero", "", 1, "rc" + r.r.serial) { r.client?.let { nav.openClient(it.code) } }) }
            a.dtrErrors.forEach { r -> add(Stop(r.client, "Errore DTR", Tone.Bad, r.r.serial, "", 3, "d" + r.r.serial) { r.client?.let { nav.openClient(it.code) } }) }
        }.onEach { it.done = a.routeDone(it.key) }
    }
    val techs = remember(stops) { stops.map { it.tech }.filter { it.isNotBlank() }.distinct().sorted() }
    // a technician opens the page on his own stops (name match), everyone else on the whole team
    val mine = remember(me, techs) { me?.takeIf { it.role == "tecnico" }?.name?.trim()?.lowercase()?.takeIf { it.length >= 3 }?.let { n -> techs.firstOrNull { t -> val x = t.trim().lowercase(); x.length >= 3 && (n.contains(x) || x.contains(n)) } } }
    var tech by rememberSaveable(mine) { mutableStateOf(mine ?: "Tutti") }
    var kind by rememberSaveable { mutableStateOf("Tutto") }
    var open by remember { mutableStateOf(setOf<String>()) }
    val byTech = remember(stops, tech) { stops.filter { tech == "Tutti" || it.tech == tech } }
    val kinds = remember(byTech) { routeKinds.mapNotNull { (k, l) -> byTech.count { it.kind == k }.takeIf { it > 0 }?.let { Triple(k, l, it) } } }
    if (kind != "Tutto" && kinds.none { it.second == kind }) kind = "Tutto"
    val shown = remember(byTech, kind) { val k = kinds.firstOrNull { it.second == kind }?.first; byTech.filter { k == null || it.kind == k } }
    val groups = remember(shown) {
        fun city(s: Stop) = (s.c?.city ?: s.c?.mastro?.city).orEmpty().trim().ifBlank { "Senza località" }
        val big = shown.groupingBy(::city).eachCount().filter { it.value > ROUTE_SPLIT && it.key != "Senza località" }.keys
        shown.groupBy { s -> val c = city(s); val cap = s.c?.mastro?.cap.orEmpty().trim(); if (c in big && cap.isNotBlank()) "$c $cap" else c }
            // done stops go to the bottom of their place, places all done after the others
            .mapValues { (_, v) -> v.sortedWith(compareBy({ it.done }, { it.rank }, { it.c?.name.orEmpty() })) }
            .entries.sortedWith(compareBy({ it.value.all { s -> s.done } }, { it.key == "Senza località" }, { it.value.first().rank }, { -it.value.count { s -> s.rank <= 1 } }, { -it.value.size }, { it.key }))
    }
    val urgent = shown.count { it.rank == 0 && !it.done }
    val doneCount = shown.count { it.done }
    Page(vm, "Giro di oggi", "${shown.size} tappe in ${groups.size} zone" + if (urgent > 0) " · " + urgentLabel(urgent) else "", onBack = { nav.popBackStack() }) {
        item(key = "pending") { PendingBanner(vm) }
        if (shown.isNotEmpty()) item(key = "progress") { RouteProgress(doneCount, shown.size) }
        if (techs.isNotEmpty()) item(key = "tech") { ChoiceChips(listOf("Tutti") + techs, tech, { tech = it }, Modifier.padding(bottom = 6.dp)) }
        if (kinds.size > 1) item(key = "kind") { ChoiceChips(listOf("Tutto") + kinds.map { it.second }, kind, { kind = it }, Modifier.padding(bottom = 10.dp)) }
        if (groups.isEmpty()) item(key = "none") { Empty(Icons.Rounded.Route, "Giornata libera", if (tech == "Tutti") "Nessun intervento aperto né verificazione imminente." else "Nessuna tappa per $tech.") }
        groups.forEach { (place, ss) ->
            stickyHeader(key = "g$place") {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(place, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val u = ss.count { it.rank == 0 && !it.done }; val d = ss.count { it.done }
                        Text(if (d == ss.size) "Tutto fatto" else (if (ss.size == 1) "1 tappa" else "${ss.size} tappe") + (if (d > 0) " · $d fatte" else "") + if (u > 0) " · " + urgentLabel(u) else "", style = MaterialTheme.typography.bodySmall,
                            color = if (u > 0) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // the route follows the order of the list: the most urgent addresses first (Maps takes up to 10)
                    val places = ss.filter { !it.done }.mapNotNull { it.c?.fullAddress?.takeIf { s -> s.isNotBlank() } }.distinct().take(10)
                    if (places.isNotEmpty()) FilledTonalButton({
                        val dest = places.last(); val way = places.dropLast(1)
                        val url = "https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=" + Uri.encode(dest) + if (way.isNotEmpty()) "&waypoints=" + Uri.encode(way.joinToString("|")) else ""
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }) { Icon(Icons.Rounded.Directions, null, Modifier.size(18.dp)); Text("Percorso", Modifier.padding(start = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
            val expanded = place in open
            val rows = if (expanded) ss else ss.take(ROUTE_SHOW)
            items(rows.size, key = { "s$place" + rows[it].key }) { idx -> val s = rows[idx]
                val fade by androidx.compose.animation.core.animateFloatAsState(if (s.done) 0.55f else 1f, androidx.compose.animation.core.tween(260), label = "done")
                CardBox(Modifier.padding(bottom = 8.dp).animateItem(), onClick = s.open, padding = PaddingValues(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RouteCheck(s.done) { vm.markRoute(s.key, !s.done) }
                        Column(Modifier.weight(1f).padding(vertical = 4.dp).graphicsLayer { alpha = fade }) {
                            Text(s.c?.name ?: "Cliente da abbinare", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(s.text, s.c?.fullAddress.orEmpty(), s.tech).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Pill(if (s.done) "Fatto" else s.kind, if (s.done) Tone.Ok else s.tone, Modifier.padding(start = 8.dp))
                        s.c?.phone?.takeIf { it.isNotBlank() }?.let { tel -> IconButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.replace(" ", "")))) } }) { Icon(Icons.Rounded.Call, "Chiama ${s.c.name}") } }
                            ?: Spacer(Modifier.width(10.dp))
                    }
                }
            }
            if (ss.size > ROUTE_SHOW) item(key = "more$place") {
                TextButton({ open = if (expanded) open - place else open + place }, Modifier.padding(bottom = 6.dp).animateItem()) {
                    Text(if (expanded) "Mostra meno" else "Mostra altre ${ss.size - ROUTE_SHOW}")
                }
            }
        }
    }
}

/** Tick of a stop: one tap = done (the whole team sees it), again = back to do. */
@Composable private fun RouteCheck(done: Boolean, onToggle: () -> Unit) {
    val scale by androidx.compose.animation.core.animateFloatAsState(if (done) 1f else 0.86f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "tick")
    val tint by androidx.compose.animation.animateColorAsState(if (done) LocalStatus.current.ok else MaterialTheme.colorScheme.outline, label = "tick-color")
    IconButton(onToggle) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (done) "Fatto: tocca per rimetterla da fare" else "Segna come fatta",
            Modifier.size(26.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = tint)
    }
}

/** "4 di 11 fatte": a thin bar that fills as the day goes on. */
@Composable private fun RouteProgress(done: Int, total: Int) {
    val p by androidx.compose.animation.core.animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, androidx.compose.animation.core.tween(420), label = "progress")
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done == total) "Giro completato" else "$done di $total fatte", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                color = if (done == total) LocalStatus.current.ok else MaterialTheme.colorScheme.onSurface)
            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(androidx.compose.foundation.shape.CircleShape),
            color = LocalStatus.current.ok, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {}, gapSize = 0.dp)
    }
}
