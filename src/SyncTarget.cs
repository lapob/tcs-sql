using System.Net.Http;
using System.Net.Http.Json;

namespace TopCash.Licenze;
/// Where the portal sync delivers what it reads: the local database or the shared Top Cash server.
public interface ISyncTarget
{
 string Label {get;}
 Task<string> Apply(string kind,string product,string scope,List<string> headers,List<List<string>> rows,string capturedAt);
 Task Log(string kind,string message);
 Task Progress(bool running,bool connected,string text);
}
public sealed class LocalSyncTarget(Store store):ISyncTarget
{
 public string Label=>"archivio locale";
 public Task<string> Apply(string kind,string product,string scope,List<string> headers,List<List<string>> rows,string capturedAt)=>Task.Run(()=>store.ApplyPortalList(kind,product,scope,headers,rows,capturedAt,"Zucchetti · sessione locale"));// written away from the window: the app never freezes while a big list is saved
 public Task Log(string kind,string message)=>Task.Run(()=>store.Log(kind,message));
 public Task Progress(bool running,bool connected,string text)=>Task.CompletedTask;
}
/// Sends each completed list to the shared server. Authenticated by the sync-station token (never a user password).
public sealed class RemoteSyncTarget:ISyncTarget
{
 readonly HttpClient http;
 public RemoteSyncTarget(string baseUrl,string token)
 {
  http=new HttpClient{BaseAddress=new Uri(baseUrl.TrimEnd('/')+"/"),Timeout=TimeSpan.FromMinutes(2)};
  http.DefaultRequestHeaders.Add("X-Sync-Token",token);http.DefaultRequestHeaders.Add("X-Requested-With","topcash");
 }
 public string Label=>"server condiviso "+http.BaseAddress?.Host;
 /// Which portals the server reads by itself (null when it cannot be reached or is older and does not know).
 public static async Task<List<string>?> Hosted(string baseUrl,string token)
 {
  using var h=new HttpClient{BaseAddress=new Uri(baseUrl.TrimEnd('/')+"/"),Timeout=TimeSpan.FromSeconds(15)};
  h.DefaultRequestHeaders.Add("X-Sync-Token",token);h.DefaultRequestHeaders.Add("X-Requested-With","topcash");
  try{var r=await h.GetAsync("api/sync/hosted");if(r.StatusCode==System.Net.HttpStatusCode.NotFound)return [];if(!r.IsSuccessStatusCode)return null;return await r.Content.ReadFromJsonAsync<List<string>>()??[];}
  catch{return null;}
 }
 /// Portals the owner asked this office PC to read now ("Aggiorna" on the site); null when the server cannot be reached
 /// or is older and does not know. The server forgets them once handed over.
 public static async Task<List<string>?> Asks(string baseUrl,string token)
 {
  using var h=new HttpClient{BaseAddress=new Uri(baseUrl.TrimEnd('/')+"/"),Timeout=TimeSpan.FromSeconds(15)};
  h.DefaultRequestHeaders.Add("X-Sync-Token",token);h.DefaultRequestHeaders.Add("X-Requested-With","topcash");
  try{var r=await h.GetAsync("api/sync/asks");if(!r.IsSuccessStatusCode)return null;return await r.Content.ReadFromJsonAsync<List<string>>()??[];}
  catch{return null;}
 }
 public async Task<string> Apply(string kind,string product,string scope,List<string> headers,List<List<string>> rows,string capturedAt)
 {
  var res=await http.PostAsJsonAsync("api/sync/batch",new{kind,product,scope,headers,rows,capturedAt});
  var body=await res.Content.ReadAsStringAsync();
  if(res.StatusCode==System.Net.HttpStatusCode.Conflict)throw new InvalidOperationException("Questo portale ora lo legge il server: la postazione non serve più.");
  if(!res.IsSuccessStatusCode)throw new InvalidOperationException($"Server: {(int)res.StatusCode} {body}");
  return body.Trim('"');
 }
 public async Task Log(string kind,string message){try{await http.PostAsJsonAsync("api/sync/log",new{kind,message});}catch{}}
 DateTime lastProgress;bool lastRunning;
 public async Task Progress(bool running,bool connected,string text)
 {
  // At most ~2 updates per second; start/stop transitions are always delivered.
  if(running==lastRunning&&(DateTime.UtcNow-lastProgress).TotalMilliseconds<500)return;
  lastProgress=DateTime.UtcNow;lastRunning=running;
  try{await http.PostAsJsonAsync("api/sync/progress",new{running,connected,text});}catch{}
 }
}
