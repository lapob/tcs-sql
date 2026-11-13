package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import it.topcash.studio.data.AccountingView.Companion.money0
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Insights
import it.topcash.studio.data.Tone
import it.topcash.studio.data.norm

private class InsTab(val key: String, val label: String, val icon: ImageVector, val count: Int, val value: Double, val note: String, val tone: Tone)
private val oppKinds = listOf("Contratto" to "Contratti", "Verifica" to "Verifiche", "Sostituzione" to "Sostituzioni")

private fun tabsOf(i: Insights): List<InsTab> {
    val notSent = i.sdi.count { it.status != "Mancata consegna" }
    val critical = i.payers.count { it.grade == "C" }
    return listOf(
        InsTab("unbilled", "Da fatturare", Icons.Rounded.Description, i.unbilled.size, i.unbilled.sumOf { it.amount }, "interventi chiusi senza fattura", if (i.unbilled.isEmpty()) Tone.Ok else Tone.Due),
        InsTab("sdi", "Fatture SDI", Icons.Rounded.Warning, i.sdi.size, i.sdi.sumOf { kotlin.math.abs(it.total) }, if (notSent > 0) "$notSent non inviate o scartate" else "consegna non riuscita", if (notSent > 0) Tone.Bad else if (i.sdi.isEmpty()) Tone.Ok else Tone.Due),
        InsTab("payers", "Pagano tardi", Icons.Rounded.Schedule, i.payers.size, i.payers.sumOf { it.overdue }, "$critical critici", if (critical > 0) Tone.Bad else if (i.payers.isEmpty()) Tone.Ok else Tone.Due),
        InsTab("churn", "Clienti in calo", Icons.AutoMirrored.Rounded.TrendingDown, i.churn.size, i.churn.sumOf { it.lost }, "fatturato perso in un anno", if (i.churn.isEmpty()) Tone.Ok else Tone.Due),
        InsTab("opps", "Da vendere", Icons.Rounded.AutoAwesome, i.opps.size, i.opps.sumOf { it.value }, "contratti, verifiche, sostituzioni", Tone.Info),
    )
}

private class InsLine(val key: String, val code: String, val name: String, val title: String, val sub: String, val amount: String, val amountNote: String, val badge: Pair<String, Tone>?)

/** Priorità: what to fix and what to sell, read from Mastro by the server (same lists as the site and the Windows app). */
@Composable fun InsightsScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    val ins = a.state.insights
    var tab by rememberSaveable { mutableStateOf("unbilled") }
    var opp by rememberSaveable { mutableStateOf("Contratto") }
    var q by rememberSaveable { mutableStateOf("") }
    Page(vm, "Priorità", "Cosa sistemare e cosa vendere", onBack = { nav.popBackStack() }) {
        if (ins == null) { item(key = "none") { Empty(Icons.Rounded.AutoAwesome, "Le priorità stanno arrivando", "Il server legge Mastro da solo ogni mezz’ora: gli elenchi compaiono alla prossima lettura.") }; return@Page }
        val tabs = tabsOf(ins)
        item(key = "tiles") {
            val st = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyRow(Modifier.bleed(16.dp).fadeEdges(st, vertical = false).padding(top = 4.dp, bottom = 12.dp), state = st, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(tabs.size) { k -> val t = tabs[k]; InsTile(t, tab == t.key, Modifier.width(170.dp).appear(k)) { tab = t.key; q = "" } }
            }
        }
        if (tab == "opps") item(key = "opp-kinds") {
            val labels = oppKinds.map { (k, l) -> "$l · ${ins.opps.count { it.kind == k }}" }
            ChoiceChips(labels, labels[oppKinds.indexOfFirst { it.first == opp }.coerceAtLeast(0)], { s -> opp = oppKinds[labels.indexOf(s)].first }, Modifier.padding(bottom = 8.dp))
        }
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, fattura, macchina…", Modifier.padding(bottom = 10.dp)) }
        val n = norm(q.trim())
        val lines = linesOf(ins, tab, opp).filter { n.isEmpty() || norm(it.name + " " + it.title + " " + it.sub + " " + it.code).contains(n) }
        item(key = "body") {
            AnimatedContent(tab + opp, transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally { it / 12 }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally { -it / 12 }) }, label = "ins") { _ ->
                Column {
                    Text(intro(tab, ins), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                    if (lines.isEmpty()) Empty(Icons.Rounded.TaskAlt, if (n.isEmpty()) "Niente da fare qui" else "Nessun risultato", if (n.isEmpty()) "Tutto in ordine per questo elenco." else "Modifica la ricerca.")
                }
            }
        }
        items(lines.size.coerceAtMost(400), key = { lines[it].key }) { k ->
            val x = lines[k]; val c = a.acc.client(x.code)
            InsRow(x, Modifier.padding(bottom = 8.dp).animateItem()) { if (c != null) nav.openClient(c.code) }
        }
    }
}

