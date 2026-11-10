using System.Collections.Concurrent;
using System.Text.Json;
using System.Threading.Channels;
using TopCash.Licenze;

namespace TopCash.Server;

/// Live updates (Server-Sent Events) + a cached, pre-serialized snapshot of the archive.
/// Every write bumps the version; browsers get a tiny "changed" event and re-download only if their ETag is stale.
public sealed class Hub(Store store)
{
 static readonly JsonSerializerOptions Json=new(JsonSerializerDefaults.Web);
 readonly ConcurrentDictionary<Guid,Channel<string>> clients=new();
 readonly Lock gate=new();
 long version=1;byte[]? snapshot;long snapshotVersion;
 public long Version=>Interlocked.Read(ref version);
 /// Changes at every start: a client that cached "v1" before a restart (or an update with new data) must not get a 304.
 public readonly string Boot=DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString("x");
 public sealed record ProgressMessage(string Type,bool Running,bool Connected,string Text);
 public ProgressMessage Progress {get;private set;}=new("progress",false,false,"");
 public DateTimeOffset LastStationContact {get;private set;}

 public void Changed(){Interlocked.Increment(ref version);Broadcast(new{type="changed",version=Version});}
 public void SetProgress(bool running,bool connected,string text){Progress=new("progress",running,connected,text);LastStationContact=DateTimeOffset.Now;Broadcast(Progress);}
 public void StationSeen()=>LastStationContact=DateTimeOffset.Now;

 /// Serialized once per version and shared by every request (also cheap for many devices).
 public byte[] Snapshot()
 {
  lock(gate)
  {
   if(snapshot!=null&&snapshotVersion==Version)return snapshot;
   var v=Version;
   snapshot=JsonSerializer.SerializeToUtf8Bytes(new{records=store.All(),clients=store.Clients(),orders=store.Orders(),mastroClients=store.MastroClients(),machines=store.Machines(),interventions=store.Interventions(),
    warehouses=store.Warehouses(),articles=store.Articles(),stockLevels=store.StockLevels(),stock=store.Stock(),movements=store.Movements(),invoices=store.Invoices(),dues=store.Dues(),balances=store.Balances(),dueNotes=store.DueNotes(),sources=store.Sources(),events=store.Events(),notes=store.Notes(),metadata=store.Metadata(),dtr=store.DtrRegisters(),dtrStatus=store.DtrState(),features=new{mail=store.MailReady(),backupError=Backups.Status(store).Error},version=v},Json);
   snapshotVersion=v;return snapshot;
  }
 }
 void Broadcast(object message){var line=JsonSerializer.Serialize(message,Json);foreach(var c in clients.Values)c.Writer.TryWrite(line);}
 public int Connected=>clients.Count;
 public async Task Stream(HttpContext ctx)
 {
  ctx.Response.Headers.ContentType="text/event-stream";ctx.Response.Headers.CacheControl="no-cache";ctx.Response.Headers["X-Accel-Buffering"]="no";
  var id=Guid.NewGuid();var ch=Channel.CreateBounded<string>(new BoundedChannelOptions(64){FullMode=BoundedChannelFullMode.DropOldest});clients[id]=ch;
  try
  {
   await ctx.Response.WriteAsync($"retry: 3000\ndata: {JsonSerializer.Serialize(new{type="hello",version=Version},Json)}\n\n",ctx.RequestAborted);
   await ctx.Response.WriteAsync($"data: {JsonSerializer.Serialize(Progress,Json)}\n\n",ctx.RequestAborted);
   await ctx.Response.Body.FlushAsync(ctx.RequestAborted);
   while(!ctx.RequestAborted.IsCancellationRequested)
   {
    using var ping=CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted);ping.CancelAfter(TimeSpan.FromSeconds(25));
    try{var msg=await ch.Reader.ReadAsync(ping.Token);await ctx.Response.WriteAsync($"data: {msg}\n\n",ctx.RequestAborted);}
    catch(OperationCanceledException) when(!ctx.RequestAborted.IsCancellationRequested){await ctx.Response.WriteAsync(": ping\n\n",ctx.RequestAborted);} // keeps proxies and Wi-Fi sleep from closing the stream
    await ctx.Response.Body.FlushAsync(ctx.RequestAborted);
   }
  }
  catch(OperationCanceledException){}
  catch(IOException){}
  finally{clients.TryRemove(id,out _);}
 }
}
