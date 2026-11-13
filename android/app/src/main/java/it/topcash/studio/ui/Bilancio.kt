package it.topcash.studio.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import it.topcash.studio.data.AccountingView.Companion.money0
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.BilGroup
import it.topcash.studio.data.Bilancio
import it.topcash.studio.data.Tone
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private fun monthName(k: String, long: Boolean = true) = runCatching { YearMonth.parse(k).let { (if (long) it.month.getDisplayName(TextStyle.FULL, Locale.ITALY) + " " + it.year else it.month.getDisplayName(TextStyle.SHORT, Locale.ITALY)) } }.getOrDefault(k)
private fun delta(now: Double, prev: Double): String = if (prev == 0.0) "" else ((now - prev) / kotlin.math.abs(prev) * 100).let { (if (it >= 0) "▲ " else "▼ ") + "%.1f".format(Locale.ITALY, kotlin.math.abs(it)) + "% · " }

/** Bilancio: income statement and balance sheet from Mastro's general ledger (computed by the server, same as the site). */
@Composable fun BilancioScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    val b = a.state.bilancio
    val year = java.time.LocalDate.now().year
    var tab by rememberSaveable { mutableStateOf("Riepilogo") }
    var sel by rememberSaveable { mutableStateOf(-1) }
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    Page(vm, "Bilancio", "Da Mastro · il bilancio ufficiale resta del commercialista", onBack = { nav.popBackStack() }) {
        if (b == null || !(b.hasIncome || b.hasBalance)) { item(key = "none") { Empty(Icons.Rounded.Insights, "Bilancio non ancora disponibile", "Compare alla prossima lettura di Mastro, ogni mezz’ora.") }; return@Page }
        val tabs = listOfNotNull("Riepilogo", if (b.hasIncome) "Conto economico" else null, if (b.hasBalance) "Stato patrimoniale" else null)
        item(key = "tabs") { ChoiceChips(tabs, tab.takeIf { it in tabs } ?: tabs[0], { tab = it }, Modifier.padding(top = 4.dp, bottom = 10.dp)) }
        if (b.note.isNotBlank()) item(key = "note") { Text(b.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp)) }
        val endPrev = "31/12/${year - 1}"
        when (tab.takeIf { it in tabs } ?: tabs[0]) {
            "Riepilogo" -> {
                val upTo = monthName(b.month, false)
                val tiles = buildList {
                    add(Triple(if (b.result >= 0) "Utile $year a $upTo" else "Perdita $year a $upTo", b.result, "${delta(b.result, b.resultPrev)}${money0(b.resultPrev)} nel ${year - 1}") to (if (b.result >= 0) Tone.Ok else Tone.Bad))
                    add(Triple("Ricavi", b.revenue, "${delta(b.revenue, b.revenuePrev)}${money0(b.revenuePrev)} nel ${year - 1}") to Tone.Info)
                    if (b.hasBalance) {
                        add(Triple("Cassa e banche", b.liquidity, "${money0(b.liquidityPrev)} al $endPrev") to (if (b.liquidity >= 0) Tone.Ok else Tone.Bad))
                        add(Triple("Posizione finanziaria netta", b.netFinancial, "debiti verso banche ${money0(b.financialDebt)}") to (if (b.netFinancial >= 0) Tone.Ok else Tone.Due))
                        add(Triple("Crediti verso clienti", b.receivables, if (b.dso > 0) "incassati in media in ${b.dso} giorni" else "") to Tone.Info)
                        add(Triple("Debiti verso fornitori", b.payables, if (b.dpo > 0) "pagati in media in ${b.dpo} giorni" else "") to Tone.Due)
                        add(Triple(if (b.vat < 0) "IVA da versare" else "Credito IVA", kotlin.math.abs(b.vat), "saldo dei conti IVA") to (if (b.vat < 0) Tone.Due else Tone.Ok))
                        add(Triple("Patrimonio netto", b.equity, "${money0(b.equityPrev)} al $endPrev") to Tone.Info)
                    }
                }
                tiles.chunked(2).forEachIndexed { r, pair ->
                    item(key = "k$r") {
                        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEachIndexed { i, (t, tone) -> BilTile(t.first, t.second, t.third, tone, Modifier.weight(1f).appear(r * 2 + i)) }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
                if (b.months.isNotEmpty()) item(key = "chart") { BilChart(b, if (sel in b.months.indices) sel else b.months.lastIndex, Modifier.padding(top = 4.dp).appear(8)) { sel = it } }
                item(key = "foot") { Text("Risultato provvisorio: ammortamenti, ratei, risconti e rimanenze finali li registra il commercialista a fine anno. Tutto il ${year - 1}: ricavi ${money0(b.revenueLastYear)}, risultato ${money0(b.resultLastYear)} prima delle chiusure.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
            }
            "Conto economico" -> {
                item(key = "ce-h") { Text("Da gennaio a ${monthName(b.month)}, confrontato con gli stessi mesi del ${year - 1}. Tocca una voce per vederne i conti.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp)) }
                listOf("Ricavi", "Costi").forEach { s -> section(s, b.income.filter { it.section == s }, "$year", "${year - 1}", open) { k -> open = if (k in open) open - k else open + k } }
                item(key = "ce-t") { TotalRow(if (b.result >= 0) "Utile del periodo" else "Perdita del periodo", b.result, b.resultPrev, if (b.result >= 0) Tone.Ok else Tone.Bad) }
            }
            else -> {
                item(key = "sp-h") { Text("Al ${monthName(b.month)} e al $endPrev. Tocca una voce per vederne i conti.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp)) }
                listOf("Immobilizzazioni", "Rimanenze", "Crediti", "Liquidità").forEach { s -> section(s, b.assets.filter { it.section == s }, "Oggi", endPrev, open) { k -> open = if (k in open) open - k else open + k } }
                item(key = "sp-a") { TotalRow("Totale attivo", b.totalAssets, b.totalAssetsPrev, Tone.Info) }
                listOf("Patrimonio netto", "Fondi e TFR", "Debiti finanziari", "Debiti").forEach { s -> section(s, b.liabilities.filter { it.section == s }, "Oggi", endPrev, open) { k -> open = if (k in open) open - k else open + k } }
                item(key = "sp-p") { TotalRow("Totale passivo e netto", b.totalLiabilities, b.totalLiabilitiesPrev, Tone.Info) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, groups: List<BilGroup>, now: String, prev: String, open: Set<String>, toggle: (String) -> Unit) {
    if (groups.isEmpty()) return
    item(key = "s-$title") {
        CardBox(Modifier.padding(bottom = 10.dp).animateContentSize(), padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(money0(groups.sumOf { it.amount }), style = MaterialTheme.typography.titleSmall.tabular)
                    Text("$prev: ${money0(groups.sumOf { it.prev })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            groups.forEach { g ->
                val key = g.section + "|" + g.code; val on = key in open; val many = g.accounts.size > 1
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val rot by animateFloatAsState(if (on) 90f else 0f, Bouncy, label = "chev")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (many) Modifier.clickable { toggle(key) } else Modifier).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (many) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).rotate(rot)) else Box(Modifier.size(18.dp))
                    Text(g.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money0(g.amount), style = MaterialTheme.typography.bodyMedium.tabular)
                        Text(money0(g.prev), style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (on && many) Column(Modifier.padding(start = 22.dp, top = 2.dp)) {
                    g.accounts.forEach { l ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            Text(money0(l.amount), style = MaterialTheme.typography.bodySmall.tabular)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun TotalRow(label: String, now: Double, prev: Double, tone: Tone) {
    val c = LocalStatus.current.of(tone)
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.09f)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(money0(now), style = MaterialTheme.typography.titleMedium.tabular, color = c)
            Text(money0(prev), style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun BilTile(title: String, value: Double, note: String, tone: Tone, modifier: Modifier) {
    val c = LocalStatus.current.of(tone)
    CardBox(modifier, padding = PaddingValues(14.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        RollingText(money0(value), MaterialTheme.typography.titleLarge.tabular.copy(color = c), Modifier.padding(top = 6.dp))
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Monthly result: green/red bars, last year's month as a thin tick; tap a month to read it. */
@Composable private fun BilChart(b: Bilancio, sel: Int, modifier: Modifier, onSel: (Int) -> Unit) {
    val st = LocalStatus.current
    val max = (b.months.map { kotlin.math.abs(it.result) } + b.monthsPrev.map { kotlin.math.abs(it.result) }).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val m = b.months[sel]; val p = b.monthsPrev.getOrNull(sel)
    CardBox(modifier, padding = PaddingValues(14.dp)) {
        Text("Risultato mese per mese", style = MaterialTheme.typography.titleSmall)
        Text(monthName(m.month).replaceFirstChar { it.uppercase() } + if (sel == b.months.lastIndex) " · in corso" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        RollingText(money0(m.result), MaterialTheme.typography.headlineSmall.tabular.copy(color = st.of(if (m.result >= 0) Tone.Ok else Tone.Bad)))
        Text("ricavi ${money0(m.revenue)} · costi ${money0(m.costs)}" + (if (b.hasBalance) " · cassa ${money0(m.liquidity)}" else "") + (p?.let { " · ${b.month.take(4).toInt() - 1}: ${money0(it.result)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(130.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            b.months.forEachIndexed { i, x ->
                val f by animateFloatAsState(1f, tween(450 + i * 35), label = "bar")
                val on = i == sel
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { onSel(i) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        val c = st.of(if (x.result >= 0) Tone.Ok else Tone.Bad)
                        Box(Modifier.fillMaxWidth(0.7f).fillMaxHeight((kotlin.math.abs(x.result) / max).toFloat().coerceIn(0.03f, 1f)).graphicsLayer { scaleY = f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(c.copy(alpha = if (on) 1f else 0.45f)))
                        b.monthsPrev.getOrNull(i)?.let { q -> Box(Modifier.fillMaxWidth(0.85f).fillMaxHeight((kotlin.math.abs(q.result) / max).toFloat().coerceIn(0.01f, 1f)), contentAlignment = Alignment.TopCenter) { Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))) } }
                    }
                    Text(monthName(x.month, false).take(3), style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (on) FontWeight.SemiBold else null, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                }
            }
        }
    }
}
