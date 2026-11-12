package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import it.topcash.studio.data.AccountingView.Companion.money0
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Family
import it.topcash.studio.data.SalesPeriod
import it.topcash.studio.data.SalesTotals
import it.topcash.studio.data.SalesView
import it.topcash.studio.data.Tone
import it.topcash.studio.data.norm
import java.time.LocalDate

private fun Family.icon(): ImageVector = when (this) {
    Family.Registers -> Icons.Rounded.PointOfSale; Family.Drawers -> Icons.Rounded.Savings; Family.Pc -> Icons.Rounded.Computer; Family.Dgfe -> Icons.Rounded.Memory
    Family.Printers -> Icons.Rounded.Print; Family.Scales -> Icons.Rounded.Scale; Family.Rolls -> Icons.Rounded.Receipt; Family.Contracts -> Icons.Rounded.VerifiedUser
    Family.Rentals -> Icons.Rounded.EventRepeat; Family.Software -> Icons.Rounded.Cloud; Family.Services -> Icons.Rounded.Build; Family.Other -> Icons.Rounded.AutoAwesome
}
private fun Family.color(dark: Boolean) = Color.hsl(hue, if (hue == 0f) 0.05f else 0.62f, if (dark) 0.62f else 0.50f)
private fun pctText(v: Double?) = v?.let { "${if (it >= 0) "▲" else "▼"} ${if (kotlin.math.abs(it) < 10) "%.1f".format(kotlin.math.abs(it)).replace('.', ',') else "%.0f".format(kotlin.math.abs(it))}%" }
private fun kShort(v: Double): String { val a = kotlin.math.abs(v); return when { a >= 1e6 -> "%.1fM".format(a / 1e6).replace('.', ','); a >= 1e3 -> "${kotlin.math.round(a / 1e3).toLong()}k"; else -> "${kotlin.math.round(a).toLong()}" } }
private fun qty(v: Double) = java.text.NumberFormat.getIntegerInstance(java.util.Locale.ITALY).format(kotlin.math.round(v).toLong())

