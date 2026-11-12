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
   if (n.Trim().Length > 0 && !list.Any(u => TechKey(u.Name) == TechKey(n))) list.Add(new(n.Trim(), "", "tecnico"));
  return list.Where(u => !HiddenTech(u.Name, r)).ToList();
 }
}
