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
  InitMagazzino(c);
  InitRapportini(c);
  InitContabilita(c);
  InitSettings(c);
  InitDtr(c);
  InitItal(c);InitRch(c);
  InitEdits(c);InitWork(c);InitNovita(c);
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
   case "dtr":return ApplyDtr(headers,rows,capturedAt,source.Replace("Zucchetti","DTR-Service"));
   case "dtrdetail":return ApplyDtrDetails(headers,rows,capturedAt);
   case "dtrstatus": // session heartbeat: headers = [connected, message, portal total]
    if(headers.Count<2)throw new InvalidDataException("Stato DTR incompleto.");
    DtrHeartbeat(headers[0]=="1",headers[1],headers.Count>2&&int.TryParse(headers[2],out var tot)?tot:0);return "DTR-Service: stato aggiornato";
   case "ital":return ApplyItal(headers,rows,capturedAt,source.Replace("Zucchetti","Ital Retail"));
   case "italdetail":return ApplyItalDetails(headers,rows,capturedAt);
   case "italorders":return ApplyItalOrders(headers,rows);
   case "italstatus":
    if(headers.Count<2)throw new InvalidDataException("Stato Ital Retail incompleto.");
    ItalHeartbeat(headers[0]=="1",headers[1],headers.Count>2&&int.TryParse(headers[2],out var itot)?itot:0);return "Ital Retail: stato aggiornato";
   case "rch":return ApplyRch(headers,rows,capturedAt,source.Replace("Zucchetti","RCH XDealer"));
   case "rchstatus":
    if(headers.Count<2)throw new InvalidDataException("Stato RCH incompleto.");
    RchHeartbeat(headers[0]=="1",headers[1],headers.Count>2&&int.TryParse(headers[2],out var rtot)?rtot:0);return "RCH XDealer: stato aggiornato";
   default:throw new InvalidDataException("Tipo di elenco sconosciuto: "+kind);
  }
 }
 static void LogTx(SqliteConnection c,SqliteTransaction tx,string kind,string message){using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="INSERT INTO events(time,kind,message) VALUES($t,$k,$m)";q.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));q.Parameters.AddWithValue("$k",kind);q.Parameters.AddWithValue("$m",message);q.ExecuteNonQuery();}
 SqliteConnection Open(){var c=new SqliteConnection(new SqliteConnectionStringBuilder{DataSource=DatabasePath,Mode=SqliteOpenMode.ReadWriteCreate}.ToString());c.Open();return c;}
 public List<LicenseRecord> All(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT data FROM licenses ORDER BY client_code,serial";using var r=q.ExecuteReader();var list=new List<LicenseRecord>();while(r.Read())list.Add(JsonSerializer.Deserialize<LicenseRecord>(r.GetString(0))!);return list;}
 public List<SourceState> Sources(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT data FROM sources ORDER BY scope";using var r=q.ExecuteReader();var list=new List<SourceState>();while(r.Read())list.Add(JsonSerializer.Deserialize<SourceState>(r.GetString(0))!);return list;}
 public void SaveEmpty(string scope,string time){using var c=Open();using var q=c.CreateCommand();q.CommandText="INSERT INTO sources VALUES($s,$d) ON CONFLICT(scope) DO UPDATE SET data=$d";q.Parameters.AddWithValue("$s",scope);q.Parameters.AddWithValue("$d",JsonSerializer.Serialize(new SourceState(scope,time,true,0,"Portale: Nessun dato")));q.ExecuteNonQuery();}
 /// Shared history (everyone sees it). Security events (kind "users": sign-ins, addresses, devices) are kept out: see SecurityEvents.
 public List<SyncEvent> Events(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT time,kind,message FROM events WHERE kind<>'users' ORDER BY id DESC LIMIT 100";using var r=q.ExecuteReader();var list=new List<SyncEvent>();while(r.Read())list.Add(new(r.GetString(0),r.GetString(1),r.GetString(2)));return list;}
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
 /// Sign-ins, blocked attempts, devices and user changes: for administrators only.
 public List<SyncEvent> SecurityEvents(int limit=60){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT time,kind,message FROM events WHERE kind='users' ORDER BY id DESC LIMIT $n";q.Parameters.AddWithValue("$n",limit);using var r=q.ExecuteReader();var list=new List<SyncEvent>();while(r.Read())list.Add(new(r.GetString(0),r.GetString(1),r.GetString(2)));return list;}
 public void Log(string kind,string message){using var c=Open();using var q=c.CreateCommand();q.CommandText="INSERT INTO events(time,kind,message) VALUES($t,$k,$m)";q.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));q.Parameters.AddWithValue("$k",kind);q.Parameters.AddWithValue("$m",message);q.ExecuteNonQuery();}
 public void Backup(string path){using var source=Open();using var destination=new SqliteConnection(new SqliteConnectionStringBuilder{DataSource=path}.ToString());destination.Open();source.BackupDatabase(destination);using var j=destination.CreateCommand();j.CommandText="PRAGMA journal_mode=DELETE";j.ExecuteScalar();} // one self-contained file (no -wal beside it on the backup drive)
#if !TOPCASH_SERVER
 /// A handwriting-like stroke rendered to PNG (WPF), used only by the self-test.
 static byte[] TestSignature()
 {
  var v=new System.Windows.Media.DrawingVisual();
  using(var dc=v.RenderOpen())
  {
   var geo=new System.Windows.Media.StreamGeometry();
   using(var ctx=geo.Open()){ctx.BeginFigure(new(20,60),false,false);for(int x=20;x<380;x+=6)ctx.LineTo(new(x,60+28*Math.Sin(x/17.0)*Math.Cos(x/53.0)),true,true);}
   dc.DrawGeometry(null,new System.Windows.Media.Pen(new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(16,24,64)),3.2){StartLineCap=System.Windows.Media.PenLineCap.Round},geo);
  }
  var bmp=new System.Windows.Media.Imaging.RenderTargetBitmap(400,120,96,96,System.Windows.Media.PixelFormats.Pbgra32);bmp.Render(v);
  var enc=new System.Windows.Media.Imaging.PngBitmapEncoder();enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bmp));
  using var ms=new MemoryStream();enc.Save(ms);return ms.ToArray();
 }
