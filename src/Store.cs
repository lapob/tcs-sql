using Microsoft.Data.Sqlite;
using System.Text.Json;

namespace TopCash.Licenze;
public sealed partial class Store
{
 public string Folder {get;}
 public string DatabasePath=>Path.Combine(Folder,"clienti-licenze.sqlite");
 public Store(string folder)
 {
  Folder=folder;Directory.CreateDirectory(folder);
  using var c=Open();using var cmd=c.CreateCommand();
  cmd.CommandText="""
   PRAGMA journal_mode=WAL;
   CREATE TABLE IF NOT EXISTS licenses(id TEXT PRIMARY KEY,client_code TEXT NOT NULL,serial TEXT NOT NULL,product TEXT NOT NULL,scope TEXT NOT NULL,data TEXT NOT NULL);
   CREATE INDEX IF NOT EXISTS licenses_clients ON licenses(client_code);
   CREATE TABLE IF NOT EXISTS sources(scope TEXT PRIMARY KEY,data TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT,time TEXT NOT NULL,kind TEXT NOT NULL,message TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS imports(hash TEXT PRIMARY KEY,file_name TEXT NOT NULL,imported_at TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS notes(client_code TEXT PRIMARY KEY,note TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS client_meta(client_code TEXT PRIMARY KEY,data TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS clients(code TEXT PRIMARY KEY,data TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS orders(id TEXT PRIMARY KEY,family TEXT NOT NULL,data TEXT NOT NULL);
   CREATE INDEX IF NOT EXISTS orders_family ON orders(family);
   """;cmd.ExecuteNonQuery();
  InitGestionale(c);
 }
 public List<ClientRecord> Clients(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT data FROM clients";using var r=q.ExecuteReader();var list=new List<ClientRecord>();while(r.Read())list.Add(JsonSerializer.Deserialize<ClientRecord>(r.GetString(0))!);return list;}
 public List<OrderRecord> Orders(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT data FROM orders";using var r=q.ExecuteReader();var list=new List<OrderRecord>();while(r.Read())list.Add(JsonSerializer.Deserialize<OrderRecord>(r.GetString(0))!);return list;}
 static string Pick(string fresh,string old)=>fresh.Length>0?fresh:old;
 /// Upsert of the customer registry: new values win, empty values never erase known data, product families accumulate.
 public int ImportClients(string family,List<ClientRecord> list)
 {
  using var c=Open();using var tx=c.BeginTransaction();int added=0,changed=0;
  foreach(var rec in list)
  {
   using var check=c.CreateCommand();check.Transaction=tx;check.CommandText="SELECT data FROM clients WHERE code=$c";check.Parameters.AddWithValue("$c",rec.Code);
   var merged=rec;
   if(check.ExecuteScalar() is string old)
   {
    var o=JsonSerializer.Deserialize<ClientRecord>(old)!;
    merged=new(rec.Code,Pick(rec.Name,o.Name),Pick(rec.City,o.City),Pick(rec.Address,o.Address),Pick(rec.Phone,o.Phone),Pick(rec.Email,o.Email),Pick(rec.TaxCode,o.TaxCode),o.Families.Union(rec.Families).OrderBy(x=>x).ToList(),rec.CapturedAt);
    if(JsonSerializer.Serialize(merged with{CapturedAt=""})!=JsonSerializer.Serialize(o with{CapturedAt=""}))changed++;
   }else added++;
   using var cmd=c.CreateCommand();cmd.Transaction=tx;cmd.CommandText="INSERT INTO clients VALUES($c,$d) ON CONFLICT(code) DO UPDATE SET data=$d";
   cmd.Parameters.AddWithValue("$c",rec.Code);cmd.Parameters.AddWithValue("$d",JsonSerializer.Serialize(merged));cmd.ExecuteNonQuery();
  }
  LogTx(c,tx,"import",$"Anagrafica {family}: {list.Count} clienti · {added} nuovi · {changed} aggiornati.");tx.Commit();return list.Count;
 }
 /// The portal order list for a family is read in full, so it replaces the previous snapshot atomically.
 public int ReplaceOrders(string family,List<OrderRecord> list)
 {
  using var c=Open();using var tx=c.BeginTransaction();
  using(var d=c.CreateCommand()){d.Transaction=tx;d.CommandText="DELETE FROM orders WHERE family=$f";d.Parameters.AddWithValue("$f",family);d.ExecuteNonQuery();}
  foreach(var o in list.DistinctBy(x=>x.Id)){using var cmd=c.CreateCommand();cmd.Transaction=tx;cmd.CommandText="INSERT INTO orders VALUES($i,$f,$d)";cmd.Parameters.AddWithValue("$i",o.Id);cmd.Parameters.AddWithValue("$f",family);cmd.Parameters.AddWithValue("$d",JsonSerializer.Serialize(o));cmd.ExecuteNonQuery();}
  LogTx(c,tx,"import",$"Ordini {family}: {list.Count} ordini letti dal portale.");tx.Commit();return list.Count;
 }
 /// Single entry point for a portal list read by the sync (used locally and by the shared server).
 public string ApplyPortalList(string kind,string product,string scope,List<string> headers,List<List<string>> rows,string capturedAt,string source)
 {
  switch(kind)
  {
   case "licenses":
    if(rows.Count==0){SaveEmpty(scope,capturedAt);return scope+": elenco vuoto";}
    var batch=new SourceBatch(scope,capturedAt,true,headers,rows);
    var hash=Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(JsonSerializer.Serialize(batch))));
    var r=Import(batch,source,hash);return $"{scope}: {r.Rows} licenze";
   case "clients":
    if(rows.Count==0)return scope+": nessun cliente";
    return $"{scope}: {ImportClients(product,PortalMapper.Clients(product,headers,rows,capturedAt))} clienti";
   case "orders":
    return $"{scope}: {ReplaceOrders(product,PortalMapper.Orders(product,headers,rows,capturedAt))} ordini";
   default:throw new InvalidDataException("Tipo di elenco sconosciuto: "+kind);
  }
 }
 static void LogTx(SqliteConnection c,SqliteTransaction tx,string kind,string message){using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT INTO events(time,kind,message) VALUES($t,$k,$m)";q.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));q.Parameters.AddWithValue("$k",kind);q.Parameters.AddWithValue("$m",message);q.ExecuteNonQuery();}
 SqliteConnection Open(){var c=new SqliteConnection(new SqliteConnectionStringBuilder{DataSource=DatabasePath,Mode=SqliteOpenMode.ReadWriteCreate}.ToString());c.Open();return c;}
 public List<LicenseRecord> All(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT data FROM licenses ORDER BY client_code,serial";using var r=q.ExecuteReader();var list=new List<LicenseRecord>();while(r.Read())list.Add(JsonSerializer.Deserialize<LicenseRecord>(r.GetString(0))!);return list;}
 public List<SourceState> Sources(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT data FROM sources ORDER BY scope";using var r=q.ExecuteReader();var list=new List<SourceState>();while(r.Read())list.Add(JsonSerializer.Deserialize<SourceState>(r.GetString(0))!);return list;}
 public void SaveEmpty(string scope,string time){using var c=Open();using var q=c.CreateCommand();q.CommandText="INSERT INTO sources VALUES($s,$d) ON CONFLICT(scope) DO UPDATE SET data=$d";q.Parameters.AddWithValue("$s",scope);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(new SourceState(scope,time,true,0,"Portale: Nessun dato")));q.ExecuteNonQuery();}
 public List<SyncEvent> Events(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT time,kind,message FROM events ORDER BY id DESC LIMIT 100";using var r=q.ExecuteReader();var list=new List<SyncEvent>();while(r.Read())list.Add(new(r.GetString(0),r.GetString(1),r.GetString(2)));return list;}
 public Dictionary<string,string> Notes(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT client_code,note FROM notes";using var r=q.ExecuteReader();var d=new Dictionary<string,string>();while(r.Read())d[r.GetString(0)]=r.GetString(1);return d;}
 public Dictionary<string,ClientMeta> Metadata(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT client_code,data FROM client_meta";using var r=q.ExecuteReader();var d=new Dictionary<string,ClientMeta>();while(r.Read())d[r.GetString(0)]=JsonSerializer.Deserialize<ClientMeta>(r.GetString(1))!;return d;}
 public void SaveMeta(string code,ClientMeta data){if(data.NextContact.Length>0&&!DateTime.TryParseExact(data.NextContact,"yyyy-MM-dd",System.Globalization.CultureInfo.InvariantCulture,System.Globalization.DateTimeStyles.None,out _))throw new InvalidDataException("Data del promemoria non valida.");if(!new[]{"","Da contattare","In attesa","Completato"}.Contains(data.FollowupState))throw new InvalidDataException("Stato attività non riconosciuto.");using var c=Open();using var q=c.CreateCommand();q.CommandText="INSERT INTO client_meta VALUES($c,$d) ON CONFLICT(client_code) DO UPDATE SET data=$d";q.Parameters.AddWithValue("$c",code);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(data));q.ExecuteNonQuery();}
 public void SaveNote(string code,string note){if(note.Length>20000)throw new InvalidDataException("Nota troppo lunga.");using var c=Open();using var q=c.CreateCommand();q.CommandText="INSERT INTO notes VALUES($c,$n) ON CONFLICT(client_code) DO UPDATE SET note=$n";q.Parameters.AddWithValue("$c",code);q.Parameters.AddWithValue("$n",note);q.ExecuteNonQuery();}
 public bool WasImported(string hash){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT count(*) FROM imports WHERE hash=$h";q.Parameters.AddWithValue("$h",hash);return (long)q.ExecuteScalar()!>0;}
 public ImportResult Import(SourceBatch batch,string source,string hash)
 {
  var records=RecordMapper.Map(batch,source);if(records.Count==0)throw new InvalidDataException("Il file non contiene licenze: dati esistenti conservati.");
  using var c=Open();using var tx=c.BeginTransaction();int added=0,changed=0,unchanged=0;
  foreach(var record in records)
  {
   using var check=c.CreateCommand();check.Transaction=tx;check.CommandText="SELECT data FROM licenses WHERE id=$id";check.Parameters.AddWithValue("$id",record.Id);
   var old=check.ExecuteScalar() as string;
   if(old==null)added++;else{var prev=JsonSerializer.Deserialize<LicenseRecord>(old)!;if(prev.RawJson==record.RawJson)unchanged++;else changed++;}
   using var cmd=c.CreateCommand();cmd.Transaction=tx;cmd.CommandText="INSERT INTO licenses VALUES($id,$c,$s,$p,$scope,$data) ON CONFLICT(id) DO UPDATE SET data=$data,client_code=$c,serial=$s,product=$p,scope=$scope";
   cmd.Parameters.AddWithValue("$id",record.Id);cmd.Parameters.AddWithValue("$c",record.ClientCode);cmd.Parameters.AddWithValue("$s",record.Serial);cmd.Parameters.AddWithValue("$p",record.Product);cmd.Parameters.AddWithValue("$scope",record.Scope);cmd.Parameters.AddWithValue("$data",JsonSerializer.Serialize(record));cmd.ExecuteNonQuery();
  }
  var state=new SourceState(batch.Scope,batch.CapturedAt,batch.Complete,records.Count,Path.GetFileName(source));
  using(var q=c.CreateCommand()){q.Transaction=tx;q.CommandText="INSERT INTO sources VALUES($s,$d) ON CONFLICT(scope) DO UPDATE SET data=$d";q.Parameters.AddWithValue("$s",batch.Scope);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(state));q.ExecuteNonQuery();}
  using(var q=c.CreateCommand()){q.Transaction=tx;q.CommandText="INSERT OR IGNORE INTO imports VALUES($h,$f,$t)";q.Parameters.AddWithValue("$h",hash);q.Parameters.AddWithValue("$f",Path.GetFileName(source));q.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));q.ExecuteNonQuery();}
  using(var q=c.CreateCommand()){q.Transaction=tx;q.CommandText="INSERT INTO events(time,kind,message) VALUES($t,'import',$m)";q.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));q.Parameters.AddWithValue("$m",$"{batch.Scope}: {records.Count} licenze · {added} nuove · {changed} aggiornate · {unchanged} invariate. Elenco "+(batch.Complete?"letto fino all'ultima pagina.":"parziale."));q.ExecuteNonQuery();}
  tx.Commit();return new(records.Count,added,changed,unchanged,batch.Scope);
 }
 public void Log(string kind,string message){using var c=Open();using var q=c.CreateCommand();q.CommandText="INSERT INTO events(time,kind,message) VALUES($t,$k,$m)";q.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));q.Parameters.AddWithValue("$k",kind);q.Parameters.AddWithValue("$m",message);q.ExecuteNonQuery();}
 public void Backup(string path){using var source=Open();using var destination=new SqliteConnection(new SqliteConnectionStringBuilder{DataSource=path}.ToString());destination.Open();source.BackupDatabase(destination);}
 public static void SelfTest(string folder)
 {
  var store=new Store(folder);var b=new SourceBatch("TEST",DateTimeOffset.Now.ToString("O"),true,["Codice Cliente","Cliente","Matricola","Nome prodotto","Scadenza","Blocco"],[["00001","Cliente di prova <script>","TEST-001","Test","30-11-2026","NO"]]);
  var a=store.Import(b,"test.json","1");var c=store.Import(b,"test.json","2");
  if(a.Added!=1||c.Unchanged!=1||store.All().Count!=1)throw new Exception("Upsert non valido");
  var bad=b with{Rows=[["00001","Test","TEST-002","Test","data errata","NO"]]};try{store.Import(bad,"invalid.json","bad");throw new Exception("Validazione non eseguita");}catch(InvalidDataException){}
  if(store.All().Count!=1)throw new Exception("Import fallito ha alterato i dati");
  store.SaveNote("00001","Nota");if(store.Notes()["00001"]!="Nota")throw new Exception("Note non persistenti");
  var ch=new List<string>{"","","CODICE CLIENTE","RAGIONE SOCIALE 1","LOCALITÀ","INDIRIZZO","RECAPITO","EMAIL","CODICE FISCALE",""};
  store.ImportClients("POSSISTEMI",PortalMapper.Clients("POSSISTEMI",ch,[["","","00001","Cliente di prova","FIRENZE","VIA ROMA 1","055 123","INFO@PROVA.IT","01234567890",""]],"t1"));
  store.ImportClients("TILBY",PortalMapper.Clients("TILBY",ch,[["","","00001","Cliente di prova","FIRENZE","VIA ROMA 1","","","01234567890",""]],"t2"));
  var cl=store.Clients().Single();if(cl.Phone!="055 123"||cl.Email!="info@prova.it"||cl.Families.Count!=2)throw new Exception("Merge anagrafica non valido");
  var oh=new List<string>{"","Data ordine","Codice","Matricola","Ragione sociale","Stato"};
  store.ReplaceOrders("POSSISTEMI",PortalMapper.Orders("POSSISTEMI",oh,[["","29/10/2026 15:00","0001","H1","Cliente","Registrato"],["","28/10/2026 15:00","0002","H2","Cliente","Bozza"]],"t"));
  store.ReplaceOrders("POSSISTEMI",PortalMapper.Orders("POSSISTEMI",oh,[["","29/10/2026 15:00","0001","H1","Cliente","Registrato"]],"t"));
  if(store.Orders().Count!=1)throw new Exception("Sostituzione ordini non valida");
  // gestionale: Mastro import replaces only Mastro data, app interventions survive, history is read-only
  var mc=new MastroClient("15","Cliente Mastro","01234567890","","VIA ROMA 1","50100","FIRENZE","FI","",["055 111"],["a@b.it"],"","",new());
  var mm=new Machine("m1","15","RT FORM 200","RT","9CF001","99MEX001","","2024-01-10","","2025-01-10",true,true,"2026-01-01","2026-12-31","120","","");
  store.ImportMastro(new([mc],[mm],[new("mi1","15","Cliente Mastro","2025-03-01","1","Verifica","Verifica periodica","Chiuso","","Normale","50","mastro","","","","")],"t","test"),"test");
  try{store.SaveIntervention(new("","NON-ESISTE","x","","","Titolo","","Aperto","","Normale","","","","","",""),"test");throw new Exception("Cliente inesistente accettato");}catch(InvalidDataException){}
  var app=store.SaveIntervention(new("","M15","Cliente Mastro","2026-11-10","m1","Sostituzione rotolo","","Aperto","Mario","Alta","","","","","",""),"test");
  try{store.SaveIntervention(new("mi1","15","x","","","Modifica storico","","Chiuso","","Normale","","","","","",""),"test");throw new Exception("Storico Mastro modificabile");}catch(InvalidDataException){}
  try{store.SaveIntervention(app with{Status="Boh"},"test");throw new Exception("Stato non validato");}catch(InvalidDataException){}
  store.ImportMastro(new([mc with{Name="Cliente Mastro 2"}],[],[],"t","test"),"test");
  if(store.MastroClients().Single().Name!="Cliente Mastro 2"||store.Machines().Count!=0||store.Interventions().Count!=1||store.Interventions()[0].Id!=app.Id)throw new Exception("Importazione Mastro non atomica");
  try{store.ImportMastro(new([],[],[],"t","test"),"test");throw new Exception("Import vuoto accettato");}catch(InvalidDataException){}
  if(store.MastroClients().Count!=1)throw new Exception("Import vuoto ha cancellato dati");
  store.DeleteIntervention(app.Id);if(store.Interventions().Count!=0)throw new Exception("Eliminazione intervento");
#if !TOPCASH_SERVER
  var ini=Path.Combine(folder,"RETE.ini");File.WriteAllText(ini,"[Server]\r\nuser=u\r\npsw=p\r\nhost=10.0.0.5\r\nport=3307\r\ndbname=db1\r\n[Altro]\r\nhost=nope\r\n");
  var cfg=MastroReader.FromIni(ini)!;if(cfg.Host!="10.0.0.5"||cfg.Port!=3307||cfg.Database!="db1")throw new Exception("Lettura RETE.ini");
#endif
  store.Backup(Path.Combine(folder,"backup.sqlite"));Console.WriteLine("PASS: upsert, deduplicazione, validazione atomica, note, backup.");
 }
}
