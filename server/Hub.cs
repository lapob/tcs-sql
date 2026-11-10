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
   var mv=store.Mastro();
   snapshot=JsonSerializer.SerializeToUtf8Bytes(new{records=store.Licenses(),licenseEdits=store.LicenseEdits(),licenseCleanup=store.LicenseCleanup(),clientEdits=store.ClientEdits(),clients=store.Clients(),orders=store.Orders(),mastroClients=mv.Clients,machines=mv.Machines,interventions=mv.Interventions,brandRule=mv.Excluded,assets=store.Assets(),renewals=store.Renewals(),deviceLinks=store.DeviceLinks(),
    warehouses=store.Warehouses(),articles=store.Articles(),stockLevels=store.StockLevels(),stock=store.Stock(),movements=store.Movements(),invoices=store.Invoices(),dues=store.Dues(),balances=store.Balances(),dueNotes=store.DueNotes(),sources=store.Sources(),events=store.Events(),notes=store.Notes(),metadata=store.Metadata(),dtr=store.DtrRegisters(),dtrStatus=store.DtrState(),ital=store.ItalDevices(),italOrders=store.ItalOrders(),italStatus=store.ItalState(),features=new{mail=store.MailReady(),backupError=Backups.Status(store).Error},version=v},Json);
   snapshotVersion=v;encoded.Clear();return snapshot;
  }
 }
 /// The snapshot compressed once per version (Brotli for browsers and phones, gzip as fallback): many MB of archive
 /// become a few, and the server does not recompress them for every device that asks.
 readonly ConcurrentDictionary<string,(long v,byte[] b)> encoded=new();
 public (byte[] body,string? encoding) SnapshotFor(string acceptEncoding)
 {
  var raw=Snapshot();long v;lock(gate){raw=snapshot??raw;v=snapshotVersion;}
  string? enc=acceptEncoding.Contains("br",StringComparison.OrdinalIgnoreCase)?"br":acceptEncoding.Contains("gzip",StringComparison.OrdinalIgnoreCase)?"gzip":null;
  if(enc==null)return(raw,null);
  if(encoded.TryGetValue(enc,out var e)&&e.v==v)return(e.b,enc);
  using var ms=new MemoryStream();
  using(Stream z=enc=="br"?new System.IO.Compression.BrotliStream(ms,System.IO.Compression.CompressionLevel.Optimal,true):new System.IO.Compression.GZipStream(ms,System.IO.Compression.CompressionLevel.Optimal,true))z.Write(raw);
  var b=ms.ToArray();encoded[enc]=(v,b);return(b,enc);
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
