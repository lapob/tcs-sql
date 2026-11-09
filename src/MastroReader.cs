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
  bool IsPhone(string k)=>System.Text.RegularExpressions.Regex.IsMatch(k,@"^(TEL|TELE|TELEF|TELEFONO|CELL|CELLU|CELLULARE|FAX|NUM_TEL|PHONE|RECAPITO)",System.Text.RegularExpressions.RegexOptions.IgnoreCase);
  bool IsMail(string k)=>System.Text.RegularExpressions.Regex.IsMatch(k,@"(E_?MAIL|^MAIL)",System.Text.RegularExpressions.RegexOptions.IgnoreCase)&&!k.Contains("PEC",StringComparison.OrdinalIgnoreCase);
  var known=new HashSet<string>(["CODI","DESCRI","DESCRI2","PIVA","COD_FIS","VIA","CAP","CITTA","PROV","ZONA","PEC","NOTE","NOTE1"],StringComparer.OrdinalIgnoreCase);
  var clients=new List<MastroClient>();
  foreach(var d in raw)
  {
   var code=F(d,"CODI","CODICE");if(code.Length==0||code=="0")continue;
   var name=(F(d,"DESCRI","RAGI_SOCI","RAGIONE")+" "+F(d,"DESCRI2")).Trim();if(name.Length==0)continue;
   var phones=d.Where(kv=>IsPhone(kv.Key)&&kv.Value.Count(char.IsDigit)>=6).Select(kv=>kv.Value).Distinct().ToList();
   var mails=d.Where(kv=>IsMail(kv.Key)&&kv.Value.Contains('@')).SelectMany(kv=>kv.Value.Split([';',',',' '],StringSplitOptions.RemoveEmptyEntries)).Where(x=>x.Contains('@')).Select(x=>x.Trim().ToLowerInvariant()).Distinct().ToList();
   var pec=d.Where(kv=>kv.Key.Contains("PEC",StringComparison.OrdinalIgnoreCase)&&kv.Value.Contains('@')).Select(kv=>kv.Value.Trim().ToLowerInvariant()).FirstOrDefault()??"";
   var notes=string.Join("\n",d.Where(kv=>kv.Key.StartsWith("NOTE",StringComparison.OrdinalIgnoreCase)&&kv.Value.Length>0).Select(kv=>kv.Value));
   // every other filled field is kept, so no information is lost
   var extra=d.Where(kv=>!known.Contains(kv.Key)&&!IsPhone(kv.Key)&&!IsMail(kv.Key)&&!kv.Key.Contains("PEC",StringComparison.OrdinalIgnoreCase)&&!kv.Key.StartsWith("NOTE",StringComparison.OrdinalIgnoreCase)
    &&kv.Value.Length is >0 and <400&&kv.Value!="0"&&kv.Value!="0.00").Take(60).ToDictionary(kv=>kv.Key,kv=>kv.Value);
   clients.Add(new(code,name,F(d,"PIVA","P_IVA","PARTITA_IVA"),F(d,"COD_FIS","CODFIS","CODICE_FISCALE"),F(d,"VIA","INDIRIZZO"),F(d,"CAP"),F(d,"CITTA","LOCALITA"),F(d,"PROV","PROVINCIA"),F(d,"ZONA"),phones,mails,pec,notes,extra));
  }
  clients=clients.DistinctBy(x=>x.Code).ToList();

  // ── machines (cash registers) with verification and contract
  var machines=new List<Machine>();var machineCols=new List<string>();
  if(tMacc!=null)
  {
   var types=new Dictionary<string,string>();
   if(tTipi!=null)foreach(var t in await All(tTipi,10_000)){var k=F(t,"NUME","CODI","CODICE","TIPO");if(k.Length>0)types[k]=F(t,"DESCRI");}
   var rows=await All(tMacc,300_000);machineCols=rows.FirstOrDefault()?.Keys.ToList()??[];
   foreach(var d in rows)
   {
    var client=F(d,"CODI","CODICE","CLIENTE");if(client.Length==0)continue;
    var id=F(d,"NUME");if(id.Length==0||id=="0")id=client+"|"+F(d,"MATRICOLA")+"|"+F(d,"DESCRI");
    var typeKey=F(d,"TIPO","TIPO_MACC","MACC_TI");
    machines.Add(new("m"+id,client,F(d,"DESCRI","MODELLO"),types.TryGetValue(typeKey,out var tn)?tn:typeKey,F(d,"MATRICOLA"),F(d,"FISCALE","MATR_FISC"),F(d,"LOGOTIPO"),
     F(d,"DATA_INS"),F(d,"PRIMA_INS"),F(d,"VERIF_DATA","DATA_VERI"),!d.TryGetValue("ATTIVA",out var at)||at.Length==0||True(at),True(F(d,"ABBONATO")),
     F(d,"INIZIO"),F(d,"FINE"),F(d,"FAT_IMP","IMPORTO"),F(d,"FILIALE","FILI_GEN"),string.Join("\n",new[]{F(d,"NOTE1"),F(d,"NOTE"),F(d,"NOTE2")}.Where(x=>x.Length>0))));
   }
  }
  else warnings.Add("Tabella 'lis_macc' (parco macchine) non trovata.");

  // ── intervention history: the table with NUME_INTE + DATA_INTE (the y_* tables are Mastro's own change log)
  var interventions=new List<Intervention>();string interTable="";
  foreach(var t in tables.Where(x=>x.Contains("interv",StringComparison.OrdinalIgnoreCase)&&!x.StartsWith("y_",StringComparison.OrdinalIgnoreCase)))
  {
   await using var q=new MySqlCommand("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=@t",c);q.Parameters.AddWithValue("@t",t);
   var tc=new List<string>();await using(var r=await q.ExecuteReaderAsync(ct))while(await r.ReadAsync(ct))tc.Add(r.GetString(0).ToUpperInvariant());
   if(tc.Contains("NUME_INTE")&&tc.Contains("DATA_INTE")){interTable=t;break;}
  }
  if(interTable.Length>0)
  {
   foreach(var d in await All(interTable,300_000))
   {
    var n=F(d,"NUME_INTE");var client=F(d,"CODICE","CODI");if(n.Length==0||client.Length==0)continue;
    var work=F(d,"LAVORO","DESCRI","NOTE");
    interventions.Add(new("mi"+n,client,F(d,"CLIENTE"),F(d,"DATA_INTE"),F(d,"NUME_MACC"),work.Split('\n')[0] is var first&&first.Length>0?(first.Length>90?first[..90]+"…":first):"Intervento n. "+n,
     work,"Chiuso",F(d,"TECNICO","OPERAIO","OPERATORE"),"Normale",F(d,"TOT_DOC"),"mastro","","","",""));
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

  var batch=new MastroBatch(clients,machines,interventions,DateTimeOffset.Now.ToString("O"),$"MastroSQL {cfg.Host}/{cfg.Database}",articles,stock,false,invoices,dues,balances);
  var preview=new Preview(clients.Count,machines.Count,interventions.Count,clients.Count(x=>x.Phones.Count>0),clients.Count(x=>x.Emails.Count>0),cols,machineCols,interTable,clients.Take(6).ToList(),warnings,
   articles.Count,stock.Count,stock.Select(s=>s.Warehouse).Distinct().Count(),invoices?.Count??0,dues?.Count??0,balances?.Count??0);
  return(batch,preview);
 }
}
