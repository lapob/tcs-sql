package it.topcash.studio.ui

import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import it.topcash.studio.data.AccountingView
import it.topcash.studio.data.AccountingView.Companion.money
import it.topcash.studio.data.AccountingView.Companion.money0
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UClient
import it.topcash.studio.data.UDue
import it.topcash.studio.data.norm
import kotlinx.coroutines.launch

private val accTabs = listOf("Da incassare", "Fatture", "Da pagare")
private val dueFilters = listOf("Aperte", "Scadute", "7 giorni", "Tutte")

private fun dueTone(d: UDue) = when { !d.open -> Tone.Ok; d.late -> Tone.Bad; (d.days ?: 99) <= 7 -> Tone.Due; else -> Tone.Neutral }
private fun statusTone(s: String) = when (s) { "Sollecitato" -> Tone.Info; "Promesso" -> Tone.Accent; "Incassato" -> Tone.Ok; "Contestato" -> Tone.Bad; else -> Tone.Neutral }

/** The accounting area has four pages, like the four registers of Mastro's accounting: summary, receivables, invoices, payables. */
private var accFilterHint: String? = null
enum class AccView(val route: String, val title: String) { Home("acc", "Contabilità"), Receivables("accRec", "Da incassare"), Invoices("accInv", "Fatture"), Payables("accPay", "Da pagare") }

@Composable fun AccountingScreen(vm: AppViewModel, a: Archive, nav: NavHostController, view: AccView = AccView.Home, topLevel: Boolean = false) {
    val acc = a.acc
    val tab = when (view) { AccView.Invoices -> accTabs[1]; AccView.Payables -> accTabs[2]; else -> accTabs[0] }
    var filter by rememberSaveable { mutableStateOf(dueFilters[0]) }
    LaunchedEffect(Unit) { accFilterHint?.let { filter = it; accFilterHint = null } } // filter chosen from the summary tiles
    var q by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val dues = remember(acc, tab, filter, q) {
        val n = norm(q.trim())
        (if (tab == "Da pagare") acc.payables else acc.receivables).filter { d ->
            (n.isEmpty() || d.search.contains(n)) && when (filter) { "Aperte" -> d.open; "Scadute" -> d.late; "7 giorni" -> d.open && (d.days ?: -1) in 0..7; else -> true }
        }
    }
    val invoices = remember(acc, q) { val n = norm(q.trim()); acc.invoices.filter { n.isEmpty() || it.search.contains(n) }.take(300) }
    val subtitle = when (view) {
        AccView.Home -> "${money0(acc.toCollect)} da incassare · ${acc.overdue.size} scadute"
        AccView.Receivables -> "${money0(acc.toCollect)} · ${acc.receivables.count { it.open }} rate aperte"
        AccView.Payables -> "${money0(acc.toPay)} · ${money0(acc.toPay30)} entro 30 giorni"
        AccView.Invoices -> "${acc.invoices.size} documenti · ${money0(acc.revenueYear)} nel ${java.time.LocalDate.now().year}"
    }
    val go: (AccView, String) -> Unit = { v, f -> accFilterHint = f; if (topLevel) nav.switchTab(v.route) else nav.navigate(v.route) }
    Page(vm, view.title, subtitle, onBack = if (topLevel) null else ({ nav.popBackStack() })) {
        if (!acc.hasData) { item(key = "none") { Empty(Icons.Rounded.Euro, "Nessun dato contabile", "Importa fatture e scadenze da MastroSQL dall'app Windows (Impostazioni).") }; return@Page }
        if (view == AccView.Home) accSummary(acc, nav, go) { open = it } else accRegister(view, tab, filter, { filter = it }, q, { q = it }, dues, invoices, nav) { open = it }
    }
    open?.let { id -> acc.due(id)?.let { d -> DueSheet(vm, d, nav, onDismiss = { open = null }) } ?: LaunchedEffect(id) { open = null } }
}

