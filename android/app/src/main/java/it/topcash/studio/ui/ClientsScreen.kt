package it.topcash.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.navigation.NavHostController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Catalog
import it.topcash.studio.data.UClient
import it.topcash.studio.data.norm

private val filters = listOf("Tutti", "Preferiti", "In scadenza", "Con macchine", "Interventi aperti", "Solo Mastro")

@Composable fun ClientsScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Tutti") }
    val list = remember(a, q, filter) {
        val n = norm(q.trim())
        a.clients.filter { c ->
            (n.isEmpty() || c.search.contains(n)) && when (filter) {
                "Preferiti" -> c.meta.favorite; "In scadenza" -> c.status == "In scadenza"; "Con macchine" -> c.machines.isNotEmpty()
                "Interventi aperti" -> c.interventions.any { it.i.status != "Chiuso" }; "Solo Mastro" -> c.mastroOnly; else -> true
            }
        }
    }
    Page(vm, "Clienti", "${list.size} di ${a.clients.size}") {
        item(key = "search") {
            SearchField(q, { q = it }, "Cerca nome, P.IVA, telefono", Modifier.padding(top = 2.dp))
        }
        item(key = "filters") {
            ChoiceChips(filters, filter, { filter = it }, Modifier.padding(vertical = 10.dp))
        }
        if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.SearchOff, "Nessun cliente trovato", "Prova con un altro nome, una partita IVA o una matricola.") }
        items(list.size.coerceAtMost(1500), key = { list[it].code }) { i ->
            ClientRow(list[i], Modifier.padding(bottom = 8.dp).animateItem()) { nav.openClient(list[i].code) }
        }
    }
}

@Composable fun ClientRow(c: UClient, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CardBox(modifier, onClick = onClick, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.name, c.code, 46.dp, Modifier.shared("avatar-${c.code}"))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false).sharedBox("name-${c.code}"))
                    if (c.meta.favorite) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Star, null, tint = LocalStatus.current.due, modifier = Modifier.size(15.dp)) }
                }
                Text(listOf(c.city, c.phone, c.families.firstOrNull()?.let(Catalog::family)).filter { !it.isNullOrBlank() }.joinToString(" · ").ifBlank { "Cod. " + c.code.trimStart('0') },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Pill(c.status, it.topcash.studio.data.Archive.toneOf(c.status))
        }
    }
}
