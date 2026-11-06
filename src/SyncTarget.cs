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
 public Task<string> Apply(string kind,string product,string scope,List<string> headers,List<List<string>> rows,string capturedAt)=>Task.FromResult(store.ApplyPortalList(kind,product,scope,headers,rows,capturedAt,"Zucchetti · sessione locale"));
 public Task Log(string kind,string message){store.Log(kind,message);return Task.CompletedTask;}
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
 public async Task<string> Apply(string kind,string product,string scope,List<string> headers,List<List<string>> rows,string capturedAt)
 {
  var res=await http.PostAsJsonAsync("api/sync/batch",new{kind,product,scope,headers,rows,capturedAt});
  var body=await res.Content.ReadAsStringAsync();
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
