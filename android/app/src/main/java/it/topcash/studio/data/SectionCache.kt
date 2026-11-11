package it.topcash.studio.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.io.SequenceInputStream
import java.io.Writer
import java.util.Collections

/**
 * The saved archive, one file per top-level section ("clients.json", "interventions.json", …).
 *
 * The server answers {"clients":…, "interventions":…, "hashes":{…}, "version":n} (or only the changed sections plus
 * "partial":true). The answer is split straight from the downloaded file into the section files and the archive is
 * decoded as a stream over them: no step ever holds the whole archive as one string or as a JSON tree. With tens of
 * thousands of rows that is what ran a phone out of memory (OutOfMemoryError while merging a partial update).
 */
class SectionCache(private val dir: File) {
    private val hashesFile get() = File(dir, "hashes.json")

    /** A complete copy is on disk (the hashes section is written last). */
    val complete: Boolean get() = hashesFile.exists()

    /** Splits a downloaded answer into [staging]; returns true when it was a partial answer. */
    fun split(answer: File, staging: File): Boolean {
        staging.deleteRecursively(); staging.mkdirs()
        var partial = false
        answer.bufferedReader(Charsets.UTF_8, 64 * 1024).use { r ->
            splitObject(r) { key ->
                if (key == "partial") { partial = true; null }
                else if (!SAFE.matches(key)) null
                else File(staging, "$key.json").bufferedWriter(Charsets.UTF_8, 64 * 1024)
            }
        }
        return partial
    }

    /** Moves the sections in [staging] into place. A full answer replaces everything; a partial one only what it carries.
     *  The hashes are moved last, so an interrupted update is never taken for a complete one. */
    fun commit(staging: File, partial: Boolean) {
        dir.mkdirs()
        if (!partial) dir.listFiles()?.forEach { if (it.name != "hashes.json") it.delete() }
        val files = staging.listFiles().orEmpty().sortedBy { it.name == "hashes.json" }
        for (f in files) { val dst = File(dir, f.name); if (!f.renameTo(dst)) { f.copyTo(dst, overwrite = true); f.delete() } }
        staging.deleteRecursively()
    }

    /** The archive as one JSON object, read from the section files as a stream. */
    @OptIn(ExperimentalSerializationApi::class)
    fun decode(): ArchiveState {
        val parts = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        val streams = ArrayList<InputStream>(parts.size * 2 + 2)
        streams += ByteArrayInputStream("{".toByteArray())
        parts.forEachIndexed { i, f ->
            streams += ByteArrayInputStream(((if (i > 0) "," else "") + "\"" + f.name.removeSuffix(".json") + "\":").toByteArray())
            streams += f.inputStream().buffered(64 * 1024)
        }
        streams += ByteArrayInputStream("}".toByteArray())
        return SequenceInputStream(Collections.enumeration(streams)).use { json.decodeFromStream<ArchiveState>(it) }
    }

    /** "name=hash,…" of the saved sections, sent to the server to download only what changed. */
    fun hashes(): String = runCatching {
        (json.parseToJsonElement(hashesFile.readText()) as JsonObject).entries.joinToString(",") { (k, v) -> k + "=" + (v as JsonPrimitive).content }
    }.getOrDefault("")

    fun clear() { dir.deleteRecursively() }

    companion object {
        private val SAFE = Regex("^[A-Za-z0-9_]{1,40}$")

        /**
         * Streams the members of a top-level JSON object: for each key [open] gives the writer that receives the raw
         * value (or null to skip it). Strings, escapes and nesting are followed character by character.
         */
        fun splitObject(input: Reader, open: (String) -> Writer?) {
            val r = Src(input)
            var c = r.skipWs()
            if (c != '{'.code) throw IllegalStateException("Archivio non valido")
            while (true) {
                c = r.skipWs()
                if (c == '}'.code || c == -1) return
                if (c == ','.code) continue
                if (c != '"'.code) throw IllegalStateException("Archivio non valido")
                val key = StringBuilder()
                while (true) { val k = r.next(); if (k == -1) return; if (k == '\\'.code) { key.append(r.next().toChar()); continue }; if (k == '"'.code) break; key.append(k.toChar()) }
                if (r.skipWs() != ':'.code) throw IllegalStateException("Archivio non valido")
                val w = open(key.toString())
                val out = CharArray(32 * 1024); var n = 0
                fun put(ch: Int) { if (w == null) return; if (n == out.size) { w.write(out, 0, n); n = 0 }; out[n++] = ch.toChar() }
                try {
                    var depth = 0; var inStr = false; var esc = false; var started = false
                    while (true) {
                        val ch = r.next(); if (ch == -1) return
                        if (!started && Character.isWhitespace(ch)) continue
                        started = true
                        if (inStr) {
                            put(ch)
                            if (esc) esc = false else if (ch == '\\'.code) esc = true else if (ch == '"'.code) inStr = false
                            continue
                        }
                        when (ch) {
                            '"'.code -> { inStr = true; put(ch) }
                            '{'.code, '['.code -> { depth++; put(ch) }
                            '}'.code, ']'.code -> {
                                if (depth == 0) return   // end of the whole object right after a plain value
                                depth--; put(ch)
                            }
                            ','.code -> if (depth == 0) break else put(ch)
                            else -> if (depth == 0 && Character.isWhitespace(ch)) Unit else put(ch)
                        }
                    }
                } finally { if (w != null) { if (n > 0) w.write(out, 0, n); w.close() } }
            }
        }

        /** Reads in large blocks (one call per character on a Reader is slow on tens of megabytes). */
        private class Src(private val r: Reader) {
            private val buf = CharArray(64 * 1024); private var pos = 0; private var len = 0
            fun next(): Int { if (pos == len) { len = r.read(buf, 0, buf.size); pos = 0; if (len <= 0) { len = 0; return -1 } }; return buf[pos++].code }
            fun skipWs(): Int { var c: Int; do { c = next() } while (c != -1 && Character.isWhitespace(c)); return c }
        }
    }
}
