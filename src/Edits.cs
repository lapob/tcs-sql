using Microsoft.Data.Sqlite;
using System.Globalization;
using System.Text.Json;

namespace TopCash.Licenze;

/// Changes made by an administrator to a customer. They are kept apart from the portal and Mastro copies, so a new
/// synchronisation never overwrites them. Empty fields keep the value coming from the sources.
/// Created = customer added by hand (it exists only here); Deleted = hidden from Studio (it can be restored).
public sealed record ClientEdit(string Name = "", string Vat = "", string TaxCode = "", string City = "", string Address = "",
    string Phone = "", string Email = "", string Pec = "", string Contact = "", string Category = "", bool Created = false,
    bool Deleted = false, string UpdatedAt = "", string UpdatedBy = "");

/// Changes made by an administrator to a licence: a corrected expiry, a note, or the licence hidden from Studio.
public sealed record LicenseEdit(string Expiry = "", string Note = "", bool Hidden = false, string UpdatedAt = "", string UpdatedBy = "");

public sealed partial class Store
{
    /// Licences expired longer ago than this are not shown (the portal keeps them for years).
    public const int ExpiredKeepDays = 183;

    void InitEdits(SqliteConnection c)
    {
        using var q = c.CreateCommand();
        q.CommandText = "CREATE TABLE IF NOT EXISTS client_edits(code TEXT PRIMARY KEY, data TEXT NOT NULL);CREATE TABLE IF NOT EXISTS license_edits(id TEXT PRIMARY KEY, data TEXT NOT NULL)";
        q.ExecuteNonQuery();
    }

    Dictionary<string, T> EditMap<T>(string table, string key)
    {
        using var c = Open(); using var q = c.CreateCommand(); q.CommandText = $"SELECT {key},data FROM {table}";
        using var r = q.ExecuteReader(); var map = new Dictionary<string, T>();
        while (r.Read()) { var d = JsonSerializer.Deserialize<T>(r.GetString(1)); if (d != null) map[r.GetString(0)] = d; }
        return map;
    }
    void SaveEdit<T>(string table, string key, string id, T? value)
    {
        using var c = Open(); using var q = c.CreateCommand();
        if (value == null) { q.CommandText = $"DELETE FROM {table} WHERE {key}=$k"; q.Parameters.AddWithValue("$k", id); }
        else
        {
            q.CommandText = $"INSERT INTO {table}({key},data) VALUES($k,$d) ON CONFLICT({key}) DO UPDATE SET data=excluded.data";
            q.Parameters.AddWithValue("$k", id); q.Parameters.AddWithValue("$d", JsonSerializer.Serialize(value));
        }
        q.ExecuteNonQuery();
    }

    public Dictionary<string, ClientEdit> ClientEdits() => EditMap<ClientEdit>("client_edits", "code");
    public Dictionary<string, LicenseEdit> LicenseEdits() => EditMap<LicenseEdit>("license_edits", "id");

    /// Saves the administrator's version of a customer. A new customer gets a code here ("L" + time).
    public string SaveClient(string? code, ClientEdit e, string who)
    {
        code = (code ?? "").Trim();
        var isNew = code.Length == 0;
        if (isNew) code = "L" + DateTime.Now.ToString("yyMMddHHmmss") + Random.Shared.Next(10, 99);
        if (code.Length > 40) throw new InvalidDataException("Codice cliente non valido.");
        var old = ClientEdits().GetValueOrDefault(code);
        var name = Trim(e.Name, 200);
        if ((isNew || old?.Created == true) && name.Length == 0) throw new InvalidDataException("Scrivi la ragione sociale del cliente.");
        var email = Trim(e.Email, 200).ToLowerInvariant();
        if (email.Length > 0 && !email.Contains('@')) throw new InvalidDataException("Indirizzo email non valido.");
        var vat = Trim(e.Vat, 20).ToUpperInvariant().Replace(" ", "");
        var clean = new ClientEdit(name, vat, Trim(e.TaxCode, 20).ToUpperInvariant().Replace(" ", ""), Trim(e.City, 100), Trim(e.Address, 200),
            Trim(e.Phone, 60), email, Trim(e.Pec, 200).ToLowerInvariant(), Trim(e.Contact, 120), Trim(e.Category, 60),
            isNew || old?.Created == true, old?.Deleted ?? false, DateTimeOffset.Now.ToString("O"), Trim(who, 80));
        SaveEdit("client_edits", "code", code, clean);
        Log("users", $"{who} ha {(isNew ? "creato" : "modificato")} il cliente {(name.Length > 0 ? name : code)}.");
        return code;
    }

