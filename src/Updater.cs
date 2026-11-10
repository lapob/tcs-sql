using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace TopCash.Licenze;

/// Network updates: when this PC works with the office server, it checks which Windows build the administrator published there,
/// downloads it in the background, verifies it and installs it when the app closes (or at night on the sync station).
/// Nothing is replaced while the app runs: a small script waits for the process to end, copies the new files, and restarts if asked.
public sealed class Updater
{
    public static Version Current => Assembly.GetEntryAssembly()?.GetName().Version ?? new(0, 0, 0);
    public static string CurrentText => Current.ToString(3);
    static string Dir => Paths.Runtime("update");
    static string Pending => Path.Combine(Dir, "pending");
    static string Marker => Path.Combine(Dir, "pending.json");

    sealed record Ready(string Version = "", string Sha256 = "", string Notes = "");

    /// Version downloaded, verified and waiting to be installed (null when there is none or it is not newer than this one).
    public static string? ReadyVersion
    {
        get
        {
            try
            {
                var r = JsonSerializer.Deserialize<Ready>(File.ReadAllText(Marker));
                return r != null && Newer(r.Version) && File.Exists(Path.Combine(Pending, "TopCash.Licenze.exe")) ? r.Version : null;
            }
            catch { return null; }
        }
    }
    public static string ReadyNotes { get { try { return JsonSerializer.Deserialize<Ready>(File.ReadAllText(Marker))?.Notes ?? ""; } catch { return ""; } } }

    static bool Newer(string v) => System.Version.TryParse(v, out var x) && x > Current;

    /// Asks the server; when a newer build is there, downloads and prepares it. Returns the version ready to install, if any.
    public static async Task<string?> Check(string serverUrl, CancellationToken ct = default)
    {
        if (serverUrl.Length == 0) return null;
        var baseUrl = serverUrl.EndsWith('/') ? serverUrl : serverUrl + "/";
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(20) };
        http.DefaultRequestHeaders.Add("X-Requested-With", "topcash");
        using var doc = JsonDocument.Parse(await http.GetStringAsync(baseUrl + "api/update/info", ct));
        if (!doc.RootElement.TryGetProperty("windows", out var w) || w.ValueKind != JsonValueKind.Object) return ReadyVersion;
        var version = w.GetProperty("version").GetString() ?? ""; var sha = w.GetProperty("sha256").GetString() ?? "";
        var notes = w.TryGetProperty("notes", out var n) ? n.GetString() ?? "" : "";
        if (!Newer(version)) return null;
        if (ReadyVersion == version) return version;

        var zip = Path.Combine(Dir, "download.zip");
        await using (var src = await http.GetStreamAsync(baseUrl + "api/update/windows", ct))
        await using (var f = File.Create(zip)) await src.CopyToAsync(f, ct);
        string got; await using (var f = File.OpenRead(zip)) got = Convert.ToHexString(await SHA256.HashDataAsync(f, ct));
        if (!got.Equals(sha, StringComparison.OrdinalIgnoreCase)) { File.Delete(zip); throw new InvalidDataException("Il file scaricato non corrisponde: riprovo più tardi."); }

        try { File.Delete(Marker); } catch { }
        if (Directory.Exists(Pending)) Directory.Delete(Pending, true);
        ZipFile.ExtractToDirectory(zip, Pending);
        File.Delete(zip);
        if (!File.Exists(Path.Combine(Pending, "TopCash.Licenze.exe"))) { Directory.Delete(Pending, true); throw new InvalidDataException("Aggiornamento incompleto sul server."); }
        File.WriteAllText(Marker, JsonSerializer.Serialize(new Ready(version, sha, notes)));
        return version;
    }

    /// Starts the installer script; the caller must close the app right after. With restart the app comes back on its own.
    public static bool Apply(bool restart, bool minimized)
    {
        if (ReadyVersion is null) return false;
        var target = AppContext.BaseDirectory.TrimEnd('\\');
        var exe = Environment.ProcessPath ?? Path.Combine(target, "TopCash.Licenze.exe");
        var log = Path.Combine(Dir, "installa.log");
        var script = Path.Combine(Dir, "installa.cmd");
        var pid = Environment.ProcessId;
        File.WriteAllText(script, string.Join("\r\n",
            "@echo off",
            "setlocal",
            ":wait",
            $"tasklist /FI \"PID eq {pid}\" 2>nul | find \"{pid}\" >nul && (timeout /t 1 /nobreak >nul & goto wait)",
            $"robocopy \"{Pending}\" \"{target}\" /E /R:10 /W:2 /NP /NJH /NJS > \"{log}\"",
            "if %ERRORLEVEL% GEQ 8 goto end",
            $"del /q \"{Marker}\"",
            $"rmdir /s /q \"{Pending}\"",
            ":end",
            restart ? $"start \"\" \"{exe}\"{(minimized ? " --minimized" : "")}" : "",
            "endlocal") + "\r\n");
        Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{script}\"") { CreateNoWindow = true, UseShellExecute = false, WorkingDirectory = Dir });
        return true;
    }
}
