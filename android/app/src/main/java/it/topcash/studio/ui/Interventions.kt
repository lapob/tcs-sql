package it.topcash.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val itabs = listOf("Da fare", "I miei", "Oggi e in ritardo", "Chiusi", "Storico Mastro", "Tutti")

@Composable fun InterventionsScreen(vm: AppViewModel, a: Archive, onOpen: (String) -> Unit, onNew: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Da fare") }
    var q by rememberSaveable { mutableStateOf("") }
    var tech by rememberSaveable { mutableStateOf("") }
    val state = rememberLazyListState()
    val expanded by remember { derivedStateOf { state.firstVisibleItemIndex < 2 } }
    val me by vm.me.collectAsStateWithLifecycle()
    var roleTab by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(me) { if (!roleTab && me?.role == "tecnico") { tab = "I miei"; roleTab = true } }
    val list = remember(a, tab, q, tech, me) {
        val n = norm(q.trim())
        a.interventions.filter { x ->
            when (tab) { "Da fare" -> x.i.status != "Chiuso"
                "I miei" -> x.i.status != "Chiuso" && x.i.source == "app" && me != null && norm(x.i.technician) == norm(me!!.name)
                "Oggi e in ritardo" -> x.i.status != "Chiuso" && x.date?.let { !it.isAfter(LocalDate.now()) } == true "Chiusi" -> x.i.status == "Chiuso" && x.i.source == "app"; "Storico Mastro" -> x.i.source == "mastro"; else -> true } &&
                (n.isEmpty() || norm(x.clientName + " " + x.i.title + " " + x.i.work + " " + x.i.technician).contains(n)) && (tech.isEmpty() || x.i.technician == tech)
        }.let { l -> if (tab == "Da fare" || tab == "I miei" || tab == "Oggi e in ritardo") l.sortedWith(compareBy({ Catalog.priorities.indexOf(it.i.priority) * -1 }, { it.date ?: LocalDate.MAX })) else l }
    }
    Page(vm, "Interventi", "${a.openInterventions.size} da completare", state = state,
        fab = { ExtendedFloatingActionButton(onClick = onNew, expanded = expanded, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Nuovo intervento") }) }) {
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, lavoro, tecnico", Modifier.padding(top = 2.dp)) }
        item(key = "tabs") { ChoiceChips(itabs, tab, { tab = it }, Modifier.padding(top = 10.dp)) }
        if (a.technicians.isNotEmpty()) item(key = "techs") {
            LazyRow(Modifier.padding(vertical = 10.dp).bleed(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                item { FilterChip(tech.isEmpty(), { tech = "" }, label = { Text("Tutti i tecnici") }) }
                items(a.technicians) { t -> FilterChip(tech == t, { tech = if (tech == t) "" else t }, label = { Text(t) }) }
            }
        }
        if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.Build, "Nessun intervento", if (tab == "Da fare") "Non ci sono interventi da fare. Ottimo!" else "Modifica i filtri o crea un nuovo intervento.") }
        items(list.size.coerceAtMost(800), key = { list[it].i.id }) { i -> InterventionRow(list[i], a, Modifier.padding(bottom = 8.dp).animateItem(), vm = vm) { onOpen(list[i].i.id) } }
    }
}

@Composable fun InterventionRow(x: UIntervention, a: Archive, modifier: Modifier = Modifier, showClient: Boolean = true, vm: AppViewModel? = null, onClick: () -> Unit) {
    val tone = when (x.i.priority) { "Urgente" -> Tone.Bad; "Alta" -> Tone.Due; else -> Archive.toneOf(x.i.status) }
    val stripe = LocalStatus.current.of(tone)
    CardBox(modifier, onClick = onClick, padding = PaddingValues(0.dp)) {
        Row(Modifier.heightIn(min = 72.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(stripe.copy(alpha = if (x.i.status == "Chiuso") 0.25f else 0.9f)))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(x.i.title.ifBlank { "Intervento" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Pill(x.i.status, Archive.toneOf(x.i.status))
                }
                if (showClient) Text(x.clientName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Text(listOf(x.date?.let { Dates.short(it) + " · " + Dates.relative(Dates.daysFromToday(it)) }, x.i.technician.takeIf { it.isNotBlank() }, if (x.i.priority != "Normale") x.i.priority else null,
                    if (x.i.source == "mastro") "storico Mastro" else null, x.i.reportNo.takeIf { x.i.signedAt.isNotBlank() }?.let { "firmato n. $it" }).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (vm != null && x.i.source == "app" && x.i.signedAt.isBlank() && x.i.status != "Chiuso") QuickActions(vm, x.i, onSign = onClick)
            }
        }
    }
}

/** Everyday actions one tap away: sign (opens the intervention), print to take along, close, postpone. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun QuickActions(vm: AppViewModel, i: Intervention, onSign: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var later by remember { mutableStateOf(false) }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.AssistChip(onSign, { Text("Firma") }, leadingIcon = { Icon(Icons.Rounded.Draw, null, Modifier.size(16.dp)) })
        androidx.compose.material3.AssistChip({ vm.printReport(i, ctx) }, { Text("Stampa") }, leadingIcon = { Icon(Icons.Rounded.Print, null, Modifier.size(16.dp)) })
        androidx.compose.material3.AssistChip({ vm.saveIntervention(i.copy(status = "Chiuso"), quiet = true) { vm.notify("Intervento chiuso") } }, { Text("Chiudi") },
            leadingIcon = { Icon(Icons.Rounded.Verified, null, Modifier.size(16.dp)) })
        Box {
            androidx.compose.material3.AssistChip({ later = true }, { Text("Posticipa") }, leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(16.dp)) })
            androidx.compose.material3.DropdownMenu(later, { later = false }, shape = RoundedCornerShape(18.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                val today = LocalDate.now()
                listOf("Domani" to today.plusDays(1), "Dopodomani" to today.plusDays(2), "Lunedì prossimo" to today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)),
                    "Tra una settimana" to today.plusWeeks(1), "Tra due settimane" to today.plusWeeks(2), "Tra un mese" to today.plusMonths(1)).forEach { (l, d) ->
                    DropdownMenuItem({ Column { Text(l); Text(Dates.short(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, {
                        later = false
                        vm.saveIntervention(i.copy(date = d.toString(), status = if (i.status == "Aperto") "Pianificato" else i.status), quiet = true) { vm.notify("Intervento spostato a ${Dates.short(d)}") }
                    })
                }
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
    var afterSign by remember { mutableStateOf<Intervention?>(null) }
    var mailDialog by remember { mutableStateOf(false) }
    val mailOn = a.state.features.mail
    val clientEmails = (a.byCode[existing?.client?.code ?: req.clientCode.orEmpty()]?.emails.orEmpty())
    val sheet = rememberSheet()
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
            Text(if (signed) "Rapportino n. ${existing.i.reportNo}" else if (ro) "Intervento dallo storico Mastro" else if (existing != null) "Modifica intervento" else "Nuovo intervento", style = MaterialTheme.typography.headlineSmall)
            if (signed) existing.i.let { e ->
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
                OutlinedTextField(clientQuery, { clientQuery = it; clientCode = ""; menu = true }, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable), enabled = !ro && existing == null || clientCode.isEmpty(),
                    label = { Text("Cliente") }, singleLine = true, shape = MaterialTheme.shapes.medium, isError = clientQuery.isNotBlank() && clientCode.isEmpty(),
                    supportingText = { if (clientQuery.isNotBlank() && clientCode.isEmpty()) Text("Scegli il cliente dall'elenco") })
                ExposedDropdownMenu(menu && suggestions.isNotEmpty() && !ro, { menu = false }, shape = RoundedCornerShape(18.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
            val st = LocalStatus.current
            SegmentedPicker(Catalog.priorities, Catalog.priorities.indexOf(priority), { priority = Catalog.priorities[it] }, Modifier.padding(top = 6.dp), enabled = !ro,
                accent = { when (Catalog.priorities[it]) { "Urgente" -> st.bad; "Alta" -> st.due; else -> null } })
            Gap(14.dp)
            // date and assignee: two fields of the same shape, label on the border, aligned on one line
            // on phones the two fields stack at full width: the date and the name are never cut
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 400.dp
                val dateField: @Composable (Modifier) -> Unit = { m -> Box(m) {
                    OutlinedTextField(Dates.short(Dates.parse(date)), {}, Modifier.fillMaxWidth(), readOnly = true, enabled = !ro, singleLine = true,
                        label = { Text("Data") }, leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(20.dp)) }, shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp))
                    Box(Modifier.matchParentSize().padding(top = 8.dp).clip(MaterialTheme.shapes.medium).clickable(enabled = !ro) { pickDate = true })
                }
                }
                if (narrow) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    dateField(Modifier.fillMaxWidth())
                    SelectField("Assegna a", tech, teamOptions(a.state.team, tech), { tech = it }, Modifier.fillMaxWidth(), enabled = !ro)
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    dateField(Modifier.weight(1f))
                    SelectField("Assegna a", tech, teamOptions(a.state.team, tech), { tech = it }, Modifier.weight(1f), enabled = !ro)
                }
            }
            val machines = a.byCode[clientCode]?.machines.orEmpty()
            if (machines.isNotEmpty()) {
                Gap(10.dp)
                Text("Macchina", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(machine.isEmpty(), { if (!ro) machine = "" }, label = { Text("Nessuna") }, enabled = !ro)
                    machines.forEach { m -> FilterChip(machine == m.m.id, { if (!ro) machine = m.m.id }, label = { Text("${m.m.model} · ${m.m.serial}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, enabled = !ro) }
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
                if (signed && me?.role != "tecnico") TextButton({ vm.unsignIntervention(existing.i.id); close() }) { Text("Annulla firma", color = MaterialTheme.colorScheme.error) }
                if (existing != null && !ro && me?.role != "tecnico") TextButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error); Text(" Elimina", color = MaterialTheme.colorScheme.error) }
                if (clientCode.isNotEmpty()) TextButton({ onOpenClient(clientCode) }) { Text("Apri cliente") }
                Box(Modifier.weight(1f))
                if (signed) OutlinedButton({ vm.shareReport(existing.i, ctx) }, shape = MaterialTheme.shapes.medium) { Icon(Icons.Rounded.Share, null); Text("  Condividi") }
                else if (ro) Button(close) { Text("Chiudi") }
                else Button({
                    saving = true
                    vm.saveIntervention(current()) { saving = false; close() }.invokeOnCompletion { saving = false }
                }, enabled = !saving && clientCode.isNotEmpty() && title.isNotBlank(), shape = MaterialTheme.shapes.medium) { Text(if (saving) "Salvataggio…" else "Salva") }
            }
            if (signed) existing.i.let { e ->
                if (e.sentAt.isNotBlank()) Text("PDF inviato a ${e.sentTo} il ${Dates.short(Dates.parse(e.sentAt.take(10)))}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.FilledTonalButton({ vm.printReport(e, ctx) }, Modifier.weight(1f).height(52.dp), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Rounded.Print, null); Text("  Stampa") }
                    if (mailOn) Button({ mailDialog = true }, Modifier.weight(1f).height(52.dp), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Rounded.Email, null); Text("  Email cliente") }
                }
            }
            if (!ro && existing != null) androidx.compose.material3.FilledTonalButton({
                saving = true
                vm.saveIntervention(current(), quiet = true) { r -> vm.printReport(r, ctx) }.invokeOnCompletion { saving = false }
            }, Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp), enabled = !saving && clientCode.isNotEmpty() && title.isNotBlank(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.Print, null); Text("  Stampa da portare dal cliente")
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
    signing?.let { r -> SignatureSheet(vm, r, a.byCode[r.clientCode]?.name ?: r.clientName, a.byCode[r.clientCode]?.emails.orEmpty(), mailOn, onDismiss = { signing = null }) { s -> signing = null; afterSign = s } }
    afterSign?.let { s ->
        AlertDialog(onDismissRequest = { afterSign = null; close() }, icon = { Icon(Icons.Rounded.Verified, null) }, title = { Text("Rapportino n. ${s.reportNo} firmato") },
            text = { Text("Puoi stamparlo subito o condividerlo (WhatsApp, email, Drive…).") },
            confirmButton = { Button({ vm.printReport(s, ctx); afterSign = null; close() }) { Icon(Icons.Rounded.Print, null); Text("  Stampa") } },
            dismissButton = { Row {
                TextButton({ afterSign = null; close() }) { Text("Chiudi") }
                TextButton({ vm.shareReport(s, ctx); afterSign = null; close() }) { Text("Condividi") } } })
    }
    if (mailDialog && existing != null) {
        var to by remember { mutableStateOf(existing.i.sentTo.ifBlank { clientEmails.firstOrNull().orEmpty() }) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!busy) mailDialog = false }, title = { Text("Invia il rapportino n. ${existing.i.reportNo}") },
            text = { Column {
                Text("Il PDF firmato parte come allegato dall'indirizzo dell'azienda.", style = MaterialTheme.typography.bodyMedium)
                Gap(10.dp)
                OutlinedTextField(to, { v -> to = v.take(400) }, Modifier.fillMaxWidth(), label = { Text("Email del cliente") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
                if (clientEmails.size > 1) Text("Altri indirizzi: " + clientEmails.drop(1).joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            } },
            confirmButton = { Button({ busy = true; vm.sendReport(existing.i.id, to.trim()) { ok -> busy = false; if (ok) mailDialog = false } }, enabled = !busy && to.contains('@')) { Text(if (busy) "Invio…" else "Invia") } },
            dismissButton = { TextButton({ mailDialog = false }, enabled = !busy) { Text("Annulla") } })
    }
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (Dates.parse(date) ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = { TextButton({ st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; pickDate = false }) { Text("OK") } }) { DatePicker(st) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Eliminare l'intervento?") }, text = { Text("L'operazione non si può annullare.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteIntervention(existing!!.i.id); close() }) { Text("Elimina", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Annulla") } })
}
