package it.topcash.studio.ui

import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Euro
import it.topcash.studio.data.AccountingView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Catalog
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UMachine
import it.topcash.studio.data.UOrder
import it.topcash.studio.data.norm

private val mtabs = listOf("Attive", "Verificazioni", "Abbonamenti", "Tutte")

@Composable fun MachinesScreen(a: Archive, nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf("Attive") }
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(a, tab, q) {
        val n = norm(q.trim())
        a.machines.filter { m ->
            when (tab) { "Attive" -> m.m.active; "Verificazioni" ->m.m.active && m.verDays != null && m.verDays <= 60; "Abbonamenti" -> m.m.subscribed; else -> true } &&
                (n.isEmpty() || norm(m.clientName + " " + m.m.model + " " + m.m.serial + " " + m.m.fiscalSerial).contains(n))
        }.sortedBy { if (tab == "Abbonamenti") it.contractEnd ?: java.time.LocalDate.MAX else it.nextVerification ?: java.time.LocalDate.MAX }
    }
    Page(null, "Registratori di cassa", "Tutti · ${a.machines.count { it.m.active }} attivi · ${a.verificationsDue.size} verificazioni entro 60 gg", onBack = { nav.popBackStack() }) {
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente o matricola") }
        item(key = "tabs") { ChoiceChips(mtabs, tab, { tab = it }, Modifier.padding(vertical = 10.dp)) }
        if (a.machines.isEmpty()) item(key = "none") { Empty(Icons.Rounded.PointOfSale, "Nessuna macchina in archivio", "Importa il parco macchine da MastroSQL dall'app Windows (Impostazioni → Importa da MastroSQL).") }
        else if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.PointOfSale, "Nessuna macchina", "Modifica i filtri o la ricerca.") }
        items(list.size.coerceAtMost(1000), key = { list[it].m.id }) { i -> MachineCard(list[i], Modifier.padding(bottom = 8.dp).animateItem(), showClient = true) { list[i].client?.let { nav.openClient(it.code) } } }
        if (a.machines.isNotEmpty()) item(key = "note") { Text("La prossima verificazione è stimata a 2 anni dall'ultima registrata in Mastro.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable fun MachineCard(m: UMachine, modifier: Modifier = Modifier, showClient: Boolean = false, onClick: (() -> Unit)? = null) {
    CardBox(modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (showClient) m.clientName else m.m.model.ifBlank { "Macchina" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (showClient) m.m.model + " · " else "") + m.m.serial + (if (m.m.fiscalSerial.isNotBlank()) " · " + m.m.fiscalSerial else ""), style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Pill(if (m.m.active) "Attiva" else "Dismessa", if (m.m.active) Tone.Ok else Tone.Neutral)
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            MiniFact("Ultima verificazione", Dates.short(m.verification))
            Column(Modifier.padding(bottom = 4.dp)) {
                Text("Prossima (stimata)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Dates.short(m.nextVerification) + (m.verDays?.let { " · " + Dates.relative(it) } ?: ""), style = MaterialTheme.typography.bodyMedium,
                    color = when { (m.verDays ?: 999) < 0 -> LocalStatus.current.bad; (m.verDays ?: 999) <= 60 -> LocalStatus.current.due; else -> MaterialTheme.colorScheme.onSurface })
            }
            MiniFact("Abbonamento", if (m.m.subscribed) (m.contractEnd?.let { "fino al " + Dates.short(it) } ?: "Sì") else "No")
            if (m.m.amount.isNotBlank() && m.m.amount != "0") MiniFact("Importo", m.m.amount + " €")
        }
        if (m.m.notes.isNotBlank()) Text(m.m.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable fun MoreScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    val me by vm.me.collectAsStateWithLifecycle()
    Page(vm, "Altro", me?.let { "${it.name} · ${it.roleLabel}" }) {
        item(key = "update") { UpdateCard(vm) }
        item(key = "items") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoreRow(Icons.Rounded.PointOfSale, "Registratori di cassa", "Tutti · ${a.machines.count { it.m.active }} attivi · ${a.verificationsDue.size} verificazioni entro 60 gg", Modifier.appear(0)) { nav.navigate("machines") }
                MoreRow(Icons.Rounded.Router, "Portale DTR", if (a.registers.isEmpty()) "Si collegano dall'app Windows dell'ufficio" else "${a.registers.size} casse · ${a.dtrErrors.size} con errori · ${a.dtrSilent.size} silenziose", Modifier.appear(0)) { nav.navigate("dtr") }
                MoreRow(Icons.Rounded.PointOfSale, "Portale Ital Retail", if (a.ital.isEmpty()) "Si collega dall'app Windows dell'ufficio" else "${a.ital.size} casse · ${a.italUpdate.size} da aggiornare · ${a.italNoSub.size} senza abbonamento", Modifier.appear(0)) { nav.navigate("ital") }
                MoreRow(Icons.Rounded.Print, "Portale RCH", if (a.rch.isEmpty()) "Si collega dall'app Windows dell'ufficio" else "${a.rchLive.size} registratori · ${a.rchVer.size} verificazioni · ${a.rchLow.size} DGFE quasi pieni", Modifier.appear(0)) { nav.navigate("rch") }
                MoreRow(Icons.Rounded.Route, "Giro di oggi", "Interventi e verificazioni per località, con il percorso", Modifier.appear(1)) { nav.navigate("route") }
                MoreRow(Icons.Rounded.Call, "Rinnovi", "Chi chiamare, chi ha rinnovato, chi sollecitare", Modifier.appear(1)) { nav.navigate("renewals") }
                if (me?.seesAccounting != false) MoreRow(Icons.Rounded.Euro, "Contabilità", if (a.acc.hasData) "${AccountingView.money0(a.acc.toCollect)} da incassare · ${a.acc.overdue.size} rate scadute" else "Fatture, incassi e pagamenti da MastroSQL", Modifier.appear(0)) { nav.navigate("accounting") }
                MoreRow(Icons.Rounded.CalendarMonth, "Scadenziario", "${a.deadlines(0, 120).size} scadenze nei prossimi 120 giorni", Modifier.appear(0)) { nav.navigate("deadlines") }
                MoreRow(Icons.Rounded.Key, "Licenze Zucchetti", "${a.currentLicenses.size} correnti · ${a.licenses.size - a.currentLicenses.size} storiche", Modifier.appear(1)) { nav.navigate("licenses") }
                MoreRow(Icons.Rounded.ShoppingBag, "Ordini web", "${a.orders.size} ordini in archivio", Modifier.appear(2)) { nav.navigate("orders") }
                MoreRow(Icons.Rounded.Computer, "PC e sistemi", if (a.assets.isEmpty()) "Inventario dei PC, stampanti e rete dei clienti" else "${a.assets.count { it.a.active }} in uso · ${a.assetsDue.size} garanzie o scadenze vicine", Modifier.appear(2)) { nav.navigate("inventory") }
                if (me?.isAdmin == true) {
                    MoreRow(Icons.Rounded.AccountCircle, "Utenti", "Crea, modifica ed elimina gli accessi, compreso il tuo", Modifier.appear(3)) { nav.navigate("users") }
                    if (a.deleted.isNotEmpty()) MoreRow(Icons.Rounded.AccountCircle, "Clienti eliminati", "${a.deleted.size} da ripristinare", Modifier.appear(3)) { nav.navigate("deleted") }
                }
                MoreRow(Icons.Rounded.AccountCircle, "Account e impostazioni", "Tema, server, uscita", Modifier.appear(3)) { nav.navigate("account") }
                MoreRow(Icons.Rounded.SystemUpdate, "Versione ${vm.appVersion}", "Tocca per controllare gli aggiornamenti", Modifier.appear(3)) { vm.checkUpdate(true) }
            }
        }
    }
}

