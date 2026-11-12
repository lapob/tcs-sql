using Microsoft.Data.Sqlite;
using System.Text.Json;

namespace TopCash.Licenze;

/// Customer master data imported (read-only) from MastroSQL.
public sealed record MastroClient(string Code,string Name,string Vat,string TaxCode,string Address,string Cap,string City,string Prov,string Zone,
 List<string> Phones,List<string> Emails,string Pec,string Notes,Dictionary<string,string> Extra,string Agent="");
/// A cash register / device installed at a customer (Mastro "lis_macc"), with periodic verification and service contract.
public sealed record Machine(string Id,string ClientCode,string Model,string Type,string Serial,string FiscalSerial,string Logo,string Installed,string FirstInstall,
 string Verification,bool Active,bool Subscribed,string ContractStart,string ContractEnd,string Amount,string Branch,string Notes,
 string Service="",string Technician="",string WarrantyEnd="",string Months="",string ContractUntil="");
/// Technical intervention / ticket. Source "mastro" = history imported from Mastro, "app" = created in Top Cash Studio.
public sealed record Intervention(string Id,string ClientCode,string ClientName,string Date,string Machine,string Title,string Work,string Status,
 string Technician,string Priority,string Total,string Source,string Created,string CreatedBy,string Updated,string UpdatedBy,List<PartUse>? Parts=null,
 string Hours="",string Signer="",string SignedAt="",string ReportNo="",string SentTo="",string SentAt="",string Kind="");
public sealed record MastroBatch(List<MastroClient> Clients,List<Machine> Machines,List<Intervention> Interventions,string CapturedAt,string Source,
 List<Invoice>? Invoices=null,List<Due>? Dues=null,List<Balance>? Balances=null,
 List<RtInfo>? Rt=null,List<Site>? Sites=null,List<Riba>? Riba=null,List<SaleRow>? Sales=null,List<LedgerRow>? Ledger=null,
 List<InsUnbilled>? Unbilled=null,List<InsSdi>? Sdi=null);
/// Last automatic or manual read of Mastro: when, what it brought and the fingerprint of the data (an unchanged read is not re-imported).
public sealed record MastroSync(string At,string Hash,string Message,bool Ok,string Who="");
/// Fiscal and technical details of a register from Mastro (firmware, electronic journal, RT activation, lottery, census,
/// QR census of the meter) plus its periodic verification history. Fields hold only the values that are filled in.
public sealed record RtInfo(string MachineId,string Serial,Dictionary<string,string> Fields,List<Verification> Verifications,int VerificationCount);
public sealed record Verification(string Date,string Outcome,string Type,string Technician,string Seal,string Amount,string Report,string Where,string Notes);
/// Shop / delivery address of a customer (Mastro "destina").
public sealed record Site(string ClientCode,string Name,string Address,string Cap,string City,string Prov,string Phone,string Email,string Hours,string Notes,bool Closed);
/// Bank receipt (RiBa / effetto) of a customer: open, paid or unpaid ("insoluto"). Accounting: never sent to technicians.
public sealed record Riba(string Id,string ClientCode,string Due,decimal Amount,bool Paid,bool Unpaid,string Bank,string Doc,string DocDate,string Description);

