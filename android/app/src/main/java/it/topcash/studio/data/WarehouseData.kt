package it.topcash.studio.data

import java.time.OffsetDateTime

/** Article with its stock, average cost and status (same rules as the server and the web app). One warehouse: "sede". */
class UArticle(val a: Article, val perWarehouse: Map<String, Double>, val cost: Double, val levels: Map<String, StockLevel>) {
    val total = perWarehouse.values.sum()
    val value = maxOf(0.0, total) * cost
    val below = levels.filter { (w, l) -> l.min > 0 && (perWarehouse[w] ?: 0.0) <= l.min }
    val negative = perWarehouse.values.any { it < 0 }
    val status = when { negative -> "Da verificare"; total <= 0 -> "Esaurito"; below.isNotEmpty() -> "Sotto scorta"; else -> "Disponibile" }
    val search = norm(listOf(a.code, a.name, a.barcode, a.category, a.supplier, a.supplierCode).joinToString(" "))
    /** Minimum / maximum stock set for the warehouse, if any. */
    val level get() = levels[WarehouseView.SEDE]
}

data class UMovement(val m: Movement, val article: UArticle?, val date: OffsetDateTime?)

class WarehouseView(state: ArchiveState) {
    val warehouses: List<Warehouse> = state.warehouses.sortedWith(compareBy({ if (it.kind == "sede") 0 else 1 }, { it.name }))
    val byId = warehouses.associateBy { it.id }
    val articles: List<UArticle>
    val byCode: Map<String, UArticle>
    private val byBarcode: Map<String, UArticle>
    val movements: List<UMovement>
    val categories: List<String>

    init {
        val stock = HashMap<String, HashMap<String, Double>>()
        state.stock.forEach { r -> stock.getOrPut(r.article.uppercase()) { HashMap() }[r.warehouse] = r.qty }
        val levels = HashMap<String, HashMap<String, StockLevel>>()
        state.stockLevels.forEach { l -> levels.getOrPut(l.article.uppercase()) { HashMap() }[l.warehouse] = l }
        // weighted average cost of loads; catalogue cost when nothing was loaded with a price
        val acc = HashMap<String, DoubleArray>()
        state.movements.filter { it.type == "carico" && it.unitCost > 0 }.forEach { m -> val c = acc.getOrPut(m.article.uppercase()) { DoubleArray(2) }; c[0] += m.qty; c[1] += m.qty * m.unitCost }
        articles = state.articles.map { a ->
            val k = a.code.uppercase(); val c = acc[k]
            UArticle(a, stock[k].orEmpty(), if (c != null && c[0] > 0) c[1] / c[0] else a.cost, levels[k].orEmpty())
        }.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.ITALIAN)) { it.a.name })
        byCode = articles.associateBy { it.a.code.uppercase() }
        byBarcode = articles.filter { it.a.barcode.isNotBlank() }.associateBy { it.a.barcode }
        movements = state.movements.map { UMovement(it, byCode[it.article.uppercase()], runCatching { OffsetDateTime.parse(it.date) }.getOrNull()) }
        categories = articles.map { it.a.category }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val value get() = articles.sumOf { it.value }
    val belowMin get() = articles.filter { it.below.isNotEmpty() && it.a.active }
    fun name(id: String) = byId[id]?.name ?: id.ifBlank { "—" }
    /** Exact barcode, then code, then name. */
    fun find(text: String): UArticle? { val t = text.trim(); if (t.isEmpty()) return null; return byBarcode[t] ?: byCode[t.uppercase()] ?: articles.firstOrNull { it.a.name.equals(t, true) } }

    companion object {
        const val SEDE = "sede"
        val typeLabel = mapOf("carico" to "Carico", "scarico" to "Scarico", "trasferimento" to "Trasferimento", "rettifica" to "Rettifica", "inventario" to "Inventario", "intervento" to "Intervento")
        fun qty(d: Double): String = if (d == Math.floor(d)) "%,.0f".format(java.util.Locale.ITALIAN, d) else "%,.3f".format(java.util.Locale.ITALIAN, d).trimEnd('0').trimEnd(',')
        fun eur(d: Double): String = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.ITALY).format(d)
    }
}
