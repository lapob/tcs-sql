package it.topcash.studio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Print
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
import it.topcash.studio.data.URch
import it.topcash.studio.data.norm

private val rtabs = listOf("Tutti", "Verificazioni", "DGFE", "Certificati", "Senza chiusure", "Da aggiornare")

/** RCH XDealer devices: periodic verification, electronic journal, daily closures, certificate. Tap a card for the detail. */
@Composable fun RchScreen(a: Archive, nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf("Tutti") }
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(a, tab, q) {
        val n = norm(q.trim())
        when (tab) { "Verificazioni" -> a.rchVer; "DGFE" -> a.rchLow; "Certificati" -> a.rchLive.filter { it.certSoon }; "Senza chiusure" -> a.rchSilent; "Da aggiornare" -> a.rchUpdate; else -> a.rch }
            .filter { n.isEmpty() || it.search.contains(n) }.sortedBy { it.name.lowercase() }
    }
    val st = a.state.rchStatus
    Page(null, "Portale RCH", if (a.rch.isEmpty()) "XDealer · area rivenditore" else "${a.rchLive.size} registratori · ${a.rchVer.size} verificazioni · ${a.rchLow.size} DGFE quasi pieni", onBack = { nav.popBackStack() }) {
        if (st.error.isNotBlank()) item(key = "err") { CardBox(Modifier.padding(bottom = 10.dp)) { Text("Serve un nuovo accesso a RCH XDealer", style = MaterialTheme.typography.titleSmall, color = LocalStatus.current.bad); Text(st.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, P.IVA o matricola") }
        item(key = "tabs") { ChoiceChips(rtabs, tab, { tab = it }, Modifier.padding(vertical = 10.dp)) }
        if (a.rch.isEmpty()) item(key = "none") { Empty(Icons.Rounded.Print, "RCH XDealer non collegato", "Si collega dall'app Windows sulla postazione di sincronizzazione (Portale RCH → Collega RCH XDealer).") }
        else if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.Print, "Nessun registratore", "Modifica i filtri o la ricerca.") }
        items(list.size, key = { list[it].r.serial }) { i -> RchCard(list[i], Modifier.padding(bottom = 8.dp).animateItem(), showClient = true) { list[i].client?.let { c -> nav.openClient(c.code) } } }
        if (st.lastSync.isNotBlank()) item(key = "note") { Text("Dati letti da RCH XDealer il ${Dates.short(Dates.parse(st.lastSync))}, in sola lettura.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable private fun tone(days: Long?, soon: Long): Color = when { days == null -> MaterialTheme.colorScheme.onSurface; days < 0 -> LocalStatus.current.bad; days <= soon -> LocalStatus.current.due; else -> MaterialTheme.colorScheme.onSurface }
@Composable private fun F(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) = Column(Modifier.padding(bottom = 4.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable fun RchCard(r: URch, modifier: Modifier = Modifier, showClient: Boolean = true, onOpenClient: (() -> Unit)? = null) {
    var open by rememberSaveable(r.r.serial) { mutableStateOf(false) }
    CardBox(modifier, onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (showClient) r.name else r.r.serial, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (showClient) r.r.serial else null, r.r.type.takeIf { it.isNotBlank() }, r.r.firmware.takeIf { it.isNotBlank() }?.let { "fw $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            when {
                r.off -> Pill(r.r.state, Tone.Info)
                r.verDays != null && r.verDays < 0 -> Pill("Verificazione scaduta", Tone.Bad)
                r.lowDgfe -> Pill("DGFE quasi pieno", Tone.Bad)
                r.verSoon -> Pill("Verificazione", Tone.Due)
                r.r.update.isNotBlank() -> Pill("Agg. ${r.r.update}", Tone.Due)
                r.certSoon -> Pill("Certificato", Tone.Due)
                r.silent -> Pill("Nessuna chiusura", Tone.Due)
                else -> Pill("Regolare", Tone.Ok)
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            F("Prossima verificazione", if (r.verNext == null) "—" else Dates.short(r.verNext) + (r.verDays?.let { " · " + Dates.relative(it) } ?: ""), tone(r.verDays, 60))
            if (r.r.dgfeFree >= 0) F("DGFE libero", "${r.r.dgfeFree}%", if (r.lowDgfe) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurface)
        }
        if (r.r.dgfeFree >= 0) LinearProgressIndicator({ (100 - r.r.dgfeFree) / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp), color = if (r.lowDgfe) LocalStatus.current.bad else MaterialTheme.colorScheme.primary)
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    F("Ultima chiusura", if (r.zDate == null) "—" else Dates.short(r.zDate) + (if (r.r.zNumber > 0) " · n. ${r.r.zNumber}" else "") + (r.zDays?.let { " · " + Dates.relative(it) } ?: ""), if (r.silent) LocalStatus.current.due else MaterialTheme.colorScheme.onSurface)
                    F("Certificato", if (r.certDue == null) "—" else Dates.short(r.certDue) + (r.certDays?.let { " · " + Dates.relative(it) } ?: ""), tone(r.certDays, 90))
                    F("Giornale", if (r.r.dgfeNumber > 0) "n. ${r.r.dgfeNumber}" else "—")
                    F("Contratto", if (r.r.contract) "attivo" else "non presente")
                    F("Attivazione", Dates.short(Dates.parse(r.r.activated)))
                    if (r.r.state.isNotBlank()) F("Stato sul portale", r.r.state)
                    if (r.r.client.isNotBlank()) F("Cliente sul portale", r.r.client + (r.r.vat.takeIf { it.isNotBlank() }?.let { " · P.IVA $it" } ?: ""))
                    if (r.via.isNotBlank()) F("Collegato per", r.via)
                }
                if (onOpenClient != null && r.client != null) OutlinedButton(onOpenClient, Modifier.padding(top = 10.dp)) { Text("Apri cliente") }
            }
        }
    }
}