public sealed partial class Store
{
 public static readonly string[] InterventionStates=["Aperto","Pianificato","In corso","In attesa","Chiuso"];
 public static readonly string[] Priorities=["Bassa","Normale","Alta","Urgente"];
 void InitGestionale(SqliteConnection c)
 {
  using var q=c.CreateCommand();q.CommandText="""
   CREATE TABLE IF NOT EXISTS mastro_clients(code TEXT PRIMARY KEY,data TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS machines(id TEXT PRIMARY KEY,client TEXT NOT NULL,data TEXT NOT NULL);
   CREATE INDEX IF NOT EXISTS machines_client ON machines(client);
   CREATE TABLE IF NOT EXISTS interventions(id TEXT PRIMARY KEY,client TEXT NOT NULL,source TEXT NOT NULL,data TEXT NOT NULL);
   CREATE INDEX IF NOT EXISTS interventions_client ON interventions(client);
   """;q.ExecuteNonQuery();
 }
 List<T> ReadAll<T>(string sql){using var c=Open();using var q=c.CreateCommand();q.CommandText=sql;using var r=q.ExecuteReader();var list=new List<T>();while(r.Read())list.Add(JsonSerializer.Deserialize<T>(r.GetString(0))!);return list;}
 /// Optional Mastro datasets (registers detail, shops, RiBa): empty until an import from 2.7.7 on.
 List<T> MastroExtra<T>(string kind)
 {
  using var c=Open();using(var t=c.CreateCommand()){t.CommandText="CREATE TABLE IF NOT EXISTS mastro_extra(kind TEXT PRIMARY KEY,data TEXT NOT NULL)";t.ExecuteNonQuery();}
  using var q=c.CreateCommand();q.CommandText="SELECT data FROM mastro_extra WHERE kind=$k";q.Parameters.AddWithValue("$k",kind);
  return q.ExecuteScalar() is string s?JsonSerializer.Deserialize<List<T>>(s)??[]:[];
 }
 public List<RtInfo> RtInfos()=>MastroExtra<RtInfo>("rt");
 public MastroSync? LastMastroSync()=>MastroExtra<MastroSync>("auto").FirstOrDefault();
 public void SaveMastroSync(MastroSync s)
 {
  using var c=Open();using(var t=c.CreateCommand()){t.CommandText="CREATE TABLE IF NOT EXISTS mastro_extra(kind TEXT PRIMARY KEY,data TEXT NOT NULL)";t.ExecuteNonQuery();}
  using var q=c.CreateCommand();q.CommandText="INSERT INTO mastro_extra VALUES('auto',$d) ON CONFLICT(kind) DO UPDATE SET data=$d";q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(new[]{s}));q.ExecuteNonQuery();
 }
 /// Fingerprint of what a Mastro read contains (not when or by whom it was read).
 public static string MastroHash(MastroBatch b)=>Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(b with{CapturedAt="",Source=""})));
 public int MastroClientCount(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT COUNT(*) FROM mastro_clients";return Convert.ToInt32(q.ExecuteScalar());}
 public List<Site> Sites()=>MastroExtra<Site>("sites");
 public List<Riba> RibaList()=>MastroExtra<Riba>("riba");
 public List<SaleRow> Sales()=>MastroExtra<SaleRow>("sales");
 public List<LedgerRow> Ledger()=>MastroExtra<LedgerRow>("ledger");
 public List<InsUnbilled> MastroUnbilled()=>MastroExtra<InsUnbilled>("unbilled");
 public List<InsSdi> MastroSdi()=>MastroExtra<InsSdi>("sdi");
 public List<MastroClient> MastroClients()=>ReadAll<MastroClient>("SELECT data FROM mastro_clients");
 public List<Machine> Machines()=>ReadAll<Machine>("SELECT data FROM machines");
 public List<Intervention> Interventions()=>ReadAll<Intervention>("SELECT data FROM interventions");

 /// Replaces the Mastro snapshot atomically. Interventions created in the app are never touched by an import.
 public string ImportMastro(MastroBatch b,string who)
 {
  if(b?.Clients==null||b.Clients.Count==0)throw new InvalidDataException("L'importazione non contiene clienti: dati esistenti conservati.");
  if(b.Clients.Any(x=>string.IsNullOrWhiteSpace(x?.Code)||string.IsNullOrWhiteSpace(x.Name)))throw new InvalidDataException("Clienti Mastro senza codice o ragione sociale: importazione annullata.");
  b=b with{Clients=b.Clients.Select(x=>x with{Phones=x.Phones??[],Emails=x.Emails??[],Extra=x.Extra??new(),Vat=x.Vat??"",TaxCode=x.TaxCode??"",Address=x.Address??"",Cap=x.Cap??"",City=x.City??"",Prov=x.Prov??"",Zone=x.Zone??"",Pec=x.Pec??"",Notes=x.Notes??""}).ToList(),
   Machines=(b.Machines??[]).Where(m=>m!=null&&!string.IsNullOrEmpty(m.Id)&&!string.IsNullOrEmpty(m.ClientCode)).ToList(),Interventions=(b.Interventions??[]).Where(x=>x!=null&&!string.IsNullOrEmpty(x.Id)&&!string.IsNullOrEmpty(x.ClientCode)).ToList()};
  if(b.Clients.Select(x=>x.Code).Distinct().Count()!=b.Clients.Count)throw new InvalidDataException("Codici cliente Mastro duplicati: importazione annullata.");
  using var c=Open();using var tx=c.BeginTransaction();
  void Exec(string sql){using var q=c.CreateCommand();q.Transaction=tx;q.CommandText=sql;q.ExecuteNonQuery();}
  Exec("DELETE FROM mastro_clients");Exec("DELETE FROM machines");Exec("DELETE FROM interventions WHERE source='mastro'");
  foreach(var m in b.Clients){using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT INTO mastro_clients VALUES($c,$d)";q.Parameters.AddWithValue("$c",m.Code);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(m));q.ExecuteNonQuery();}
  foreach(var m in (b.Machines??[]).DistinctBy(x=>x.Id)){using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT INTO machines VALUES($i,$c,$d)";q.Parameters.AddWithValue("$i",m.Id);q.Parameters.AddWithValue("$c",m.ClientCode);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(m));q.ExecuteNonQuery();}
  foreach(var i in (b.Interventions??[]).DistinctBy(x=>x.Id)){using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT OR REPLACE INTO interventions VALUES($i,$c,'mastro',$d)";q.Parameters.AddWithValue("$i",i.Id);q.Parameters.AddWithValue("$c",i.ClientCode);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(i with{Source="mastro"}));q.ExecuteNonQuery();}
  var acc=ImportMastroAccounting(c,tx,b.Invoices,b.Dues,b.Balances);
  // optional datasets (older apps do not send them: what is already stored is kept)
  using(var q=c.CreateCommand()){q.Transaction=tx;q.CommandText="CREATE TABLE IF NOT EXISTS mastro_extra(kind TEXT PRIMARY KEY,data TEXT NOT NULL)";q.ExecuteNonQuery();}
  void Extra(string kind,object? data){if(data==null)return;using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT INTO mastro_extra VALUES($k,$d) ON CONFLICT(kind) DO UPDATE SET data=$d";q.Parameters.AddWithValue("$k",kind);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(data));q.ExecuteNonQuery();}
  Extra("rt",b.Rt);Extra("sites",b.Sites);Extra("riba",b.Riba);Extra("sales",b.Sales);Extra("ledger",b.Ledger);Extra("unbilled",b.Unbilled);Extra("sdi",b.Sdi);
  var msg=$"Importazione MastroSQL ({who}): {b.Clients.Count} clienti · {b.Machines?.Count??0} macchine · {b.Interventions?.Count??0} interventi storici"+(b.Rt!=null?$" · {b.Rt.Sum(r=>r.VerificationCount)} verifiche periodiche":"")+(b.Sites!=null?$" · {b.Sites.Count} sedi":"")+(b.Invoices!=null?$" · {acc[0]} fatture":"")+(b.Dues!=null?$" · {acc[1]} scadenze":"")+".";
  using(var q=c.CreateCommand()){q.Transaction=tx;q.CommandText="INSERT INTO events(time,kind,message) VALUES($t,'import',$m)";q.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));q.Parameters.AddWithValue("$m",msg);q.ExecuteNonQuery();}
  Extra("auto",new[]{new MastroSync(DateTimeOffset.Now.ToString("O"),MastroHash(b),msg,true,who)});
  tx.Commit();return msg;
 }
 /// Creates or updates an intervention made in the app (validated; history imported from Mastro is read-only).
 public Intervention SaveIntervention(Intervention i,string who)
 {
  if(i==null)throw new InvalidDataException("Intervento mancante.");
  // missing JSON fields arrive as null: normalise before validating
  i=i with{Id=i.Id??"",ClientCode=(i.ClientCode??"").Trim(),ClientName=i.ClientName??"",Date=(i.Date??"").Trim(),Machine=i.Machine??"",Title=(i.Title??"").Trim(),Work=i.Work??"",
   Status=i.Status??"",Technician=i.Technician??"",Priority=i.Priority??"Normale",Total=i.Total??"",Source="app",Created=i.Created??"",CreatedBy=i.CreatedBy??"",Updated=i.Updated??"",UpdatedBy=i.UpdatedBy??""};
  if(string.IsNullOrWhiteSpace(i.ClientCode)||i.ClientCode.Length>60)throw new InvalidDataException("Seleziona il cliente.");
  if(string.IsNullOrWhiteSpace(i.Title)||i.Title.Length>200)throw new InvalidDataException("Scrivi un titolo (max 200 caratteri).");
  if((i.Work??"").Length>20000)throw new InvalidDataException("Descrizione troppo lunga.");
  if(!InterventionStates.Contains(i.Status))throw new InvalidDataException("Stato non valido.");
  if(!Priorities.Contains(i.Priority))throw new InvalidDataException("Priorità non valida.");
  if(i.Date.Length>0&&!DateTime.TryParseExact(i.Date,"yyyy-MM-dd",System.Globalization.CultureInfo.InvariantCulture,System.Globalization.DateTimeStyles.None,out _))throw new InvalidDataException("Data non valida.");
  var now=DateTimeOffset.Now.ToString("O");
  using var c=Open();
  using(var ex=c.CreateCommand())
  {
   // the customer must exist: Zucchetti code, registry code, or "M"+Mastro code for customers known only to Mastro
   ex.CommandText="SELECT EXISTS(SELECT 1 FROM licenses WHERE client_code=$c) OR EXISTS(SELECT 1 FROM clients WHERE code=$c) OR EXISTS(SELECT 1 FROM mastro_clients WHERE 'M'||code=$c)";
   ex.Parameters.AddWithValue("$c",i.ClientCode);if(Convert.ToInt64(ex.ExecuteScalar())==0)throw new InvalidDataException("Cliente non trovato nell'archivio.");
  }
  Intervention? old=null;
  if(!string.IsNullOrEmpty(i.Id)){using var g=c.CreateCommand();g.CommandText="SELECT data FROM interventions WHERE id=$i";g.Parameters.AddWithValue("$i",i.Id);if(g.ExecuteScalar() is string s)old=JsonSerializer.Deserialize<Intervention>(s);}
  if(old?.Source=="mastro")throw new InvalidDataException("Gli interventi storici importati da Mastro non si modificano qui.");
  if(!string.IsNullOrEmpty(old?.SignedAt))throw new InvalidDataException("Rapportino già firmato dal cliente: per modificarlo l'ufficio deve prima annullare la firma.");
  if(i.Hours is {Length:>0} h&&(!decimal.TryParse(h.Replace(',','.'),System.Globalization.NumberStyles.Number,System.Globalization.CultureInfo.InvariantCulture,out var hv)||hv<0||hv>200))throw new InvalidDataException("Ore lavorate non valide.");
  var rec=i with{Id=old?.Id??("app-"+Guid.NewGuid().ToString("N")[..12]),Source="app",Created=old?.Created??now,CreatedBy=old?.CreatedBy??who,Updated=now,UpdatedBy=who,
   Work=i.Work??"",Technician=(i.Technician??"").Trim(),Machine=i.Machine??"",Total=i.Total??"",ClientName=i.ClientName??"",
   Hours=(i.Hours??"").Replace(',','.'),Signer=old?.Signer??"",SignedAt=old?.SignedAt??"",ReportNo=old?.ReportNo??"",SentTo=old?.SentTo??"",SentAt=old?.SentAt??""}; // signature fields only change via SignIntervention
  rec=rec with{Parts=(i.Parts??[]).Where(p=>p!=null&&!string.IsNullOrWhiteSpace(p.Article)&&p.Qty>0).Select(p=>p with{Article=p.Article.Trim(),Warehouse=p.Warehouse??"",Serial=p.Serial??""}).ToList()};
  if(rec.Parts!.Count>100)throw new InvalidDataException("Troppi ricambi in un intervento.");
  rec=rec with{Parts=rec.Parts.Select(p=>p.Warehouse.Length==0?p with{Warehouse="sede"}:p).ToList()};   // one warehouse
  // intervention + its spare-part unloads in one transaction
  using var tx=c.BeginTransaction();
  using(var q=c.CreateCommand()){q.Transaction=tx;q.CommandText="INSERT INTO interventions VALUES($i,$c,'app',$d) ON CONFLICT(id) DO UPDATE SET client=$c,data=$d";
   q.Parameters.AddWithValue("$i",rec.Id);q.Parameters.AddWithValue("$c",rec.ClientCode);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(rec));q.ExecuteNonQuery();}
  SyncInterventionParts(c,tx,rec,who);
  tx.Commit();
  return rec;
 }
 public void DeleteIntervention(string id)
 {
  using var c=Open();using var tx=c.BeginTransaction();
  using(var q=c.CreateCommand()){q.Transaction=tx;q.CommandText="DELETE FROM interventions WHERE id=$i AND source='app'";q.Parameters.AddWithValue("$i",id);
   if(q.ExecuteNonQuery()==0)throw new InvalidDataException("Intervento non trovato o non eliminabile.");}
  // spare parts go back to their location
  using(var m=c.CreateCommand()){m.Transaction=tx;m.CommandText="DELETE FROM movements WHERE intervention=$i AND type='intervento'";m.Parameters.AddWithValue("$i",id);m.ExecuteNonQuery();}
  // its photos go with it
  using(var f=c.CreateCommand()){f.Transaction=tx;f.CommandText="DELETE FROM photos WHERE intervention=$i";f.Parameters.AddWithValue("$i",id);f.ExecuteNonQuery();}
  tx.Commit();
 }
}
