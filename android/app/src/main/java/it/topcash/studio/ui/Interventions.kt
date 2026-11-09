package it.topcash.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Catalog
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Intervention
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UIntervention
import it.topcash.studio.data.norm
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val itabs = listOf("Da fare", "Chiusi", "Storico Mastro", "Tutti")

@Composable fun InterventionsScreen(vm: AppViewModel, a: Archive, onOpen: (String) -> Unit, onNew: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Da fare") }
    var q by rememberSaveable { mutableStateOf("") }
    var tech by rememberSaveable { mutableStateOf("") }
    val state = rememberLazyListState()
    val expanded by remember { derivedStateOf { state.firstVisibleItemIndex < 2 } }
    val list = remember(a, tab, q, tech) {
        val n = norm(q.trim())
        a.interventions.filter { x ->
            when (tab) { "Da fare" -> x.i.status != "Chiuso"; "Chiusi" -> x.i.status == "Chiuso" && x.i.source == "app"; "Storico Mastro" -> x.i.source == "mastro"; else -> true } &&
                (n.isEmpty() || norm(x.clientName + " " + x.i.title + " " + x.i.work + " " + x.i.technician).contains(n)) && (tech.isEmpty() || x.i.technician == tech)
        }.let { l -> if (tab == "Da fare") l.sortedWith(compareBy({ Catalog.priorities.indexOf(it.i.priority) * -1 }, { it.date ?: LocalDate.MAX })) else l }
    }
    Page(vm, "Interventi", "${a.openInterventions.size} da completare", state = state,
        fab = { ExtendedFloatingActionButton(onClick = onNew, expanded = expanded, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Nuovo intervento") }) }) {
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, lavoro, tecnico", Modifier.padding(top = 2.dp)) }
        item(key = "tabs") { ChoiceChips(itabs, tab, { tab = it }, Modifier.padding(top = 10.dp)) }
        if (a.technicians.isNotEmpty()) item(key = "techs") {
            LazyRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(tech.isEmpty(), { tech = "" }, label = { Text("Tutti i tecnici") }) }
                items(a.technicians) { t -> FilterChip(tech == t, { tech = if (tech == t) "" else t }, label = { Text(t) }) }
            }
        }
        if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.Build, "Nessun intervento", if (tab == "Da fare") "Non ci sono interventi da fare. Ottimo!" else "Modifica i filtri o crea un nuovo intervento.") }
        items(list.size.coerceAtMost(800), key = { list[it].i.id }) { i -> InterventionRow(list[i], a, Modifier.padding(bottom = 8.dp).animateItem()) { onOpen(list[i].i.id) } }
    }
}

