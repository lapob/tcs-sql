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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TaskAlt
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
import it.topcash.studio.data.Analysis
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Tone
import it.topcash.studio.data.norm

private class AnaTab(val key: String, val label: String, val icon: ImageVector, val value: String, val note: String, val tone: Tone)
private class AnaLine(val key: String, val code: String, val title: String, val sub: String, val amount: String, val note: String, val badge: Pair<String, Tone>?)

private fun pct(a: Double, b: Double): String = if (b > 0) ((a - b) / b * 100).let { (if (it > 0) "+" else "") + it.toInt() + "%" } else "nuovo"
private fun dec(v: Double) = java.text.DecimalFormat("0.#", java.text.DecimalFormatSymbols(java.util.Locale.ITALY)).format(v)
private fun int(n: Int) = java.text.NumberFormat.getIntegerInstance(java.util.Locale.ITALY).format(n)

private fun tabsOf(x: Analysis): List<AnaTab> {
    val up = x.costs.count { it.impact > 0 }
    return listOf(
        AnaTab("cash", "Incassi previsti", Icons.Rounded.Euro, money0(x.forecastIn), "13 settimane · da pagare ${money0(x.forecastOut)}", if (x.forecastIn >= x.forecastOut) Tone.Ok else Tone.Due),
        AnaTab("abc", "Clienti ABC", Icons.Rounded.Group, int(x.abcClasses.firstOrNull { it.`class` == "A" }?.clients ?: 0), "clienti A: l'80% del fatturato", Tone.Info),
        AnaTab("agents", "Agenti", Icons.Rounded.Star, int(x.agents.size), x.agents.firstOrNull()?.let { "primo: ${it.name}" } ?: "nessun dato", Tone.Info),
        AnaTab("techs", "Tecnici", Icons.Rounded.Build, int(x.techs.size), "lavori chiusi in 12 mesi", Tone.Info),
        AnaTab("machines", "Macchine", Icons.Rounded.PointOfSale, int(x.models.size), "modelli con valore annuo", Tone.Info),
        AnaTab("disc", "Sconti", Icons.Rounded.LocalOffer, money0(x.discountTotal), "sotto il prezzo abituale", if (x.discountTotal > 0) Tone.Due else Tone.Ok),
        AnaTab("costs", "Costi fornitori", Icons.Rounded.Inventory2, int(up), if (x.costImpact > 0) "rincari: ${money0(x.costImpact)} l'anno" else "nessun rincaro", if (up > 0) Tone.Bad else Tone.Ok),
    )
}

/** Analisi: cash forecast, ABC, agents, technicians, registers, discounts and supplier costs (computed by the server, same as the site). */
@Composable fun AnalysisScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    val x = a.state.analysis
    var tab by rememberSaveable { mutableStateOf("cash") }
    var sub by rememberSaveable { mutableStateOf("A") }
    var q by rememberSaveable { mutableStateOf("") }
    Page(vm, "Analisi", "Calcolate da Mastro a ogni lettura", onBack = { nav.popBackStack() }) {
        if (x == null) { item(key = "none") { Empty(Icons.Rounded.Insights, "Le analisi stanno arrivando", "Si calcolano alla prossima lettura di Mastro, ogni mezz’ora.") }; return@Page }
        val tabs = tabsOf(x)
        item(key = "tiles") {
            LazyRow(Modifier.padding(top = 4.dp, bottom = 12.dp).bleed(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(tabs.size) { k -> val t = tabs[k]; AnaTileView(t, tab == t.key, Modifier.width(176.dp).appear(k)) { tab = t.key; q = ""; sub = when (t.key) { "abc" -> "A"; "disc" -> "Per cliente"; "machines" -> "Per modello"; else -> "" } } }
            }
        }
        val subs = when (tab) { "abc" -> listOf("A", "B", "C"); "disc" -> listOf("Per cliente", "Per articolo"); "machines" -> listOf("Per modello", "Per registratore"); else -> emptyList() }
        if (subs.isNotEmpty()) item(key = "subs") {
            val labels = if (tab == "abc") subs.map { c -> x.abcClasses.firstOrNull { it.`class` == c }.let { "Classe $c · ${int(it?.clients ?: 0)} · ${dec(it?.share ?: 0.0)}%" } } else subs
            val cur = subs.indexOf(sub).coerceAtLeast(0)
            ChoiceChips(labels, labels[cur], { s -> sub = subs[labels.indexOf(s)] }, Modifier.padding(bottom = 8.dp))
        }
        if (tab == "cash") item(key = "cash") { CashChart(x, Modifier.padding(bottom = 12.dp).appear(1)) }
        else item(key = "search") { SearchField(q, { q = it }, "Cerca…", Modifier.padding(bottom = 10.dp)) }
        val n = norm(q.trim())
        val lines = linesOf(x, tab, sub).filter { n.isEmpty() || norm(it.title + " " + it.sub + " " + it.code).contains(n) }
        item(key = "intro") {
            AnimatedContent(tab + sub, transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally { it / 12 }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally { -it / 12 }) }, label = "ana") { _ ->
                Column {
                    Text(intro(tab, x), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                    if (lines.isEmpty() && tab != "cash") Empty(Icons.Rounded.TaskAlt, if (n.isEmpty()) "Niente da mostrare" else "Nessun risultato", if (n.isEmpty()) "Nessun dato per questo elenco." else "Modifica la ricerca.")
                }
            }
        }
        items(lines.size.coerceAtMost(400), key = { lines[it].key }) { k ->
            val l = lines[k]; val c = if (l.code.isNotBlank()) a.acc.client(l.code) else null
            AnaRowView(l, Modifier.padding(bottom = 8.dp).animateItem()) { if (c != null) nav.openClient(c.code) }
        }
    }
}