#endif
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
  // warehouse: ledger, negative-stock guard, intervention parts (edit-safe), inventory, Mastro alignment
  decimal Q(string a,string w)=>store.Stock().Where(s=>s.Article==a&&s.Warehouse==w).Sum(s=>s.Qty);
  store.SaveArticle(new("ROT-80","Rotolo carta 80mm","8001234567890","Consumabili","PZ",0.5m,1.2m,22,"","",false,true,"","",""),"test");
  store.SaveArticle(new("RT-200","RT Form 200","","Registratori","PZ",350,590,22,"","",true,true,"","",""),"test");
  store.AddMovements([new("","","carico","ROT-80",100,"","sede",0.45m,"","","","DDT 1","","")],"test");
  if(Q("ROT-80","sede")!=100)throw new Exception("Giacenza dopo il carico");
  try{store.AddMovements([new("","","scarico","ROT-80",101,"sede","",0,"","","","","","")],"test");throw new Exception("Giacenza negativa accettata");}catch(InvalidDataException){}
  try{store.AddMovements([new("","","carico","RT-200",1.5m,"","sede",0,"","","","","","")],"test");throw new Exception("Decimali su articolo a matricola");}catch(InvalidDataException){}
  try{store.SaveArticle(new("ALTRO","x","8001234567890","","PZ",0,0,22,"","",false,true,"","",""),"test");throw new Exception("Codice a barre duplicato");}catch(InvalidDataException){}
  store.ImportMastro(new([mc],[],[],"t","test"),"test");
  var iv=store.SaveIntervention(new("","M15","Cliente Mastro","2026-11-09","","Cambio rotoli","","Chiuso","Mario","Normale","","","","","","",[new("ROT-80",5,"","")]),"test");
  store.SaveIntervention(iv with{Parts=[new("ROT-80",3,"sede","")]},"test"); // edit: 3 instead of 5, not 5+3
  if(Q("ROT-80","sede")!=97)throw new Exception("Ricambi intervento non idempotenti");
  store.DeleteIntervention(iv.Id);if(Q("ROT-80","sede")!=100)throw new Exception("Ricambi non restituiti alla cancellazione");
  if(store.RecordInventory("sede",new(){["ROT-80"]=98},"test")!=1||Q("ROT-80","sede")!=98)throw new Exception("Inventario");
  // products are entered by hand only: a Mastro import never touches the catalogue or the quantities
  store.ImportMastro(new([mc],[],[],"t","test"),"test");
  if(Q("ROT-80","sede")!=98||store.Articles().Single(a=>a.Code=="ROT-80").Barcode!="8001234567890")throw new Exception("Import Mastro ha toccato il magazzino");
  // signed report: yearly number, lock, unsign/re-sign keeps the number, PDF
