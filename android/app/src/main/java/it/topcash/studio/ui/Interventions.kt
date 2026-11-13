package it.topcash.studio.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Spacer
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

private val itabs = listOf("Da fare", "I miei", "Oggi e in ritardo", "Chiusi", "Tutti", "Verifiche")

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
        (if (tab == "Verifiche") a.verifications else a.interventions).filter { x ->
            when (tab) { "Da fare" -> x.i.status != "Chiuso"
                "I miei" -> x.i.status != "Chiuso" && x.i.source == "app" && me != null && norm(x.i.technician) == norm(me!!.techName)
                "Oggi e in ritardo" -> x.i.status != "Chiuso" && x.date?.let { !it.isAfter(LocalDate.now()) } == true "Chiusi" -> x.i.status == "Chiuso"; else -> true } &&
                (n.isEmpty() || norm(x.clientName + " " + x.i.title + " " + x.i.work + " " + x.i.technician).contains(n)) && (tech.isEmpty() || x.i.technician == tech)
        }.let { l -> if (tab == "Da fare" || tab == "I miei" || tab == "Oggi e in ritardo") l.sortedWith(compareBy({ Catalog.priorities.indexOf(it.i.priority) * -1 }, { it.date ?: LocalDate.MAX })) else l }
    }
    Page(vm, "Interventi", "${a.openInterventions.size} da completare", state = state,
        fab = { ExtendedFloatingActionButton(onClick = onNew, expanded = expanded, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Nuovo intervento") }) }) {
        item(key = "pending") { PendingBanner(vm) }
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, lavoro, tecnico", Modifier.padding(top = 2.dp)) }
        item(key = "tabs") { ChoiceChips(itabs, tab, { tab = it }, Modifier.padding(top = 10.dp)) }
        if (a.technicians.isNotEmpty()) item(key = "techs") {
            LazyRow(Modifier.padding(vertical = 10.dp).bleed(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                item { FilterChip(tech.isEmpty(), { tech = "" }, label = { Text("Tutti i tecnici") }) }
                items(a.technicians) { t -> FilterChip(tech == t, { tech = if (tech == t) "" else t }, label = { Text(t) }) }
            }
        }
        if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.Build, "Nessun intervento", if (tab == "Da fare") "Non ci sono interventi da fare. Ottimo!" else if (tab == "Verifiche") "Nessuna verifica periodica registrata." else "Modifica i filtri o crea un nuovo intervento.") }
        items(list.size.coerceAtMost(800), key = { list[it].i.id }) { i -> InterventionRow(list[i], a, Modifier.padding(bottom = 8.dp).animateItem(), vm = vm) { onOpen(list[i].i.id) } }
    }
}

