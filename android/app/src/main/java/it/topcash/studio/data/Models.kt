package it.topcash.studio.data

import kotlinx.serialization.Serializable

// Wire model: mirrors the JSON of the Top Cash server (/api/state). Unknown fields are ignored, missing ones default.

@Serializable data class LicenseRecord(
    val id: String = "", val clientCode: String = "", val client: String = "", val vat: String = "", val serial: String = "",
    val installer: String = "", val product: String = "", val version: String = "", val released: String = "", val updated: String = "",
    val expiry: String = "", val blocked: String = "", val renewalRequested: String = "", val scope: String = "", val capturedAt: String = "",
)
@Serializable data class RegistryClient(
    val code: String = "", val name: String = "", val city: String = "", val address: String = "", val phone: String = "",
    val email: String = "", val taxCode: String = "", val families: List<String> = emptyList(), val capturedAt: String = "",
)
@Serializable data class Order(
    val id: String = "", val family: String = "", val date: String = "", val number: String = "", val serial: String = "",
    val client: String = "", val status: String = "",
)
@Serializable data class MastroClient(
    val code: String = "", val name: String = "", val vat: String = "", val taxCode: String = "", val address: String = "",
    val cap: String = "", val city: String = "", val prov: String = "", val zone: String = "", val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(), val pec: String = "", val notes: String = "", val extra: Map<String, String> = emptyMap(),
    val agent: String = "",
)
@Serializable data class Machine(
    val id: String = "", val clientCode: String = "", val model: String = "", val type: String = "", val serial: String = "",
    val fiscalSerial: String = "", val logo: String = "", val installed: String = "", val firstInstall: String = "", val verification: String = "",
    val active: Boolean = true, val subscribed: Boolean = false, val contractStart: String = "", val contractEnd: String = "",
    val amount: String = "", val branch: String = "", val notes: String = "",
    val service: String = "", val technician: String = "", val warrantyEnd: String = "", val months: String = "", val contractUntil: String = "",
)
@Serializable data class Intervention(
    val id: String = "", val clientCode: String = "", val clientName: String = "", val date: String = "", val machine: String = "",
    val title: String = "", val work: String = "", val status: String = "Aperto", val technician: String = "", val priority: String = "Normale",
    val total: String = "", val source: String = "app", val created: String = "", val createdBy: String = "", val updated: String = "", val updatedBy: String = "",
    val parts: List<PartUse> = emptyList(), val hours: String = "", val signer: String = "", val signedAt: String = "", val reportNo: String = "",
    val sentTo: String = "", val sentAt: String = "",
    /** "Verifica" = periodic verification (every 2 years), kept apart from the real interventions;
     *  "Incasso" / "Nota di credito" = collection or credit note (Accounting); "Intervento" = kept among the work by hand. */
    val kind: String = "",
    /** "mastro" = imported from Mastro and then edited here: the next Mastro reads never overwrite it. */
    val origin: String = "",
)
// ── warehouse
@Serializable data class PartUse(val article: String = "", val qty: Double = 1.0, val warehouse: String = "", val serial: String = "")
@Serializable data class Warehouse(val id: String = "", val name: String = "", val kind: String = "sede", val technician: String = "", val active: Boolean = true, val notes: String = "")
@Serializable data class Article(
    val code: String = "", val name: String = "", val barcode: String = "", val category: String = "", val unit: String = "PZ", val cost: Double = 0.0,
    val price: Double = 0.0, val vat: Double = 22.0, val supplier: String = "", val supplierCode: String = "", val serialized: Boolean = false,
    val active: Boolean = true, val notes: String = "", val source: String = "", val updated: String = "",
)
@Serializable data class StockLevel(val article: String = "", val warehouse: String = "", val min: Double = 0.0, val max: Double = 0.0)
@Serializable data class StockRow(val article: String = "", val warehouse: String = "", val qty: Double = 0.0)
@Serializable data class Movement(
    val id: String = "", val date: String = "", val type: String = "", val article: String = "", val qty: Double = 0.0, val from: String = "", val to: String = "",
    val unitCost: Double = 0.0, val serial: String = "", val clientCode: String = "", val interventionId: String = "", val notes: String = "", val user: String = "", val created: String = "",
)
@Serializable data class Invoice(val id: String = "", val number: String = "", val date: String = "", val clientCode: String = "", val clientName: String = "", val kind: String = "Fattura",
    val taxable: Double = 0.0, val vat: Double = 0.0, val total: Double = 0.0, val payment: String = "", val source: String = "")
