using Microsoft.Data.Sqlite;
using System.Globalization;
using System.Text.Json;

namespace TopCash.Licenze;

// ── Warehouse management ──────────────────────────────────────────────────────────────────────────────
// Ledger model: every stock change is a Movement with a positive quantity going FROM a location and/or TO a location.
// Stock(article, location) = Σ qty IN − Σ qty OUT. Locations = the main warehouse plus one per van / technician.

/// The stock location. There is one, the office warehouse "sede" (older archives may still list others).
public sealed record Warehouse(string Id, string Name, string Kind, string Technician, bool Active, string Notes);
/// Catalogue item. Serialized items (e.g. cash registers) are tracked by serial number in every movement.
public sealed record Article(string Code, string Name, string Barcode, string Category, string Unit, decimal Cost, decimal Price, decimal Vat,
 string Supplier, string SupplierCode, bool Serialized, bool Active, string Notes, string Source, string Updated);
/// Min / max for an article in a location: below min → reorder proposal up to max.
public sealed record StockLevel(string Article, string Warehouse, decimal Min, decimal Max);
public sealed record Movement(string Id, string Date, string Type, string Article, decimal Qty, string From, string To, decimal UnitCost,
 string Serial, string ClientCode, string InterventionId, string Notes, string User, string Created);
/// Spare part used in an intervention: unloaded automatically from the given location (usually the technician's van).
public sealed record PartUse(string Article, decimal Qty, string Warehouse, string Serial);
public sealed record StockRow(string Article, string Warehouse, decimal Qty);

/// CSV reports (Italian Excel: ';' separator, UTF-8 BOM added by the caller).
public static class WarehouseReports
{
 static string Q(string s) { if (s.Length > 0 && "=+-@\t\r".Contains(s[0])) s = "'" + s; return "\"" + s.Replace("\"", "\"\"") + "\""; }
 static string N(decimal d) => d.ToString("0.###", CultureInfo.GetCultureInfo("it-IT"));
 /// Stock per article and location, valued at weighted average cost of loads (falls back to the catalogue cost).
 public static string StockCsv(Store s)
 {
  var arts = s.Articles().ToDictionary(a => a.Code, StringComparer.OrdinalIgnoreCase); var whs = s.Warehouses().ToDictionary(w => w.Id);
  var cost = AverageCosts(s);
  var rows = new List<string> { "Codice;Descrizione;Categoria;Magazzino;Quantità;U.M.;Costo medio;Valore" };
  foreach (var r in s.Stock().OrderBy(r => r.Article).ThenBy(r => r.Warehouse))
  {
   arts.TryGetValue(r.Article, out var a); var c = cost.GetValueOrDefault(r.Article, a?.Cost ?? 0);
   rows.Add(string.Join(';', new[] { r.Article, a?.Name ?? "", a?.Category ?? "", whs.GetValueOrDefault(r.Warehouse)?.Name ?? r.Warehouse, N(r.Qty), a?.Unit ?? "", N(c), N(Math.Round(c * r.Qty, 2)) }.Select(Q)));
  }
  return string.Join(Environment.NewLine, rows);
 }
 /// Reorder proposal: every location at or below its minimum, quantity to bring it back to the maximum, grouped by supplier.
 public static string ReorderCsv(Store s)
 {
  var arts = s.Articles().ToDictionary(a => a.Code, StringComparer.OrdinalIgnoreCase); var whs = s.Warehouses().ToDictionary(w => w.Id);
  var stock = s.Stock().ToDictionary(r => (r.Article.ToUpperInvariant(), r.Warehouse), r => r.Qty);
  var rows = new List<string> { "Fornitore;Codice fornitore;Codice;Descrizione;Magazzino;Giacenza;Minimo;Massimo;Da ordinare;U.M." };
  foreach (var l in s.StockLevels().Where(l => l.Min > 0).OrderBy(l => arts.GetValueOrDefault(l.Article)?.Supplier).ThenBy(l => l.Article))
  {
   var have = stock.GetValueOrDefault((l.Article.ToUpperInvariant(), l.Warehouse)); if (have > l.Min) continue;
   arts.TryGetValue(l.Article, out var a); var order = Math.Max(l.Max > 0 ? l.Max : l.Min * 2, l.Min) - have;
   rows.Add(string.Join(';', new[] { a?.Supplier ?? "", a?.SupplierCode ?? "", l.Article, a?.Name ?? "", whs.GetValueOrDefault(l.Warehouse)?.Name ?? l.Warehouse, N(have), N(l.Min), N(l.Max), N(order), a?.Unit ?? "" }.Select(Q)));
  }
  return string.Join(Environment.NewLine, rows);
 }
 public static Dictionary<string, decimal> AverageCosts(Store s)
 {
  var res = new Dictionary<string, decimal>(StringComparer.OrdinalIgnoreCase);
  foreach (var g in s.Movements(int.MaxValue).Where(m => m.Type == "carico" && m.UnitCost > 0).GroupBy(m => m.Article, StringComparer.OrdinalIgnoreCase))
  { var q = g.Sum(m => m.Qty); if (q > 0) res[g.Key] = Math.Round(g.Sum(m => m.Qty * m.UnitCost) / q, 4); }
  return res;
 }
}

