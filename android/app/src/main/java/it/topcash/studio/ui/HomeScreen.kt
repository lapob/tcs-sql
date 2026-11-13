package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Dates
import it.topcash.studio.data.Tone
import java.time.LocalTime

@Composable fun HomeScreen(vm: AppViewModel, a: Archive, nav: NavHostController, onNewIntervention: () -> Unit, onOpenIntervention: (String) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    val h = LocalTime.now().hour
    val greet = if (h < 13) "Buongiorno" else if (h < 18) "Buon pomeriggio" else "Buonasera"
    val first = me?.name?.split(" ")?.firstOrNull().orEmpty()
    val deadlines = remember(a) { a.deadlines(-7, 60).take(8) }
    // under "Clienti" what matters today: how many customers are waiting for us (open work), else how many are followed
    val clientsWaiting = remember(a) { val n = a.openInterventions.map { it.i.clientCode }.distinct().size
        if (n > 0) (if (n == 1) "1 aspetta un intervento" else "$n aspettano un intervento") else "nessuno in attesa" }
    val open = remember(a) { a.openInterventions.sortedWith(compareBy({ it.i.priority != "Urgente" }, { it.i.priority != "Alta" }, { it.date })).take(5) }
    Page(vm, "$greet${if (first.isNotBlank()) ", $first" else ""}", "Top Cash Service · ${Dates.short(java.time.LocalDate.now())}",
        actions = { androidx.compose.material3.IconButton({ nav.navigate("search") { launchSingleTop = true } }) { Icon(Icons.Rounded.Search, "Cerca in tutto l'archivio") } }) {
        item(key = "update") { UpdateCard(vm) }
        item(key = "pending") { PendingBanner(vm) }
        item(key = "kpi") {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Kpi("Clienti", a.clients.size, clientsWaiting, Icons.Rounded.Groups, Tone.Accent, Modifier.weight(1f).appear(0)) { nav.switchTab("clients") }
                    Kpi("Licenze", a.currentLicenses.size, "attive · ${a.licenses.size - a.currentLicenses.size} storiche", Icons.Rounded.Key, Tone.Ok, Modifier.weight(1f).appear(1)) { nav.navigate("licenses") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Kpi("Scadenze", a.due30.size + a.verificationsDue.count { (it.verDays ?: 99) in 0..30 }, if (a.overdue.isNotEmpty()) "entro 30 gg · ${a.overdue.size} già scadute" else "entro 30 giorni", Icons.Rounded.Schedule, Tone.Due, Modifier.weight(1f).appear(2)) { nav.navigate("deadlines") }
                    Kpi("Interventi", a.openInterventions.size, "aperti · ${a.openInterventions.count { it.i.priority == "Urgente" }} urgenti", Icons.Rounded.Build, Tone.Info, Modifier.weight(1f).appear(3)) { nav.switchTab("interventions") }
                }
            }
        }
        if (me?.role != "tecnico" && a.staleMachines.isNotEmpty()) item(key = "stale") { StaleMachinesCard(a, nav, Modifier.padding(top = 12.dp).appear(4)) }
        item(key = "open-title") {
            SectionTitle("Da fare") {
                TextButton(onNewIntervention) { Icon(Icons.Rounded.Add, null, Modifier.padding(end = 4.dp)); Text("Nuovo") }
                TextButton({ nav.switchTab("interventions") }) { Text("Tutti") }
            }
        }
        if (open.isEmpty()) item(key = "open-empty") { CardBox { Text("Nessun intervento aperto. Ottimo lavoro!", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        open.forEachIndexed { i, x ->
            item(key = "o" + x.i.id) {
                InterventionRow(x, a, Modifier.padding(bottom = 10.dp).appear(i + 4).animateItem(), vm = vm) { onOpenIntervention(x.i.id) }
            }
        }
        item(key = "dl-title") { SectionTitle("Prossime scadenze") { TextButton({ nav.navigate("deadlines") }) { Text("Scadenziario") } } }
        if (deadlines.isEmpty()) item(key = "dl-empty") { CardBox { Text("Nessuna scadenza nei prossimi 60 giorni.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        deadlines.forEachIndexed { i, d ->
            item(key = "d$i-${d.kind}-${d.detail}") {
                CardBox(Modifier.padding(bottom = 10.dp).appear(i + 6).animateItem(), onClick = d.client?.let { c -> { nav.openClient(c.code) } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DateChip(d.date.dayOfMonth, Dates.month(d.date), if (d.days < 0) Tone.Bad else if (d.days <= 30) Tone.Due else Tone.Neutral)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(d.clientName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${d.kind} · ${d.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(Dates.relative(d.days), style = MaterialTheme.typography.labelMedium, color = LocalStatus.current.of(if (d.days < 0) Tone.Bad else if (d.days <= 30) Tone.Due else Tone.Neutral))
                    }
                }
            }
        }
        item(key = "spacer") { Spacer(Modifier.width(1.dp).padding(bottom = 24.dp)) }
    }
}
