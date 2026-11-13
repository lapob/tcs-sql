using Microsoft.Data.Sqlite;
using System.Globalization;
using System.Text.Json;

namespace TopCash.Licenze;

/// One telematic cash register (RT) as shown in DTR-Service → RT-Utility → Registratori telematici. Read-only copy.
public sealed record DtrRegister(string Serial, string Tags, string Client, string Alias, string Firmware,
    int GeNumber, string GeDue, int LastZ, string LastSync, string CapturedAt, DtrDetail? Detail = null);

/// Detail page of a register (RT-Utility → info): holder and VAT, certificate, periodic check, licences, GE extensions,
/// recent errors and technical interventions. Read on a rolling basis, kept apart so a list refresh never drops it.
public sealed record DtrDetail(string Serial, string Holder = "", string Vat = "", string CertDue = "", string LastVp = "",
    string GeInit = "", List<DtrLicense>? Licenses = null, List<DtrEvent>? Errors = null, List<DtrEvent>? Works = null,
    string Extensions = "", string ReadAt = "", string Warning = "", string Address = "");
public sealed record DtrLicense(string Client = "", string From = "", string To = "", string Status = "", string Name = "", string Type = "", string AutoRenew = "");
public sealed record DtrEvent(string At = "", string Text = "");

/// Connection state of the DTR-Service session kept by the sync station (shown in Studio).
public sealed record DtrStatus(bool Connected = false, string Message = "", string LastSync = "", int Count = 0,
    int PortalTotal = 0, string CheckedAt = "", string Error = "", string ErrorAt = "");

public sealed partial class Store
{
    /// Fixed column order sent by the DTR portal window (headers travel with the rows and are checked).
    public static readonly string[] DtrHeaders = ["Matricola", "Tag", "Cliente", "Insegna", "Firmware", "GE", "Scadenza GE", "Numero Z", "Ultima comunicazione"];

    void InitDtr(SqliteConnection c)
    {
        using var q = c.CreateCommand();
        q.CommandText = "CREATE TABLE IF NOT EXISTS dtr_registers(serial TEXT PRIMARY KEY, data TEXT NOT NULL);CREATE TABLE IF NOT EXISTS dtr_details(serial TEXT PRIMARY KEY, data TEXT NOT NULL)";
        q.ExecuteNonQuery();
    }

    public List<DtrRegister> DtrRegisters()
    {
        var details = DtrDetails();
        using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "SELECT data FROM dtr_registers ORDER BY serial";
        using var r = q.ExecuteReader(); var list = new List<DtrRegister>();
        while (r.Read()) { var d = JsonSerializer.Deserialize<DtrRegister>(r.GetString(0)); if (d != null) list.Add(details.TryGetValue(d.Serial, out var x) ? d with { Detail = x } : d); }
        return list;
    }

    public Dictionary<string, DtrDetail> DtrDetails()
    {
        using var c = Open(); using var q = c.CreateCommand(); q.CommandText = "SELECT data FROM dtr_details";
        using var r = q.ExecuteReader(); var map = new Dictionary<string, DtrDetail>(StringComparer.OrdinalIgnoreCase);
        while (r.Read()) { var d = JsonSerializer.Deserialize<DtrDetail>(r.GetString(0)); if (d != null) map[d.Serial] = d; }
        return map;
    }