@Serializable data class Due(val id: String = "", val party: String = "C", val partyCode: String = "", val partyName: String = "", val docNumber: String = "", val docDate: String = "",
    val dueDate: String = "", val amount: Double = 0.0, val paid: Double = 0.0, val payment: String = "", val source: String = "")
/** Last read of Mastro (the server does it by itself every hour). */
/** Analisi: computed by the server from Mastro at every read, the same figures the site shows. */
@Serializable data class PortalView(val id: String = "", val name: String = "", val enabled: Boolean = false, val browser: Boolean = false, val connected: Boolean = false, val running: Boolean = false,
    val status: String = "", val lastSync: String = "", val error: String = "", val url: String = "", val every: String = "",
    /** the portal asks for Cloudflare's robot check: the server cannot pass it, the office station keeps reading it */
    val blocked: Boolean = false)
/** The office PC (Windows app as sync station): when it last called the server and whether it is on now. */
@Serializable data class StationState(val lastContact: String = "", val online: Boolean = false)
@Serializable data class PortalList(val portals: List<PortalView> = emptyList(), val browser: Boolean = false, val station: StationState? = null, val asked: List<String> = emptyList(),
    val pcDaily: PcDailyRule? = null, val pcIds: List<String> = emptyList())
/** 2.10.12: every morning at [hour] the server asks the office PC to read the portals that are on it. */
@Serializable data class PcDailyRule(val enabled: Boolean = true, val hour: Int = 7, val lastDay: String = "", val lastRun: String = "", val lastResult: String = "")
@Serializable data class PcDailySave(val enabled: Boolean, val hour: Int)
/** 2.10.12: the server's own update (owner and administrators): a build that went back, or one waiting too long. */
@Serializable data class SelfUpdateState(val version: String = "", val waiting: String = "", val failed: String = "", val reason: String = "", val stuck: Boolean = false, val lastEvent: String = "")
@Serializable data class ReopenRequest(val ids: List<String>)
@Serializable data class ReopenResult(val reopened: Int = 0)
@Serializable data class PortalSwitch(val enabled: Boolean)
@Serializable data class PortalInput(val kind: String, val x: Double = 0.0, val y: Double = 0.0, val dy: Double = 0.0, val text: String? = null)
/** Income statement and balance sheet from Mastro's general ledger (computed by the server, same as the site). */
@Serializable data class BilLine(val code: String = "", val label: String = "", val amount: Double = 0.0, val prev: Double = 0.0)
@Serializable data class BilGroup(val code: String = "", val label: String = "", val section: String = "", val amount: Double = 0.0, val prev: Double = 0.0, val accounts: List<BilLine> = emptyList())
@Serializable data class BilMonth(val month: String = "", val revenue: Double = 0.0, val costs: Double = 0.0, val result: Double = 0.0, val liquidity: Double = 0.0)
@Serializable data class Bilancio(val at: String = "", val month: String = "", val prevEnd: String = "", val hasIncome: Boolean = false, val hasBalance: Boolean = false, val note: String = "",
    val revenue: Double = 0.0, val costs: Double = 0.0, val result: Double = 0.0, val revenuePrev: Double = 0.0, val costsPrev: Double = 0.0, val resultPrev: Double = 0.0, val revenueLastYear: Double = 0.0, val resultLastYear: Double = 0.0,
    val income: List<BilGroup> = emptyList(), val assets: List<BilGroup> = emptyList(), val liabilities: List<BilGroup> = emptyList(),
    val totalAssets: Double = 0.0, val totalLiabilities: Double = 0.0, val totalAssetsPrev: Double = 0.0, val totalLiabilitiesPrev: Double = 0.0,
    val liquidity: Double = 0.0, val liquidityPrev: Double = 0.0, val financialDebt: Double = 0.0, val netFinancial: Double = 0.0, val netFinancialPrev: Double = 0.0,
    val receivables: Double = 0.0, val receivablesPrev: Double = 0.0, val payables: Double = 0.0, val payablesPrev: Double = 0.0, val vat: Double = 0.0, val equity: Double = 0.0, val equityPrev: Double = 0.0,
    val dso: Int = 0, val dpo: Int = 0, val revenue12: Double = 0.0, val purchases12: Double = 0.0, val months: List<BilMonth> = emptyList(), val monthsPrev: List<BilMonth> = emptyList())
