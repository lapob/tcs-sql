package it.topcash.studio.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.foundation.clickable
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Catalog
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable fun ClientDetailScreen(vm: AppViewModel, a: Archive, c: UClient, nav: NavHostController, onNewIntervention: () -> Unit, onOpenIntervention: (String) -> Unit) {
    val ctx = LocalContext.current
    var dueOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    dueOpen?.let { id -> a.acc.due(id)?.let { d -> DueSheet(vm, d, null) { dueOpen = null } } }
    val me by vm.me.collectAsState(); val admin = me?.isAdmin == true
    var editClient by remember { mutableStateOf(false) }
    var editLicense by remember { mutableStateOf<it.topcash.studio.data.ULicense?>(null) }
    if (editClient) ClientEditSheet(vm, c, { editClient = false })
    // the card is split in tabs: what you need is one tap away instead of at the end of a long scroll
    val tabs = listOfNotNull("Riepilogo", "Dispositivi", "Licenze", "Interventi", if (me?.seesAccounting != false) "Contabilità" else null)
    var tab by androidx.compose.runtime.saveable.rememberSaveable(c.code) { mutableStateOf("Riepilogo") }
    if (tab !in tabs) tab = "Riepilogo"
    var showOff by androidx.compose.runtime.saveable.rememberSaveable(c.code) { mutableStateOf(false) }
    editLicense?.let { l -> LicenseEditSheet(vm, a, l) { editLicense = null } }
    Page(vm, c.name, null, onBack = { nav.popBackStack() }, compactTitle = true,
        actions = {
            if (me != null) androidx.compose.material3.IconButton({ editClient = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.Edit, "Modifica cliente") }
            val fav = c.meta.favorite
            androidx.compose.material3.IconButton({ vm.saveMeta(c.code, c.meta.copy(favorite = !fav), if (fav) "Rimosso dai preferiti" else "Aggiunto ai preferiti") }) {
                val s by animateFloatAsState(if (fav) 1.15f else 1f, Bouncy, label = "fav")
                Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Preferito", tint = if (fav) LocalStatus.current.due else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.graphicsLayer { scaleX = s; scaleY = s })
            }
        }) {
        item(key = "head") {
            Row(Modifier.padding(top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.name, c.code, 64.dp, Modifier.shared("avatar-${c.code}"))
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.sharedBox("name-${c.code}"), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Cod. ${c.code.trimStart('0')}${if (c.mastro != null) " · Mastro ${c.mastro!!.code}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(c.status, Archive.toneOf(c.status)); if (c.mastroOnly) Pill("Mastro", Tone.Info); c.edit?.let { e -> Pill(if (e.created) "Creato a mano" else "Modificato", Tone.Accent) }
                        if (c.dormant) Pill("Archiviato", Tone.Neutral)
                    }
                }
            }
        }
        item(key = "who") {
            // who follows the customer, under which conditions, and (only for who sees accounting) what they still owe
            CardBox(Modifier.padding(bottom = 14.dp).appear(1)) {
                Text(if (c.tech.isNotBlank()) "Tecnico: ${c.tech}" else "Nessun tecnico assegnato", style = MaterialTheme.typography.titleSmall,
                    color = if (c.tech.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.services.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.services.forEach { (k, n) ->
                        Pill(if (n > 1) "$k · $n macchine" else k, when (k) { "Noleggio", "Abbonamento" -> Tone.Info; "Garanzia" -> Tone.Ok; "A chiamata" -> Tone.Neutral; else -> Tone.Accent })
                    }
                }
                if (me?.seesAccounting == true) a.acc.of(c)?.takeIf { it.dues.isNotEmpty() }?.let { p ->
                    val (txt, tone) = when {
                        p.overdue > 0.005 -> (if (p.overdue >= p.open - 0.005) "Deve pagare ${it.topcash.studio.data.AccountingView.money(p.open)}, tutto scaduto"
                            else "Deve pagare ${it.topcash.studio.data.AccountingView.money(p.open)}, di cui ${it.topcash.studio.data.AccountingView.money(p.overdue)} scaduti") to Tone.Bad
                        p.open > 0.005 -> "Da pagare ${it.topcash.studio.data.AccountingView.money(p.open)}, nulla di scaduto" to Tone.Due
                        else -> "In regola con i pagamenti" to Tone.Ok
                    }
                    androidx.compose.foundation.layout.Box(Modifier.padding(top = 8.dp).clickable { tab = "Contabilità" }) { Pill(txt, tone) }
                }
            }
        }
        item(key = "quick") {
            Row(Modifier.fillMaxWidth().appear(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.Rounded.Call, "Chiama", c.phone.isNotBlank(), Modifier.weight(1f)) { Actions.call(ctx, c.phone) }
                QuickAction(Icons.Rounded.Email, "Email", c.email.isNotBlank(), Modifier.weight(1f)) { Actions.mail(ctx, c.email) }
                QuickAction(Icons.Rounded.Place, "Mappa", c.fullAddress.isNotBlank(), Modifier.weight(1f)) { Actions.map(ctx, c.fullAddress) }
                QuickAction(Icons.Rounded.Add, "Intervento", true, Modifier.weight(1f), onNewIntervention)
            }
        }
        item(key = "tabs") { ChoiceChips(tabs, tab, { tab = it }, Modifier.padding(top = 14.dp)) }
        if (tab == "Riepilogo") {
        item(key = "anag") {
            SectionTitle("Anagrafica")
            CardBox(Modifier.appear(2)) {
                // only filled fields: no rows of "—"
                if (c.vat.isNotBlank()) Fact("Partita IVA", c.vat)
                if (c.taxCode.isNotBlank() && c.taxCode != c.vat) Fact("Codice fiscale", c.taxCode)
                if (c.phones.isNotEmpty()) Fact("Telefono", c.phones.joinToString(" · "))
                if (c.emails.isNotEmpty()) Fact("Email", c.emails.joinToString(" · "))
                c.pec.takeIf { it.isNotBlank() }?.let { Fact("PEC", it) }
                if (c.contact.isNotBlank()) Fact("Referente", c.contact)
                if (c.category.isNotBlank()) Fact("Categoria", c.category)
                if (c.fullAddress.isNotBlank()) Fact("Indirizzo", c.fullAddress)
                if (c.phones.isEmpty() && c.emails.isEmpty() && c.fullAddress.isBlank())
                    Text("Contatti e indirizzo arrivano con la sincronizzazione Zucchetti o l'importazione da MastroSQL.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                c.mastro?.zone?.takeIf { it.isNotBlank() }?.let { Fact("Zona", it) }
                if (c.families.isNotEmpty()) Fact("Prodotti Zucchetti", c.families.joinToString(", ") { Catalog.family(it) })
                c.mastro?.notes?.takeIf { it.isNotBlank() && it.trim() != "0" }?.let { Fact("Note Mastro", it) }
                val extra = c.mastro?.extra.orEmpty()
                if (extra.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    TextButton({ open = !open }) { Text(if (open) "Nascondi altri dati Mastro" else "Altri dati Mastro (${extra.size})"); Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
                    Column(Modifier.animateContentSize()) { if (open) extra.forEach { (k, v) -> Fact(k.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, v) } }
                }
                Text("Tieni premuto un valore per copiarlo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item(key = "followup") { FollowUp(vm, c) }
        }
        if (tab == "Interventi") {
        item(key = "int-title") { SectionTitle("Interventi") { TextButton(onNewIntervention) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text(" Nuovo") } } }
        if (c.interventions.isEmpty()) item(key = "int-empty") { CardBox { Text("Nessun intervento registrato.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        c.interventions.take(15).forEach { x -> item(key = "i" + x.i.id) { InterventionRow(x, a, Modifier.padding(bottom = 8.dp).animateItem(), showClient = false, vm = vm) { onOpenIntervention(x.i.id) } } }
        }
        if (tab == "Dispositivi") {
        val off = c.machines.count { !it.m.active }
        if (off > 0) item(key = "m-off") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mostra anche le dismesse ($off)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(showOff, { showOff = it })
            }
        }
        val shown = c.machines.filter { showOff || it.m.active }.sortedWith(compareBy({ !it.m.active }, { it.m.model }))
        devOrder.forEach { gi -> val label = devGroups[gi]
            val ms = shown.filter { devCat(it.m.model) == gi }
            if (ms.isNotEmpty()) {
                item(key = "mg$gi") { SectionTitle("$label (${ms.count { it.m.active }}${if (ms.any { !it.m.active }) " + ${ms.count { !it.m.active }} dismesse" else ""})") }
                ms.forEach { m -> item(key = "m" + m.m.id) { MachineCard(m, Modifier.padding(bottom = 8.dp)) } }
            }
        }
        if (shown.isEmpty()) item(key = "m-empty") { CardBox(Modifier.padding(top = 12.dp)) { Text(if (off > 0) "Solo dispositivi dismessi: attiva l'interruttore qui sopra per vederli." else "Nessun dispositivo: importa il parco macchine da MastroSQL.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (c.registers.isNotEmpty()) {
            item(key = "dtr-title") { SectionTitle("Registratori DTR (${c.registers.size})") }
            c.registers.forEach { r -> item(key = "dtr" + r.r.serial) { RegisterCard(r, Modifier.padding(bottom = 8.dp), showClient = false) } }
        }
        if (c.italDevices.isNotEmpty()) {
            item(key = "ital-title") { SectionTitle("Casse Ital Retail (${c.italDevices.size})") }
            c.italDevices.forEach { r -> item(key = "ital" + r.r.serial) { ItalCard(r, Modifier.padding(bottom = 8.dp), showClient = false) } }
        }
        item(key = "assets") { ClientAssets(vm, a, c) }
        }
        if (tab == "Contabilità" && me?.seesAccounting != false) clientAccounting(a, c) { dueOpen = it }
        if (tab == "Licenze" && c.licenses.isEmpty()) item(key = "l-empty") { CardBox(Modifier.padding(top = 12.dp)) { Text("Nessuna licenza Zucchetti negli elenchi letti.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (tab == "Licenze" && c.licenses.isNotEmpty()) {
            item(key = "l-title") { SectionTitle("Licenze Zucchetti (${c.licenses.size})") }
            c.licenses.forEach { l ->
                item(key = "l" + l.r.id) {
                    CardBox(Modifier.padding(bottom = 8.dp), onClick = if (admin) ({ editLicense = l }) else null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(Catalog.family(l.family), style = MaterialTheme.typography.titleSmall); Text(l.r.serial, style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Pill(l.status, Archive.toneOf(l.status))
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            MiniFact("Scadenza", Dates.short(l.expiry) + (l.days?.let { " · " + Dates.relative(it) } ?: ""))
                            MiniFact("Versione", l.r.version.ifBlank { "—" }); MiniFact("Elenco", if (l.current) "Corrente" else "Storico")
                        }
                    }
                }
            }
        }
        if (tab == "Licenze" && c.orders.isNotEmpty()) {
            item(key = "or-title") { SectionTitle("Ordini (${c.orders.size})") }
            c.orders.take(20).forEach { o -> item(key = "or" + o.o.id) { OrderRow(o, Modifier.padding(bottom = 8.dp)) } }
        }
    }
}

@Composable private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    FilledTonalButton(onClick, modifier.height(72.dp).pressScale(src), enabled = enabled, interactionSource = src, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable fun MiniFact(label: String, value: String) = Column(Modifier.padding(bottom = 4.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun FollowUp(vm: AppViewModel, c: UClient) {
    var state by remember(c.code, c.meta) { mutableStateOf(c.meta.followupState) }
    var date by remember(c.code, c.meta) { mutableStateOf(c.meta.nextContact) }
    var note by remember(c.code, c.note) { mutableStateOf(c.note) }
    var picking by remember { mutableStateOf(false) }
    SectionTitle("Promemoria e note")
    CardBox {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.followups.filter { it.isNotBlank() }.forEach { s -> FilterChip(state == s, { state = if (state == s) "" else s }, label = { Text(s) }) }
        }
        OutlinedButton({ picking = true }, Modifier.padding(top = 8.dp)) { Text(if (date.isBlank()) "Imposta prossimo contatto" else "Prossimo contatto: " + Dates.short(Dates.parse(date))) }
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Note") }, minLines = 3, shape = MaterialTheme.shapes.medium)
        val changed = state != c.meta.followupState || date != c.meta.nextContact || note != c.note
        Button({ if (state != c.meta.followupState || date != c.meta.nextContact) vm.saveMeta(c.code, c.meta.copy(followupState = state, nextContact = date)); if (note != c.note) vm.saveNote(c.code, note) },
            Modifier.padding(top = 10.dp), enabled = changed) { Text("Salva") }
        Text("Visibile a tutto il team", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    if (picking) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (Dates.parse(date) ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = { TextButton({ st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; picking = false }) { Text("OK") } },
            dismissButton = { TextButton({ date = ""; picking = false }) { Text("Rimuovi") } }) { DatePicker(st) }
    }
}

/** Kind of device from the model written in Mastro: registers, automatic cash (Cashmatic), software, printers, scales. */
private val devGroups = listOf("Registratori di cassa", "Casse automatiche · Cashmatic", "Software e app", "Stampanti", "Bilance", "Registratori DTR", "Ital Retail", "Epson")
/** Order on screen: DTR, Ital Retail and Epson on their own, then every other register, then the other kinds. */
private val devOrder = listOf(5, 6, 7, 0, 1, 2, 3, 4)
private val softRe = Regex("LICENZA|\\bAPP\\b|APP\\.|SOFTWARE|RISTOQUICK|POSBY"); private val printRe = Regex("^STAMP|STAMPANTE")
private fun devCat(model: String): Int { val s = model.uppercase(); return when { "CASHMATIC" in s -> 1; softRe.containsMatchIn(s) -> 2; printRe.containsMatchIn(s) -> 3; "BILANCIA" in s -> 4; else -> rtSection(s) } }
private fun rtSection(s: String) = when { Regex("\\bDTR\\b|\\bDRT|^D(CASH|FLEX|PALM)\\b").containsMatchIn(s) -> 5; Regex("ITAL ?RETAIL|\\bNICE\\b|\\bSPICE\\b|ITAL ?START|^NEXT\\b").containsMatchIn(s) -> 6; Regex("EPSON|\\bFP[- ]?\\d").containsMatchIn(s) -> 7; else -> 0 }
