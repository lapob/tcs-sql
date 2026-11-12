using Microsoft.Data.Sqlite;
using MimeKit;
using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;

namespace TopCash.Licenze;

// ── 2.9.0: stops of the day marked as done, machines to check (verification overdue for over a year), photos of an
// intervention, notices for the phones and the weekly summary by e-mail. All kept apart from the Mastro copy: an import
// never touches them.

/// What the office decided about a Mastro machine: "dismessa" (left the customer: out of every list) or "attiva"
/// (still working even if the verification is very late: it stays in the round and the deadlines).
public sealed record MachineFlag(string State = "", string At = "", string By = "");
/// A stop of the round marked as done ("Fatto") on [Day]; kept two days, then forgotten.
public sealed record RouteMark(string Day = "", string At = "", string By = "");
/// A photo attached to an intervention (the picture itself stays on the server, apart from the archive snapshot).
public sealed record PhotoInfo(string Id = "", string Intervention = "", string At = "", string By = "", int Size = 0);
/// The Monday summary: who receives it and how the last send went.
public sealed record WeeklyConfig(bool Enabled = false, string To = "", string LastWeek = "", string LastSent = "", string LastTry = "", string LastError = "");
/// One notice for the phones: an intervention assigned to you, or a new urgent one.
public sealed record Notice(string Id, string Kind, string Title, string Client, string Priority, string By, string At);

public sealed partial class Store
{
 public static readonly string[] MachineStates = ["dismessa", "attiva"];
 public const int MaxPhotos = 30;
 /// A verification overdue longer than this (days) means the machine is probably gone: it waits for a decision.
 public const int StaleVerificationDays = 365;

 void InitNovita(SqliteConnection c)
 {
  using var q = c.CreateCommand(); q.CommandText = """
   CREATE TABLE IF NOT EXISTS machine_flags(id TEXT PRIMARY KEY, data TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS route_done(key TEXT PRIMARY KEY, data TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS photos(id TEXT PRIMARY KEY, intervention TEXT NOT NULL, jpg BLOB NOT NULL, data TEXT NOT NULL);
   CREATE INDEX IF NOT EXISTS photos_intervention ON photos(intervention);
   """; q.ExecuteNonQuery();
 }

 static DateTime? AnyDate(string? s)
 {
  s = (s ?? "").Trim(); if (s.Length < 8) return null;
  string[] f = ["yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "yyyy-MM-ddTHH:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy HH:mm:ss"];
  if (DateTime.TryParseExact(s.Length > 10 && s[4] == '-' ? s[..10] : s.Length > 10 && s[2] is '/' or '-' ? s[..10] : s, f, CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) && d.Year >= 1900) return d;
  return null;
 }
 /// Next periodic verification of a machine (every two years from the last one), like the apps compute it.
 public static DateTime? NextVerification(Machine m) => AnyDate(m.Verification)?.AddYears(2);

 // ── machines to check
 public Dictionary<string, MachineFlag> MachineFlags() => EditMap<MachineFlag>("machine_flags", "id");
 public int SaveMachineFlags(IEnumerable<string> ids, string state, string who)
 {
  state = (state ?? "").Trim().ToLowerInvariant();
  if (state.Length > 0 && !MachineStates.Contains(state)) throw new InvalidDataException("Scelta non valida.");
  var list = (ids ?? []).Select(i => Trim(i, 60)).Where(i => i.Length > 0).Distinct().ToList();
  if (list.Count == 0) throw new InvalidDataException("Nessuna macchina scelta.");
  if (list.Count > 5000) throw new InvalidDataException("Troppe macchine in una volta.");
  var now = DateTimeOffset.Now.ToString("O"); var by = Trim(who, 80);
  using (var c = Open())
  {
   using var tx = c.BeginTransaction();
   foreach (var id in list)
   {
    using var q = c.CreateCommand(); q.Transaction = tx;
    if (state.Length == 0) { q.CommandText = "DELETE FROM machine_flags WHERE id=$i"; q.Parameters.AddWithValue("$i", id); }
    else { q.CommandText = "INSERT INTO machine_flags VALUES($i,$d) ON CONFLICT(id) DO UPDATE SET data=excluded.data"; q.Parameters.AddWithValue("$i", id); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(new MachineFlag(state, now, by))); }
    q.ExecuteNonQuery();
   }
   tx.Commit();
  }
  var what = state switch { "dismessa" => "segnato come dismesse", "attiva" => "confermato come ancora attive", _ => "rimesso da controllare" };
  Log("edit", $"{by} ha {what} {list.Count} {(list.Count == 1 ? "macchina" : "macchine")}.");
  return list.Count;
 }

