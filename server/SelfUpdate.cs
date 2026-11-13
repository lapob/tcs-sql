using System.Diagnostics;

namespace TopCash.Server;

/// Lets the installed server take a new build by itself. The swap is done by the scheduled task created by
/// aggiorna-server.ps1 -Installa (it runs as SYSTEM, stops this service, copies, restarts and rolls back if needed);
/// here the server only notices a newer build in ..\server-update and starts that task at once.
public sealed class SelfUpdate(string baseDir)
{
    const string TaskName = "Top Cash Studio - Aggiornamento server";
    readonly string root = Path.GetFullPath(Path.Combine(baseDir, ".."));
    string Incoming => Path.Combine(root, "server-update", "TopCash.Server.exe");
    string Runtime(string f) => Path.Combine(root, ".runtime", f);
    readonly Version mine = typeof(SelfUpdate).Assembly.GetName().Version ?? new Version(0, 0);
    string asked = "", lastSign = "";
    DateTime askedAt = DateTime.MinValue;
    // when this server first saw the waiting build (the files keep their build time, not the time they arrived)
    string seenVer = ""; DateTime seenAt = DateTime.MinValue;

    static Version? FileVersion(string f)
    {
        try { return File.Exists(f) && Version.TryParse(FileVersionInfo.GetVersionInfo(f).FileVersion, out var v) ? v : null; }
        catch { return null; }
    }

    public void Tick()
    {
        try
        {
            var v = FileVersion(Incoming);
            if (v == null || v <= mine) return;
            if (seenVer != v.ToString()) { seenVer = v.ToString(); seenAt = DateTime.Now; }
            // the build must be complete: the folder has to look the same on two rounds in a row (a copy in progress changes it)
            var files = new DirectoryInfo(Path.GetDirectoryName(Incoming)!).EnumerateFiles("*", SearchOption.AllDirectories).ToList();
            var sign = $"{files.Count}|{files.Sum(f => f.Length)}|{files.Max(f => f.LastWriteTimeUtc.Ticks)}";
            if (sign != lastSign) { lastSign = sign; return; }
            // a build that did not start is not retried (the task already went back to the previous one)
            var bad = Runtime("server-scartata.txt");
            if (File.Exists(bad) && File.ReadAllText(bad).Trim() == v.ToString()) return;
            if (asked == v.ToString() && DateTime.UtcNow - askedAt < TimeSpan.FromMinutes(15)) return;
            asked = v.ToString(); askedAt = DateTime.UtcNow;
            if (!OperatingSystem.IsWindows()) return;
            using var p = Process.Start(new ProcessStartInfo("schtasks.exe", $"/Run /TN \"{TaskName}\"") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true });
            p?.WaitForExit(15000);
        }
        catch { }
    }

    /// A newer build the update task tried and put back (it is not retried until the owner asks): its version, or "".
    public string Failed()
    {
        try
        {
            var f = Runtime("server-scartata.txt");
            if (!File.Exists(f)) return "";
            return Version.TryParse(File.ReadAllText(f).Trim().TrimStart('\uFEFF'), out var v) && v > mine ? v.ToString(3) : "";
        }
        catch { return ""; }
    }
    /// Why the last update went back, in the words of the update log (the file that could not be copied, a server that did not answer).
    string FailReason()
    {
        try
        {
            var f = Runtime("aggiorna-server.log"); if (!File.Exists(f)) return "";
            using var s = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            var lines = new StreamReader(s).ReadToEnd().Split('\n').Select(l => l.Trim()).Where(l => l.Length > 20).ToList();
            var start = lines.FindLastIndex(l => l.Contains("Aggiorno il server"));
            var why = lines.Skip(Math.Max(0, start)).Where(l => l.Contains("Copia:") || l.Contains("non risponde") || l.Contains("non riuscita")).ToList();
            return why.Count == 0 ? "" : why[0][20..].Trim();
        }
        catch { return ""; }
    }
    /// The owner asks to try the refused build again: the mark is removed and the update task is started at once.
    public bool Retry()
    {
        var v = FileVersion(Incoming);
        if (v == null || v <= mine) return false;
        try { File.Delete(Runtime("server-scartata.txt")); } catch { return false; }
        asked = ""; lastSign = "";
        Tick(); Tick(); // two looks in a row: the build is complete and the task starts now
        return true;
    }

    public object Status()
    {
        string last = "", lastLine = "";
        try { var f = Runtime("aggiorna-server.ultimo"); if (File.Exists(f)) last = File.ReadAllText(f).Trim(); } catch { }
        try
        {
            var f = Runtime("aggiorna-server.log");
            if (File.Exists(f))
            {
                using var s = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                lastLine = new StreamReader(s).ReadToEnd().Split('\n').Select(l => l.Trim()).LastOrDefault(l => l.Length > 0) ?? "";
            }
        }
        catch { }
        var waiting = FileVersion(Incoming) is { } v && v > mine ? v.ToString(3) : "";
        // the scheduled task leaves its mark every 10 minutes: older than 30 minutes means it is not running
        var active = DateTime.TryParse(last, out var at) && DateTime.Now - at < TimeSpan.FromMinutes(30);
        var failed = Failed();
        // a newer build left waiting for over 40 minutes means the update task does not start: worth telling the owner
        var stuck = false;
        stuck = waiting.Length > 0 && failed.Length == 0 && seenAt != DateTime.MinValue && DateTime.Now - seenAt > TimeSpan.FromMinutes(40);
        return new { version = mine.ToString(3), active, lastCheck = last, lastEvent = lastLine, waiting, failed, reason = failed.Length > 0 ? FailReason() : "", stuck };
    }
}