@Composable fun InterventionRow(x: UIntervention, a: Archive, modifier: Modifier = Modifier, showClient: Boolean = true, onClick: () -> Unit) {
    val tone = when (x.i.priority) { "Urgente" -> Tone.Bad; "Alta" -> Tone.Due; else -> Archive.toneOf(x.i.status) }
    val stripe = LocalStatus.current.of(tone)
    CardBox(modifier, onClick = onClick, padding = PaddingValues(0.dp)) {
        Row(Modifier.heightIn(min = 72.dp)) {
            Box(Modifier.width(5.dp).height(88.dp).background(stripe.copy(alpha = if (x.i.status == "Chiuso") 0.25f else 0.9f)))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(x.i.title.ifBlank { "Intervento" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Pill(x.i.status, Archive.toneOf(x.i.status))
                }
                if (showClient) Text(x.clientName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Text(listOf(x.date?.let { Dates.short(it) + " · " + Dates.relative(Dates.daysFromToday(it)) }, x.i.technician.takeIf { it.isNotBlank() }, if (x.i.priority != "Normale") x.i.priority else null,
                    if (x.i.source == "mastro") "storico Mastro" else null, x.i.reportNo.takeIf { x.i.signedAt.isNotBlank() }?.let { "firmato n. $it" }).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Bottom sheet to create / edit an intervention. Mastro history opens read-only. */
@Composable fun InterventionEditor(vm: AppViewModel, a: Archive, req: EditorRequest, onDismiss: () -> Unit, onOpenClient: (String) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    val existing = req.interventionId?.let { id -> a.interventions.firstOrNull { it.i.id == id } }
    val signed = existing?.i?.signedAt?.isNotBlank() == true
    val ro = existing?.i?.source == "mastro" || signed
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var signing by remember { mutableStateOf<Intervention?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var clientCode by remember { mutableStateOf(existing?.client?.code ?: req.clientCode.orEmpty()) }
    var clientQuery by remember { mutableStateOf(a.byCode[clientCode]?.name.orEmpty()) }
    var title by remember { mutableStateOf(existing?.i?.title.orEmpty()) }
    var work by remember { mutableStateOf(existing?.i?.work.orEmpty()) }
    var status by remember { mutableStateOf(existing?.i?.status ?: "Aperto") }
    var priority by remember { mutableStateOf(existing?.i?.priority ?: "Normale") }
    var tech by remember { mutableStateOf(existing?.i?.technician ?: me?.name.orEmpty()) }
    var date by remember { mutableStateOf(existing?.i?.date?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()) }
    var machine by remember { mutableStateOf(existing?.i?.machine.orEmpty()) }
    var parts by remember { mutableStateOf(existing?.i?.parts.orEmpty()) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    val current = { Intervention(id = existing?.i?.id.orEmpty(), clientCode = clientCode, clientName = clientQuery, date = date, machine = machine, title = title.trim(), work = work,
        status = status, technician = tech.trim(), priority = priority, total = existing?.i?.total.orEmpty(), parts = parts, hours = existing?.i?.hours.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(if (signed) "Rapportino n. ${existing!!.i.reportNo}" else if (ro) "Intervento dallo storico Mastro" else if (existing != null) "Modifica intervento" else "Nuovo intervento", style = MaterialTheme.typography.headlineSmall)
            if (signed) existing!!.i.let { e ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("  Firmato da ${e.signer} il ${Dates.short(Dates.parse(e.signedAt.take(10)))}${if (e.hours.isNotBlank()) " · " + e.hours.replace('.', ',') + " h" else ""}. Non è più modificabile.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Gap(14.dp)
            // client picker with live suggestions
            var menu by remember { mutableStateOf(false) }
            val suggestions = remember(clientQuery) { val n = norm(clientQuery); if (n.length < 2) emptyList() else a.clients.filter { it.search.contains(n) }.take(8) }
            ExposedDropdownMenuBox(menu && suggestions.isNotEmpty() && !ro, { menu = it }) {
                OutlinedTextField(clientQuery, { clientQuery = it; clientCode = ""; menu = true }, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable), enabled = !ro && existing == null || clientCode.isEmpty(),
                    label = { Text("Cliente") }, singleLine = true, shape = MaterialTheme.shapes.medium, isError = clientQuery.isNotBlank() && clientCode.isEmpty(),
                    supportingText = { if (clientQuery.isNotBlank() && clientCode.isEmpty()) Text("Scegli il cliente dall'elenco") })
                ExposedDropdownMenu(menu && suggestions.isNotEmpty() && !ro, { menu = false }) {
                    suggestions.forEach { c -> DropdownMenuItem(text = { Column { Text(c.name, maxLines = 1); Text(listOf(c.city, c.code.trimStart('0')).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        onClick = { clientCode = c.code; clientQuery = c.name; menu = false; machine = "" }) }
                }
            }
            OutlinedTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth(), enabled = !ro, label = { Text("Titolo") }, placeholder = { Text("es. Sostituzione stampante, verificazione…") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            Gap(14.dp)
            Text("Stato", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Catalog.interventionStates.forEach { s -> FilterChip(status == s, { if (!ro) status = s }, label = { Text(s) }, enabled = !ro) } }
            Gap(8.dp)
            Text("Priorità", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Catalog.priorities.forEachIndexed { i, p -> SegmentedButton(priority == p, { if (!ro) priority = p }, SegmentedButtonDefaults.itemShape(i, Catalog.priorities.size), enabled = !ro, label = { Text(p, maxLines = 1) }) }
            }
            Gap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ pickDate = true }, Modifier.weight(1f).height(56.dp), enabled = !ro, shape = MaterialTheme.shapes.medium) { Icon(Icons.Rounded.CalendarMonth, null); Text("  " + Dates.short(Dates.parse(date))) }
                OutlinedTextField(tech, { tech = it.take(80) }, Modifier.weight(1f), enabled = !ro, label = { Text("Tecnico") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            }
            val machines = a.byCode[clientCode]?.machines.orEmpty()
            if (machines.isNotEmpty()) {
                Gap(10.dp)
                Text("Macchina", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(machine.isEmpty(), { if (!ro) machine = "" }, label = { Text("Nessuna") }, enabled = !ro)
                    machines.forEach { m -> FilterChip(machine == m.m.id, { if (!ro) machine = m.m.id }, label = { Text("${m.m.model} · ${m.m.serial}", maxLines = 1) }, enabled = !ro) }
                }
            }
            Gap(10.dp)
            PartsEditor(a, parts, tech, ro) { parts = it }
            Gap(10.dp)
            OutlinedTextField(work, { work = it.take(20000) }, Modifier.fillMaxWidth(), enabled = !ro, label = { Text("Lavoro svolto / note") }, minLines = 4, shape = MaterialTheme.shapes.medium)
            existing?.i?.let { e -> Text(if (ro) "Importato da MastroSQL${if (e.total.isNotBlank() && e.total != "0") " · totale " + e.total + " €" else ""}" else "Creato da ${e.createdBy.ifBlank { "—" }}${if (e.updatedBy.isNotBlank() && e.updated != e.created) " · modificato da " + e.updatedBy else ""}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            Gap(16.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (signed && me?.role != "tecnico") TextButton({ vm.unsignIntervention(existing!!.i.id); close() }) { Text("Annulla firma", color = MaterialTheme.colorScheme.error) }
                if (existing != null && !ro && me?.role != "tecnico") TextButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error); Text(" Elimina", color = MaterialTheme.colorScheme.error) }
                if (clientCode.isNotEmpty()) TextButton({ onOpenClient(clientCode) }) { Text("Apri cliente") }
                Box(Modifier.weight(1f))
                if (signed) Button({ vm.shareReport(existing!!.i, ctx) }, shape = MaterialTheme.shapes.medium) { Icon(Icons.Rounded.Share, null); Text("  Invia PDF") }
                else if (ro) Button(close) { Text("Chiudi") }
                else Button({
                    saving = true
                    vm.saveIntervention(current()) { saving = false; close() }.invokeOnCompletion { saving = false }
                }, enabled = !saving && clientCode.isNotEmpty() && title.isNotBlank(), shape = MaterialTheme.shapes.medium) { Text(if (saving) "Salvataggio…" else "Salva") }
            }
            // the customer signs on the spot: the current edits are saved first, then the pad opens
            if (!ro) OutlinedButton({
                saving = true
                vm.saveIntervention(current(), quiet = true) { r -> signing = r }.invokeOnCompletion { saving = false }
            }, Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp), enabled = !saving && clientCode.isNotEmpty() && title.isNotBlank(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.Draw, null); Text("  Fai firmare il rapportino al cliente")
            }
        }
    }
    signing?.let { r -> SignatureSheet(vm, r, a.byCode[r.clientCode]?.name ?: r.clientName, onDismiss = { signing = null }) { s -> signing = null; close(); vm.shareReport(s, ctx) } }
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (Dates.parse(date) ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = { TextButton({ st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; pickDate = false }) { Text("OK") } }) { DatePicker(st) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Eliminare l'intervento?") }, text = { Text("L'operazione non si può annullare.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteIntervention(existing!!.i.id); close() }) { Text("Elimina", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Annulla") } })
}
