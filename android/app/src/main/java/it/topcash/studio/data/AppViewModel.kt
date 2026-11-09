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
    /** Code shown on the "authorize this device" screen, and its status (pending / revoked). */
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

    init { start() }

    private fun start() = viewModelScope.launch {
        if (server.isBlank()) { _screen.value = Screen.Setup; return@launch }
        api.deviceKey = deviceKey
        api.bases = listOf(server, serverExternal).filter { it.isNotBlank() }; api.base = server
        if (_archive.value == null) loadCache()
        try {
            if (api.bases.size > 1) api.choose()
            // outside the office the server only talks to approved devices
            // an older server has no device check yet (404): behave as in the office
            val h = try { api.hello() } catch (e: ApiException) { if (e.status == 404) DeviceStatus("office") else throw e }
            if (h.remote && h.status != "approved") { _device.value = h; _screen.value = Screen.Device; return@launch }
            val s = api.session()
            if (s.user == null) { _screen.value = Screen.Login; return@launch }
            _me.value = s.user; prefs.edit().putString("me", json.encodeToString(Me.serializer(), s.user)).apply()
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
        runCatching { _archive.value = Archive(json.decodeFromString<ArchiveState>(cacheFile.readText())); etag = etagFile.takeIf { it.exists() }?.readText() }
    }

    fun refresh(userInitiated: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (userInitiated) _refreshing.value = true
            try {
                val r = api.state(if (_archive.value == null) null else etag)
                if (r != null) {
                    val (body, tag) = r
                    val parsed = withContext(Dispatchers.Default) { Archive(json.decodeFromString<ArchiveState>(body)) }
                    _archive.value = parsed; etag = tag
                    withContext(Dispatchers.IO) { cacheFile.writeText(body); tag?.let { etagFile.writeText(it) } }
                }
                _online.value = true
            } catch (e: ApiException) {
                if (e.status == 401) { signedOut(); return@launch }
                if (e.device.isNotEmpty()) { stream?.cancel(); stream = null; _device.value = DeviceStatus(e.device, e.code, true); _screen.value = Screen.Device; return@launch }
                _online.value = false; if (userInitiated) _messages.tryEmit(e.message ?: "Aggiornamento non riuscito")
            } finally { _refreshing.value = false }
        }
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
        if (value) { if (_screen.value == Screen.Main) { refresh(); connect() } }
        else { stream?.cancel(); stream = null; reconnect?.cancel() }
    }

    fun setServer(input: String, external: String = ""): String? {
        val url = Api.normalize(input) ?: return "Indirizzo non valido"
        val ext = if (external.isBlank()) "" else Api.normalizeExternal(external) ?: return "Indirizzo esterno non valido: usa il nome del dominio, es. topcash.tuodominio.it"
        prefs.edit().putString("server", url).putString("server_ext", ext).apply()
        _screen.value = Screen.Loading; start(); return null
    }
    /** "Controlla ora" on the authorization screen; also polled every few seconds. */
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
        withContext(Dispatchers.IO) { cacheFile.delete(); etagFile.delete() }; _archive.value = null
    }
    private fun signedOut() { _me.value = null; prefs.edit().remove("me").apply(); _screen.value = Screen.Login }

    fun notify(text: String) { _messages.tryEmit(text) }
    fun setTheme(t: String) { _theme.value = t; prefs.edit().putString("theme", t).apply() }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        try { block(); _messages.tryEmit(success); refresh() } catch (e: ApiException) { _messages.tryEmit(e.message ?: "Operazione non riuscita") }
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
