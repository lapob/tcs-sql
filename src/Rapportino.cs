using Microsoft.Data.Sqlite;
using PdfSharp.Drawing;
using PdfSharp.Fonts;
using PdfSharp.Pdf;
using System.Globalization;
using System.Text.Json;

namespace TopCash.Licenze;

// ── Intervention report ("rapportino") signed by the customer ─────────────────────────────────────────
// The signature is stored apart (PNG blob) so the archive snapshot stays small; the intervention only carries
// signer, timestamp and the yearly progressive number. A signed report is locked until the office unsigns it.

public sealed partial class Store
{
 void InitRapportini(SqliteConnection c)
 {
  using var q = c.CreateCommand(); q.CommandText = """
   CREATE TABLE IF NOT EXISTS signatures(intervention TEXT PRIMARY KEY, png BLOB NOT NULL, signer TEXT NOT NULL, signed_at TEXT NOT NULL, signed_by TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS report_numbers(year INTEGER PRIMARY KEY, last INTEGER NOT NULL);
   """; q.ExecuteNonQuery();
 }

 static bool IsPng(byte[] b) => b.Length > 8 && b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47;

 public Intervention SignIntervention(string id, string signer, string hours, byte[] png, bool close, string who)
 {
  signer = (signer ?? "").Trim();
  if (signer.Length is < 2 or > 80) throw new InvalidDataException("Scrivi nome e cognome di chi firma.");
  if (png == null || !IsPng(png) || png.Length < 200) throw new InvalidDataException("Firma mancante: fai firmare il cliente nel riquadro.");
  if (png.Length > 600_000) throw new InvalidDataException("Immagine della firma troppo grande.");
  hours = (hours ?? "").Trim().Replace(',', '.');
  if (hours.Length > 0 && (!decimal.TryParse(hours, NumberStyles.Number, CultureInfo.InvariantCulture, out var h) || h < 0 || h > 200)) throw new InvalidDataException("Ore lavorate non valide.");
  using var c = Open(); using var tx = c.BeginTransaction();
  Intervention i;
  using (var g = c.CreateCommand()) { g.Transaction = tx; g.CommandText = "SELECT data FROM interventions WHERE id=$i"; g.Parameters.AddWithValue("$i", id);
   i = g.ExecuteScalar() is string s ? JsonSerializer.Deserialize<Intervention>(s)! : throw new InvalidDataException("Intervento non trovato: salvalo prima di farlo firmare."); }
  if (i.Source != "app") throw new InvalidDataException("Gli interventi storici di Mastro non si firmano.");
  if (!string.IsNullOrEmpty(i.SignedAt)) throw new InvalidDataException("Rapportino già firmato.");
  var now = DateTimeOffset.Now;
  var number = i.ReportNo;
  if (string.IsNullOrEmpty(number))
  {
   // yearly progressive number, assigned once (re-signing after an unsign keeps it)
   using var n = c.CreateCommand(); n.Transaction = tx;
   n.CommandText = "INSERT INTO report_numbers VALUES($y,1) ON CONFLICT(year) DO UPDATE SET last=last+1 RETURNING last";
   n.Parameters.AddWithValue("$y", now.Year); number = $"{now.Year}/{Convert.ToInt64(n.ExecuteScalar()):0000}";
  }
  var rec = i with { Signer = signer, SignedAt = now.ToString("O"), ReportNo = number, Hours = hours.Length > 0 ? hours : i.Hours, Status = close ? "Chiuso" : i.Status, Updated = now.ToString("O"), UpdatedBy = who };
  using (var u = c.CreateCommand()) { u.Transaction = tx; u.CommandText = "UPDATE interventions SET data=$d WHERE id=$i"; u.Parameters.AddWithValue("$d", JsonSerializer.Serialize(rec)); u.Parameters.AddWithValue("$i", id); u.ExecuteNonQuery(); }
  using (var p = c.CreateCommand())
  {
   p.Transaction = tx; p.CommandText = "INSERT OR REPLACE INTO signatures VALUES($i,$p,$s,$t,$w)";
   p.Parameters.AddWithValue("$i", id); p.Parameters.AddWithValue("$p", png); p.Parameters.AddWithValue("$s", signer); p.Parameters.AddWithValue("$t", rec.SignedAt); p.Parameters.AddWithValue("$w", who); p.ExecuteNonQuery();
  }
  using (var e = c.CreateCommand()) { e.Transaction = tx; e.CommandText = "INSERT INTO events(time,kind,message) VALUES($t,'rapportino',$m)"; e.Parameters.AddWithValue("$t", now.ToString("O")); e.Parameters.AddWithValue("$m", $"Rapportino n. {number} firmato da {signer} ({who})."); e.ExecuteNonQuery(); }
  tx.Commit(); return rec;
 }

