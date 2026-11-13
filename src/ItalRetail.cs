using Microsoft.Data.Sqlite;
using System.Globalization;
using System.Text.Json;

namespace TopCash.Licenze;

/// One device (telematic register) as shown in Ital Retail OnePlus → Area rivenditore → Gestione dispositivi. Read-only copy.
public sealed record ItalDevice(string Serial, string PortalId, string Type, string Update, bool Subscribed, string Client,
    string Note, string CapturedAt, ItalDetail? Detail = null);

/// Device page: subscription, firmware and RT state, electronic journal (DGFE), certificate, update history.
public sealed record ItalDetail(string Serial, string SubFrom = "", string SubTo = "", bool AutoRenew = false, string Firmware = "",
    int Closures = 0, int DgfeNumber = 0, string DgfeSize = "", int DgfeFree = -1, string RtState = "", string Activated = "",
    string Registered = "", string CertFrom = "", string CertDue = "", string DataAt = "", string Hw = "", string Wifi = "",
    string Seasonal = "", string Clock = "", List<ItalUpdate>? Updates = null, List<ItalDgfe>? Dgfe = null, string ReadAt = "");
public sealed record ItalUpdate(string At = "", string From = "", string To = "");
public sealed record ItalDgfe(string Duration = "", string Number = "", string At = "", string Status = "");
/// Order history of the dealer area (subscriptions and update purchases).
public sealed record ItalOrder(string Number, string Date, string Serial, string Client, string Kind, string Amount);

public sealed partial class Store
{
    /// Fixed column order sent by the Ital Retail window (headers travel with the rows and are checked).
    public static readonly string[] ItalHeaders = ["Matricola", "Id", "Tipo", "Aggiornamento", "Abbonamento", "Cliente", "Note"];
    public static readonly string[] ItalOrderHeaders = ["Numero", "Data", "Matricola", "Cliente", "Tipo", "Importo"];

    void InitItal(SqliteConnection c)
    {
        using var q = c.CreateCommand();
        q.CommandText = "CREATE TABLE IF NOT EXISTS ital_devices(serial TEXT PRIMARY KEY, data TEXT NOT NULL);CREATE TABLE IF NOT EXISTS ital_details(serial TEXT PRIMARY KEY, data TEXT NOT NULL);CREATE TABLE IF NOT EXISTS ital_orders(number TEXT PRIMARY KEY, data TEXT NOT NULL)";
        q.ExecuteNonQuery();
    }

    List<T> ItalRows<T>(string table, string key)
    {
        using var c = Open(); using var q = c.CreateCommand(); q.CommandText = $"SELECT data FROM {table} ORDER BY {key}";
        using var r = q.ExecuteReader(); var list = new List<T>();
        while (r.Read()) { var d = JsonSerializer.Deserialize<T>(r.GetString(0)); if (d != null) list.Add(d); }
        return list;
    }

    public List<ItalDevice> ItalDevices()
    {
        var details = ItalRows<ItalDetail>("ital_details", "serial").ToDictionary(d => d.Serial, StringComparer.OrdinalIgnoreCase);
        return ItalRows<ItalDevice>("ital_devices", "serial").Select(d => details.TryGetValue(d.Serial, out var x) ? d with { Detail = x } : d).ToList();
    }
    public List<ItalOrder> ItalOrders() => ItalRows<ItalOrder>("ital_orders", "number");
    public DtrStatus ItalState() => Setting<DtrStatus>("ital.status") ?? new DtrStatus();

    /// Session heartbeat from the sync station: connected / signed out, with a short message.
    public void ItalHeartbeat(bool connected, string message, int portalTotal = 0)
    {
        var s = ItalState(); var now = DateTimeOffset.Now.ToString("O"); var was = s.Connected;
        s = s with { Connected = connected, Message = Trim(message, 300), CheckedAt = now, PortalTotal = portalTotal > 0 ? portalTotal : s.PortalTotal };
        if (connected && s.Error.StartsWith("Accesso", StringComparison.Ordinal)) s = s with { Error = "", ErrorAt = "" };
        if (!connected) s = s with { Error = "Accesso a Ital Retail richiesto: ricollegalo da Sincronizzazione → Portali e accessi.", ErrorAt = now };
        SetSetting("ital.status", s);
        if (was && !connected) Log("error", "Ital Retail: sessione scaduta, serve un nuovo accesso (Sincronizzazione → Portali e accessi).");
    }

    /// Replaces the whole device list atomically. Only a complete read reaches this point (the portal window checks the total).
    public string ApplyItal(List<string> headers, List<List<string>> rows, string capturedAt, string source)
    {
        if (!headers.SequenceEqual(ItalHeaders)) throw new InvalidDataException("Colonne Ital Retail inattese: aggiorna Top Cash Studio.");
        if (rows.Count == 0) throw new InvalidDataException("Elenco Ital Retail vuoto: dati precedenti conservati.");
        var list = new Dictionary<string, ItalDevice>(StringComparer.OrdinalIgnoreCase);
        foreach (var row in rows)
        {
            if (row.Count != ItalHeaders.Length) throw new InvalidDataException("Riga Ital Retail incompleta.");
            var serial = row[0].Trim();
            if (serial.Length is < 6 or > 40) continue;
            var up = Trim(row[3], 60); var ver = System.Text.RegularExpressions.Regex.Match(up, @"\d+(\.\d+)+").Value;
            if (!list.ContainsKey(serial)) list[serial] = new ItalDevice(serial, Trim(row[1], 12), Trim(row[2], 60),
                up.StartsWith("S", StringComparison.OrdinalIgnoreCase) ? (ver.Length > 0 ? ver : "sì") : "", row[4].Trim().StartsWith("S", StringComparison.OrdinalIgnoreCase),
                Trim(row[5], 200), Trim(row[6], 300), capturedAt);
        }
        if (list.Count == 0) throw new InvalidDataException("Nessuna matricola valida nell'elenco Ital Retail.");
        using var c = Open(); using var tx = c.BeginTransaction();
        using (var del = c.CreateCommand()) { del.Transaction = tx; del.CommandText = "DELETE FROM ital_devices"; del.ExecuteNonQuery(); }
        using (var ins = c.CreateCommand())
        {
            ins.Transaction = tx; ins.CommandText = "INSERT INTO ital_devices(serial,data) VALUES($s,$d)";
            var ps = ins.Parameters.Add("$s", SqliteType.Text); var pd = ins.Parameters.Add("$d", SqliteType.Text);
            foreach (var r in list.Values) { ps.Value = r.Serial; pd.Value = JsonSerializer.Serialize(r); ins.ExecuteNonQuery(); }
        }
        LogTx(c, tx, "portal", $"Ital Retail: {list.Count} dispositivi aggiornati ({source}).");
        tx.Commit();
        SetSetting("ital.status", ItalState() with { Connected = true, LastSync = capturedAt, Count = list.Count, Error = "", ErrorAt = "" });
        return $"Ital Retail: {list.Count} dispositivi";
    }

