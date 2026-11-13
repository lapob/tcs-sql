using System.Collections.Concurrent;
using System.Text.Json;
using System.Threading.Channels;
using TopCash.Licenze;

namespace TopCash.Server;

/// Live updates (Server-Sent Events) + a cached, pre-serialized snapshot of the archive.
/// Every write bumps the version; browsers get a tiny "changed" event and re-download only if their ETag is stale.
public sealed class Hub(Store store)
{
 /// The team (names and roles only, never anything else): interventions are assigned to a person, who finds them in "I miei".
 public Func<object[]>? Team;
 /// Portals the server keeps itself (ids): the pages stop pointing at the office station for those.
 public Func<List<string>>? ServerPortals;
 static readonly JsonSerializerOptions Json=new(JsonSerializerDefaults.Web);
 readonly ConcurrentDictionary<Guid,Channel<string>> clients=new();
 readonly Lock gate=new();
 long version=1;
 public long Version=>Interlocked.Read(ref version);
 /// Changes at every start: a client that cached "v1" before a restart (or an update with new data) must not get a 304.
 public readonly string Boot=DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString("x");
 public sealed record ProgressMessage(string Type,bool Running,bool Connected,string Text);
 public ProgressMessage Progress {get;private set;}=new("progress",false,false,"");
 public DateTimeOffset LastStationContact {get;private set;}

 public void Changed(){Interlocked.Increment(ref version);Broadcast(new{type="changed",version=Version});}
 public void SetProgress(bool running,bool connected,string text){Progress=new("progress",running,connected,text);LastStationContact=DateTimeOffset.Now;Broadcast(Progress);}
 public void StationSeen()=>LastStationContact=DateTimeOffset.Now;