 // ── the round of the day
 static string Today() => DateTime.Today.ToString("yyyy-MM-dd");
 /// Only today's and yesterday's marks (the apps keep today's).
 public Dictionary<string, RouteMark> RouteDone()
 {
  var from = DateTime.Today.AddDays(-1).ToString("yyyy-MM-dd");
  return EditMap<RouteMark>("route_done", "key").Where(kv => string.CompareOrdinal(kv.Value.Day, from) >= 0).ToDictionary(kv => kv.Key, kv => kv.Value);
 }
 public RouteMark? SaveRouteDone(string key, bool done, string who)
 {
  key = Trim(key, 120); if (key.Length == 0) throw new InvalidDataException("Tappa non valida.");
  using (var c = Open()) { using var p = c.CreateCommand(); p.CommandText = "DELETE FROM route_done WHERE json_extract(data,'$.Day') < $d"; p.Parameters.AddWithValue("$d", DateTime.Today.AddDays(-1).ToString("yyyy-MM-dd")); p.ExecuteNonQuery(); }
  var mark = done ? new RouteMark(Today(), DateTimeOffset.Now.ToString("O"), Trim(who, 80)) : null;
  SaveEdit("route_done", "key", key, mark);
  return mark;
 }

 // ── photos of an intervention
 public Dictionary<string, List<PhotoInfo>> Photos()
 {
  using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "SELECT data FROM photos";
  using var r = q.ExecuteReader(); var all = new List<PhotoInfo>();
  while (r.Read()) if (JsonSerializer.Deserialize<PhotoInfo>(r.GetString(0)) is { } p) all.Add(p);
  return all.GroupBy(p => p.Intervention).ToDictionary(g => g.Key, g => g.OrderBy(p => p.At, StringComparer.Ordinal).ToList());
 }
 public static bool IsJpeg(byte[] b) => b.Length > 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF;
 public PhotoInfo AddPhoto(string interventionId, byte[] jpg, string who)
 {
  interventionId = Trim(interventionId, 60);
  if (jpg == null || jpg.Length < 200 || !IsJpeg(jpg)) throw new InvalidDataException("Foto non valida: usa una foto JPEG.");
  if (jpg.Length > 4_000_000) throw new InvalidDataException("Foto troppo grande (massimo 4 MB).");
  using var c = Open();
  using (var g = c.CreateCommand())
  {
   g.CommandText = "SELECT data FROM interventions WHERE id=$i AND source<>'deleted'"; g.Parameters.AddWithValue("$i", interventionId);
   var i = g.ExecuteScalar() is string js ? JsonSerializer.Deserialize<Intervention>(js) : null;
   if (i == null) throw new InvalidDataException("Intervento non trovato: salvalo prima di aggiungere foto.");
   if (i.Source != "app") throw new InvalidDataException("Agli interventi storici di Mastro non si aggiungono foto.");
   if (!string.IsNullOrEmpty(i.SignedAt)) throw new InvalidDataException("Rapportino già firmato: le foto si aggiungono prima della firma.");
  }
  using (var n = c.CreateCommand()) { n.CommandText = "SELECT COUNT(*) FROM photos WHERE intervention=$i"; n.Parameters.AddWithValue("$i", interventionId); if (Convert.ToInt32(n.ExecuteScalar()) >= MaxPhotos) throw new InvalidDataException($"Al massimo {MaxPhotos} foto per intervento."); }
  var p = new PhotoInfo("P" + Guid.NewGuid().ToString("N")[..16], interventionId, DateTimeOffset.Now.ToString("O"), Trim(who, 80), jpg.Length);
  using (var q = c.CreateCommand())
  {
   q.CommandText = "INSERT INTO photos VALUES($i,$n,$j,$d)";
   q.Parameters.AddWithValue("$i", p.Id); q.Parameters.AddWithValue("$n", interventionId); q.Parameters.AddWithValue("$j", jpg); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(p));
   q.ExecuteNonQuery();
  }
  return p;
 }
 public byte[]? Photo(string id)
 {
  using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "SELECT jpg FROM photos WHERE id=$i"; q.Parameters.AddWithValue("$i", Trim(id, 40));
  return q.ExecuteScalar() as byte[];
 }
 public PhotoInfo? PhotoInfoOf(string id)
 {
  using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "SELECT data FROM photos WHERE id=$i"; q.Parameters.AddWithValue("$i", Trim(id, 40));
  return q.ExecuteScalar() is string s ? JsonSerializer.Deserialize<PhotoInfo>(s) : null;
 }
 public void DeletePhoto(string id, string who)
 {
  var p = PhotoInfoOf(id) ?? throw new InvalidDataException("Foto non trovata.");
  using (var c = Open()) { using var q = c.CreateCommand(); q.CommandText = "DELETE FROM photos WHERE id=$i"; q.Parameters.AddWithValue("$i", p.Id); q.ExecuteNonQuery(); }
  Log("edit", $"{Trim(who, 80)} ha tolto una foto da un intervento.");
 }
 // ── notices for the phones (checked in the background every quarter of an hour)
 static string NameKey(string? s) => (s ?? "").Trim().ToLowerInvariant();
 /// Interventions changed since [since] that concern [me]: assigned to me by someone else, or a new urgent one nobody has yet.
 public List<Notice> Notices(string me, DateTimeOffset since, bool everyUrgent)
 {
  var key = NameKey(me); var res = new List<Notice>();
  if (key.Length == 0) return res;
  foreach (var i in Interventions())
  {
   if (i.Source != "app" || i.Status == "Chiuso") continue;
   if (!DateTimeOffset.TryParse(i.Updated, CultureInfo.InvariantCulture, DateTimeStyles.None, out var up) || up <= since) continue;
   var by = string.IsNullOrWhiteSpace(i.UpdatedBy) ? i.CreatedBy : i.UpdatedBy;
   if (NameKey(by) == key) continue; // my own change
   var created = DateTimeOffset.TryParse(i.Created, CultureInfo.InvariantCulture, DateTimeStyles.None, out var cr) && cr > since;
   var mine = NameKey(i.Technician) == key;
   if (mine) res.Add(new(i.Id, created ? "assigned" : "updated", i.Title, i.ClientName, i.Priority, by, i.Updated));
   else if (created && i.Priority == "Urgente" && (everyUrgent || i.Technician.Trim().Length == 0)) res.Add(new(i.Id, "urgent", i.Title, i.ClientName, i.Priority, by, i.Updated));
  }
  return res.OrderByDescending(n => n.At, StringComparer.Ordinal).Take(20).ToList();
 }

 // ── weekly summary
 public WeeklyConfig Weekly() => Setting<WeeklyConfig>("weekly") ?? new WeeklyConfig();
 public WeeklyConfig SaveWeekly(bool enabled, string to, string who)
 {
  to = (to ?? "").Trim();
  if (to.Length > 0) Mailer.Addresses(to, 5);
  if (enabled && to.Length == 0) throw new InvalidDataException("Scrivi a chi inviare il riepilogo.");
  var w = Weekly() with { Enabled = enabled, To = to };
  SetSetting("weekly", w);
  Log("settings", $"{Trim(who, 80)} ha {(enabled ? "attivato" : "disattivato")} il riepilogo settimanale via email.");
  return w;
 }
 public static string WeekKey(DateTime d) => $"{ISOWeek.GetYear(d)}-W{ISOWeek.GetWeekOfYear(d):00}";
}

