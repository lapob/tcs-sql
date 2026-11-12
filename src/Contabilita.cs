using Microsoft.Data.Sqlite;
using System.Globalization;
using System.Text;
using System.Text.Json;

namespace TopCash.Licenze;

// ───────────────────────── accounting (read from MastroSQL, Mastro stays the accounting system of record)
/// Sales invoice or credit note. Kind: "Fattura" or "Nota di credito" (credit notes have a negative effect on totals).
public sealed record Invoice(string Id,string Number,string Date,string ClientCode,string ClientName,string Kind,decimal Taxable,decimal Vat,decimal Total,string Payment,string Source="mastro");
/// Instalment due (Mastro "mov_sca"). Party: "C" customer receivable, "F" supplier payable. Paid = amount already settled.
public sealed record Due(string Id,string Party,string PartyCode,string PartyName,string DocNumber,string DocDate,string DueDate,decimal Amount,decimal Paid,string Payment,string Source="mastro");
/// Ledger balance of a customer / supplier (sum of debit and credit entries in Mastro "mov_con").
public sealed record Balance(string Party,string Code,decimal Debit,decimal Credit);
/// Sales of one article in one month (Mastro invoice lines "rig_fat", credit notes excluded). Revenue = quantity x net price;
/// Cost is known only where Mastro has the purchase cost on the line (CostedRevenue is the revenue of those lines, so the
/// margin is computed on comparable amounts). Below = what was lost selling under cost. Family groups the products
/// (registers, DGFE, PCs...) from the code prefix of the article or, failing that, from its description.
public sealed record SaleRow(string Month,string Code,string Name,string Family,decimal Qty,decimal Revenue,decimal CostedRevenue,decimal Cost,decimal Below,int Docs);
/// One month of the general ledger (Mastro "mov_con", year-end closing entries excluded). Kind "C" cost and "R" revenue by
/// counter-account; "F" purchase invoices by supplier and counter-account. Group is the parent account ("Acquisti per la vendita").
public sealed record LedgerRow(string Month,string Kind,string Account,string Label,string Group,string Party,decimal Amount,int Docs);
/// What the office did about a receivable (kept by the app, survives every Mastro import).
public sealed record DueNote(string Id,string Status,string Note,string Date,string By);

public sealed partial class Store
{
 public static readonly string[] DueStatuses=["","Sollecitato","Promesso","Incassato","Contestato"];
 void InitContabilita(SqliteConnection c)
 {
  using var q=c.CreateCommand();q.CommandText="""
   CREATE TABLE IF NOT EXISTS invoices(id TEXT PRIMARY KEY,client TEXT NOT NULL,date TEXT NOT NULL,data TEXT NOT NULL);
   CREATE INDEX IF NOT EXISTS invoices_client ON invoices(client);
   CREATE TABLE IF NOT EXISTS dues(id TEXT PRIMARY KEY,party TEXT NOT NULL,code TEXT NOT NULL,due TEXT NOT NULL,data TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS balances(party TEXT NOT NULL,code TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(party,code));
   CREATE TABLE IF NOT EXISTS due_notes(id TEXT PRIMARY KEY,data TEXT NOT NULL);
   """;q.ExecuteNonQuery();
 }
 /// Most recent first; the snapshot is capped so that phones stay fast (older invoices remain in the database).
 public List<Invoice> Invoices(int max=25000)=>ReadAll<Invoice>($"SELECT data FROM invoices ORDER BY date DESC,id DESC LIMIT {max}");
 /// Open instalments plus those settled in the last 2 years.
 public List<Due> Dues()=>ReadAll<Due>("SELECT data FROM dues").Where(d=>d.Paid<d.Amount||string.CompareOrdinal(d.DueDate,DateTime.Today.AddYears(-2).ToString("yyyy-MM-dd"))>=0).ToList();
 public List<Balance> Balances()=>ReadAll<Balance>("SELECT data FROM balances");
 public Dictionary<string,DueNote> DueNotes()=>ReadAll<DueNote>("SELECT data FROM due_notes").ToDictionary(n=>n.Id);

