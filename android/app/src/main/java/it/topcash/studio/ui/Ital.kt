package it.topcash.studio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UItal
import it.topcash.studio.data.norm

private val itabs = listOf("Tutti", "Da aggiornare", "Abbonamento", "DGFE", "Certificati")

/** Ital Retail OnePlus devices: subscription, firmware updates, electronic journal, certificate. Tap a card for the detail. */
@Composable fun ItalScreen(a: Archive, nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf("Tutti") }
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(a, tab, q) {
        val n = norm(q.trim())
        when (tab) { "Da aggiornare" -> a.italUpdate; "Abbonamento" -> a.ital.filter { it.noSub || it.subSoon }; "DGFE" -> a.italLow; "Certificati" -> a.ital.filter { it.certSoon }; else -> a.ital }
            .filter { n.isEmpty() || it.search.contains(n) }.sortedBy { it.name.lowercase() }
    }
    val st = a.state.italStatus
    Page(null, "Casse Ital Retail", if (a.ital.isEmpty()) "OnePlus · area rivenditore" else "${a.ital.size} casse · ${a.italUpdate.size} da aggiornare · ${a.italNoSub.size} senza abbonamento", onBack = { nav.popBackStack() }) {
        if (st.error.isNotBlank()) item(key = "err") { CardBox(Modifier.padding(bottom = 10.dp)) { Text("Serve un nuovo accesso a Ital Retail", style = MaterialTheme.typography.titleSmall, color = LocalStatus.current.bad); Text(st.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, matricola o firmware") }
        item(key = "tabs") { ChoiceChips(itabs, tab, { tab = it }, Modifier.padding(vertical = 10.dp)) }
        if (a.ital.isEmpty()) item(key = "none") { Empty(Icons.Rounded.PointOfSale, "Ital Retail non collegato", "Si collega dall'app Windows sulla postazione di sincronizzazione (Casse Ital Retail → Collega Ital Retail).") }
        else if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.PointOfSale, "Nessuna cassa", "Modifica i filtri o la ricerca.") }
        items(list.size, key = { list[it].r.serial }) { i -> ItalCard(list[i], Modifier.padding(bottom = 8.dp).animateItem(), showClient = true) { list[i].client?.let { c -> nav.openClient(c.code) } } }
        if (st.lastSync.isNotBlank()) item(key = "note") { Text("Dati letti da Ital Retail il ${Dates.short(Dates.parse(st.lastSync))}, in sola lettura.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable private fun tone(days: Long?, soon: Long): Color = when { days == null -> MaterialTheme.colorScheme.onSurface; days < 0 -> LocalStatus.current.bad; days <= soon -> LocalStatus.current.due; else -> MaterialTheme.colorScheme.onSurface }
@Composable private fun F(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) = Column(Modifier.padding(bottom = 4.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable fun ItalCard(r: UItal, modifier: Modifier = Modifier, showClient: Boolean = true, onOpenClient: (() -> Unit)? = null) {
    var open by rememberSaveable(r.r.serial) { mutableStateOf(false) }
    val d = r.d
    CardBox(modifier, onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (showClient) r.name else r.r.serial, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (showClient) r.r.serial else null, r.r.type.takeIf { it.isNotBlank() }, d?.firmware?.takeIf { it.isNotBlank() }?.let { "fw $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            when {
                r.noSub -> Pill("Senza abbonamento", Tone.Bad)
                r.lowDgfe -> Pill("DGFE quasi pieno", Tone.Bad)
                r.r.update.isNotBlank() -> Pill("Agg. ${r.r.update}", Tone.Due)
                r.subSoon -> Pill("Abbonamento", Tone.Due)
                r.certSoon -> Pill("Certificato", Tone.Due)
                else -> Pill("Regolare", Tone.Ok)
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            F("Abbonamento", if (r.noSub) "non attivo" else if (d?.autoRenew == true) "fino al ${Dates.short(r.subTo)} · rinnovo automatico" else Dates.short(r.subTo) + (r.subDays?.let { " · " + Dates.relative(it) } ?: ""),
                if (r.noSub) LocalStatus.current.bad else tone(if (d?.autoRenew == true) null else r.subDays, 45))
            if ((d?.dgfeFree ?: -1) >= 0) F("DGFE libero", "${d!!.dgfeFree}%", if (r.lowDgfe) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurface)
        }
        if ((d?.dgfeFree ?: -1) >= 0) LinearProgressIndicator({ (100 - d!!.dgfeFree) / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp), color = if (r.lowDgfe) LocalStatus.current.bad else MaterialTheme.colorScheme.primary)
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    F("Stato RT", d?.rtState?.ifBlank { "—" } ?: "—")
                    F("Chiusure fatte", if ((d?.closures ?: 0) > 0) "n. ${d!!.closures}" else "—")
                    F("Giornale", if ((d?.dgfeNumber ?: 0) > 0) "n. ${d!!.dgfeNumber} · ${d.dgfeSize}" else "—")
                    F("Certificato", Dates.short(r.certDue) + (r.certDays?.let { " · " + Dates.relative(it) } ?: ""), tone(r.certDays, 90))
                    F("Attivazione", Dates.short(Dates.parse(d?.activated)))
                    F("Dati della cassa", if (r.seen == null) "mai ricevuti" else Dates.relative(r.seenDays))
                    if (!d?.hw.isNullOrBlank()) F("Hardware", "v. ${d!!.hw}" + (d.wifi.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                    if (r.via.isNotBlank()) F("Collegato per", r.via)
                }
                d?.updates.orEmpty().takeIf { it.isNotEmpty() }?.let { ups ->
                    Text("Aggiornamenti installati", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    ups.reversed().take(6).forEach { u -> Text("${Dates.short(Dates.parse(u.at))} · dalla ${u.from} alla ${u.to}", style = MaterialTheme.typography.bodySmall) }
                }
                if (r.orders.isNotEmpty()) {
                    Text("Ordini", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    r.orders.take(6).forEach { o -> Text("${Dates.short(Dates.parse(o.date))} · ${o.kind} · n. ${o.number} · ${o.amount}", style = MaterialTheme.typography.bodySmall) }
                }
                if (d == null) Text("Scheda di dettaglio non ancora letta: arriva con il prossimo aggiornamento.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onOpenClient != null && r.client != null) OutlinedButton(onOpenClient, Modifier.padding(top = 10.dp)) { Text("Apri cliente") }
            }
        }
    }
}
