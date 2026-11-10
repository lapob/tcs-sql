using System.Collections.Concurrent;

namespace TopCash.Server;

/// Brute-force protection for sign-ins from outside the office (the public address).
/// Per account: 5 wrong passwords within 15 minutes suspend that account from outside for 15 minutes, doubling at every
/// new suspension up to 24 hours. Per address: 20 wrong passwords within an hour suspend that address for an hour.
/// The office network is never affected, so an attacker cannot lock colleagues out of their own desks.
public sealed class LoginGuard
{
 sealed class Entry { public int Fails; public DateTimeOffset First; public DateTimeOffset Until; public int Strikes; }
 readonly ConcurrentDictionary<string,Entry> map=new(StringComparer.OrdinalIgnoreCase);
 const int UserLimit=5, IpLimit=20;
 static readonly TimeSpan UserWindow=TimeSpan.FromMinutes(15), IpWindow=TimeSpan.FromHours(1), MaxLock=TimeSpan.FromHours(24);

 static string U(string user)=>"u:"+user.Trim();
 static string A(string ip)=>"a:"+ip;

 /// Time left if this address or this account is suspended, otherwise null.
 public TimeSpan? Locked(string ip,string user)
 {
  var now=DateTimeOffset.UtcNow;TimeSpan? left=null;
  foreach(var k in new[]{A(ip),U(user)})
   if(map.TryGetValue(k,out var e)){lock(e){if(e.Until>now){var l=e.Until-now;if(left==null||l>left)left=l;}}}
  return left;
 }

 /// Records a wrong password. Returns a description when it has just triggered a suspension.
 public string? Fail(string ip,string user)
 {
  Prune();
  string? what=null;
  if(Hit(A(ip),IpLimit,IpWindow,_=>IpWindow))what=$"l'indirizzo {ip}";
  if(user.Trim()!=""&&Hit(U(user),UserLimit,UserWindow,s=>TimeSpan.FromTicks(Math.Min(MaxLock.Ticks,TimeSpan.FromMinutes(15).Ticks<<Math.Min(s-1,10)))))
   what=what==null?$"l'utente {user.Trim()}":what+$" e l'utente {user.Trim()}";
  return what;
 }

 /// A correct password clears the account counter (the address counter keeps running).
 public void Success(string ip,string user){if(map.TryGetValue(U(user),out var e))lock(e){e.Fails=0;}}

 bool Hit(string key,int limit,TimeSpan window,Func<int,TimeSpan> lockFor)
 {
  var now=DateTimeOffset.UtcNow;var e=map.GetOrAdd(key,_=>new Entry{First=now});
  lock(e)
  {
   if(now-e.First>window){e.Fails=0;e.First=now;}
   e.Fails++;
   if(e.Fails<limit)return false;
   e.Strikes++;e.Until=now+lockFor(e.Strikes);e.Fails=0;e.First=now;return true;
  }
 }

 void Prune()
 {
  if(map.Count<5000)return;
  var now=DateTimeOffset.UtcNow;
  foreach(var kv in map)if(kv.Value.Until<now&&now-kv.Value.First>MaxLock)map.TryRemove(kv.Key,out _);
 }
}
