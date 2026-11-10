using MySqlConnector;
using System.Globalization;
using System.Text;

namespace TopCash.Licenze;

/// Reads customers, installed machines and intervention history from the MastroSQL database.
/// STRICTLY READ-ONLY: only SELECT statements, inside a READ ONLY session; nothing is ever written to Mastro.
/// Connection settings come from Mastro's own RETE.ini on this PC (the user's configuration), never hard-coded.
public static class MastroReader
{
 public sealed record Config(string Host,int Port,string Database,string User,string Password,string IniPath);
 public sealed record Preview(int Clients,int Machines,int Interventions,int WithPhone,int WithEmail,List<string> ClientColumns,List<string> MachineColumns,string InterventionTable,List<MastroClient> Sample,List<string> Warnings,
  int Articles=0,int StockRows=0,int Warehouses=0,int Invoices=0,int Dues=0,int Balances=0);

 public static readonly string[] IniCandidates=[@"C:\MastroSQL\RETE.ini",@"C:\mastro20\RETE.ini",@"C:\MastroSQL\utility\RETE.ini"];
 public static Config? FromIni(string? path=null)
 {
  path??=IniCandidates.FirstOrDefault(File.Exists);if(path==null||!File.Exists(path))return null;
  Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
  string section="";var server=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
  foreach(var raw in File.ReadAllLines(path,Encoding.GetEncoding(1252)))
  {
   var line=raw.Trim();if(line.StartsWith('[')){section=line.Trim('[',']');continue;}
   var eq=line.IndexOf('=');if(eq<=0||!section.Equals("Server",StringComparison.OrdinalIgnoreCase))continue;
   server[line[..eq].Trim()]=line[(eq+1)..].Trim();
  }
  string G(string k)=>server.TryGetValue(k,out var v)?v:"";
  if(G("host").Length==0||G("dbname").Length==0)return null;
  return new(G("host"),int.TryParse(G("port"),out var p)?p:3306,G("dbname"),G("user"),G("psw"),path);
 }

 static string S(object? v)=>v switch
 {
  null or DBNull=>"",
  DateTime d=>d.Year<1900?"":d.ToString("yyyy-MM-dd"),
  MySqlDateTime md=>md.IsValidDateTime&&md.Year>=1900?md.GetDateTime().ToString("yyyy-MM-dd"):"",
  decimal m=>m.ToString("0.##",CultureInfo.InvariantCulture),
  double f=>f.ToString("0.##",CultureInfo.InvariantCulture),
  bool b=>b?"1":"0",
  byte[] bytes=>bytes.Length==1?(bytes[0]!=0?"1":"0"):"",
  ulong u=>u.ToString(CultureInfo.InvariantCulture),
  _=>Convert.ToString(v,CultureInfo.InvariantCulture)?.Trim()??""
 };
 static bool True(string s)=>s is "1" or "S" or "s" or "SI" or "si" or "Si" or "True" or "true" or "Y" or "T";