@Serializable data class Analysis(val at: String = "", val forecast: List<AnaWeek> = emptyList(), val forecastIn: Double = 0.0, val forecastOut: Double = 0.0, val overdueExpected: Double = 0.0,
    val abc: List<AnaAbc> = emptyList(), val abcClasses: List<AnaAbcClass> = emptyList(), val agents: List<AnaAgent> = emptyList(), val techs: List<AnaTech> = emptyList(),
    val machines: List<AnaMachine> = emptyList(), val models: List<AnaModel> = emptyList(), val discountClients: List<AnaDiscount> = emptyList(), val discountArticles: List<AnaDiscount> = emptyList(),
    val discountTotal: Double = 0.0, val costs: List<AnaCost> = emptyList(), val costImpact: Double = 0.0)
@Serializable data class AnaWeek(val start: String = "", val `in`: Double = 0.0, val inSure: Double = 0.0, val out: Double = 0.0, val net: Double = 0.0, val cumulative: Double = 0.0)
@Serializable data class AnaAbc(val clientCode: String = "", val name: String = "", val `class`: String = "", val revenue: Double = 0.0, val prev: Double = 0.0, val share: Double = 0.0, val rank: Int = 0)
@Serializable data class AnaAbcClass(val `class`: String = "", val clients: Int = 0, val revenue: Double = 0.0, val share: Double = 0.0)
@Serializable data class AnaAgent(val code: String = "", val name: String = "", val revenue: Double = 0.0, val prev: Double = 0.0, val margin: Double = 0.0, val marginPct: Double = 0.0, val clients: Int = 0, val topFamily: String = "", val discounts: Double = 0.0, val agent: Boolean = false)
@Serializable data class AnaTech(val name: String = "", val jobs: Int = 0, val verifications: Int = 0, val amount: Double = 0.0, val hours: Double = 0.0, val clients: Int = 0, val open: Int = 0, val agent: Boolean = false)
@Serializable data class AnaMachine(val id: String = "", val clientCode: String = "", val name: String = "", val model: String = "", val serial: String = "", val contract: Double = 0.0, val interventions: Double = 0.0, val verification: Double = 0.0, val total: Double = 0.0)
@Serializable data class AnaModel(val model: String = "", val machines: Int = 0, val total: Double = 0.0, val average: Double = 0.0)
@Serializable data class AnaDiscount(val key: String = "", val name: String = "", val sub: String = "", val given: Double = 0.0, val revenue: Double = 0.0, val lines: Int = 0)
@Serializable data class AnaCost(val code: String = "", val name: String = "", val supplier: String = "", val date: String = "", val old: Double = 0.0, val new: Double = 0.0, val pct: Double = 0.0,
    val qty12: Double = 0.0, val price: Double = 0.0, val impact: Double = 0.0, val marginBefore: Double = 0.0, val marginNow: Double = 0.0)
/** Priorità: computed by the server from Mastro, the same lists the site shows. */
@Serializable data class Insights(val at: String = "", val unbilled: List<InsUnbilled> = emptyList(), val sdi: List<InsSdi> = emptyList(), val payers: List<InsPayer> = emptyList(),
    val churn: List<InsChurn> = emptyList(), val opps: List<InsOpp> = emptyList(), val registerPrice: Double = 0.0)
