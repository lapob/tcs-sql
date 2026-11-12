package it.topcash.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 2.9.0: machines to check, decisions of the office, stops of the round done, photos from the server. */
class NovitaTest {
    private val today = LocalDate.now()
    private fun machine(id: String, lastVerification: LocalDate) = Machine(id = id, clientCode = "100", model = "RCH Print F", serial = "S$id", verification = lastVerification.toString())
    private val client = MastroClient(code = "100", name = "Bar Centrale", vat = "01234567890")

    @Test fun verification_overdue_for_over_a_year_waits_for_the_office() {
        val a = Archive(ArchiveState(mastroClients = listOf(client), machines = listOf(
            machine("old", today.minusYears(4)),          // next was 2 years ago: to check
            machine("late", today.minusYears(2).minusDays(20)), // overdue 20 days: normal deadline
            machine("ok", today.minusYears(1)))))
        assertEquals(listOf("old"), a.staleMachines.map { it.m.id })
        assertFalse(a.verificationsDue.any { it.m.id == "old" })
        assertTrue(a.verificationsDue.any { it.m.id == "late" })
        assertFalse(a.deadlines(-2000, 120).any { it.key.startsWith("ver|old|") })
        assertTrue(a.deadlines(-2000, 120).any { it.key.startsWith("ver|late|") })
    }

    @Test fun office_decision_dismessa_or_attiva() {
        val st = ArchiveState(mastroClients = listOf(client), machines = listOf(machine("a", today.minusYears(4)), machine("b", today.minusYears(4)), machine("c", today.minusYears(1))),
            machineFlags = mapOf("a" to MachineFlag("dismessa", "2026-11-01T10:00:00+01:00", "Ufficio"), "b" to MachineFlag("attiva", "2026-11-01T10:00:00+01:00", "Ufficio")))
        val a = Archive(st)
        val by = a.machines.associateBy { it.m.id }
        assertFalse(by["a"]!!.m.active); assertTrue(by["a"]!!.mastroActive); assertFalse(by["a"]!!.stale)
        assertTrue(by["b"]!!.m.active); assertFalse(by["b"]!!.stale)
        assertTrue(a.verificationsDue.any { it.m.id == "b" }) // confirmed: back in the deadlines
        assertTrue(a.staleMachines.isEmpty())
        // all machines dismissed: the customer leaves the list (restorable)
        val gone = Archive(st.copy(machines = listOf(machine("a", today.minusYears(4))), machineFlags = mapOf("a" to MachineFlag("dismessa"))))
        assertTrue(gone.clients.none { it.name == "Bar Centrale" }); assertTrue(gone.deleted.any { it.name == "Bar Centrale" })
    }

    @Test fun stops_done_today_only() {
        val a = Archive(ArchiveState(routeDone = mapOf("i1" to RouteMark(today.toString(), "", "Luca"), "i2" to RouteMark(today.minusDays(1).toString(), "", "Luca"))))
        assertTrue(a.routeDone("i1")); assertFalse(a.routeDone("i2")); assertFalse(a.routeDone("i3"))
    }

    @Test fun new_sections_decode_from_the_server_json() {
        val s = json.decodeFromString<ArchiveState>("""{"machineFlags":{"m1":{"state":"dismessa","at":"x","by":"Ufficio"}},
            "routeDone":{"i9":{"day":"2026-11-12","at":"x","by":"Luca"}},"photos":{"i9":[{"id":"p1","intervention":"i9","at":"x","by":"Luca","size":1234}]}}""")
        assertEquals("dismessa", s.machineFlags["m1"]!!.state)
        assertEquals("2026-11-12", s.routeDone["i9"]!!.day)
        assertEquals(listOf("p1"), Archive(s).photos("i9").map { it.id })
        val n = json.decodeFromString<NoticesResponse>("""{"now":"2026-11-12T10:00:00+01:00","items":[{"id":"i9","kind":"urgent","title":"Cassa ferma","client":"Bar","priority":"Urgente","by":"Ufficio","at":"x"}]}""")
        assertEquals("urgent", n.items.single().kind)
    }
}
