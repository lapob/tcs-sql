using MailKit.Net.Smtp;
using MailKit.Security;
using Microsoft.Data.Sqlite;
using MimeKit;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace TopCash.Licenze;

// ── App settings (key → JSON) + outgoing e-mail for signed intervention reports ───────────────────────
// The SMTP password is encrypted with Windows DPAPI (machine scope + app entropy): it never leaves the server
// in clear, never appears in the archive snapshot and is not readable from a copied database on another PC.

public sealed record MailConfig(string Host = "", int Port = 587, string Security = "starttls", string User = "", string Password = "",
 string From = "", string FromName = "Top Cash Service S.r.l.", string Bcc = "", bool Enabled = false);
/// What the apps may see: everything except the password.
public sealed record MailConfigView(string Host, int Port, string Security, string User, bool HasPassword, string From, string FromName, string Bcc, bool Enabled, bool Ready);
public sealed record MailConfigInput(string? Host, int Port, string? Security, string? User, string? Password, bool ClearPassword, string? From, string? FromName, string? Bcc, bool Enabled);

public sealed partial class Store
{
 static readonly string[] MailSecurity = ["starttls", "ssl", "none"];
 static readonly byte[] MailEntropy = Encoding.UTF8.GetBytes("TopCashStudio.mail.v1");

 void InitSettings(SqliteConnection c)
 {
  using var q = c.CreateCommand(); q.CommandText = "CREATE TABLE IF NOT EXISTS app_settings(key TEXT PRIMARY KEY, data TEXT NOT NULL)"; q.ExecuteNonQuery();
 }

 public T? Setting<T>(string key)
 {
  using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "SELECT data FROM app_settings WHERE key=$k"; q.Parameters.AddWithValue("$k", key);
  return q.ExecuteScalar() is string s ? JsonSerializer.Deserialize<T>(s) : default;
 }
 public void SetSetting<T>(string key, T value)
 {
  using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "INSERT INTO app_settings VALUES($k,$d) ON CONFLICT(key) DO UPDATE SET data=excluded.data";
  q.Parameters.AddWithValue("$k", key); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(value)); q.ExecuteNonQuery();
 }

 public MailConfig Mail() => Setting<MailConfig>("mail") ?? new MailConfig();
 public bool MailReady() { var m = Mail(); return m.Enabled && m.Host.Length > 0 && m.From.Length > 0; }
 public MailConfigView MailView() { var m = Mail(); return new(m.Host, m.Port, m.Security, m.User, m.Password.Length > 0, m.From, m.FromName, m.Bcc, m.Enabled, MailReady()); }

#pragma warning disable CA1416 // the server and the desktop app run on Windows only
 internal static string Protect(string clear) => clear.Length == 0 ? "" : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(clear), MailEntropy, DataProtectionScope.LocalMachine));
 internal static string Unprotect(string stored)
 {
  if (stored.Length == 0) return "";
  try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(stored), MailEntropy, DataProtectionScope.LocalMachine)); }
  catch (CryptographicException) { throw new InvalidDataException("La password dell'email è stata salvata su un altro computer: inseriscila di nuovo nelle impostazioni."); }
 }
