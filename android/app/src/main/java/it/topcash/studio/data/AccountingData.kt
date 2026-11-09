package it.topcash.studio.data

import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/** Invoice / credit note linked to the app's client (same rules as the web app). */
class UInvoice(val i: Invoice, val date: LocalDate?, val client: UClient?) {
    val sign = if (i.kind == "Nota di credito") -1 else 1
    val net = sign * kotlin.math.abs(i.taxable)
    val gross = sign * kotlin.math.abs(i.total)
    val name = client?.name ?: i.clientName.ifBlank { i.clientCode }
    val search = norm(listOf(i.number, i.clientName, client?.name, i.clientCode, i.payment).joinToString(" "))
}

/** Instalment (receivable "C" or payable "F") with what the office noted about it. */
class UDue(val d: Due, val due: LocalDate?, val docDate: LocalDate?, val client: UClient?, val note: DueNote?) {
    val left = maxOf(0.0, d.amount - d.paid)
    val open = left > 0.005 && note?.status != "Incassato"
    val days = Dates.daysFromToday(due)
    val late = open && days != null && days < 0
    val name = client?.name ?: d.partyName.ifBlank { d.partyCode }
    val search = norm(listOf(d.docNumber, d.partyName, client?.name, d.partyCode, d.payment, note?.note).joinToString(" "))
}

class ClientAccounting(val invoices: List<UInvoice>, val dues: List<UDue>, val revenueYear: Double, val open: Double, val overdue: Double, val balance: Double?)

class AccountingView(state: ArchiveState, clients: List<UClient>) {
    val invoices: List<UInvoice>
    val receivables: List<UDue>
    val payables: List<UDue>
    val months: List<Pair<LocalDate, Double>>
    val revenueYear: Double
    val revenuePrevYear: Double
    val toCollect: Double
    val overdue: List<UDue>
    val overdueSum: Double
    val toPay30: Double
    val toPay: Double
    val hasData = state.invoices.isNotEmpty() || state.dues.isNotEmpty()
    private val perClient = HashMap<String, ClientAccounting>()

    init {
        val byMastro = HashMap<String, UClient>()
        clients.forEach { c -> c.mastroCodes.forEach { byMastro.putIfAbsent(it.uppercase(), c) } }
        fun cl(code: String) = byMastro[code.uppercase()]
        invoices = state.invoices.map { UInvoice(it, Dates.parse(it.date), cl(it.clientCode)) }.sortedByDescending { it.date }
        val dues = state.dues.map { UDue(it, Dates.parse(it.dueDate), Dates.parse(it.docDate), if (it.party == "C") cl(it.partyCode) else null, state.dueNotes[it.id]) }.sortedBy { it.due }
        receivables = dues.filter { it.d.party == "C" }
        payables = dues.filter { it.d.party == "F" }
        val today = LocalDate.now()
        revenueYear = invoices.filter { it.date?.year == today.year }.sumOf { it.net }
        revenuePrevYear = invoices.filter { val d = it.date; d != null && d.year == today.year - 1 && d.dayOfYear <= today.dayOfYear }.sumOf { it.net }
        val first = today.withDayOfMonth(1).minusMonths(11)
        months = (0L..11L).map { k -> val m = first.plusMonths(k); m to invoices.filter { it.date != null && it.date.year == m.year && it.date.monthValue == m.monthValue }.sumOf { it.net } }
        val openRec = receivables.filter { it.open }
        toCollect = openRec.sumOf { it.left }
        overdue = openRec.filter { it.late }
        overdueSum = overdue.sumOf { it.left }
        val openPay = payables.filter { it.open }
        toPay = openPay.sumOf { it.left }
        toPay30 = openPay.filter { (it.days ?: 999) <= 30 }.sumOf { it.left }
        val bal = state.balances.filter { it.party == "C" }.associateBy { it.code.uppercase() }
        val invBy = invoices.filter { it.client != null }.groupBy { it.client!!.code }
        val dueBy = receivables.filter { it.client != null }.groupBy { it.client!!.code }
        clients.forEach { c ->
            val iv = invBy[c.code].orEmpty(); val du = dueBy[c.code].orEmpty()
            val b = c.mastroCodes.mapNotNull { bal[it.uppercase()] }
            if (iv.isNotEmpty() || du.isNotEmpty() || b.isNotEmpty())
                perClient[c.code] = ClientAccounting(iv, du, iv.filter { it.date?.year == today.year }.sumOf { it.net },
                    du.filter { it.open }.sumOf { it.left }, du.filter { it.late }.sumOf { it.left }, if (b.isEmpty()) null else b.sumOf { it.debit - it.credit })
        }
    }

    fun of(c: UClient): ClientAccounting? = perClient[c.code]
    fun due(id: String): UDue? = receivables.firstOrNull { it.d.id == id } ?: payables.firstOrNull { it.d.id == id }

    companion object {
        val statuses = listOf("", "Sollecitato", "Promesso", "Incassato", "Contestato")
        private val eur = NumberFormat.getCurrencyInstance(Locale.ITALY)
        private val eur0 = NumberFormat.getCurrencyInstance(Locale.ITALY).apply { maximumFractionDigits = 0 }
        fun money(v: Double): String = eur.format(v)
        fun money0(v: Double): String = eur0.format(v)
        fun whenText(d: UDue): String = when {
            !d.open -> if (d.note?.status == "Incassato") "incassata" else "pagata"
            d.days == null -> "—"
            d.days < 0 -> "scaduta da ${-d.days} gg"
            d.days == 0L -> "scade oggi"
            else -> "tra ${d.days} gg"
        }
    }
}
