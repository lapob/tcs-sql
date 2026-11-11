package it.topcash.studio.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Movement
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UArticle
import it.topcash.studio.data.WarehouseView
import it.topcash.studio.data.WarehouseView.Companion.eur
import it.topcash.studio.data.WarehouseView.Companion.qty
import it.topcash.studio.data.norm
import kotlinx.coroutines.launch

private fun statusTone(s: String) = when (s) { "Disponibile" -> Tone.Ok; "Sotto scorta" -> Tone.Due; else -> Tone.Bad }
private val stockFilters = listOf("Tutti", "Disponibili", "Sotto scorta", "Esauriti")

@Composable fun WarehouseScreen(vm: AppViewModel, a: Archive, nav: NavHostController, onMovement: (String, String?) -> Unit) {
    val wh = a.wh
    val me by vm.me.collectAsStateWithLifecycle()
    val myVan = remember(wh, me) { wh.vanOf(me?.name) }
    var loc by rememberSaveable { mutableStateOf<String?>(null) }
    var locSet by rememberSaveable { mutableStateOf(false) }
    if (!locSet && myVan != null) { loc = myVan.id; locSet = true }
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Tutti") }
    var scan by remember { mutableStateOf(false) }
    val list = remember(wh, loc, q, filter) {
        val n = norm(q.trim())
        wh.articles.filter { x ->
            x.a.active && (n.isEmpty() || x.search.contains(n)) && (loc == null || x.qtyIn(loc) != 0.0 || x.levels.containsKey(loc) || n.isNotEmpty()) &&
                when (filter) { "Disponibili" -> x.qtyIn(loc) > 0; "Sotto scorta" -> if (loc == null) x.below.isNotEmpty() else x.below.containsKey(loc); "Esauriti" -> x.qtyIn(loc) <= 0; else -> true }
        }
    }
    Page(vm, "Magazzino", "${eur(wh.value)} · ${wh.belowMin.size} sotto scorta",
        actions = { IconButton({ scan = true }) { Icon(Icons.Rounded.QrCodeScanner, "Scansiona codice") } },
        fab = { ExtendedFloatingActionButton(onClick = { onMovement(if (myVan != null) "scarico" else "carico", null) }, icon = { Icon(Icons.Rounded.SwapHoriz, null) }, text = { Text("Movimento") }) }) {
        item(key = "search") { SearchField(q, { q = it }, "Cerca o scansiona un articolo") }
        item(key = "locs") {
            val opts = listOf<Pair<String?, String>>(null to "Tutti i magazzini") + wh.warehouses.filter { it.active }.map { it.id to (if (it.id == myVan?.id) "Il mio furgone" else it.name) }
            androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 10.dp).bleed(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(opts.size) { i -> val (id, label) = opts[i]
                    FilterChip(loc == id, { loc = id; locSet = true }, label = { Text(label, maxLines = 1) }, shape = MaterialTheme.shapes.small,
                        leadingIcon = if (loc == id) { { Icon(Icons.Rounded.Warehouse, null, Modifier.size(16.dp)) } } else null) }
            }
        }
        item(key = "filters") { ChoiceChips(stockFilters, filter, { filter = it }, Modifier.padding(vertical = 8.dp)) }
        if (wh.articles.isEmpty()) item(key = "none") { Empty(Icons.Rounded.Inventory2, "Magazzino vuoto", "Crea gli articoli dall'app Windows o importali da MastroSQL.") }
        else if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.Inventory2, "Nessun articolo", if (loc != null) "Niente in ${wh.name(loc!!)} con questi filtri." else "Modifica la ricerca o i filtri.") }
        items(list.size.coerceAtMost(1500), key = { list[it].a.code }) { i -> ArticleRow(list[i], loc, Modifier.padding(bottom = 8.dp).animateItem()) { nav.navigate("article/" + android.net.Uri.encode(list[i].a.code)) } }
    }
    if (scan) BarcodeScannerDialog(onResult = { code -> scan = false; val found = wh.find(code); if (found != null) nav.navigate("article/" + android.net.Uri.encode(found.a.code)) else q = code }, onDismiss = { scan = false })
}