/** One intervention. Quick gestures (same as on the website from the phone): swipe right = print, swipe left = close,
 *  long press = the everyday actions (sign, print, close, postpone). A tap opens it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable fun InterventionRow(x: UIntervention, a: Archive, modifier: Modifier = Modifier, showClient: Boolean = true, vm: AppViewModel? = null, onClick: () -> Unit) {
    val tone = prioTone(x.i.priority) ?: Archive.toneOf(x.i.status)
    val stripe = LocalStatus.current.of(tone)
    val me = vm?.me?.collectAsStateWithLifecycle()?.value
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // imported from Mastro: the office and administrators may act on it too (it becomes the app's own), technicians only look
    val ownable = x.i.source == "app" || (x.i.source == "mastro" && me?.canEdit == true)
    val canPrint = vm != null && ownable && !x.i.id.startsWith("local-")
    val canClose = vm != null && ownable && x.i.signedAt.isBlank() && x.i.status != "Chiuso"
    var menu by rememberSaveable(x.i.id) { mutableStateOf(false) }
    val card: @Composable () -> Unit = {
        CardBox(Modifier, onClick = onClick, padding = PaddingValues(0.dp), onLongClick = if (canClose || canPrint) ({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = !menu }) else null) {
            // the status stripe is drawn behind the row (not measured): the row's height follows the menu animation frame by frame,
            // so the card below never overlaps it while the actions open or close
            val stripeColor = stripe.copy(alpha = if (x.i.status == "Chiuso") 0.25f else 0.9f)
            Row(Modifier.heightIn(min = 72.dp).drawBehind { drawRect(stripeColor, size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height)) }) {
                Column(Modifier.weight(1f).padding(start = 19.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(x.i.title.ifBlank { "Intervento" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        prioTone(x.i.priority)?.let { Pill(x.i.priority, it, Modifier.padding(start = 8.dp, end = 6.dp)) }
                        Pill(if (x.i.id in a.pending) "Da inviare" else x.i.status, if (x.i.id in a.pending) Tone.Info else Archive.toneOf(x.i.status), if (prioTone(x.i.priority) == null) Modifier.padding(start = 8.dp) else Modifier)
                    }
                    if (showClient) Text(x.clientName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    Text(listOf(x.date?.let { Dates.short(it) + " · " + Dates.relative(Dates.daysFromToday(it)) }, x.i.technician.takeIf { it.isNotBlank() },
                        x.i.reportNo.takeIf { x.i.signedAt.isNotBlank() }?.let { "firmato n. $it" }).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    androidx.compose.animation.AnimatedVisibility(menu && vm != null && (canClose || canPrint),
                        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 500f)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180, delayMillis = 60)),
                        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 600f)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110))) {
                        if (vm != null) QuickActions(vm, x.i, canClose, canPrint, onSign = onClick, onDone = { menu = false })
                    }
                }
            }
        }
    }
    if (!canPrint && !canClose) { Box(modifier) { card() }; return }
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.32f })
    androidx.compose.material3.SwipeToDismissBox(
        state = state, modifier = modifier,
        enableDismissFromStartToEnd = canPrint, enableDismissFromEndToStart = canClose,
        onDismiss = { v ->
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            if (v == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) vm?.printReport(x.i, ctx)
            else if (v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) vm?.saveIntervention(x.i.copy(status = "Chiuso"), quiet = true) { vm.notify("Intervento chiuso") }
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val dir = state.dismissDirection
            val toPrint = dir == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val st = LocalStatus.current
            val bg = if (toPrint) MaterialTheme.colorScheme.primary else st.ok
            val reached = state.targetValue != androidx.compose.material3.SwipeToDismissBoxValue.Settled
            val scale by androidx.compose.animation.core.animateFloatAsState(if (reached) 1.15f else 0.9f, androidx.compose.animation.core.spring(dampingRatio = 0.5f), label = "swipe-icon")
            Row(Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(if (dir == androidx.compose.material3.SwipeToDismissBoxValue.Settled) Color.Transparent else bg.copy(alpha = if (reached) 1f else 0.75f)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (toPrint) Arrangement.Start else Arrangement.End) {
                if (dir != androidx.compose.material3.SwipeToDismissBoxValue.Settled) {
                    Icon(if (toPrint) Icons.Rounded.Print else Icons.Rounded.Verified, null, tint = Color.White, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                    Text(if (toPrint) "Stampa" else "Chiudi", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }) { card() }
}

/** Everyday actions one tap away: sign (opens the intervention), print to take along, close, postpone. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun QuickActions(vm: AppViewModel, i: Intervention, canClose: Boolean, canPrint: Boolean, onSign: () -> Unit, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var later by remember { mutableStateOf(false) }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (canClose) androidx.compose.material3.AssistChip({ onDone(); onSign() }, { Text("Firma") }, leadingIcon = { Icon(Icons.Rounded.Draw, null, Modifier.size(16.dp)) })
        if (canPrint) androidx.compose.material3.AssistChip({ onDone(); vm.printReport(i, ctx) }, { Text("Stampa") }, leadingIcon = { Icon(Icons.Rounded.Print, null, Modifier.size(16.dp)) })
        if (canClose) androidx.compose.material3.AssistChip({ onDone(); vm.saveIntervention(i.copy(status = "Chiuso"), quiet = true) { vm.notify("Intervento chiuso") } }, { Text("Chiudi") },
            leadingIcon = { Icon(Icons.Rounded.Verified, null, Modifier.size(16.dp)) })
        if (canClose) Box {
            androidx.compose.material3.AssistChip({ later = true }, { Text("Posticipa") }, leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(16.dp)) })
            androidx.compose.material3.DropdownMenu(later, { later = false }, shape = RoundedCornerShape(18.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                val today = LocalDate.now()
                listOf("Domani" to today.plusDays(1), "Dopodomani" to today.plusDays(2), "Lunedì prossimo" to today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)),
                    "Tra una settimana" to today.plusWeeks(1), "Tra due settimane" to today.plusWeeks(2), "Tra un mese" to today.plusMonths(1)).forEach { (l, d) ->
                    DropdownMenuItem({ Column { Text(l); Text(Dates.short(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, {
                        later = false; onDone()
                        vm.saveIntervention(i.copy(date = d.toString(), status = if (i.status == "Aperto") "Pianificato" else i.status), quiet = true) { vm.notify("Intervento spostato a ${Dates.short(d)}") }
                    })
                }
            }
        }
    }
}

/** Bottom sheet to create / edit an intervention, always filled with what exists. One imported from Mastro is editable for owner,
 *  administrators and office (it becomes the app's own copy, later Mastro reads never overwrite it); technicians see it read-only. */
