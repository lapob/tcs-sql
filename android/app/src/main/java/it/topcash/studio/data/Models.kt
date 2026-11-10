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
)
@Serializable data class Machine(
    val id: String = "", val clientCode: String = "", val model: String = "", val type: String = "", val serial: String = "",
    val fiscalSerial: String = "", val logo: String = "", val installed: String = "", val firstInstall: String = "", val verification: String = "",
    val active: Boolean = true, val subscribed: Boolean = false, val contractStart: String = "", val contractEnd: String = "",
    val amount: String = "", val branch: String = "", val notes: String = "",
)
@Serializable data class Intervention(
    val id: String = "", val clientCode: String = "", val clientName: String = "", val date: String = "", val machine: String = "",
    val title: String = "", val work: String = "", val status: String = "Aperto", val technician: String = "", val priority: String = "Normale",
    val total: String = "", val source: String = "app", val created: String = "", val createdBy: String = "", val updated: String = "", val updatedBy: String = "",
    val parts: List<PartUse> = emptyList(), val hours: String = "", val signer: String = "", val signedAt: String = "", val reportNo: String = "",
    val sentTo: String = "", val sentAt: String = "",
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
@Serializable data class Balance(val party: String = "C", val code: String = "", val debit: Double = 0.0, val credit: Double = 0.0)
@Serializable data class DueNote(val id: String = "", val status: String = "", val note: String = "", val date: String = "", val by: String = "")
@Serializable data class DueNoteRequest(val id: String, val status: String, val note: String)
@Serializable data class MovementsRequest(val movements: List<Movement>)
@Serializable data class ClientMeta(val favorite: Boolean = false, val nextContact: String = "", val followupState: String = "")
@Serializable data class SyncEvent(val time: String = "", val kind: String = "", val message: String = "")
@Serializable data class ArchiveState(
    val records: List<LicenseRecord> = emptyList(), val clients: List<RegistryClient> = emptyList(), val orders: List<Order> = emptyList(),
    val mastroClients: List<MastroClient> = emptyList(), val machines: List<Machine> = emptyList(), val interventions: List<Intervention> = emptyList(),
    val events: List<SyncEvent> = emptyList(), val notes: Map<String, String> = emptyMap(), val metadata: Map<String, ClientMeta> = emptyMap(),
    val warehouses: List<Warehouse> = emptyList(), val articles: List<Article> = emptyList(), val stockLevels: List<StockLevel> = emptyList(),
    val stock: List<StockRow> = emptyList(), val movements: List<Movement> = emptyList(),
    val invoices: List<Invoice> = emptyList(), val dues: List<Due> = emptyList(), val balances: List<Balance> = emptyList(), val dueNotes: Map<String, DueNote> = emptyMap(),
    val features: Features = Features(),
    val dtr: List<DtrRegister> = emptyList(), val dtrStatus: DtrStatus = DtrStatus(),
    val ital: List<ItalDevice> = emptyList(), val italOrders: List<ItalOrder> = emptyList(), val italStatus: DtrStatus = DtrStatus(),
    val clientEdits: Map<String, ClientEdit> = emptyMap(), val licenseEdits: Map<String, LicenseEdit> = emptyMap(), val licenseCleanup: LicenseCleanup = LicenseCleanup(),
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
@Serializable data class Me(val username: String = "", val name: String = "", val role: String = "", val roleLabel: String = "")
@Serializable data class Session(val setupNeeded: Boolean = false, val user: Me? = null, val version: Long = 0)
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
    val updatedAt: String = "", val updatedBy: String = "")
@Serializable data class LicenseEdit(val expiry: String = "", val note: String = "", val hidden: Boolean = false, val updatedAt: String = "", val updatedBy: String = "")
@Serializable data class LicenseCleanup(val red: Int = 0, val old: Int = 0, val hidden: Int = 0)
@Serializable data class ClientSaveRequest(val code: String, val edit: ClientEdit)
@Serializable data class ClientDeleteRequest(val code: String, val deleted: Boolean)
@Serializable data class LicenseSaveRequest(val id: String, val edit: LicenseEdit)
@Serializable data class SavedCode(val code: String = "")
@Serializable data class UserRow(val username: String = "", val name: String = "", val role: String = "", val roleLabel: String = "", val disabled: Boolean = false,
    val created: String = "", val lastLogin: String = "")
@Serializable data class UsersResponse(val users: List<UserRow> = emptyList())
@Serializable data class UserCreate(val username: String, val name: String, val role: String, val password: String)
@Serializable data class UserPatch(val role: String? = null, val disabled: Boolean? = null, val name: String? = null, val password: String? = null)
@Serializable data class PasswordChange(val current: String, val password: String)
