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

class ApiException(message: String, val status: Int = 0) : IOException(message)

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
            chain.proceed(chain.request().newBuilder().header("X-Requested-With", "topcash").header("User-Agent", "TopCashStudio-Android/2.0").build())
        }
        .build()
    private val streamHttp = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() // SSE: the server pings every 25 s
    var base: String = ""

    private fun url(path: String) = base.trimEnd('/') + path
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun Response.errorText(): String {
        val body = body.string()
        return runCatching { json.decodeFromString<ServerError>(body).error }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: when (code) { 401 -> "Accesso richiesto"; 403 -> "Non hai i permessi per questa operazione"; 429 -> "Troppi tentativi: attendi un minuto"; else -> "Errore del server ($code)" }
    }
    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw ApiException(r.errorText(), r.code); r.body.string() }
        } catch (e: ApiException) { throw e } catch (e: IOException) { throw ApiException("Server non raggiungibile. Controlla la rete dell'ufficio.") }
    }
    private suspend fun get(path: String) = call(Request.Builder().url(url(path)).get().build())
    private suspend fun post(path: String, body: String = "{}") = call(Request.Builder().url(url(path)).post(body.toRequestBody(jsonType)).build())

    suspend fun session(): Session = json.decodeFromString(get("/api/session"))
    suspend fun login(username: String, password: String): Me = json.decodeFromString(post("/api/login", json.encodeToString(LoginRequest(username, password, true))))
    suspend fun logout() { runCatching { post("/api/logout") }; cookies.clear() }
    suspend fun saveNote(code: String, note: String) { post("/api/note", json.encodeToString(NoteRequest(code, note))) }
    suspend fun saveMeta(code: String, meta: ClientMeta) { post("/api/meta", json.encodeToString(MetaRequest(code, meta))) }
    suspend fun saveIntervention(i: Intervention): Intervention = json.decodeFromString(post("/api/interventions", json.encodeToString(i)))
    suspend fun deleteIntervention(id: String) { post("/api/interventions/" + java.net.URLEncoder.encode(id, "UTF-8") + "/delete") }

    /** Returns null when the archive did not change (HTTP 304). */
    suspend fun state(etag: String?): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url("/api/state")).get()
        if (!etag.isNullOrEmpty()) b.header("If-None-Match", etag)
        try {
            http.newCall(b.build()).execute().use { r ->
                if (r.code == 304) return@withContext null
                if (!r.isSuccessful) throw ApiException(r.errorText(), r.code)
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
