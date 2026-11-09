using Microsoft.Data.Sqlite;
using System.Security.Cryptography;
using System.Text;

namespace TopCash.Server;

public sealed record DeviceInfo(string Id,string Name,string Platform,string Status,string User,string Created,string LastSeen,string LastIp,string ApprovedBy,bool Remote);

/// Trusted devices. Each phone / PC / browser holds a random secret (header X-Device-Key or the HttpOnly cookie tcs.dev);
/// only its SHA-256 is stored. From outside the office (Cloudflare tunnel) only approved devices reach the archive.
public sealed class Devices
{
 public const string Pending="pending",Approved="approved",Revoked="revoked";
 const int MaxPending=30;
 readonly string cs;
 // last-seen writes are throttled: one per device per minute is enough
 readonly System.Collections.Concurrent.ConcurrentDictionary<string,DateTime> touched=new();
 public Devices(string databasePath)
 {
  cs=new SqliteConnectionStringBuilder{DataSource=databasePath}.ToString();
  using var c=Open();using var q=c.CreateCommand();
  q.CommandText="CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,key_hash TEXT NOT NULL UNIQUE,name TEXT NOT NULL,platform TEXT NOT NULL DEFAULT '',status TEXT NOT NULL,user TEXT NOT NULL DEFAULT '',created TEXT NOT NULL,last_seen TEXT NOT NULL DEFAULT '',last_ip TEXT NOT NULL DEFAULT '',approved_by TEXT NOT NULL DEFAULT '',remote INTEGER NOT NULL DEFAULT 0)";
  q.ExecuteNonQuery();
 }
 SqliteConnection Open(){var c=new SqliteConnection(cs);c.Open();return c;}
 static string Hash(string key)=>Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(key)));
 public static bool ValidKey(string? k)=>k is {Length:>=32 and <=128}&&k.All(ch=>char.IsAsciiLetterOrDigit(ch)||ch is '-' or '_');
 public static string NewKey()=>Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).Replace('+','-').Replace('/','_').TrimEnd('=');
 /// Short code read aloud to the administrator, e.g. "K7Q-4M2" (no ambiguous characters).
 static string NewCode(){const string a="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";var b=RandomNumberGenerator.GetBytes(6);var s=new string(b.Select(x=>a[x%a.Length]).ToArray());return s[..3]+"-"+s[3..];}

 static DeviceInfo Read(SqliteDataReader r)=>new(r.GetString(0),r.GetString(1),r.GetString(2),r.GetString(3),r.GetString(4),r.GetString(5),r.GetString(6),r.GetString(7),r.GetString(8),r.GetInt64(9)!=0);
 const string Cols="id,name,platform,status,user,created,last_seen,last_ip,approved_by,remote";

 public DeviceInfo? Find(string? key)
 {
  if(!ValidKey(key))return null;
  using var c=Open();using var q=c.CreateCommand();q.CommandText=$"SELECT {Cols} FROM devices WHERE key_hash=$h";q.Parameters.AddWithValue("$h",Hash(key!));
  using var r=q.ExecuteReader();return r.Read()?Read(r):null;
 }
 public List<DeviceInfo> List()
 {
  using var c=Open();using var q=c.CreateCommand();q.CommandText=$"SELECT {Cols} FROM devices ORDER BY CASE status WHEN 'pending' THEN 0 WHEN 'approved' THEN 1 ELSE 2 END,last_seen DESC";
  using var r=q.ExecuteReader();var l=new List<DeviceInfo>();while(r.Read())l.Add(Read(r));return l;
 }
 /// Registers an unknown device as pending (capped, old pending requests expire after 7 days).
 public DeviceInfo Register(string key,string name,string platform,string ip,bool remote,string user="")
 {
  if(Find(key) is DeviceInfo d)return d;
  using var c=Open();
  using(var del=c.CreateCommand()){del.CommandText="DELETE FROM devices WHERE status='pending' AND created<$t";del.Parameters.AddWithValue("$t",DateTimeOffset.Now.AddDays(-7).ToString("O"));del.ExecuteNonQuery();}
  using(var cnt=c.CreateCommand()){cnt.CommandText="SELECT count(*) FROM devices WHERE status='pending'";if((long)cnt.ExecuteScalar()!>=MaxPending)throw new InvalidDataException("Troppe richieste di accesso in attesa: l'amministratore deve prima approvarle o rifiutarle.");}
  var id=NewCode();var now=DateTimeOffset.Now.ToString("O");
  using var q=c.CreateCommand();
  q.CommandText="INSERT INTO devices(id,key_hash,name,platform,status,user,created,last_seen,last_ip,remote) VALUES($id,$h,$n,$p,'pending',$u,$c,$c,$ip,$r)";
  q.Parameters.AddWithValue("$id",id);q.Parameters.AddWithValue("$h",Hash(key));q.Parameters.AddWithValue("$n",Clean(name,"Dispositivo"));q.Parameters.AddWithValue("$p",Clean(platform,""));
  q.Parameters.AddWithValue("$u",user);q.Parameters.AddWithValue("$c",now);q.Parameters.AddWithValue("$ip",ip);q.Parameters.AddWithValue("$r",remote?1:0);q.ExecuteNonQuery();
  return Find(key)!;
 }
 static string Clean(string s,string fallback){s=new string((s??"").Where(ch=>!char.IsControl(ch)).ToArray()).Trim();return s.Length==0?fallback:s.Length>60?s[..60]:s;}
 public void Touch(string key,string ip,bool remote,string? user)
 {
  var h=Hash(key);var now=DateTime.UtcNow;
  if(user==null&&touched.TryGetValue(h,out var t)&&now-t<TimeSpan.FromMinutes(1))return;
  touched[h]=now;
  using var c=Open();using var q=c.CreateCommand();
  q.CommandText="UPDATE devices SET last_seen=$t,last_ip=$ip,remote=$r"+(user!=null?",user=$u":"")+" WHERE key_hash=$h";
  q.Parameters.AddWithValue("$t",DateTimeOffset.Now.ToString("O"));q.Parameters.AddWithValue("$ip",ip);q.Parameters.AddWithValue("$r",remote?1:0);q.Parameters.AddWithValue("$h",h);
  if(user!=null)q.Parameters.AddWithValue("$u",user);
  q.ExecuteNonQuery();
 }
 public void SetStatus(string id,string status,string by)
 {
  if(status is not (Approved or Revoked))throw new InvalidDataException("Stato non valido.");
  using var c=Open();using var q=c.CreateCommand();q.CommandText="UPDATE devices SET status=$s,approved_by=$b WHERE id=$id";
  q.Parameters.AddWithValue("$s",status);q.Parameters.AddWithValue("$b",by);q.Parameters.AddWithValue("$id",id);
  if(q.ExecuteNonQuery()==0)throw new InvalidDataException("Dispositivo non trovato.");
 }
 public void Rename(string id,string name)
 {
  using var c=Open();using var q=c.CreateCommand();q.CommandText="UPDATE devices SET name=$n WHERE id=$id";q.Parameters.AddWithValue("$n",Clean(name,"Dispositivo"));q.Parameters.AddWithValue("$id",id);
  if(q.ExecuteNonQuery()==0)throw new InvalidDataException("Dispositivo non trovato.");
 }
 public void Delete(string id){using var c=Open();using var q=c.CreateCommand();q.CommandText="DELETE FROM devices WHERE id=$id";q.Parameters.AddWithValue("$id",id);q.ExecuteNonQuery();}
 public int PendingCount(){using var c=Open();using var q=c.CreateCommand();q.CommandText="SELECT count(*) FROM devices WHERE status='pending'";return (int)(long)q.ExecuteScalar()!;}
}