/** Summary: figures, the monthly chart and what the office should look at today. */
private fun LazyListScope.accSummary(acc: AccountingView, nav: NavHostController, go: (AccView, String) -> Unit, onDue: (String) -> Unit) {
        item(key = "kpi") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoneyTile("Fatturato ${java.time.LocalDate.now().year}", acc.revenueYear, deltaText(acc.revenueYear, acc.revenuePrevYear), Tone.Info, Modifier.weight(1f).appear(0))
                    MoneyTile("Da incassare", acc.toCollect, "${acc.receivables.count { it.open }} rate aperte", Tone.Accent, Modifier.weight(1f).appear(1)) { go(AccView.Receivables, "Aperte") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoneyTile("Scaduto", acc.overdueSum, if (acc.overdue.isEmpty()) "nessun ritardo" else "${acc.overdue.map { it.d.partyCode }.toSet().size} clienti da sollecitare", if (acc.overdue.isEmpty()) Tone.Ok else Tone.Bad, Modifier.weight(1f).appear(2)) { go(AccView.Receivables, "Scadute") }
                    MoneyTile("Da pagare · 30 gg", acc.toPay30, "${money0(acc.toPay)} in totale", Tone.Due, Modifier.weight(1f).appear(3)) { go(AccView.Payables, "Aperte") }
                }
            }
        }
        if (acc.invoices.isNotEmpty()) item(key = "chart") { MonthBars(acc, Modifier.padding(top = 10.dp).appear(4)) }
        val chase = acc.overdue.filter { it.note?.status.isNullOrBlank() || it.note.status == "Sollecitato" }.sortedByDescending { it.left }.take(6)
        item(key = "t-chase") { SectionTitle("Da sollecitare · ${acc.overdue.size}", Modifier.padding(top = 20.dp, bottom = 8.dp)) { androidx.compose.material3.TextButton({ go(AccView.Receivables, "Scadute") }) { Text("Tutte") } } }
        if (chase.isEmpty()) item(key = "e-chase") { Empty(Icons.Rounded.TaskAlt, "Nessun ritardo", "Tutti i clienti sono in regola con i pagamenti.") }
        items(chase.size, key = { "c" + chase[it].d.id }) { i -> DueRow(chase[i], Modifier.padding(bottom = 8.dp)) { onDue(chase[i].d.id) } }
        val soon = acc.payables.filter { it.open && (it.days ?: 999) <= 30 }.take(6)
        item(key = "t-soon") { SectionTitle("Da pagare entro 30 giorni", Modifier.padding(top = 16.dp, bottom = 8.dp)) { androidx.compose.material3.TextButton({ go(AccView.Payables, "Aperte") }) { Text("Tutti") } } }
        if (soon.isEmpty()) item(key = "e-soon") { Empty(Icons.Rounded.TaskAlt, "Niente in scadenza", "Nessun pagamento ai fornitori nei prossimi 30 giorni.") }
        items(soon.size, key = { "p" + soon[it].d.id }) { i -> DueRow(soon[i], Modifier.padding(bottom = 8.dp)) { onDue(soon[i].d.id) } }
        val rank = acc.ranking.take(8)
        if (rank.isNotEmpty()) item(key = "t-cli") { SectionTitle("Situazione clienti", Modifier.padding(top = 16.dp, bottom = 8.dp)) }
        items(rank.size, key = { "r" + rank[it].first.code }) { i -> val (c, p) = rank[i]
            CardBox(Modifier.padding(bottom = 8.dp), onClick = { nav.openClient(c.code) }, padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (p.overdue > 0.005) "${money(p.overdue)} scaduti · ${money(p.open)} da incassare" else if (p.open > 0.005) "${money(p.open)} da incassare" else "in regola",
                            style = MaterialTheme.typography.bodySmall, color = if (p.overdue > 0.005) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money0(p.revenueYear), style = MaterialTheme.typography.titleSmall.tabular)
                        Text("fatturato ${java.time.LocalDate.now().year}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
}

/** One register per page: receivables / payables with filters, or invoices. */
private fun LazyListScope.accRegister(view: AccView, tab: String, filter: String, setFilter: (String) -> Unit, q: String, setQ: (String) -> Unit,
    dues: List<UDue>, invoices: List<it.topcash.studio.data.UInvoice>, nav: NavHostController, onDue: (String) -> Unit) {
        item(key = "search") { SearchField(q, setQ, if (tab == "Fatture") "Cerca numero o cliente" else "Cerca nominativo, documento, nota", Modifier.padding(top = 8.dp)) }
        if (tab != "Fatture") {
            item(key = "filters") { ChoiceChips(dueFilters, filter, setFilter, Modifier.padding(vertical = 8.dp)) }
            if (dues.isEmpty()) item(key = "e") { Empty(Icons.Rounded.TaskAlt, "Nessuna rata", "Nessuna scadenza corrisponde ai filtri.") }
            items(dues.size.coerceAtMost(400), key = { "d" + dues[it].d.id }) { i -> DueRow(dues[i], Modifier.padding(bottom = 8.dp).animateItem()) { onDue(dues[i].d.id) } }
        } else {
            if (invoices.isEmpty()) item(key = "e") { Empty(Icons.Rounded.Euro, "Nessuna fattura", "Modifica la ricerca.") }
            items(invoices.size, key = { "i" + invoices[it].i.id }) { k -> val v = invoices[k]
                CardBox(Modifier.padding(bottom = 8.dp).animateItem(), onClick = v.client?.let { c -> { nav.openClient(c.code) } }, padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("n. ${v.i.number} · ${Dates.short(v.date)}${if (v.i.payment.isNotBlank()) " · " + v.i.payment else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(v.gross), style = MaterialTheme.typography.titleSmall.tabular)
                            if (v.sign < 0) Pill("Nota di credito", Tone.Accent) else Text("imp. " + money(v.net), style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
}

private fun deltaText(now: Double, prev: Double): String =
    if (prev <= 0) "imponibile, note di credito dedotte" else { val p = (now - prev) / prev * 100; "${if (p >= 0) "▲" else "▼"} ${"%.1f".format(kotlin.math.abs(p)).replace('.', ',')}% sull'anno scorso" }

@Composable private fun MoneyTile(title: String, value: Double, note: String, tone: Tone, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val c = LocalStatus.current.of(tone)
    CardBox(modifier, onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c)); Spacer(Modifier.size(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RollingText(money0(value), MaterialTheme.typography.titleLarge.tabular, Modifier.padding(top = 10.dp, bottom = 2.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Last 12 months of net revenue: bars grow in with a spring, staggered. */
@Composable private fun MonthBars(acc: AccountingView, modifier: Modifier) {
    val max = (acc.months.maxOfOrNull { kotlin.math.abs(it.second) } ?: 0.0).coerceAtLeast(1.0)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    CardBox(modifier, padding = PaddingValues(16.dp)) {
        Text("Fatturato mensile", style = MaterialTheme.typography.titleSmall)
        Text("Ultimi 12 mesi, imponibile", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(120.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            acc.months.forEachIndexed { i, (m, v) ->
                val f by animateFloatAsState(if (shown) (kotlin.math.abs(v) / max).toFloat().coerceIn(0.03f, 1f) else 0f,
                    spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow - i * 8f), label = "bar$i")
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight().graphicsLayer { scaleY = f; transformOrigin = TransformOrigin(0.5f, 1f) }
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (v < 0) LocalStatus.current.bad.copy(alpha = .5f) else MaterialTheme.colorScheme.primary.copy(alpha = if (i == acc.months.lastIndex) 1f else .55f)))
                    }
                    Text(Dates.month(m).take(3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                }
            }
        }
    }
}

@Composable fun DueRow(d: UDue, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tone = dueTone(d)
    CardBox(modifier, onClick = onClick, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (d.due != null) DateChip(d.due.dayOfMonth, Dates.month(d.due), tone)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull("doc. ${d.d.docNumber.ifBlank { "—" }}", AccountingView.whenText(d), d.note?.status?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                d.note?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(money(d.left), style = MaterialTheme.typography.titleSmall.tabular, color = if (d.late) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Client detail: accounting summary + open instalments. */
fun LazyListScope.clientAccounting(a: Archive, c: UClient, onDue: (String) -> Unit) {
    val p = a.acc.of(c) ?: return
    item(key = "acc-title") { SectionTitle("Contabilità") }
    item(key = "acc-sum") {
        CardBox(padding = PaddingValues(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Fatturato ${java.time.LocalDate.now().year}" to p.revenueYear, "Da incassare" to p.open, "Scaduto" to p.overdue).forEach { (t, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(money0(v), style = MaterialTheme.typography.titleSmall.tabular, color = if (t == "Scaduto" && v > 0) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    }
                }
            }
            p.balance?.let { b -> Text("Saldo contabile Mastro: ${money(b)} ${if (b > 0.005) "dare" else if (b < -0.005) "avere" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
    val open = p.dues.filter { it.open }.take(8)
    items(open.size, key = { "acc-d" + open[it].d.id }) { i -> DueRow(open[i], Modifier.padding(top = 8.dp)) { onDue(open[i].d.id) } }
}

@Composable fun DueSheet(vm: AppViewModel, d: UDue, nav: NavHostController?, onDismiss: () -> Unit) {
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(d.note?.status.orEmpty()) }
    var note by remember { mutableStateOf(d.note?.note.orEmpty()) }
    fun close(after: () -> Unit = {}) = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); after() }
    val email = d.client?.emails?.firstOrNull().orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(d.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${if (d.d.party == "F") "Pagamento a fornitore" else "Incasso da cliente"} · doc. ${d.d.docNumber.ifBlank { "—" }}${d.docDate?.let { " del " + Dates.short(it) } ?: ""}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(money(d.left), style = MaterialTheme.typography.headlineMedium.tabular)
                    Text("scadenza ${Dates.short(d.due)} · ${AccountingView.whenText(d)}${if (d.d.paid > 0) " · su " + money(d.d.amount) else ""}", style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.of(dueTone(d)))
                }
                if (d.d.payment.isNotBlank()) Chip(d.d.payment)
            }
            Text("Stato", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountingView.statuses.forEach { s -> FilterChip(status == s, { status = s }, label = { Text(s.ifBlank { "Nessuno" }) }, shape = MaterialTheme.shapes.small) }
            }
            OutlinedTextField(note, { if (it.length <= 2000) note = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Nota") }, placeholder = { Text("Es. promette bonifico entro venerdì") }, minLines = 2)
            d.note?.let { n -> Text("Ultimo aggiornamento: ${n.by} · ${n.date.take(10)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (d.d.party == "C") FilledTonalButton(enabled = email.isNotBlank(), onClick = {
                    val subject = "Sollecito di pagamento - documento ${d.d.docNumber}".trim()
                    val body = "Gentile ${d.name},\n\nda un controllo risulta ancora aperto l'importo di ${money(d.left)} relativo al documento n. ${d.d.docNumber.ifBlank { "—" }}${d.docDate?.let { " del " + Dates.short(it) } ?: ""}, scaduto il ${Dates.short(d.due)}.\n\nVi chiediamo cortesemente di provvedere al pagamento o di inviarci la contabile se già effettuato.\n\nCordiali saluti\nTop Cash Service S.r.l."
                    val uri = ("mailto:" + android.net.Uri.encode(email) + "?subject=" + android.net.Uri.encode(subject) + "&body=" + android.net.Uri.encode(body)).toUri()
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, uri)) }.onFailure { vm.notify("Nessuna app email trovata") }
                    if (status.isBlank()) status = "Sollecitato"
                }) { Icon(Icons.Rounded.Mail, null, Modifier.size(18.dp)); Text(" Sollecito") }
                if (nav != null && d.client != null) FilledTonalButton({ close { nav.openClient(d.client.code) } }) { Text("Cliente") }
                Spacer(Modifier.weight(1f))
                Button({ vm.saveDueNote(d.d.id, status, note.trim()); close() }) { Text("Salva") }
            }
        }
    }
}
