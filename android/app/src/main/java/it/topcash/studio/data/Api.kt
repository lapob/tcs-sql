package it.topcash.studio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

/** [device] is set when the server refuses this phone from outside the office ("unknown", "pending", "revoked"). */
class ApiException(message: String, val status: Int = 0, val device: String = "", val code: String = "") : IOException(message)

/** Session cookie kept in app-private storage (excluded from backups), so the user stays signed in. */
private class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)
    private val store = mutableMapOf<String, Cookie>()
    init {
        prefs.all.values.forEach { v ->
            (v as? String)?.let { line ->
                val parts = line.split('\u0001'); if (parts.size == 2) parts[0].toHttpUrlOrNull()?.let { url -> Cookie.parse(url, parts[1])?.let { c -> store[c.name + "@" + c.domain] = c } }
            }
        }
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val e = prefs.edit()
        cookies.forEach { c ->
            val key = c.name + "@" + c.domain
            if (c.expiresAt < System.currentTimeMillis()) { store.remove(key); e.remove(key) }
            else { store[key] = c; e.putString(key, url.toString() + '\u0001' + c.toString()) }
        }
        e.apply()
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.filter { it.expiresAt > now && it.matches(url) }
    }
    @Synchronized fun clear() { store.clear(); prefs.edit().clear().apply() }
}