    /// Deleting hides the customer everywhere in Studio (sources are read-only); restoring brings it back.
    public void DeleteClient(string code, bool deleted, string who)
    {
        var old = ClientEdits().GetValueOrDefault(code) ?? new ClientEdit();
        SaveEdit("client_edits", "code", code, old with { Deleted = deleted, UpdatedAt = DateTimeOffset.Now.ToString("O"), UpdatedBy = Trim(who, 80) });
        Log("users", $"{who} ha {(deleted ? "eliminato" : "ripristinato")} il cliente {(old.Name.Length > 0 ? old.Name : code)}.");
    }

    /// Removes the administrator's changes: the customer goes back to the data from the sources.
    public void ResetClient(string code, string who)
    {
        var old = ClientEdits().GetValueOrDefault(code);
        if (old?.Created == true) throw new InvalidDataException("Un cliente creato a mano non ha dati di origine: eliminalo se non serve più.");
        SaveEdit<ClientEdit>("client_edits", "code", code, null);
        Log("users", $"{who} ha annullato le modifiche al cliente {code}.");
    }

    public void SaveLicense(string id, LicenseEdit e, string who)
    {
        if (id.Length is 0 or > 80) throw new InvalidDataException("Licenza non valida.");
        var expiry = (e.Expiry ?? "").Trim();
        if (expiry.Length > 0 && !DateTime.TryParseExact(expiry, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
            throw new InvalidDataException("Data di scadenza non valida.");
        var clean = new LicenseEdit(expiry, Trim(e.Note, 500), e.Hidden, DateTimeOffset.Now.ToString("O"), Trim(who, 80));
        SaveEdit("license_edits", "id", id, clean.Expiry.Length == 0 && clean.Note.Length == 0 && !clean.Hidden ? null : clean);
        Log("users", $"{who} ha {(e.Hidden ? "nascosto" : "modificato")} una licenza ({id[..Math.Min(8, id.Length)]}).");
    }

    /// The licences Studio shows: the administrator's changes applied, hidden ones removed, licences marked
    /// "not valid" on the portal (red square) removed, and those expired more than six months ago removed.
    public List<LicenseRecord> Licenses()
    {
        var edits = LicenseEdits(); var limit = DateTime.Today.AddDays(-ExpiredKeepDays); var list = new List<LicenseRecord>();
        foreach (var r0 in All())
        {
            var r = r0;
            if (edits.TryGetValue(r.Id, out var e))
            {
                if (e.Hidden) continue;
                if (e.Expiry.Length > 0) r = r with { Expiry = DateTime.ParseExact(e.Expiry, "yyyy-MM-dd", CultureInfo.InvariantCulture).ToString("dd-MM-yyyy") };
            }
            if (NotValidOnPortal(r) && (e == null || e.Expiry.Length == 0)) continue;
            if (LicenseRecord.ParseDate(r.Expiry) is DateTime d && d.Date < limit) continue;
            list.Add(r);
        }
        return list;
    }

    /// The portal draws a red square ("licenza non valida") next to licences that are no longer valid.
    static bool NotValidOnPortal(LicenseRecord r)
    {
        try { return JsonSerializer.Deserialize<List<string>>(r.RawJson)?.Any(x => x.Trim().Equals("licenza non valida", StringComparison.OrdinalIgnoreCase)) == true; }
        catch (JsonException) { return false; }
    }

    /// Licences removed by the rules above (shown on request, so nothing disappears without explanation).
    public object LicenseCleanup()
    {
        var edits = LicenseEdits(); var limit = DateTime.Today.AddDays(-ExpiredKeepDays); int red = 0, old = 0, hidden = 0;
        foreach (var r in All())
        {
            var e = edits.GetValueOrDefault(r.Id);
            if (e?.Hidden == true) { hidden++; continue; }
            if (e != null && e.Expiry.Length > 0) continue;
            if (NotValidOnPortal(r)) red++;
            else if (LicenseRecord.ParseDate(r.Expiry) is DateTime d && d.Date < limit) old++;
        }
        return new { red, old, hidden, keepDays = ExpiredKeepDays };
    }
}