    /// Rows: [serial, detail JSON]. Each detail is validated and capped; unknown serials are ignored.
    public string ApplyDtrDetails(List<string> headers, List<List<string>> rows, string capturedAt)
    {
        if (headers.Count != 2 || headers[0] != "Matricola" || headers[1] != "Dettaglio") throw new InvalidDataException("Formato dettagli DTR inatteso.");
        int n = 0; using var c = Open(); using var tx = c.BeginTransaction();
        foreach (var row in rows)
        {
            if (row.Count != 2) continue;
            DtrDetail? d; try { d = JsonSerializer.Deserialize<DtrDetail>(row[1]); } catch (JsonException) { continue; }
            if (d == null || !string.Equals(d.Serial, row[0].Trim(), StringComparison.OrdinalIgnoreCase)) continue;
            d = d with
            {
                Holder = Trim(d.Holder, 200), Vat = Trim(d.Vat, 20), CertDue = IsoDate(d.CertDue), LastVp = DateTimeOffset.TryParse(d.LastVp, CultureInfo.InvariantCulture, DateTimeStyles.None, out var vp) ? vp.ToLocalTime().ToString("yyyy-MM-dd") : IsoDate(d.LastVp), GeInit = IsoDate(d.GeInit),
                Extensions = Trim(d.Extensions, 1000), Warning = Trim(d.Warning, 400), Address = Trim(d.Address, 300), ReadAt = capturedAt,
                Licenses = (d.Licenses ?? []).Take(30).Select(l => new DtrLicense(Trim(l.Client, 200), IsoDate(l.From), IsoDate(l.To), Trim(l.Status, 40), Trim(l.Name, 80), Trim(l.Type, 60), Trim(l.AutoRenew, 20))).ToList(),
                Errors = (d.Errors ?? []).Take(20).Select(e => new DtrEvent(IsoDateTime(e.At), Trim(e.Text, 400))).ToList(),
                Works = (d.Works ?? []).Take(20).Select(e => new DtrEvent(IsoDateTime(e.At), Trim(e.Text, 400))).ToList(),
            };
            using var q = c.CreateCommand(); q.Transaction = tx;
            q.CommandText = "INSERT INTO dtr_details(serial,data) VALUES($s,$d) ON CONFLICT(serial) DO UPDATE SET data=excluded.data";
            q.Parameters.AddWithValue("$s", d.Serial); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(d)); q.ExecuteNonQuery(); n++;
        }
        tx.Commit();
        return $"DTR-Service: {n} schede di dettaglio";
    }

    public DtrStatus DtrState() => Setting<DtrStatus>("dtr.status") ?? new DtrStatus();

    /// Session heartbeat from the sync station: connected / signed out, with a short message.
    public void DtrHeartbeat(bool connected, string message, int portalTotal = 0)
    {
        var s = DtrState(); var now = DateTimeOffset.Now.ToString("O");
        var wasConnected = s.Connected;
        s = s with { Connected = connected, Message = Trim(message, 300), CheckedAt = now, PortalTotal = portalTotal > 0 ? portalTotal : s.PortalTotal };
        if (connected && s.Error.StartsWith("Accesso", StringComparison.Ordinal)) s = s with { Error = "", ErrorAt = "" };
        if (!connected) s = s with { Error = "Accesso a DTR-Service richiesto: ricollegalo da Sincronizzazione → Portali e accessi.", ErrorAt = now };
        SetSetting("dtr.status", s);
        if (wasConnected && !connected) Log("error", "DTR-Service: sessione scaduta, serve un nuovo accesso (Sincronizzazione → Portali e accessi).");
    }

    /// Replaces the whole register list atomically. Only a complete read reaches this point (the portal window checks the total).
    public string ApplyDtr(List<string> headers, List<List<string>> rows, string capturedAt, string source)
    {
        if (!headers.SequenceEqual(DtrHeaders)) throw new InvalidDataException("Colonne DTR inattese: aggiorna Top Cash Studio.");
        if (rows.Count == 0) throw new InvalidDataException("Elenco DTR vuoto: dati precedenti conservati.");
        var list = new Dictionary<string, DtrRegister>(StringComparer.OrdinalIgnoreCase);
        foreach (var row in rows)
        {
            if (row.Count != DtrHeaders.Length) throw new InvalidDataException("Riga DTR incompleta.");
            var serial = row[0].Trim();
            if (serial.Length is < 6 or > 40) continue;
            if (!list.ContainsKey(serial)) list[serial] = new DtrRegister(serial, Trim(row[1], 200), Trim(row[2], 200), Trim(row[3], 200), Trim(row[4], 30),
                Int(row[5]), IsoDate(row[6]), Int(row[7]), IsoDateTime(row[8]), capturedAt);
        }
        if (list.Count == 0) throw new InvalidDataException("Nessuna matricola valida nell'elenco DTR.");
        using var c = Open(); using var tx = c.BeginTransaction();
        using (var del = c.CreateCommand()) { del.Transaction = tx; del.CommandText = "DELETE FROM dtr_registers"; del.ExecuteNonQuery(); }
        using (var ins = c.CreateCommand())
        {
            ins.Transaction = tx; ins.CommandText = "INSERT INTO dtr_registers(serial,data) VALUES($s,$d)";
            var ps = ins.Parameters.Add("$s", SqliteType.Text); var pd = ins.Parameters.Add("$d", SqliteType.Text);
            foreach (var r in list.Values) { ps.Value = r.Serial; pd.Value = JsonSerializer.Serialize(r); ins.ExecuteNonQuery(); }
        }
        LogTx(c, tx, "portal", $"DTR-Service: {list.Count} registratori telematici aggiornati ({source}).");
        tx.Commit();
        SetSetting("dtr.status", DtrState() with { Connected = true, LastSync = capturedAt, Count = list.Count, Error = "", ErrorAt = "" });
        return $"DTR-Service: {list.Count} registratori";
    }

    static string Trim(string? s, int max) { s = (s ?? "").Replace('\n', ' ').Trim(); while (s.Contains("  ")) s = s.Replace("  ", " "); return s.Length > max ? s[..max] : s; }
    static int Int(string? s) => int.TryParse(new string((s ?? "").Where(char.IsDigit).ToArray()), out var n) ? n : 0;
    static string IsoDate(string? s) => DateTime.TryParseExact((s ?? "").Trim(), "dd/MM/yyyy", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d.ToString("yyyy-MM-dd") : "";
    static string IsoDateTime(string? s) => DateTime.TryParseExact((s ?? "").Trim(), ["dd/MM/yyyy HH:mm", "dd/MM/yyyy HH:mm:ss"], CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d.ToString("yyyy-MM-ddTHH:mm") : "";
}
