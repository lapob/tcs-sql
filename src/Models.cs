using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace TopCash.Licenze;

public sealed record LicenseRecord(string Id, string ClientCode, string Client, string Vat, string Serial,
 string Installer, string Product, string Version, string Released, string Updated, string Expiry,
 string Blocked, string RenewalRequested, string Scope, string CapturedAt, string Source, string RawJson)
{
 public string Status => Blocked.Equals("SI", StringComparison.OrdinalIgnoreCase) ? "Bloccata" :
  ParseDate(Expiry) is not DateTime date ? "Da verificare" : date.Date < DateTime.Today ? "Scaduta" :
  date.Date <= DateTime.Today.AddDays(30) ? "In scadenza" : "In corso";
 public static DateTime? ParseDate(string value)
 {
  if (string.IsNullOrWhiteSpace(value)) return null;
  string[] formats=["dd-MM-yyyy","dd/MM/yyyy","yyyy-MM-dd","dd-MM-yyyy HH:mm:ss","dd/MM/yyyy HH:mm:ss","yyyy-MM-ddTHH:mm:ss"];
  return DateTime.TryParseExact(value.Trim(),formats,CultureInfo.InvariantCulture,DateTimeStyles.None,out var d) ? d : null;
 }
}
public sealed record SourceBatch(string Scope, string CapturedAt, bool Complete, List<string> Headers, List<List<string>> Rows);
public sealed record SourceState(string Scope, string CapturedAt, bool Complete, int RowCount, string FileName);
public sealed record ImportResult(int Rows, int Added, int Changed, int Unchanged, string Scope);
public sealed record SyncEvent(string Time, string Kind, string Message);
public sealed record ClientMeta(bool Favorite=false,string NextContact="",string FollowupState="");
public sealed record ClientRecord(string Code,string Name,string City,string Address,string Phone,string Email,string TaxCode,List<string> Families,string CapturedAt);
public sealed record OrderRecord(string Id,string Family,string Date,string Number,string Serial,string Client,string Status,string CapturedAt);

public static class PortalMapper
{
 static int Col(List<string> h,params string[] prefixes)=>h.FindIndex(x=>prefixes.Any(p=>x.StartsWith(p,StringComparison.Ordinal)));
 static string At(List<string> row,int i)=>i>=0&&i<row.Count?row[i].Trim():"";
 public static List<ClientRecord> Clients(string family,List<string> headers,List<List<string>> rows,string capturedAt)
 {
  var h=headers.Select(RecordMapper.Normalize).ToList();
  int code=Col(h,"CODICECLIENTE"),name=Col(h,"RAGIONESOCIALE"),city=Col(h,"LOCALIT"),addr=Col(h,"INDIRIZZO"),phone=Col(h,"RECAPITO","TELEFONO"),mail=Col(h,"EMAIL"),tax=Col(h,"CODICEFISCALE","PARTITAIVA");
  if(code<0||name<0)throw new InvalidDataException("Elenco clienti: colonne Codice cliente e Ragione sociale non trovate.");
  return rows.Where(r=>At(r,code).Length>0).Select(r=>new ClientRecord(At(r,code),At(r,name),At(r,city),At(r,addr),At(r,phone),At(r,mail).ToLowerInvariant(),At(r,tax),[family],capturedAt)).ToList();
 }
 public static List<OrderRecord> Orders(string family,List<string> headers,List<List<string>> rows,string capturedAt)
 {
  var h=headers.Select(RecordMapper.Normalize).ToList();
  int date=Col(h,"DATAORDINE"),num=h.FindIndex(x=>x=="CODICE"),serial=Col(h,"MATRICOLA"),name=Col(h,"RAGIONESOCIALE"),status=Col(h,"STATO");
  if(num<0||name<0)throw new InvalidDataException("Elenco ordini: colonne Codice e Ragione sociale non trovate.");
  return rows.Where(r=>At(r,num).Length>0).Select(r=>new OrderRecord(family+"|"+At(r,num),family,At(r,date),At(r,num),At(r,serial),At(r,name),At(r,status),capturedAt)).ToList();
 }
}

public static class RecordMapper
{
 public static string Normalize(string s)=>new(s.ToUpperInvariant().Where(char.IsLetterOrDigit).ToArray());
 public static List<LicenseRecord> Map(SourceBatch batch,string source)
 {
  if(string.IsNullOrWhiteSpace(batch.Scope)||batch.Headers.Count<4)throw new InvalidDataException("Ambito o intestazioni mancanti.");
  var h=batch.Headers.Select(Normalize).ToList();
  int Find(params string[] names)=>h.FindIndex(x=>names.Contains(x));
  var ci=Find("CODICECLIENTE","CODCLIENTE");var ni=Find("CLIENTE","RAGIONESOCIALE");var si=Find("MATRICOLA");
  if(ci<0||ni<0||si<0)throw new InvalidDataException("Servono le colonne Codice Cliente, Cliente e Matricola.");
  var result=new List<LicenseRecord>();
  foreach(var row in batch.Rows)
  {
   string At(int i)=>i>=0&&i<row.Count?row[i].Trim():"";
   string Get(params string[] n)=>At(Find(n));
   if(row.All(string.IsNullOrWhiteSpace))continue;
   var code=At(ci);var name=At(ni);var serial=At(si);
   if(code.Length==0||name.Length==0||serial.Length==0)throw new InvalidDataException($"Riga {result.Count+2}: identificativo cliente, nome o matricola mancante. Nessun dato importato.");
   var product=Get("NOMEPRODOTTO","PRODOTTO");
   if(product.Length==0)throw new InvalidDataException($"Prodotto mancante per la matricola {serial}.");
   var id=Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(batch.Scope+"|"+code+"|"+serial+"|"+product)));
   var expiry=Get("SCADENZA","DATASCADENZA");
   if(expiry.Length>0&&LicenseRecord.ParseDate(expiry)==null)throw new InvalidDataException($"Data scadenza non riconosciuta nella matricola {serial}.");
   result.Add(new(id,code,name,Get("PARTITAIVA","PIVA"),serial,Get("INSTALLATORE"),product,Get("RELPROG","VERSIONE"),
    Get("RILASCIATOIL","RILASCIATAIL"),Get("AGGIORNATAIL"),expiry,Get("BLOCCO"),Get("RICHIESTARINNOVOIL"),batch.Scope,
    batch.CapturedAt,source,JsonSerializer.Serialize(row)));
  }
  if(result.Select(x=>x.Id).Distinct().Count()!=result.Count)throw new InvalidDataException("Matricole duplicate nello stesso ambito. Verificare il file prima di importarlo.");
  return result;
 }
}
