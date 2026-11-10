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
/** A DTR register linked to the app's customer: by Mastro machine serial, then VAT number, then name ([via]). */
class URegister(val r: DtrRegister, val client: UClient?, val machine: UMachine?, val via: String) {
    val d get() = r.detail
    val geDue = Dates.parse(r.geDue); val geDays = Dates.daysFromToday(geDue)
    val seen = Dates.parse(r.lastSync); val seenDays = Dates.daysFromToday(seen)
    val certDue = Dates.parse(r.detail?.certDue); val certDays = Dates.daysFromToday(certDue)
    val licence = r.detail?.licenses.orEmpty().filter { it.name.contains("RT-Utility", true) }.maxByOrNull { it.to }
    val licDays = Dates.daysFromToday(Dates.parse(licence?.to))
    /** Daily closings still available in the electronic journal (from the newest "CHIUSURE RIMANENTI:n" message). */
    val remaining: Int? = r.detail?.errors.orEmpty().firstNotNullOfOrNull { remainingRe.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
    val recentErrors = r.detail?.errors.orEmpty().filter { e -> !remainingRe.containsMatchIn(e.text) && (Dates.daysFromToday(Dates.parse(e.at)) ?: -99) >= -7 }
    val silent = seenDays != null && seenDays < -7
    val warn = !r.detail?.warning.isNullOrBlank() || recentErrors.isNotEmpty()
    val lowZ = remaining != null && remaining <= 60
    val geSoon = (geDays != null && geDays <= 60) || lowZ
    val certSoon = certDays != null && certDays <= 90
    val name get() = client?.name ?: r.detail?.holder?.takeIf { it.isNotBlank() } ?: r.client
    val search = norm(listOf(r.serial, r.client, r.alias, r.tags, r.detail?.holder, r.detail?.vat, r.detail?.address, client?.name, machine?.m?.model).joinToString(" "))
    companion object { private val remainingRe = Regex("CHIUSURE RIMANENTI\\s*:?\\s*(\\d+)", RegexOption.IGNORE_CASE) }
}
/** An Ital Retail device linked to the app's customer: by Mastro machine serial, else by the label written on the portal. */
class UItal(val r: ItalDevice, val client: UClient?, val machine: UMachine?, val via: String, val orders: List<ItalOrder>) {
    val d get() = r.detail
    val subTo = Dates.parse(r.detail?.subTo); val subDays = Dates.daysFromToday(subTo)
    val certDue = Dates.parse(r.detail?.certDue); val certDays = Dates.daysFromToday(certDue)
    val seen = Dates.parse(r.detail?.dataAt); val seenDays = Dates.daysFromToday(seen)
    val noSub = !r.subscribed
    val subSoon = !noSub && subDays != null && subDays <= 45 && r.detail?.autoRenew != true
    val lowDgfe = (r.detail?.dgfeFree ?: -1) in 0..15
    val certSoon = certDays != null && certDays <= 90
    val label get() = r.client.ifBlank { r.note }
    val name get() = client?.name ?: label.ifBlank { r.serial }
    val search = norm(listOf(r.serial, r.type, r.client, r.note, r.detail?.firmware, client?.name, machine?.m?.model).joinToString(" "))
}
data class UIntervention(val i: Intervention, var client: UClient?, val clientName: String, val date: LocalDate?)
data class UOrder(val o: Order, var client: UClient?, val date: LocalDate?)

class UClient(
    val code: String, var name: String, var vat: String, var taxCode: String, var city: String, var address: String,
    val phones: MutableList<String>, val emails: MutableList<String>, val families: MutableSet<String>,
    licenses: List<ULicense>, val meta: ClientMeta, val note: String, val fromRegistry: Boolean,
) {
    /** Mutable: a duplicate merged by the administrator brings its licences here. */
    val licenses: MutableList<ULicense> = licenses.toMutableList()
    val assets = mutableListOf<UAsset>()
    val mergedFrom = mutableListOf<UClient>()
    var mastro: MastroClient? = null
    var mastroOnly = false
    val mastroCodes = mutableListOf<String>()
    val machines = mutableListOf<UMachine>()
    val registers = mutableListOf<URegister>()
    val italDevices = mutableListOf<UItal>()
    var pec = ""; var contact = ""; var category = ""
    var edit: ClientEdit? = null
    val interventions = mutableListOf<UIntervention>()
    val orders = mutableListOf<UOrder>()
    val active get() = licenses.filter { it.current }
    /** Technician: the one on most active machines, else the customer's referent in Mastro, else the latest intervention. */
    val tech: String by lazy {
        machines.filter { it.m.active && it.m.technician.isNotBlank() }.groupingBy { it.m.technician }.eachCount().maxByOrNull { it.value }?.key
            ?: mastro?.agent?.takeIf { it.isNotBlank() }
            ?: interventions.firstOrNull { it.i.technician.isNotBlank() && !it.i.technician.all(Char::isDigit) }?.i?.technician ?: ""
    }
    /** Service conditions of the active machines: "Noleggio", "Abbonamento", "Garanzia", "A chiamata"… with how many. */
    val services: List<Pair<String, Int>> by lazy {
        machines.filter { it.m.active }.mapNotNull { serviceKind(it.m) }.groupingBy { it }.eachCount().toList().sortedBy { serviceOrder.indexOf(it.first) }
    }
    /** Nothing to work on (no active device, licence, technician, recent activity…): kept out of the list, found by search. */
    val dormant: Boolean by lazy {
        val since = LocalDate.now().minusYears(3)
        machines.none { it.m.active } && active.isEmpty() && tech.isBlank() && registers.isEmpty() && italDevices.isEmpty() && assets.isEmpty() &&
            interventions.none { (it.i.source == "app" && it.i.status != "Chiuso") || it.date?.isAfter(since) == true } &&
            orders.none { it.date?.isAfter(LocalDate.now().minusYears(2)) == true } && edit?.created != true && !meta.favorite && note.isBlank()
    }
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

data class Deadline(val kind: String, val date: LocalDate, val days: Long, val client: UClient?, val clientName: String, val detail: String, val key: String = "")
/** An item of the IT inventory with its warranty and expiry. */
class UAsset(val a: Asset, val client: UClient?) {
    val warranty = Dates.parse(a.warrantyTo); val warDays = Dates.daysFromToday(warranty)
    val expiry = Dates.parse(a.expiry); val expDays = Dates.daysFromToday(expiry)
    val due get() = a.active && ((warDays != null && warDays <= 60) || (expDays != null && expDays <= 60))
    val clientName get() = client?.name ?: "Cliente ${a.clientCode}"
    val search = norm(listOf(a.name, a.brand, a.kind, a.serial, a.ip, a.os, a.location, a.notes, client?.name).joinToString(" "))
}

class Archive(val state: ArchiveState) {
    val clients: List<UClient>
    val byCode: Map<String, UClient>
    val licenses: List<ULicense>
    val machines: List<UMachine>
    val registers: List<URegister>
    val ital: List<UItal>
    val assets: List<UAsset>
    /** Duplicates merged into another customer by the administrator. */
    val merged: List<UClient>
    /** Customers deleted by an administrator (hidden everywhere, restorable). */
    val deleted: List<UClient>
    val interventions: List<UIntervention>
    val orders: List<UOrder>

    init {
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
        // DTR-Service registers → Mastro machine by serial (a missing manufacturer prefix is tolerated when unambiguous), else VAT, else name
        fun serKey(s: String?) = (s ?: "").uppercase().replace(Regex("[^A-Z0-9]"), "")
        val bySerial = HashMap<String, UMachine>(); val byTail = HashMap<String, MutableList<Pair<String, UMachine>>>()
        machines.sortedByDescending { it.m.active }.forEach { m ->
            setOf(serKey(m.m.fiscalSerial), serKey(m.m.serial)).filter { it.length >= 8 }.forEach { k -> bySerial.putIfAbsent(k, m); byTail.getOrPut(k.takeLast(8)) { mutableListOf() }.add(k to m) }
        }
        registers = state.dtr.map { r ->
            val k = serKey(r.serial)
            val m = bySerial[k] ?: byTail[k.takeLast(8)].orEmpty().filter { (mk, _) -> mk.endsWith(k) || k.endsWith(mk) }.singleOrNull()?.second
            val cVat = r.detail?.vat?.let(::idKey)?.takeIf { it.length >= 11 }?.let { byVat[it] }
            val cName = nameTo[nameKey(r.client)] ?: r.detail?.holder?.let { nameTo[nameKey(it)] } ?: r.alias.takeIf { it.isNotBlank() }?.let { nameTo[nameKey(it)] }
            val cLink = state.deviceLinks[r.serial.uppercase()]?.let { map[it] }
            val c = cLink ?: m?.client ?: cVat ?: cName
            URegister(r, c, m, when { cLink != null -> "abbinata a mano"; m?.client != null -> "matricola"; cVat != null -> "P.IVA"; cName != null -> "nome"; else -> "" }).also { c?.registers?.add(it) }
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
        // Ital Retail devices → Mastro machine by serial, else the label written on the portal
        val italOrders = state.italOrders.groupBy { it.serial }
        ital = state.ital.map { r ->
            val m = bySerial[serKey(r.serial)]; val cName = nameTo[nameKey(r.client)] ?: r.note.takeIf { it.isNotBlank() }?.let { nameTo[nameKey(it)] }
            val cLink = state.deviceLinks[r.serial.uppercase()]?.let { map[it] }
            val c = cLink ?: m?.client ?: cName
            UItal(r, c, m, when { cLink != null -> "abbinata a mano"; m?.client != null -> "matricola"; cName != null -> "nome"; else -> "" }, italOrders[r.serial].orEmpty().sortedByDescending { it.date }).also { c?.italDevices?.add(it) }
        }
        // administrator's changes: they win over the synchronised data; a customer created by hand exists only here
        val gone = mutableListOf<UClient>()
        for ((code, e) in state.clientEdits) {
            var c = map[code]
            if (c == null) {
                if (!e.created) continue
                c = UClient(code, e.name.ifBlank { code }, "", "", "", "", mutableListOf(), mutableListOf(), mutableSetOf(), emptyList(), state.metadata[code] ?: ClientMeta(), state.notes[code].orEmpty(), false)
                map[code] = c
            }
            c.edit = e
            if (e.name.isNotBlank()) c.name = e.name; if (e.vat.isNotBlank()) c.vat = e.vat; if (e.taxCode.isNotBlank()) c.taxCode = e.taxCode
            if (e.city.isNotBlank()) c.city = e.city; if (e.address.isNotBlank()) c.address = e.address
            if (e.phone.isNotBlank()) { c.phones.remove(e.phone); c.phones.add(0, e.phone) }
            if (e.email.isNotBlank()) { c.emails.remove(e.email); c.emails.add(0, e.email) }
            c.pec = e.pec.ifBlank { c.mastro?.pec.orEmpty() }; c.contact = e.contact; c.category = e.category
            if (e.deleted) gone += c
        }
        map.values.forEach { c -> if (c.pec.isBlank()) c.pec = c.mastro?.pec.orEmpty() }
        deleted = gone.sortedBy { it.name }
        gone.forEach { map.remove(it.code) }
        // merged duplicates: their licences, machines, devices and history show under the main customer
        val aliases = HashMap<String, UClient>(); val mergedList = mutableListOf<UClient>()
        for ((code, e) in state.clientEdits) {
            if (e.mergedInto.isBlank() || e.deleted) continue
            val c = map[code] ?: continue; val t = map[e.mergedInto] ?: continue
            if (c === t) continue
            t.licenses += c.licenses; c.machines.forEach { it.client = t }; t.machines += c.machines; t.registers += c.registers; t.italDevices += c.italDevices
            t.interventions += c.interventions; t.orders += c.orders
            c.phones.filter { it !in t.phones }.forEach(t.phones::add); c.emails.filter { it !in t.emails }.forEach(t.emails::add)
            if (t.vat.isBlank()) t.vat = c.vat; if (t.taxCode.isBlank()) t.taxCode = c.taxCode; if (t.mastro == null) t.mastro = c.mastro; t.mastroCodes += c.mastroCodes
            t.mergedFrom += c; aliases[code] = t; mergedList += c; map.remove(code)
        }
        merged = mergedList
        assets = state.assets.map { a -> val c = map[a.clientCode] ?: aliases[a.clientCode]; UAsset(a, c).also { c?.assets?.add(it) } }
        map.values.forEach { c ->
            c.search = norm(c.contact + " " + c.category + " " + listOf(c.name, c.code, c.vat, c.taxCode, c.city, c.address, c.mastro?.pec, c.mastro?.code).joinToString(" ") + " " +
                c.phones.joinToString(" ") + " " + c.emails.joinToString(" ") + " " + c.licenses.joinToString(" ") { it.r.serial } + " " + c.machines.joinToString(" ") { it.m.serial + " " + it.m.fiscalSerial } +
                " " + c.assets.joinToString(" ") { it.a.name + " " + it.a.serial + " " + it.a.ip })
        }
        clients = map.values.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.ITALIAN)) { it.name })
        byCode = HashMap(map).apply { putAll(aliases) }
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
    val dtrErrors by lazy { registers.filter { it.warn } }
    val dtrSilent by lazy { registers.filter { it.silent } }
    val dtrGeSoon by lazy { registers.filter { it.geSoon } }
    val dtrCertSoon by lazy { registers.filter { it.certSoon } }
    val italUpdate by lazy { ital.filter { it.r.update.isNotBlank() } }
    val italNoSub by lazy { ital.filter { it.noSub } }
    val italLow by lazy { ital.filter { it.lowDgfe } }
    fun licenseEdit(id: String) = state.licenseEdits[id]
    val assetsDue by lazy { assets.filter { it.due } }
    val technicians by lazy { interventions.map { it.i.technician }.filter { it.isNotBlank() }.distinct().sorted() }

    /** Licences, periodic verifications and contracts in one chronological list. */
    fun deadlines(fromDays: Long = -30, toDays: Long = 120): List<Deadline> = buildList {
        currentLicenses.forEach { l -> if (l.expiry != null && l.days != null && l.days in fromDays..toDays) add(Deadline("Licenza", l.expiry, l.days, byCode[l.r.clientCode], l.r.client, "${Catalog.family(l.family)} · ${l.r.serial}", "lic|${l.r.id}|${l.expiry}")) }
        machines.forEach { m ->
            if (m.m.active && m.nextVerification != null && m.verDays != null && m.verDays in fromDays..toDays) add(Deadline("Verificazione", m.nextVerification, m.verDays, m.client, m.clientName, "${m.m.model} · ${m.m.serial}", "ver|${m.m.id}|${m.nextVerification}"))
            if (m.m.subscribed && m.contractEnd != null && m.endDays != null && m.endDays in fromDays..toDays) add(Deadline("Abbonamento", m.contractEnd, m.endDays, m.client, m.clientName, "${m.m.model} · ${m.m.serial}", "con|${m.m.id}|${m.contractEnd}"))
        }
        registers.forEach { r ->
            if (r.geDue != null && r.geDays != null && r.geDays in fromDays..toDays) add(Deadline("Giornale elettronico", r.geDue, r.geDays, r.client, r.name, "GE ${r.r.geNumber} · ${r.r.serial}"))
            if (r.certDue != null && r.certDays != null && r.certDays in fromDays..toDays) add(Deadline("Certificato RT", r.certDue, r.certDays, r.client, r.name, r.r.serial, "cert|${r.r.serial}|${r.certDue}"))
        }
        ital.forEach { r ->
            if (r.subTo != null && r.subDays != null && r.d?.autoRenew != true && r.subDays in fromDays..toDays) add(Deadline("Abbonamento Ital Retail", r.subTo, r.subDays, r.client, r.name, "${r.r.type.ifBlank { "Cassa" }} · ${r.r.serial}", "isub|${r.r.type.ifBlank { "Cassa" }} · ${r.r.serial}|${r.subTo}"))
            if (r.certDue != null && r.certDays != null && r.certDays in fromDays..toDays) add(Deadline("Certificato RT", r.certDue, r.certDays, r.client, r.name, "Ital Retail · ${r.r.serial}", "icert|Ital Retail · ${r.r.serial}|${r.certDue}"))
        }
        assets.forEach { x ->
            if (!x.a.active) return@forEach
            if (x.warranty != null && x.warDays != null && x.warDays in fromDays..toDays) add(Deadline("Garanzia", x.warranty, x.warDays, x.client, x.clientName, "${x.a.kind} ${x.a.name}", "war|${x.a.id}|${x.warranty}"))
            if (x.expiry != null && x.expDays != null && x.expDays in fromDays..toDays) add(Deadline("Scadenza software", x.expiry, x.expDays, x.client, x.clientName, "${x.a.kind} ${x.a.name}", "aexp|${x.a.id}|${x.expiry}"))
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

val serviceOrder = listOf("Noleggio", "Abbonamento", "Garanzia", "Ore prepagate", "Installazione", "A chiamata")
/** Mastro service of a machine (lis_macc.SERVIZIO): an open warranty wins, "Pagamento" means paid per call. */
fun serviceKind(m: Machine): String? {
    val w = runCatching { LocalDate.parse(m.warrantyEnd.take(10)) }.getOrNull()
    if (w != null && !w.isBefore(LocalDate.now())) return "Garanzia"
    val s = m.service.uppercase()
    return when {
        "NOLEGG" in s -> "Noleggio"; "ABBON" in s || "CANONE" in s -> "Abbonamento"; "PREPAGAT" in s -> "Ore prepagate"
        "INSTALLAZ" in s -> "Installazione"; "PAGAMENTO" in s || "CHIAMATA" in s || "GARANZ" in s -> "A chiamata"
        m.subscribed -> "Abbonamento"; s.isNotBlank() -> "A chiamata"; else -> null
    }
}
