using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace TopCash.Licenze;

/// Updates: the app looks for a newer Windows build (office server, or the data folder on the PC that holds the data) and
/// shows it; one tap downloads, verifies and installs it with a restart (the sync station does it by itself at night).
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

    /// A newer build published for this PC: what it is and how to read it (from the office server, or from the data folder).
    public sealed record Offer(string Version, string Sha256, string Notes, long Size, Func<CancellationToken, Task<Stream>> Open);
    static readonly HttpClient http = MakeClient();
    static HttpClient MakeClient() { var c = new HttpClient { Timeout = TimeSpan.FromMinutes(20) }; c.DefaultRequestHeaders.Add("X-Requested-With", "topcash"); return c; }

    /// Only looks: never downloads. With an office server it asks it; on the PC that holds the data it reads the build
    /// the release left in data\aggiornamenti. Null when this PC already has the latest version.
    public static async Task<Offer?> Find(string serverUrl, string dataFolder, CancellationToken ct = default)
    {
        if (serverUrl.Length > 0)
        {
            var baseUrl = serverUrl.EndsWith('/') ? serverUrl : serverUrl + "/";
            using var doc = JsonDocument.Parse(await http.GetStringAsync(baseUrl + "api/update/info", ct));
            if (!doc.RootElement.TryGetProperty("windows", out var w) || w.ValueKind != JsonValueKind.Object) return null;
            var o = Read(w, false, c => http.GetStreamAsync(baseUrl + "api/update/windows", c));
            return o != null && Newer(o.Version) ? o : null;
        }
        var dir = Path.Combine(dataFolder, "aggiornamenti"); var info = Path.Combine(dir, "versioni.json"); var file = Path.Combine(dir, "TopCash-Studio-Windows.zip");
        if (!File.Exists(info) || !File.Exists(file)) return null;
        using (var doc = JsonDocument.Parse(await File.ReadAllTextAsync(info, ct)))
        {
            if (!doc.RootElement.TryGetProperty("Windows", out var w) || w.ValueKind != JsonValueKind.Object) return null;
            var o = Read(w, true, _ => Task.FromResult<Stream>(File.OpenRead(file)));
            return o != null && Newer(o.Version) ? o : null;
        }
    }
    static Offer? Read(JsonElement w, bool pascal, Func<CancellationToken, Task<Stream>> open)
    {
        string S(string k) => w.TryGetProperty(pascal ? char.ToUpperInvariant(k[0]) + k[1..] : k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
        var size = w.TryGetProperty(pascal ? "Size" : "size", out var z) && z.TryGetInt64(out var n) ? n : 0;
        var version = S("version");
        return version.Length == 0 ? null : new Offer(version, S("sha256"), S("notes"), size, open);
    }

    /// Downloads, verifies (SHA-256) and unpacks the offer, reporting 0..1 as it goes. Returns the version ready to install.
    public static async Task<string> Download(Offer o, IProgress<double>? progress = null, CancellationToken ct = default)
    {
        if (ReadyVersion == o.Version) { progress?.Report(1); return o.Version; }
        var zip = Path.Combine(Dir, "download.zip");
        await using (var src = await o.Open(ct))
        await using (var f = File.Create(zip))
        {
            var buf = new byte[1 << 16]; long done = 0; int r; var last = DateTime.MinValue;
            while ((r = await src.ReadAsync(buf, ct)) > 0)
            {
                await f.WriteAsync(buf.AsMemory(0, r), ct); done += r;
                if (o.Size > 0 && (DateTime.UtcNow - last).TotalMilliseconds > 120) { last = DateTime.UtcNow; progress?.Report(Math.Min(0.96, done * 0.96 / o.Size)); }
            }
        }
        string got; await using (var f = File.OpenRead(zip)) got = Convert.ToHexString(await SHA256.HashDataAsync(f, ct));
        if (!got.Equals(o.Sha256, StringComparison.OrdinalIgnoreCase)) { File.Delete(zip); throw new InvalidDataException("Il file scaricato non corrisponde: riprova tra poco."); }

        try { File.Delete(Marker); } catch { }
        if (Directory.Exists(Pending)) Directory.Delete(Pending, true);
        ZipFile.ExtractToDirectory(zip, Pending);
        File.Delete(zip);
        if (!File.Exists(Path.Combine(Pending, "TopCash.Licenze.exe"))) { Directory.Delete(Pending, true); throw new InvalidDataException("Aggiornamento incompleto: riprova tra poco."); }
        File.WriteAllText(Marker, JsonSerializer.Serialize(new Ready(o.Version, o.Sha256, o.Notes)));
        progress?.Report(1);
        return o.Version;
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