    /// Rows: [serial, detail JSON]. Each detail is validated and capped.
    public string ApplyItalDetails(List<string> headers, List<List<string>> rows, string capturedAt)
    {
        if (headers.Count != 2 || headers[0] != "Matricola" || headers[1] != "Dettaglio") throw new InvalidDataException("Formato dettagli Ital Retail inatteso.");
        int n = 0; using var c = Open(); using var tx = c.BeginTransaction();
        foreach (var row in rows)
        {
            if (row.Count != 2) continue;
            ItalDetail? d; try { d = JsonSerializer.Deserialize<ItalDetail>(row[1]); } catch (JsonException) { continue; }
            if (d == null || !string.Equals(d.Serial, row[0].Trim(), StringComparison.OrdinalIgnoreCase)) continue;
            d = d with
            {
                SubFrom = ItDate(d.SubFrom), SubTo = ItDate(d.SubTo), Firmware = Trim(d.Firmware, 30), DgfeSize = Trim(d.DgfeSize, 60),
                DgfeFree = Math.Clamp(d.DgfeFree, -1, 100), RtState = Trim(d.RtState, 60), Activated = ItDate(d.Activated), Registered = ItDate(d.Registered),
                CertFrom = ItDate(d.CertFrom), CertDue = ItDate(d.CertDue), DataAt = ItDate(d.DataAt), Hw = Trim(d.Hw, 30), Wifi = Trim(d.Wifi, 60),
                Seasonal = Trim(d.Seasonal, 10), Clock = Trim(d.Clock, 40), ReadAt = capturedAt,
                Updates = (d.Updates ?? []).Take(30).Select(u => new ItalUpdate(ItDate(u.At), Trim(u.From, 20), Trim(u.To, 20))).ToList(),
                Dgfe = (d.Dgfe ?? []).Take(20).Select(g => new ItalDgfe(Trim(g.Duration, 60), Trim(g.Number, 10), ItDate(g.At), Trim(g.Status, 60))).ToList(),
            };
            using var q = c.CreateCommand(); q.Transaction = tx;
            q.CommandText = "INSERT INTO ital_details(serial,data) VALUES($s,$d) ON CONFLICT(serial) DO UPDATE SET data=excluded.data";
            q.Parameters.AddWithValue("$s", d.Serial); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(d)); q.ExecuteNonQuery(); n++;
        }
        tx.Commit();
        return $"Ital Retail: {n} schede di dettaglio";
    }

    /// Replaces the order history (an empty history is valid: a new dealer has no orders).
    public string ApplyItalOrders(List<string> headers, List<List<string>> rows)
    {
        if (!headers.SequenceEqual(ItalOrderHeaders)) throw new InvalidDataException("Colonne ordini Ital Retail inattese: aggiorna Top Cash Studio.");
        var list = new Dictionary<string, ItalOrder>();
        foreach (var row in rows)
        {
            if (row.Count != ItalOrderHeaders.Length) throw new InvalidDataException("Riga ordine Ital Retail incompleta.");
            var num = Trim(row[0], 20); if (num.Length == 0) continue;
            var client = Trim(row[3], 200);
            list[num] = new ItalOrder(num, ItDate(row[1]), Trim(row[2], 40), client == "-" ? "" : client, Trim(row[4], 80), Trim(row[5], 20));
        }
        using var c = Open(); using var tx = c.BeginTransaction();
        using (var del = c.CreateCommand()) { del.Transaction = tx; del.CommandText = "DELETE FROM ital_orders"; del.ExecuteNonQuery(); }
        foreach (var o in list.Values)
        {
            using var q = c.CreateCommand(); q.Transaction = tx; q.CommandText = "INSERT INTO ital_orders(number,data) VALUES($n,$d)";
            q.Parameters.AddWithValue("$n", o.Number.PadLeft(12, '0')); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(o)); q.ExecuteNonQuery();
        }
        tx.Commit();
        return $"Ital Retail: {list.Count} ordini";
    }

    /// Portal dates are "dd-MM-yyyy" with an optional time.
    static string ItDate(string? s)
    {
        s = (s ?? "").Trim();
        if (DateTime.TryParseExact(s, "dd-MM-yyyy", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)) return d.ToString("yyyy-MM-dd");
        return DateTime.TryParseExact(s, ["dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm"], CultureInfo.InvariantCulture, DateTimeStyles.None, out d) ? d.ToString("yyyy-MM-ddTHH:mm") : "";
    }
}
