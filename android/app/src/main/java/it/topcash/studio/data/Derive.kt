package it.topcash.studio.data

import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Unified, ready-to-display model: Zucchetti licences + portal registry + MastroSQL master data, machines and interventions.

object Dates {
    private val dmy = Regex("""^(\d{2})[-/](\d{2})[-/](\d{4})""")
    private val ymd = Regex("""^(\d{4})-(\d{2})-(\d{2})""")
    private val cache = HashMap<String, LocalDate?>()
    fun parse(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        return synchronized(cache) { cache.getOrPut(s) {
            runCatching {
                dmy.find(s)?.let { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
                    ?: ymd.find(s)?.let { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            }.getOrNull()?.takeIf { it.year >= 1900 }
        } }
    }
    fun daysFromToday(d: LocalDate?): Long? = d?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
    fun relative(n: Long?): String = when {
        n == null -> ""
        n == 0L -> "oggi"; n == 1L -> "domani"; n == -1L -> "ieri"
        n > 0 -> "tra $n giorni"; else -> "${-n} giorni fa"
    }
    private val months = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
    private val monthsLong = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
    fun short(d: LocalDate?) = d?.let { "${it.dayOfMonth} ${months[it.monthValue - 1]} ${it.year}" } ?: "—"
    fun month(d: LocalDate) = months[d.monthValue - 1]
    fun monthYear(d: LocalDate) = "${monthsLong[d.monthValue - 1]} ${d.year}"
    fun iso(d: LocalDate) = d.toString()
}

fun norm(s: String?): String = Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
private fun nameKey(s: String?) = norm(s).replace(Regex("[^a-z0-9]"), "")
private fun idKey(s: String?) = (s ?: "").uppercase().removePrefix("IT").replace(Regex("[^0-9A-Z]"), "")

enum class Tone { Ok, Due, Bad, Info, Accent, Neutral }

data class ULicense(val r: LicenseRecord, val expiry: LocalDate?, val days: Long?, val status: String, val current: Boolean, val family: String)
data class UMachine(val m: Machine, var client: UClient?, val clientName: String, val verification: LocalDate?, val nextVerification: LocalDate?, val verDays: Long?, val contractEnd: LocalDate?, val endDays: Long?)
data class UIntervention(val i: Intervention, var client: UClient?, val clientName: String, val date: LocalDate?)
data class UOrder(val o: Order, var client: UClient?, val date: LocalDate?)

class UClient(
    val code: String, var name: String, var vat: String, var taxCode: String, var city: String, var address: String,
    val phones: MutableList<String>, val emails: MutableList<String>, val families: MutableSet<String>,
    val licenses: List<ULicense>, val meta: ClientMeta, val note: String, val fromRegistry: Boolean,
) {
    var mastro: MastroClient? = null
    var mastroOnly = false
    val mastroCodes = mutableListOf<String>()
    val machines = mutableListOf<UMachine>()
    val interventions = mutableListOf<UIntervention>()
    val orders = mutableListOf<UOrder>()
    val active get() = licenses.filter { it.current }
    var search = ""
    val status: String get() {
        val pool = active.ifEmpty { licenses }; val st = pool.map { it.status }
        return when {
            licenses.isEmpty() -> "Senza licenze"; "Bloccata" in st -> "Bloccata"; "In scadenza" in st -> "In scadenza"
            "Attiva" in st -> "Attiva"; st.all { it == "Scaduta" } -> "Scaduta"; else -> "Da verificare"
        }
    }
    val nextExpiry: LocalDate? get() = active.mapNotNull { it.expiry }.filter { !it.isBefore(LocalDate.now()) }.minOrNull()
    val phone get() = phones.firstOrNull().orEmpty()
    val email get() = emails.firstOrNull().orEmpty()
    val fullAddress get() = mastro?.let { m -> listOf(m.address, listOf(m.cap, m.city, m.prov.takeIf { it.isNotBlank() }?.let { "($it)" }).filter { !it.isNullOrBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(", ") }
        ?.takeIf { it.isNotBlank() } ?: listOf(address, city).filter { it.isNotBlank() }.joinToString(", ")
}

data class Deadline(val kind: String, val date: LocalDate, val days: Long, val client: UClient?, val clientName: String, val detail: String)

class Archive(val state: ArchiveState) {
    val clients: List<UClient>
    val byCode: Map<String, UClient>
    val licenses: List<ULicense>
    val machines: List<UMachine>
    val interventions: List<UIntervention>
    val orders: List<UOrder>

    init {
        val today = LocalDate.now()
        fun lic(r: LicenseRecord): ULicense {
            val exp = Dates.parse(r.expiry); val d = Dates.daysFromToday(exp)
            val st = when { r.blocked.equals("SI", true) -> "Bloccata"; d == null -> "Da verificare"; d < 0 -> "Scaduta"; d <= 30 -> "In scadenza"; else -> "Attiva" }
            return ULicense(r, exp, d, st, !r.scope.endsWith("-scadute"), r.scope.substringBefore("-"))
        }
        licenses = state.records.map(::lic)
        val byClient = licenses.groupBy { it.r.clientCode }
        val registry = state.clients.associateBy { it.code }
        val map = LinkedHashMap<String, UClient>()
        val serialTo = HashMap<String, UClient>(); val nameTo = HashMap<String, UClient>()
        for (code in (registry.keys + byClient.keys)) {
            val g = registry[code]; val recs = byClient[code].orEmpty().sortedBy { it.expiry ?: LocalDate.MAX }
            val c = UClient(code, g?.name?.takeIf { it.isNotBlank() } ?: recs.firstOrNull()?.r?.client ?: code, recs.firstOrNull { it.r.vat.isNotBlank() }?.r?.vat.orEmpty(),
                g?.taxCode.orEmpty(), g?.city.orEmpty(), g?.address.orEmpty(), listOfNotNull(g?.phone?.trim()?.takeIf { it.count(Char::isDigit) >= 6 }).toMutableList(),
                listOfNotNull(g?.email?.takeIf { it.contains('@') }).toMutableList(), (g?.families.orEmpty() + recs.map { it.family }).toMutableSet(),
                recs, state.metadata[code] ?: ClientMeta(), state.notes[code].orEmpty(), g != null)
            map[code] = c; recs.forEach { serialTo[it.r.serial] = c }; nameTo[nameKey(c.name)] = c
        }
        // MastroSQL: link by VAT / tax code, else by name; otherwise a customer known only to Mastro ("M" + code)
        val byVat = HashMap<String, UClient>()
        map.values.forEach { c -> listOf(c.vat, c.taxCode).map(::idKey).filter { it.length >= 11 }.forEach { byVat.putIfAbsent(it, c) } }
        val mastroTo = HashMap<String, UClient>()
        for (m in state.mastroClients) {
            var c = listOf(m.vat, m.taxCode).map(::idKey).filter { it.length >= 11 }.firstNotNullOfOrNull { byVat[it] } ?: nameTo[nameKey(m.name)]
            if (c == null) {
                val code = "M" + m.code
                c = UClient(code, m.name, m.vat, m.taxCode, "", "", mutableListOf(), mutableListOf(), mutableSetOf(), emptyList(), state.metadata[code] ?: ClientMeta(), state.notes[code].orEmpty(), false)
                c.mastroOnly = true; map[code] = c
            }
            // the same customer registered twice in Mastro stays one customer (first record is the main one)
            if (c.mastro == null) c.mastro = m; mastroTo[m.code] = c; c.mastroCodes += m.code
            listOf(m.vat, m.taxCode).map(::idKey).filter { it.length >= 11 }.forEach { byVat.putIfAbsent(it, c) }; nameTo.putIfAbsent(nameKey(m.name), c)
            if (c.vat.isBlank()) c.vat = m.vat; if (c.taxCode.isBlank()) c.taxCode = m.taxCode
            if (c.city.isBlank()) c.city = m.city; if (c.address.isBlank()) c.address = m.address
            m.phones.filter { it !in c.phones }.forEach(c.phones::add); m.emails.filter { it !in c.emails }.forEach(c.emails::add)
        }
        machines = state.machines.map { m ->
            val c = mastroTo[m.clientCode]; val ver = Dates.parse(m.verification); val next = ver?.plusYears(2); val end = Dates.parse(m.contractEnd)
            UMachine(m, c, c?.name ?: "Cliente Mastro ${m.clientCode}", ver, next, Dates.daysFromToday(next), end, Dates.daysFromToday(end)).also { c?.machines?.add(it) }
        }
        interventions = state.interventions.map { i ->
            val c = if (i.source == "mastro") mastroTo[i.clientCode] else map[i.clientCode]
            UIntervention(i, c, c?.name ?: i.clientName.ifBlank { "—" }, Dates.parse(i.date)).also { c?.interventions?.add(it) }
        }.sortedByDescending { it.date ?: LocalDate.MIN }
        map.values.forEach { it.interventions.sortByDescending { x -> x.date ?: LocalDate.MIN } }
        orders = state.orders.map { o ->
            val c = serialTo[o.serial] ?: nameTo[nameKey(o.client)]
            UOrder(o, c, Dates.parse(o.date)).also { c?.orders?.add(it) }
        }.sortedByDescending { it.date ?: LocalDate.MIN }
        map.values.forEach { c ->
            c.search = norm(listOf(c.name, c.code, c.vat, c.taxCode, c.city, c.address, c.mastro?.pec, c.mastro?.code).joinToString(" ") + " " +
                c.phones.joinToString(" ") + " " + c.emails.joinToString(" ") + " " + c.licenses.joinToString(" ") { it.r.serial } + " " + c.machines.joinToString(" ") { it.m.serial + " " + it.m.fiscalSerial })
        }
        clients = map.values.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.ITALIAN)) { it.name })
        byCode = map
        @Suppress("UNUSED_VARIABLE") val unused = today
    }

    /** Warehouse: stock per location, average costs, reorder state. */
    val wh: WarehouseView by lazy { WarehouseView(state) }
    /** Accounting mirror of MastroSQL (invoices, instalments, balances) linked to the app's clients. */
    val acc: AccountingView by lazy { AccountingView(state, clients) }
    val currentLicenses by lazy { licenses.filter { it.current } }
    val due30 by lazy { currentLicenses.filter { (it.days ?: -1) in 0..30 } }
    val overdue by lazy { currentLicenses.filter { (it.days ?: 0) < 0 } }
    val openInterventions by lazy { interventions.filter { it.i.status != "Chiuso" } }
    val verificationsDue by lazy { machines.filter { it.m.active && it.verDays != null && it.verDays <= 60 } }
    val contractsDue by lazy { machines.filter { it.m.subscribed && it.endDays != null && it.endDays <= 60 } }
    val technicians by lazy { interventions.map { it.i.technician }.filter { it.isNotBlank() }.distinct().sorted() }

    /** Licences, periodic verifications and contracts in one chronological list. */
    fun deadlines(fromDays: Long = -30, toDays: Long = 120): List<Deadline> = buildList {
        currentLicenses.forEach { l -> if (l.expiry != null && l.days != null && l.days in fromDays..toDays) add(Deadline("Licenza", l.expiry, l.days, byCode[l.r.clientCode], l.r.client, "${Catalog.family(l.family)} · ${l.r.serial}")) }
        machines.forEach { m ->
            if (m.m.active && m.nextVerification != null && m.verDays != null && m.verDays in fromDays..toDays) add(Deadline("Verificazione", m.nextVerification, m.verDays, m.client, m.clientName, "${m.m.model} · ${m.m.serial}"))
            if (m.m.subscribed && m.contractEnd != null && m.endDays != null && m.endDays in fromDays..toDays) add(Deadline("Abbonamento", m.contractEnd, m.endDays, m.client, m.clientName, "${m.m.model} · ${m.m.serial}"))
        }
    }.sortedBy { it.date }

    companion object {
        fun toneOf(status: String) = when {
            status in setOf("Attiva", "Chiuso", "Registrato") -> Tone.Ok
            status in setOf("In scadenza", "In corso", "Alta") || status.contains("allegati", true) || status.startsWith("Sospes") -> Tone.Due
            status in setOf("Scaduta", "Bloccata", "Urgente", "Annullato", "Non approvato") || status.startsWith("Disdett") -> Tone.Bad
            status in setOf("Aperto") || status.contains("approvazione") || status.contains("registrazione") || status.contains("inviare") -> Tone.Info
            status == "Pianificato" -> Tone.Accent
            else -> Tone.Neutral
        }
    }
}