@Serializable data class InsUnbilled(val id: String = "", val clientCode: String = "", val name: String = "", val date: String = "", val title: String = "", val technician: String = "", val amount: Double = 0.0, val source: String = "")
@Serializable data class InsSdi(val number: String = "", val date: String = "", val clientCode: String = "", val name: String = "", val total: Double = 0.0, val status: String = "")
@Serializable data class InsPayer(val clientCode: String = "", val name: String = "", val grade: String = "", val overdue: Double = 0.0, val rates: Int = 0, val oldestDays: Int = 0, val open: Double = 0.0, val revenue12: Double = 0.0)
@Serializable data class InsChurn(val clientCode: String = "", val name: String = "", val last12: Double = 0.0, val prev12: Double = 0.0, val lastInvoice: String = "", val lost: Double = 0.0)
@Serializable data class InsOpp(val kind: String = "", val clientCode: String = "", val name: String = "", val machineId: String = "", val what: String = "", val `when`: String = "", val value: Double = 0.0)
@Serializable data class MastroSync(val at: String = "", val ok: Boolean = true, val who: String = "")
@Serializable data class SaleRow(val month: String = "", val code: String = "", val name: String = "", val family: String = "", val qty: Double = 0.0, val revenue: Double = 0.0,
    val costedRevenue: Double = 0.0, val cost: Double = 0.0, val below: Double = 0.0, val docs: Int = 0)
@Serializable data class LedgerRow(val month: String = "", val kind: String = "", val account: String = "", val label: String = "", val group: String = "", val party: String = "",
    val amount: Double = 0.0, val docs: Int = 0)
@Serializable data class Balance(val party: String = "C", val code: String = "", val debit: Double = 0.0, val credit: Double = 0.0)
@Serializable data class DueNote(val id: String = "", val status: String = "", val note: String = "", val date: String = "", val by: String = "")
@Serializable data class DueNoteRequest(val id: String, val status: String, val note: String)
@Serializable data class MovementsRequest(val movements: List<Movement>)
@Serializable data class ClientMeta(val favorite: Boolean = false, val nextContact: String = "", val followupState: String = "")
@Serializable data class SyncEvent(val time: String = "", val kind: String = "", val message: String = "")
@Serializable data class ArchiveState(
    val records: List<LicenseRecord> = emptyList(), val clients: List<RegistryClient> = emptyList(), val orders: List<Order> = emptyList(),
    val mastroClients: List<MastroClient> = emptyList(), val machines: List<Machine> = emptyList(), val interventions: List<Intervention> = emptyList(),
    /** Collections and credit notes Mastro files as interventions (Accounting only; a technician never receives them). */
    val moneyInterventions: List<Intervention> = emptyList(), val moneyRule: MoneyRule? = null,
    val events: List<SyncEvent> = emptyList(), val notes: Map<String, String> = emptyMap(), val metadata: Map<String, ClientMeta> = emptyMap(),
    val warehouses: List<Warehouse> = emptyList(), val articles: List<Article> = emptyList(), val stockLevels: List<StockLevel> = emptyList(),
    val stock: List<StockRow> = emptyList(), val movements: List<Movement> = emptyList(),
    val invoices: List<Invoice> = emptyList(), val dues: List<Due> = emptyList(), val balances: List<Balance> = emptyList(), val dueNotes: Map<String, DueNote> = emptyMap(),
    val sales: List<SaleRow> = emptyList(), val ledger: List<LedgerRow> = emptyList(), val mastroSync: MastroSync? = null,
    val insights: Insights? = null, val analysis: Analysis? = null, val bilancio: Bilancio? = null,
    val features: Features = Features(),
    val dtr: List<DtrRegister> = emptyList(), val dtrStatus: DtrStatus = DtrStatus(),
    val ital: List<ItalDevice> = emptyList(), val italOrders: List<ItalOrder> = emptyList(), val italStatus: DtrStatus = DtrStatus(),
    val team: List<TeamMember> = emptyList(),
    val rch: List<RchDevice> = emptyList(), val rchStatus: DtrStatus = DtrStatus(),
    val clientEdits: Map<String, ClientEdit> = emptyMap(), val licenseEdits: Map<String, LicenseEdit> = emptyMap(), val licenseCleanup: LicenseCleanup = LicenseCleanup(), val licenseRule: LicenseRule? = null,
    val assets: List<Asset> = emptyList(), val renewals: Map<String, RenewalMark> = emptyMap(), val deviceLinks: Map<String, String> = emptyMap(),
    val machineFlags: Map<String, MachineFlag> = emptyMap(), val routeDone: Map<String, RouteMark> = emptyMap(), val photos: Map<String, List<PhotoInfo>> = emptyMap(),
    val version: Long = 0,
)
/** Server capabilities: [mail] = signed reports can be e-mailed to the customer from the server. */
@Serializable data class Features(val mail: Boolean = false, val backupError: String = "")
@Serializable data class SendReportRequest(val to: String)
/** DTR-Service (telematic cash registers): read-only copy kept by the sync station. Dates are ISO. */
@Serializable data class DtrRegister(val serial: String = "", val tags: String = "", val client: String = "", val alias: String = "", val firmware: String = "",
    val geNumber: Int = 0, val geDue: String = "", val lastZ: Int = 0, val lastSync: String = "", val detail: DtrDetail? = null)