#pragma warning restore CA1416

 public MailConfigView SaveMail(MailConfigInput i, string who)
 {
  var old = Mail();
  var host = (i.Host ?? "").Trim().ToLowerInvariant();
  var sec = (i.Security ?? "starttls").Trim().ToLowerInvariant();
  var from = (i.From ?? "").Trim();
  var bcc = (i.Bcc ?? "").Trim();
  if (host.Length > 120 || (host.Length > 0 && !Uri.CheckHostName(host).HasFlag(UriHostNameType.Dns) && Uri.CheckHostName(host) is not (UriHostNameType.IPv4 or UriHostNameType.IPv6)))
   throw new InvalidDataException("Server SMTP non valido (es. smtps.aruba.it).");
  if (i.Port is < 1 or > 65535) throw new InvalidDataException("Porta non valida (di solito 587 o 465).");
  if (!MailSecurity.Contains(sec)) throw new InvalidDataException("Sicurezza non valida.");
  if (from.Length > 0) Mailer.Addresses(from, 1);
  if (bcc.Length > 0) Mailer.Addresses(bcc, 3);
  if (i.Enabled && (host.Length == 0 || from.Length == 0)) throw new InvalidDataException("Per attivare l'invio servono server SMTP e indirizzo mittente.");
  var pwd = i.ClearPassword ? "" : !string.IsNullOrEmpty(i.Password) ? Protect(i.Password) : old.Password;
  if ((i.Password ?? "").Length > 300) throw new InvalidDataException("Password troppo lunga.");
  var m = new MailConfig(host, i.Port, sec, (i.User ?? "").Trim(), pwd, from, string.IsNullOrWhiteSpace(i.FromName) ? "Top Cash Service S.r.l." : i.FromName.Trim()[..Math.Min(80, i.FromName.Trim().Length)], bcc, i.Enabled);
  SetSetting("mail", m);
  Log("settings", $"{who} ha aggiornato le impostazioni email ({(m.Enabled ? "invio attivo" : "invio disattivato")}).");
  return MailView();
 }

 /// Records a successful send on the intervention (shown in the apps) and in the history.
 public Intervention MarkReportSent(string id, string to, string who)
 {
  using var c = Open(); using var tx = c.BeginTransaction();
  Intervention i;
  using (var g = c.CreateCommand()) { g.Transaction = tx; g.CommandText = "SELECT data FROM interventions WHERE id=$i AND source<>'deleted'"; g.Parameters.AddWithValue("$i", id);
   i = g.ExecuteScalar() is string s ? JsonSerializer.Deserialize<Intervention>(s)! : throw new InvalidDataException("Intervento non trovato."); }
  var now = DateTimeOffset.Now.ToString("O");
  var rec = i with { SentTo = to, SentAt = now };
  using (var u = c.CreateCommand()) { u.Transaction = tx; u.CommandText = "UPDATE interventions SET data=$d WHERE id=$i"; u.Parameters.AddWithValue("$d", JsonSerializer.Serialize(rec)); u.Parameters.AddWithValue("$i", id); u.ExecuteNonQuery(); }
  using (var e = c.CreateCommand()) { e.Transaction = tx; e.CommandText = "INSERT INTO events(time,kind,message) VALUES($t,'rapportino',$m)"; e.Parameters.AddWithValue("$t", now); e.Parameters.AddWithValue("$m", $"Rapportino n. {i.ReportNo} inviato a {to} ({who})."); e.ExecuteNonQuery(); }
  tx.Commit(); return rec;
 }
}