 public Intervention UnsignIntervention(string id, string who)
 {
  using var c = Open(); using var tx = c.BeginTransaction();
  Intervention i;
  using (var g = c.CreateCommand()) { g.Transaction = tx; g.CommandText = "SELECT data FROM interventions WHERE id=$i"; g.Parameters.AddWithValue("$i", id);
   i = g.ExecuteScalar() is string s ? JsonSerializer.Deserialize<Intervention>(s)! : throw new InvalidDataException("Intervento non trovato."); }
  if (string.IsNullOrEmpty(i.SignedAt)) throw new InvalidDataException("Il rapportino non è firmato.");
  var rec = i with { Signer = "", SignedAt = "", Updated = DateTimeOffset.Now.ToString("O"), UpdatedBy = who };
  using (var u = c.CreateCommand()) { u.Transaction = tx; u.CommandText = "UPDATE interventions SET data=$d WHERE id=$i"; u.Parameters.AddWithValue("$d", JsonSerializer.Serialize(rec)); u.Parameters.AddWithValue("$i", id); u.ExecuteNonQuery(); }
  using (var d = c.CreateCommand()) { d.Transaction = tx; d.CommandText = "DELETE FROM signatures WHERE intervention=$i"; d.Parameters.AddWithValue("$i", id); d.ExecuteNonQuery(); }
  using (var e = c.CreateCommand()) { e.Transaction = tx; e.CommandText = "INSERT INTO events(time,kind,message) VALUES($t,'rapportino',$m)"; e.Parameters.AddWithValue("$t", DateTimeOffset.Now.ToString("O")); e.Parameters.AddWithValue("$m", $"{who} ha annullato la firma del rapportino n. {i.ReportNo} (firmato da {i.Signer})."); e.ExecuteNonQuery(); }
  tx.Commit(); return rec;
 }

 public byte[]? Signature(string id)
 {
  using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "SELECT png FROM signatures WHERE intervention=$i"; q.Parameters.AddWithValue("$i", id);
  return q.ExecuteScalar() as byte[];
 }
}

/// A4 PDF of the intervention report. PDFsharp 6.2 (MIT licence), Windows fonts.
/// Maps the report fonts to the Windows font files (Segoe UI, falling back to Arial), so PDFs look the same on every PC and on the server.
sealed class WindowsFontResolver : IFontResolver
{
 static readonly string Dir = Environment.GetFolderPath(Environment.SpecialFolder.Fonts);
 static string Pick(params string[] files) => files.FirstOrDefault(f => File.Exists(Path.Combine(Dir, f))) ?? throw new InvalidOperationException("Font di Windows non trovati (Segoe UI / Arial).");
 public FontResolverInfo ResolveTypeface(string familyName, bool isBold, bool isItalic) => new(isBold ? "tc-bold" : "tc-regular");
 public byte[] GetFont(string faceName) => File.ReadAllBytes(Path.Combine(Dir, faceName == "tc-bold" ? Pick("segoeuib.ttf", "arialbd.ttf") : Pick("segoeui.ttf", "arial.ttf")));
}

public static class InterventionReport
{
 static InterventionReport() { if (GlobalFontSettings.FontResolver is not WindowsFontResolver) GlobalFontSettings.FontResolver = new WindowsFontResolver(); }
 public const string Company = "Top Cash Service S.r.l.";
 static readonly string[] CompanyLines = ["Via Ponte alle Mosse 163/a · 50144 Firenze", "Tel. 055 353619 · assistenza@topcash.it", "P.IVA e C.F. 04289740484"];
 static readonly XColor Blue = XColor.FromArgb(10, 107, 218), Navy = XColor.FromArgb(8, 37, 75), Ink = XColor.FromArgb(22, 22, 29), Muted = XColor.FromArgb(110, 111, 130), Line = XColor.FromArgb(225, 226, 232), Soft = XColor.FromArgb(246, 247, 250);
 const double M = 42, W = 595.28, H = 841.89;
 static readonly CultureInfo It = CultureInfo.GetCultureInfo("it-IT");

 public sealed record ClientInfo(string Name, string Address, string Vat, string Code, string Phone);

