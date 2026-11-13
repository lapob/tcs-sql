using Microsoft.Data.Sqlite;

namespace TopCash.Licenze;

/// Who works in the team besides the app users: Hidden = names that must not appear as technicians anywhere
/// (machines, interventions, referents, filters), Added = technicians without an account who can still be assigned work.
public sealed record TechRoster(List<string> Hidden, List<string> Added, string UpdatedAt = "", string UpdatedBy = "");
public sealed record TeamMember(string Name, string Username, string Role);

public sealed partial class Store
{
 public static readonly TechRoster DefaultRoster = new(["Croce", "Fiorenzo Croce"], ["Giacomo Corezzi"]);
 public TechRoster Roster() => Setting<TechRoster>("techs.roster") ?? DefaultRoster;
 /// The team by surname only (one name per person, whatever Mastro, the portals or an account call them: "BARTOLI A.", "Andrea Bartoli"
 /// and "A.Bartoli" are all "Bartoli"); the two groups that are not people keep their own words.
 public static readonly string[] TechSurnames = ["Bardi", "Chiti", "Bartoli", "Bianco", "Corezzi", "Zucchini", "Cappellini", "Innocenti", "Meriggi", "Gheri", "Papi", "Pistolesi"];
 public static string TechName(string? raw)
 {
  var t = (raw ?? "").Trim(); if (t.Length == 0) return "";
  var low = t.ToLowerInvariant();
  if (low.Contains("rivendit")) return "Rivenditori";
  if (low.Contains("partner")) return "Aziende Partner";
  var words = low.Split([' ', '.', ',', '-', '/', '_', '(', ')'], StringSplitOptions.RemoveEmptyEntries);
  foreach (var s in TechSurnames) if (words.Contains(s.ToLowerInvariant())) return s;
  // someone not in the list: kept, written properly ("ROSSI MARIO" → "Rossi Mario")
  return string.Join(' ', t.Split(' ', StringSplitOptions.RemoveEmptyEntries).Select(w => w.Length > 1 && w.All(ch => !char.IsLetter(ch) || char.IsUpper(ch)) ? char.ToUpper(w[0]) + w[1..].ToLowerInvariant() : w));
 }
 static string TechKey(string? s) => string.Join(' ', (s ?? "").Trim().ToLowerInvariant().Split(' ', StringSplitOptions.RemoveEmptyEntries));
 /// True when a technician name is one of the removed ones ("Croce" also matches "Fiorenzo Croce" and "CROCE F.").
 public static bool HiddenTech(string? name, TechRoster r)
 {
  var k = TechKey(name); if (k.Length == 0) return false;
  return r.Hidden.Select(TechKey).Any(h => h.Length > 0 && (k == h || k.Split(' ').Contains(h) && !h.Contains(' ')));
 }

 /// The people work can be assigned to: active app users plus the technicians added by hand, hidden ones left out.
 public List<TeamMember> Team()
 {
  var r = Roster(); var list = new List<TeamMember>();
  try
  {
   using var c = Open(); using var q = c.CreateCommand();
   q.CommandText = "SELECT name,username,role FROM users WHERE disabled=0";
   using var rd = q.ExecuteReader();
   while (rd.Read()) list.Add(new(rd.GetString(0), rd.GetString(1), rd.GetString(2)));
  }
  catch (SqliteException) { }   // the Windows app on its own has no users table
  foreach (var n in r.Added)
   if (n.Trim().Length > 0 && !list.Any(u => TechName(u.Name) == TechName(n))) list.Add(new(n.Trim(), "", "tecnico"));
  // work is assigned by surname: one entry per person (an account and a hand-added name of the same person are one)
  return list.Where(u => !HiddenTech(u.Name, r)).Select(u => u with { Name = TechName(u.Name) })
   .GroupBy(u => u.Name, StringComparer.OrdinalIgnoreCase).Select(g => g.OrderByDescending(u => u.Username.Length > 0).First()).ToList();
 }
}