#if !TOPCASH_SERVER
  var sig=TestSignature();
  var rv=store.SaveIntervention(new("","M15","Cliente Mastro","2026-11-09","","Sostituzione stampante fiscale","Sostituita la stampante termica guasta e verificato il collegamento con l'Agenzia delle Entrate.\nCliente istruito sull'uso.","Aperto","Mario","Alta","","","","","","",[new("ROT-80",2,"sede","")],"1.5"),"test");
  try{store.SignIntervention(rv.Id,"Cliente","",[1,2,3],true,"test");throw new Exception("Firma non valida accettata");}catch(InvalidDataException){}
  var signed=store.SignIntervention(rv.Id,"Mario Rossi","1,5",sig,true,"test");
  if(!signed.ReportNo.EndsWith("/0001")||signed.Status!="Chiuso"||store.Signature(rv.Id)==null)throw new Exception("Firma rapportino");
  try{store.SaveIntervention(signed with{Title="Modifica"},"test");throw new Exception("Rapportino firmato modificabile");}catch(InvalidDataException){}
  store.UnsignIntervention(rv.Id,"test");store.SaveIntervention(store.Interventions().Single(x=>x.Id==rv.Id) with{Work="Lavoro aggiornato"},"test");
  var again=store.SignIntervention(rv.Id,"Mario Rossi","1.5",sig,true,"test");if(again.ReportNo!=signed.ReportNo)throw new Exception("Numero rapportino cambiato");
  var pdf=InterventionReport.Build(store,rv.Id);if(pdf.Length<2000||pdf[0]!=(byte)'%'||pdf[1]!=(byte)'P')throw new Exception("PDF rapportino");
  File.WriteAllBytes(Path.Combine(folder,"rapportino-esempio.pdf"),pdf);
  // e-mail: validation, password encrypted and never exposed, message with the signed PDF, sent mark survives edits
  if(store.MailReady())throw new Exception("Email attiva senza configurazione");
  try{store.SaveMail(new("smtp.prova.it",587,"starttls","u","x",false,"",null,"",true),"test");throw new Exception("Email attivata senza mittente");}catch(InvalidDataException){}
  try{store.SaveMail(new("smtp prova",587,"starttls","","",false,"a@b.it",null,"",false),"test");throw new Exception("Server SMTP non validato");}catch(InvalidDataException){}
  try{Mailer.Addresses("cliente@",3);throw new Exception("Indirizzo non validato");}catch(InvalidDataException){}
  if(Mailer.Addresses("a@b.it; A@b.it, c@d.com",3).Count!=2)throw new Exception("Indirizzi doppi");
  var mv=store.SaveMail(new("smtp.prova.it",587,"starttls","assistenza@prova.it","S3greta!",false,"assistenza@prova.it","Top Cash","ufficio@prova.it",true),"test");
  if(!mv.Ready||!mv.HasPassword||store.Mail().Password.Contains("S3greta")||Unprotect(store.Mail().Password)!="S3greta!")throw new Exception("Password email non cifrata");
  store.SaveMail(new("smtp.prova.it",465,"ssl","assistenza@prova.it","",false,"assistenza@prova.it","Top Cash","ufficio@prova.it",true),"test");
  if(Unprotect(store.Mail().Password)!="S3greta!")throw new Exception("Password email persa al salvataggio");
  var msg=Mailer.ReportMessage(store,rv.Id,"cliente@prova.it");
  if(msg.Attachments.Count()!=1||!(msg.Subject??"").Contains(again.ReportNo)||msg.Bcc.Mailboxes.Single().Address!="ufficio@prova.it")throw new Exception("Email rapportino");
  try{Mailer.ReportMessage(store,store.SaveIntervention(new("","M15","Cliente Mastro","2026-11-09","","Da firmare","","Aperto","Mario","Normale","","","","","",""),"test").Id,"c@prova.it");throw new Exception("Inviato rapportino non firmato");}catch(InvalidDataException){}
  store.MarkReportSent(rv.Id,"cliente@prova.it","test");
  if(store.Interventions().Single(x=>x.Id==rv.Id).SentTo!="cliente@prova.it")throw new Exception("Invio non registrato");
  store.SaveMail(new("",587,"starttls","","",true,"",null,"",false),"test");if(store.Mail().Password.Length!=0||store.MailReady())throw new Exception("Disattivazione email");
  // 2.9.0: photos (also in the PDF), stops of the round done, machines to check, notices for the phones, weekly summary
  static byte[] Jpg(){var px=new byte[64*48*4];for(var k=0;k<px.Length;k+=4){px[k]=200;px[k+1]=90;px[k+2]=40;}
   var bmp=System.Windows.Media.Imaging.BitmapSource.Create(64,48,96,96,System.Windows.Media.PixelFormats.Bgr32,null,px,64*4);
   var enc=new System.Windows.Media.Imaging.JpegBitmapEncoder{QualityLevel=80};enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bmp));using var ms=new MemoryStream();enc.Save(ms);return ms.ToArray();}
  var jpg=Jpg();
  try{store.AddPhoto(rv.Id,[1,2,3],"test");throw new Exception("Foto non valida accettata");}catch(InvalidDataException){}
  try{store.AddPhoto("mi1",jpg,"test");throw new Exception("Foto su intervento storico accettata");}catch(InvalidDataException){}
  try{store.AddPhoto(rv.Id,jpg,"Mario");throw new Exception("Foto su rapportino firmato accettata");}catch(InvalidDataException){}
  store.UnsignIntervention(rv.Id,"test");
  var ph=store.AddPhoto(rv.Id,jpg,"Mario");if(store.Photos()[rv.Id].Single().Id!=ph.Id||store.Photo(ph.Id)?.Length!=jpg.Length)throw new Exception("Foto: salvataggio");
  store.SignIntervention(rv.Id,"Mario Rossi","1.5",sig,true,"test");
  var pdf2=InterventionReport.Build(store,rv.Id);if(pdf2.Length<=pdf.Length)throw new Exception("Foto nel PDF");
  File.WriteAllBytes(Path.Combine(folder,"rapportino-con-foto.pdf"),pdf2);
  store.DeletePhoto(ph.Id,"test");if(store.Photos().ContainsKey(rv.Id))throw new Exception("Foto: eliminazione");
  store.SaveRouteDone("i"+rv.Id,true,"Mario");if(store.RouteDone()["i"+rv.Id].Day!=DateTime.Today.ToString("yyyy-MM-dd"))throw new Exception("Giro: tappa fatta");
  store.SaveRouteDone("i"+rv.Id,false,"Mario");if(store.RouteDone().Count!=0)throw new Exception("Giro: tappa da rifare");
  store.SaveMachineFlags(["m1","m1"],"dismessa","test");if(store.MachineFlags().Single().Value.State!="dismessa")throw new Exception("Macchine: dismessa");
  try{store.SaveMachineFlags(["m1"],"boh","test");throw new Exception("Macchine: scelta non valida accettata");}catch(InvalidDataException){}
  store.SaveMachineFlags(["m1"],"","test");if(store.MachineFlags().Count!=0)throw new Exception("Macchine: di nuovo da controllare");
  var since=DateTimeOffset.Now.AddMinutes(-1);
  var na=store.SaveIntervention(new("","M15","Cliente Mastro","2026-11-12","","Cassa ferma","","Aperto","Luca","Urgente","","","","","",""),"Ufficio");
  if(store.Notices("Luca",since,false).SingleOrDefault(n=>n.Id==na.Id)?.Kind!="assigned"||store.Notices("Ufficio",since,true).Any(n=>n.Id==na.Id)
   ||store.Notices("Mario",since,true).SingleOrDefault(n=>n.Id==na.Id)?.Kind!="urgent"||store.Notices("Mario",since,false).Any(n=>n.Id==na.Id))throw new Exception("Avvisi sul telefono");
  var wk=WeeklyReport.Message(store,"ufficio@prova.it",DateTime.Today);if(!(wk.HtmlBody??"").Contains("Interventi aperti")||!(wk.HtmlBody??"").Contains("Cassa ferma")||!(wk.TextBody??"").Contains("Verificazioni"))throw new Exception("Riepilogo settimanale");
  try{store.SaveWeekly(true,"","test");throw new Exception("Riepilogo senza destinatario");}catch(InvalidDataException){}
  store.AddPhoto(na.Id,jpg,"Luca");store.DeleteIntervention(na.Id);if(store.Photos().ContainsKey(na.Id))throw new Exception("Foto rimaste dopo l'eliminazione");
