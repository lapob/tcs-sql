package it.topcash.studio.data

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.sse.EventSource
import java.io.File

enum class Screen { Loading, Setup, Login, Main, Device }
data class SyncProgress(val running: Boolean = false, val text: String = "")

/** Single source of truth: cached archive (works offline), session, live updates from the server. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("topcash", Context.MODE_PRIVATE)
    private val api = Api(app)
    private val cacheFile = File(app.filesDir, "archive.json")
    private val etagFile = File(app.filesDir, "archive.etag")
    /** Fingerprints of the saved sections ("name=hash,…"): sent to the server to download only what changed. */
    private val hashFile = File(app.filesDir, "archive.hashes")

    private val _screen = MutableStateFlow(Screen.Loading); val screen = _screen.asStateFlow()
    /** Pending launcher shortcut ("new", "load", "search"), consumed by the UI once the archive is ready. */
    val shortcut = MutableStateFlow<String?>(null)
    /** One-shot: the clients search field takes the focus (shortcut "Cerca cliente"). */
    val focusSearch = MutableStateFlow(false)
    private val _archive = MutableStateFlow<Archive?>(null); val archive = _archive.asStateFlow()
    private val _me = MutableStateFlow<Me?>(null); val me = _me.asStateFlow()
    private val _online = MutableStateFlow(true); val online = _online.asStateFlow()
    private val _refreshing = MutableStateFlow(false); val refreshing = _refreshing.asStateFlow()
    private val _progress = MutableStateFlow(SyncProgress()); val progress = _progress.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4); val messages = _messages.asSharedFlow()
    private val _theme = MutableStateFlow(prefs.getString("theme", "system") ?: "system"); val theme = _theme.asStateFlow()
    val server: String get() = prefs.getString("server", "").orEmpty()
    val serverExternal: String get() = prefs.getString("server_ext", "").orEmpty()
    /** Code shown on the "device blocked" screen, and its status. */
    private val _device = MutableStateFlow(DeviceStatus()); val device = _device.asStateFlow()
    val usingExternal: Boolean get() = api.isExternal
    private val deviceKey: String by lazy {
        prefs.getString("device_key", null) ?: java.security.SecureRandom().let { r -> ByteArray(32).also(r::nextBytes) }
            .let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) }
            .also { prefs.edit().putString("device_key", it).apply() }
    }

    private var etag: String? = null
    /** The saved archive as JSON sections, read from disk when a partial update needs it. */
    private suspend fun rawArchive(): kotlinx.serialization.json.JsonObject? = withContext(Dispatchers.Default) {
        runCatching { json.parseToJsonElement(cacheFile.readText()) as kotlinx.serialization.json.JsonObject }.getOrNull()
    }
    private var stream: EventSource? = null
    private var reconnect: Job? = null
    private var refreshJob: Job? = null
    private var foreground = false
    private var backoff = 2_000L

    init { start() }

    private fun start() = viewModelScope.launch {
        if (server.isBlank()) { _screen.value = Screen.Setup; return@launch }
        api.deviceKey = deviceKey
        api.bases = listOf(server, serverExternal).filter { it.isNotBlank() }; api.base = server
        if (_archive.value == null) loadCache()
        try {
            if (api.bases.size > 1) api.choose()
            // outside the office the server talks only to devices approved by an administrator
            // an older server has no device check yet (404): behave as in the office
            val h = try { api.hello() } catch (e: ApiException) { if (e.status == 404) DeviceStatus("office") else throw e }
            if (h.remote && h.status != "approved") { _device.value = h; _screen.value = Screen.Device; return@launch } // outside the office: approved devices only
            val s = api.session()
            if (s.user == null) { _screen.value = Screen.Login; return@launch }
            _me.value = s.user; prefs.edit().putString("me", json.encodeToString(Me.serializer(), s.user)).apply()
            learnPublic(s.publicUrl)
            _screen.value = Screen.Main; refresh(); connect()
        } catch (e: ApiException) {
            if (e.device.isNotEmpty()) { _device.value = DeviceStatus(e.device, e.code, true); _screen.value = Screen.Device; return@launch }
            // offline: show the last saved archive if we have one
            if (_archive.value != null && prefs.getString("me", null) != null) {
                _me.value = runCatching { json.decodeFromString<Me>(prefs.getString("me", "")!!) }.getOrNull(); _online.value = false; _screen.value = Screen.Main; scheduleReconnect()
            } else { _screen.value = Screen.Login; _messages.tryEmit(e.message ?: "Server non raggiungibile") }
        }
    }

    private suspend fun loadCache() = withContext(Dispatchers.Default) {
        if (!cacheFile.exists()) return@withContext
        runCatching { val ar = Archive(json.decodeFromString<ArchiveState>(cacheFile.readText())); _archive.value = ar; etag = etagFile.takeIf { it.exists() }?.readText(); ar.warm() } // shown at once, the rest is prepared here in the background
    }

    fun refresh(userInitiated: Boolean = false) {
        val running = refreshJob
        if (running?.isActive == true) {
            // pulled while a background refresh is already running: show the indicator until that one ends
            // (before, the pull was ignored and the indicator stayed stuck half-way)
            if (userInitiated && !_refreshing.value) viewModelScope.launch {
                _refreshing.value = true; val t0 = System.currentTimeMillis()
                try { running.join() } finally { delay((MIN_SPIN - (System.currentTimeMillis() - t0)).coerceAtLeast(0)); _refreshing.value = false }
            }
            return
        }
        refreshJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            if (userInitiated) _refreshing.value = true
            try {
                val have = if (_archive.value == null) null else withContext(Dispatchers.IO) { hashFile.takeIf { it.exists() }?.readText()?.ifBlank { null } }
                val r = try { api.state(if (_archive.value == null) null else etag, have) }
                        catch (e: ApiException) { if (e.status == 404 && have != null) api.state(null) else throw e } // older server: full archive
                if (r != null) {
                    var (body, tag) = r; var hashes = ""
                    val parsed = withContext(Dispatchers.Default) {
                        val o = json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                        // partial answer: only the sections that changed, merged into the saved copy
                        var o2 = o
                        if (o["partial"] != null) {
                            val saved = rawArchive()
                            if (saved != null) body = kotlinx.serialization.json.JsonObject(saved + o.filterKeys { it != "partial" }).toString()
                            else { val full = api.state(null)!!; body = full.first; tag = full.second; o2 = json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject } // saved copy unreadable: whole archive
                        }
                        hashes = (o2["hashes"] as? kotlinx.serialization.json.JsonObject)?.entries?.joinToString(",") { (k, v) -> k + "=" + (v as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
                        Archive(json.decodeFromString<ArchiveState>(body)).also { it.warm() }
                    }
                    _archive.value?.let { noticeAssigned(it.state.interventions, parsed.state.interventions) }
                    _archive.value = parsed; etag = tag
                    withContext(Dispatchers.IO) { cacheFile.writeText(body); tag?.let { etagFile.writeText(it) }; hashFile.writeText(hashes) }
                }
                _online.value = true
            } catch (e: ApiException) {
                if (e.status == 401) { signedOut(); return@launch }
                if (e.device.isNotEmpty()) { stream?.cancel(); stream = null; _device.value = DeviceStatus(e.device, e.code, true); _screen.value = Screen.Device; return@launch }
                _online.value = false; if (userInitiated) _messages.tryEmit(e.message ?: "Aggiornamento non riuscito")
            } finally {
                if (userInitiated) withContext(kotlinx.coroutines.NonCancellable) { delay((MIN_SPIN - (System.currentTimeMillis() - t0)).coerceAtLeast(0)) }
                _refreshing.value = false
            }
        }
    }
    private companion object { const val MIN_SPIN = 650L }
    /** Safety net for live updates: a cheap ETag check every 2 minutes while the app is visible (304 = nothing to download). */
    private var heartbeat: Job? = null
    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = viewModelScope.launch { while (true) { delay(120_000); if (foreground && _screen.value == Screen.Main) refresh() } }
    }

    private fun connect() {
        if (!foreground || _screen.value != Screen.Main || stream != null) return
        stream = api.stream(
            onMessage = { m ->
                when (m.type) {
                    "changed", "hello" -> refresh()
                    "progress" -> _progress.value = SyncProgress(m.running, m.text)
                }
            },
            onOpen = { _online.value = true; backoff = 2_000L },
            onFailure = { stream = null; _online.value = false; scheduleReconnect() },
        )
    }
    private fun scheduleReconnect() {
        reconnect?.cancel()
        reconnect = viewModelScope.launch { delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000L); if (api.bases.size > 1) api.choose(); refresh(); connect() }
    }
    /** Live updates only while the app is visible (battery friendly); catch up on return. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) { if (_screen.value == Screen.Main) { refresh(); connect() }; startHeartbeat() }
        else { stream?.cancel(); stream = null; reconnect?.cancel(); heartbeat?.cancel() }
    }

    /** The office server hands out its public address (Cloudflare tunnel): stored once, so outside the office the app switches by itself. */
    private fun learnPublic(url: String) {
        if (serverExternal.isNotBlank() || url.isBlank()) return
        val ext = Api.normalizeExternal(url) ?: return
        prefs.edit().putString("server_ext", ext).apply()
        api.bases = listOf(server, ext)
    }

    fun setServer(input: String, external: String = ""): String? {
        val url = Api.normalize(input) ?: return "Indirizzo non valido"
        val ext = if (external.isBlank()) "" else Api.normalizeExternal(external) ?: return "Indirizzo esterno non valido: usa il nome del dominio, es. topcash.tuodominio.it"
        prefs.edit().putString("server", url).putString("server_ext", ext).apply()
        _screen.value = Screen.Loading; start(); return null
    }
    /** "Controlla ora" on the device screen; also polled every few seconds. */
    suspend fun checkDevice(): Boolean = try {
        val h = api.hello(); _device.value = h
        if (!h.remote || h.status == "approved") { _screen.value = Screen.Loading; start(); true } else false
    } catch (e: ApiException) { false }
    fun changeServer() { stream?.cancel(); stream = null; _screen.value = Screen.Setup }

    suspend fun login(user: String, password: String): String? = try {
        val me = api.login(user.trim(), password); _me.value = me; prefs.edit().putString("last_user", user.trim()).apply(); prefs.edit().putString("me", json.encodeToString(Me.serializer(), me)).apply()
        _screen.value = Screen.Main; refresh(); connect(); null
    } catch (e: ApiException) { if (e.device.isNotEmpty()) { _device.value = DeviceStatus(e.device, e.code, true); _screen.value = Screen.Device }; e.message }
    val lastUser: String get() = prefs.getString("last_user", "").orEmpty()

    fun logout() = viewModelScope.launch {
        stream?.cancel(); stream = null; api.logout(); signedOut()
        withContext(Dispatchers.IO) { cacheFile.delete(); etagFile.delete(); hashFile.delete() }; _archive.value = null
    }
    /** Interventions just assigned to me by someone else: a system notification ("Interventi assegnati" channel). */
    private fun noticeAssigned(prev: List<Intervention>, next: List<Intervention>) {
        val me = _me.value ?: return
        fun n(s: String) = s.trim().lowercase()
        fun mine(i: Intervention?) = i != null && i.source == "app" && n(i.technician) == n(me.name)
        val was = prev.associateBy { it.id }
        val fresh = next.filter { mine(it) && it.status != "Chiuso" && n(it.updatedBy.ifBlank { it.createdBy }) != n(me.name) && !mine(was[it.id]) }
        if (fresh.isEmpty()) return
        val ctx = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel("assigned", "Interventi assegnati", android.app.NotificationManager.IMPORTANCE_HIGH).apply { description = "Quando qualcuno ti assegna un intervento" })
        val first = fresh.first()
        val title = if (fresh.size == 1) "Nuovo intervento assegnato a te" else "${fresh.size} nuovi interventi assegnati a te"
        val text = if (fresh.size == 1) listOf(first.clientName, first.title).filter { it.isNotBlank() }.joinToString(" · ") else fresh.joinToString(", ") { it.clientName }.take(200)
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { android.app.PendingIntent.getActivity(ctx, 7, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT) }
        val note = android.app.Notification.Builder(ctx, "assigned").setSmallIcon(it.topcash.studio.R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build()
        runCatching { nm.notify(("int-" + first.id).hashCode(), note) }
    }
    private fun signedOut() { _me.value = null; prefs.edit().remove("me").apply(); _screen.value = Screen.Login }

    fun notify(text: String) { _messages.tryEmit(text) }
    fun setTheme(t: String) { _theme.value = t; prefs.edit().putString("theme", t).apply() }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        try { block(); _messages.tryEmit(success); refresh() } catch (e: ApiException) { _messages.tryEmit(e.message ?: "Operazione non riuscita") }
    }
    // ── inventory and renewals (every technician can add PCs and record the outcome of a call)
    fun saveAsset(a: Asset) = act(if (a.id.isBlank()) "Aggiunto all'inventario" else "Inventario aggiornato") { api.saveAsset(a) }
    fun deleteAsset(id: String) = act("Tolto dall'inventario") { api.deleteAsset(id) }
    fun saveRenewal(key: String, status: String, note: String, next: String) = act("Esito salvato") { api.saveRenewal(RenewalRequest(key, status, note, next)) }
    // ── network updates: compare the APK on the server with this one, download it and hand it to the system installer
    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
    private val _update = MutableStateFlow<AppBuild?>(null); val update = _update.asStateFlow()
    private val _updateProgress = MutableStateFlow<Float?>(null); val updateProgress = _updateProgress.asStateFlow()
    private var lastUpdateCheck = 0L
    fun checkUpdate(force: Boolean = false) = viewModelScope.launch {
        if (!force && System.currentTimeMillis() - lastUpdateCheck < 3 * 3600_000L) return@launch
        lastUpdateCheck = System.currentTimeMillis()
        try { val b = api.updateInfo().android; _update.value = b?.takeIf { newer(it.version, appVersion) }; if (force && _update.value == null) _messages.tryEmit("L'app è aggiornata (versione $appVersion)") }
        catch (_: ApiException) { if (force) _messages.tryEmit("Server non raggiungibile per gli aggiornamenti") }
    }
    fun installUpdate(context: Context) = viewModelScope.launch {
        val b = _update.value ?: return@launch
        if (_updateProgress.value != null) return@launch
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            _messages.tryEmit("Consenti a Top Cash di installare app, poi tocca di nuovo «Aggiorna»")
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return@launch
        }
        _updateProgress.value = 0f
        try {
            val f = api.downloadApk(File(context.cacheDir, "updates"), b) { _updateProgress.value = it }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ApiException) { _messages.tryEmit(e.message ?: "Aggiornamento non riuscito") }
        finally { _updateProgress.value = null }
    }
    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
        return false
    }
    // ── administrator
    fun saveClient(code: String, e: ClientEdit, onDone: (String) -> Unit = {}) = viewModelScope.launch {
        try { val c = api.saveClient(code, e); _messages.tryEmit(if (code.isBlank()) "Cliente creato" else "Cliente aggiornato"); refresh(); onDone(c) }
        catch (x: ApiException) { _messages.tryEmit(x.message ?: "Salvataggio non riuscito") }
    }
    fun deleteClient(code: String, deleted: Boolean) = act(if (deleted) "Cliente eliminato · ripristinabile da Altro → Clienti eliminati" else "Cliente ripristinato") { api.deleteClient(code, deleted) }
    fun resetClient(code: String) = act("Modifiche annullate") { api.resetClient(code) }
    fun saveLicense(id: String, e: LicenseEdit) = act(if (e.hidden) "Licenza tolta dall'elenco" else "Licenza aggiornata") { api.saveLicense(id, e) }
    private val _users = MutableStateFlow<List<UserRow>?>(null); val users = _users.asStateFlow()
    fun loadUsers() = viewModelScope.launch { try { _users.value = api.users() } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Utenti non disponibili") } }
    private fun userAct(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        try { block(); _messages.tryEmit(success); _users.value = api.users() } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Operazione non riuscita") }
    }
    fun createUser(u: UserCreate) = userAct("Utente ${u.name} creato") { api.createUser(u) }
    fun updateUser(username: String, p: UserPatch, success: String) = userAct(success) { api.updateUser(username, p) }
    fun deleteUser(username: String) = userAct("Utente eliminato") { api.deleteUser(username) }
    fun renameMe(name: String) = userAct("Nome aggiornato") { api.updateMe(name); _me.value = _me.value?.copy(name = name) }
    fun changePassword(current: String, password: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        try { api.changePassword(current, password); _messages.tryEmit("Password aggiornata: accedi di nuovo"); onDone(true); logout() }
        catch (x: ApiException) { _messages.tryEmit(x.message ?: "Password non aggiornata"); onDone(false) }
    }
    fun saveNote(code: String, note: String) = act("Nota salvata e condivisa") { api.saveNote(code, note) }
    fun saveMeta(code: String, meta: ClientMeta, message: String = "Promemoria aggiornato") = act(message) { api.saveMeta(code, meta) }
    fun saveIntervention(i: Intervention, quiet: Boolean = false, onDone: (Intervention) -> Unit) = viewModelScope.launch {
        try { val r = api.saveIntervention(i); if (!quiet) _messages.tryEmit("Intervento salvato"); onDone(r); refresh() } catch (e: ApiException) { _messages.tryEmit(e.message ?: "Salvataggio non riuscito") }
    }
    fun saveDueNote(id: String, status: String, note: String) = act(if (status.isBlank()) "Nota salvata" else "Scadenza segnata come “$status”") { api.saveDueNote(id, status, note) }
    fun deleteIntervention(id: String) = act("Intervento eliminato") { api.deleteIntervention(id) }
    fun saveMovements(m: List<Movement>, onDone: () -> Unit) = viewModelScope.launch {
        try { api.saveMovements(m); _messages.tryEmit(if (m.size == 1) "Movimento registrato" else "${m.size} movimenti registrati"); onDone(); refresh() }
        catch (e: ApiException) { _messages.tryEmit(e.message ?: "Movimento non registrato") }
    }

    fun signIntervention(id: String, signer: String, hours: String, png: ByteArray, close: Boolean, onDone: (Intervention) -> Unit) = viewModelScope.launch {
        try {
            val r = api.signIntervention(id, SignRequest(signer, hours.replace(',', '.'), "data:image/png;base64," + android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP), close))
            _messages.tryEmit("Rapportino n. ${r.reportNo} firmato"); onDone(r); refresh()
        } catch (e: ApiException) { _messages.tryEmit(e.message ?: "Firma non registrata") }
    }
    fun unsignIntervention(id: String) = act("Firma annullata") { api.unsignIntervention(id) }
    /** E-mails the signed PDF from the company mailbox (server side). */
    fun sendReport(id: String, to: String, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try { val r = api.sendReport(id, to); _messages.tryEmit("Rapportino inviato a ${r.sentTo}"); onDone(true); refresh() }
        catch (e: ApiException) { _messages.tryEmit(e.message ?: "Email non inviata"); onDone(false) }
    }
    /** Prints the PDF report through the Android print service (Wi-Fi printers, Save as PDF…). */
    fun printReport(i: Intervention, context: android.content.Context) = viewModelScope.launch {
        try {
            val name = "Rapportino-" + (i.reportNo.ifBlank { i.id.take(8) }).replace('/', '-') + ".pdf"
            val f = api.reportPdf(i.id, File(getApplication<Application>().cacheDir, "print"), name)
            val pm = context.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
            pm.print("Rapportino " + i.reportNo, object : android.print.PrintDocumentAdapter() {
                override fun onLayout(oldAttributes: android.print.PrintAttributes?, newAttributes: android.print.PrintAttributes?, cancellationSignal: android.os.CancellationSignal?,
                                      callback: LayoutResultCallback, extras: android.os.Bundle?) {
                    if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                    callback.onLayoutFinished(android.print.PrintDocumentInfo.Builder(name).setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
                }
                override fun onWrite(pages: Array<out android.print.PageRange>?, destination: android.os.ParcelFileDescriptor, cancellationSignal: android.os.CancellationSignal?, callback: WriteResultCallback) {
                    try {
                        f.inputStream().use { inp -> java.io.FileOutputStream(destination.fileDescriptor).use { out -> inp.copyTo(out) } }
                        if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    } catch (e: Exception) { callback.onWriteFailed(e.message) }
                }
            }, null)
        } catch (e: ApiException) { _messages.tryEmit(e.message ?: "PDF non disponibile") }
        catch (e: Exception) { _messages.tryEmit("Stampa non disponibile su questo telefono") }
    }
    /** Downloads the PDF report and opens the Android share sheet (WhatsApp, e-mail, Drive…). */
    fun shareReport(i: Intervention, context: android.content.Context) = viewModelScope.launch {
        try {
            val name = "Rapportino-" + (i.reportNo.ifBlank { i.id.take(8) }).replace('/', '-') + ".pdf"
            val f = api.reportPdf(i.id, File(getApplication<Application>().cacheDir, "reports"), name)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Rapportino di intervento " + i.reportNo).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(send, "Invia il rapportino"))
        } catch (e: ApiException) { _messages.tryEmit(e.message ?: "PDF non disponibile") }
        catch (e: Exception) { _messages.tryEmit("Nessuna app per condividere il PDF") }
    }

    override fun onCleared() { stream?.cancel() }
}