@Composable private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    CardBox(modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun DeadlinesScreen(a: Archive, nav: NavHostController) {
    var kind by rememberSaveable { mutableStateOf("Tutte") }
    val all = remember(a) { a.deadlines(-60, 180) }
    val list = remember(all, kind) { all.filter { kind == "Tutte" || it.kind == kind } }
    val grouped = remember(list) { list.groupBy { it.date.withDayOfMonth(1) } }
    Page(null, "Scadenziario", "Licenze, verificazioni e abbonamenti", onBack = { nav.popBackStack() }) {
        item(key = "kinds") {
            LazyRow(Modifier.padding(bottom = 6.dp).bleed(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(listOf("Tutte", "Licenza", "Verificazione", "Abbonamento")) { k -> FilterChip(kind == k, { kind = k }, label = { Text(if (k == "Tutte") k else k + (if (k == "Licenza") "" else "")) }) }
            }
        }
        if (list.isEmpty()) item(key = "e") { Empty(Icons.Rounded.CalendarMonth, "Nessuna scadenza", "Nel periodo considerato non ci sono scadenze.") }
        grouped.forEach { (month, items) ->
            stickyHeader(key = "h$month") {
                Text("${Dates.monthYear(month).replaceFirstChar { it.uppercase() }} · ${items.size}", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary)
            }
            items.forEachIndexed { i, d ->
                item(key = "d$month$i${d.detail}${d.kind}") {
                    val tone = if (d.days < 0) Tone.Bad else if (d.days <= 30) Tone.Due else Tone.Neutral
                    CardBox(Modifier.padding(bottom = 8.dp).animateItem(), onClick = d.client?.let { c -> { nav.openClient(c.code) } }, padding = PaddingValues(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DateChip(d.date.dayOfMonth, Dates.month(d.date), tone)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(d.clientName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${d.kind} · ${d.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(Dates.relative(d.days), style = MaterialTheme.typography.labelMedium, color = LocalStatus.current.of(tone))
                        }
                    }
                }
            }
        }
    }
}

@Composable fun LicensesScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    val me by vm.me.collectAsStateWithLifecycle(); val admin = me?.isAdmin == true
    var editing by remember { mutableStateOf<it.topcash.studio.data.ULicense?>(null) }
    editing?.let { l -> LicenseEditSheet(vm, a, l) { editing = null } }
    var tab by rememberSaveable { mutableStateOf("Correnti") }
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(a, tab, q) {
        val n = norm(q.trim())
        a.licenses.filter { (if (tab == "Correnti") it.current else if (tab == "Storico") !it.current else true) && (n.isEmpty() || norm(it.r.client + " " + it.r.serial + " " + it.r.vat).contains(n)) }
            .sortedBy { it.expiry ?: java.time.LocalDate.MAX }
    }
    Page(null, "Licenze", "${list.size} licenze", onBack = { nav.popBackStack() }) {
        item(key = "tabs") { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("Correnti", "Storico", "Tutte").forEachIndexed { i, t -> SegmentedButton(tab == t, { tab = t }, SegmentedButtonDefaults.itemShape(i, 3), label = { Text(t) }) } } }
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, matricola, P.IVA", Modifier.padding(vertical = 10.dp)) }
        a.state.licenseCleanup.let { k -> if (k.red + k.old + k.hidden > 0) item(key = "clean") { Text("Non compaiono ${listOfNotNull(k.red.takeIf { it > 0 }?.let { "$it licenze non valide sul portale" }, k.old.takeIf { it > 0 }?.let { "$it scadute da oltre 6 mesi" }, k.hidden.takeIf { it > 0 }?.let { "$it tolte a mano" }).joinToString(", ")}.${if (admin) " Tocca una licenza per correggerla." else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp)) } }
        items(list.size.coerceAtMost(1500), key = { list[it].r.id }) { i ->
            val l = list[i]
            CardBox(Modifier.padding(bottom = 8.dp).animateItem(), onClick = { if (admin) editing = l else nav.openClient(l.r.clientCode) }, padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.r.client, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Catalog.family(l.family)} · ${l.r.serial} · ${Dates.short(l.expiry)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Pill(l.status, Archive.toneOf(l.status))
                }
            }
        }
    }
}

@Composable fun OrdersScreen(a: Archive, nav: NavHostController) {
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(a, q) { val n = norm(q.trim()); a.orders.filter { n.isEmpty() || norm(it.o.client + " " + it.o.number + " " + it.o.serial + " " + it.o.status).contains(n) } }
    Page(null, "Ordini web", "${a.orders.size} ordini Zucchetti", onBack = { nav.popBackStack() }) {
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, numero, matricola", Modifier.padding(bottom = 10.dp)) }
        if (list.isEmpty()) item(key = "e") { Empty(Icons.Rounded.ShoppingBag, "Nessun ordine", "Gli ordini arrivano dalla sincronizzazione con Zucchetti.") }
        items(list.size.coerceAtMost(1500), key = { list[it].o.id }) { i -> OrderRow(list[i], Modifier.padding(bottom = 8.dp).animateItem()) { list[i].client?.let { nav.openClient(it.code) } } }
    }
}

@Composable fun OrderRow(o: UOrder, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    CardBox(modifier, onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.o.client, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Dates.short(o.date)} · n. ${o.o.number} · ${Catalog.family(o.o.family)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Pill(o.o.status, Archive.toneOf(o.o.status))
        }
    }
}

@Composable fun AccountScreen(vm: AppViewModel, nav: NavHostController) {
    val me by vm.me.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    Page(null, "Account", onBack = { nav.popBackStack() }) {
        item(key = "me") {
            CardBox(Modifier.appear(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(me?.name.orEmpty(), me?.username.orEmpty(), 52.dp)
                    Column(Modifier.padding(start = 14.dp)) { Text(me?.name.orEmpty(), style = MaterialTheme.typography.titleMedium); Text("${me?.username} · ${me?.roleLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text("Per cambiare la password usa Impostazioni su PC o browser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
        item(key = "theme") {
            SectionTitle("Aspetto")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().appear(1)) {
                listOf("system" to "Automatico", "light" to "Chiaro", "dark" to "Scuro").forEachIndexed { i, (k, l) -> SegmentedButton(theme == k, { vm.setTheme(k) }, SegmentedButtonDefaults.itemShape(i, 3), label = { Text(l) }) }
            }
        }
        item(key = "server") {
            SectionTitle("Server")
            CardBox(Modifier.appear(2)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Router, null, tint = MaterialTheme.colorScheme.primary); Text("  " + vm.server.removePrefix("http://").trimEnd('/'), style = MaterialTheme.typography.bodyLarge) }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ vm.changeServer() }) { Text("Cambia server") }
                    OutlinedButton({ vm.logout() }) { Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp)); Text("  Esci") }
                }
            }
        }
        item(key = "about") { Text("Top Cash Studio 2.0 per Android · Top Cash Service S.r.l.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp)) }
    }
}
