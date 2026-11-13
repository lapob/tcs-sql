using Microsoft.Data.Sqlite;

namespace TopCash.Licenze;
/// How each person likes the lists (order and filters per page), kept on the server so the same choice is there on the
/// site, the Windows app and the phone. Only presentation: never data, never shared between people.
public sealed partial class Store
{
 public const int MaxPrefsBytes = 16_000;
 static void InitPrefs(SqliteConnection c)
 {
  using var q = c.CreateCommand(); q.CommandText = "CREATE TABLE IF NOT EXISTS user_prefs(username TEXT PRIMARY KEY, data TEXT NOT NULL, at TEXT NOT NULL)"; q.ExecuteNonQuery();
 }
 /// The saved choices of [user] (a JSON object, "{}" when none) and when they were saved.
 public (string Json, string At) Prefs(string user)
 {
  using var c = Open(); using var q = c.CreateCommand();
  q.CommandText = "SELECT data,at FROM user_prefs WHERE username=$u"; q.Parameters.AddWithValue("$u", (user ?? "").Trim().ToLowerInvariant());
  using var r = q.ExecuteReader(); return r.Read() ? (r.GetString(0), r.GetString(1)) : ("{}", "");
 }
 public string SavePrefs(string user, string json)
 {
  var key = (user ?? "").Trim().ToLowerInvariant(); if (key.Length == 0) throw new InvalidDataException("Utente sconosciuto.");
  if (string.IsNullOrWhiteSpace(json) || json.Length > MaxPrefsBytes) throw new InvalidDataException("Preferenze troppo grandi.");
  using (var d = System.Text.Json.JsonDocument.Parse(json)) if (d.RootElement.ValueKind != System.Text.Json.JsonValueKind.Object) throw new InvalidDataException("Preferenze non valide.");
  var at = DateTimeOffset.Now.ToString("O");
  using var c = Open(); using var q = c.CreateCommand();
  q.CommandText = "INSERT INTO user_prefs VALUES($u,$d,$a) ON CONFLICT(username) DO UPDATE SET data=$d,at=$a";
  q.Parameters.AddWithValue("$u", key); q.Parameters.AddWithValue("$d", json); q.Parameters.AddWithValue("$a", at); q.ExecuteNonQuery();
  return at;
 }
}
