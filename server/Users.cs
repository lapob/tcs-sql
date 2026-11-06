using Microsoft.Data.Sqlite;
using System.Security.Cryptography;
using System.Text;

namespace TopCash.Server;

public static class Roles
{
 public const string Admin="admin",Office="ufficio",Tech="tecnico";
 public static readonly string[] All=[Admin,Office,Tech];
 public static string Label(string r)=>r switch{Admin=>"Amministratore",Office=>"Ufficio",Tech=>"Tecnico",_=>r};
}
public sealed record UserInfo(string Username,string Name,string Role,bool Disabled,string Created,string LastLogin);

/// Accounts live in the same SQLite file as the archive. Passwords: PBKDF2-SHA256, 210k iterations, per-user salt.
public sealed class Users
{
 readonly string cs;
 const int Iterations=210_000;
 public Users(string databasePath)
 {
  cs=new SqliteConnectionStringBuilder{DataSource=databasePath}.ToString();
  using var c=Open();using var q=c.CreateCommand();
  q.CommandText="""
   CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY COLLATE NOCASE,name TEXT NOT NULL,role TEXT NOT NULL,hash BLOB NOT NULL,salt BLOB NOT NULL,iterations INTEGER NOT NULL,disabled INTEGER NOT NULL DEFAULT 0,stamp TEXT NOT NULL,created TEXT NOT NULL,last_login TEXT NOT NULL DEFAULT '');
   CREATE TABLE IF NOT EXISTS server_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
   """;q.ExecuteNonQuery();
 }
 SqliteConnection Open(){var c=new SqliteConnection(cs);c.Open();return c;}
 public bool Any(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT count(*) FROM users";return (long)q.ExecuteScalar()!>0;}
 public static string? ValidatePassword(string p)=>p.Length<10?"La password deve avere almeno 10 caratteri.":p.Distinct().Count()<5?"La password è troppo semplice.":null;
 public static string? ValidateUsername(string u)=>u.Length is <3 or >40||!u.All(ch=>char.IsLetterOrDigit(ch)||ch is '.' or '_' or '-' or '@')?"Nome utente non valido (3-40 caratteri: lettere, numeri, . _ - @).":null;
 public void Create(string username,string name,string role,string password)
 {
  if(ValidateUsername(username) is string e1)throw new InvalidDataException(e1);
  if(ValidatePassword(password) is string e2)throw new InvalidDataException(e2);
  if(!Roles.All.Contains(role))throw new InvalidDataException("Ruolo non valido.");
  if(string.IsNullOrWhiteSpace(name)||name.Length>80)throw new InvalidDataException("Nome visualizzato non valido.");
  var salt=RandomNumberGenerator.GetBytes(16);var hash=Rfc2898DeriveBytes.Pbkdf2(password,salt,Iterations,HashAlgorithmName.SHA256,32);
  using var c=Open();using var q=c.CreateCommand();
  q.CommandText="INSERT INTO users(username,name,role,hash,salt,iterations,stamp,created) VALUES($u,$n,$r,$h,$s,$i,$st,$c)";
  q.Parameters.AddWithValue("$u",username.Trim());q.Parameters.AddWithValue("$n",name.Trim());q.Parameters.AddWithValue("$r",role);q.Parameters.AddWithValue("$h",hash);q.Parameters.AddWithValue("$s",salt);q.Parameters.AddWithValue("$i",Iterations);q.Parameters.AddWithValue("$st",Guid.NewGuid().ToString("N"));q.Parameters.AddWithValue("$c",DateTimeOffset.Now.ToString("O"));
  try{q.ExecuteNonQuery();}catch(SqliteException ex) when(ex.SqliteErrorCode==19){throw new InvalidDataException("Esiste già un utente con questo nome.");}
 }
 /// Returns the user and its security stamp, or null. Constant-time comparison; a dummy hash runs for unknown users.
 public (UserInfo User,string Stamp)? Verify(string username,string password)
 {
  using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT username,name,role,hash,salt,iterations,disabled,stamp,created,last_login FROM users WHERE username=$u";q.Parameters.AddWithValue("$u",username.Trim());
  using var r=q.ExecuteReader();
  if(!r.Read()){Rfc2898DeriveBytes.Pbkdf2(password,new byte[16],Iterations,HashAlgorithmName.SHA256,32);return null;}
  var hash=(byte[])r[3];var test=Rfc2898DeriveBytes.Pbkdf2(password,(byte[])r[4],r.GetInt32(5),HashAlgorithmName.SHA256,32);
  if(!CryptographicOperations.FixedTimeEquals(hash,test)||r.GetInt64(6)!=0)return null;
  var info=new UserInfo(r.GetString(0),r.GetString(1),r.GetString(2),false,r.GetString(8),r.GetString(9));var stamp=r.GetString(7);r.Close();
  using var u=c.CreateCommand();u.CommandText="UPDATE users SET last_login=$t WHERE username=$u";u.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));u.Parameters.AddWithValue("$u",info.Username);u.ExecuteNonQuery();
  return(info,stamp);
 }
 /// Used on every request: a changed stamp (password reset, role change, disable) logs the user out everywhere.
 public (UserInfo User,string Stamp)? Get(string username)
 {
  using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT username,name,role,disabled,stamp,created,last_login FROM users WHERE username=$u";q.Parameters.AddWithValue("$u",username);
  using var r=q.ExecuteReader();if(!r.Read())return null;
  return(new UserInfo(r.GetString(0),r.GetString(1),r.GetString(2),r.GetInt64(3)!=0,r.GetString(5),r.GetString(6)),r.GetString(4));
 }
 public List<UserInfo> List()
 {
  using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT username,name,role,disabled,created,last_login FROM users ORDER BY name";using var r=q.ExecuteReader();var list=new List<UserInfo>();
  while(r.Read())list.Add(new(r.GetString(0),r.GetString(1),r.GetString(2),r.GetInt64(3)!=0,r.GetString(4),r.GetString(5)));return list;
 }
 int ActiveAdmins(SqliteConnection c,SqliteTransaction tx){using var q=c.CreateCommand();q.Transaction=tx;q.CommandText="SELECT count(*) FROM users WHERE role='admin' AND disabled=0";return (int)(long)q.ExecuteScalar()!;}
 public void Update(string username,string? role,bool? disabled,string? name)
 {
  using var c=Open();using var tx=c.BeginTransaction();
  var current=Get(username)??throw new InvalidDataException("Utente non trovato.");
  if(role!=null&&!Roles.All.Contains(role))throw new InvalidDataException("Ruolo non valido.");
  bool losesAdmin=current.User.Role==Roles.Admin&&!current.User.Disabled&&((role!=null&&role!=Roles.Admin)||disabled==true);
  if(losesAdmin&&ActiveAdmins(c,tx)<=1)throw new InvalidDataException("Deve restare almeno un amministratore attivo.");
  using var q=c.CreateCommand();q.Transaction=tx;
  q.CommandText="UPDATE users SET role=coalesce($r,role),disabled=coalesce($d,disabled),name=coalesce($n,name),stamp=$s WHERE username=$u";
  q.Parameters.AddWithValue("$r",(object?)role??DBNull.Value);q.Parameters.AddWithValue("$d",disabled.HasValue?(disabled.Value?1:0):DBNull.Value);q.Parameters.AddWithValue("$n",(object?)name?.Trim()??DBNull.Value);q.Parameters.AddWithValue("$s",Guid.NewGuid().ToString("N"));q.Parameters.AddWithValue("$u",username);
  q.ExecuteNonQuery();tx.Commit();
 }
 public void SetPassword(string username,string password)
 {
  if(ValidatePassword(password) is string e)throw new InvalidDataException(e);
  var salt=RandomNumberGenerator.GetBytes(16);var hash=Rfc2898DeriveBytes.Pbkdf2(password,salt,Iterations,HashAlgorithmName.SHA256,32);
  using var c=Open();using var q=c.CreateCommand();q.CommandText="UPDATE users SET hash=$h,salt=$s,iterations=$i,stamp=$st WHERE username=$u";
  q.Parameters.AddWithValue("$h",hash);q.Parameters.AddWithValue("$s",salt);q.Parameters.AddWithValue("$i",Iterations);q.Parameters.AddWithValue("$st",Guid.NewGuid().ToString("N"));q.Parameters.AddWithValue("$u",username);
  if(q.ExecuteNonQuery()==0)throw new InvalidDataException("Utente non trovato.");
 }
 // ── sync-station token (stored as SHA-256 only; shown once at creation)
 public string NewSyncToken()
 {
  var token="tcs_"+Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_');
  SetSetting("sync_token_sha256",Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token))));SetSetting("sync_token_created",DateTimeOffset.Now.ToString("O"));return token;
 }
 public void RevokeSyncToken(){SetSetting("sync_token_sha256","");}
 public bool CheckSyncToken(string? token)
 {
  var stored=GetSetting("sync_token_sha256");if(string.IsNullOrEmpty(stored)||string.IsNullOrEmpty(token))return false;
  return CryptographicOperations.FixedTimeEquals(Convert.FromHexString(stored),SHA256.HashData(Encoding.UTF8.GetBytes(token)));
 }
 public string SyncTokenCreated()=>string.IsNullOrEmpty(GetSetting("sync_token_sha256"))?"":GetSetting("sync_token_created")??"";
 string? GetSetting(string key){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT value FROM server_settings WHERE key=$k";q.Parameters.AddWithValue("$k",key);return q.ExecuteScalar() as string;}
 void SetSetting(string key,string value){using var c=Open();using var q=c.CreateCommand();q.CommandText="INSERT INTO server_settings VALUES($k,$v) ON CONFLICT(key) DO UPDATE SET value=$v";q.Parameters.AddWithValue("$k",key);q.Parameters.AddWithValue("$v",value);q.ExecuteNonQuery();}
}