@Serializable data class DtrDetail(val holder: String = "", val vat: String = "", val certDue: String = "", val lastVp: String = "", val geInit: String = "",
    val licenses: List<DtrLicense>? = null, val errors: List<DtrEvent>? = null, val works: List<DtrEvent>? = null,
    val extensions: String = "", val readAt: String = "", val warning: String = "", val address: String = "")
@Serializable data class DtrLicense(val from: String = "", val to: String = "", val status: String = "", val name: String = "", val type: String = "")
@Serializable data class DtrEvent(val at: String = "", val text: String = "")
@Serializable data class DtrStatus(val connected: Boolean = false, val lastSync: String = "", val checkedAt: String = "", val error: String = "")
/** A person of the team (name and role only): interventions are assigned to them. */
@Serializable data class TeamMember(val name: String = "", val username: String = "", val role: String = "")
@Serializable data class Me(val username: String = "", val name: String = "", val tech: String = "", val role: String = "", val roleLabel: String = "", val admin: Boolean = false, val accounting: Boolean = true, val owner: Boolean = false) {
    /** Owner and administrator can do everything; the owner also manages other administrators. */
    val isAdmin get() = admin || role == "admin" || role == "owner"
    val isOwner get() = owner || role == "owner"
    /** Technicians see everything except the accounting. */
    val seesAccounting get() = accounting && role != "tecnico"
    /** Owner, administrators and office create and edit customers and their machines; technicians only look. */
    val canEdit get() = role != "tecnico"
    /** How work is assigned to me: by surname ("Bartoli"), the same on the website and in Mastro. */
    val techName get() = tech.ifBlank { name }
}
@Serializable data class Session(val setupNeeded: Boolean = false, val user: Me? = null, val version: Long = 0, val publicUrl: String = "")
@Serializable data class ServerError(val error: String = "", val device: String = "", val code: String = "")
@Serializable data class DeviceHello(val name: String, val platform: String)
@Serializable data class DeviceStatus(val status: String = "", val code: String = "", val remote: Boolean = false, val name: String = "")
@Serializable data class LoginRequest(val username: String, val password: String, val remember: Boolean = true, val deviceName: String = "", val platform: String = "Android")
@Serializable data class SignRequest(val signer: String, val hours: String, val signature: String, val close: Boolean)
@Serializable data class NoteRequest(val code: String, val note: String)
@Serializable data class MetaRequest(val code: String, val meta: ClientMeta)
@Serializable data class StreamMessage(val type: String = "", val version: Long = 0, val running: Boolean = false, val connected: Boolean = false, val text: String = "")

object Catalog {
    val families = linkedMapOf("ILCONTO" to "Il Conto", "ZMENU" to "ZMenu", "ILCONTOAN" to "Il Conto noleggio", "POSSISTEMI" to "Pos Sistemi", "SELLBY" to "Sellby", "TILBY" to "Tilby")
    fun family(code: String) = families[code] ?: code
    val interventionStates = listOf("Aperto", "Pianificato", "In corso", "In attesa", "Chiuso")
    val priorities = listOf("Bassa", "Normale", "Alta", "Urgente")
    val followups = listOf("", "Da contattare", "In attesa", "Completato")
}