private fun intro(tab: String, x: Analysis) = when (tab) {
    "cash" -> "Rate dei clienti pesate su come pagano oggi (chi è in regola incassa alla scadenza, chi è in ritardo più tardi e non sempre); pagamenti ai fornitori alla scadenza, quelli già scaduti nella prima settimana. Dagli scaduti dei clienti si stimano ${money0(x.overdueExpected)} in arrivo."
    "abc" -> "Ultimi 12 mesi al netto delle note di credito. A: i clienti che fanno l'80% del fatturato; B: il 15% successivo; C: il resto."
    "agents" -> "Fatturato degli ultimi 12 mesi sulle fatture: prima gli agenti (si scelgono in Utenti), poi chi altro compare sulle fatture, con margine e sconti."
    "techs" -> "Interventi chiusi negli ultimi 12 mesi con il loro importo; le verifiche periodiche sono contate a parte."
    "machines" -> "Canone riportato all'anno, interventi chiusi sulla macchina in 12 mesi e verificazione (metà all'anno). Stima: le fatture non indicano la macchina."
    "disc" -> "Vendite pagate almeno il 10% meno del prezzo abituale dello stesso articolo (solo articoli a prezzo stabile, niente contratti o servizi)."
    else -> "Costi d'acquisto cambiati negli ultimi 12 mesi. Impatto: quanto costa in più su quello che hai venduto in un anno. Se il margine scende, aggiorna il prezzo di vendita."
}

private fun linesOf(x: Analysis, tab: String, sub: String): List<AnaLine> = when (tab) {
    "abc" -> x.abc.filter { it.`class` == sub }.map { c -> AnaLine("a" + c.clientCode, c.clientCode, c.name, "${c.rank}° · ${dec(c.share)}% del fatturato" + if (c.prev > 0) " · anno prima ${money0(c.prev)}" else "", money0(c.revenue), pct(c.revenue, c.prev), null) }
    "agents" -> x.agents.map { g -> AnaLine("g" + g.code, "", g.name, "${int(g.clients)} clienti · margine ${money0(g.margin)} (${dec(g.marginPct)}%) · ${g.topFamily}" + if (g.discounts > 0) " · sconti ${money0(g.discounts)}" else "", money0(g.revenue), pct(g.revenue, g.prev), if (g.agent) "Agente" to Tone.Accent else null) }
    "techs" -> x.techs.map { t -> AnaLine("t" + t.name, "", t.name, "${if (t.agent) "anche agente · " else ""}${int(t.jobs)} interventi · ${int(t.verifications)} verifiche · ${int(t.clients)} clienti", money0(t.amount), if (t.jobs > 0) money0(t.amount / t.jobs) + " a intervento" else "", if (t.open > 0) "${t.open} aperti" to (if (t.open > 40) Tone.Due else Tone.Info) else null) }
    "machines" -> if (sub == "Per registratore") x.machines.map { m -> AnaLine("m" + m.id, m.clientCode, m.name, listOf(m.model, if (m.serial.isNotBlank()) "matr. ${m.serial}" else "", "canone ${money0(m.contract)}", "interventi ${money0(m.interventions)}").filter { it.isNotBlank() }.joinToString(" · "), money0(m.total), "l'anno", null) }
        else x.models.map { m -> AnaLine("o" + m.model, "", m.model, "${int(m.machines)} attivi · in media ${money0(m.average)} l'anno", money0(m.total), "l'anno", null) }
    "disc" -> if (sub == "Per articolo") x.discountArticles.map { d -> AnaLine("d" + d.key, "", d.name, "${d.key} · ${d.sub} · ${int(d.lines)} vendite", money0(d.given), "di sconto", null) }
        else x.discountClients.map { d -> AnaLine("d" + d.key, d.key, d.name, "agente ${d.sub} · ${int(d.lines)} vendite scontate per ${money0(d.revenue)}", money0(d.given), "di sconto", null) }
    "costs" -> x.costs.map { c -> AnaLine("c" + c.code, "", c.name, listOf(c.supplier.ifBlank { "fornitore non indicato" }, Dates.short(Dates.parse(c.date)), "costo ${money0(c.old)} → ${money0(c.new)}", if (c.price > 0) "margine ${dec(c.marginBefore)}% → ${dec(c.marginNow)}%" else "").filter { it.isNotBlank() }.joinToString(" · "),
        if (c.impact != 0.0) (if (c.impact > 0) "+" else "−") + money0(kotlin.math.abs(c.impact)) else "—", if (c.qty12 > 0) "sull'anno" else "non venduto",
        (if (c.pct > 0) "+" else "") + dec(c.pct) + "%" to (if (c.new > c.old) (if (c.marginNow < 15) Tone.Bad else Tone.Due) else Tone.Ok)) }
    else -> emptyList()
}