public sealed partial class Store
{
 public static readonly string[] MovementTypes = ["carico", "scarico", "rettifica", "inventario", "intervento"];

 void InitMagazzino(SqliteConnection c)
 {
  using var q = c.CreateCommand(); q.CommandText = """
   CREATE TABLE IF NOT EXISTS warehouses(id TEXT PRIMARY KEY, data TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS articles(code TEXT PRIMARY KEY COLLATE NOCASE, barcode TEXT NOT NULL DEFAULT '', data TEXT NOT NULL);
   CREATE INDEX IF NOT EXISTS articles_barcode ON articles(barcode);
   CREATE TABLE IF NOT EXISTS stock_levels(article TEXT NOT NULL, warehouse TEXT NOT NULL, min REAL NOT NULL, max REAL NOT NULL, PRIMARY KEY(article, warehouse));
   CREATE TABLE IF NOT EXISTS movements(id TEXT PRIMARY KEY, date TEXT NOT NULL, type TEXT NOT NULL, article TEXT NOT NULL, qty REAL NOT NULL,
     from_wh TEXT NOT NULL DEFAULT '', to_wh TEXT NOT NULL DEFAULT '', intervention TEXT NOT NULL DEFAULT '', data TEXT NOT NULL);
   CREATE INDEX IF NOT EXISTS movements_article ON movements(article);
   CREATE INDEX IF NOT EXISTS movements_intervention ON movements(intervention);
   CREATE INDEX IF NOT EXISTS movements_date ON movements(date);
   """; q.ExecuteNonQuery();
  // a fresh archive always has the office warehouse
  using var w = c.CreateCommand(); w.CommandText = "INSERT OR IGNORE INTO warehouses VALUES('sede',$d)";
  w.Parameters.AddWithValue("$d", JsonSerializer.Serialize(new Warehouse("sede", "Magazzino sede", "sede", "", true, ""))); w.ExecuteNonQuery();
  // 2.9.8: one warehouse, products entered by hand only. What older versions imported from Mastro goes away
  // (catalogue, stock alignments, the extra Mastro warehouses); everything created in the app stays.
  using var x = c.CreateCommand(); x.CommandText = """
   DELETE FROM articles WHERE json_extract(data,'$.Source')='mastro';
   DELETE FROM movements WHERE type='rettifica' AND json_extract(data,'$.Notes')='Allineamento giacenze MastroSQL';
   DELETE FROM warehouses WHERE id LIKE 'mastro-%';
   DELETE FROM stock_levels WHERE article COLLATE NOCASE NOT IN (SELECT code FROM articles) OR warehouse NOT IN (SELECT id FROM warehouses);
   """; x.ExecuteNonQuery();
 }