/** RCH XDealer (dealer area): read-only copy kept by the sync station, with the data the registers send. Dates are ISO. */
@Serializable data class RchDevice(val serial: String = "", val portalId: String = "", val part: String = "", val type: String = "", val state: String = "",
    val client: String = "", val vat: String = "", val city: String = "", val province: String = "", val phone: String = "", val email: String = "", val note: String = "", val labels: String = "",
    val contract: Boolean = false, val update: String = "", val firmware: String = "", val dgfeFree: Int = -1, val dgfeNumber: Int = 0,
    val lastVerification: String = "", val zDate: String = "", val zNumber: Int = 0, val certFrom: String = "", val certDue: String = "",
    val activated: String = "", val registered: String = "", val capturedAt: String = "")
/** Ital Retail OnePlus (dealer area): read-only copy kept by the sync station. Dates are ISO. */
@Serializable data class ItalDevice(val serial: String = "", val portalId: String = "", val type: String = "", val update: String = "", val subscribed: Boolean = false,
    val client: String = "", val note: String = "", val detail: ItalDetail? = null)
@Serializable data class ItalDetail(val subFrom: String = "", val subTo: String = "", val autoRenew: Boolean = false, val firmware: String = "", val closures: Int = 0,
    val dgfeNumber: Int = 0, val dgfeSize: String = "", val dgfeFree: Int = -1, val rtState: String = "", val activated: String = "", val registered: String = "",
    val certFrom: String = "", val certDue: String = "", val dataAt: String = "", val hw: String = "", val wifi: String = "", val seasonal: String = "", val clock: String = "",
    val updates: List<ItalUpdate>? = null, val dgfe: List<ItalDgfe>? = null, val readAt: String = "")
@Serializable data class ItalUpdate(val at: String = "", val from: String = "", val to: String = "")
@Serializable data class ItalDgfe(val duration: String = "", val number: String = "", val at: String = "", val status: String = "")
@Serializable data class ItalOrder(val number: String = "", val date: String = "", val serial: String = "", val client: String = "", val kind: String = "", val amount: String = "")
/** Administrator's changes, kept apart from the synchronised copies (never overwritten by a sync). */
@Serializable data class ClientEdit(val name: String = "", val vat: String = "", val taxCode: String = "", val city: String = "", val address: String = "", val phone: String = "",
    val email: String = "", val pec: String = "", val contact: String = "", val category: String = "", val created: Boolean = false, val deleted: Boolean = false,
    val updatedAt: String = "", val updatedBy: String = "", val mergedInto: String = "", val keep: Boolean = false)
@Serializable data class LicenseEdit(val expiry: String = "", val note: String = "", val hidden: Boolean = false, val updatedAt: String = "", val updatedBy: String = "")
/** PC, server, printer, network device or software licence at a customer (no passwords on purpose). */
@Serializable data class Asset(val id: String = "", val clientCode: String = "", val kind: String = "PC", val name: String = "", val brand: String = "", val serial: String = "",
    val location: String = "", val ip: String = "", val os: String = "", val purchased: String = "", val warrantyTo: String = "", val expiry: String = "", val notes: String = "",
    val active: Boolean = true, val updatedAt: String = "", val updatedBy: String = "")
