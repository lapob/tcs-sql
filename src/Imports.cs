using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;

namespace TopCash.Licenze;
public static class Imports
{
 public static (SourceBatch Batch,string Hash) Read(string file)
 {
  var bytes=File.ReadAllBytes(file);if(bytes.Length>20_000_000)throw new InvalidDataException("File troppo grande (massimo 20 MB).");
  var hash=Convert.ToHexString(SHA256.HashData(bytes));
  if(Path.GetExtension(file).Equals(".json",StringComparison.OrdinalIgnoreCase))
  {
   var b=JsonSerializer.Deserialize<SourceBatch>(bytes,new JsonSerializerOptions{PropertyNameCaseInsensitive=true})??throw new InvalidDataException("JSON non valido.");
   if(b.Rows==null||b.Headers==null)throw new InvalidDataException("Il JSON non è un'esportazione licenze.");return(b,hash);
  }
  var rows=Path.GetExtension(file).Equals(".xlsx",StringComparison.OrdinalIgnoreCase)?Xlsx(file):Csv(Decode(bytes));
  var index=rows.FindIndex(r=>r.Any(c=>RecordMapper.Normalize(c)=="MATRICOLA")&&r.Any(c=>RecordMapper.Normalize(c)=="CODICECLIENTE"));
  if(index<0)throw new InvalidDataException("Intestazioni Zucchetti non riconosciute: Codice Cliente e Matricola mancanti.");
  // File imports cannot prove that every page/product has been exported.
  return(new("file-"+Path.GetFileNameWithoutExtension(file),DateTimeOffset.Now.ToString("O"),false,rows[index],rows.Skip(index+1).ToList()),hash);
 }
 static string Decode(byte[] b){try{return new UTF8Encoding(false,true).GetString(b).TrimStart('\uFEFF');}catch(DecoderFallbackException){Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);return Encoding.GetEncoding(1252).GetString(b);}}
 public static List<List<string>> Csv(string text)
 {
  var line=text.Split('\n').FirstOrDefault(x=>x.Contains(';')||x.Contains(',')||x.Contains('\t'))??"";
  var separator=new[]{';',',','\t'}.MaxBy(c=>line.Count(x=>x==c));var rows=new List<List<string>>();var row=new List<string>();var cell=new StringBuilder();bool quoted=false;
  for(int i=0;i<text.Length;i++){char c=text[i];if(c=='"'){if(quoted&&i+1<text.Length&&text[i+1]=='"'){cell.Append('"');i++;}else quoted=!quoted;}else if(c==separator&&!quoted){row.Add(cell.ToString());cell.Clear();}else if((c=='\r'||c=='\n')&&!quoted){if(c=='\r'&&i+1<text.Length&&text[i+1]=='\n')i++;row.Add(cell.ToString());cell.Clear();if(row.Any(v=>v.Length>0))rows.Add(row);row=[];}else cell.Append(c);}
  if(quoted)throw new InvalidDataException("CSV incompleto: virgolette non chiuse.");if(cell.Length>0||row.Count>0){row.Add(cell.ToString());rows.Add(row);}return rows;
 }
 static List<List<string>> Xlsx(string path)
 {
  using var z=ZipFile.OpenRead(path);XNamespace n="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  var shared=new List<string>();var ss=z.GetEntry("xl/sharedStrings.xml");if(ss!=null){using var s=ss.Open();shared=XDocument.Load(s).Descendants(n+"si").Select(e=>string.Concat(e.Descendants(n+"t").Select(t=>t.Value))).ToList();}
  var entries=z.Entries.Where(e=>e.FullName.StartsWith("xl/worksheets/sheet")&&e.FullName.EndsWith(".xml")).OrderBy(e=>e.FullName);var result=new List<List<string>>();
  foreach(var entry in entries){if(entry.Length>40_000_000)throw new InvalidDataException("Foglio Excel troppo grande.");using var s=entry.Open();var doc=XDocument.Load(s);foreach(var row in doc.Descendants(n+"sheetData").Elements(n+"row")){var cells=new List<string>();foreach(var c in row.Elements(n+"c")){string reference=(string?)c.Attribute("r")??"A1";int col=0;foreach(char ch in reference.TakeWhile(char.IsLetter))col=col*26+char.ToUpperInvariant(ch)-'A'+1;while(cells.Count<col)cells.Add("");string value=c.Element(n+"v")?.Value??string.Concat(c.Descendants(n+"t").Select(x=>x.Value));if((string?)c.Attribute("t")=="s"&&int.TryParse(value,out var ix)&&ix<shared.Count)value=shared[ix];if(col>0)cells[col-1]=value;}if(cells.Any(v=>v.Length>0))result.Add(cells);}
   // Each worksheet is a separate table; take the first valid license table.
   if(result.Any(r=>r.Any(c=>RecordMapper.Normalize(c)=="MATRICOLA")))break;result.Clear();}
  return result;
 }
 public static string CsvExport(IEnumerable<LicenseRecord> records)
 {
  string Q(string s){if(s.Length>0&&"=+-@\t\r".Contains(s[0]))s="'"+s;return "\""+s.Replace("\"","\"\"")+"\"";}
  var rows=new List<string>{"Codice Cliente;Cliente;Partita IVA;Matricola;Nome prodotto;Rel. Prog.;Rilasciato il;Aggiornata il;Scadenza;Blocco;Richiesta rinnovo il;Ambito;Acquisito il"};
  rows.AddRange(records.Select(r=>string.Join(';',new[]{r.ClientCode,r.Client,r.Vat,r.Serial,r.Product,r.Version,r.Released,r.Updated,r.Expiry,r.Blocked,r.RenewalRequested,r.Scope,r.CapturedAt}.Select(Q))));return string.Join(Environment.NewLine,rows);
 }
 public static string ClientsCsv(List<ClientRecord> clients,List<LicenseRecord> licenses,Dictionary<string,ClientMeta> meta,Dictionary<string,string> notes)
 {
  string Q(string s){if(s.Length>0&&"=+-@\t\r".Contains(s[0]))s="'"+s;return "\""+s.Replace("\"","\"\"")+"\"";}
  var byCode=licenses.GroupBy(l=>l.ClientCode).ToDictionary(g=>g.Key,g=>g.ToList());
  var codes=clients.Select(c=>c.Code).Union(byCode.Keys).OrderBy(c=>c).ToList();var reg=clients.ToDictionary(c=>c.Code);
  var rows=new List<string>{"Codice Cliente;Ragione sociale;Partita IVA;Codice fiscale;Località;Indirizzo;Telefono;Email;Prodotti;Licenze correnti;Matricole;Prossimo contatto;Stato attività;Note"};
  foreach(var code in codes)
  {
   reg.TryGetValue(code,out var c);byCode.TryGetValue(code,out var ls);ls??=[];meta.TryGetValue(code,out var m);notes.TryGetValue(code,out var n);
   rows.Add(string.Join(';',new[]{code,c?.Name??ls.FirstOrDefault()?.Client??"",ls.FirstOrDefault(l=>l.Vat.Length>0)?.Vat??"",c?.TaxCode??"",c?.City??"",c?.Address??"",c?.Phone??"",c?.Email??"",
    string.Join(", ",(c?.Families??[]).Union(ls.Select(l=>l.Product)).Distinct()),ls.Count(l=>!l.Scope.EndsWith("-scadute")).ToString(),string.Join(" ",ls.Select(l=>l.Serial).Distinct()),m?.NextContact??"",m?.FollowupState??"",n??""}.Select(Q)));
  }
  return string.Join(Environment.NewLine,rows);
 }
 public static string Calendar(IEnumerable<LicenseRecord> records)
 {
  string Esc(string v)=>v.Replace("\\","\\\\").Replace("\n","\\n").Replace("\r","").Replace(",","\\,").Replace(";","\\;");
  var lines=new List<string>{"BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//TopCash//License Studio//IT","CALSCALE:GREGORIAN"};
  foreach(var r in records){var d=LicenseRecord.ParseDate(r.Expiry);if(d==null||d.Value.Date<DateTime.Today||d.Value.Date>DateTime.Today.AddDays(90))continue;
   lines.AddRange(["BEGIN:VEVENT","UID:"+r.Id+"@topcash.local","DTSTAMP:"+DateTime.UtcNow.ToString("yyyyMMdd'T'HHmmss'Z'"),"DTSTART;VALUE=DATE:"+d.Value.ToString("yyyyMMdd"),"SUMMARY:"+Esc("Scadenza licenza · "+r.Client),"DESCRIPTION:"+Esc(r.Product+" · "+r.Serial+" · codice cliente "+r.ClientCode),"END:VEVENT"]);}
  lines.Add("END:VCALENDAR");var result=new StringBuilder();foreach(var line in lines){int size=0;foreach(var rune in line.EnumerateRunes()){var text=rune.ToString();int bytes=Encoding.UTF8.GetByteCount(text);if(size+bytes>74){result.Append("\r\n ");size=1;}result.Append(text);size+=bytes;}result.Append("\r\n");}return result.ToString();
 }
}