#endif
  // backups: verified local copy + external copy; missing drive reported (and recovered) without losing the local backup
  var ext=Path.Combine(folder,"esterno");
  Backups.Save(store,ext,"test");var bs=Backups.Run(store,folder);
  if(bs.LastExternal.Length==0||bs.Error.Length>0||Directory.GetFiles(ext,"TopCash-*.sqlite").Length!=1||Directory.GetFiles(Path.Combine(folder,"backups"),"TopCash-*.sqlite").Length!=1)throw new Exception("Backup esterno");
  // today's backup follows the changes made during the day (and only those: our own status write is not one)
  var day1=Directory.GetFiles(ext,"TopCash-*.sqlite").Single();var t0=File.GetLastWriteTimeUtc(day1);
  bs=Backups.Run(store,folder);if(File.GetLastWriteTimeUtc(day1)!=t0)throw new Exception("Backup rifatto senza modifiche");
  Thread.Sleep(30);store.Log("test","modifica dopo il backup");bs=Backups.Run(store,folder);
  if(File.GetLastWriteTimeUtc(day1)==t0||bs.Error.Length>0||Directory.GetFiles(ext,"TopCash-*.sqlite").Length!=1||Directory.GetFiles(Path.Combine(folder,"backups"),"TopCash-*.sqlite*").Length!=1)throw new Exception("Backup del giorno non aggiornato");
  {using var bc=new SqliteConnection(new SqliteConnectionStringBuilder{DataSource=day1,Mode=SqliteOpenMode.ReadOnly,Pooling=false}.ToString());bc.Open();using var bq=bc.CreateCommand();bq.CommandText="SELECT count(*) FROM events WHERE message='modifica dopo il backup'";if(Convert.ToInt64(bq.ExecuteScalar())!=1)throw new Exception("Backup del giorno senza l'ultima modifica");}
  t0=File.GetLastWriteTimeUtc(day1);bs=Backups.Run(store,folder);if(File.GetLastWriteTimeUtc(day1)!=t0)throw new Exception("Backup rifatto senza modifiche");
  var missing=Enumerable.Range('K',16).Select(l=>(char)l+@":\").First(d=>!Directory.Exists(d));
  Backups.Save(store,missing+"Backup","test");bs=Backups.Run(store,folder,true,"test");
  if(bs.Error.Length==0||Directory.GetFiles(Path.Combine(folder,"backups"),"TopCash-*.sqlite").Length!=2)throw new Exception("Disco esterno assente non segnalato");
  try{Backups.Save(store,"backup\\relativo","test");throw new Exception("Percorso relativo accettato");}catch(InvalidDataException){}
  Backups.Save(store,ext,"test");bs=Backups.Run(store,folder,true,"test");if(bs.Error.Length>0||Directory.GetFiles(ext,"TopCash-*.sqlite").Length!=2)throw new Exception("Ripresa backup esterno");
  // accounting: Mastro mirror replaced atomically, office notes survive re-imports, null lists keep existing data, CSV guards
  var dues=new List<Due>{new("C15-1-2026-01-31-1","C","15","Cliente Mastro","1","2026-01-01","2026-01-31",122,0,"Bonifico"),new("C15-2-2026-12-31-2","C","15","Cliente Mastro","2","2026-12-01","2026-12-31",50,50,""),new("F9-7-2026-11-30-3","F","9","=Fornitore","7","2026-11-01","2026-11-30",80,200,"")};
  store.ImportMastro(new([mc],[],[],"t","test",[new("2026-1-F","1","2026-01-01","15","Cliente Mastro","Fattura",100,22,122,"Bonifico"),new("2026-2-N","2","2026-02-01","15","Cliente Mastro","Nota di credito",10,2.2m,12.2m,"")],dues,[new("C","15",122,0)]),"test");
  if(store.Invoices().Count!=2||store.Dues().Count!=3||store.Balances().Single().Debit!=122)throw new Exception("Import contabilità");
  if(store.Dues().Single(d=>d.Party=="F").Paid!=80)throw new Exception("Pagato oltre l'importo accettato");
  store.SaveDueNote("C15-1-2026-01-31-1","Sollecitato","Telefonato il 9/11","test");
  try{store.SaveDueNote("C15-1-2026-01-31-1","Boh","","test");throw new Exception("Stato scadenza non validato");}catch(InvalidDataException){}
  try{store.SaveDueNote("NON-ESISTE","Promesso","","test");throw new Exception("Nota su scadenza inesistente");}catch(InvalidDataException){}
  store.ImportMastro(new([mc],[],[],"t","test",null,dues.Take(1).ToList(),null),"test");
  if(store.Invoices().Count!=2||store.Balances().Count!=1||store.Dues().Count!=1||store.DueNotes()["C15-1-2026-01-31-1"].Status!="Sollecitato")throw new Exception("Note o dati contabili persi al re-import");
  // insights: an instalment 9 months late makes the customer a "C" payer
  var ins=store.Insights(new DateTime(2026,11,12));if(ins.Payers.SingleOrDefault(p=>p.ClientCode=="15") is not {Grade:"C",Rates:1})throw new Exception("Analisi: clienti in ritardo");
  if(!AccountingReports.DuesCsv(store).Contains("Sollecitato")||AccountingReports.InvoicesCsv(store).Split('\n').Length<3)throw new Exception("Export contabilità");
  store.SaveDueNote("C15-1-2026-01-31-1","","","test");if(store.DueNotes().Count!=0)throw new Exception("Cancellazione nota scadenza");
  // DTR-Service: whole-list replace, dates parsed, wrong columns rejected, session state tracked
  var dh=Store.DtrHeaders.ToList();
  store.ApplyPortalList("dtr","DTR","Registratori DTR-Service",dh,[["4CEDL000385","DPALM","PUZZOLI GINO","","10.70","3","05/11/2034","2255","07/11/2026 13:48"],["4CEDL000403","DPALM DCASH+","Stockeria di Ciatti","Stockeria S.n.c.","10.49","5","07/11/2026","1808","09/11/2026 12:11"],["4CEDL000403","x","dup","","","","","",""]],"2026-11-10T10:00:00+01:00","test");
  var dl=store.DtrRegisters();if(dl.Count!=2||dl[0].GeDue!="2034-11-05"||dl[0].LastSync!="2026-11-07T13:48"||dl[1].Alias!="Stockeria S.n.c."||dl[1].LastZ!=1808)throw new Exception("DTR: elenco");
  try{store.ApplyPortalList("dtr","DTR","x",["Matricola","Cliente"],[["4CEDL000999","a"]],"t","test");throw new Exception("DTR: colonne errate accettate");}catch(InvalidDataException){}
  try{store.ApplyPortalList("dtr","DTR","x",dh,[],"t","test");throw new Exception("DTR: elenco vuoto accettato");}catch(InvalidDataException){}
  if(store.DtrRegisters().Count!=2)throw new Exception("DTR: dati persi dopo un errore");
  var det=System.Text.Json.JsonSerializer.Serialize(new{Serial="4CEDL000385",Holder="PUZZOLI GINO",Vat="04989200482",CertDue="17/07/2028",LastVp="2026-07-25T09:00:13.163Z",GeInit="25/06/2026",Licenses=new[]{new{Client="PUZZOLI GINO",From="24/07/2026",To="19/07/2027",Status="Valida",Name="RT-Utility",Type="A pagamento",AutoRenew=""}},Errors=new[]{new{At="09/11/2026 18:30:50",Text="31 - CHIUSURE RIMANENTI:157"}},Works=Array.Empty<object>(),Extensions="",Warning="Attenzione!",Address="VIA ROMA 1 FIRENZE"});
  store.ApplyPortalList("dtrdetail","DTR","Dettagli",["Matricola","Dettaglio"],[["4CEDL000385",det],["ALTRA",det]],"2026-11-10T10:00:00+01:00","test");
  store.ApplyPortalList("dtr","DTR","Registratori DTR-Service",dh,[["4CEDL000385","DPALM","PUZZOLI GINO","","10.70","3","05/11/2034","2256","10/11/2026 13:48"]],"2026-11-10T12:00:00+01:00","test");
  var dd=store.DtrRegisters().Single().Detail;
  if(dd==null||dd.Vat!="04989200482"||dd.CertDue!="2028-07-17"||dd.LastVp!="2026-07-25"||dd.GeInit!="2026-06-25"||dd.Licenses![0].To!="2027-07-19"||dd.Errors![0].At!="2026-11-09T18:30"||dd.Address!="VIA ROMA 1 FIRENZE")throw new Exception("DTR: dettaglio");
  store.ApplyPortalList("dtrstatus","DTR","Stato",["0","Accesso richiesto"],[],"t","test");if(store.DtrState().Connected||store.DtrState().Error.Length==0)throw new Exception("DTR: sessione scaduta non segnalata");
  store.ApplyPortalList("dtrstatus","DTR","Stato",["1","Collegato","782"],[],"t","test");if(!store.DtrState().Connected||store.DtrState().Error.Length>0||store.DtrState().PortalTotal!=782)throw new Exception("DTR: ripresa sessione");
  // Ital Retail: whole-list replace, dd-MM-yyyy dates, details kept across list refreshes, orders, session state
  var ih=Store.ItalHeaders.ToList();
  store.ApplyPortalList("ital","ITAL","Dispositivi Ital Retail",ih,[["1BEN1002040","1831","NEXT","No","Sì","","EQUAZIONE"],["1BEN1002043","1864","NEXT","Sì [ version 1.0.4 ]","No","","HOTEL"]],"2026-11-10T10:00:00+01:00","test");
  var il=store.ItalDevices();if(il.Count!=2||il[0].Update!=""||!il[0].Subscribed||il[1].Update!="1.0.4"||il[1].Subscribed||il[1].PortalId!="1864")throw new Exception("Ital Retail: elenco");
  try{store.ApplyPortalList("ital","ITAL","x",["Matricola"],[["1BEN1002040"]],"t","test");throw new Exception("Ital Retail: colonne errate accettate");}catch(InvalidDataException){}
  try{store.ApplyPortalList("ital","ITAL","x",ih,[],"t","test");throw new Exception("Ital Retail: elenco vuoto accettato");}catch(InvalidDataException){}
  var idet=JsonSerializer.Serialize(new ItalDetail("1BEN1002040","03-11-2026","03-11-2027",true,"1.0.4",8,1,"330 Giornate lavorative",98,"In servizio","05-11-2026","03-11-2026","03-11-2026","03-11-2034","09-11-2026 18:23:35",Updates:[new("05-11-2026","1.0.3","1.0.4")],Dgfe:[new("330 giornate lavorative","1","14-09-2026 08:19:29","Completata online")]));
  store.ApplyPortalList("italdetail","ITAL","Dettagli",["Matricola","Dettaglio"],[["1BEN1002040",idet],["ALTRA",idet]],"2026-11-10T10:00:00+01:00","test");
  store.ApplyPortalList("ital","ITAL","Dispositivi Ital Retail",ih,[["1BEN1002040","1831","NEXT","No","Sì","","EQUAZIONE"]],"2026-11-10T12:00:00+01:00","test");
  var idd=store.ItalDevices().Single().Detail;
  if(idd==null||idd.SubTo!="2027-11-03"||idd.CertDue!="2034-11-03"||idd.DataAt!="2026-11-09T18:23"||idd.DgfeFree!=98||idd.Updates![0].At!="2026-11-05"||idd.Dgfe![0].At!="2026-09-14T08:19")throw new Exception("Ital Retail: dettaglio");
  store.ApplyPortalList("italorders","ITAL","Ordini",Store.ItalOrderHeaders.ToList(),[["3420","03-11-2026","1BEN1002040","-","Contratto di manutenzione","0,00 €"]],"t","test");
  if(store.ItalOrders().Single().Date!="2026-11-03"||store.ItalOrders()[0].Client!="")throw new Exception("Ital Retail: ordini");
  store.ApplyPortalList("italstatus","ITAL","Stato",["0","Accesso richiesto"],[],"t","test");if(store.ItalState().Connected||store.ItalState().Error.Length==0)throw new Exception("Ital Retail: sessione scaduta non segnalata");
  store.ApplyPortalList("italstatus","ITAL","Stato",["1","Collegato","7"],[],"t","test");if(!store.ItalState().Connected||store.ItalState().Error.Length>0)throw new Exception("Ital Retail: ripresa sessione");
  var rj="{\"Serial\":\"80IEQ012345\",\"PortalId\":\"77\",\"Type\":\"Print!F\",\"Client\":\"BAR SPORT\",\"Vat\":\"01234567890\",\"Contract\":true,\"Update\":\"8.1\",\"DgfeFree\":7,\"LastVerification\":\"2025-03-04 10:00:00\",\"CertDue\":\"2031-01-02 00:00:00\"}";
  store.ApplyPortalList("rch","RCH","Dispositivi RCH",Store.RchHeaders.ToList(),[["80IEQ012345",rj],["ALTRA",rj]],"2026-11-11T10:00:00+01:00","test");
  var rd=store.RchDevices().Single();if(rd.Client!="BAR SPORT"||!rd.Contract||rd.DgfeFree!=7||rd.LastVerification!="2025-03-04"||rd.CertDue!="2031-01-02"||rd.Update!="8.1")throw new Exception("RCH XDealer: elenco");
  try{store.ApplyPortalList("rch","RCH","x",Store.RchHeaders.ToList(),[],"t","test");throw new Exception("RCH XDealer: elenco vuoto accettato");}catch(InvalidDataException){}
  store.ApplyPortalList("rchstatus","RCH","Stato",["0","Accesso richiesto"],[],"t","test");if(store.RchState().Connected||store.RchState().Error.Length==0)throw new Exception("RCH XDealer: sessione scaduta");
  store.ApplyPortalList("rchstatus","RCH","Stato",["1","Collegato","1"],[],"t","test");if(!store.RchState().Connected||store.RchState().Error.Length>0)throw new Exception("RCH XDealer: ripresa sessione");
  // Administrator edits: customers created / changed / deleted, licences corrected or hidden, portal cleanup rules
  var nc=store.SaveClient("",new ClientEdit(Name:"Bar Nuovo",Vat:"it 01234567890",Email:"Info@Bar.it"),"test");
  var ce=store.ClientEdits()[nc];if(!ce.Created||ce.Vat!="IT01234567890"||ce.Email!="info@bar.it")throw new Exception("Clienti: creazione");
  try{store.SaveClient("",new ClientEdit(),"test");throw new Exception("Clienti: senza nome accettato");}catch(InvalidDataException){}
  store.DeleteClient(nc,true,"test");if(!store.ClientEdits()[nc].Deleted)throw new Exception("Clienti: eliminazione");
  store.SaveClient("000000000334426",new ClientEdit(Phone:"055 123"),"test");store.ResetClient("000000000334426","test");if(store.ClientEdits().ContainsKey("000000000334426"))throw new Exception("Clienti: annulla modifiche");
  var lic=store.All()[0];store.SaveLicense(lic.Id,new LicenseEdit(Expiry:"2031-01-31",Note:"rinnovata a mano"),"test");
  if(store.Licenses().Single(x=>x.Id==lic.Id).Expiry!="31-01-2031")throw new Exception("Licenze: modifica scadenza");
  store.SaveLicense(lic.Id,new LicenseEdit(Hidden:true),"test");if(store.Licenses().Any(x=>x.Id==lic.Id))throw new Exception("Licenze: nascosta ancora visibile");
  store.SaveLicense(lic.Id,new LicenseEdit(),"test");if(store.LicenseEdits().ContainsKey(lic.Id))throw new Exception("Licenze: ripristino");
  // Brands set aside (EDIT): machines hidden, customers hidden only when the registers they use are all of those brands
  MastroClient Mc(string code,string name)=>new(code,name,"","","","","","","",[],[],"","",new());
  Machine Mm(string id,string cl,string model,bool active)=>new(id,cl,model,"","S"+id,"","","","","",active,false,"","","","","");
  store.ImportMastro(new([mc,Mc("A1","Bar Edit"),Mc("B1","Bar Cambiato"),Mc("C1","Bar Chiuso")],[mm,Mm("m6","C1","RCH PRINT",false),Mm("m2","A1","EDIT SEVEN TOP",true),Mm("m3","B1","MIS.FISC.EDIT ONE",false),Mm("m4","B1","RCH ONDA",true),Mm("m5","B1","CREDIT CARD READER",true)],[],"t","test"),"test");
  var bv=store.Mastro();
  if(bv.Clients.Any(x=>x.Code=="A1")||!bv.Clients.Any(x=>x.Code=="B1")||bv.Machines.Any(x=>x.Id is "m2" or "m3")||!bv.Machines.Any(x=>x.Id=="m5")||bv.Excluded.Clients!=1||bv.Excluded.Machines!=1)throw new Exception("Marche escluse: regola EDIT");
  // ceased registers never enter, and neither does a customer left with only ceased registers
  if(bv.Clients.Any(x=>x.Code=="C1")||bv.Machines.Any(x=>x.Id=="m6")||bv.Excluded.CeasedClients!=1||bv.Excluded.CeasedMachines!=2)throw new Exception("Import pulito: macchine cessate");
  store.SaveHiddenBrands([],"test");if(store.Mastro().Machines.Count!=4||store.Mastro().Excluded.Clients!=0)throw new Exception("Marche escluse: ripristino");
  store.SaveHiddenBrands(["edit"],"test");if(!store.HiddenBrands().SequenceEqual(["EDIT"]))throw new Exception("Marche escluse: salvataggio");
  // IT inventory, renewals with history, manual device links, merged duplicates
  var asx=store.SaveAsset(new Asset(ClientCode:"M15",Kind:"PC",Name:"PC cassa",WarrantyTo:"2027-01-31"),"test");if(store.Assets().Single().WarrantyTo!="2027-01-31")throw new Exception("Inventario: salvataggio");
  try{store.SaveAsset(new Asset(ClientCode:"M15",Name:"x",WarrantyTo:"31/01/2027"),"test");throw new Exception("Inventario: data errata accettata");}catch(InvalidDataException){}
  try{store.SaveAsset(new Asset(Name:"x"),"test");throw new Exception("Inventario: senza cliente accettato");}catch(InvalidDataException){}
  store.DeleteAsset(asx.Id,"test");if(store.Assets().Count!=0)throw new Exception("Inventario: eliminazione");
  store.SaveRenewal("lic|x|2027-01-01","Contattato","richiamare","2026-12-01","test");store.SaveRenewal("lic|x|2027-01-01","Rinnovato","","","test");
  var rn=store.Renewals()["lic|x|2027-01-01"];if(rn.Status!="Rinnovato"||rn.Steps!.Count!=2||rn.Steps[0].Note!="richiamare")throw new Exception("Rinnovi: storia");
  try{store.SaveRenewal("k","Boh","","","test");throw new Exception("Rinnovi: stato non valido accettato");}catch(InvalidDataException){}
  store.SaveDeviceLink("ab123","M15","test");if(store.DeviceLinks()["AB123"]!="M15")throw new Exception("Abbinamenti: salvataggio");
  store.SaveDeviceLink("AB123","","test");if(store.DeviceLinks().Count!=0)throw new Exception("Abbinamenti: rimozione");
  store.MergeClient("X1","M15","test");if(store.ClientEdits()["X1"].MergedInto!="M15")throw new Exception("Unione clienti");
  store.Backup(Path.Combine(folder,"backup.sqlite"));Console.WriteLine("PASS: upsert, deduplicazione, validazione atomica, note, magazzino, rapportini, email, contabilità, backup esterni, DTR-Service, Ital Retail, RCH XDealer, modifiche amministratore, marche escluse, inventario, rinnovi, unioni.");
 }
}
