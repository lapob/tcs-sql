using Microsoft.Data.Sqlite;
using System.Globalization;
using System.Text.Json;

namespace TopCash.Licenze;

/// Changes made by an administrator to a customer. They are kept apart from the portal and Mastro copies, so a new
/// synchronisation never overwrites them. Empty fields keep the value coming from the sources.
/// Created = customer added by hand (it exists only here); Deleted = hidden from Studio (it can be restored).
public sealed record ClientEdit(string Name = "", string Vat = "", string TaxCode = "", string City = "", string Address = "",
    string Phone = "", string Email = "", string Pec = "", string Contact = "", string Category = "", bool Created = false,
    bool Deleted = false, string UpdatedAt = "", string UpdatedBy = "", string MergedInto = "", bool Keep = false);

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
            isNew || old?.Created == true, old?.Deleted ?? false, DateTimeOffset.Now.ToString("O"), Trim(who, 80), old?.MergedInto ?? "");
        SaveEdit("client_edits", "code", code, clean);
        Log("edit", $"{who} ha {(isNew ? "creato" : "modificato")} il cliente {(name.Length > 0 ? name : code)}.");
        return code;
    }

    /// Deleting hides the customer everywhere in Studio (sources are read-only); restoring brings it back.
    public void DeleteClient(string code, bool deleted, string who)
    {
        var old = ClientEdits().GetValueOrDefault(code) ?? new ClientEdit();
        SaveEdit("client_edits", "code", code, old with { Deleted = deleted, Keep = !deleted, UpdatedAt = DateTimeOffset.Now.ToString("O"), UpdatedBy = Trim(who, 80) });
        Log("users", $"{who} ha {(deleted ? "eliminato" : "ripristinato")} il cliente {(old.Name.Length > 0 ? old.Name : code)}.");
    }

    /// Merging: the duplicate disappears and its licences, machines, devices and history show under the main customer.
    public void MergeClient(string code, string into, string who)
    {
        if (code.Length is 0 or > 40 || into.Length > 40 || code == into) throw new InvalidDataException("Clienti da unire non validi.");
        var old = ClientEdits().GetValueOrDefault(code) ?? new ClientEdit();
        SaveEdit("client_edits", "code", code, old with { MergedInto = into, UpdatedAt = DateTimeOffset.Now.ToString("O"), UpdatedBy = Trim(who, 80) });
        Log("users", into.Length > 0 ? $"{who} ha unito il cliente {code} in {into}." : $"{who} ha separato di nuovo il cliente {code}.");
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

/// Brands of cash register set aside for now (default EDIT): their machines disappear, and so do the Mastro customers
/// whose active registers are all of those brands (or, with none active, all their registers), with their Mastro history. Nothing is deleted: the Mastro copy stays intact
/// and removing the brand from the list brings everything back.
public sealed record BrandRule(List<string> Brands, string UpdatedAt = "", string UpdatedBy = "");
public sealed record BrandExcluded(List<string> Brands, int Clients, int Machines, List<string> Names);
public sealed record MastroView(List<MastroClient> Clients, List<Machine> Machines, List<Intervention> Interventions, BrandExcluded Excluded);

public sealed partial class Store
{
 public static readonly List<string> DefaultHiddenBrands = ["EDIT"];
 public List<string> HiddenBrands() => Setting<BrandRule>("hide.brands")?.Brands ?? [.. DefaultHiddenBrands];
 public void SaveHiddenBrands(IEnumerable<string> brands, string who)
 {
  var list = brands.Select(b => b.Trim().ToUpperInvariant()).Where(b => b.Length is > 1 and <= 40).Distinct().Take(20).ToList();
  SetSetting("hide.brands", new BrandRule(list, DateTimeOffset.Now.ToString("O"), who));
  Log("users", list.Count == 0 ? $"{who} ha rimesso visibili tutte le marche di registratori." : $"{who} ha messo da parte i registratori {string.Join(", ", list)} e i loro clienti.");
 }
 public static bool IsBrand(Machine m, IReadOnlyCollection<string> brands) =>
  brands.Count > 0 && brands.Any(b => System.Text.RegularExpressions.Regex.IsMatch(m.Model + " " + m.Type, $@"(?<![\p{{L}}\p{{N}}]){System.Text.RegularExpressions.Regex.Escape(b)}(?![\p{{L}}\p{{N}}])", System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.CultureInvariant));
 /// The Mastro snapshot as the app shows it, with the set-aside brands removed.
 public MastroView Mastro()
 {
  var brands = HiddenBrands(); var cl = MastroClients(); var all = Machines(); var iv = Interventions();
  if (brands.Count == 0) return new(cl, all, iv, new(brands, 0, 0, []));
  var hiddenMachines = all.Where(m => IsBrand(m, brands)).ToList();
  if (hiddenMachines.Count == 0) return new(cl, all, iv, new(brands, 0, 0, []));
  // a customer goes only when the registers it still uses are all of those brands (an old EDIT replaced by another brand keeps it)
  var gone = all.GroupBy(m => m.ClientCode).Where(g => { var act = g.Where(m => m.Active).ToList(); return (act.Count > 0 ? act : g.ToList()).All(m => IsBrand(m, brands)); }).Select(g => g.Key).ToHashSet();
  var names = cl.Where(c => gone.Contains(c.Code)).Select(c => c.Name).OrderBy(n => n).ToList();
  return new(cl.Where(c => !gone.Contains(c.Code)).ToList(), all.Where(m => !IsBrand(m, brands)).ToList(),
   iv.Where(i => !(i.Source == "mastro" && gone.Contains(i.ClientCode))).ToList(),
   new(brands, gone.Count, hiddenMachines.Count, names.Take(300).ToList()));
 }
}