 public List<Warehouse> Warehouses() => ReadAll<Warehouse>("SELECT data FROM warehouses");
 public List<Article> Articles() => ReadAll<Article>("SELECT data FROM articles");
 public List<StockLevel> StockLevels()
 {
  using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "SELECT article,warehouse,min,max FROM stock_levels"; using var r = q.ExecuteReader();
  var l = new List<StockLevel>(); while (r.Read()) l.Add(new(r.GetString(0), r.GetString(1), (decimal)r.GetDouble(2), (decimal)r.GetDouble(3))); return l;
 }
 /// Movements, most recent first (the full ledger is small: one row per item moved).
 public List<Movement> Movements(int max = 20000) => ReadAll<Movement>($"SELECT data FROM movements ORDER BY date DESC, id DESC LIMIT {max}");
 public List<StockRow> Stock()
 {
  using var c = Open(); return StockOf(c, null);
 }
 static List<StockRow> StockOf(SqliteConnection c, SqliteTransaction? tx, string? article = null)
 {
  using var q = c.CreateCommand(); q.Transaction = tx;
  q.CommandText = """
   SELECT article, wh, SUM(qty) FROM (
     SELECT article, to_wh AS wh, qty FROM movements WHERE to_wh <> '' AND ($a IS NULL OR article = $a)
     UNION ALL SELECT article, from_wh AS wh, -qty FROM movements WHERE from_wh <> '' AND ($a IS NULL OR article = $a))
   GROUP BY article, wh HAVING ABS(SUM(qty)) > 0.0000001
   """;
  q.Parameters.AddWithValue("$a", (object?)article ?? DBNull.Value);
  using var r = q.ExecuteReader(); var l = new List<StockRow>();
  while (r.Read()) l.Add(new(r.GetString(0), r.GetString(1), Math.Round((decimal)r.GetDouble(2), 3))); return l;
 }

