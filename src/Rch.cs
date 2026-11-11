using Microsoft.Data.Sqlite;
using System.Text.Json;

namespace TopCash.Licenze;

/// One device of RCH XDealer (dealer area → Dispositivi), read through the portal's own JSON API. Read-only copy.
/// Dates are yyyy-MM-dd ("" when the portal has none); DgfeFree is the % of free electronic journal (-1 = unknown).
public sealed record RchDevice(string Serial, string PortalId = "", string Part = "", string Type = "", string State = "",
    string Client = "", string Vat = "", string City = "", string Province = "", string Phone = "", string Email = "", string Note = "", string Labels = "",
    bool Contract = false, string Update = "", string Firmware = "", int DgfeFree = -1, int DgfeNumber = 0,
    string LastVerification = "", string ZDate = "", int ZNumber = 0, string CertFrom = "", string CertDue = "",
    string Activated = "", string Registered = "", string CapturedAt = "");

public sealed partial class Store
{
    public static readonly string[] RchHeaders = ["Matricola", "Dati"];

    void InitRch(SqliteConnection c)
    {
        using var q = c.CreateCommand();
        q.CommandText = "CREATE TABLE IF NOT EXISTS rch_devices(serial TEXT PRIMARY KEY, data TEXT NOT NULL)";
        q.ExecuteNonQuery();
    }

    public List<RchDevice> RchDevices() => ItalRows<RchDevice>("rch_devices", "serial");
    public DtrStatus RchState() => Setting<DtrStatus>("rch.status") ?? new DtrStatus();

    public void RchHeartbeat(bool connected, string message, int portalTotal = 0)
    {
        var s = RchState(); var now = DateTimeOffset.Now.ToString("O"); var was = s.Connected;
        s = s with { Connected = connected, Message = Trim(message, 300), CheckedAt = now, PortalTotal = portalTotal > 0 ? portalTotal : s.PortalTotal };
        if (connected && s.Error.StartsWith("Accesso", StringComparison.Ordinal)) s = s with { Error = "", ErrorAt = "" };
        if (!connected) s = s with { Error = "Accesso a RCH XDealer richiesto: apri Studio sulla postazione di sincronizzazione e accedi di nuovo.", ErrorAt = now };
        SetSetting("rch.status", s);
        if (was && !connected) Log("error", "RCH XDealer: sessione scaduta, serve un nuovo accesso sulla postazione di sincronizzazione.");
    }

    /// Rows: [serial, device JSON]. Replaces the whole list atomically: the window sends only complete reads.
    public string ApplyRch(List<string> headers, List<List<string>> rows, string capturedAt, string source)
    {
        if (!headers.SequenceEqual(RchHeaders)) throw new InvalidDataException("Formato RCH XDealer inatteso: aggiorna Top Cash Studio.");
        if (rows.Count == 0) throw new InvalidDataException("Elenco RCH XDealer vuoto: dati precedenti conservati.");
        var list = new Dictionary<string, RchDevice>(StringComparer.OrdinalIgnoreCase);
        foreach (var row in rows)
        {
            if (row.Count != 2) throw new InvalidDataException("Riga RCH XDealer incompleta.");
            RchDevice? d; try { d = JsonSerializer.Deserialize<RchDevice>(row[1]); } catch (JsonException) { continue; }
            var serial = row[0].Trim();
            if (d == null || serial.Length is < 5 or > 40 || !string.Equals(d.Serial?.Trim(), serial, StringComparison.OrdinalIgnoreCase)) continue;
            static string D(string? s) { s = (s ?? "").Trim(); return s.Length >= 10 && DateTime.TryParse(s[..10], System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.None, out var x) ? x.ToString("yyyy-MM-dd") : ""; }
            list.TryAdd(serial, d with
            {
                Serial = serial, PortalId = Trim(d.PortalId, 20), Part = Trim(d.Part, 40), Type = Trim(d.Type, 80), State = Trim(d.State, 60),
                Client = Trim(d.Client, 200), Vat = Trim(d.Vat, 20), City = Trim(d.City, 80), Province = Trim(d.Province, 4), Phone = Trim(d.Phone, 40), Email = Trim(d.Email, 120),
                Note = Trim(d.Note, 300), Labels = Trim(d.Labels, 200), Update = Trim(d.Update, 40), Firmware = Trim(d.Firmware, 40),
                DgfeFree = Math.Clamp(d.DgfeFree, -1, 100), DgfeNumber = Math.Max(0, d.DgfeNumber), ZNumber = Math.Max(0, d.ZNumber),
                LastVerification = D(d.LastVerification), ZDate = D(d.ZDate), CertFrom = D(d.CertFrom), CertDue = D(d.CertDue),
                Activated = D(d.Activated), Registered = D(d.Registered), CapturedAt = capturedAt,
            });
        }
        if (list.Count == 0) throw new InvalidDataException("Nessuna matricola valida nell'elenco RCH XDealer.");
        using var c = Open(); using var tx = c.BeginTransaction();
        using (var del = c.CreateCommand()) { del.Transaction = tx; del.CommandText = "DELETE FROM rch_devices"; del.ExecuteNonQuery(); }
        using (var ins = c.CreateCommand())
        {
            ins.Transaction = tx; ins.CommandText = "INSERT INTO rch_devices(serial,data) VALUES($s,$d)";
            var ps = ins.Parameters.Add("$s", SqliteType.Text); var pd = ins.Parameters.Add("$d", SqliteType.Text);
            foreach (var r in list.Values) { ps.Value = r.Serial; pd.Value = JsonSerializer.Serialize(r); ins.ExecuteNonQuery(); }
        }
        LogTx(c, tx, "portal", $"RCH XDealer: {list.Count} dispositivi aggiornati ({source}).");
        tx.Commit();
        SetSetting("rch.status", RchState() with { Connected = true, LastSync = capturedAt, Count = list.Count, Error = "", ErrorAt = "" });
        return $"RCH XDealer: {list.Count} dispositivi";
    }
}