private fun intro(tab: String, i: Insights) = when (tab) {
    "unbilled" -> "Chiusi con un importo ma senza documento in Mastro, o fatti con l'app e senza una fattura al cliente da allora."
    "sdi" -> "Non inviate allo SDI o scartate: da sistemare e inviare. Mancata consegna: il cliente la trova nel cassetto fiscale, va solo avvisato."
    "payers" -> "Rate scadute e non incassate. C = oltre 60 giorni o 3.000 €, B = oltre 30 giorni o 800 €, A = lieve ritardo."
    "churn" -> "Clienti che negli ultimi 12 mesi hanno comprato meno della metà dei 12 mesi prima (almeno 500 €)."
    else -> "Contratti scaduti o in scadenza, verifiche periodiche dovute (ogni 2 anni) e registratori con più di 6 anni" + if (i.registerPrice > 0) " (sostituzione stimata ${money0(i.registerPrice)})." else "."
}

private fun linesOf(i: Insights, tab: String, opp: String): List<InsLine> = when (tab) {
    "unbilled" -> i.unbilled.map { x -> InsLine("u" + x.id, x.clientCode, x.name, x.title, listOf(Dates.short(Dates.parse(x.date)), x.technician).filter { it.isNotBlank() }.joinToString(" · "),
        if (x.amount > 0) money0(x.amount) else "—", if (x.source == "app") "dall'app" else "da Mastro", null) }
    "sdi" -> i.sdi.map { x -> InsLine("s" + x.number + x.date, x.clientCode, x.name, "Fattura " + x.number, Dates.short(Dates.parse(x.date)),
        money0(kotlin.math.abs(x.total)), "", x.status to (if (x.status == "Mancata consegna") Tone.Due else Tone.Bad)) }
    "payers" -> i.payers.map { x -> InsLine("p" + x.clientCode, x.clientCode, x.name, "${x.rates} " + (if (x.rates == 1) "rata scaduta" else "rate scadute") + " · la più vecchia da ${x.oldestDays} giorni",
        if (x.revenue12 > 0) "fatturato 12 mesi ${money0(x.revenue12)}" else "nessuna fattura negli ultimi 12 mesi", money0(x.overdue), "scaduto",
        x.grade to when (x.grade) { "C" -> Tone.Bad; "B" -> Tone.Due; else -> Tone.Info }) }
    "churn" -> i.churn.map { x -> InsLine("c" + x.clientCode, x.clientCode, x.name, "da ${money0(x.prev12)} a ${money0(x.last12)}",
        if (x.lastInvoice.isNotBlank()) "ultima fattura " + Dates.short(Dates.parse(x.lastInvoice)) else "", "− " + money0(x.lost), "in un anno", null) }
    else -> i.opps.filter { it.kind == opp }.map { x ->
        val d = Dates.parse(x.`when`); val days = Dates.daysFromToday(d)
        InsLine("o" + x.kind + x.machineId, x.clientCode, x.name, x.what.ifBlank { x.kind },
            when (x.kind) { "Sostituzione" -> "installato il " + Dates.short(d); "Verifica" -> "verifica dovuta " + Dates.relative(days); else -> "contratto " + (if ((days ?: 0) < 0) "scaduto " else "in scadenza ") + Dates.relative(days) },
            if (x.value > 0) money0(x.value) else "—", if (x.kind == "Contratto") "canone" else if (x.kind == "Sostituzione") "stimato" else "",
            if (x.kind != "Sostituzione" && (days ?: 0) < 0) "Scaduta" to Tone.Bad else null)
    }
}

@Composable private fun InsTile(t: InsTab, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalStatus.current.of(t.tone)
    val s by animateFloatAsState(if (on) 1f else 0.97f, Bouncy, label = "tile")
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.graphicsLayer { scaleX = s; scaleY = s }.clip(shape)
        .background(if (on) c.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, if (on) c.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
        .clickable(onClick = onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(t.icon, null, tint = c, modifier = Modifier.size(17.dp)) }
            Text(t.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
        RollingText(java.text.NumberFormat.getIntegerInstance(java.util.Locale.ITALY).format(t.count), MaterialTheme.typography.headlineMedium.tabular, Modifier.padding(top = 10.dp))
        Text(if (t.value > 0) money0(t.value) + " · " + t.note else t.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun InsRow(x: InsLine, modifier: Modifier, onClick: () -> Unit) {
    CardBox(modifier, onClick = onClick, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(x.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(x.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (x.sub.isNotBlank()) Text(x.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(x.amount, style = MaterialTheme.typography.titleMedium.tabular, maxLines = 1)
                x.badge?.let { (l, t) -> Pill(l, t, Modifier.padding(top = 4.dp)) } ?: if (x.amountNote.isNotBlank()) Text(x.amountNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) else Unit
            }
        }
    }
}

/** "Oggi da guardare" on the accounting summary: the five counters, a tap opens Priorità. */
fun androidx.compose.foundation.lazy.LazyListScope.insightsStrip(a: Archive, nav: NavHostController) {
    val ins = a.state.insights ?: return
    item(key = "ins-strip") {
        val tabs = tabsOf(ins)
        CardBox(Modifier.padding(bottom = 12.dp).appear(0), onClick = { nav.navigate("accOpp") { launchSingleTop = true } }, padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Oggi da guardare", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text("Priorità", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEach { t ->
                    val c = LocalStatus.current.of(t.tone)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.09f)).padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(java.text.NumberFormat.getIntegerInstance(java.util.Locale.ITALY).format(t.count), style = MaterialTheme.typography.titleMedium.tabular, color = c, maxLines = 1)
                        Text(t.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