/** Vendite: what was sold (by family and article), bought (by counter-account or supplier), the margin and what was lost. */
@Composable fun SalesScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    var period by rememberSaveable { mutableStateOf("Quest'anno") }
    var fam by rememberSaveable { mutableStateOf("") }
    var buy by rememberSaveable { mutableStateOf("Contropartita") }
    var q by rememberSaveable { mutableStateOf("") }
    val v = remember(a, period) { SalesView(a.state, a.acc, SalesPeriod(period)) }
    val c = v.cur; val p = v.prev
    val prods = remember(v, fam, q) { val n = norm(q.trim()); c.prod.values.filter { (fam.isEmpty() || it.f.label == fam) && (n.isEmpty() || it.search.contains(n)) }.sortedByDescending { it.rev } }
    Page(vm, "Vendite", v.period.label + syncedText(a.state.mastroSync)) {
        item(key = "period") { Column {
            ChoiceChips(v.periods, period, { period = it; fam = "" }, Modifier.padding(top = 4.dp))
            Text("Variazioni rispetto a ${v.period.cmpLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
        } }
        if (!c.has && !p.has) { item(key = "none") { Empty(Icons.Rounded.Insights, "Le vendite stanno arrivando", "Il server legge MastroSQL da solo ogni ora (sola lettura): vendite e conti compaiono qui alla prossima lettura.") }; return@Page }
        item(key = "kpi") {
            AnimatedContent(period, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) }, label = "kpi") { _ ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SaleTile("Emesso", c.rev, SalesView.pct(c.rev, p.rev), false, if (c.creditNotes > 0) "− ${money0(c.creditNotes)} note di credito" else "fatture ai clienti", Tone.Info, Modifier.weight(1f).appear(0))
                        SaleTile("Acquisti", c.f, SalesView.pct(c.f, p.f), true, "${c.bySup.size} fornitori", Tone.Accent, Modifier.weight(1f).appear(1))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SaleTile("Margine prodotti", c.margin, SalesView.pct(c.margin, p.margin), false, if (c.cRev > 0) "%.1f%% del venduto".format(c.margin / c.cRev * 100).replace('.', ',') else "nessun costo noto", if (c.margin >= 0) Tone.Ok else Tone.Bad, Modifier.weight(1f).appear(2))
                        SaleTile(if (c.result >= 0) "Utile contabile" else "Perdita contabile", c.result, SalesView.pct(c.result, p.result), false, "ricavi ${kShort(c.r)} · costi ${kShort(c.c)}", if (c.result >= 0) Tone.Ok else Tone.Bad, Modifier.weight(1f).appear(3))
                    }
                }
            }
        }
        item(key = "trend") { Trend(v, Modifier.padding(top = 10.dp).appear(4)) }
        val fams = Family.entries.mapNotNull { f -> c.fam[f]?.takeIf { it.rev != 0.0 || it.qty != 0.0 } }
        if (fams.isNotEmpty()) {
            item(key = "t-fam") { SectionTitle("Cosa hai venduto", Modifier.padding(top = 20.dp, bottom = 8.dp)) { if (fam.isNotEmpty()) androidx.compose.material3.TextButton({ fam = "" }) { Text("Tutte") } } }
            fams.chunked(2).forEachIndexed { r, row ->
                item(key = "fam$r") {
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { k, t -> FamilyCard(t, p.fam[t.f], fam == t.f.label, Modifier.weight(1f).appear(r * 2 + k)) { fam = if (fam == t.f.label) "" else t.f.label } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "loss") { Losses(c, a, v.period.label, Modifier.padding(top = 10.dp).appear(5)) { q = it; fam = "" } }
        item(key = "buy") { Buys(c, p, buy, { buy = it }, Modifier.padding(top = 10.dp).appear(6)) }
        item(key = "t-prod") { SectionTitle("${fam.ifEmpty { "Articoli venduti" }} · ${prods.size}", Modifier.padding(top = 20.dp, bottom = 8.dp)) }
        item(key = "search") { SearchField(q, { q = it }, "Cerca articolo o codice", Modifier.padding(bottom = 8.dp)) }
        if (prods.isEmpty()) item(key = "e-prod") { Empty(Icons.Rounded.Insights, "Nessun articolo", "Nessuna vendita corrisponde alla ricerca nel periodo.") }
        val top = prods.firstOrNull()?.rev?.coerceAtLeast(1.0) ?: 1.0
        items(prods.size.coerceAtMost(200), key = { "p" + prods[it].key }) { i -> val x = prods[i]; val b = p.prod[x.key]
            val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            CardBox(Modifier.padding(bottom = 8.dp).animateItem(), padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(4.dp)).background(x.f.color(dark))); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(x.name.ifBlank { x.code }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(x.code.takeIf { it.isNotBlank() }, x.f.label, if (x.cRev > 0) "margine ${money0(x.cRev - x.cost)}" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${qty(x.qty)} pz", style = MaterialTheme.typography.titleSmall.tabular)
                        Text(money0(x.rev), style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Bar((x.rev / top).toFloat(), MaterialTheme.colorScheme.primary.copy(alpha = .7f), Modifier.weight(1f).height(4.dp))
                    Spacer(Modifier.width(10.dp))
                    if (b == null) Pill("nuovo", Tone.Ok) else pctText(SalesView.pct(x.qty, b.qty))?.let { Pill(it, if (x.qty >= b.qty) Tone.Ok else Tone.Bad) }
                    if (x.below > 0.5) { Spacer(Modifier.width(6.dp)); Pill("−${money0(x.below)} sotto costo", Tone.Bad) }
                }
            }
        }
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable private fun Bar(f: Float, color: Color, modifier: Modifier, index: Int = 0) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val k by animateFloatAsState(if (shown) f.coerceIn(0.02f, 1f) else 0f, spring(dampingRatio = 0.9f, stiffness = (Spring.StiffnessLow - index * 10f).coerceAtLeast(60f)), label = "bar")
    Box(modifier.clip(RoundedCornerShape(99.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(k).clip(RoundedCornerShape(99.dp)).background(color))
    }
}

@Composable private fun SaleTile(title: String, value: Double, delta: Double?, inverse: Boolean, note: String, tone: Tone, modifier: Modifier) {
    val st = LocalStatus.current
    CardBox(modifier, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(st.of(tone))); Spacer(Modifier.size(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RollingText(money0(value), MaterialTheme.typography.titleLarge.tabular, Modifier.padding(top = 10.dp, bottom = 4.dp))
        pctText(delta)?.let { Pill(it, if ((delta!! >= 0) != inverse) Tone.Ok else Tone.Bad, Modifier.padding(bottom = 4.dp)) }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Revenues (blue) and costs (red) month by month, with what remained under each month. */
@Composable private fun Trend(v: SalesView, modifier: Modifier) {
    val st = LocalStatus.current
    val rows = v.period.months.map { m -> m to (v.cur.months[m]?.let { it.r to it.c } ?: (0.0 to 0.0)) }
    if (rows.none { it.second.first != 0.0 || it.second.second != 0.0 }) return
    val max = rows.maxOf { maxOf(it.second.first, it.second.second) }.coerceAtLeast(1.0)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    CardBox(modifier, padding = PaddingValues(16.dp)) {
        Text("Ricavi e costi per mese", style = MaterialTheme.typography.titleSmall)
        Text("Dalla contabilità di Mastro · sotto, quanto è rimasto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(150.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            rows.forEachIndexed { i, (m, rc) -> val (r, c) = rc
                val spec = spring<Float>(dampingRatio = 1f, stiffness = (Spring.StiffnessLow - i * 8f).coerceAtLeast(80f))
                val fr by animateFloatAsState(if (shown) (r.coerceAtLeast(0.0) / max).toFloat() else 0f, spec, label = "r$i")
                val fc by animateFloatAsState(if (shown) (c.coerceAtLeast(0.0) / max).toFloat() else 0f, spec, label = "c$i")
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                        listOf(fr to MaterialTheme.colorScheme.primary, fc to st.bad).forEach { (f, col) ->
                            Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleY = f.coerceAtLeast(0.01f); transformOrigin = TransformOrigin(0.5f, 1f) }
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Brush.verticalGradient(listOf(col, col.copy(alpha = .45f)))))
                        }
                    }
                    val net = r - c
                    Text((if (net < 0) "−" else "+") + kShort(net), style = MaterialTheme.typography.labelSmall.tabular, color = if (net < 0) st.bad else st.ok, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    Text(Dates.month(LocalDate.parse("$m-01")).take(3).lowercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable private fun FamilyCard(t: it.topcash.studio.data.FamTotal, b: it.topcash.studio.data.FamTotal?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val col = t.f.color(dark)
    CardBox(modifier.then(if (on) Modifier.border(2.dp, col, MaterialTheme.shapes.large) else Modifier), onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(col.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(t.f.icon(), null, Modifier.size(18.dp), tint = col) }
            Spacer(Modifier.width(8.dp))
            Text(t.f.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            RollingText(qty(t.qty), MaterialTheme.typography.headlineSmall.tabular.copy(fontWeight = FontWeight.Bold))
            Text(" " + t.f.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(money0(t.rev), style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val m = t.margin
        if (m != null && t.f.product) Text("%.0f%% margine".format(m), style = MaterialTheme.typography.bodySmall, color = if (m < 0) LocalStatus.current.bad else LocalStatus.current.ok)
        Bar(1f, col, Modifier.padding(vertical = 8.dp).fillMaxWidth().height(4.dp))
        if (b == null) Pill("nuovo", Tone.Ok) else pctText(SalesView.pct(t.qty, b.qty))?.let { Pill(it, if (t.qty >= b.qty) Tone.Ok else Tone.Bad) }
    }
}

@Composable private fun Losses(c: SalesTotals, a: Archive, label: String, modifier: Modifier, onProduct: (String) -> Unit) {
    val st = LocalStatus.current
    val items = (listOf("Vendite sotto costo" to c.below, "Note di credito emesse" to c.creditNotes) + c.lossAcc.toList()).filter { it.second > 0.5 }
    val old = a.acc.aging.lastOrNull()
    CardBox(modifier, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quanto hai perso", style = MaterialTheme.typography.titleSmall)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RollingText(money0(c.losses), MaterialTheme.typography.titleLarge.tabular, color = if (c.losses > 0) st.bad else Color.Unspecified)
        }
        if (items.isEmpty()) Text("Nessuna vendita sotto costo, nota di credito o perdita registrata nel periodo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        val max = items.maxOfOrNull { it.second } ?: 1.0
        items.forEachIndexed { i, (l, v) ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Bar((v / max).toFloat(), st.bad, Modifier.weight(1f).height(8.dp), i)
                Text(money0(v), style = MaterialTheme.typography.labelLarge.tabular, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (old != null && old.sum > 0.5) Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(st.due.copy(alpha = .14f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = st.due); Spacer(Modifier.width(10.dp))
            Text("Oggi ${money0(old.sum)} di rate scadute da oltre 90 giorni (${old.count}): rischiano di diventare perdite.", style = MaterialTheme.typography.bodySmall)
        }
        val worst = c.prod.values.filter { it.below > 0.5 }.sortedByDescending { it.below }.take(4)
        if (worst.isNotEmpty()) {
            Text("Venduti sotto costo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            worst.forEach { x ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(Modifier).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(x.name.ifBlank { x.code }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${x.code} · ${qty(x.qty)} pz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text("−" + money0(x.below), style = MaterialTheme.typography.labelLarge.tabular, color = st.bad)
                }
            }
        }
    }
}

@Composable private fun Buys(c: SalesTotals, p: SalesTotals, mode: String, setMode: (String) -> Unit, modifier: Modifier) {
    val acc = mode == "Contropartita"
    val list = (if (acc) c.byAcc.values else c.bySup.values).sortedByDescending { it.v }
    val top = list.take(8); val max = top.maxOfOrNull { it.v }?.coerceAtLeast(1.0) ?: 1.0
    CardBox(modifier, padding = PaddingValues(16.dp)) {
        Text("Acquisti", style = MaterialTheme.typography.titleSmall)
        Text("${money0(c.f)} di fatture fornitori", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceChips(listOf("Contropartita", "Fornitori"), mode, setMode, Modifier.padding(top = 8.dp))
        if (top.isEmpty()) Text("Nessuna fattura fornitore nel periodo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        AnimatedContent(mode, transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) }, label = "buys") { _ ->
            Column {
                top.forEachIndexed { i, x ->
                    val prev = (if (acc) p.byAcc else p.bySup)[x.key]
                    val sub = if (acc) x.sub else x.parts.entries.sortedByDescending { it.value }.take(2).joinToString(" · ") { it.key }
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(x.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(sub, if (x.n > 0) "${x.n} fatture" else "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money0(x.v), style = MaterialTheme.typography.labelLarge.tabular)
                                prev?.let { pr -> pctText(SalesView.pct(x.v, pr.v))?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (x.v <= pr.v) LocalStatus.current.ok else LocalStatus.current.bad) } }
                            }
                        }
                        Bar((x.v / max).toFloat(), MaterialTheme.colorScheme.primary, Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp), i)
                    }
                }
                val rest = list.drop(8).sumOf { it.v }
                if (rest > 0.5) Text("Altre ${list.size - 8} voci: ${money0(rest)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** " · aggiornato alle 16:46": when the server last read Mastro (it does it by itself every hour). */
private fun syncedText(s: it.topcash.studio.data.MastroSync?): String {
    val at = s?.at?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()) }.getOrNull() } ?: return ""
    val today = at.toLocalDate() == java.time.LocalDate.now()
    return " · aggiornato " + if (today) "alle %02d:%02d".format(at.hour, at.minute) else "il ${at.dayOfMonth} ${it.topcash.studio.data.Dates.month(at.toLocalDate())}"
}
