package it.topcash.studio.data

import java.time.LocalDate
import java.time.YearMonth

/** Product families in the order the screen shows them (products first, services after); same list as the web app. */
enum class Family(val label: String, val product: Boolean, val unit: String, val hue: Float) {
    Registers("Registratori di cassa", true, "venduti", 212f), Drawers("Cassetti automatici", true, "venduti", 152f), Pc("PC e palmari", true, "venduti", 262f),
    Dgfe("DGFE e chip", true, "venduti", 32f), Printers("Stampanti", true, "venduti", 292f), Scales("Bilance", true, "venduti", 12f), Rolls("Rotoli e consumabili", true, "pezzi", 190f),
    Contracts("Contratti di assistenza", false, "contratti", 232f), Rentals("Noleggi", false, "canoni", 330f), Software("Software e licenze", false, "licenze", 200f),
    Services("Servizi tecnici", false, "servizi", 24f), Other("Altro", false, "righe", 0f);
    companion object { private val by = entries.associateBy { it.label }; fun of(s: String) = by[s] ?: Other }
}

class FamTotal(val f: Family) { var qty = 0.0; var rev = 0.0; var cRev = 0.0; var cost = 0.0; var below = 0.0
    val margin get() = if (cRev > 0) (cRev - cost) / cRev * 100 else null }
class ProdTotal(val key: String, val code: String, var name: String, val f: Family) { var qty = 0.0; var rev = 0.0; var cRev = 0.0; var cost = 0.0; var below = 0.0; var last = ""
    val search get() = norm("$code $name ${f.label}") }
class BuyTotal(val key: String, val label: String, val sub: String) { var v = 0.0; var n = 0; val parts = HashMap<String, Double>() }
class MonthTotal(val m: String) { var r = 0.0; var c = 0.0 }

/** Sales, purchases, margin and losses of a range of months ("yyyy-MM", inclusive). */
class SalesTotals(state: ArchiveState, acc: AccountingView, from: String, to: String) {
    val fam = LinkedHashMap<Family, FamTotal>(); val prod = HashMap<String, ProdTotal>()
    var rev = 0.0; var cRev = 0.0; var cost = 0.0; var below = 0.0
    var r = 0.0; var c = 0.0; var f = 0.0
    val byAcc = HashMap<String, BuyTotal>(); val bySup = HashMap<String, BuyTotal>(); val months = HashMap<String, MonthTotal>(); val lossAcc = LinkedHashMap<String, Double>()
    var creditNotes = 0.0; var creditCount = 0
    val has = state.sales.isNotEmpty() || state.ledger.isNotEmpty()
    init {
        fun inR(m: String) = m >= from && m <= to
        for (x in state.sales) {
            if (!inR(x.month) || x.family == "Note di credito") continue
            val fm = Family.of(x.family)
            rev += x.revenue; cRev += x.costedRevenue; cost += x.cost; below += x.below
            fam.getOrPut(fm) { FamTotal(fm) }.apply { qty += x.qty; rev += x.revenue; cRev += x.costedRevenue; cost += x.cost; below += x.below }
            val k = x.code.ifBlank { x.name }
            prod.getOrPut(k) { ProdTotal(k, x.code, x.name, fm) }.apply { qty += x.qty; rev += x.revenue; cRev += x.costedRevenue; cost += x.cost; below += x.below
                if (x.month > last) { last = x.month; if (x.name.isNotBlank()) name = x.name } }
        }
        val lossRe = Regex("perdit|omagg|demoliz|insolut", RegexOption.IGNORE_CASE)
        for (l in state.ledger) {
            if (!inR(l.month)) continue
            val mt = months.getOrPut(l.month) { MonthTotal(l.month) }
            when (l.kind) {
                "R" -> { r += l.amount; mt.r += l.amount }
                "C" -> { c += l.amount; mt.c += l.amount; if (lossRe.containsMatchIn(l.label)) lossAcc[l.label] = (lossAcc[l.label] ?: 0.0) + l.amount }
                "F" -> { f += l.amount
                    byAcc.getOrPut(l.account) { BuyTotal(l.account, l.label, l.group) }.apply { v += l.amount; n += l.docs }
                    bySup.getOrPut(l.party) { BuyTotal(l.party, l.party, "") }.apply { v += l.amount; n += l.docs; parts[l.label] = (parts[l.label] ?: 0.0) + l.amount } }
            }
        }
        for (i in acc.invoices) { val d = i.date ?: continue; if (i.sign > 0) continue
            val k = "%04d-%02d".format(d.year, d.monthValue); if (inR(k)) { creditNotes += kotlin.math.abs(i.net); creditCount++ } }
    }
    val margin get() = cRev - cost
    val result get() = r - c
    val losses get() = below + creditNotes + lossAcc.values.sum()
}

/** The period chosen on the screen and the one it is compared with. A month in progress would be compared with a whole
 *  month of the year before: comparisons stop at the last closed month (from the 25th the current month counts). */
class SalesPeriod(val key: String) {
    val from: String; val to: String; val cmpFrom: String; val cmpTo: String; val label: String; val cmpLabel: String
    init {
        val now = LocalDate.now(); val cur = YearMonth.from(now).let { if (now.dayOfMonth >= 25 || now.monthValue == 1) it else it.minusMonths(1) }
        fun s(y: YearMonth) = "%04d-%02d".format(y.year, y.monthValue)
        fun name(y: YearMonth) = Dates.month(y.atDay(1)).lowercase()
        when {
            key == "12 mesi" -> { val a = cur.minusMonths(11); from = s(a); to = s(cur); cmpFrom = s(a.minusYears(1)); cmpTo = s(cur.minusYears(1))
                label = "${name(a)} ${a.year} – ${name(cur)} ${cur.year}"; cmpLabel = "i 12 mesi prima" }
            key.length == 4 && key.all(Char::isDigit) -> { val y = key.toInt(); from = "$y-01"; to = "$y-12"; cmpFrom = "${y - 1}-01"; cmpTo = "${y - 1}-12"; label = key; cmpLabel = "${y - 1}" }
            else -> { from = "${cur.year}-01"; to = s(cur); cmpFrom = "${cur.year - 1}-01"; cmpTo = s(cur.minusYears(1))
                label = if (cur.monthValue == 1) "${name(cur)} ${cur.year}" else "gennaio–${name(cur)} ${cur.year}"; cmpLabel = "lo stesso periodo del ${cur.year - 1}" }
        }
    }
    val months: List<String> get() { val out = ArrayList<String>(); var y = YearMonth.parse(from); val end = YearMonth.parse(to)
        while (!y.isAfter(end) && out.size < 24) { out += "%04d-%02d".format(y.year, y.monthValue); y = y.plusMonths(1) }; return out }
}

class SalesView(state: ArchiveState, acc: AccountingView, val period: SalesPeriod) {
    val cur = SalesTotals(state, acc, period.from, period.to)
    val prev = SalesTotals(state, acc, period.cmpFrom, period.cmpTo)
    val periods: List<String> = listOf("Quest'anno", "12 mesi") + (state.sales.map { it.month.take(4) } + state.ledger.map { it.month.take(4) })
        .toSet().filter { it < LocalDate.now().year.toString() }.sortedDescending().take(4)
    companion object {
        fun pct(a: Double, b: Double): Double? = if (b > 0.5) (a - b) / b * 100 else null
    }
}
