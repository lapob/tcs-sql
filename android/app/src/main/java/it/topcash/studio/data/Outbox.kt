package it.topcash.studio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** A change made without network, waiting to reach the server. [ref] is the intervention id (or the stop key, the client code). */
@Serializable data class PendingOp(val id: String, val kind: String, val body: String = "", val ref: String = "", val label: String = "", val at: Long = 0)
data class FlushResult(val sent: Int = 0, val failed: List<String> = emptyList(), val offline: Boolean = false)

/**
 * Changes made in a basement or in the countryside are kept on the phone and sent by themselves as soon as the network
 * is back (when the app is opened, at every update and by the background check every quarter of an hour).
 * Kept: interventions (new and edited, quick close / postpone), stops of the round done, notes, photos.
 * One queue for the app and the background job; a lock makes sure nothing is sent twice.
 */
object Outbox {
    const val INTERVENTION = "intervention"; const val ROUTE = "route"; const val NOTE = "note"; const val PHOTO = "photo"
    private val lock = Mutex()
    private var dir: File? = null
    private val _items = MutableStateFlow<List<PendingOp>>(emptyList()); val items = _items.asStateFlow()

    @Synchronized fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.filesDir, "outbox").apply { mkdirs() }; dir = d
        _items.value = runCatching { json.decodeFromString<List<PendingOp>>(File(d, "queue.json").readText()) }.getOrDefault(emptyList())
    }
    private fun file() = File(dir!!, "queue.json")
    @Synchronized private fun save() {
        val tmp = File(dir!!, "queue.tmp"); tmp.writeText(json.encodeToString(_items.value))
        if (!tmp.renameTo(file())) { file().delete(); tmp.renameTo(file()) }
    }
    fun photoFile(id: String) = File(dir!!, "$id.jpg")
    fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    /** The latest change of the same thing replaces the older one (two edits of one intervention = one send). */
    @Synchronized fun add(op: PendingOp) {
        val l = _items.value.filterNot { it.kind != PHOTO && it.kind == op.kind && it.ref == op.ref }
        _items.value = l + op; save()
    }
    /** Saved online meanwhile: the older offline copy must not overwrite it later. */
    @Synchronized fun drop(kind: String, ref: String) {
        if (_items.value.none { it.kind == kind && it.ref == ref }) return
        _items.value = _items.value.filterNot { it.kind == kind && it.ref == ref }; save()
    }
    @Synchronized fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }; save()
        runCatching { photoFile(id).delete() }
    }
    /** The offline edit of an intervention, shown in the editor in place of the server copy. */
    fun pendingIntervention(id: String?): Intervention? = id?.let { i -> _items.value.lastOrNull { it.kind == INTERVENTION && it.ref == i } }
        ?.let { runCatching { json.decodeFromString<Intervention>(it.body) }.getOrNull() }
    fun pendingInterventions(): List<Intervention> = _items.value.filter { it.kind == INTERVENTION }.mapNotNull { runCatching { json.decodeFromString<Intervention>(it.body) }.getOrNull() }
    fun pendingPhotos(interventionId: String) = _items.value.filter { it.kind == PHOTO && it.ref == interventionId }

    /** A new intervention got its real id: photos and stops waiting for it follow. */
    @Synchronized private fun remap(local: String, real: String) {
        _items.value = _items.value.map { op ->
            when {
                op.kind == PHOTO && op.ref == local -> op.copy(ref = real)
                op.kind == ROUTE && op.ref == "i$local" -> op.copy(ref = "i$real", body = json.encodeToString(RouteDoneRequest("i$real", json.decodeFromString<RouteDoneRequest>(op.body).done)))
                else -> op
            }
        }; save()
    }

    suspend fun flush(api: Api): FlushResult = lock.withLock {
        if (dir == null || _items.value.isEmpty()) return@withLock FlushResult()
        var sent = 0; val failed = mutableListOf<String>()
        // interventions first: photos and stops may wait for a new intervention's id
        for (op in _items.value.sortedBy { if (it.kind == INTERVENTION) 0 else 1 }) {
            val now = _items.value.firstOrNull { it.id == op.id } ?: continue // remapped meanwhile: take the fresh copy
            try {
                when (now.kind) {
                    INTERVENTION -> {
                        val i = json.decodeFromString<Intervention>(now.body)
                        val local = i.id.startsWith("local-")
                        val r = api.saveIntervention(if (local) i.copy(id = "") else i)
                        if (local) remap(i.id, r.id)
                    }
                    ROUTE -> { val r = json.decodeFromString<RouteDoneRequest>(now.body); if (r.key.startsWith("ilocal-")) continue; api.routeDone(r.key, r.done) }
                    NOTE -> { val n = json.decodeFromString<NoteRequest>(now.body); api.saveNote(n.code, n.note) }
                    PHOTO -> {
                        if (now.ref.startsWith("local-")) continue // its intervention has not reached the server yet
                        val f = photoFile(now.id); if (f.exists()) api.addPhoto(now.ref, withContext(Dispatchers.IO) { f.readBytes() })
                    }
                }
                remove(now.id); sent++
            } catch (e: ApiException) {
                if (e.status == 0) return@withLock FlushResult(sent, failed, offline = true) // still no network: try again later
                remove(now.id); failed += "${now.label}: ${e.message}" // refused by the server (e.g. signed meanwhile): it would never pass
            }
        }
        FlushResult(sent, failed)
    }
}