 public static async Task<(MastroBatch Batch,Preview Preview)> Read(Config cfg,CancellationToken ct=default)
 {
  var cs=new MySqlConnectionStringBuilder{Server=cfg.Host,Port=(uint)cfg.Port,Database=cfg.Database,UserID=cfg.User,Password=cfg.Password,
   ConnectionTimeout=10,DefaultCommandTimeout=120,AllowZeroDateTime=true,ConvertZeroDateTime=true,TreatTinyAsBoolean=false,Pooling=false,SslMode=MySqlSslMode.Preferred,AllowPublicKeyRetrieval=true}.ConnectionString;
  await using var c=new MySqlConnection(cs);await c.OpenAsync(ct);
  var warnings=new List<string>();
  try{await using var ro=new MySqlCommand("SET SESSION TRANSACTION READ ONLY",c);await ro.ExecuteNonQueryAsync(ct);}catch{warnings.Add("Il server MySQL non supporta la sessione READ ONLY: vengono comunque eseguite solo letture.");}

  // Table names (case-insensitive) from the catalog.
  var tables=new List<string>();
  await using(var t=new MySqlCommand("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()",c))
  await using(var r=await t.ExecuteReaderAsync(ct))while(await r.ReadAsync(ct))tables.Add(r.GetString(0));
  string? Tab(params string[] names)=>tables.FirstOrDefault(x=>names.Any(n=>x.Equals(n,StringComparison.OrdinalIgnoreCase)));
  var tClienti=Tab("clienti")??throw new InvalidDataException("Nel database Mastro non trovo la tabella 'clienti'.");
  var tMacc=Tab("lis_macc");var tTipi=Tab("macc_ti");
  // technician names (operai) and service kinds (servizi): only the two columns needed, never the rest of the staff table
  var techNames=new Dictionary<string,string>();var services=new Dictionary<string,string>();
  async Task Pairs(string? table,string k,string v,Dictionary<string,string> into)
  {
   if(table==null)return;
   try{await using var q=new MySqlCommand($"SELECT `{k}`,`{v}` FROM `{table.Replace("`","")}`",c);await using var r=await q.ExecuteReaderAsync(ct);
    while(await r.ReadAsync(ct)){var key=S(r.GetValue(0)).Trim();var val=S(r.GetValue(1)).Trim();if(key.Length>0&&key!="0"&&val.Length>0)into[key]=Nice(val);}}
   catch(Exception ex){warnings.Add($"Tabella '{table}' non leggibile: {ex.Message}");}
  }
  static string Nice(string s)=>s.Any(char.IsLower)?s:System.Globalization.CultureInfo.GetCultureInfo("it-IT").TextInfo.ToTitleCase(s.ToLowerInvariant());
  await Pairs(Tab("operai"),"CODI","NOME",techNames);await Pairs(Tab("servizi"),"NUME","DESCRI",services);
  string Tech(string code)=>code.Length==0||code=="0"?"":techNames.TryGetValue(code,out var n)?n:code.All(char.IsDigit)?"":code;
  async Task<List<Dictionary<string,string>>> All(string table,int max)
  {
   var rows=new List<Dictionary<string,string>>();
   await using var q=new MySqlCommand($"SELECT * FROM `{table.Replace("`","")}` LIMIT {max}",c);
   await using var r=await q.ExecuteReaderAsync(ct);
   while(await r.ReadAsync(ct)){var d=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);for(int i=0;i<r.FieldCount;i++){object? v;try{v=r.GetValue(i);}catch{v=null;}d[r.GetName(i)]=S(v);}rows.Add(d);}
   return rows;
  }
  static string F(Dictionary<string,string> d,params string[] keys){foreach(var k in keys)if(d.TryGetValue(k,out var v)&&v.Length>0)return v;return "";}

  // ── customers
  var raw=await All(tClienti,200_000);
  var cols=raw.FirstOrDefault()?.Keys.ToList()??[];
  bool IsPhone(string k)=>System.Text.RegularExpressions.Regex.IsMatch(k,@"(^|_)(TEL|TELE|TELEF|TELEFONO|TELEFONI|TEL\d|TELE\d|CELL|CELLU|CELLULARE|FAX|PHONE|RECAPITO|RECA_TEL|MOBILE)\d*($|_)",System.Text.RegularExpressions.RegexOptions.IgnoreCase)&&!k.Contains("TELEMAT",StringComparison.OrdinalIgnoreCase);
  bool IsMail(string k)=>System.Text.RegularExpressions.Regex.IsMatch(k,@"(E_?MAIL|^MAIL)",System.Text.RegularExpressions.RegexOptions.IgnoreCase)&&!k.Contains("PEC",StringComparison.OrdinalIgnoreCase);
  var known=new HashSet<string>(["CODI","DESCRI","DESCRI2","PIVA","COD_FIS","VIA","CAP","CITTA","PROV","ZONA","PEC","NOTE","NOTE1","AGENTE","AGENTE_2","AGENTE_PR","AGENTE_PR_2"],StringComparer.OrdinalIgnoreCase);
  var clients=new List<MastroClient>();
  foreach(var d in raw)
  {
   var code=F(d,"CODI","CODICE");if(code.Length==0||code=="0")continue;
   var name=(F(d,"DESCRI","RAGI_SOCI","RAGIONE")+" "+F(d,"DESCRI2")).Trim();if(name.Length==0)continue;
   var phones=d.Where(kv=>IsPhone(kv.Key)&&kv.Value.Count(char.IsDigit)>=6).Select(kv=>kv.Value).Distinct().ToList();
   var mails=d.Where(kv=>IsMail(kv.Key)&&kv.Value.Contains('@')).SelectMany(kv=>kv.Value.Split([';',',',' '],StringSplitOptions.RemoveEmptyEntries)).Where(x=>x.Contains('@')).Select(x=>x.Trim().ToLowerInvariant()).Distinct().ToList();
   var pec=d.Where(kv=>kv.Key.Contains("PEC",StringComparison.OrdinalIgnoreCase)&&kv.Value.Contains('@')).Select(kv=>kv.Value.Trim().ToLowerInvariant()).FirstOrDefault()??"";
   var notes=string.Join("\n",d.Where(kv=>kv.Key.StartsWith("NOTE",StringComparison.OrdinalIgnoreCase)&&kv.Value.Trim().Length>0&&kv.Value.Trim()!="0").Select(kv=>kv.Value));
   // every other filled field is kept, so no information is lost
   var extra=d.Where(kv=>!known.Contains(kv.Key)&&!IsPhone(kv.Key)&&!IsMail(kv.Key)&&!kv.Key.Contains("PEC",StringComparison.OrdinalIgnoreCase)&&!kv.Key.StartsWith("NOTE",StringComparison.OrdinalIgnoreCase)
    &&kv.Value.Length is >0 and <400&&kv.Value!="0"&&kv.Value!="0.00").Take(60).ToDictionary(kv=>kv.Key,kv=>kv.Value);
   clients.Add(new(code,name,F(d,"PIVA","P_IVA","PARTITA_IVA"),F(d,"COD_FIS","CODFIS","CODICE_FISCALE"),F(d,"VIA","INDIRIZZO"),F(d,"CAP"),F(d,"CITTA","LOCALITA"),F(d,"PROV","PROVINCIA"),F(d,"ZONA"),phones,mails,pec,notes,extra,Tech(F(d,"AGENTE"))));
  }
  clients=clients.DistinctBy(x=>x.Code).ToList();
  // phones often live in a separate address book table (one row per number): read every table that has a customer code and phone columns
  var phoneTables=new List<string>();
  {
   var byCode=clients.ToDictionary(x=>x.Code,StringComparer.OrdinalIgnoreCase);
   string[] bookWords=["tel","rubri","contat","recap","indir","referen","cli_"];
   foreach(var t in tables.Where(x=>!x.StartsWith("y_",StringComparison.OrdinalIgnoreCase)&&!x.Equals(tClienti,StringComparison.OrdinalIgnoreCase)&&bookWords.Any(w=>x.Contains(w,StringComparison.OrdinalIgnoreCase))).Take(30))
   {
    List<string> tc;
    try{await using var q=new MySqlCommand("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=@t",c);q.Parameters.AddWithValue("@t",t);
     tc=[];await using var r=await q.ExecuteReaderAsync(ct);while(await r.ReadAsync(ct))tc.Add(r.GetString(0));}catch{continue;}
    // the customer column wins over a generic code: Mastro's phone book (tele_nume) keeps customers AND suppliers,
    // CODI is the code of either, CODI_CLI only of customers (0 on supplier rows)
    string[] cliPref=["CODI_CLI","CODI_CLIE","COD_CLI","CLIENTE","CODI","CODICE"];
    var cli=cliPref.Select(p=>tc.FirstOrDefault(k=>k.Equals(p,StringComparison.OrdinalIgnoreCase))).FirstOrDefault(k=>k!=null);
    var onlyCustomers=cli!=null&&!cli.Equals("CODI",StringComparison.OrdinalIgnoreCase)&&!cli.Equals("CODICE",StringComparison.OrdinalIgnoreCase);
    var ph=tc.Where(IsPhone).ToList();var ml=tc.Where(IsMail).ToList();
    // phone book rows: the number is in NUMERO, its kind in TIPO_NUM (3 = fax, not useful to call)
    var numCol=tc.FirstOrDefault(k=>k.Equals("NUMERO",StringComparison.OrdinalIgnoreCase));var kindCol=tc.FirstOrDefault(k=>k.Equals("TIPO_NUM",StringComparison.OrdinalIgnoreCase));
    if(numCol!=null&&kindCol!=null&&!ph.Contains(numCol))ph.Add(numCol);
    if(cli==null||ph.Count+ml.Count==0)continue;
    var hits=0;
    try{
     foreach(var d in await All(t,300_000))
     {
      var key=F(d,cli);if(onlyCustomers&&(key.Length==0||key=="0"))continue;
      if(!byCode.TryGetValue(key,out var mc))continue;
      if(kindCol!=null&&F(d,kindCol)=="3")continue;
      foreach(var k in ph){var v=F(d,k).Trim();if(v.Count(char.IsDigit)>=6&&!mc.Phones.Contains(v)){mc.Phones.Add(v);hits++;}}
      foreach(var k in ml)foreach(var e in F(d,k).Split([';',',',' '],StringSplitOptions.RemoveEmptyEntries).Select(x=>x.Trim().ToLowerInvariant()).Where(x=>x.Contains('@')))if(!mc.Emails.Contains(e)){mc.Emails.Add(e);hits++;}
     }
    }catch(Exception ex){warnings.Add($"Rubrica '{t}' non leggibile: {ex.Message}");continue;}
    if(hits>0)phoneTables.Add(t);
   }
   if(clients.Count>0&&clients.All(x=>x.Phones.Count==0))warnings.Add("Nessun numero di telefono trovato nei clienti di Mastro: controlla in quale tabella Mastro salva i telefoni (vedi .runtime/mastro/schema.json).");
  }

  // ── machines (cash registers) with verification and contract
  var machines=new List<Machine>();var machineCols=new List<string>();
  if(tMacc!=null)
  {
   var types=new Dictionary<string,string>();
   if(tTipi!=null)foreach(var t in await All(tTipi,10_000)){var k=F(t,"NUME","CODI","CODICE","TIPO");if(k.Length>0)types[k]=F(t,"DESCRI");}
   var rows=await All(tMacc,300_000);machineCols=rows.FirstOrDefault()?.Keys.ToList()??[];
   // the type column has different names across Mastro versions: any column mentioning TIPO
   var typeCol=machineCols.FirstOrDefault(k=>k.Contains("TIPO",StringComparison.OrdinalIgnoreCase));
   foreach(var d in rows)
   {
    var client=F(d,"CODI","CODICE","CLIENTE");if(client.Length==0)continue;
    var id=F(d,"NUME");if(id.Length==0||id=="0")id=client+"|"+F(d,"MATRICOLA")+"|"+F(d,"DESCRI");
    var typeKey=F(d,"TIPO","TIPO_MACC","MACC_TI");if(typeKey.Length==0&&typeCol!=null)typeKey=F(d,typeCol);
    machines.Add(new("m"+id,client,F(d,"DESCRI","MODELLO"),types.TryGetValue(typeKey,out var tn)?tn:typeKey,F(d,"MATRICOLA"),F(d,"FISCALE","MATR_FISC"),F(d,"LOGOTIPO"),
     F(d,"DATA_INS"),F(d,"PRIMA_INS"),F(d,"VERIF_DATA","DATA_VERI"),!d.TryGetValue("ATTIVA",out var at)||at.Length==0||True(at),True(F(d,"ABBONATO")),
     F(d,"INIZIO"),F(d,"FINE"),F(d,"FAT_IMP","IMPORTO"),F(d,"FILIALE","FILI_GEN"),string.Join("\n",new[]{F(d,"NOTE1"),F(d,"NOTE"),F(d,"NOTE2")}.Where(x=>x.Length>0)),
     services.TryGetValue(F(d,"SERVIZIO"),out var sv)?sv:"",Tech(F(d,"TECNICO")),F(d,"FINE_GAR"),F(d,"MESI") is "0"?"":F(d,"MESI"),F(d,"FINE_CONTRATTO")));
   }
  }
  else warnings.Add("Tabella 'lis_macc' (parco macchine) non trovata.");

  // ── register details, verification history and QR census (optional: a failure never stops the import)
  List<RtInfo>? rtList=null;
  if(tMacc!=null)try
  {
   (string Col,string Label)[] rtCols=[("TELEMATICO","Telematico"),("RT_ATTIVA","Attivazione RT"),("RT_DISATTIVA","Disattivazione RT"),("RT_DISMESSO","Dismissione RT"),("RT_FISCALI","Dati fiscali RT"),
    ("TELE_ATTIVA","Trasmissione attiva dal"),("TELE_QRCODE","QR code"),("FIRMWARE","Firmware"),("FIRMWARE_DATA","Data firmware"),("FIRMWARE_2","Firmware secondario"),("DGFE_DATA","Giornale elettronico (DGFE) dal"),("DGFE_GG","DGFE: giorni"),
    ("DATA_ULT_ZETA","Ultima chiusura Z"),("AZZ_NUME","Azzeramenti"),("AZZ_DATA","Ultimo azzeramento"),("ANNO_VER","Anno verificazione"),("IMPO_VERIFICA","Importo verificazione"),("LOTTERIA_CODICE","Lotteria scontrini"),("XML2_CODICE","Tracciato XML 7.0"),
    ("CENSIMENTO_DATA","Censimento"),("FINE_GAR","Fine garanzia"),("FINE_CONTRATTO","Fine contratto"),("MESI","Mesi abbonamento"),("COSTO","Costo"),("MESE_FATTURA","Mese fatturazione"),("UBICAZIONE","Ubicazione"),
    ("MODE_NUM","Modello (numero)"),("VENDITO","Venditore"),("BILANCIA","Bilancia collegata"),("PRODU_ZETA","Produttore: chiusure"),("PRODU_FIRMWARE","Produttore: firmware"),("PRODU_ABB","Produttore: abbonamento"),("PRODU_ABB_DATA","Produttore: abbonamento fino al"),("TELE_12MESI","Trasmissioni 12 mesi"),("RT_AUTO","RT automatico")];
   static bool Empty(string v)=>v.Length==0||v is "0" or "0.00" or "0,00" or "N" or "n";
   var byId=new Dictionary<string,RtInfo>();var bySerial=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
   foreach(var d in await All(tMacc,300_000))
   {
    var client=F(d,"CODI","CODICE","CLIENTE");if(client.Length==0)continue;
    var id=F(d,"NUME");if(id.Length==0||id=="0")id=client+"|"+F(d,"MATRICOLA")+"|"+F(d,"DESCRI");id="m"+id;
    var fields=new Dictionary<string,string>();
    foreach(var(col,label) in rtCols){var v=F(d,col).Trim();if(!Empty(v))fields[label]=v.Length>200?v[..200]:v;}
    byId[id]=new RtInfo(id,F(d,"MATRICOLA"),fields,[],0);
    foreach(var s in new[]{F(d,"MATRICOLA"),F(d,"FISCALE")})if(s.Length>=6)bySerial.TryAdd(s.Replace(" ",""),id);
   }
   string? Find(string num,string serial)=>num.Length>0&&byId.ContainsKey("m"+num)?"m"+num:bySerial.TryGetValue(serial.Replace(" ",""),out var x)?x:null;
   if(Tab("misuratori_dati_qrcode") is string tq)
    foreach(var d in await All(tq,100_000))
    {
     if(Find("",F(d,"MATRICOLA")) is not string id)continue;var f=byId[id].Fields;
     void Put(string l,string v){if(!Empty(v)&&!f.ContainsKey(l))f[l]=v;}
     Put("Stato (QR)",F(d,"STATO"));Put("Verificazione periodica (QR)",F(d,"VP"));Put("Tipo verificazione (QR)",F(d,"VP_TIPO"));Put("Firmware disponibile",F(d,"FW_DISP_VERS"));
     Put("Firmware modello",F(d,"FW_MODELLO_VERS"));Put("Laboratorio (P.IVA)",F(d,"PIVA_LAB"));Put("Censimento QR del",F(d,"DATA_UT"));
    }
   if(Tab("verifiche") is string tv)
   {
    var hist=new Dictionary<string,List<Verification>>();
    foreach(var d in await All(tv,400_000))
    {
     if(Find(F(d,"NUM_MAC"),F(d,"MATRICOLA")) is not string id)continue;
     // Mastro leaves ESITO and TIPO_VERIFI at 0: shown only when actually filled in; TIPO is Mastro's own code (1/2/3)
     var outcome=F(d,"ESITO");if(outcome is "0" or "")outcome="";else outcome=outcome switch{"1" or "P" or "p"=>"Positivo","N" or "n"=>"Negativo",_=>outcome};
     var kind=F(d,"TIPO_VERIFI");if(kind is "0" or "")kind=F(d,"TIPO") is var vt&&vt.Length>0&&vt!="0"?"Tipo "+vt:"";
     if(!hist.TryGetValue(id,out var l))hist[id]=l=[];
     l.Add(new(F(d,"DATA_IN","DATA_FI"),outcome,kind,F(d,"OPERAIO"),F(d,"SIGILLO"),F(d,"IMPORTO") is var a&&a!="0"&&a!="0.00"?a:"",F(d,"NUME_RAPP","NUME"),F(d,"DOVE_ESEGUITO") is var w&&w!="0"?w:"",F(d,"NOTE")));
    }
    foreach(var(id,l) in hist){var sorted=l.OrderByDescending(v=>v.Date,StringComparer.Ordinal).ToList();byId[id]=byId[id] with{Verifications=sorted.Take(12).ToList(),VerificationCount=sorted.Count};}
   }
   rtList=byId.Values.Where(r=>r.Fields.Count>0||r.VerificationCount>0).ToList();
  }
  catch(Exception ex) when(ex is MySqlException or InvalidCastException or FormatException){rtList=null;warnings.Add("Dettagli registratori e verifiche non leggibili: "+ex.Message);}

  // ── shops / delivery addresses of the customers
  List<Site>? sites=null;
  if(Tab("destina") is string tdst)try
  {
   sites=[];var siteClients=clients.Select(x=>x.Code).ToHashSet(StringComparer.OrdinalIgnoreCase);
   foreach(var d in await All(tdst,50_000))
   {
    var code=F(d,"CODI");if(code.Length==0||!siteClients.Contains(code))continue;
    sites.Add(new(code,F(d,"DESCRI"),F(d,"VIA"),F(d,"CAP"),F(d,"CITTA"),F(d,"PROV"),string.Join(" · ",new[]{F(d,"TELE_1"),F(d,"TELE_2")}.Where(x=>x.Length>0)),F(d,"EMAIL"),F(d,"ORARIO"),F(d,"NOTE"),True(F(d,"CHIUSO"))));
   }
  }
  catch(MySqlException ex){sites=null;warnings.Add("Sedi dei clienti non leggibili: "+ex.Message);}

  // ── intervention history: the table with NUME_INTE + DATA_INTE (the y_* tables are Mastro's own change log)
  var interventions=new List<Intervention>();string interTable="";
  // column names only (never data): saved in .runtime/mastro/schema.json to adapt the reader to this Mastro version
  async Task<List<string>> Cols(string t)
  {
   await using var q=new MySqlCommand("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=@t",c);q.Parameters.AddWithValue("@t",t);
   var tc=new List<string>();await using(var r=await q.ExecuteReaderAsync(ct))while(await r.ReadAsync(ct))tc.Add(r.GetString(0).ToUpperInvariant());return tc;
  }
  string[] interWords=["interv","inte","assist","rapport","chiamat","ticket","lavor"];
  var candidates=tables.Where(x=>!x.StartsWith("y_",StringComparison.OrdinalIgnoreCase)&&interWords.Any(w=>x.Contains(w,StringComparison.OrdinalIgnoreCase)))
   .OrderBy(x=>Array.FindIndex(interWords,w=>x.Contains(w,StringComparison.OrdinalIgnoreCase))).ToList();
  var schema=new Dictionary<string,List<string>>();
  string interNum="NUME_INTE",interDate="DATA_INTE",interClient="CODICE";
  foreach(var t in candidates.Take(25))
  {
   var tc=await Cols(t);schema[t]=tc;
   if(interTable.Length>0)continue;
   var num=tc.FirstOrDefault(k=>k=="NUME_INTE")??tc.FirstOrDefault(k=>k.StartsWith("NUME_")&&k.Contains("INT"))??tc.FirstOrDefault(k=>k is "NUME" or "NUMERO");
   var date=tc.FirstOrDefault(k=>k=="DATA_INTE")??tc.FirstOrDefault(k=>k.StartsWith("DATA")&&k.Contains("INT"))??tc.FirstOrDefault(k=>k is "DATA" or "DATA_DOC" or "DATA_RICH");
   var cli=tc.FirstOrDefault(k=>k is "CODICE" or "CODI" or "COD_CLI" or "CODI_CLIE" or "CLIENTE_COD");
   if(num!=null&&date!=null&&cli!=null){interTable=t;interNum=num;interDate=date;interClient=cli;}
  }
  schema[tClienti]=cols.Select(x=>x.ToUpperInvariant()).ToList();schema["__rubriche"]=phoneTables;
  if(tMacc!=null)schema[tMacc]=machineCols.Select(x=>x.ToUpperInvariant()).ToList();
  if(tTipi!=null)schema[tTipi]=await Cols(tTipi);
  try{File.WriteAllText(Path.Combine(Paths.Runtime("mastro"),"schema.json"),System.Text.Json.JsonSerializer.Serialize(new{read=DateTimeOffset.Now,tables,columns=schema,interTable,interNum,interDate,interClient},new System.Text.Json.JsonSerializerOptions{WriteIndented=true}));}catch{}
  if(interTable.Length>0)
  {
   foreach(var d in await All(interTable,300_000))
   {
    var n=F(d,interNum,"NUME_INTE");var client=F(d,interClient,"CODICE","CODI");if(n.Length==0||n=="0"||client.Length==0)continue;
    var work=F(d,"LAVORO","DESCRI","NOTE");
    interventions.Add(new("mi"+n,client,F(d,"CLIENTE","RAGI_SOCI","DESCRI_CLI"),F(d,interDate,"DATA_INTE"),F(d,"NUME_MACC"),work.Split('\n')[0] is var first&&first.Length>0?(first.Length>90?first[..90]+"…":first):"Intervento n. "+n,
     work,"Chiuso",Tech(F(d,"TECNICO","OPERAIO","OPERATORE")),"Normale",F(d,"TOT_DOC"),"mastro","","","",""));
   }
  }
  else warnings.Add("Archivio interventi di Mastro non trovato: verranno usati solo quelli creati nell'app.");

  // ── warehouse: catalogue (arti), stock per warehouse (art_giace), categories (reparti), suppliers (fornito)
  var articles=new List<Article>();var stock=new List<MastroStock>();
  static decimal D(string s)=>decimal.TryParse(s,NumberStyles.Any,CultureInfo.InvariantCulture,out var v)?v:0;
  var tArti=Tab("arti");
  if(tArti!=null)
  {
   var cats=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);var sups=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
   if(Tab("reparti") is string tr)foreach(var r in await All(tr,10_000)){var k=F(r,"NUME","CODI","REPA","CODICE");if(k.Length>0)cats[k]=F(r,"DESCRI");}
   if(Tab("fornito") is string tf)foreach(var r in await All(tf,50_000)){var k=F(r,"CODI","CODICE");if(k.Length>0)sups[k]=F(r,"DESCRI");}
   var seenBarcodes=new HashSet<string>();
   foreach(var d in await All(tArti,300_000))
   {
    var code=F(d,"CODI","CODICE");var name=(F(d,"DESCRI")+" "+F(d,"DESCRI2")).Trim();if(code.Length==0||name.Length==0)continue;
    var barcode=F(d,"BARCODE","EAN","CORTO");if(barcode.Length>0&&!seenBarcodes.Add(barcode))barcode=""; // a barcode identifies one article only
    var cost=D(F(d,"COSM"));if(cost<=0)cost=D(F(d,"COST"));
    var repa=F(d,"REPA");var forn=F(d,"FORNITO");
    articles.Add(new(code,name.Length>200?name[..200]:name,barcode,cats.GetValueOrDefault(repa,repa),F(d,"MISU").ToUpperInvariant(),Math.Max(0,cost),Math.Max(0,D(F(d,"VEND"))),
     Math.Clamp(D(F(d,"IVA")),0,100),sups.GetValueOrDefault(forn,forn),F(d,"CFOR"),false,true,"","mastro",""));
   }
   articles=articles.DistinctBy(a=>a.Code.ToUpperInvariant()).ToList();
   if(Tab("art_giace") is string tg)
    foreach(var d in await All(tg,500_000))
    {var q=D(F(d,"ESIS"));var code=F(d,"CODI","ARTI","ART_CODI","CODICE");if(q!=0&&code.Length>0)stock.Add(new(code,F(d,"MAGA").Length>0?F(d,"MAGA"):"1",q));}
   else warnings.Add("Tabella delle giacenze (art_giace) non trovata: verrà importato solo il catalogo articoli.");
  }
  else warnings.Add("Tabella articoli (arti) non trovata: magazzino non importato.");

  // ── accounting: invoices (lis_fat), instalments (mov_sca), ledger balances (mov_con). Read only; Mastro stays the system of record.
  List<Invoice>? invoices=null;List<Due>? dues=null;List<Balance>? balances=null;
  var cliNames=clients.GroupBy(x=>x.Code,StringComparer.OrdinalIgnoreCase).ToDictionary(g=>g.Key,g=>g.First().Name,StringComparer.OrdinalIgnoreCase);
  var supNames=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
  if(Tab("fornito") is string tf2)foreach(var r in await All(tf2,50_000)){var k=F(r,"CODI","CODICE");if(k.Length>0)supNames[k]=F(r,"DESCRI");}
  var pays=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
  if(Tab("pagamenti") is string tp)foreach(var r in await All(tp,5_000)){var k=F(r,"CODI","CODICE","NUME");if(k.Length>0)pays[k]=F(r,"DESCRI");}
  static string Num(string s)=>decimal.TryParse(s,NumberStyles.Any,CultureInfo.InvariantCulture,out var v)&&v==Math.Floor(v)?v.ToString("0",CultureInfo.InvariantCulture):s;
  if(Tab("lis_fat") is string tl)
  {
   invoices=[];
   foreach(var d in await All(tl,400_000))
   {
    var num=Num(F(d,"NUME","DOC_NUME","NUME_DOC","NUMERO","N_FAT"));var date=F(d,"DATAI","DATA_DOC","DOC_DATA","DATA");if(num.Length==0||date.Length==0)continue;
    var barra=F(d,"BARRA");var cli=F(d,"CODI","CLIENTE","CODICE","NUMCLIFOR","CLI");
    var total=D(F(d,"TOT_DOC","TOTALE"));var vat=D(F(d,"TOTA_IVA","IVA"));var taxable=D(F(d,"TOTA_IMP","IMPONIBILE","TOT_IMP"));if(taxable==0&&total!=0)taxable=total-vat;
    var credit=F(d,"FATT_NOTA").StartsWith("N",StringComparison.OrdinalIgnoreCase);
    var pay=F(d,"PAGA","COD_PAG","PAGAMENTO","PAGAM");
    var number=barra.Length>0?$"{num}/{barra}":num;
    invoices.Add(new($"{date[..Math.Min(4,date.Length)]}-{number}-{(credit?"N":"F")}",number,date,cli,cliNames.GetValueOrDefault(cli,F(d,"DESCRI","RAGSOC")),credit?"Nota di credito":"Fattura",taxable,vat,total,pays.GetValueOrDefault(pay,pay)));
   }
  }
  else warnings.Add("Tabella fatture (lis_fat) non trovata: fatture non importate.");
  if(Tab("mov_sca") is string ts)
  {
   dues=[];int n=0;
   foreach(var d in await All(ts,400_000))
   {
    var party=F(d,"CLIFOR").StartsWith("F",StringComparison.OrdinalIgnoreCase)?"F":"C";var code=F(d,"NUMCLIFOR","CODI");var due=F(d,"SCADENZA","DATA_SCA");if(code.Length==0||due.Length==0)continue;
    var amount=D(F(d,"IMPORTO"));if(amount==0)amount=D(F(d,"TOTALE"));
    var pagatoRaw=F(d,"PAGATO");var paid=True(pagatoRaw)?amount:D(pagatoRaw);
    var pay=F(d,"PAGA","COD_PAG","PAGAMENTO");
    var id=F(d,"ID","NUME","PROGR");if(id.Length==0)id=(++n).ToString(CultureInfo.InvariantCulture);
    dues.Add(new($"{party}{code}-{Num(F(d,"NUMDOC"))}-{due}-{id}",party,code,party=="F"?supNames.GetValueOrDefault(code,code):cliNames.GetValueOrDefault(code,code),
     Num(F(d,"NUMDOC")),F(d,"DATADOC"),due,Math.Abs(amount),Math.Abs(paid),pays.GetValueOrDefault(pay,pay)));
   }
  }
  else warnings.Add("Tabella scadenze (mov_sca) non trovata: scadenzario non importato.");
  if(Tab("mov_con") is string tcon)
  {
   try
   {
    balances=[];
    await using var q=new MySqlCommand($"SELECT CLI_O_FOR,NUMCLIFOR,SUM(DARE),SUM(AVERE) FROM `{tcon.Replace("`","")}` WHERE NUMCLIFOR IS NOT NULL AND NUMCLIFOR<>'' GROUP BY CLI_O_FOR,NUMCLIFOR",c);
    await using var r=await q.ExecuteReaderAsync(ct);
    while(await r.ReadAsync(ct))
    {
     var code=S(r.GetValue(1));if(code.Length==0)continue;
     balances.Add(new(S(r.GetValue(0)).StartsWith("F",StringComparison.OrdinalIgnoreCase)?"F":"C",Num(code),D(S(r.GetValue(2))),D(S(r.GetValue(3)))));
    }
   }
   catch(MySqlException){balances=null;warnings.Add("Saldi contabili (mov_con) non leggibili: verranno mostrate solo fatture e scadenze.");}
  }

  // ── bank receipts (RiBa / effetti) of the customers: open and unpaid ones of the last 3 years
  List<Riba>? riba=null;
  if(Tab("effetti") is string tef)try
  {
   riba=[];var since=DateTime.Today.AddYears(-3).ToString("yyyy-MM-dd");
   foreach(var d in await All(tef,100_000))
   {
    // CLIFOR: "1"/"C" = customer (Mastro 20 writes 1), "F"/"2" = supplier
    if(F(d,"CLIFOR") is "F" or "f" or "2")continue;
    var due=F(d,"SCADENZA");if(string.CompareOrdinal(due,since)<0)continue;
    var code=Num(F(d,"NUMCLIFOR"));if(code.Length==0)continue;
    riba.Add(new("rb"+F(d,"NUME"),code,due,Math.Abs(D(F(d,"IMPORTO"))),True(F(d,"PAGATA")),True(F(d,"INSOLUTO")),F(d,"BANCA_CLI","BANCA"),Num(F(d,"NUMDOC")),F(d,"DATADOC"),F(d,"DESCRI")));
   }
  }
  catch(MySqlException ex){riba=null;warnings.Add("Effetti (RiBa) non leggibili: "+ex.Message);}

  var batch=new MastroBatch(clients,machines,interventions,DateTimeOffset.Now.ToString("O"),$"MastroSQL {cfg.Host}/{cfg.Database}",articles,stock,false,invoices,dues,balances,rtList,sites,riba);
  var preview=new Preview(clients.Count,machines.Count,interventions.Count,clients.Count(x=>x.Phones.Count>0),clients.Count(x=>x.Emails.Count>0),cols,machineCols,interTable,clients.Take(6).ToList(),warnings,
   articles.Count,stock.Count,stock.Select(s=>s.Warehouse).Distinct().Count(),invoices?.Count??0,dues?.Count??0,balances?.Count??0);
  return(batch,preview);
 }
}
