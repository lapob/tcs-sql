package it.topcash.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Catalog
import it.topcash.studio.data.ClientEdit
import it.topcash.studio.data.Dates
import it.topcash.studio.data.LicenseEdit
import it.topcash.studio.data.Tone
import it.topcash.studio.data.UClient
import it.topcash.studio.data.ULicense
import it.topcash.studio.data.UserCreate
import it.topcash.studio.data.UserPatch
import it.topcash.studio.data.UserRow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/* Administrator tools: customers (create / edit / delete / restore), licences (correct expiry, note, hide), users. */

private val clientFields = listOf("name" to "Ragione sociale", "vat" to "Partita IVA", "taxCode" to "Codice fiscale", "phone" to "Telefono", "email" to "Email",
    "pec" to "PEC", "contact" to "Referente", "address" to "Indirizzo", "city" to "Località", "category" to "Categoria")

private fun ClientEdit.get(k: String) = when (k) { "name" -> name; "vat" -> vat; "taxCode" -> taxCode; "phone" -> phone; "email" -> email; "pec" -> pec; "contact" -> contact; "address" -> address; "city" -> city; else -> category }
private fun current(c: UClient, k: String) = when (k) { "name" -> c.name; "vat" -> c.vat; "taxCode" -> c.taxCode; "phone" -> c.phone; "email" -> c.email; "pec" -> c.pec; "contact" -> c.contact; "address" -> c.address; "city" -> c.city; else -> c.category }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ClientEditSheet(vm: AppViewModel, c: UClient?, onDismiss: () -> Unit, onCreated: (String) -> Unit = {}) {
    val isAdmin = vm.me.collectAsState().value?.isAdmin == true
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    val e = c?.edit ?: ClientEdit(); val manual = c == null || e.created
    val values = remember { clientFields.associate { (k, _) -> k to mutableStateOf(e.get(k)) } }
    var confirmDelete by remember { mutableStateOf(false) }
    fun close(after: () -> Unit = {}) = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); after() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(if (c == null) "Nuovo cliente" else c.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (manual) "Il cliente compare subito per tutto il team." else "Quello che scrivi ha la precedenza sui dati di portali e Mastro e resta anche dopo le sincronizzazioni. Un campo vuoto torna al valore di origine.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            clientFields.forEach { (k, label) ->
                val v = values.getValue(k)
                OutlinedTextField(v.value, { if (it.length <= 200) v.value = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text(label) }, singleLine = true,
                    placeholder = { if (!manual) Text(current(c, k), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = when (k) { "phone" -> KeyboardType.Phone; "email", "pec" -> KeyboardType.Email; else -> KeyboardType.Text }))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c != null) {
                    if (isAdmin) IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Elimina", tint = LocalStatus.current.bad) }
                    if (isAdmin && !manual && c.edit != null) TextButton({ vm.resetClient(c.code); close() }) { Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp)); Text(" Annulla modifiche") }
                }
                Spacer(Modifier.weight(1f))
                Button({
                    val edit = ClientEdit(values.getValue("name").value.trim(), values.getValue("vat").value.trim(), values.getValue("taxCode").value.trim(), values.getValue("city").value.trim(),
                        values.getValue("address").value.trim(), values.getValue("phone").value.trim(), values.getValue("email").value.trim(), values.getValue("pec").value.trim(),
                        values.getValue("contact").value.trim(), values.getValue("category").value.trim())
                    if (manual && edit.name.isBlank()) { vm.notify("Scrivi la ragione sociale"); return@Button }
                    vm.saveClient(c?.code.orEmpty(), edit) { code -> if (c == null) onCreated(code) }; close()
                }) { Text(if (c == null) "Crea cliente" else "Salva") }
            }
        }
    }
    if (confirmDelete && c != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Eliminare ${c.name}?") },
        text = { Text("Il cliente sparisce dall'app per tutto il team. Portali e Mastro non vengono toccati: puoi ripristinarlo da Altro → Clienti eliminati.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteClient(c.code, true); close() }) { Text("Elimina", color = LocalStatus.current.bad) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Annulla") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LicenseEditSheet(vm: AppViewModel, a: Archive, l: ULicense, onDismiss: () -> Unit) {
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    val e = a.licenseEdit(l.r.id) ?: LicenseEdit()
    var expiry by remember { mutableStateOf(Dates.parse(e.expiry) ?: l.expiry) }
    var note by remember { mutableStateOf(e.note) }
    var hidden by remember { mutableStateOf(e.hidden) }
    var pick by remember { mutableStateOf(false) }
    fun close() = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(l.r.serial, style = MaterialTheme.typography.titleLarge)
            Text("${Catalog.family(l.family)} · ${l.r.client}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scadenza", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Dates.short(expiry), style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton({ pick = true }) { Text("Cambia") }
            }
            OutlinedTextField(note, { if (it.length <= 500) note = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Nota") }, placeholder = { Text("Es. rinnovata a mano, spostata su un'altra cassa…") }, minLines = 2)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Togli dall'elenco", style = MaterialTheme.typography.bodyLarge); Text("Non compare più nell'app né nelle esportazioni", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(hidden, { hidden = it })
            }
            if (e.updatedBy.isNotBlank()) Text("Ultima modifica: ${e.updatedBy} · ${e.updatedAt.take(10)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.licenseEdit(l.r.id) != null) TextButton({ vm.saveLicense(l.r.id, LicenseEdit()); close() }) { Text("Torna ai dati del portale") }
                Spacer(Modifier.weight(1f))
                Button({
                    val iso = expiry?.takeIf { it != l.expiry || e.expiry.isNotBlank() }?.let(Dates::iso).orEmpty()
                    vm.saveLicense(l.r.id, LicenseEdit(iso, note.trim(), hidden)); close()
                }) { Text("Salva") }
            }
        }
    }
    if (pick) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (expiry ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { pick = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { expiry = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pick = false }) { Text("OK") } },
            dismissButton = { TextButton({ pick = false }) { Text("Annulla") } }) { DatePicker(st) }
    }
}

