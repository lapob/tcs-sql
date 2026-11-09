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
    val version: Long = 0,
)
/** Server capabilities: [mail] = signed reports can be e-mailed to the customer from the server. */
@Serializable data class Features(val mail: Boolean = false, val backupError: String = "")
@Serializable data class SendReportRequest(val to: String)
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