@Composable private fun ArticleRow(x: UArticle, loc: String?, modifier: Modifier, onClick: () -> Unit) {
    CardBox(modifier, onClick = onClick, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(hueColor(x.a.category.ifBlank { x.a.code }, MaterialTheme.colorScheme.background.luminanceDark()).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Inventory2, null, tint = hueColor(x.a.category.ifBlank { x.a.code }, MaterialTheme.colorScheme.background.luminanceDark()), modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(x.a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(x.a.code, x.a.category).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                val v = x.qtyIn(loc)
                Text("${qty(v)} ${x.a.unit}", style = MaterialTheme.typography.titleMedium.tabular, color = if (v < 0) LocalStatus.current.bad else MaterialTheme.colorScheme.onSurface)
                if (x.status != "Disponibile") Text(x.status, style = MaterialTheme.typography.labelSmall, color = LocalStatus.current.of(statusTone(x.status)))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.luminanceDark() = (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.2f

@Composable fun ArticleScreen(vm: AppViewModel, a: Archive, code: String, nav: NavHostController, onMovement: (String, String?) -> Unit) {
    val wh = a.wh; val x = wh.byCode[code.uppercase()]
    if (x == null) { LoadingArchive(); return }
    val recent = remember(wh, code) { wh.movements.filter { it.m.article.equals(code, true) }.take(15) }
    Page(vm, x.a.name, null, onBack = { nav.popBackStack() }, compactTitle = true) {
        item(key = "head") {
            Column(Modifier.padding(top = 4.dp, bottom = 14.dp)) {
                Text(x.a.name, style = MaterialTheme.typography.headlineSmall)
                Text(listOf(x.a.code, x.a.barcode).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(x.status, statusTone(x.status)); if (x.a.serialized) Pill("A matricola", Tone.Info)
                }
            }
        }
        item(key = "total") {
            CardBox(Modifier.appear(0)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val shown by androidx.compose.animation.core.animateFloatAsState(x.total.toFloat(), label = "tot")
                    Text(qty(Math.round(shown * 1000) / 1000.0), style = MaterialTheme.typography.displaySmall.tabular)
                    Text(" ${x.a.unit} in totale", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("Valore ${eur(x.value)} · costo medio ${eur(x.cost)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).appear(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton({ onMovement("carico", x.a.code) }, Modifier.weight(1f).height(56.dp), shape = MaterialTheme.shapes.medium) { Icon(Icons.AutoMirrored.Rounded.CallReceived, null, Modifier.size(18.dp)); Text(" Carico") }
                FilledTonalButton({ onMovement("scarico", x.a.code) }, Modifier.weight(1f).height(56.dp), shape = MaterialTheme.shapes.medium) { Icon(Icons.AutoMirrored.Rounded.CallMade, null, Modifier.size(18.dp)); Text(" Scarico") }
                FilledTonalButton({ onMovement("trasferimento", x.a.code) }, Modifier.weight(1f).height(56.dp), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(4.dp)) { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)); Text(" Trasf.") }
            }
        }
        item(key = "stock-title") { SectionTitle("Dove si trova") }
        item(key = "stock") {
            CardBox(Modifier.appear(2)) {
                val rows = wh.warehouses.filter { it.active || (x.perWarehouse[it.id] ?: 0.0) != 0.0 }
                rows.forEachIndexed { i, w ->
                    val v = x.perWarehouse[w.id] ?: 0.0; val l = x.levels[w.id]
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (w.kind == "furgone") Icons.Rounded.SwapHoriz else Icons.Rounded.Warehouse, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(w.name, style = MaterialTheme.typography.bodyLarge)
                            if (l != null) Text("scorta min ${qty(l.min)} · max ${qty(l.max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${qty(v)} ${x.a.unit}", style = MaterialTheme.typography.titleMedium.tabular,
                            color = when { v < 0 -> LocalStatus.current.bad; l != null && v <= l.min -> LocalStatus.current.due; else -> MaterialTheme.colorScheme.onSurface })
                    }
                    if (i < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        item(key = "info") {
            SectionTitle("Dettagli")
            CardBox {
                if (x.a.category.isNotBlank()) Fact("Categoria", x.a.category)
                if (x.a.supplier.isNotBlank()) Fact("Fornitore", x.a.supplier + if (x.a.supplierCode.isNotBlank()) " · " + x.a.supplierCode else "")
                Fact("Prezzo di vendita", eur(x.a.price) + " · IVA " + qty(x.a.vat) + "%")
                if (x.a.notes.isNotBlank()) Fact("Note", x.a.notes)
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "mv-title") { SectionTitle("Ultimi movimenti") }
            recent.forEach { m -> item(key = "mv" + m.m.id) { MovementRow(m.m, wh, Modifier.padding(bottom = 8.dp)) } }
        }
    }
}

@Composable private fun MovementRow(m: Movement, wh: WarehouseView, modifier: Modifier) {
    val tone = when (m.type) { "carico" -> Tone.Ok; "scarico", "intervento" -> Tone.Due; "trasferimento" -> Tone.Info; else -> Tone.Accent }
    CardBox(modifier, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${WarehouseView.typeLabel[m.type] ?: m.type} · ${qty(m.qty)}", style = MaterialTheme.typography.titleSmall)
                Text(listOf(if (m.from.isNotBlank()) wh.name(m.from) else null, if (m.to.isNotBlank()) "→ " + wh.name(m.to) else if (m.from.isNotBlank()) "→ consumo" else null).filterNotNull().joinToString(" ") +
                    " · " + m.user + (if (m.serial.isNotBlank()) " · matr. " + m.serial else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(m.date.take(10).split("-").reversed().joinToString("/"), style = MaterialTheme.typography.labelMedium, color = LocalStatus.current.of(tone))
        }
    }
}

/** Load / unload / transfer in a bottom sheet: article by search or camera scan, quantity stepper, locations as chips. */
@Composable fun MovementSheet(vm: AppViewModel, a: Archive, initialType: String, articleCode: String?, onDismiss: () -> Unit) {
    val wh = a.wh; val me by vm.me.collectAsStateWithLifecycle()
    val myVan = wh.vanOf(me?.name)
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(initialType) }
    var article by remember { mutableStateOf(articleCode?.let { wh.byCode[it.uppercase()] }) }
    var query by remember { mutableStateOf(article?.a?.name.orEmpty()) }
    var amount by remember { mutableStateOf("1") }
    var from by remember { mutableStateOf(myVan?.id ?: "sede") }
    var to by remember { mutableStateOf(if (initialType == "trasferimento") (myVan?.id ?: wh.warehouses.firstOrNull { it.kind == "furgone" && it.active }?.id ?: "sede") else "sede") }
    var serial by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    if (type == "trasferimento" && from == to) to = wh.warehouses.firstOrNull { it.active && it.id != from }?.id ?: to
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    val n = amount.replace(',', '.').toDoubleOrNull() ?: 0.0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Movimento di magazzino", style = MaterialTheme.typography.headlineSmall)
            Gap(14.dp)
            val types = listOf("carico" to "Carico", "scarico" to "Scarico", "trasferimento" to "Trasferisci")
            SegmentedPicker(types.map { it.second }, types.indexOfFirst { it.first == type }, { type = types[it].first })
            Gap(14.dp)
            // article: type to search, or scan
            var menu by remember { mutableStateOf(false) }
            val suggestions = remember(query, wh) { val t = norm(query); if (t.length < 2 || article?.a?.name == query) emptyList() else wh.articles.filter { it.a.active && it.search.contains(t) }.take(8) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(menu && suggestions.isNotEmpty(), { menu = it }, Modifier.weight(1f)) {
                    OutlinedTextField(query, { query = it; article = wh.find(it); menu = true }, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        label = { Text("Articolo") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        supportingText = { article?.let { x -> Text("${x.a.code} · disponibili: " + wh.warehouses.filter { (x.perWarehouse[it.id] ?: 0.0) != 0.0 }.joinToString(" · ") { "${it.name} ${qty(x.perWarehouse[it.id]!!)}" }.ifBlank { "nessuna giacenza" }, maxLines = 2) } })
                    ExposedDropdownMenu(menu && suggestions.isNotEmpty(), { menu = false }, shape = RoundedCornerShape(18.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        suggestions.forEach { s -> DropdownMenuItem(text = { Column { Text(s.a.name, maxLines = 1); Text(s.a.code + " · " + qty(s.total) + " " + s.a.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                            onClick = { article = s; query = s.a.name; menu = false }) }
                    }
                }
                FilledTonalIconButton({ scan = true }, Modifier.padding(start = 8.dp).size(52.dp)) { Icon(Icons.Rounded.QrCodeScanner, "Scansiona") }
            }
            Gap(6.dp)
            Text("Quantità", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                FilledTonalIconButton({ amount = qty(maxOf(1.0, n - 1)) }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Remove, "Meno") }
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(10) }, Modifier.width(120.dp).padding(horizontal = 10.dp), singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.tabular.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), shape = MaterialTheme.shapes.medium,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                FilledTonalIconButton({ amount = qty(n + 1) }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Add, "Più") }
                Text("  " + (article?.a?.unit ?: "PZ"), style = MaterialTheme.typography.bodyLarge)
            }
            AnimatedContent(type, transitionSpec = { (fadeIn() + slideInVertically { it / 4 }) togetherWith (fadeOut() + slideOutVertically { -it / 4 }) }, label = "loc") { t ->
                Column {
                    if (t == "scarico" || t == "trasferimento") LocationPicker("Da", wh, from) { from = it }
                    if (t == "carico" || t == "trasferimento") LocationPicker(if (t == "carico") "In" else "A", wh, to, exclude = if (t == "trasferimento") from else null) { to = it }
                }
            }
            if (article?.a?.serialized == true) OutlinedTextField(serial, { serial = it.take(60) }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Matricola / numero di serie") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            OutlinedTextField(notes, { notes = it.take(300) }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Note (DDT, cliente, motivo…)") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            Gap(16.dp)
            val ok = article != null && n > 0 && !saving && (type != "trasferimento" || from != to)
            Button({
                saving = true
                val m = Movement(type = type, article = article!!.a.code, qty = n, from = if (type == "carico") "" else from, to = if (type == "scarico") "" else to, serial = serial.trim(), notes = notes.trim())
                vm.saveMovements(listOf(m)) { saving = false; close() }.invokeOnCompletion { saving = false }
            }, Modifier.fillMaxWidth().height(54.dp), enabled = ok, shape = MaterialTheme.shapes.medium) { Text(if (saving) "Registrazione…" else "Registra") }
        }
    }
    if (scan) BarcodeScannerDialog(onResult = { code -> scan = false; val f = wh.find(code); if (f != null) { article = f; query = f.a.name } else { query = code; article = null } }, onDismiss = { scan = false })
}

@Composable private fun LocationPicker(label: String, wh: WarehouseView, selected: String, exclude: String? = null, onSelect: (String) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            wh.warehouses.filter { it.active && it.id != exclude }.forEach { w ->
                val s by animateFloatAsState(if (selected == w.id) 1.04f else 1f, Bouncy, label = "loc")
                FilterChip(selected == w.id, { onSelect(w.id) }, label = { Text(w.name, maxLines = 1) }, modifier = Modifier.graphicsLayer { scaleX = s; scaleY = s },
                    leadingIcon = { Icon(if (w.kind == "furgone") Icons.Rounded.SwapHoriz else Icons.Rounded.Warehouse, null, Modifier.size(16.dp)) })
            }
        }
    }
}

/** Spare parts in the intervention editor: unloaded from the technician's van when the intervention is saved. */
@Composable fun PartsEditor(a: Archive, parts: List<it.topcash.studio.data.PartUse>, technician: String, readOnly: Boolean, onChange: (List<it.topcash.studio.data.PartUse>) -> Unit) {
    val wh = a.wh; var scan by remember { mutableStateOf(false) }; var query by remember { mutableStateOf("") }
    val van = wh.vanOf(technician)?.id ?: "sede"
    Text("Ricambi usati", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    parts.forEachIndexed { i, p ->
        val x = wh.byCode[p.article.uppercase()]
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(x?.a?.name ?: p.article, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("da " + wh.name(p.warehouse), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!readOnly) IconButton({ onChange(parts.toMutableList().also { l -> if (p.qty <= 1) l.removeAt(i) else l[i] = p.copy(qty = p.qty - 1) }) }) { Icon(Icons.Rounded.Remove, "Meno") }
            Text(qty(p.qty), style = MaterialTheme.typography.titleMedium.tabular)
            if (!readOnly) IconButton({ onChange(parts.toMutableList().also { l -> l[i] = p.copy(qty = p.qty + 1) }) }) { Icon(Icons.Rounded.Add, "Più") }
        }
    }
    if (!readOnly && wh.articles.isNotEmpty()) {
        var menu by remember { mutableStateOf(false) }
        val sugg = remember(query, wh) { val t = norm(query); if (t.length < 2) emptyList() else wh.articles.filter { it.a.active && it.search.contains(t) }.take(6) }
        val add: (UArticle) -> Unit = { x -> query = ""; menu = false
            val i = parts.indexOfFirst { it.article.equals(x.a.code, true) && it.warehouse == van }
            onChange(if (i >= 0) parts.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + 1) } else parts + it.topcash.studio.data.PartUse(x.a.code, 1.0, van, "")) }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(menu && sugg.isNotEmpty(), { menu = it }, Modifier.weight(1f)) {
                OutlinedTextField(query, { query = it; menu = true }, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable), placeholder = { Text("Aggiungi ricambio…", maxLines = 1) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                ExposedDropdownMenu(menu && sugg.isNotEmpty(), { menu = false }, shape = RoundedCornerShape(18.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    sugg.forEach { s -> DropdownMenuItem(text = { Text(s.a.name + " · " + qty(s.qtyIn(van)) + " " + s.a.unit + " sul furgone", maxLines = 1) }, onClick = { add(s) }) }
                }
            }
            FilledTonalIconButton({ scan = true }, Modifier.padding(start = 8.dp).size(52.dp)) { Icon(Icons.Rounded.QrCodeScanner, "Scansiona ricambio") }
        }
        if (scan) BarcodeScannerDialog(onResult = { code -> scan = false; wh.find(code)?.let(add) ?: run { query = code; menu = true } }, onDismiss = { scan = false })
    }
}