 /// The archive is split into named sections, each serialized once per version with a short fingerprint.
 /// A device that already has a copy sends its fingerprints and downloads only the sections that changed
 /// (usually a few KB instead of the whole archive); the full snapshot is assembled from the same bytes.
 sealed record Snap(long Version,byte[] Full,byte[] FullNoAccounting,Dictionary<string,(byte[] Json,string Hash)> Sections);
 /// Sections a technician never receives (accounting stays on the server for them).
 static readonly HashSet<string> Accounting=["moneyInterventions","invoices","dues","balances","dueNotes","riba","sales","ledger","mastroSync","insights","analysis","bilancio"];
 Snap? snap;
 /// Devices get the invoices from January 1st of last year (charts, receivables and client situation never look further back);
 /// the full history stays here, in the CSV export and in Mastro. 40k rows since 2019 were too much for a phone's memory.
 List<Invoice> RecentInvoices(){var from=new DateTime(DateTime.Today.Year-1,1,1).ToString("yyyy-MM-dd");return store.Invoices().Where(i=>string.CompareOrdinal(i.Date,from)>=0).ToList();}
 /// Sales and ledger by month: the current year and the two before (enough for the trend and the year-on-year comparison).
 static string SinceMonth=>new DateTime(DateTime.Today.Year-2,1,1).ToString("yyyy-MM");
 Snap Current()
 {
  lock(gate)
  {
   if(snap!=null&&snap.Version==Version)return snap;
   var v=Version;
   var mv=store.Mastro();
   var parts=new (string,object)[]{("records",store.Licenses()),("licenseEdits",store.LicenseEdits()),("licenseCleanup",store.LicenseCleanup()),("clientEdits",store.ClientEdits()),("clients",store.Clients()),("orders",store.Orders()),
    ("mastroClients",mv.Clients),("machines",mv.Machines),("interventions",mv.Interventions.Where(i=>!MoneyWork.IsMoney(i.Kind)).ToList()),("moneyInterventions",mv.Interventions.Where(i=>MoneyWork.IsMoney(i.Kind)).ToList()),("moneyRule",store.Money()),("brandRule",mv.Excluded),("assets",store.Assets()),("renewals",store.RenewalsView()),("licenseRule",store.LicenseRules()),("deviceLinks",store.DeviceLinks()),
    ("warehouses",store.Warehouses()),("articles",store.Articles()),("stockLevels",store.StockLevels()),("stock",store.Stock()),("movements",store.Movements()),("invoices",RecentInvoices()),("dues",store.Dues()),("balances",store.Balances()),("dueNotes",store.DueNotes()),("sales",store.Sales().Where(x=>string.CompareOrdinal(x.Month,SinceMonth)>=0).ToList()),("ledger",store.Ledger().Where(x=>string.CompareOrdinal(x.Month,SinceMonth)>=0).ToList()),
    ("sources",store.Sources()),("events",store.Events()),("notes",store.Notes()),("metadata",store.Metadata()),("dtr",store.DtrRegisters()),("dtrStatus",store.DtrState()),("ital",store.ItalDevices()),("italOrders",store.ItalOrders()),("italStatus",store.ItalState()),("rch",store.RchDevices()),("rchStatus",store.RchState()),
    ("rt",store.RtInfos()),("machineFlags",store.MachineFlags()),("routeDone",store.RouteDone()),("photos",store.Photos()),("sites",store.Sites()),("riba",store.RibaList()),("team",Team?.Invoke()??[]),("serverPortals",ServerPortals?.Invoke()??[]),
    ("features",new{mail=store.MailReady(),backupError=Backups.Status(store).Error}),("mastroSync",(object?)store.LastMastroSync()??new{}),("insights",store.Insights()),("analysis",store.Analysis()),("bilancio",store.Bilancio())};
   var sections=new Dictionary<string,(byte[],string)>(parts.Length);
   foreach(var(name,value) in parts){var b=JsonSerializer.SerializeToUtf8Bytes(value,value.GetType(),Json);sections[name]=(b,Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(b),0,8));}
   snap=new Snap(v,Compose(v,sections,null,true),Compose(v,sections,null,false),sections);encoded.Clear();return snap;
  }
 }
 /// Writes {"section":…, …, "hashes":{…}, "version":n}; with [only] just those sections plus "partial":true.
 static byte[] Compose(long v,Dictionary<string,(byte[] Json,string Hash)> sections,HashSet<string>? only,bool accounting)
 {
  using var ms=new MemoryStream(only==null?sections.Values.Sum(x=>x.Json.Length)+4096:4096);
  using(var w=new Utf8JsonWriter(ms))
  {
   w.WriteStartObject();
   foreach(var(name,(json,_)) in sections)if((only==null||only.Contains(name))&&(accounting||!Accounting.Contains(name))){w.WritePropertyName(name);w.WriteRawValue(json,skipInputValidation:true);}
   w.WriteStartObject("hashes");foreach(var(name,(_,hash)) in sections)if(accounting||!Accounting.Contains(name))w.WriteString(name,hash);w.WriteEndObject();
   w.WriteNumber("version",v);if(only!=null)w.WriteBoolean("partial",true);
   w.WriteEndObject();
  }
  return ms.ToArray();
 }
 public byte[] Snapshot(bool accounting=true)=>accounting?Current().Full:Current().FullNoAccounting;
 /// Only the sections whose fingerprint differs from [have] ("name=hash,name=hash"); unknown names count as changed.
 public byte[] Delta(string have,bool accounting=true)
 {
  var s=Current();var known=new Dictionary<string,string>();
  foreach(var pair in have.Split(',',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries)){var i=pair.IndexOf('=');if(i>0)known[pair[..i]]=pair[(i+1)..];}
  var changed=s.Sections.Where(kv=>!known.TryGetValue(kv.Key,out var h)||h!=kv.Value.Hash).Select(kv=>kv.Key).ToHashSet();
  return Compose(s.Version,s.Sections,changed,accounting);
 }
 /// The snapshot compressed once per version (Brotli for browsers and phones, gzip as fallback): many MB of archive
 /// become a few, and the server does not recompress them for every device that asks.
 readonly ConcurrentDictionary<string,(long v,byte[] b)> encoded=new();
 public (byte[] body,string? encoding) SnapshotFor(string acceptEncoding,bool accounting=true)
 {
  var cur=Current();var raw=accounting?cur.Full:cur.FullNoAccounting;var v=cur.Version;
  string? enc=acceptEncoding.Contains("br",StringComparison.OrdinalIgnoreCase)?"br":acceptEncoding.Contains("gzip",StringComparison.OrdinalIgnoreCase)?"gzip":null;
  if(enc==null)return(raw,null);
  var key=accounting?enc:enc+"-t";if(encoded.TryGetValue(key,out var e)&&e.v==v)return(e.b,enc);
  using var ms=new MemoryStream();
  using(Stream z=enc=="br"?new System.IO.Compression.BrotliStream(ms,System.IO.Compression.CompressionLevel.Optimal,true):new System.IO.Compression.GZipStream(ms,System.IO.Compression.CompressionLevel.Optimal,true))z.Write(raw);
  var b=ms.ToArray();encoded[key]=(v,b);return(b,enc);
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