@Composable fun InterventionEditor(vm: AppViewModel, a: Archive, req: EditorRequest, onDismiss: () -> Unit, onOpenClient: (String) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    val existing = req.interventionId?.let { id -> a.interventionById(id) }
    val signed = existing?.i?.signedAt?.isNotBlank() == true
    val imported = existing?.i?.source == "mastro"
    val ro = signed || (imported && me?.canEdit != true)
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
    var tech by remember { mutableStateOf(existing?.i?.technician ?: me?.techName.orEmpty()) }
    var date by remember { mutableStateOf(existing?.i?.date?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()) }
    var machine by remember { mutableStateOf(existing?.i?.machine.orEmpty().let { raw -> // Mastro stores the bare machine number: match it to the customer's machine
        if (raw.isBlank()) raw else existing?.client?.machines?.firstOrNull { it.m.id == raw || it.m.id == "m$raw" || it.m.serial == raw }?.m?.id ?: raw }) }
    var parts by remember { mutableStateOf(existing?.i?.parts.orEmpty()) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    val current = { Intervention(id = existing?.i?.id.orEmpty(), clientCode = clientCode, clientName = clientQuery, date = date, machine = machine, title = title.trim(), work = work,
        status = status, technician = tech.trim(), priority = priority, total = existing?.i?.total.orEmpty(), parts = parts, hours = existing?.i?.hours.orEmpty(), kind = existing?.i?.kind.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(if (signed) "Rapportino n. ${existing.i.reportNo}" else if (ro) "Intervento dallo storico Mastro" else if (imported) "Modifica intervento importato" else if (existing != null) "Modifica intervento" else "Nuovo intervento", style = MaterialTheme.typography.headlineSmall)
            if (signed) existing.i.let { e ->
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                    Text(modifier = Modifier.weight(1f).padding(start = 12.dp), text = "Firmato da ${e.signer} il ${Dates.short(Dates.parse(e.signedAt.take(10)))}${if (e.hours.isNotBlank()) " · " + e.hours.replace('.', ',') + " h" else ""}. Non è più modificabile.",
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
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Catalog.interventionStates.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s -> FilterChip(status == s, { if (!ro) status = s }, modifier = Modifier.weight(1f).height(40.dp), enabled = !ro, shape = MaterialTheme.shapes.small,
                            label = { Text(s, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Gap(14.dp)
            Text("Priorità", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val st = LocalStatus.current
            SegmentedPicker(Catalog.priorities, Catalog.priorities.indexOf(priority).let { if (it < 0) 1 else it }, { priority = Catalog.priorities[it] }, Modifier.padding(top = 8.dp), enabled = !ro,
                accent = { prioTone(Catalog.priorities[it])?.let(st::of) })
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
                // a few machines: chips, one tap; many (a chain with dozens of registers): a menu, so the form stays short
                if (machines.size <= 4) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(machine.isEmpty(), { if (!ro) machine = "" }, label = { Text("Nessuna") }, enabled = !ro)
                    machines.forEach { m -> FilterChip(machine == m.m.id, { if (!ro) machine = m.m.id }, label = { Text("${m.m.model} · ${m.m.serial}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, enabled = !ro) }
                } else SelectField("Macchina (${machines.size})", machine, listOf(SelectOption("", "Nessuna")) + machines.map { m ->
                        SelectOption(m.m.id, "${m.m.model} · ${m.m.serial}".trim(' ', '·'), supporting = listOfNotNull(m.m.type.takeIf { it.isNotBlank() }, if (m.m.active) null else "dismessa").joinToString(" · ")) },
                    { machine = it }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !ro)
            }
            Gap(10.dp)
            PartsEditor(a, parts, tech, ro) { parts = it }
            Gap(10.dp)
            OutlinedTextField(work, { work = it.take(20000) }, Modifier.fillMaxWidth(), enabled = !ro, label = { Text("Lavoro svolto / note") }, minLines = 4, shape = MaterialTheme.shapes.medium)
            // photos: before the signature they can be added; after it they are part of the report
            if (existing?.i?.source != "mastro") PhotosSection(vm, a, existing?.i?.id, editable = !ro)
            if (existing != null && existing.i.id in a.pending) Pill("Da inviare · salvato sul telefono", Tone.Info, Modifier.padding(top = 10.dp))
            existing?.i?.let { e -> Text(if (imported) "Importato da MastroSQL${if (e.total.isNotBlank() && e.total != "0") " · totale " + e.total + " €" else ""}${if (ro) "" else ". Quello che cambi resta tuo: le prossime letture di Mastro non lo sovrascrivono."}" else if (e.origin == "mastro") "Importato da MastroSQL e modificato${if (e.updatedBy.isNotBlank()) " da " + e.updatedBy else ""} · le letture di Mastro non lo sovrascrivono" else "Creato da ${e.createdBy.ifBlank { "—" }}${if (e.updatedBy.isNotBlank() && e.updated != e.created) " · modificato da " + e.updatedBy else ""}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            Gap(16.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                if (signed && me?.role != "tecnico") TextButton({ vm.unsignIntervention(existing.i.id); close() }) { Text("Annulla firma", color = MaterialTheme.colorScheme.error) }
                if (existing != null && !ro && me?.role != "tecnico") TextButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error); Text("Elimina", Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.error, maxLines = 1) }
                if (clientCode.isNotEmpty()) TextButton({ onOpenClient(clientCode) }) { Text("Apri cliente") }
                }
                if (signed) Unit
                else if (ro) Button(close, Modifier.padding(start = 8.dp)) { Text("Chiudi") }
                else Button({
                    saving = true
                    vm.saveIntervention(current()) { saving = false; close() }.invokeOnCompletion { saving = false }
                }, Modifier.padding(start = 8.dp), enabled = !saving && clientCode.isNotEmpty() && title.isNotBlank(), shape = MaterialTheme.shapes.medium) { Text(if (saving) "Salvataggio…" else "Salva") }
            }
            if (signed) existing.i.let { e ->
                if (e.sentAt.isNotBlank()) Text("PDF inviato a ${e.sentTo} il ${Dates.short(Dates.parse(e.sentAt.take(10)))}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(Icons.Rounded.Print, "Stampa", { vm.printReport(e, ctx) }, Modifier.weight(1f))
                    ActionTile(Icons.Rounded.Share, "Condividi", { vm.shareReport(e, ctx) }, Modifier.weight(1f))
                    if (mailOn) ActionTile(Icons.Rounded.Email, "Email cliente", { mailDialog = true }, Modifier.weight(1f), primary = true)
                }
            }
            if (!ro && existing != null) androidx.compose.material3.FilledTonalButton({
                saving = true
                vm.saveIntervention(current(), quiet = true, offline = false) { r -> vm.printReport(r, ctx) }.invokeOnCompletion { saving = false }
            }, Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp), enabled = !saving && clientCode.isNotEmpty() && title.isNotBlank(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.Print, null); Text("Stampa da portare dal cliente", Modifier.padding(start = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            // the customer signs on the spot: the current edits are saved first, then the pad opens
            if (!ro) OutlinedButton({
                saving = true
                vm.saveIntervention(current(), quiet = true, offline = false) { r -> signing = r }.invokeOnCompletion { saving = false }
            }, Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp), enabled = !saving && clientCode.isNotEmpty() && title.isNotBlank(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.Draw, null); Text("Fai firmare il rapportino al cliente", Modifier.padding(start = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
    signing?.let { r -> SignatureSheet(vm, r, a.byCode[r.clientCode]?.name ?: r.clientName, a.byCode[r.clientCode]?.emails.orEmpty(), mailOn, onDismiss = { signing = null }) { s -> signing = null; afterSign = s } }
    afterSign?.let { s ->
        AlertDialog(onDismissRequest = { afterSign = null; close() }, icon = { Icon(Icons.Rounded.Verified, null) }, title = { Text("Rapportino n. ${s.reportNo} firmato") },
            text = { Text("Puoi stamparlo subito o condividerlo (WhatsApp, email, Drive…).") },
            confirmButton = { Button({ vm.printReport(s, ctx); afterSign = null; close() }) { Icon(Icons.Rounded.Print, null); Text("Stampa", Modifier.padding(start = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
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