@Composable fun DeletedClientsScreen(vm: AppViewModel, a: Archive, nav: NavHostController) {
    var q by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val list = androidx.compose.runtime.remember(a, q) { val n = it.topcash.studio.data.norm(q.trim()); if (n.isEmpty()) a.deleted else a.deleted.filter { it.search.contains(n) } }
    val auto = a.deleted.count { it.autoGone }
    Page(null, "Clienti eliminati", "${a.deleted.size} nascosti per tutto il team" + (if (auto > 0) " · $auto senza macchine attive" else ""), onBack = { nav.popBackStack() }) {
        if (a.deleted.isEmpty()) item(key = "none") { Empty(Icons.Rounded.Restore, "Nessun cliente eliminato", "Quando elimini un cliente lo ritrovi qui, pronto da ripristinare.") }
        else item(key = "search") { SearchField(q, { q = it }, "Cerca tra gli eliminati") }
        items(list.size, key = { list[it].code }) { i ->
            val c = list[i]
            CardBox(Modifier.padding(bottom = 8.dp).animateItem()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.name, c.code, 40.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull("Cod. " + c.code.trimStart('0'), if (c.autoGone) "senza macchine attive" else c.edit?.updatedBy?.takeIf { it.isNotBlank() }?.let { "eliminato da $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton({ vm.deleteClient(c.code, false) }) { Text("Ripristina") }
                }
            }
        }
    }
}

/** Only the owner can create other owners or touch them. */
private fun roles(owner: Boolean) = (if (owner) listOf("owner" to "Proprietario") else emptyList()) +
    listOf("admin" to "Amministratore", "ufficio" to "Amministrazione", "tecnico" to "Tecnico")

/** Users and roles (administrators): create, rename, change role, new password, disable, delete. Your own name too. */
@Composable fun UsersScreen(vm: AppViewModel, nav: NavHostController) {
    val users by vm.users.collectAsState(); val me by vm.me.collectAsState()
    LaunchedEffect(Unit) { vm.loadUsers() }
    var editing by remember { mutableStateOf<UserRow?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    Page(null, "Utenti", users?.let { "${it.size} utenti · ${it.count { u -> !u.disabled }} attivi" } ?: "Caricamento…", onBack = { nav.popBackStack() },
        actions = { IconButton({ creating = true }) { Icon(Icons.Rounded.PersonAdd, "Nuovo utente") } }) {
        item(key = "info") { Text("Proprietario: poteri illimitati, gestisce anche gli amministratori. Amministratore: tutto, compresi utenti e backup. Amministrazione: vede tutto, contabilità compresa. Tecnico: tutto tranne la contabilità.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp)) }
        val list = users.orEmpty()
        if (users != null && list.isEmpty()) item(key = "none") { Empty(Icons.Rounded.Group, "Nessun utente", "") }
        items(list.size, key = { list[it].username }) { i ->
            val u = list[i]
            CardBox(Modifier.padding(bottom = 8.dp).animateItem(), onClick = { editing = u }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u.name, u.username, 42.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(u.name + if (u.username == me?.username) " (tu)" else "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${u.username} · ultimo accesso ${if (u.lastLogin.isBlank()) "mai" else Dates.short(Dates.parse(u.lastLogin))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill(if (u.disabled) "Disattivato" else u.roleLabel.ifBlank { u.role }, if (u.disabled) Tone.Bad else if (u.role == "admin" || u.role == "owner") Tone.Accent else Tone.Neutral)
                }
            }
        }
    }
    editing?.let { u -> UserSheet(vm, u, u.username == me?.username) { editing = null } }
    if (creating) NewUserSheet(vm) { creating = false }
}

private fun genPassword(): String { val a = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"; val r = java.security.SecureRandom(); return (1..14).map { a[r.nextInt(a.length)] }.joinToString("") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun UserSheet(vm: AppViewModel, u: UserRow, self: Boolean, onDismiss: () -> Unit) {
    val meOwner = vm.me.collectAsState().value?.isOwner == true
    val locked = u.role == "owner" && !meOwner
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(u.name) }; var role by remember { mutableStateOf(u.role) }
    var newPwd by remember { mutableStateOf<String?>(null) }; var confirmDelete by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }; var mine by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun close() = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(u.name, style = MaterialTheme.typography.titleLarge); Text(u.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { if (it.length <= 80) name = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Nome e cognome") }, singleLine = true)
            if (!self && !locked) {
                Text("Ruolo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { roles(meOwner).forEach { (k, l) -> FilterChip(role == k, { role = k }, label = { Text(l) }, shape = MaterialTheme.shapes.small) } }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ newPwd = genPassword() }) { Text("Nuova password") }
                    OutlinedButton({ vm.updateUser(u.username, UserPatch(disabled = !u.disabled), if (u.disabled) "Utente riattivato" else "Utente disattivato"); close() }) { Text(if (u.disabled) "Riattiva" else "Disattiva") }
                }
                newPwd?.let { p ->
                    CardBox(Modifier.padding(top = 10.dp)) {
                        Text(p, style = MaterialTheme.typography.titleLarge.tabular)
                        Text("Comunicala al collega: le sue sessioni aperte vengono chiuse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button({ vm.updateUser(u.username, UserPatch(password = p), "Password impostata e copiata")
                                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("password", p)); close() }) { Text("Imposta e copia") }
                            TextButton({ newPwd = genPassword() }) { Text("Un'altra") }
                        }
                    }
                }
            } else {
                Text("La tua password", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                OutlinedTextField(current, { current = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Password attuale") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedTextField(mine, { mine = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Nuova password (min. 10 caratteri)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedButton({ if (mine.length < 10) vm.notify("Almeno 10 caratteri") else vm.changePassword(current, mine) { ok -> if (ok) close() } }, Modifier.padding(top = 8.dp)) { Text("Cambia password") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!self && !locked) IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Elimina utente", tint = LocalStatus.current.bad) }
                Spacer(Modifier.weight(1f))
                Button({
                    val n = name.trim()
                    if (self) { if (n != u.name && n.isNotBlank()) vm.renameMe(n) }
                    else if (n != u.name || role != u.role) vm.updateUser(u.username, UserPatch(role = role.takeIf { it != u.role }, name = n.takeIf { it != u.name && it.isNotBlank() }), "Utente aggiornato")
                    close()
                }) { Text("Salva") }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Eliminare ${u.name}?") },
        text = { Text("Non potrà più accedere e le sue sessioni si chiudono subito. Interventi e note che ha scritto restano.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteUser(u.username); close() }) { Text("Elimina", color = LocalStatus.current.bad) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Annulla") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NewUserSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val meOwner = vm.me.collectAsState().value?.isOwner == true
    val sheet = rememberSheet(); val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var role by remember { mutableStateOf("ufficio") }; var pwd by remember { mutableStateOf(genPassword()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun close() = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text("Nuovo utente", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { if (it.length <= 80) name = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Nome e cognome") }, singleLine = true)
            OutlinedTextField(user, { user = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch in "._-" } }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Nome utente") }, placeholder = { Text("es. mario.rossi") }, singleLine = true)
            Text("Ruolo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { roles(meOwner).forEach { (k, l) -> FilterChip(role == k, { role = k }, label = { Text(l) }, shape = MaterialTheme.shapes.small) } }
            OutlinedTextField(pwd, { pwd = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Password iniziale") }, singleLine = true,
                trailingIcon = { TextButton({ pwd = genPassword() }) { Text("Genera") } })
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Spacer(Modifier.weight(1f))
                Button({
                    if (name.isBlank() || user.isBlank() || pwd.length < 10) { vm.notify("Compila nome, nome utente e una password di almeno 10 caratteri"); return@Button }
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("password", pwd))
                    vm.createUser(UserCreate(user.trim(), name.trim(), role, pwd)); close()
                }) { Text("Crea e copia password") }
            }
        }
    }
}