/// The Monday summary: open work, what was done last week, verifications coming up, machines to check, licences and stock.
public static class WeeklyReport
{
 static readonly CultureInfo It = CultureInfo.GetCultureInfo("it-IT");
 sealed record Line(string Main, string Sub, string Tag, string Tone);
 sealed record Block(string Title, string Count, string Note, List<Line> Lines, string More);

 static List<Block> Blocks(Store s, DateTime today)
 {
  var blocks = new List<Block>();
  var mv = s.Mastro(); var flags = s.MachineFlags();
  var clients = mv.Clients.GroupBy(c => c.Code).ToDictionary(g => g.Key, g => g.First().Name);
  string Client(string code, string fallback = "") => clients.GetValueOrDefault(code) ?? (fallback.Length > 0 ? fallback : "Cliente " + code);
  // interventions
  var app = mv.Interventions.Where(i => i.Source == "app").ToList();
  var open = app.Where(i => i.Status != "Chiuso").ToList();
  DateTime? When(string v) => DateTimeOffset.TryParse(v, CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d.LocalDateTime.Date : null;
  var weekAgo = today.AddDays(-7);
  var created = app.Count(i => When(i.Created) >= weekAgo);
  var closed = app.Where(i => i.Status == "Chiuso" && When(i.Updated) >= weekAgo).ToList();
  int Rank(string p) => p switch { "Urgente" => 0, "Alta" => 1, "Normale" => 2, _ => 3 };
  var hot = open.OrderBy(i => Rank(i.Priority)).ThenBy(i => i.Date.Length > 0 ? i.Date : i.Created, StringComparer.Ordinal).ToList();
  var urgent = open.Count(i => i.Priority == "Urgente"); var high = open.Count(i => i.Priority == "Alta");
  blocks.Add(new("Interventi aperti", open.Count.ToString(It),
   string.Join(" · ", new[] { urgent > 0 ? $"{urgent} urgenti" : "", high > 0 ? $"{high} alta priorità" : "", $"{created} nuovi e {closed.Count} chiusi negli ultimi 7 giorni" }.Where(x => x.Length > 0)),
   hot.Take(12).Select(i => new Line(i.Title, string.Join(" · ", new[] { (i.ClientName.Length > 0 ? i.ClientName : Client(i.ClientCode.TrimStart('M'))), i.Technician.Length > 0 ? i.Technician : "da assegnare", i.Status }.Where(x => x.Length > 0)),
    i.Priority == "Normale" ? "" : i.Priority, i.Priority switch { "Urgente" => "bad", "Alta" => "due", "Bassa" => "ok", _ => "" })).ToList(),
   hot.Count > 12 ? $"e altri {hot.Count - 12}" : ""));
  if (closed.Count > 0)
   blocks.Add(new("Chiusi negli ultimi 7 giorni", closed.Count.ToString(It), string.Join(" · ", closed.GroupBy(i => i.Technician.Length > 0 ? i.Technician : "senza tecnico").OrderByDescending(g => g.Count()).Select(g => $"{g.Key} {g.Count()}")), [], ""));
  // periodic verifications (machines marked as gone or very late are left out: see "Macchine da controllare")
  var machines = mv.Machines.Where(m => m.Active && flags.GetValueOrDefault(m.Id)?.State != "dismessa").ToList();
  var ver = machines.Select(m => (m, next: Store.NextVerification(m))).Where(x => x.next != null).Select(x => (x.m, next: x.next!.Value, days: (int)(x.next!.Value - today).TotalDays)).ToList();
  var stale = ver.Where(x => x.days < -Store.StaleVerificationDays && flags.GetValueOrDefault(x.m.Id)?.State != "attiva").ToList();
  var due = ver.Where(x => x.days <= 30 && !stale.Contains(x)).OrderBy(x => x.next).ToList();
  var late = due.Count(x => x.days < 0);
  blocks.Add(new("Verificazioni periodiche", due.Count.ToString(It), (late > 0 ? $"{late} già scadute · " : "") + $"{due.Count - late} entro 30 giorni",
   due.Take(12).Select(x => new Line(Client(x.m.ClientCode), $"{(x.m.Model.Length > 0 ? x.m.Model : "Macchina")} · {x.m.Serial}", x.days < 0 ? $"scaduta da {-x.days} gg" : x.days == 0 ? "oggi" : $"tra {x.days} gg", x.days < 0 ? "bad" : "due")).ToList(),
   due.Count > 12 ? $"e altre {due.Count - 12}" : ""));
  if (stale.Count > 0)
   blocks.Add(new("Macchine da controllare", stale.Count.ToString(It), "Verificazione scaduta da oltre un anno: probabilmente non sono più dal cliente. Aprile in Studio → Macchine da controllare e segnale come dismesse o ancora attive.", [], ""));
  // licences expiring in 30 days
  var lic = s.Licenses().Where(r => !r.Scope.EndsWith("-scadute")).Select(r => (r, d: AnyDateOf(r.Expiry))).Where(x => x.d != null && x.d >= today && x.d <= today.AddDays(30)).ToList();
  var renewals = s.Renewals();
  lic = lic.Where(x => renewals.GetValueOrDefault($"lic|{x.r.Id}|{x.d:yyyy-MM-dd}")?.Status != "Rinnovato").ToList();
  if (lic.Count > 0) blocks.Add(new("Licenze in scadenza entro 30 giorni", lic.Count.ToString(It), "", lic.OrderBy(x => x.d).Take(8).Select(x => new Line(x.r.Client, $"{x.r.Product} · {x.r.Serial}", x.d!.Value.ToString("d MMM", It), "due")).ToList(), lic.Count > 8 ? $"e altre {lic.Count - 8}" : ""));
  // stock under minimum
  var stock = s.Stock().ToDictionary(r => (r.Article.ToUpperInvariant(), r.Warehouse), r => r.Qty);
  var arts = s.Articles().GroupBy(a => a.Code, StringComparer.OrdinalIgnoreCase).ToDictionary(g => g.Key, g => g.First(), StringComparer.OrdinalIgnoreCase);
  var whs = s.Warehouses().ToDictionary(w => w.Id, w => w.Name);
  var low = s.StockLevels().Where(l => l.Min > 0 && stock.GetValueOrDefault((l.Article.ToUpperInvariant(), l.Warehouse)) <= l.Min).ToList();
  if (low.Count > 0) blocks.Add(new("Magazzino sotto scorta", low.Count.ToString(It), "Articoli al minimo o sotto: la proposta d'ordine è in Magazzino → Riordino.",
   low.Take(8).Select(l => new Line(arts.GetValueOrDefault(l.Article)?.Name ?? l.Article, whs.GetValueOrDefault(l.Warehouse) ?? l.Warehouse, $"{stock.GetValueOrDefault((l.Article.ToUpperInvariant(), l.Warehouse)):0.##} / min {l.Min:0.##}", "due")).ToList(), low.Count > 8 ? $"e altri {low.Count - 8}" : ""));
  return blocks;
 }
 static DateTime? AnyDateOf(string v)
 {
  v = (v ?? "").Trim(); if (v.Length < 10) return null;
  return DateTime.TryParseExact(v[..10], ["dd/MM/yyyy", "dd-MM-yyyy", "yyyy-MM-dd"], CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d : null;
 }

 public static MimeMessage Message(Store s, string to, DateTime today)
 {
  var cfg = s.Mail(); var blocks = Blocks(s, today);
  var monday = today.AddDays(-(((int)today.DayOfWeek + 6) % 7));
  var period = $"settimana dal {monday.ToString("d MMMM", It)} al {monday.AddDays(6).ToString("d MMMM yyyy", It)}";
  var msg = new MimeMessage();
  msg.From.Add(new MailboxAddress(cfg.FromName, cfg.From.Length > 0 ? cfg.From : "noreply@localhost"));
  msg.To.AddRange(Mailer.Addresses(to, 5));
  msg.Subject = $"Riepilogo settimanale Top Cash Studio · {monday.ToString("d MMMM", It)}";
  string E(string x) => WebUtility.HtmlEncode(x);
  var colors = new Dictionary<string, (string fg, string bg)> { ["bad"] = ("#b42318", "#fde7e5"), ["due"] = ("#9a6100", "#fdf1d8"), ["ok"] = ("#127a43", "#dcf4e6"), [""] = ("#475467", "#eef0f4") };
  var html = new StringBuilder();
  html.Append("<!doctype html><html lang=\"it\"><body style=\"margin:0;background:#f3f4f8;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#101828\">");
  html.Append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f3f4f8;padding:24px 12px\"><tr><td align=\"center\">");
  html.Append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:640px\">");
  html.Append($"<tr><td style=\"padding:4px 4px 18px\"><div style=\"font-size:13px;color:#667085\">Top Cash Studio · {E(period)}</div><div style=\"font-size:24px;font-weight:700;margin-top:4px\">Riepilogo settimanale</div></td></tr>");
  foreach (var b in blocks)
  {
   html.Append("<tr><td style=\"background:#ffffff;border-radius:16px;padding:18px 20px;border:1px solid #e4e7ec\">");
   html.Append($"<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td style=\"font-size:16px;font-weight:650\">{E(b.Title)}</td><td align=\"right\" style=\"font-size:26px;font-weight:700\">{E(b.Count)}</td></tr></table>");
   if (b.Note.Length > 0) html.Append($"<div style=\"font-size:13px;color:#667085;margin-top:2px\">{E(b.Note)}</div>");
   if (b.Lines.Count > 0)
   {
    html.Append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:12px\">");
    foreach (var l in b.Lines)
    {
     var (fg, bg) = colors.GetValueOrDefault(l.Tone, colors[""]);
     html.Append($"<tr><td style=\"padding:8px 0;border-top:1px solid #f0f1f4\"><div style=\"font-size:14px;font-weight:600\">{E(l.Main)}</div><div style=\"font-size:12px;color:#667085\">{E(l.Sub)}</div></td>");
     html.Append(l.Tag.Length > 0 ? $"<td align=\"right\" style=\"padding:8px 0 8px 10px;border-top:1px solid #f0f1f4;white-space:nowrap\"><span style=\"display:inline-block;font-size:12px;font-weight:600;color:{fg};background:{bg};border-radius:999px;padding:3px 10px\">{E(l.Tag)}</span></td></tr>" : "<td style=\"border-top:1px solid #f0f1f4\"></td></tr>");
    }
    html.Append("</table>");
   }
   if (b.More.Length > 0) html.Append($"<div style=\"font-size:12px;color:#667085;margin-top:8px\">{E(b.More)}</div>");
   html.Append("</td></tr><tr><td style=\"height:12px\"></td></tr>");
  }
  html.Append("<tr><td style=\"font-size:12px;color:#98a2b3;padding:8px 4px\">Email automatica di ogni lunedì mattina. Si disattiva in Studio → Impostazioni → Riepilogo settimanale.</td></tr>");
  html.Append("</table></td></tr></table></body></html>");
  var text = new StringBuilder($"Riepilogo settimanale Top Cash Studio\n{period}\n\n");
  foreach (var b in blocks)
  {
   text.Append($"{b.Title}: {b.Count}\n"); if (b.Note.Length > 0) text.Append(b.Note + "\n");
   foreach (var l in b.Lines) text.Append($" - {l.Main} · {l.Sub}{(l.Tag.Length > 0 ? " · " + l.Tag : "")}\n");
   if (b.More.Length > 0) text.Append($" {b.More}\n"); text.Append('\n');
  }
  msg.Body = new BodyBuilder { HtmlBody = html.ToString(), TextBody = text.ToString() }.ToMessageBody();
  return msg;
 }

 public static async Task Send(Store s, string to, string who)
 {
  if (!s.MailReady()) throw new InvalidDataException("Invio email non attivo: configuralo prima in Impostazioni → Email rapportini.");
  await Mailer.Send(s.Mail(), Message(s, to, DateTime.Today));
  s.Log("settings", $"Riepilogo settimanale inviato a {to}{(who.Length > 0 ? $" ({who})" : "")}.");
 }

 /// Called every half hour by the server: from Monday 7:00 on, once per week (a server switched off on Monday sends it later).
 public static async Task Tick(Store s, DateTime now)
 {
  var w = s.Weekly();
  if (!w.Enabled || w.To.Length == 0 || !s.MailReady()) return;
  var week = Store.WeekKey(now);
  if (w.LastWeek == week) return;
  if (now.DayOfWeek == DayOfWeek.Monday && now.Hour < 7) return;
  if (DateTimeOffset.TryParse(w.LastTry, out var t) && DateTimeOffset.Now - t < TimeSpan.FromHours(3)) return; // after a failure: retry every 3 hours
  s.SetSetting("weekly", w with { LastTry = DateTimeOffset.Now.ToString("O") });
  try { await Send(s, w.To, ""); s.SetSetting("weekly", s.Weekly() with { LastWeek = week, LastSent = DateTimeOffset.Now.ToString("O"), LastError = "" }); }
  catch (InvalidDataException ex) { s.SetSetting("weekly", s.Weekly() with { LastError = ex.Message }); s.Log("error", "Riepilogo settimanale non inviato: " + ex.Message); }
 }
}