 /// Customer data the same way the apps show it: Zucchetti registry + licences, completed by MastroSQL.
 public static ClientInfo ResolveClient(Store s, string code, string fallbackName)
 {
  static string Key(string v) => new string((v ?? "").ToUpperInvariant().Replace("IT", "").Where(char.IsLetterOrDigit).ToArray());
  var mastro = s.MastroClients();
  if (code.StartsWith('M') && mastro.FirstOrDefault(m => "M" + m.Code == code) is { } mo)
   return new(mo.Name, Addr(mo), mo.Vat.Length > 0 ? mo.Vat : mo.TaxCode, mo.Code, mo.Phones.FirstOrDefault() ?? "");
  var reg = s.Clients().FirstOrDefault(c => c.Code == code);
  var lic = s.All().FirstOrDefault(l => l.ClientCode == code);
  var vat = lic?.Vat ?? reg?.TaxCode ?? "";
  var m = mastro.FirstOrDefault(x => Key(vat).Length >= 11 && (Key(x.Vat) == Key(vat) || Key(x.TaxCode) == Key(vat)));
  var name = reg?.Name ?? lic?.Client ?? m?.Name ?? fallbackName;
  var addr = m != null ? Addr(m) : string.Join(", ", new[] { reg?.Address, reg?.City }.Where(x => !string.IsNullOrWhiteSpace(x)));
  return new(name, addr, vat, code.TrimStart('0'), reg?.Phone is { Length: > 0 } p ? p : m?.Phones.FirstOrDefault() ?? "");
 }
 static string Addr(MastroClient m) => string.Join(", ", new[] { m.Address, string.Join(" ", new[] { m.Cap, m.City, m.Prov.Length > 0 ? $"({m.Prov})" : "" }.Where(x => x.Length > 0)) }.Where(x => x.Length > 0));

 public static string FileName(Intervention i) => $"Rapportino-{(i.ReportNo.Length > 0 ? i.ReportNo.Replace('/', '-') : i.Id)}.pdf";

