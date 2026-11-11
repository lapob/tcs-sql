using Microsoft.Data.Sqlite;

namespace TopCash.Licenze;

// ── Automatic backups: daily local copy (data\backups) + a second copy on an external/USB folder ─────────
// The local backup is an online SQLite backup (no downtime), checked with PRAGMA quick_check before being
// copied outside. If the external drive is missing the copy is retried every hour and the apps show a warning.

public sealed record BackupConfig(string External = Backups.DefaultExternal, int KeepLocal = 30, int KeepExternal = 120);
public sealed record BackupStatus(string LastLocal = "", string LastExternal = "", string LastExternalFile = "", string Error = "", string ErrorAt = "");

public static class Backups
{
 public const string DefaultExternal = @"H:\TcsSqlOfflineBackups";
 static readonly SemaphoreSlim Gate = new(1, 1);

 public static BackupConfig Config(Store s) => s.Setting<BackupConfig>("backup") ?? new BackupConfig();
 public static BackupStatus Status(Store s) => s.Setting<BackupStatus>("backup.status") ?? new BackupStatus();

 public static BackupConfig Save(Store s, string? external, string who)
 {
  var path = (external ?? "").Trim().TrimEnd('\\');
  if (path.Length > 0)
  {
   if (path.Length > 240 || !Path.IsPathFullyQualified(path) || path.IndexOfAny(Path.GetInvalidPathChars()) >= 0 || path.StartsWith(@"\\?\"))
    throw new InvalidDataException(@"Percorso non valido: scrivi una cartella completa, es. H:\TcsSqlOfflineBackups o \\NAS\backup.");
  }
  var cfg = Config(s) with { External = path };
  s.SetSetting("backup", cfg);
  s.Log("settings", path.Length > 0 ? $"{who} ha impostato la copia esterna dei backup in {path}." : $"{who} ha disattivato la copia esterna dei backup.");
  return cfg;
 }

 /// One backup per day (or a new one when [now] is true). Returns the status after the run.
 public static BackupStatus Run(Store s, string dataDir, bool now = false, string who = "")
 {
  Gate.Wait();
  try
  {
   var cfg = Config(s); var st = Status(s);
   var dir = Path.Combine(dataDir, "backups"); Directory.CreateDirectory(dir);
   var day = DateTime.Now.ToString("yyyyMMdd");
   var name = now ? $"TopCash-{day}-{DateTime.Now:HHmmss}.sqlite" : $"TopCash-{day}.sqlite";
   var local = Path.Combine(dir, name);
   // today's backup is renewed (at most hourly, by the timer) when the archive changed after it: an import made
   // in the afternoon reaches the backups the same day instead of the next night
   var exists = File.Exists(local);
   var refresh = !now && exists && (lastStamp.Db != s.DatabasePath ? Stamp(s) > File.GetLastWriteTimeUtc(local).AddMinutes(2) : Stamp(s) != lastStamp.At);
   if (!exists || refresh)
   {
    var tmp = local + ".new";   // the old copy stays until the new one is verified
    try { s.Backup(tmp); SqliteConnection.ClearAllPools(); Check(tmp); File.Move(tmp, local, true); }
    finally { if (File.Exists(tmp)) File.Delete(tmp); }
    foreach (var old in new DirectoryInfo(dir).GetFiles("TopCash-*.sqlite").OrderByDescending(f => f.Name).Skip(Math.Max(7, cfg.KeepLocal))) old.Delete();
    st = st with { LastLocal = DateTimeOffset.Now.ToString("O") };
    if (!refresh) s.Log("backup", now ? $"Backup creato da {who}." : "Backup automatico giornaliero creato.");
   }
   if (cfg.External.Length > 0)
   {
    var target = Path.Combine(cfg.External, name);
    if (!File.Exists(target) || now || refresh)
    {
     try
     {
      var root = Path.GetPathRoot(cfg.External);
      if (!string.IsNullOrEmpty(root) && !root.StartsWith(@"\\") && !Directory.Exists(root)) throw new IOException($"il disco {root.TrimEnd('\\')} non è collegato");
      Directory.CreateDirectory(cfg.External);
      var tmp = target + ".part";
      File.Copy(local, tmp, true);
      if (new FileInfo(tmp).Length != new FileInfo(local).Length) throw new IOException("copia incompleta");
      File.Move(tmp, target, true);
      foreach (var old in new DirectoryInfo(cfg.External).GetFiles("TopCash-*.sqlite").OrderByDescending(f => f.Name).Skip(Math.Max(7, cfg.KeepExternal))) old.Delete();
      var hadError = st.Error.Length > 0;
      st = st with { LastExternal = DateTimeOffset.Now.ToString("O"), LastExternalFile = target, Error = "", ErrorAt = "" };
      if (hadError || !refresh) s.Log("backup", hadError ? $"Copia esterna dei backup di nuovo riuscita ({cfg.External})." : $"Backup copiato in {cfg.External}.");
     }
     catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
     {
      var msg = $"Copia esterna non riuscita in {cfg.External}: {ex.Message}.";
      if (st.Error != msg) s.Log("error", msg + " Riprovo ogni ora.");
      st = st with { Error = msg, ErrorAt = DateTimeOffset.Now.ToString("O") };
     }
    }
    else if (st.Error.Length > 0) st = st with { Error = "", ErrorAt = "", LastExternalFile = target }; // today's copy is already there (folder reachable again)
   }
   else if (st.Error.Length > 0) st = st with { Error = "", ErrorAt = "" };
   s.SetSetting("backup.status", st);
   lastStamp = (s.DatabasePath, Stamp(s));   // taken after our own status write, so it alone never looks like a change
   return st;
  }
  finally { Gate.Release(); }
 }

 static (string Db, DateTime At) lastStamp;
 /// Last write to the archive (main file or its WAL journal).
 static DateTime Stamp(Store s)
 {
  var t = File.GetLastWriteTimeUtc(s.DatabasePath); var wal = s.DatabasePath + "-wal";
  if (File.Exists(wal)) { var w = File.GetLastWriteTimeUtc(wal); if (w > t) t = w; }
  return t;
 }

 /// A backup that SQLite cannot read is worse than none: verify it before trusting it.
 static void Check(string file)
 {
  using var c = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = file, Mode = SqliteOpenMode.ReadOnly, Pooling = false }.ToString());
  c.Open(); using var q = c.CreateCommand(); q.CommandText = "PRAGMA quick_check"; var r = q.ExecuteScalar() as string;
  if (r != "ok") { c.Close(); File.Delete(file); throw new IOException("backup danneggiato: " + r); }
 }
}
