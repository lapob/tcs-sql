package it.topcash.studio.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The saved archive is split and put back together without ever holding it whole: it must match the server's answer. */
class SectionCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun roundTrip(text: String): Pair<JsonObject, Boolean> {
        val dir = tmp.newFolder(); val cache = SectionCache(File(dir, "archive"))
        val answer = File(dir, "answer.json").apply { writeText(text) }
        val staging = File(dir, "new"); val partial = cache.split(answer, staging); cache.commit(staging, partial)
        // reassemble exactly as decode() does, but as a JSON tree to compare
        val parts = File(dir, "archive").listFiles().orEmpty().sortedBy { it.name }
        val joined = parts.joinToString(",", "{", "}") { "\"" + it.name.removeSuffix(".json") + "\":" + it.readText() }
        return (json.parseToJsonElement(joined) as JsonObject) to partial
    }

    @Test fun tricky_values_survive() {
        val text = """ { "clients" : [ {"name":"A \"quoted\" , {name} [x]","n":1}, {"u":"\\","e":"\u00e8 ✓ 😀"} ] ,
            "version":42,"flag":true , "none":null,"neg":-1.5e3, "empty":{} ,"arr":[] ,"nested":{"a":{"b":[1,{"c":"}"}]}},
            "hashes":{"clients":"AB12"} } """
        val (o, partial) = roundTrip(text)
        assertFalse(partial)
        assertEquals(json.parseToJsonElement(text) as JsonObject, o)
    }

    @Test fun partial_answer_updates_only_its_sections() {
        val dir = tmp.newFolder(); val cache = SectionCache(File(dir, "archive"))
        fun apply(t: String) { val a = File(dir, "a.json").apply { writeText(t) }; val st = File(dir, "st"); cache.commit(st, cache.split(a, st)) }
        apply("""{"clients":[{"n":"old"}],"events":[],"hashes":{"clients":"1","events":"2"},"version":1}""")
        assertTrue(cache.complete)
        apply("""{"partial":true,"clients":[{"n":"new"}],"hashes":{"clients":"9","events":"2"},"version":2}""")
        assertEquals("clients=9,events=2", cache.hashes())
        assertFalse(File(dir, "archive/partial.json").exists())
        assertEquals("[]", File(dir, "archive/events.json").readText())
        assertEquals("""[{"n":"new"}]""", File(dir, "archive/clients.json").readText())
        assertEquals(0, cache.decode().events.size)
    }

    @Test fun large_archive_is_split_and_decoded() {
        val big = buildJsonObject {
            put("interventions", buildJsonArray {
                repeat(40_000) { i -> add(buildJsonObject { put("id", "mi$i"); put("client", "${i % 4000}"); put("title", "Intervento \"$i\" {x} [y], ok"); put("work", "Lavoro ".repeat(60)); put("status", if (i % 7 == 0) "Aperto" else "Chiuso") }) }
            })
            put("hashes", buildJsonObject { put("interventions", "FF") }); put("version", 7)
        }.toString()
        val dir = tmp.newFolder(); val cache = SectionCache(File(dir, "archive"))
        val answer = File(dir, "answer.json").apply { writeText(big) }
        val t0 = System.nanoTime(); val st = File(dir, "st"); cache.commit(st, cache.split(answer, st)); val t1 = System.nanoTime()
        val state = cache.decode(); val t2 = System.nanoTime()
        println("archivio ${answer.length() / 1_000_000} MB: divisione ${(t1 - t0) / 1_000_000} ms, lettura ${(t2 - t1) / 1_000_000} ms")
        assertEquals(40_000, state.interventions.size)
        assertEquals("mi39999", state.interventions.last().id)
        assertEquals("Intervento \"5\" {x} [y], ok", state.interventions[5].title)
    }
}
