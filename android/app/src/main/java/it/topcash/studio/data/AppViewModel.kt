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
import kotlinx.coroutines.flow.collectLatest
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
    private val files: File = app.filesDir
    /** Archive saved by versions up to 2.8.11 as one big file: moved into [sections] on the first start. */
    private val cacheFile = File(app.filesDir, "archive.json")
    /** The saved archive, one file per section (see SectionCache: no huge strings or JSON trees in memory). */
    private val sections = SectionCache(File(app.filesDir, "archive"))
    private val etagFile = File(app.filesDir, "archive.etag")
    /** Fingerprints of the saved sections ("name=hash,…"): sent to the server to download only what changed. */
    private val hashFile = File(app.filesDir, "archive.hashes")

    private val _screen = MutableStateFlow(Screen.Loading); val screen = _screen.asStateFlow()
    /** Pending launcher shortcut ("route", "new", "load", "search"), consumed by the UI once the archive is ready. */
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
    /** Code shown on the "authorize / blocked device" screen, and its status. */
    private val _device = MutableStateFlow(DeviceStatus()); val device = _device.asStateFlow()
    val usingExternal: Boolean get() = api.isExternal
    private val deviceKey: String by lazy {
        prefs.getString("device_key", null) ?: java.security.SecureRandom().let { r -> ByteArray(32).also(r::nextBytes) }
            .let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) }
            .also { prefs.edit().putString("device_key", it).apply() }
    }

    private var etag: String? = null
    private var stream: EventSource? = null
    private var reconnect: Job? = null
    private var refreshJob: Job? = null
    private var foreground = false
    private var backoff = 2_000L

    /** The archive as the server sent it; what is shown is this plus the changes still waiting to be sent ([Outbox]). */
    @Volatile private var rawState: ArchiveState? = null
    private var flushing: Job? = null
    /** Changes made without network, waiting to be sent (the UI shows "Da inviare"). */
    val outbox = Outbox.items
    private val photoDir = File(app.cacheDir, "photos")

    /** Stops ticked a moment ago: the round shows them at once, before the archive is rebuilt. */
    private val _routeLocal = MutableStateFlow<Map<String, Boolean>>(emptyMap()); val routeLocal = _routeLocal.asStateFlow()

    init {
        Outbox.init(app); start(); watchOutbox()
        // a tick shown at once is dropped only when the archive on screen says the same (never earlier: no flicker back)
        viewModelScope.launch { _archive.collect { a -> if (a != null && _routeLocal.value.isNotEmpty()) _routeLocal.value = _routeLocal.value.filter { (k, v) -> a.routeDone(k) != v } } }
    }

    /** Server archive + changes not sent yet: the technician sees his work at once, with or without network. */
    private fun overlay(s: ArchiveState): ArchiveState {
        val ops = Outbox.items.value; if (ops.isEmpty()) return s
        val me = _me.value?.name.orEmpty(); val now = java.time.OffsetDateTime.now().toString(); val today = java.time.LocalDate.now().toString()
        var ints = s.interventions
        val edits = ops.filter { it.kind == Outbox.INTERVENTION }.mapNotNull { runCatching { json.decodeFromString<Intervention>(it.body) }.getOrNull() }
        if (edits.isNotEmpty()) {
            val map = LinkedHashMap<String, Intervention>(); ints.forEach { map[it.id] = it }
            edits.forEach { e -> val old = map[e.id]
                map[e.id] = old?.copy(clientCode = e.clientCode, clientName = e.clientName.ifBlank { old.clientName }, date = e.date, machine = e.machine, title = e.title, work = e.work,
                    status = e.status, technician = e.technician, priority = e.priority, parts = e.parts, updatedBy = me.ifBlank { old.updatedBy }, updated = now)
                    ?: e.copy(source = "app", created = now, createdBy = me, updated = now, updatedBy = me)
            }
            ints = map.values.toList()
        }
        var route = s.routeDone
        ops.filter { it.kind == Outbox.ROUTE }.forEach { op -> runCatching { json.decodeFromString<RouteDoneRequest>(op.body) }.getOrNull()?.let { r ->
            route = if (r.done) route + (r.key to RouteMark(today, now, me)) else route - r.key } }
        var notes = s.notes
        ops.filter { it.kind == Outbox.NOTE }.forEach { op -> runCatching { json.decodeFromString<NoteRequest>(op.body) }.getOrNull()?.let { notes = notes + (it.code to it.note) } }
        return s.copy(interventions = ints, routeDone = route, notes = notes)
    }
    private fun build(s: ArchiveState): Archive = Archive(overlay(s)).also { a ->
        a.pending = Outbox.items.value.filter { it.kind == Outbox.INTERVENTION }.map { it.ref }.toSet()
    }
    /** A change queued: shown at once. A change sent: the fresh archive comes from the server (no flicker back to the old copy). */
    private fun watchOutbox() = viewModelScope.launch {
        var last = Outbox.items.value.size
        Outbox.items.collectLatest { l ->
            val grew = l.size >= last; last = l.size
            if (grew) rawState?.let { st -> _archive.value = withContext(Dispatchers.Default) { build(st).also { it.warm() } } }
            else if (_screen.value == Screen.Main) refresh()
        }
    }
    /** Sends what was saved without network; called when the network is back (every update) and by the background check. */
    fun flushOutbox(announce: Boolean = true) {
        if (Outbox.items.value.isEmpty() || flushing?.isActive == true) return
        flushing = viewModelScope.launch {
            val r = Outbox.flush(api)
            if (announce && r.sent > 0) _messages.tryEmit(if (r.sent == 1) "Inviata la modifica fatta senza rete" else "Inviate ${r.sent} modifiche fatte senza rete")
            r.failed.take(3).forEach { _messages.tryEmit("Non inviata · $it") }
        }
    }
    private fun nowMs() = System.currentTimeMillis()
    private fun queueIntervention(i: Intervention): Intervention {
        val q = if (i.id.isBlank()) i.copy(id = "local-" + Outbox.newId()) else i
        Outbox.add(PendingOp(Outbox.newId(), Outbox.INTERVENTION, json.encodeToString(Intervention.serializer(), q), q.id,
            listOf(q.clientName, q.title).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Intervento" }, nowMs()))
        return q
    }
    private val offlineText = "Senza rete: salvato sul telefono, parte da solo appena torna la connessione"

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
            sendCrashLog()
            learnPublic(s.publicUrl)
            _screen.value = Screen.Main; refresh(); connect(); runCatching { NoticeJob.schedule(getApplication()) }
        } catch (e: ApiException) {
            if (e.device.isNotEmpty()) { _device.value = DeviceStatus(e.device, e.code, true); _screen.value = Screen.Device; return@launch }
            // offline: show the last saved archive if we have one
            if (_archive.value != null && prefs.getString("me", null) != null) {
                _me.value = runCatching { json.decodeFromString<Me>(prefs.getString("me", "")!!) }.getOrNull(); _online.value = false; _screen.value = Screen.Main; scheduleReconnect()
            } else { _screen.value = Screen.Login; _messages.tryEmit(e.message ?: "Server non raggiungibile") }
        }
    }

    private suspend fun loadCache() = withContext(Dispatchers.Default) {
        try {
            if (cacheFile.exists()) {
                // first start after the update: the old single file is split into sections (streamed, little memory)
                if (!sections.complete) runCatching { val st = File(files, "archive.new"); sections.commit(st, sections.split(cacheFile, st)); hashFile.writeText(sections.hashes()) }
                    .onFailure { sections.clear(); etagFile.delete(); hashFile.delete() }
                cacheFile.delete()
            }
            if (!sections.complete) return@withContext
            val st = sections.decode(); rawState = st; val ar = build(st); _archive.value = ar; etag = etagFile.takeIf { it.exists() }?.readText(); ar.warm() // shown at once, the rest is prepared here in the background
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) {
            // unreadable copy (or not enough memory for it): start clean, the archive is downloaded again
            sections.clear(); etagFile.delete(); hashFile.delete(); etag = null
        }
    }

    // Live updates. While a portal sync runs the server changes every second: the phone rebuilds its archive at most every
    // 8 s (one fresh picture, no constant work, no stutter); otherwise a short pause groups a burst of changes into one
    // download. A change arriving while an update is running is never lost: one more update follows.
    private var syncSeen = 0L; private var lastRefreshAt = 0L; private var livePending = false; private var againNow = false; private var liveJob: kotlinx.coroutines.Job? = null
    private fun liveRefresh() {
        if (refreshJob?.isActive == true) { livePending = true; return }
        if (liveJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val wait = if (now - syncSeen < 20_000) (lastRefreshAt + 8_000 - now).coerceAtLeast(400) else 400L
        liveJob = viewModelScope.launch { delay(wait); refresh() }
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
            againNow = true // asked while another update runs (e.g. right after saving): one more, straight after it
            return
        }
        livePending = false; againNow = false
        refreshJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis(); lastRefreshAt = t0
            if (userInitiated) _refreshing.value = true
            try {
                val have = if (_archive.value == null) null else withContext(Dispatchers.IO) { hashFile.takeIf { it.exists() }?.readText()?.ifBlank { null } }
                val download = File(files, "archive.download")
                var tag = try { api.stateTo(download, if (_archive.value == null) null else etag, have) }
                          catch (e: ApiException) { if (e.status == 404 && have != null) api.stateTo(download, null) else throw e } // older server: full archive
                if (tag != null) {
                    val parsed = try {
                        withContext(Dispatchers.Default) {
                            // the answer is split from the file into the section files; a partial one replaces only what changed
                            val staging = File(files, "archive.new")
                            var partial = sections.split(download, staging)
                            if (partial && !sections.complete) { tag = api.stateTo(download, null).orEmpty(); partial = sections.split(download, staging) } // no complete copy here: whole archive
                            sections.commit(staging, partial); download.delete()
                            sections.decode().let { st -> rawState = st; build(st) }.also { it.warm() }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: ApiException) { throw e
                    } catch (e: Throwable) {
                        // damaged copy or answer: the next update downloads the whole archive again (never a crash)
                        download.delete(); sections.clear(); etagFile.delete(); hashFile.delete(); etag = null
                        throw ApiException("Archivio da riscaricare: riprova tra un attimo")
                    }
                    _archive.value?.let { noticeAssigned(it.state.interventions, parsed.state.interventions) }
                    _archive.value = parsed; etag = tag?.ifEmpty { null }
                    withContext(Dispatchers.IO) { etag?.let { etagFile.writeText(it) } ?: etagFile.delete(); hashFile.writeText(sections.hashes()) }
                }
                _online.value = true
                flushOutbox()
            } catch (e: ApiException) {
                if (e.status == 401) { signedOut(); return@launch }
                if (e.device.isNotEmpty()) { stream?.cancel(); stream = null; _device.value = DeviceStatus(e.device, e.code, true); _screen.value = Screen.Device; return@launch }
                _online.value = false; if (userInitiated) _messages.tryEmit(e.message ?: "Aggiornamento non riuscito")
            } finally {
                if (userInitiated) withContext(kotlinx.coroutines.NonCancellable) { delay((MIN_SPIN - (System.currentTimeMillis() - t0)).coerceAtLeast(0)) }
                _refreshing.value = false
            }
        }
        refreshJob?.invokeOnCompletion {
            if (againNow) { againNow = false; livePending = false; viewModelScope.launch { refresh() } }
            else if (livePending) { livePending = false; viewModelScope.launch { liveRefresh() } }
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
                    "changed", "hello" -> liveRefresh()
                    "progress" -> { if (m.running) syncSeen = System.currentTimeMillis(); _progress.value = SyncProgress(m.running, m.text) }
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
        _screen.value = Screen.Main; refresh(); connect(); runCatching { NoticeJob.schedule(getApplication()) }; null
    } catch (e: ApiException) { if (e.device.isNotEmpty()) { _device.value = DeviceStatus(e.device, e.code, true); _screen.value = Screen.Device }; e.message }
    val lastUser: String get() = prefs.getString("last_user", "").orEmpty()

    fun logout() = viewModelScope.launch {
        stream?.cancel(); stream = null
        runCatching { Outbox.flush(api) } // what was saved without network leaves before the session closes
        api.logout(); signedOut(); runCatching { NoticeJob.cancel(getApplication()) }; prefs.edit().remove("notices_at").apply()
        withContext(Dispatchers.IO) { cacheFile.delete(); sections.clear(); etagFile.delete(); hashFile.delete() }; _archive.value = null
    }
    /** Interventions just assigned to me by someone else, new urgent ones: a system notification (announced once, also by the background check). */
    private fun noticeAssigned(prev: List<Intervention>, next: List<Intervention>) {
        val me = _me.value ?: return
        runCatching { Notifier.fromArchive(getApplication(), me, prev, next) }
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
    /** A crash recorded on the previous run goes to the office server once (then the file is removed). */
    private fun sendCrashLog() = viewModelScope.launch(Dispatchers.IO) {
        val f = it.topcash.studio.CrashLog.file(getApplication()); if (!f.exists()) return@launch
        runCatching { api.sendCrash(appVersion, f.readText()); f.delete() }
    }
    /** "Già rinnovata" on a licence (or back to the outcome it had before, when undone). */
    fun markRenewed(key: String, on: Boolean, client: String) {
        val m = _archive.value?.state?.renewals?.get(key)
        val status = if (on) "Rinnovato" else m?.steps.orEmpty().lastOrNull { it.status.isNotEmpty() && it.status != "Rinnovato" }?.status.orEmpty()
        act(if (on) "$client: licenza segnata come rinnovata" else "$client: di nuovo da rinnovare") {
            api.saveRenewal(RenewalRequest(key, status, m?.note.orEmpty(), if (on) "" else m?.nextContact.orEmpty()))
        }
    }
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
    private val _roster = MutableStateFlow<RosterView?>(null); val roster = _roster.asStateFlow()
    private val _autoClose = MutableStateFlow<AutoCloseRule?>(null); val autoClose = _autoClose.asStateFlow()
    fun loadAutoClose() = viewModelScope.launch { try { _autoClose.value = api.autoClose() } catch (_: ApiException) { } }
    fun saveLicenseRule(keepMonths: Int, renewedUntil: String) = viewModelScope.launch {
        try { val r = api.saveLicenseRule(LicenseRuleSave(keepMonths, renewedUntil))
            _messages.tryEmit("Salvato: tolte dopo ${if (r.keepMonths == 1) "1 mese" else "${r.keepMonths} mesi"}" + (if (r.renewedUntil.isNotBlank()) ", rinnovate fino a ${monthLabel(r.renewedUntil)}" else "")); refresh(true) }
        catch (e: ApiException) { _messages.tryEmit(e.message ?: "Impossibile salvare") }
    }
    fun saveMoney(enabled: Boolean) = viewModelScope.launch {
        try { api.saveMoney(MoneySave(enabled)); _messages.tryEmit(if (enabled) "Incassi e note di credito spostati in Contabilità" else "Incassi e note di credito tornano tra gli interventi"); refresh(true) }
        catch (e: ApiException) { _messages.tryEmit(e.message ?: "Impossibile salvare") }
    }
    fun saveAutoClose(enabled: Boolean, days: Int) = viewModelScope.launch {
        try { val r = api.saveAutoClose(AutoCloseSave(enabled, days)); _autoClose.value = r
            _messages.tryEmit(if (!r.enabled) "Chiusura automatica disattivata" else if (r.closed > 0) "Salvato: chiusi ${r.closed} interventi aperti da oltre ${r.days} giorni" else "Salvato: chiusura dopo ${r.days} giorni")
            refresh(true)
        } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Operazione non riuscita") }
    }
    private val _portals = MutableStateFlow<PortalList?>(null); val portals = _portals.asStateFlow()
    fun loadPortals() = viewModelScope.launch { try { _portals.value = api.portals() } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Portali non disponibili") } }
    fun portalEnabled(id: String, on: Boolean, then: () -> Unit = {}) = viewModelScope.launch {
        try { api.portalEnabled(id, on); _portals.value = api.portals(); then() } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Impossibile salvare") } }
    fun portalAction(id: String, action: String, done: String) = viewModelScope.launch {
        try { api.portalAction(id, action); _messages.tryEmit(done); kotlinx.coroutines.delay(1500); _portals.value = api.portals() } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Operazione non riuscita") } }
    suspend fun portalView(id: String) = api.portalView(id)
    fun portalDone(id: String) = viewModelScope.launch {
        if (portalInput(id, PortalInput("done")).isSuccess) _messages.tryEmit("Controllo l’accesso: se è riuscito la lettura parte da sola")
        delay(1500); runCatching { _portals.value = api.portals() } }
    suspend fun portalInput(id: String, i: PortalInput) = runCatching { api.portalInput(id, i) }.onFailure { _messages.tryEmit(it.message ?: "Portale non raggiungibile") }
    fun loadUsers() = viewModelScope.launch { try { val r = api.usersFull(); _users.value = r.users; _roster.value = r.roster } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Utenti non disponibili") } }
    /** Agent on/off or hidden on/off for one person: shown at once, then confirmed by the server (undone if it refuses). */
    fun toggleRoster(name: String, agent: Boolean) = viewModelScope.launch {
        val before = _roster.value ?: return@launch
        val people = before.people.map { p -> if (p.name != name) p else if (agent) p.copy(agent = !p.agent) else p.copy(hidden = !p.hidden, agent = false) }
        _roster.value = before.copy(people = people)
        try {
            _roster.value = api.saveRoster(RosterSave(people.filter { it.hidden }.map { it.name }, people.filter { it.agent && !it.hidden }.map { it.name }))
            val p = people.first { it.name == name }
            _messages.tryEmit(if (agent) (if (p.agent) "$name è anche agente" else "$name non è più agente") else (if (p.hidden) "$name nascosto ovunque" else "$name di nuovo visibile"))
            refresh(true)
        } catch (x: ApiException) { _roster.value = before; _messages.tryEmit(x.message ?: "Operazione non riuscita") }
    }
    private fun userAct(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        try { block(); _messages.tryEmit(success); _users.value = api.users() } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Operazione non riuscita") }
    }
    fun createUser(u: UserCreate) = userAct("Utente ${u.name} creato") { api.createUser(u) }
    fun updateUser(username: String, p: UserPatch, success: String) = userAct(success) { api.updateUser(username, p) }
    /** Changes the sign-in name (capitals kept, as typed). Renaming yourself ends your session: sign in again with the new name, same password. */
    fun renameUser(username: String, newName: String, self: Boolean) = viewModelScope.launch {
        try { api.renameUser(username, newName)
            if (self) { _messages.tryEmit("Da ora accedi con «$newName» (stessa password)"); logout() }
            else { _messages.tryEmit("Da ora accede con «$newName» (stessa password)"); _users.value = api.users() }
        } catch (x: ApiException) { _messages.tryEmit(x.message ?: "Operazione non riuscita") }
    }
    fun deleteUser(username: String) = userAct("Utente eliminato") { api.deleteUser(username) }
    fun renameMe(name: String) = userAct("Nome aggiornato") { api.updateMe(name); _me.value = _me.value?.copy(name = name) }
    fun changePassword(current: String, password: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        try { api.changePassword(current, password); _messages.tryEmit("Password aggiornata: accedi di nuovo"); onDone(true); logout() }
        catch (x: ApiException) { _messages.tryEmit(x.message ?: "Password non aggiornata"); onDone(false) }
    }
    fun saveNote(code: String, note: String) = viewModelScope.launch {
        try { api.saveNote(code, note); Outbox.drop(Outbox.NOTE, code); _messages.tryEmit("Nota salvata e condivisa"); refresh() }
        catch (e: ApiException) {
            if (e.status == 0) { Outbox.add(PendingOp(Outbox.newId(), Outbox.NOTE, json.encodeToString(NoteRequest.serializer(), NoteRequest(code, note)), code, "Nota cliente", nowMs())); _messages.tryEmit(offlineText) }
            else _messages.tryEmit(e.message ?: "Nota non salvata")
        }
    }
    fun saveMeta(code: String, meta: ClientMeta, message: String = "Promemoria aggiornato") = act(message) { api.saveMeta(code, meta) }
    /** [offline]: without network the change is kept on the phone and sent later (not for signing or printing, which need the server). */
    fun saveIntervention(i: Intervention, quiet: Boolean = false, offline: Boolean = true, onDone: (Intervention) -> Unit) = viewModelScope.launch {
        if (i.id.startsWith("local-")) { // created without network and not sent yet: the newest version replaces the queued one
            if (!offline) { _messages.tryEmit("Serve la connessione: l'intervento non è ancora arrivato in ufficio"); return@launch }
            val q = queueIntervention(i); if (!quiet) { _messages.tryEmit("Intervento salvato"); onDone(q) }; flushOutbox(); return@launch
        }
        try { val r = api.saveIntervention(i); Outbox.drop(Outbox.INTERVENTION, i.id); if (!quiet) _messages.tryEmit("Intervento salvato"); onDone(r); refresh() }
        catch (e: ApiException) {
            if (e.status == 0 && offline) { val q = queueIntervention(i); _messages.tryEmit(offlineText); if (!quiet) onDone(q) }
            else _messages.tryEmit(e.message ?: "Salvataggio non riuscito")
        }
    }
    /** A stop of today's round done (or back to do): shown at once, sent now or as soon as there is network. */
    fun markRoute(key: String, done: Boolean) = viewModelScope.launch {
        _routeLocal.value = _routeLocal.value + (key to done)
        Outbox.add(PendingOp(Outbox.newId(), Outbox.ROUTE, json.encodeToString(RouteDoneRequest.serializer(), RouteDoneRequest(key, done)), key, "Tappa del giro", nowMs()))
        if (flushing?.isActive == true) return@launch
        val r = Outbox.flush(api)
        if (r.offline) _messages.tryEmit("Senza rete: tappa segnata sul telefono, la vede anche l'ufficio appena torna la connessione")
        r.failed.take(3).forEach { _messages.tryEmit("Non inviata · $it") }
    }
    // ── photos of the intervention: kept on the phone until they reach the server
    fun addPhotos(interventionId: String, jpgs: List<ByteArray>) = viewModelScope.launch {
        if (jpgs.isEmpty()) return@launch
        withContext(Dispatchers.IO) { jpgs.forEach { jpg -> val id = Outbox.newId(); Outbox.photoFile(id).writeBytes(jpg); Outbox.add(PendingOp(id, Outbox.PHOTO, "", interventionId, "Foto", nowMs())) } }
        flushing?.join()
        val r = Outbox.flush(api)
        when {
            r.offline -> _messages.tryEmit("Senza rete: foto tenute sul telefono, partono da sole appena torna la connessione")
            interventionId.startsWith("local-") -> _messages.tryEmit("Foto tenute sul telefono: partono con l'intervento")
            r.failed.isEmpty() -> _messages.tryEmit(if (jpgs.size == 1) "Foto aggiunta" else "${jpgs.size} foto aggiunte")
        }
        r.failed.take(3).forEach { _messages.tryEmit("Non inviata · $it") }
    }
    fun deletePhoto(id: String, pending: Boolean) {
        if (pending) { Outbox.remove(id); _messages.tryEmit("Foto tolta"); return }
        act("Foto tolta") { api.deletePhoto(id); withContext(Dispatchers.IO) { File(photoDir, "$id.jpg").delete() } }
    }
    /** A photo of the server, kept in the cache (a photo never changes). */
    suspend fun photo(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = File(photoDir, "$id.jpg")
        if (f.exists()) return@withContext runCatching { f.readBytes() }.getOrNull()
        runCatching { api.photo(id) }.getOrNull()?.also { b -> runCatching { photoDir.mkdirs(); f.writeBytes(b) } }
    }
    /** Machines to check: "dismessa" (left the customer) or "attiva" (still in use); "" undoes the decision. */
    fun flagMachines(ids: List<String>, state: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        try {
            api.flagMachines(ids, state); onDone(); refresh()
            val one = ids.size == 1
            _messages.tryEmit(when (state) {
                "dismessa" -> if (one) "Macchina segnata come dismessa" else "${ids.size} macchine segnate come dismesse"
                "attiva" -> if (one) "Macchina confermata ancora attiva" else "${ids.size} macchine confermate ancora attive"
                else -> if (one) "Macchina di nuovo da controllare" else "${ids.size} macchine di nuovo da controllare"
            })
        } catch (e: ApiException) { _messages.tryEmit(e.message ?: "Operazione non riuscita") }
    }
    fun saveDueNote(id: String, status: String, note: String) = act(if (status.isBlank()) "Nota salvata" else "Scadenza segnata come “$status”") { api.saveDueNote(id, status, note) }
    fun deleteIntervention(id: String) = act("Intervento eliminato") { api.deleteIntervention(id) }
    fun saveMovements(m: List<Movement>, onDone: () -> Unit) = viewModelScope.launch {
        try { api.saveMovements(m); _messages.tryEmit(if (m.size == 1) "Movimento registrato" else "${m.size} movimenti registrati"); onDone(); refresh() }
        catch (e: ApiException) { _messages.tryEmit(e.message ?: "Movimento non registrato") }
    }

    fun saveArticle(x: Article, isNew: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        try { api.saveArticle(x); _messages.tryEmit(if (isNew) "Articolo creato" else "Articolo salvato"); onDone(); refresh() }
        catch (e: ApiException) { _messages.tryEmit(e.message ?: "Articolo non salvato") }
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