/** Client for the Top Cash server REST API + Server-Sent Events live stream. */
class Api(context: Context) {
    private val cookies = PersistentCookieJar(context)
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val b = chain.request().newBuilder().header("X-Requested-With", "topcash").header("User-Agent", "TopCashStudio-Android/2.2")
            deviceKey.takeIf { it.isNotEmpty() }?.let { b.header("X-Device-Key", it) }
            chain.proceed(b.build())
        }
        .build()
    private val streamHttp = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() // SSE: the server pings every 25 s
    var base: String = ""
    /** Office address first, then the external one (Cloudflare tunnel, https). */
    var bases: List<String> = emptyList()
    var deviceKey: String = ""
    val deviceName: String = (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim()
    private val probeHttp by lazy { http.newBuilder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(4, TimeUnit.SECONDS).retryOnConnectionFailure(false).build() }
    /** Picks the first reachable address (office Wi-Fi is tried first: faster and stays on the LAN). */
    suspend fun choose(): Boolean = withContext(Dispatchers.IO) {
        for (b in bases.distinct()) {
            val ok = runCatching { probeHttp.newCall(Request.Builder().url(b.trimEnd('/') + "/api/health").get().build()).execute().use { it.isSuccessful } }.getOrDefault(false)
            if (ok) { base = b; return@withContext true }
        }
        false
    }
    val isExternal: Boolean get() = bases.size > 1 && base == bases.last() && base.startsWith("https")

    private fun url(path: String) = base.trimEnd('/') + path
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun Response.error(): ApiException {
        val body = body.string()
        val e = runCatching { json.decodeFromString<ServerError>(body) }.getOrNull()
        val msg = e?.error?.takeIf { it.isNotBlank() }
            ?: when (code) { 401 -> "Accesso richiesto"; 403 -> "Non hai i permessi per questa operazione"; 429 -> "Troppi tentativi: attendi un minuto"; else -> "Errore del server ($code)" }
        return ApiException(msg, code, e?.device.orEmpty(), e?.code.orEmpty())
    }
    /** Runs the request; on a network failure re-picks the address (office ⇄ external) and retries once. */
    private suspend fun call(build: () -> Request): String = withContext(Dispatchers.IO) {
        suspend fun once() = http.newCall(build()).execute().use { r -> if (!r.isSuccessful) throw r.error(); r.body.string() }
        try { once() } catch (e: ApiException) { throw e } catch (e: IOException) {
            val before = base
            if (bases.size > 1 && choose() && base != before) try { return@withContext once() } catch (e2: ApiException) { throw e2 } catch (_: IOException) {}
            throw ApiException(if (bases.size > 1) "Server non raggiungibile. Controlla la connessione a Internet." else "Server non raggiungibile. Controlla la rete dell'ufficio.")
        }
    }
    private suspend fun get(path: String) = call { Request.Builder().url(url(path)).get().build() }
    private suspend fun post(path: String, body: String = "{}") = call { Request.Builder().url(url(path)).post(body.toRequestBody(jsonType)).build() }
    suspend fun hello(): DeviceStatus = json.decodeFromString(post("/api/device/hello", json.encodeToString(DeviceHello(deviceName, "Android " + android.os.Build.VERSION.RELEASE))))

    suspend fun session(): Session = json.decodeFromString(get("/api/session"))
    suspend fun login(username: String, password: String): Me = json.decodeFromString(post("/api/login", json.encodeToString(LoginRequest(username, password, true, deviceName, "Android " + android.os.Build.VERSION.RELEASE))))
    suspend fun logout() { runCatching { post("/api/logout") }; cookies.clear() }
    suspend fun saveNote(code: String, note: String) { post("/api/note", json.encodeToString(NoteRequest(code, note))) }
    suspend fun saveMeta(code: String, meta: ClientMeta) { post("/api/meta", json.encodeToString(MetaRequest(code, meta))) }
    suspend fun saveIntervention(i: Intervention): Intervention = json.decodeFromString(post("/api/interventions", json.encodeToString(i)))
    suspend fun saveDueNote(id: String, status: String, note: String) { post("/api/accounting/due-note", json.encodeToString(DueNoteRequest(id, status, note))) }
    suspend fun saveMovements(m: List<Movement>) { post("/api/warehouse/movements", json.encodeToString(MovementsRequest(m))) }
    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    suspend fun signIntervention(id: String, r: SignRequest): Intervention = json.decodeFromString(post("/api/interventions/" + enc(id) + "/sign", json.encodeToString(r)))
    suspend fun unsignIntervention(id: String) { post("/api/interventions/" + enc(id) + "/unsign") }
    suspend fun sendReport(id: String, to: String): Intervention = json.decodeFromString(post("/api/interventions/" + enc(id) + "/send-report", json.encodeToString(SendReportRequest(to))))
    /** Downloads the numbered PDF report into [dir]; returns the file. */
    suspend fun reportPdf(id: String, dir: java.io.File, name: String): java.io.File = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url("/api/interventions/" + enc(id) + "/report.pdf")).get().build()).execute().use { r ->
                if (!r.isSuccessful) throw r.error()
                dir.mkdirs(); dir.listFiles()?.forEach { it.delete() } // keep only the latest report in the cache
                java.io.File(dir, name).also { f -> f.outputStream().use { r.body.byteStream().copyTo(it) } }
            }
        } catch (e: ApiException) { throw e } catch (e: IOException) { throw ApiException("Server non raggiungibile") }
    }
    // administrator: customers, licences, users
    suspend fun saveClient(code: String, e: ClientEdit): String = json.decodeFromString<SavedCode>(post("/api/clients/save", json.encodeToString(ClientSaveRequest(code, e)))).code
    suspend fun deleteClient(code: String, deleted: Boolean) { post("/api/clients/delete", json.encodeToString(ClientDeleteRequest(code, deleted))) }
    suspend fun resetClient(code: String) { post("/api/clients/reset", json.encodeToString(ClientDeleteRequest(code, false))) }
    suspend fun saveLicense(id: String, e: LicenseEdit) { post("/api/licenses/save", json.encodeToString(LicenseSaveRequest(id, e))) }
    suspend fun users(): List<UserRow> = json.decodeFromString<UsersResponse>(get("/api/users")).users
    suspend fun createUser(u: UserCreate) { post("/api/users", json.encodeToString(u)) }
    suspend fun updateUser(username: String, p: UserPatch) { post("/api/users/" + enc(username), json.encodeToString(p)) }
    suspend fun deleteUser(username: String) { post("/api/users/" + enc(username) + "/delete") }
    suspend fun updateMe(name: String) { post("/api/me", json.encodeToString(UserPatch(name = name))) }
    suspend fun changePassword(current: String, password: String) { post("/api/password", json.encodeToString(PasswordChange(current, password))) }
    suspend fun deleteIntervention(id: String) { post("/api/interventions/" + java.net.URLEncoder.encode(id, "UTF-8") + "/delete") }

    /** Returns null when the archive did not change (HTTP 304). */
    suspend fun state(etag: String?): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url("/api/state")).get()
        if (!etag.isNullOrEmpty()) b.header("If-None-Match", etag)
        try {
            http.newCall(b.build()).apply { timeout().timeout(25, TimeUnit.SECONDS) }.execute().use { r ->
                if (r.code == 304) return@withContext null
                if (!r.isSuccessful) throw r.error()
                r.body.string() to r.header("ETag")
            }
        } catch (e: ApiException) { throw e } catch (e: IOException) { throw ApiException("Server non raggiungibile") }
    }

    fun stream(onMessage: (StreamMessage) -> Unit, onOpen: () -> Unit, onFailure: () -> Unit): EventSource =
        EventSources.createFactory(streamHttp).newEventSource(Request.Builder().url(url("/api/stream")).build(), object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) = onOpen()
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { json.decodeFromString<StreamMessage>(data) }.getOrNull()?.let(onMessage)
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) = onFailure()
            override fun onClosed(eventSource: EventSource) = onFailure()
        })

    companion object {
        /** External address: "topcash.example.it" -> "https://topcash.example.it/" (the tunnel always uses https). */
        fun normalizeExternal(input: String): String? {
            var s = input.trim(); if (s.isEmpty()) return null
            if (!s.contains("://")) s = "https://$s"
            val u = s.toHttpUrlOrNull() ?: return null
            if (u.scheme != "https" || !u.host.contains('.')) return null
            return "https://${u.host}${if (u.port != 443) ":" + u.port else ""}/"
        }
        /** "192.168.1.220" -> "http://192.168.1.220:8470/" (default Top Cash port), keeps explicit scheme/port. */
        fun normalize(input: String): String? {
            var s = input.trim(); if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val u = s.toHttpUrlOrNull() ?: return null
            val explicitPort = Regex("^[a-zA-Z]+://[^/]+:\\d+").containsMatchIn(s)
            val port = if (explicitPort) u.port else if (u.scheme == "http") 8470 else u.port
            val portPart = if ((u.scheme == "http" && port == 80) || (u.scheme == "https" && port == 443)) "" else ":$port"
            return "${u.scheme}://${u.host}$portPart/"
        }
    }
}