public static class Mailer
{
 /// Parses "a@b.it, c@d.it" (also ; separated) into validated addresses.
 public static List<MailboxAddress> Addresses(string text, int max)
 {
  var list = new List<MailboxAddress>();
  foreach (var part in (text ?? "").Split([',', ';', ' ', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
  {
   var at = part.IndexOf('@');
   if (part.Length > 254 || at < 1 || part.LastIndexOf('.') < at + 2 || part.EndsWith('.') || !MailboxAddress.TryParse(part, out var a) || a.Address != part)
    throw new InvalidDataException($"Indirizzo email non valido: {part}");
   if (!list.Any(x => x.Address.Equals(a.Address, StringComparison.OrdinalIgnoreCase))) list.Add(a);
  }
  if (list.Count == 0) throw new InvalidDataException("Scrivi l'indirizzo email del cliente.");
  if (list.Count > max) throw new InvalidDataException($"Al massimo {max} indirizzi.");
  return list;
 }

 /// Builds the e-mail with the signed PDF attached (no network: also used by the self-test).
 public static MimeMessage ReportMessage(Store s, string id, string to)
 {
  var i = s.Interventions().FirstOrDefault(x => x.Id == id) ?? throw new InvalidDataException("Intervento non trovato.");
  if (string.IsNullOrEmpty(i.SignedAt)) throw new InvalidDataException("Si possono inviare solo rapportini firmati dal cliente.");
  var cfg = s.Mail();
  var client = InterventionReport.ResolveClient(s, i.ClientCode, i.ClientName);
  var msg = new MimeMessage();
  msg.From.Add(new MailboxAddress(cfg.FromName, cfg.From.Length > 0 ? cfg.From : "noreply@localhost"));
  msg.To.AddRange(Addresses(to, 5));
  if (cfg.Bcc.Length > 0) msg.Bcc.AddRange(Addresses(cfg.Bcc, 3));
  msg.Subject = $"Rapporto di intervento n. {i.ReportNo} – {InterventionReport.Company}";
  var date = DateTime.TryParse(i.Date, out var d) ? d.ToString("d MMMM yyyy", System.Globalization.CultureInfo.GetCultureInfo("it-IT")) : "";
  var text = $"""
   Gentile {client.Name},

   in allegato il rapporto di intervento n. {i.ReportNo}{(date.Length > 0 ? " del " + date : "")}, firmato da {i.Signer}.
   Oggetto: {i.Title}

   Per qualsiasi chiarimento può rispondere a questa email o chiamarci allo 055 353619.

   Cordiali saluti,
   {InterventionReport.Company}
   Via Ponte alle Mosse 163/a · 50144 Firenze
   """;
  var body = new BodyBuilder { TextBody = text };
  body.Attachments.Add(InterventionReport.FileName(i), InterventionReport.Build(s, id), new ContentType("application", "pdf"));
  msg.Body = body.ToMessageBody();
  return msg;
 }

 public static async Task Send(MailConfig cfg, MimeMessage msg, CancellationToken ct = default)
 {
  if (cfg.Host.Length == 0 || cfg.From.Length == 0) throw new InvalidDataException("Email non configurata: l'amministratore deve impostarla in Impostazioni → Email rapportini.");
  using var smtp = new SmtpClient { Timeout = 30000 };
  try
  {
   var sec = cfg.Security switch { "ssl" => SecureSocketOptions.SslOnConnect, "none" => SecureSocketOptions.None, _ => SecureSocketOptions.StartTls };
   await smtp.ConnectAsync(cfg.Host, cfg.Port, sec, ct);
   if (cfg.User.Length > 0) await smtp.AuthenticateAsync(cfg.User, Store.Unprotect(cfg.Password), ct);
   await smtp.SendAsync(msg, ct);
   await smtp.DisconnectAsync(true, ct);
  }
  catch (AuthenticationException) { throw new InvalidDataException("Il server email ha rifiutato utente o password: controllali in Impostazioni → Email rapportini."); }
  catch (SslHandshakeException) { throw new InvalidDataException("Connessione sicura non riuscita: prova l'altra impostazione di sicurezza (SSL/TLS su porta 465 oppure STARTTLS su porta 587)."); }
  catch (SmtpCommandException ex) { throw new InvalidDataException("Il server email ha rifiutato il messaggio: " + ex.Message); }
  catch (Exception ex) when (ex is System.Net.Sockets.SocketException or SmtpProtocolException or IOException or TimeoutException or OperationCanceledException)
  { throw new InvalidDataException($"Server email {cfg.Host}:{cfg.Port} non raggiungibile ({ex.Message})."); }
 }

 /// Sends the signed report and records it. Returns the updated intervention.
 public static async Task<Intervention> SendReport(Store s, string id, string to, string who)
 {
  if (!s.MailReady()) throw new InvalidDataException("Invio email non attivo: l'amministratore deve configurarlo in Impostazioni → Email rapportini.");
  var msg = ReportMessage(s, id, to);
  await Send(s.Mail(), msg);
  return s.MarkReportSent(id, string.Join(", ", msg.To.Mailboxes.Select(m => m.Address)), who);
 }

 public static async Task SendTest(Store s, string to, string who)
 {
  var cfg = s.Mail();
  var msg = new MimeMessage();
  msg.From.Add(new MailboxAddress(cfg.FromName, cfg.From.Length > 0 ? cfg.From : "noreply@localhost"));
  msg.To.AddRange(Addresses(to, 1));
  msg.Subject = "Prova invio – Top Cash Studio";
  msg.Body = new TextPart("plain") { Text = $"Questa è un'email di prova inviata da Top Cash Studio ({who}, {DateTime.Now:dd/MM/yyyy HH:mm}).\nSe la ricevi, l'invio dei rapportini ai clienti funziona." };
  await Send(cfg, msg);
  s.Log("settings", $"{who} ha inviato un'email di prova a {to}.");
 }
}
