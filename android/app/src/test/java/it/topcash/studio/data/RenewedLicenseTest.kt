package it.topcash.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** "Già rinnovata": a licence marked for its current expiry leaves the "in scadenza" lists, and only for that expiry. */
class RenewedLicenseTest {
    private val soon = LocalDate.now().plusDays(10)
    private val late = LocalDate.now().minusDays(5)
    private fun rec(id: String, exp: LocalDate) = LicenseRecord(id = id, clientCode = "C1", client = "Cliente", serial = "S$id", expiry = exp.toString(), scope = "pos-correnti")

    @Test fun renewed_licence_leaves_the_due_lists() {
        val a = Archive(ArchiveState(
            records = listOf(rec("a", soon), rec("b", soon), rec("c", late)), clients = listOf(RegistryClient(code = "C1", name = "Cliente")),
            renewals = mapOf("lic|a|$soon" to RenewalMark(status = "Rinnovato"), "lic|c|$late" to RenewalMark(status = "Rinnovato"))))
        val byId = a.licenses.associateBy { it.r.id }
        assertEquals("Rinnovata", byId["a"]!!.status)
        assertEquals("In scadenza", byId["b"]!!.status)
        assertEquals("Rinnovata", byId["c"]!!.status)
        assertEquals(listOf("b"), a.due30.map { it.r.id })
        assertTrue(a.overdue.isEmpty())
        assertEquals("In scadenza", (a.clients + a.deleted).single { it.code == "C1" }.status)   // b still needs a call
    }

    @Test fun mark_for_an_old_expiry_does_not_apply_to_the_new_one() {
        val a = Archive(ArchiveState(records = listOf(rec("a", soon)), renewals = mapOf("lic|a|${soon.minusYears(1)}" to RenewalMark(status = "Rinnovato"))))
        assertEquals("In scadenza", a.licenses.single().status)
        assertFalse(a.due30.isEmpty())
    }

    @Test fun other_outcomes_do_not_count_as_renewed() {
        val a = Archive(ArchiveState(records = listOf(rec("a", soon)), renewals = mapOf("lic|a|$soon" to RenewalMark(status = "Contattato"))))
        assertEquals("In scadenza", a.licenses.single().status)
    }
}
