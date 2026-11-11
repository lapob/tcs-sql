package it.topcash.studio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.HorizontalDivider
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
import it.topcash.studio.data.DtrEvent
import it.topcash.studio.data.Tone
import it.topcash.studio.data.URegister
import it.topcash.studio.data.norm

private val dtabs = listOf("Tutti", "GE", "Errori", "Silenziose", "Certificati")

/** DTR-Service registers: electronic journal, certificate, last closing, errors. Tap a card for the full detail. */
@Composable fun DtrScreen(a: Archive, nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf("Tutti") }
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(a, tab, q) {
        val n = norm(q.trim())
        when (tab) { "GE" -> a.dtrGeSoon; "Errori" -> a.dtrErrors; "Silenziose" -> a.dtrSilent; "Certificati" -> a.dtrCertSoon; else -> a.registers }
            .filter { n.isEmpty() || it.search.contains(n) }
            .sortedBy { when (tab) { "Certificati" -> it.certDue; "Silenziose" -> it.seen; else -> it.geDue } ?: java.time.LocalDate.MAX }
    }
    val st = a.state.dtrStatus
    Page(null, "Registratori DTR", if (a.registers.isEmpty()) "DTR-Service" else "${a.registers.size} casse · ${a.dtrGeSoon.size} GE da rinnovare · ${a.dtrErrors.size} con errori", onBack = { nav.popBackStack() }) {
        if (st.error.isNotBlank()) item(key = "err") { CardBox(Modifier.padding(bottom = 10.dp)) { Text("Serve un nuovo accesso a DTR-Service", style = MaterialTheme.typography.titleSmall, color = LocalStatus.current.bad); Text(st.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item(key = "search") { SearchField(q, { q = it }, "Cerca cliente, P.IVA o matricola") }
        item(key = "tabs") { ChoiceChips(dtabs, tab, { tab = it }, Modifier.padding(vertical = 10.dp)) }
        if (a.registers.isEmpty()) item(key = "none") { Empty(Icons.Rounded.Router, "DTR-Service non collegato", "Si collega dall'app Windows sulla postazione di sincronizzazione (Registratori DTR → Collega DTR-Service).") }
        else if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.Router, "Nessun registratore", "Modifica i filtri o la ricerca.") }
        items(list.size.coerceAtMost(1000), key = { list[it].r.serial }) { i -> RegisterCard(list[i], Modifier.padding(bottom = 8.dp).animateItem(), showClient = true) { list[i].client?.let { c -> nav.openClient(c.code) } } }
        if (st.lastSync.isNotBlank()) item(key = "note") { Text("Dati letti da DTR-Service il ${Dates.short(Dates.parse(st.lastSync))}, in sola lettura.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable private fun toneColor(days: Long?, soon: Long): Color = when { days == null -> MaterialTheme.colorScheme.onSurface; days < 0 -> LocalStatus.current.bad; days <= soon -> LocalStatus.current.due; else -> MaterialTheme.colorScheme.onSurface }

@Composable private fun RegFact(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) = Column(Modifier.padding(bottom = 4.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable private fun Events(title: String, list: List<DtrEvent>) {
    if (list.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    list.take(6).forEach { e ->
        Row(Modifier.padding(vertical = 3.dp)) {
            Text(Dates.short(Dates.parse(e.at)), style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
            Text(e.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable fun RegisterCard(r: URegister, modifier: Modifier = Modifier, showClient: Boolean = true, onOpenClient: (() -> Unit)? = null) {
    var open by rememberSaveable(r.r.serial) { mutableStateOf(false) }
    CardBox(modifier, onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (showClient) r.name else r.r.serial, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (showClient) r.r.serial else null, r.machine?.m?.model?.takeIf { it.isNotBlank() }, r.r.tags.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                r.warn -> Pill("Errori", Tone.Bad)
                r.silent -> Pill("Silenziosa", Tone.Bad)
                r.geSoon -> Pill(if (r.lowZ) "GE quasi pieno" else "GE in scadenza", Tone.Due)
                r.certSoon -> Pill("Certificato", Tone.Due)
                else -> Pill("Regolare", Tone.Ok)
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            RegFact("Giornale elettronico", Dates.short(r.geDue) + (r.geDays?.let { " · " + Dates.relative(it) } ?: ""), toneColor(r.geDays, 60))
            RegFact("Ultima comunicazione", if (r.seen == null) "mai" else Dates.relative(r.seenDays), if (r.silent) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurface)
            r.remaining?.let { RegFact("Chiusure rimanenti", it.toString(), if (r.lowZ) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurface) }
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                val d = r.d
                if (!d?.warning.isNullOrBlank()) Text(d.warning, style = MaterialTheme.typography.bodySmall, color = LocalStatus.current.bad, modifier = Modifier.padding(bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RegFact("GE", "n. ${r.r.geNumber}" + (Dates.parse(d?.geInit)?.let { " · dal " + Dates.short(it) } ?: ""))
                    RegFact("Ultima chiusura Z", if (r.r.lastZ > 0) "n. ${r.r.lastZ}" else "—")
                    RegFact("Firmware", r.r.firmware.ifBlank { "—" })
                    RegFact("Certificato", Dates.short(r.certDue) + (r.certDays?.let { " · " + Dates.relative(it) } ?: ""), toneColor(r.certDays, 90))
                    RegFact("Verificazione periodica", Dates.short(Dates.parse(d?.lastVp)))
                    r.licence?.let { RegFact("Licenza ${it.name}", "${it.status} · fino al ${Dates.short(Dates.parse(it.to))}", toneColor(r.licDays, 45)) }
                    if (!d?.vat.isNullOrBlank()) RegFact("Partita IVA", d.vat)
                    if (r.via.isNotBlank()) RegFact("Collegato per", r.via)
                }
                if (!d?.address.isNullOrBlank()) RegFact("Indirizzo di installazione", d.address.removeSuffix(" IT"))
                if (!d?.extensions.isNullOrBlank()) RegFact("Estensioni GE", d.extensions)
                Events("Ultimi errori", d?.errors.orEmpty())
                Events("Ultimi interventi", d?.works.orEmpty())
                if (d == null) Text("Scheda di dettaglio non ancora letta: arriva con i prossimi aggiornamenti.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onOpenClient != null && r.client != null) OutlinedButton(onOpenClient, Modifier.padding(top = 10.dp)) { Text("Apri cliente") }
            }
        }
    }
}