 public static byte[] Build(Store s, string id, string? logoPath = null)
 {
  var i = s.Interventions().FirstOrDefault(x => x.Id == id) ?? throw new InvalidDataException("Intervento non trovato.");
  var client = ResolveClient(s, i.ClientCode, i.ClientName);
  var machine = s.Machines().FirstOrDefault(m => m.Id == i.Machine || m.Id == "m" + i.Machine);
  var articles = s.Articles().ToDictionary(a => a.Code, StringComparer.OrdinalIgnoreCase);
  var signature = s.Signature(id);
  logoPath ??= Path.Combine(AppContext.BaseDirectory, "ui", "assets", "tcs-logo.png");

  var doc = new PdfDocument(); doc.Info.Title = $"Rapporto di intervento {i.ReportNo}"; doc.Info.Author = Company; doc.Info.Creator = "Top Cash Studio";
  var f = (double size, bool bold) => new XFont("Segoe UI", size, bold ? XFontStyleEx.Bold : XFontStyleEx.Regular);
  XFont body = f(9.5, false), bold = f(9.5, true), small = f(8, false), label = f(7.5, true);
  PdfPage page = null!; XGraphics g = null!; double y = 0; int pageNo = 0;

  void Footer()
  {
   g.DrawLine(new XPen(Line, 0.6), M, H - 38, W - M, H - 38);
   g.DrawString($"{Company} · P.IVA 04289740484 · Rapporto n. {(i.ReportNo.Length > 0 ? i.ReportNo : "provvisorio")}", small, new XSolidBrush(Muted), new XRect(M, H - 33, W - 2 * M, 12), XStringFormats.TopLeft);
   g.DrawString($"Pagina {pageNo}", small, new XSolidBrush(Muted), new XRect(M, H - 33, W - 2 * M, 12), XStringFormats.TopRight);
  }
  void NewPage()
  {
   if (g != null) { Footer(); g.Dispose(); }
   page = doc.AddPage(); page.Width = XUnit.FromPoint(W); page.Height = XUnit.FromPoint(H); g = XGraphics.FromPdfPage(page); pageNo++; y = M;
   if (pageNo > 1) { g.DrawString($"Rapporto di intervento n. {i.ReportNo} — segue", bold, new XSolidBrush(Muted), M, y, XStringFormats.TopLeft); y += 22; }
  }
  void Need(double h) { if (y + h > H - 56) NewPage(); }
  // word-wrap that respects new lines
  List<string> Wrap(string text, XFont font, double width)
  {
   var lines = new List<string>();
   foreach (var para in (text ?? "").Replace("\r", "").Split('\n'))
   {
    var cur = "";
    foreach (var word in para.Split(' '))
    {
     var t = cur.Length == 0 ? word : cur + " " + word;
     if (g.MeasureString(t, font).Width <= width) { cur = t; continue; }
     if (cur.Length > 0) lines.Add(cur);
     cur = word;
     while (g.MeasureString(cur, font).Width > width && cur.Length > 1) { var k = cur.Length - 1; while (k > 1 && g.MeasureString(cur[..k], font).Width > width) k--; lines.Add(cur[..k]); cur = cur[k..]; }
    }
    lines.Add(cur);
   }
   return lines;
  }
  void Text(string t, XFont font, XColor color, double x, double width)
  {
   foreach (var l in Wrap(t, font, width)) { Need(font.Size * 1.45); g.DrawString(l, font, new XSolidBrush(color), x, y, XStringFormats.TopLeft); y += font.Size * 1.45; }
  }
  void Section(string title) { Need(34); y += 10; g.DrawString(title.ToUpperInvariant(), label, new XSolidBrush(Blue), M, y, XStringFormats.TopLeft); y += 14; }

  NewPage();
  // ── header: logo + company
  if (File.Exists(logoPath)) { using var img = XImage.FromFile(logoPath); var lw = 118.0; g.DrawImage(img, M, y, lw, lw * img.PixelHeight / img.PixelWidth); }
  var cy = y + 2;
  g.DrawString(Company, bold, new XSolidBrush(Navy), new XRect(M, cy, W - 2 * M, 12), XStringFormats.TopRight); cy += 13;
  foreach (var l in CompanyLines) { g.DrawString(l, small, new XSolidBrush(Muted), new XRect(M, cy, W - 2 * M, 11), XStringFormats.TopRight); cy += 11; }
  y += 92;
  g.DrawRectangle(new XSolidBrush(Blue), M, y, W - 2 * M, 2.2); y += 16;
  g.DrawString("Rapporto di intervento", f(20, true), new XSolidBrush(Ink), M, y, XStringFormats.TopLeft);
  var date = DateTime.TryParse(i.Date, CultureInfo.InvariantCulture, DateTimeStyles.None, out var dd) ? dd.ToString("d MMMM yyyy", It) : "—";
  g.DrawString($"N. {(i.ReportNo.Length > 0 ? i.ReportNo : "—")}", f(13, true), new XSolidBrush(Blue), new XRect(M, y + 4, W - 2 * M, 16), XStringFormats.TopRight);
  y += 30; g.DrawString(date, body, new XSolidBrush(Muted), M, y, XStringFormats.TopLeft); y += 22;

  // ── two info boxes
  double bw = (W - 2 * M - 12) / 2, by = y, bh = 92;
  void Box(double x, string title, (string, string)[] rows)
  {
   g.DrawRoundedRectangle(new XPen(Line, 0.8), new XSolidBrush(Soft), x, by, bw, bh, 10, 10);
   double yy = by + 10; g.DrawString(title.ToUpperInvariant(), label, new XSolidBrush(Blue), x + 12, yy, XStringFormats.TopLeft); yy += 14;
   foreach (var (k, v) in rows.Where(r => r.Item2.Length > 0).Take(5))
   {
    g.DrawString(k, small, new XSolidBrush(Muted), x + 12, yy, XStringFormats.TopLeft);
    var val = v; while (g.MeasureString(val, k == "" ? bold : body).Width > bw - 86 && val.Length > 3) val = val[..^2];
    if (val != v) val = val.TrimEnd() + "…";
    g.DrawString(val, k == "" ? bold : body, new XSolidBrush(Ink), x + 74, yy - 1, XStringFormats.TopLeft); yy += 12.5;
   }
  }
  Box(M, "Cliente", [("Ragione soc.", client.Name), ("Indirizzo", client.Address), ("P.IVA / C.F.", client.Vat), ("Telefono", client.Phone), ("Codice", client.Code)]);
  var hours = decimal.TryParse(i.Hours, NumberStyles.Number, CultureInfo.InvariantCulture, out var hv) && hv > 0 ? hv.ToString("0.##", It) + " h" : "";
  Box(M + bw + 12, "Intervento", [("Tecnico", i.Technician), ("Ore", hours), ("Macchina", machine == null ? "" : $"{machine.Model} · matr. {machine.Serial}" + (machine.FiscalSerial.Length > 0 ? $" · {machine.FiscalSerial}" : "")), ("Stato", i.Status), ("Priorità", i.Priority == "Normale" ? "" : i.Priority)]);
  y = by + bh + 8;

  Section("Oggetto"); Text(i.Title, f(11, true), Ink, M, W - 2 * M);
  Section("Lavoro svolto"); Text(i.Work.Length > 0 ? i.Work : "—", body, Ink, M, W - 2 * M);

  var parts = i.Parts ?? [];
  if (parts.Count > 0)
  {
   Section("Materiale utilizzato");
   double[] cx = [M, M + 92, W - M - 150, W - M - 108];
   void Head() { g.DrawRectangle(new XSolidBrush(Soft), M, y - 3, W - 2 * M, 16); foreach (var (t, x) in new[] { ("Codice", cx[0] + 6), ("Descrizione", cx[1]), ("Q.tà", cx[2]), ("Matricola", cx[3]) }) g.DrawString(t, label, new XSolidBrush(Muted), x, y, XStringFormats.TopLeft); y += 18; }
   Need(40); Head();
   foreach (var p in parts)
   {
    Need(16); if (y < M + 30) Head();
    articles.TryGetValue(p.Article, out var a);
    var desc = a?.Name ?? p.Article; while (g.MeasureString(desc, body).Width > cx[2] - cx[1] - 8 && desc.Length > 3) desc = desc[..^2];
    g.DrawString(p.Article, body, new XSolidBrush(Ink), cx[0] + 6, y, XStringFormats.TopLeft); g.DrawString(desc, body, new XSolidBrush(Ink), cx[1], y, XStringFormats.TopLeft);
    g.DrawString(p.Qty.ToString("0.###", It) + " " + (a?.Unit ?? ""), body, new XSolidBrush(Ink), cx[2], y, XStringFormats.TopLeft); g.DrawString(p.Serial, body, new XSolidBrush(Ink), cx[3], y, XStringFormats.TopLeft);
    y += 17; g.DrawLine(new XPen(Line, 0.5), M, y - 3, W - M, y - 3);
   }
  }

  // ── signatures
  Need(140); y += 18;
  double sw = (W - 2 * M - 24) / 2;
  g.DrawString("FIRMA DEL CLIENTE", label, new XSolidBrush(Blue), M, y, XStringFormats.TopLeft);
  g.DrawString("IL TECNICO", label, new XSolidBrush(Blue), M + sw + 24, y, XStringFormats.TopLeft); y += 12;
  g.DrawRoundedRectangle(new XPen(Line, 0.8), XBrushes.White, M, y, sw, 84, 10, 10);
  g.DrawRoundedRectangle(new XPen(Line, 0.8), XBrushes.White, M + sw + 24, y, sw, 84, 10, 10);
  if (signature != null)
  {
   using var ms = new MemoryStream(signature); using var img = XImage.FromStream(ms);
   double iw = img.PixelWidth, ih = img.PixelHeight, sc = Math.Min((sw - 16) / iw, 70 / ih);
   g.DrawImage(img, M + (sw - iw * sc) / 2, y + 7 + (70 - ih * sc) / 2, iw * sc, ih * sc);
  }
  // printed before the visit: the box stays blank for a pen signature at the customer's
  else g.DrawString("firma e timbro", small, new XSolidBrush(Muted), new XRect(M, y + 66, sw, 14), XStringFormats.Center);
  g.DrawString(i.Technician.Length > 0 ? i.Technician : "—", f(12, true), new XSolidBrush(Ink), new XRect(M + sw + 24, y, sw, 84), XStringFormats.Center);
  y += 90;
  var signedAt = DateTimeOffset.TryParse(i.SignedAt, CultureInfo.InvariantCulture, DateTimeStyles.None, out var sa) ? sa.ToLocalTime().ToString("d MMMM yyyy 'alle' HH:mm", It) : "";
  g.DrawString(i.Signer.Length > 0 ? $"{i.Signer} · firmato il {signedAt}" : "", small, new XSolidBrush(Muted), M, y, XStringFormats.TopLeft);
  g.DrawString(Company, small, new XSolidBrush(Muted), M + sw + 24, y, XStringFormats.TopLeft); y += 18;
  Text("Il cliente con la firma conferma l'esecuzione dell'intervento e l'utilizzo del materiale indicati. Documento generato da Top Cash Studio.", small, Muted, M, W - 2 * M);

  Footer(); g.Dispose();
  using var outMs = new MemoryStream(); doc.Save(outMs, false); return outMs.ToArray();
 }
}
