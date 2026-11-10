using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace TopCash.Server;

/// One published build of an app, kept on the server so every PC and phone of the team can update itself.
public sealed record AppBuild(string Version = "", long Size = 0, string Sha256 = "", string UploadedAt = "", string UploadedBy = "", string Notes = "");
public sealed record UpdateInfo(AppBuild? Windows = null, AppBuild? Android = null);

/// Builds uploaded by an administrator (Windows: the release zip with TopCash.Licenze.exe and its ui folder; Android: the APK).
/// They are served to the team's devices; the files are the same ones published in the GitHub release, nothing secret.
public sealed class Updates(string dataDir)
{
    readonly string dir = Directory.CreateDirectory(Path.Combine(dataDir, "aggiornamenti")).FullName;
    string InfoPath => Path.Combine(dir, "versioni.json");
    public string FilePath(string kind) => Path.Combine(dir, kind == "android" ? "TopCash-Studio.apk" : "TopCash-Studio-Windows.zip");
    readonly Lock gate = new();

    public UpdateInfo Info()
    {
        lock (gate) { try { return JsonSerializer.Deserialize<UpdateInfo>(File.ReadAllText(InfoPath)) ?? new(); } catch { return new(); } }
    }

    public static bool ValidVersion(string v) => Regex.IsMatch(v, @"^\d{1,3}\.\d{1,3}\.\d{1,4}$");

    /// Stores an uploaded build after checking it really is what it says (zip with the Studio executable, or an APK).
    public async Task<AppBuild> Save(string kind, string version, string notes, Stream body, string who)
    {
        if (kind is not ("windows" or "android")) throw new InvalidDataException("Tipo di aggiornamento sconosciuto.");
        version = version.Trim().TrimStart('v', 'V');
        if (!ValidVersion(version)) throw new InvalidDataException("Versione non valida: usa il formato 2.7.0.");
        var tmp = FilePath(kind) + ".upload";
        try
        {
            await using (var f = File.Create(tmp)) await body.CopyToAsync(f);
            var size = new FileInfo(tmp).Length;
            if (size < 100_000) throw new InvalidDataException("File troppo piccolo: non sembra un aggiornamento di Top Cash Studio.");
            ZipArchive z;
            try { z = ZipFile.OpenRead(tmp); } // an APK is a zip too
            catch (InvalidDataException) { throw new InvalidDataException(kind == "android" ? "Non è un file APK valido." : "Non è uno zip valido: carica il file TopCash-Studio-Windows-x.y.z.zip della release."); }
            using (z)
            {
                if (kind == "windows" && !z.Entries.Any(e => e.FullName.Equals("TopCash.Licenze.exe", StringComparison.OrdinalIgnoreCase)))
                    throw new InvalidDataException("Nello zip manca TopCash.Licenze.exe: carica il file TopCash-Studio-Windows-x.y.z.zip della release.");
                if (kind == "android" && !z.Entries.Any(e => e.FullName == "AndroidManifest.xml"))
                    throw new InvalidDataException("Non è un file APK valido.");
            }
            string sha; await using (var f = File.OpenRead(tmp)) sha = Convert.ToHexString(await SHA256.HashDataAsync(f));
            var build = new AppBuild(version, size, sha, DateTimeOffset.Now.ToString("O"), who, notes.Trim().Length > 500 ? notes.Trim()[..500] : notes.Trim());
            lock (gate)
            {
                File.Move(tmp, FilePath(kind), true);
                var info = Info();
                info = kind == "android" ? info with { Android = build } : info with { Windows = build };
                File.WriteAllText(InfoPath, JsonSerializer.Serialize(info));
            }
            return build;
        }
        catch (InvalidDataException) { try { File.Delete(tmp); } catch { } throw; }
        catch (Exception ex) when (ex is IOException or InvalidDataException or UnauthorizedAccessException) { try { File.Delete(tmp); } catch { } throw new InvalidDataException("File non leggibile: " + ex.Message); }
    }
}
