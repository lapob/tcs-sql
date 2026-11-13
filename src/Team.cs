using Microsoft.Data.Sqlite;

namespace TopCash.Licenze;

/// Who works in the team besides the app users: Hidden = names that must not appear as technicians anywhere
/// (machines, interventions, referents, filters), Added = technicians without an account who can still be assigned work.
/// Agents = people who also sell (they stay technicians too); null until first saved = the default ones.
public sealed record TechRoster(List<string> Hidden, List<string> Added, string UpdatedAt = "", string UpdatedBy = "", List<string>? Agents = null);
public sealed record TeamMember(string Name, string Username, string Role, bool Agent = false);
/// One person as the team list shows them: hidden or not, agent or not, with an account or not, how much work carries their name.
public sealed record RosterPerson(string Name, bool Hidden, bool Agent, string Username, int Jobs, bool Group);
public sealed record RosterView(List<RosterPerson> People, string UpdatedAt, string UpdatedBy);

public sealed partial class Store
{
 public static readonly TechRoster DefaultRoster = new(["Croce", "Fiorenzo Croce"], ["Giacomo Corezzi"]);
 public TechRoster Roster() => Setting<TechRoster>("techs.roster") ?? DefaultRoster;
 public static readonly string[] DefaultAgents = ["Pistolesi", "Innocenti"];
 /// The agents by surname (the default ones until the list is changed).
 public static HashSet<string> AgentSet(TechRoster r) => (r.Agents ?? [.. DefaultAgents]).Select(TechName).Where(n => n.Length > 0).ToHashSet(StringComparer.OrdinalIgnoreCase);
 static bool IsGroup(string n) => n is "Rivenditori" or "Aziende Partner";

 /// Everyone who appears anywhere (accounts, people added by hand, the known team, names found on interventions and machines), each once.
 public RosterView RosterView()
 {
  var r = Roster(); var agents = AgentSet(r);
  var jobs = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase); var account = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
  // a removed person is listed once, under the name they were hidden with ("CROCE F." and "Fiorenzo Croce" are "Croce")
  string Canon(string t) { if (!HiddenTech(t, r)) return t; var k = TechKey(t); var h = r.Hidden.Select(TechKey).FirstOrDefault(x => x.Length > 0 && !x.Contains(' ') && k.Split(' ').Contains(x)); return h != null ? TechName(char.ToUpper(h[0]) + h[1..]) : t; }
  void Seen(string? raw, int n = 1) { var t = Canon(TechName(raw)); if (t.Length > 0 && !t.All(char.IsDigit)) jobs[t] = jobs.GetValueOrDefault(t) + n; }
  foreach (var s in TechSurnames) Seen(s, 0);
  foreach (var a in r.Added) Seen(a, 0);
  foreach (var h in r.Hidden) Seen(h, 0);
  foreach (var a in agents) Seen(a, 0);
  try
  {
   using var c = Open();
   using (var q = c.CreateCommand()) { q.CommandText = "SELECT name,username FROM users WHERE disabled=0"; using var rd = q.ExecuteReader(); while (rd.Read()) { Seen(rd.GetString(0), 0); account.TryAdd(TechName(rd.GetString(0)), rd.GetString(1)); } }
  }
  catch (SqliteException) { }
  try
  {
   using var c = Open(); using var q = c.CreateCommand();
   // counted by the database, one row per spelling: the names are cleaned once each, not once per intervention
   q.CommandText = "SELECT json_extract(data,'$.Technician') t, count(*) FROM interventions WHERE source<>'deleted' GROUP BY t";
   using var rd = q.ExecuteReader(); while (rd.Read()) if (!rd.IsDBNull(0)) Seen(rd.GetString(0), rd.GetInt32(1));
  }
  catch (SqliteException) { }
  var people = jobs.Select(kv => new RosterPerson(kv.Key, HiddenTech(kv.Key, r), agents.Contains(kv.Key), account.GetValueOrDefault(kv.Key, ""), kv.Value, IsGroup(kv.Key)))
   .OrderBy(p => p.Hidden).ThenBy(p => p.Group).ThenBy(p => Array.IndexOf(TechSurnames, p.Name) is var i && i >= 0 ? i : 99).ThenBy(p => p.Name, StringComparer.OrdinalIgnoreCase).ToList();
  return new(people, r.UpdatedAt, r.UpdatedBy);
 }

 /// Saves who is hidden and who is an agent (names by surname). Added stays as it is.
 public void SaveRoster(IEnumerable<string>? hidden, IEnumerable<string>? agents, string who)
 {
  var r = Roster();
  static List<string> Clean(IEnumerable<string>? l) => (l ?? []).Select(TechName).Where(n => n.Length is > 0 and <= 80).Distinct(StringComparer.OrdinalIgnoreCase).Take(200).ToList();
  var h = Clean(hidden); var a = Clean(agents).Where(n => !h.Contains(n, StringComparer.OrdinalIgnoreCase)).ToList();
  // a hidden name keeps its old spellings too (so "Fiorenzo Croce" stays hidden when "Croce" is)
  var keep = r.Hidden.Where(x => h.Contains(TechName(x), StringComparer.OrdinalIgnoreCase)).ToList();
  SetSetting("techs.roster", r with { Hidden = keep.Concat(h).Distinct(StringComparer.OrdinalIgnoreCase).ToList(), Agents = a, UpdatedAt = DateTimeOffset.Now.ToString("O"), UpdatedBy = who });
 }
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
  var agents = AgentSet(r);
  return list.Where(u => !HiddenTech(u.Name, r)).Select(u => u with { Name = TechName(u.Name) }).Select(u => u with { Agent = agents.Contains(u.Name) })
   .GroupBy(u => u.Name, StringComparer.OrdinalIgnoreCase).Select(g => g.OrderByDescending(u => u.Username.Length > 0).First()).ToList();
 }
}