 public DueNote SaveDueNote(string id,string status,string note,string who)
 {
  id=(id??"").Trim();status=(status??"").Trim();note=(note??"").Trim();
  if(id.Length is 0 or >80)throw new InvalidDataException("Scadenza non indicata.");
  if(!DueStatuses.Contains(status))throw new InvalidDataException("Stato non valido.");
  if(note.Length>2000)throw new InvalidDataException("Nota troppo lunga (max 2000 caratteri).");
  using var c=Open();
  using(var ex=c.CreateCommand()){ex.CommandText="SELECT EXISTS(SELECT 1 FROM dues WHERE id=$i)";ex.Parameters.AddWithValue("$i",id);if(Convert.ToInt64(ex.ExecuteScalar())==0)throw new InvalidDataException("Scadenza non trovata.");}
  var n=new DueNote(id,status,note,DateTimeOffset.Now.ToString("O"),who);
  using var q=c.CreateCommand();
  if(status.Length==0&&note.Length==0){q.CommandText="DELETE FROM due_notes WHERE id=$i";q.Parameters.AddWithValue("$i",id);}
  else{q.CommandText="INSERT INTO due_notes VALUES($i,$d) ON CONFLICT(id) DO UPDATE SET data=excluded.data";q.Parameters.AddWithValue("$i",id);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(n));}
  q.ExecuteNonQuery();
  Log("edit",$"{who}: scadenza {id} → {(status.Length>0?status:"nota")}.");
  return n;
 }

 /// Replaces the accounting mirror inside the Mastro import transaction. Null lists = table not found in Mastro: existing data kept.
 int[] ImportMastroAccounting(SqliteConnection c,SqliteTransaction tx,List<Invoice>? invoices,List<Due>? dues,List<Balance>? balances)
 {
  void Exec(string sql){using var q=c.CreateCommand();q.Transaction=tx;q.CommandText=sql;q.ExecuteNonQuery();}
  var counts=new int[3];
  if(invoices!=null)
  {
   Exec("DELETE FROM invoices");
   foreach(var i in invoices.Where(x=>x!=null&&!string.IsNullOrEmpty(x.Id)).DistinctBy(x=>x.Id))
   {
    var v=i with{Number=i.Number??"",Date=i.Date??"",ClientCode=i.ClientCode??"",ClientName=i.ClientName??"",Kind=i.Kind is "Nota di credito"?"Nota di credito":"Fattura",Payment=i.Payment??"",Source="mastro"};
    using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT INTO invoices VALUES($i,$c,$d,$j)";
    q.Parameters.AddWithValue("$i",v.Id);q.Parameters.AddWithValue("$c",v.ClientCode);q.Parameters.AddWithValue("$d",v.Date);q.Parameters.AddWithValue("$j",JsonSerializer.Serialize(v));q.ExecuteNonQuery();counts[0]++;
   }
  }
  if(dues!=null)
  {
   Exec("DELETE FROM dues");
   foreach(var d in dues.Where(x=>x!=null&&!string.IsNullOrEmpty(x.Id)).DistinctBy(x=>x.Id))
   {
    var v=d with{Party=d.Party=="F"?"F":"C",PartyCode=d.PartyCode??"",PartyName=d.PartyName??"",DocNumber=d.DocNumber??"",DocDate=d.DocDate??"",DueDate=d.DueDate??"",Payment=d.Payment??"",Paid=Math.Clamp(d.Paid,0,Math.Max(0,d.Amount)),Source="mastro"};
    using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT INTO dues VALUES($i,$p,$c,$d,$j)";
    q.Parameters.AddWithValue("$i",v.Id);q.Parameters.AddWithValue("$p",v.Party);q.Parameters.AddWithValue("$c",v.PartyCode);q.Parameters.AddWithValue("$d",v.DueDate);q.Parameters.AddWithValue("$j",JsonSerializer.Serialize(v));q.ExecuteNonQuery();counts[1]++;
   }
   // notes of instalments that no longer exist in Mastro are dropped; the others are kept
   Exec("DELETE FROM due_notes WHERE id NOT IN (SELECT id FROM dues)");
  }
  if(balances!=null)
  {
   Exec("DELETE FROM balances");
   foreach(var b in balances.Where(x=>x!=null&&!string.IsNullOrEmpty(x.Code)).DistinctBy(x=>x.Party+"|"+x.Code))
   {
    using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT INTO balances VALUES($p,$c,$j)";
    q.Parameters.AddWithValue("$p",b.Party=="F"?"F":"C");q.Parameters.AddWithValue("$c",b.Code);q.Parameters.AddWithValue("$j",JsonSerializer.Serialize(b with{Party=b.Party=="F"?"F":"C"}));q.ExecuteNonQuery();counts[2]++;
   }
  }
  return counts;
 }
}

public static class AccountingReports
{
 static string Csv(IEnumerable<IEnumerable<string>> rows)
 {
  var sb=new StringBuilder();
  foreach(var r in rows)sb.AppendLine(string.Join(';',r.Select(v=>{v??="";if(v.Length>0&&"=+-@".Contains(v[0]))v="'"+v;return v.Contains(';')||v.Contains('"')||v.Contains('\n')?"\""+v.Replace("\"","\"\"")+"\"":v;})));
  return sb.ToString();
 }
 static string M(decimal v)=>v.ToString("0.00",CultureInfo.GetCultureInfo("it-IT"));
 public static string InvoicesCsv(Store s)=>Csv(new[]{new[]{"Numero","Data","Tipo","Codice cliente","Cliente","Imponibile","IVA","Totale","Pagamento"}.AsEnumerable()}
  .Concat(s.Invoices(1_000_000).Select(i=>new[]{i.Number,i.Date,i.Kind,i.ClientCode,i.ClientName,M(i.Taxable),M(i.Vat),M(i.Total),i.Payment})));
 public static string DuesCsv(Store s)
 {
  var notes=s.DueNotes();
  return Csv(new[]{new[]{"Tipo","Codice","Nominativo","Documento","Data documento","Scadenza","Importo","Pagato","Residuo","Stato","Nota"}.AsEnumerable()}
   .Concat(s.Dues().Where(d=>d.Paid<d.Amount).OrderBy(d=>d.DueDate).Select(d=>{notes.TryGetValue(d.Id,out var n);return new[]{d.Party=="F"?"Fornitore":"Cliente",d.PartyCode,d.PartyName,d.DocNumber,d.DocDate,d.DueDate,M(d.Amount),M(d.Paid),M(d.Amount-d.Paid),n?.Status??"",n?.Note??""};})));
 }
}
