using System.Globalization;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace TopCash.Licenze;

/// A PC, server, printer, network device or software licence installed at a customer (the IT side of the work).
/// No passwords are kept here on purpose: credentials belong in a password manager.
public sealed record Asset(string Id = "", string ClientCode = "", string Kind = "", string Name = "", string Brand = "", string Serial = "",
    string Location = "", string Ip = "", string Os = "", string Purchased = "", string WarrantyTo = "", string Expiry = "", string Notes = "",
    bool Active = true, string UpdatedAt = "", string UpdatedBy = "");
/// The follow-up of one deadline (licence, verification, subscription, certificate...): who called, how it went, when to call again.
public sealed record RenewalMark(string Status = "", string Note = "", string NextContact = "", string At = "", string By = "", List<RenewalStep>? Steps = null);
public sealed record RenewalStep(string At, string By, string Status, string Note);

public sealed partial class Store
{
    public static readonly string[] AssetKinds = ["PC", "Server", "Stampante", "Router / firewall", "NAS", "Tablet", "Licenza software", "Altro"];
    public static readonly string[] RenewalStates = ["Da contattare", "Contattato", "Da sollecitare", "Rinnovato", "Perso"];

    void InitWork(SqliteConnection c)
    {
        using var q = c.CreateCommand();
        q.CommandText = """
         CREATE TABLE IF NOT EXISTS assets(id TEXT PRIMARY KEY, client TEXT NOT NULL, data TEXT NOT NULL);
         CREATE TABLE IF NOT EXISTS renewals(key TEXT PRIMARY KEY, data TEXT NOT NULL);
         CREATE TABLE IF NOT EXISTS device_links(serial TEXT PRIMARY KEY, data TEXT NOT NULL);
         """;
        q.ExecuteNonQuery();
    }

    static string Iso(string v, string what)
    {
        v = (v ?? "").Trim();
        if (v.Length > 0 && !DateTime.TryParseExact(v, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _)) throw new InvalidDataException($"Data non valida: {what}.");
        return v;
    }

    public List<Asset> Assets() => EditMap<Asset>("assets", "id").Values.OrderBy(a => a.ClientCode).ThenBy(a => a.Name).ToList();
    public Asset SaveAsset(Asset a, string who)
    {
        if (string.IsNullOrWhiteSpace(a.ClientCode) || a.ClientCode.Length > 40) throw new InvalidDataException("Scegli il cliente.");
        var name = Trim(a.Name, 120); if (name.Length == 0) throw new InvalidDataException("Scrivi nome o modello.");
        var kind = AssetKinds.Contains(a.Kind) ? a.Kind : "Altro";
        var id = string.IsNullOrWhiteSpace(a.Id) ? "A" + DateTime.Now.ToString("yyMMddHHmmss") + Random.Shared.Next(100, 999) : Trim(a.Id, 40);
        var clean = new Asset(id, Trim(a.ClientCode, 40), kind, name, Trim(a.Brand, 60), Trim(a.Serial, 80), Trim(a.Location, 120), Trim(a.Ip, 60), Trim(a.Os, 80),
            Iso(a.Purchased, "acquisto"), Iso(a.WarrantyTo, "garanzia"), Iso(a.Expiry, "scadenza"), Trim(a.Notes, 1000), a.Active, DateTimeOffset.Now.ToString("O"), Trim(who, 80));
        using (var c = Open())
        {
            using var q = c.CreateCommand();
            q.CommandText = "INSERT INTO assets(id,client,data) VALUES($i,$c,$d) ON CONFLICT(id) DO UPDATE SET client=excluded.client,data=excluded.data";
            q.Parameters.AddWithValue("$i", id); q.Parameters.AddWithValue("$c", clean.ClientCode); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(clean));
            q.ExecuteNonQuery();
        }
        Log("users", $"{who} ha salvato {kind} «{name}» nell'inventario del cliente {clean.ClientCode}.");
        return clean;
    }
    public void DeleteAsset(string id, string who)
    {
        SaveEdit<Asset>("assets", "id", Trim(id, 40), null);
        Log("users", $"{who} ha tolto un elemento dall'inventario ({id}).");
    }

    public Dictionary<string, RenewalMark> Renewals() => EditMap<RenewalMark>("renewals", "key");
    public RenewalMark SaveRenewal(string key, string status, string note, string nextContact, string who)
    {
        key = (key ?? "").Trim(); if (key.Length is 0 or > 200) throw new InvalidDataException("Scadenza non valida.");
        if (status.Length > 0 && !RenewalStates.Contains(status)) throw new InvalidDataException("Stato non valido.");
        var old = Renewals().GetValueOrDefault(key);
        var now = DateTimeOffset.Now.ToString("O");
        var steps = (old?.Steps ?? []).ToList(); steps.Add(new(now, Trim(who, 80), status, Trim(note, 500)));
        var mark = new RenewalMark(status, Trim(note, 500), Iso(nextContact, "richiamo"), now, Trim(who, 80), steps.TakeLast(30).ToList());
        SaveEdit("renewals", "key", key, mark);
        return mark;
    }

    /// Serial → customer chosen by hand, for DTR / Ital Retail devices the portals list without a recognisable customer.
    public Dictionary<string, string> DeviceLinks() => EditMap<string>("device_links", "serial");
    public void SaveDeviceLink(string serial, string clientCode, string who)
    {
        serial = Trim(serial, 60).ToUpperInvariant(); if (serial.Length == 0) throw new InvalidDataException("Matricola non valida.");
        SaveEdit("device_links", "serial", serial, clientCode.Length == 0 ? null : Trim(clientCode, 40));
        Log("users", clientCode.Length == 0 ? $"{who} ha tolto l'abbinamento della matricola {serial}." : $"{who} ha abbinato la matricola {serial} al cliente {clientCode}.");
    }
}