 public Article SaveArticle(Article a, string who)
 {
  if (a == null || string.IsNullOrWhiteSpace(a.Code) || a.Code.Length > 40) throw new InvalidDataException("Il codice articolo è obbligatorio (max 40 caratteri).");
  if (string.IsNullOrWhiteSpace(a.Name) || a.Name.Length > 200) throw new InvalidDataException("Scrivi la descrizione dell'articolo.");
  if (a.Cost < 0 || a.Price < 0 || a.Vat < 0 || a.Vat > 100) throw new InvalidDataException("Prezzi o IVA non validi.");
  var rec = a with { Code = a.Code.Trim(), Name = a.Name.Trim(), Barcode = (a.Barcode ?? "").Trim(), Category = (a.Category ?? "").Trim(), Unit = string.IsNullOrWhiteSpace(a.Unit) ? "PZ" : a.Unit.Trim().ToUpperInvariant(),
   Supplier = a.Supplier ?? "", SupplierCode = a.SupplierCode ?? "", Notes = a.Notes ?? "", Source = string.IsNullOrEmpty(a.Source) ? "app" : a.Source, Updated = DateTimeOffset.Now.ToString("O") };
  using var c = Open();
  if (rec.Barcode.Length > 0)
  {
   using var b = c.CreateCommand(); b.CommandText = "SELECT code FROM articles WHERE barcode=$b AND code<>$c COLLATE NOCASE LIMIT 1"; b.Parameters.AddWithValue("$b", rec.Barcode); b.Parameters.AddWithValue("$c", rec.Code);
   if (b.ExecuteScalar() is string other) throw new InvalidDataException($"Codice a barre già usato dall'articolo {other}.");
  }
  using var q = c.CreateCommand(); q.CommandText = "INSERT INTO articles VALUES($c,$b,$d) ON CONFLICT(code) DO UPDATE SET barcode=$b, data=$d";
  q.Parameters.AddWithValue("$c", rec.Code); q.Parameters.AddWithValue("$b", rec.Barcode); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(rec)); q.ExecuteNonQuery();
  return rec;
 }

 public void SaveStockLevel(StockLevel l)
 {
  if (l == null || l.Min < 0 || l.Max < 0 || (l.Max > 0 && l.Max < l.Min)) throw new InvalidDataException("Scorta minima e massima non valide (la massima deve essere ≥ minima).");
  using var c = Open(); using var q = c.CreateCommand();
  if (l.Min == 0 && l.Max == 0) { q.CommandText = "DELETE FROM stock_levels WHERE article=$a AND warehouse=$w"; }
  else q.CommandText = "INSERT INTO stock_levels VALUES($a,$w,$n,$x) ON CONFLICT(article,warehouse) DO UPDATE SET min=$n, max=$x";
  q.Parameters.AddWithValue("$a", l.Article); q.Parameters.AddWithValue("$w", l.Warehouse); q.Parameters.AddWithValue("$n", (double)l.Min); q.Parameters.AddWithValue("$x", (double)l.Max); q.ExecuteNonQuery();
 }

 /// Validates and records movements atomically. Unless allowNegative, a location can never go below zero.
 public List<Movement> AddMovements(IEnumerable<Movement> input, string who, bool allowNegative = false)
 {
  using var c = Open(); using var tx = c.BeginTransaction();
  var done = AddMovementsTx(c, tx, input.ToList(), who, allowNegative, null);
  tx.Commit(); return done;
 }

 List<Movement> AddMovementsTx(SqliteConnection c, SqliteTransaction tx, List<Movement> input, string who, bool allowNegative, string? intervention)
 {
  if (input.Count == 0) return [];
  if (input.Count > 500) throw new InvalidDataException("Troppi movimenti in una sola operazione.");
  var articles = new Dictionary<string, Article>(StringComparer.OrdinalIgnoreCase);
  using (var q = c.CreateCommand()) { q.Transaction = tx; q.CommandText = "SELECT data FROM articles"; using var r = q.ExecuteReader(); while (r.Read()) { var a = JsonSerializer.Deserialize<Article>(r.GetString(0))!; articles[a.Code] = a; } }
  var warehouses = new HashSet<string>();
  using (var q = c.CreateCommand()) { q.Transaction = tx; q.CommandText = "SELECT id FROM warehouses"; using var r = q.ExecuteReader(); while (r.Read()) warehouses.Add(r.GetString(0)); }
  var now = DateTimeOffset.Now; var saved = new List<Movement>();
  // running balance per (article, location) so several lines of the same batch are checked together
  var balance = new Dictionary<(string, string), decimal>();
  decimal Bal(string a, string w)
  {
   if (!balance.TryGetValue((a, w), out var v)) v = balance[(a, w)] = StockOf(c, tx, a).Where(s => s.Warehouse == w).Sum(s => s.Qty);
   return v;
  }
  int n = 0;
  foreach (var m0 in input)
  {
   n++;
   var m = m0 with { Article = (m0.Article ?? "").Trim(), From = m0.From ?? "", To = m0.To ?? "", Serial = (m0.Serial ?? "").Trim(), ClientCode = m0.ClientCode ?? "", Notes = (m0.Notes ?? "").Trim(), Type = m0.Type ?? "" };
   if (!MovementTypes.Contains(m.Type)) throw new InvalidDataException($"Riga {n}: tipo di movimento non valido.");
   if (!articles.TryGetValue(m.Article, out var art)) throw new InvalidDataException($"Riga {n}: articolo «{m.Article}» non trovato.");
   if (m.Qty <= 0 || m.Qty > 1_000_000) throw new InvalidDataException($"Riga {n}: quantità non valida.");
   if (m.Qty != Math.Round(m.Qty, 3)) throw new InvalidDataException($"Riga {n}: massimo 3 decimali.");
   if (art.Serialized && m.Qty != Math.Floor(m.Qty)) throw new InvalidDataException($"Riga {n}: {art.Name} si gestisce a pezzi interi (matricola).");
   switch (m.Type)
   {
    case "carico": if (m.To.Length == 0 || m.From.Length > 0) throw new InvalidDataException($"Riga {n}: il carico va in un magazzino di destinazione."); break;
    case "scarico": case "intervento": if (m.From.Length == 0 || m.To.Length > 0) throw new InvalidDataException($"Riga {n}: lo scarico parte da un magazzino."); break;
    default: if ((m.From.Length == 0) == (m.To.Length == 0)) throw new InvalidDataException($"Riga {n}: una rettifica indica un solo magazzino."); break;
   }
   foreach (var w in new[] { m.From, m.To }) if (w.Length > 0 && !warehouses.Contains(w)) throw new InvalidDataException($"Riga {n}: magazzino non trovato.");
   if (m.From.Length > 0)
   {
    var have = Bal(m.Article, m.From);
    if (!allowNegative && have - m.Qty < 0) throw new InvalidDataException($"Riga {n}: disponibili solo {have.ToString("0.###", CultureInfo.GetCultureInfo("it-IT"))} {art.Unit} di «{art.Name}».");
    balance[(m.Article, m.From)] = have - m.Qty;
   }
   if (m.To.Length > 0) balance[(m.Article, m.To)] = Bal(m.Article, m.To) + m.Qty;
   var date = DateTimeOffset.TryParse(m.Date, CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d : now;
   if (date > now.AddDays(1)) throw new InvalidDataException($"Riga {n}: data nel futuro.");
   var rec = m with { Id = "mv-" + Guid.NewGuid().ToString("N")[..14], Date = date.ToString("O"), Article = art.Code, UnitCost = m.UnitCost > 0 ? m.UnitCost : art.Cost,
    InterventionId = intervention ?? m.InterventionId ?? "", User = who, Created = now.ToString("O") };
   using var ins = c.CreateCommand(); ins.Transaction = tx;
   ins.CommandText = "INSERT INTO movements VALUES($i,$d,$t,$a,$q,$f,$to,$iv,$data)";
   ins.Parameters.AddWithValue("$i", rec.Id); ins.Parameters.AddWithValue("$d", rec.Date); ins.Parameters.AddWithValue("$t", rec.Type); ins.Parameters.AddWithValue("$a", rec.Article);
   ins.Parameters.AddWithValue("$q", (double)rec.Qty); ins.Parameters.AddWithValue("$f", rec.From); ins.Parameters.AddWithValue("$to", rec.To); ins.Parameters.AddWithValue("$iv", rec.InterventionId);
   ins.Parameters.AddWithValue("$data", JsonSerializer.Serialize(rec)); ins.ExecuteNonQuery();
   saved.Add(rec);
  }
  return saved;
 }

 /// Physical count: records only the differences as "inventario" movements, so the ledger stays complete.
 public int RecordInventory(string warehouse, Dictionary<string, decimal> counted, string who)
 {
  using var c = Open(); using var tx = c.BeginTransaction();
  var stock = StockOf(c, tx).Where(s => s.Warehouse == warehouse).ToDictionary(s => s.Article, s => s.Qty, StringComparer.OrdinalIgnoreCase);
  var moves = new List<Movement>();
  foreach (var (article, qty) in counted)
  {
   if (qty < 0) throw new InvalidDataException($"Quantità contata negativa per {article}.");
   var diff = qty - stock.GetValueOrDefault(article);
   if (diff == 0) continue;
   moves.Add(new("", "", "inventario", article, Math.Abs(diff), diff < 0 ? warehouse : "", diff > 0 ? warehouse : "", 0, "", "", "", "Inventario fisico", who, ""));
  }
  AddMovementsTx(c, tx, moves, who, allowNegative: true, null);
  using (var q = c.CreateCommand()) { q.Transaction = tx; q.CommandText = "INSERT INTO events(time,kind,message) VALUES($t,'magazzino',$m)"; q.Parameters.AddWithValue("$t", DateTimeOffset.Now.ToString("O")); q.Parameters.AddWithValue("$m", $"{who}: inventario registrato ({moves.Count} differenze)."); q.ExecuteNonQuery(); }
  tx.Commit(); return moves.Count;
 }

 /// Replaces the parts unloaded for an intervention (edit-safe: saving again never double-unloads).
 void SyncInterventionParts(SqliteConnection c, SqliteTransaction tx, Intervention i, string who)
 {
  using (var d = c.CreateCommand()) { d.Transaction = tx; d.CommandText = "DELETE FROM movements WHERE intervention=$i AND type='intervento'"; d.Parameters.AddWithValue("$i", i.Id); d.ExecuteNonQuery(); }
  var parts = (i.Parts ?? []).Where(p => p != null && !string.IsNullOrWhiteSpace(p.Article) && p.Qty > 0).ToList();
  if (parts.Count == 0) return;
  // parts used on site may be recorded after the fact: the van may go negative and is flagged for checking instead of blocking the report
  AddMovementsTx(c, tx, parts.Select(p => new Movement("", i.Date.Length > 0 ? i.Date + "T12:00:00" : "", "intervento", p.Article, p.Qty, p.Warehouse ?? "", "", 0, p.Serial ?? "", i.ClientCode, i.Id,
   "Intervento: " + i.Title, who, "")).ToList(), who, allowNegative: true, i.Id);
 }

}
