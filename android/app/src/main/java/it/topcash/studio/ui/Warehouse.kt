package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import it.topcash.studio.data.Article
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Tutti") }
    var scan by remember { mutableStateOf(false) }
    // office and administrators create articles here too (also straight from a scanned code that is not in the archive yet)
    val canManage = me?.role != "tecnico"
    var newArticle by remember { mutableStateOf<String?>(null) }
    val list = remember(wh, q, filter) {
        val n = norm(q.trim())
        wh.articles.filter { x ->
            x.a.active && (n.isEmpty() || x.search.contains(n)) &&
                when (filter) { "Disponibili" -> x.total > 0; "Sotto scorta" -> x.below.isNotEmpty(); "Esauriti" -> x.total <= 0; else -> true }
        }
    }
    Page(vm, "Magazzino", "${eur(wh.value)} · ${wh.belowMin.size} sotto scorta",
        actions = {
            if (canManage) IconButton({ newArticle = "" }) { Icon(Icons.Rounded.Add, "Nuovo articolo") }
            IconButton({ scan = true }) { Icon(Icons.Rounded.QrCodeScanner, "Scansiona codice") }
        },
        fab = { ExtendedFloatingActionButton(onClick = { onMovement("carico", null) }, icon = { Icon(Icons.Rounded.SwapHoriz, null) }, text = { Text("Movimento") }) }) {
        item(key = "search") { SearchField(q, { q = it }, "Cerca o scansiona un articolo") }
        item(key = "filters") { ChoiceChips(stockFilters, filter, { filter = it }, Modifier.padding(vertical = 8.dp)) }
        if (wh.articles.isEmpty()) item(key = "none") { Empty(Icons.Rounded.Inventory2, "Magazzino vuoto", if (canManage) "Tocca + in alto per inserire il primo prodotto." else "Gli articoli li crea l'ufficio.") }
        else if (list.isEmpty()) item(key = "empty") { Empty(Icons.Rounded.Inventory2, "Nessun articolo", "Modifica la ricerca o i filtri.") }
        if (canManage && q.isNotBlank() && list.isEmpty()) item(key = "create") {
            FilledTonalButton({ newArticle = q.trim() }, Modifier.fillMaxWidth().padding(top = 4.dp)) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("Crea «${q.trim().take(30)}» come nuovo articolo", Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        items(list.size.coerceAtMost(1500), key = { list[it].a.code }) { i -> ArticleRow(list[i], Modifier.padding(bottom = 8.dp).animateItem()) { nav.navigate("article/" + android.net.Uri.encode(list[i].a.code)) } }
    }
    if (scan) BarcodeScannerDialog(onResult = { code -> scan = false; val found = wh.find(code); if (found != null) nav.navigate("article/" + android.net.Uri.encode(found.a.code)) else { q = code; if (canManage) newArticle = code } }, onDismiss = { scan = false })
    newArticle?.let { seed -> ArticleEditorSheet(vm, a, null, seed, onDismiss = { newArticle = null }, onSaved = { code -> newArticle = null; q = ""; nav.navigate("article/" + android.net.Uri.encode(code)) }) }
}

@Composable private fun ArticleRow(x: UArticle, modifier: Modifier, onClick: () -> Unit) {
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
                val v = x.total
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
    val me by vm.me.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf(false) }
    if (edit) ArticleEditorSheet(vm, a, x.a, null, onDismiss = { edit = false }, onSaved = { edit = false })
    Page(vm, x.a.name, null, onBack = { nav.popBackStack() }, compactTitle = true,
        actions = { if (me?.role != "tecnico") IconButton({ edit = true }) { Icon(Icons.Rounded.Edit, "Modifica articolo") } }) {
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
                    Text(" ${x.a.unit} in magazzino", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("Valore ${eur(x.value)} · costo medio ${eur(x.cost)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).appear(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(Icons.AutoMirrored.Rounded.CallReceived, "Carico", { onMovement("carico", x.a.code) }, Modifier.weight(1f))
                ActionTile(Icons.AutoMirrored.Rounded.CallMade, "Scarico", { onMovement("scarico", x.a.code) }, Modifier.weight(1f))
            }
        }
        x.level?.let { l ->
            item(key = "level") {
                CardBox(Modifier.padding(top = 12.dp).appear(2)) {
                    Fact("Scorta minima", "${qty(l.min)} ${x.a.unit}" + if (x.below.isNotEmpty()) " · da riordinare" else "")
                    if (l.max > 0) Fact("Scorta massima", "${qty(l.max)} ${x.a.unit}")
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
            recent.forEach { m -> item(key = "mv" + m.m.id) { MovementRow(m.m, Modifier.padding(bottom = 8.dp)) } }
        }
    }
}

@Composable private fun MovementRow(m: Movement, modifier: Modifier) {
    val tone = when (m.type) { "carico" -> Tone.Ok; "scarico", "intervento" -> Tone.Due; "trasferimento" -> Tone.Info; else -> Tone.Accent }
    CardBox(modifier, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val sign = if (m.to.isNotBlank() && m.from.isBlank()) "+" else if (m.from.isNotBlank() && m.to.isBlank()) "−" else ""
                Text("${WarehouseView.typeLabel[m.type] ?: m.type} · $sign${qty(m.qty)}", style = MaterialTheme.typography.titleSmall)
                Text(listOf(m.user, if (m.serial.isNotBlank()) "matr. " + m.serial else "", m.notes).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(m.date.take(10).split("-").reversed().joinToString("/"), style = MaterialTheme.typography.labelMedium, color = LocalStatus.current.of(tone))
        }
    }
}

/** Load / unload in a bottom sheet: article by search or camera scan, quantity stepper. */
@Composable fun MovementSheet(vm: AppViewModel, a: Archive, initialType: String, articleCode: String?, onDismiss: () -> Unit) {
    val wh = a.wh
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(if (initialType == "scarico") "scarico" else "carico") }
    var article by remember { mutableStateOf(articleCode?.let { wh.byCode[it.uppercase()] }) }
    var query by remember { mutableStateOf(article?.a?.name.orEmpty()) }
    var amount by remember { mutableStateOf("1") }
    var serial by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    val n = amount.replace(',', '.').toDoubleOrNull() ?: 0.0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Movimento di magazzino", style = MaterialTheme.typography.headlineSmall)
            Gap(14.dp)
            val types = listOf("carico" to "Carico", "scarico" to "Scarico")
            SegmentedPicker(types.map { it.second }, types.indexOfFirst { it.first == type }, { type = types[it].first })
            Gap(14.dp)
            // article: type to search, or scan
            var menu by remember { mutableStateOf(false) }
            val suggestions = remember(query, wh) { val t = norm(query); if (t.length < 2 || article?.a?.name == query) emptyList() else wh.articles.filter { it.a.active && it.search.contains(t) }.take(8) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(menu && suggestions.isNotEmpty(), { menu = it }, Modifier.weight(1f)) {
                    OutlinedTextField(query, { query = it; article = wh.find(it); menu = true }, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        label = { Text("Articolo") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                        supportingText = { article?.let { x -> Text("${x.a.code} · in magazzino: ${qty(x.total)} ${x.a.unit}", maxLines = 1) } })
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
            if (article?.a?.serialized == true) OutlinedTextField(serial, { serial = it.take(60) }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Matricola / numero di serie") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            OutlinedTextField(notes, { notes = it.take(300) }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Note (DDT, cliente, motivo…)") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            Gap(16.dp)
            val ok = article != null && n > 0 && !saving
            Button({
                saving = true
                val m = Movement(type = type, article = article!!.a.code, qty = n, from = if (type == "carico") "" else WarehouseView.SEDE, to = if (type == "scarico") "" else WarehouseView.SEDE, serial = serial.trim(), notes = notes.trim())
                vm.saveMovements(listOf(m)) { saving = false; close() }.invokeOnCompletion { saving = false }
            }, Modifier.fillMaxWidth().height(54.dp), enabled = ok, shape = MaterialTheme.shapes.medium) { Text(if (saving) "Registrazione…" else "Registra") }
        }
    }
    if (scan) BarcodeScannerDialog(onResult = { code -> scan = false; val f = wh.find(code); if (f != null) { article = f; query = f.a.name } else { query = code; article = null } }, onDismiss = { scan = false })
}

/** Spare parts in the intervention editor: unloaded from the warehouse when the intervention is saved. */
@Composable fun PartsEditor(a: Archive, parts: List<it.topcash.studio.data.PartUse>, technician: String, readOnly: Boolean, onChange: (List<it.topcash.studio.data.PartUse>) -> Unit) {
    val wh = a.wh; var scan by remember { mutableStateOf(false) }; var query by remember { mutableStateOf("") }
    val van = WarehouseView.SEDE
    if (readOnly && parts.isEmpty()) return // a closed report without parts: no empty title
    Text("Ricambi usati", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    parts.forEachIndexed { i, p ->
        val x = wh.byCode[p.article.uppercase()]
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(x?.a?.name ?: p.article, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.serial.isNotBlank()) Text("matr. " + p.serial, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    sugg.forEach { s -> DropdownMenuItem(text = { Text(s.a.name + " · " + qty(s.total) + " " + s.a.unit + " in magazzino", maxLines = 1) }, onClick = { add(s) }) }
                }
            }
            FilledTonalIconButton({ scan = true }, Modifier.padding(start = 8.dp).size(52.dp)) { Icon(Icons.Rounded.QrCodeScanner, "Scansiona ricambio") }
        }
        if (scan) BarcodeScannerDialog(onResult = { code -> scan = false; wh.find(code)?.let(add) ?: run { query = code; menu = true } }, onDismiss = { scan = false })
    }
}

/** Create or change an article. A scanned barcode goes in the EAN field, any other text becomes the description;
 *  the code is proposed from the description and can be changed until the first save. */
@Composable fun ArticleEditorSheet(vm: AppViewModel, a: Archive, current: Article?, seed: String?, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val wh = a.wh; val isNew = current == null
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    val seedIsCode = seed != null && seed.length >= 6 && seed.all { it.isDigit() }
    var name by remember { mutableStateOf(current?.name ?: if (seedIsCode) "" else seed.orEmpty()) }
    var code by remember { mutableStateOf(current?.code ?: if (seedIsCode) "" else autoCode(seed.orEmpty())) }
    var codeTouched by remember { mutableStateOf(!isNew) }
    var barcode by remember { mutableStateOf(current?.barcode ?: if (seedIsCode) seed.orEmpty() else "") }
    var category by remember { mutableStateOf(current?.category.orEmpty()) }
    var unit by remember { mutableStateOf(current?.unit ?: "PZ") }
    fun num(d: Double?) = if (d == null || d == 0.0) "" else if (d % 1.0 == 0.0) d.toLong().toString() else d.toString().replace('.', ',')
    var cost by remember { mutableStateOf(num(current?.cost)) }
    var price by remember { mutableStateOf(num(current?.price)) }
    var vat by remember { mutableStateOf(num(current?.vat ?: 22.0)) }
    var supplier by remember { mutableStateOf(current?.supplier.orEmpty()) }
    var serialized by remember { mutableStateOf(current?.serialized ?: false) }
    var active by remember { mutableStateOf(current?.active ?: true) }
    var scan by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val taken = isNew && code.isNotBlank() && wh.byCode.containsKey(code.trim().uppercase())
    val ok = code.isNotBlank() && name.isNotBlank() && !taken && !saving
    fun d(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    val cats = remember(wh) { wh.articles.map { it.a.category }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(8) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isNew) "Nuovo articolo" else "Modifica articolo", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(name, { name = it; if (!codeTouched) code = autoCode(it) }, Modifier.fillMaxWidth(), label = { Text("Descrizione") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(code, { code = it.uppercase().take(40); codeTouched = true }, Modifier.weight(1f), label = { Text("Codice") }, singleLine = true, readOnly = !isNew, isError = taken,
                    supportingText = if (taken) { { Text("Codice già usato") } } else null, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false))
                OutlinedTextField(unit, { unit = it.uppercase().take(10) }, Modifier.width(96.dp), label = { Text("Unità") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(barcode, { barcode = it.trim() }, Modifier.weight(1f), label = { Text("Codice a barre (EAN)") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                FilledTonalIconButton({ scan = true }, Modifier.padding(start = 8.dp).size(52.dp)) { Icon(Icons.Rounded.QrCodeScanner, "Scansiona") }
            }
            OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("Categoria") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            if (cats.isNotEmpty() && category.isBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { cats.forEach { c -> FilterChip(false, { category = c }, label = { Text(c, maxLines = 1) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(cost, { cost = it }, Modifier.weight(1f), label = { Text("Costo €") }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(price, { price = it }, Modifier.weight(1f), label = { Text("Prezzo €") }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(vat, { vat = it }, Modifier.width(84.dp), label = { Text("IVA %") }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            OutlinedTextField(supplier, { supplier = it }, Modifier.fillMaxWidth(), label = { Text("Fornitore") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Gestito a matricola", style = MaterialTheme.typography.bodyLarge); Text("Ogni pezzo ha il suo numero di serie", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(serialized, { serialized = it })
            }
            if (!isNew) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Articolo attivo", style = MaterialTheme.typography.bodyLarge); Text("Spento, non compare più negli elenchi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(active, { active = it })
            }
            Button({
                saving = true
                val item = (current ?: Article(source = "app")).copy(code = code.trim().uppercase(), name = name.trim(), barcode = barcode, category = category.trim(), unit = unit.ifBlank { "PZ" },
                    cost = d(cost), price = d(price), vat = d(vat), supplier = supplier.trim(), serialized = serialized, active = active)
                vm.saveArticle(item, isNew) { scope.launch { sheet.hide() }.invokeOnCompletion { onSaved(item.code) } }.invokeOnCompletion { saving = false }
            }, Modifier.fillMaxWidth().height(52.dp), enabled = ok, shape = MaterialTheme.shapes.medium) { Text(if (saving) "Salvataggio…" else if (isNew) "Crea articolo" else "Salva") }
        }
    }
    if (scan) BarcodeScannerDialog(onResult = { c -> scan = false; barcode = c }, onDismiss = { scan = false })
}

/** A short code proposed from the description: words shortened, numbers kept ("Rotolo termico 57x40" -> "ROTOLOTERMIC57X40"). */
private fun autoCode(name: String): String {
    val w = name.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotBlank() }
    return w.joinToString("") { if (it.any(Char::isDigit)) it else it.take(if (w.size <= 2) 6 else 3) }.take(16)
}