/** Follow-up of a deadline: outcome, note, when to call again, with its history. */
@Serializable data class RenewalStep(val at: String = "", val by: String = "", val status: String = "", val note: String = "")
@Serializable data class RenewalMark(val status: String = "", val note: String = "", val nextContact: String = "", val at: String = "", val by: String = "", val steps: List<RenewalStep>? = null)
@Serializable data class CrashReport(val platform: String, val version: String, val text: String)
@Serializable data class RenewalRequest(val key: String, val status: String, val note: String, val nextContact: String)
@Serializable data class IdOnly(val id: String)
/** A build published on the office server by the administrator (network updates). */
@Serializable data class AppBuild(val version: String = "", val size: Long = 0, val sha256: String = "", val uploadedAt: String = "", val uploadedBy: String = "", val notes: String = "")
@Serializable data class UpdateInfo(val windows: AppBuild? = null, val android: AppBuild? = null)
@Serializable data class LicenseCleanup(val red: Int = 0, val old: Int = 0, val hidden: Int = 0, val keepMonths: Int = 1)
/** Office rule (server setting, same on every app): licences not renewed leave the list after [keepMonths];
 *  every expiry up to the month [renewedUntil] ("yyyy-MM") counts as renewed unless marked otherwise by hand. */
@Serializable data class LicenseRule(val keepMonths: Int = 1, val renewedUntil: String = "", val updatedAt: String = "", val updatedBy: String = "")
@Serializable data class LicenseRuleSave(val keepMonths: Int, val renewedUntil: String)
@Serializable data class ClientSaveRequest(val code: String, val edit: ClientEdit)
@Serializable data class ClientDeleteRequest(val code: String, val deleted: Boolean)
@Serializable data class LicenseSaveRequest(val id: String, val edit: LicenseEdit)
@Serializable data class SavedCode(val code: String = "")
@Serializable data class UserRow(val username: String = "", val name: String = "", val role: String = "", val roleLabel: String = "", val disabled: Boolean = false,
    val created: String = "", val lastLogin: String = "")
@Serializable data class UsersResponse(val users: List<UserRow> = emptyList(), val roster: RosterView? = null)
/** The team by surname: agent (also sells, stays a technician) and hidden (never shown anywhere). */
@Serializable data class RosterPerson(val name: String = "", val hidden: Boolean = false, val agent: Boolean = false, val username: String = "", val jobs: Int = 0, val group: Boolean = false)
@Serializable data class RosterView(val people: List<RosterPerson> = emptyList(), val updatedAt: String = "", val updatedBy: String = "")
/** Open interventions that close by themselves after [days] days (same setting on website, Windows and Android). */
@Serializable data class AutoCloseRule(val enabled: Boolean = true, val days: Int = 15, val updatedAt: String = "", val updatedBy: String = "", val closed: Int = 0)
@Serializable data class AutoCloseSave(val enabled: Boolean, val days: Int)
/** Server rule: collections and credit notes recorded in Mastro as interventions move to Accounting (on by default). */
@Serializable data class MoneyRule(val enabled: Boolean = true, val updatedAt: String = "", val updatedBy: String = "")
@Serializable data class MoneySave(val enabled: Boolean)
@Serializable data class RosterSave(val hidden: List<String>, val agents: List<String>)
@Serializable data class UserCreate(val username: String, val name: String, val role: String, val password: String)
@Serializable data class UserPatch(val role: String? = null, val disabled: Boolean? = null, val name: String? = null, val password: String? = null)
@Serializable data class UserRename(val username: String)
@Serializable data class PasswordChange(val current: String, val password: String)

// ── 2.9.0: machines to check, round of the day, photos, notices
/** Office decision on a machine whose verification is overdue for over a year: "dismessa" (left the customer) or "attiva" (still in use). */
@Serializable data class MachineFlag(val state: String = "", val at: String = "", val by: String = "")
/** A stop of today's round marked as done ([day] ISO), shared with the whole team. */
@Serializable data class RouteMark(val day: String = "", val at: String = "", val by: String = "")
@Serializable data class PhotoInfo(val id: String = "", val intervention: String = "", val at: String = "", val by: String = "", val size: Int = 0)
@Serializable data class RouteDoneRequest(val key: String, val done: Boolean)
@Serializable data class MachineFlagRequest(val ids: List<String>, val state: String)
@Serializable data class PhotoRequest(val data: String)
@Serializable data class Notice(val id: String = "", val kind: String = "", val title: String = "", val client: String = "", val priority: String = "", val by: String = "", val at: String = "")
@Serializable data class NoticesResponse(val now: String = "", val items: List<Notice> = emptyList())
