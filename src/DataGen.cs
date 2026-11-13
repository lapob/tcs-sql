using Microsoft.Data.Sqlite;

namespace TopCash.Licenze;
/// One change counter per table, moved by SQLite itself at every write (whoever writes: server, import, station).
/// The server reuses its heavy calculations (analysis, priorities, balance sheet, invoices, Mastro) until a table
/// they read changes: a note, a photo or the history no longer trigger a full recalculation.
public sealed partial class Store
{
 /// Tables whose changes never alter the archive sent to devices (or that are always read fresh).
 static readonly HashSet<string> Unwatched=["events","imports","sqlite_sequence","data_changes","users","devices","server_settings"];
 /// Tables that only light sections read (notes, follow-ups, warehouse, photos, routes, portal orders…): checked against
 /// Insights, Analisi, Bilancio, Contabilita, Edits, Gestionale, Dtr, ItalRetail, Rch and Team, which never read them.
 public static readonly HashSet<string> LightTables=["notes","client_meta","route_done","photos","signatures","orders","articles","warehouses","stock_levels","movements","assets","sources","report_numbers","machine_flags"];
 static void InitDataGen(SqliteConnection c)
 {
  // mastro_extra is otherwise created on its first read: it must exist now to get its counter
  using(var q=c.CreateCommand()){q.CommandText="CREATE TABLE IF NOT EXISTS data_changes(t TEXT PRIMARY KEY,n INTEGER NOT NULL);CREATE TABLE IF NOT EXISTS mastro_extra(kind TEXT PRIMARY KEY,data TEXT NOT NULL)";q.ExecuteNonQuery();}
  var tables=new List<string>();
  using(var q=c.CreateCommand()){q.CommandText="SELECT name FROM sqlite_master WHERE type='table'";using var r=q.ExecuteReader();while(r.Read())tables.Add(r.GetString(0));}
  foreach(var t in tables.Where(t=>!Unwatched.Contains(t)&&!t.StartsWith("sqlite_")&&t.All(ch=>char.IsAsciiLetterOrDigit(ch)||ch=='_')))
  {
   // portal statuses and the station key change all the time and are read fresh: they do not count
   var when=t=="app_settings"?" WHEN {0}.key NOT LIKE '%.status' AND {0}.key NOT LIKE 'portals.%' AND {0}.key NOT LIKE 'sync_token%'":"";
   foreach(var(op,row) in new[]{("INSERT","NEW"),("UPDATE","NEW"),("DELETE","OLD")})
   {
    using var q=c.CreateCommand();
    q.CommandText=$"CREATE TRIGGER IF NOT EXISTS chg_{t}_{op.ToLowerInvariant()} AFTER {op} ON \"{t}\"{string.Format(when,row)} BEGIN INSERT INTO data_changes VALUES('{t}',1) ON CONFLICT(t) DO UPDATE SET n=n+1; END";
    q.ExecuteNonQuery();
   }
  }
 }
 /// The change counter of every table that has changed at least once (see InitDataGen).
 public Dictionary<string,long> DataChanges(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT t,n FROM data_changes";using var r=q.ExecuteReader();var d=new Dictionary<string,long>();while(r.Read())d[r.GetString(0)]=r.GetInt64(1);return d;}
}