/** Thirteen weeks: collections (green) and payments (red), net of the week under each pair. */
@Composable private fun CashChart(x: Analysis, modifier: Modifier) {
    val st = LocalStatus.current
    val max = (x.forecast.maxOfOrNull { maxOf(it.`in`, it.out) } ?: 1.0).coerceAtLeast(1.0)
    CardBox(modifier, padding = PaddingValues(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("Incassi", x.forecastIn, Tone.Ok), Triple("Pagamenti", x.forecastOut, Tone.Bad), Triple("Saldo", x.forecastIn - x.forecastOut, if (x.forecastIn >= x.forecastOut) Tone.Ok else Tone.Bad)).forEach { (l, v, t) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(st.of(t).copy(alpha = 0.08f)).padding(8.dp)) {
                    Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text((if (v < 0) "−" else "") + money0(kotlin.math.abs(v)), style = MaterialTheme.typography.titleSmall.tabular, color = st.of(t), maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(120.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            x.forecast.forEachIndexed { i, w ->
                val f by animateFloatAsState(1f, tween(500 + i * 30), label = "bar")
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f).fillMaxHeight((w.`in` / max).toFloat().coerceIn(0.02f, 1f)).graphicsLayer { scaleY = f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }.clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(st.of(Tone.Ok)))
                    Box(Modifier.weight(1f).fillMaxHeight((w.out / max).toFloat().coerceIn(0.02f, 1f)).graphicsLayer { scaleY = f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }.clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(st.of(Tone.Bad).copy(alpha = 0.75f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("Questa settimana", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            x.forecast.lastOrNull()?.let { Text(Dates.short(Dates.parse(it.start)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    x.forecast.forEachIndexed { i, w ->
        if (w.`in` == 0.0 && w.out == 0.0) return@forEachIndexed
        CardBox(Modifier.padding(top = 8.dp), padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (i == 0) "Questa settimana" else "Dal " + Dates.short(Dates.parse(w.start)), style = MaterialTheme.typography.titleSmall)
                    Text("incassi ${money0(w.`in`)} · pagamenti ${money0(w.out)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text((if (w.net < 0) "−" else "+") + money0(kotlin.math.abs(w.net)), style = MaterialTheme.typography.titleSmall.tabular, color = st.of(if (w.net < 0) Tone.Bad else Tone.Ok))
            }
        }
    }
}

@Composable private fun AnaTileView(t: AnaTab, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
        RollingText(t.value, MaterialTheme.typography.headlineSmall.tabular, Modifier.padding(top = 10.dp))
        Text(t.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun AnaRowView(x: AnaLine, modifier: Modifier, onClick: () -> Unit) {
    CardBox(modifier, onClick = onClick, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(x.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (x.sub.isNotBlank()) Text(x.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(x.amount, style = MaterialTheme.typography.titleMedium.tabular, maxLines = 1)
                x.badge?.let { (l, t) -> Pill(l, t, Modifier.padding(top = 4.dp)) } ?: if (x.note.isNotBlank()) Text(x.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) else Unit
            }
        }
    }
}
