using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.ResponseCompression;
using Microsoft.Extensions.FileProviders;
using System.IO.Compression;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using System.Threading.RateLimiting;
using TopCash.Licenze;
using TopCash.Server;

// ───────────────────────── configuration
var dataDir=args.FirstOrDefault(a=>a.StartsWith("--data="))?[7..]??Environment.GetEnvironmentVariable("TOPCASH_DATA")??FindDataFolder();
Directory.CreateDirectory(dataDir);
var store=new Store(dataDir);
var users=new Users(store.DatabasePath);
var hub=new Hub(store);
var uiDir=Path.Combine(AppContext.BaseDirectory,"ui");

var builder=WebApplication.CreateBuilder(new WebApplicationOptions{Args=args,ContentRootPath=AppContext.BaseDirectory});
builder.Host.UseWindowsService(o=>o.ServiceName="TopCashStudio");
if(!args.Any(a=>a.StartsWith("--urls"))&&builder.Configuration["Kestrel:Endpoints:Http:Url"]==null&&Environment.GetEnvironmentVariable("ASPNETCORE_URLS")==null)
 builder.WebHost.UseUrls("http://0.0.0.0:8470");
builder.Services.AddSingleton(store).AddSingleton(users).AddSingleton(hub);
// Session-cookie keys: on disk next to the data, encrypted with machine DPAPI (works for the service and the console run).
builder.Services.AddDataProtection().SetApplicationName("TopCashStudio").PersistKeysToFileSystem(new DirectoryInfo(Path.Combine(dataDir,"keys"))).ProtectKeysWithDpapi(protectToLocalMachine:true);
builder.Services.AddResponseCompression(o=>{o.EnableForHttps=true;o.Providers.Add<BrotliCompressionProvider>();o.Providers.Add<GzipCompressionProvider>();o.MimeTypes=ResponseCompressionDefaults.MimeTypes.Concat(["application/json","image/svg+xml","application/manifest+json"]);});
builder.Services.Configure<BrotliCompressionProviderOptions>(o=>o.Level=CompressionLevel.Fastest);
builder.Services.Configure<GzipCompressionProviderOptions>(o=>o.Level=CompressionLevel.Fastest);
builder.Services.AddAuthentication(CookieAuthenticationDefaults.AuthenticationScheme).AddCookie(o=>
{
 o.Cookie.Name="tcs.auth";o.Cookie.HttpOnly=true;o.Cookie.SameSite=SameSiteMode.Strict;o.Cookie.SecurePolicy=CookieSecurePolicy.SameAsRequest;
 o.ExpireTimeSpan=TimeSpan.FromDays(14);o.SlidingExpiration=true;
 o.Events.OnRedirectToLogin=c=>{c.Response.StatusCode=401;return Task.CompletedTask;};
 o.Events.OnRedirectToAccessDenied=c=>{c.Response.StatusCode=403;return Task.CompletedTask;};
 // Password reset, role change or deactivation invalidates every open session of that user.
 o.Events.OnValidatePrincipal=c=>
 {
  var name=c.Principal?.Identity?.Name;var stamp=c.Principal?.FindFirstValue("stamp");var u=name==null?null:users.Get(name);
  if(u==null||u.Value.User.Disabled||u.Value.Stamp!=stamp){c.RejectPrincipal();return c.HttpContext.SignOutAsync();}
  return Task.CompletedTask;
 };
});
builder.Services.AddAuthorization(o=>
{
 o.AddPolicy("read",p=>p.RequireAuthenticatedUser());
 o.AddPolicy("export",p=>p.RequireRole(Roles.Admin,Roles.Office));
 o.AddPolicy("admin",p=>p.RequireRole(Roles.Admin));
});
builder.Services.AddRateLimiter(o=>
{
 o.RejectionStatusCode=429;
 o.AddPolicy("login",ctx=>RateLimitPartition.GetFixedWindowLimiter(ctx.Connection.RemoteIpAddress?.ToString()??"?",_=>new FixedWindowRateLimiterOptions{PermitLimit=8,Window=TimeSpan.FromMinutes(1)}));
 o.AddPolicy("sync",ctx=>RateLimitPartition.GetFixedWindowLimiter(ctx.Connection.RemoteIpAddress?.ToString()??"?",_=>new FixedWindowRateLimiterOptions{PermitLimit=600,Window=TimeSpan.FromMinutes(1)}));
});
builder.Services.ConfigureHttpJsonOptions(o=>o.SerializerOptions.PropertyNameCaseInsensitive=true);
builder.WebHost.ConfigureKestrel(k=>k.Limits.MaxRequestBodySize=25_000_000);

var app=builder.Build();

// ───────────────────────── pipeline
app.Use(async(ctx,next)=>
{
 var h=ctx.Response.Headers;h["X-Content-Type-Options"]="nosniff";h["Referrer-Policy"]="no-referrer";h["X-Frame-Options"]="DENY";h["Permissions-Policy"]="camera=(), microphone=(), geolocation=()";
 // CSRF guard: every state-changing API call must carry a custom header (cannot be sent cross-site without CORS).
 if(ctx.Request.Path.StartsWithSegments("/api")&&!HttpMethods.IsGet(ctx.Request.Method)&&!HttpMethods.IsHead(ctx.Request.Method)&&ctx.Request.Headers["X-Requested-With"]!="topcash")
 {ctx.Response.StatusCode=400;await ctx.Response.WriteAsync("Richiesta non valida.");return;}
 await next();
});
app.UseResponseCompression();
app.UseDefaultFiles(new DefaultFilesOptions{FileProvider=new PhysicalFileProvider(uiDir)});
app.UseStaticFiles(new StaticFileOptions
{
 FileProvider=new PhysicalFileProvider(uiDir),
 ContentTypeProvider=new Microsoft.AspNetCore.StaticFiles.FileExtensionContentTypeProvider{Mappings={[".webmanifest"]="application/manifest+json"}},
 OnPrepareResponse=c=>c.Context.Response.Headers.CacheControl=c.File.Name.EndsWith(".png")?"public, max-age=604800":"no-cache" // code always revalidated: updates reach every device at once
});
app.UseAuthentication();app.UseRateLimiter();app.UseAuthorization();

string Who(HttpContext c)=>c.User.FindFirstValue("display")??c.User.Identity?.Name??"?";
object Me(UserInfo u)=>new{u.Username,u.Name,u.Role,roleLabel=Roles.Label(u.Role)};
async Task SignIn(HttpContext ctx,UserInfo u,string stamp,bool remember)
{
 var id=new ClaimsIdentity([new(ClaimTypes.Name,u.Username),new(ClaimTypes.Role,u.Role),new("display",u.Name),new("stamp",stamp)],CookieAuthenticationDefaults.AuthenticationScheme);
 await ctx.SignInAsync(new ClaimsPrincipal(id),new AuthenticationProperties{IsPersistent=remember});
}
IResult Bad(string m)=>Results.BadRequest(new{error=m});

// ───────────────────────── session & accounts
app.MapGet("/api/session",(HttpContext ctx)=>
{
 var name=ctx.User.Identity?.IsAuthenticated==true?ctx.User.Identity.Name:null;var u=name==null?null:users.Get(name);
 return Results.Ok(new{setupNeeded=!users.Any(),user=u==null?null:Me(u.Value.User),version=hub.Version});
});
app.MapPost("/api/setup",async(HttpContext ctx,SetupRequest r)=>
{
 if(users.Any())return Results.Conflict(new{error="Configurazione già completata."});
 try{users.Create(r.Username,r.Name,Roles.Admin,r.Password);}catch(InvalidDataException ex){return Bad(ex.Message);}
 var u=users.Get(r.Username)!.Value;await SignIn(ctx,u.User,u.Stamp,true);store.Log("users",$"Primo amministratore creato: {u.User.Name}.");hub.Changed();
 return Results.Ok(Me(u.User));
}).RequireRateLimiting("login");
app.MapPost("/api/login",async(HttpContext ctx,LoginRequest r)=>
{
 var v=users.Verify(r.Username??"",r.Password??"");
 if(v==null){await Task.Delay(Random.Shared.Next(200,500));return Results.Json(new{error="Nome utente o password non corretti."},statusCode:401);}
 await SignIn(ctx,v.Value.User,v.Value.Stamp,r.Remember);return Results.Ok(Me(v.Value.User));
}).RequireRateLimiting("login");
app.MapPost("/api/logout",async(HttpContext ctx)=>{await ctx.SignOutAsync();return Results.Ok();});
app.MapPost("/api/password",(HttpContext ctx,PasswordRequest r)=>
{
 if(users.Verify(ctx.User.Identity!.Name!,r.Current??"")==null)return Bad("La password attuale non è corretta.");
 try{users.SetPassword(ctx.User.Identity!.Name!,r.Password??"");}catch(InvalidDataException ex){return Bad(ex.Message);}
 return Results.Ok(new{message="Password aggiornata. Accedi di nuovo con la nuova password."});
}).RequireAuthorization("read");

app.MapGet("/api/users",()=>Results.Ok(new{users=users.List().Select(u=>new{u.Username,u.Name,u.Role,roleLabel=Roles.Label(u.Role),u.Disabled,u.Created,u.LastLogin}),syncTokenCreated=users.SyncTokenCreated(),stationLastContact=hub.LastStationContact==default?"":hub.LastStationContact.ToString("O"),connected=hub.Connected})).RequireAuthorization("admin");
app.MapPost("/api/users",(HttpContext ctx,UserRequest r)=>
{
 try{users.Create(r.Username??"",r.Name??"",r.Role??"",r.Password??"");}catch(InvalidDataException ex){return Bad(ex.Message);}
 store.Log("users",$"{Who(ctx)} ha creato l'utente {r.Name} ({Roles.Label(r.Role!)}).");hub.Changed();return Results.Ok();
}).RequireAuthorization("admin");
app.MapPost("/api/users/{username}",(HttpContext ctx,string username,UserUpdate r)=>
{
 try
 {
  if(r.Role!=null||r.Disabled!=null||r.Name!=null)users.Update(username,r.Role,r.Disabled,r.Name);
  if(!string.IsNullOrEmpty(r.Password))users.SetPassword(username,r.Password);
 }catch(InvalidDataException ex){return Bad(ex.Message);}
 store.Log("users",$"{Who(ctx)} ha modificato l'utente {username}.");hub.Changed();return Results.Ok();
}).RequireAuthorization("admin");
app.MapPost("/api/sync/token",(HttpContext ctx)=>{var t=users.NewSyncToken();store.Log("users",$"{Who(ctx)} ha creato una nuova chiave per la postazione di sincronizzazione.");return Results.Ok(new{token=t});}).RequireAuthorization("admin");
app.MapPost("/api/sync/token/revoke",(HttpContext ctx)=>{users.RevokeSyncToken();store.Log("users",$"{Who(ctx)} ha revocato la chiave della postazione di sincronizzazione.");return Results.Ok();}).RequireAuthorization("admin");

// ───────────────────────── archive
app.MapGet("/api/state",(HttpContext ctx)=>
{
 var etag=$"\"v{hub.Version}\"";ctx.Response.Headers.ETag=etag;ctx.Response.Headers.CacheControl="no-cache";
 if(ctx.Request.Headers.IfNoneMatch.ToString()==etag)return Results.StatusCode(304);
 return Results.Bytes(hub.Snapshot(),"application/json");
}).RequireAuthorization("read");
app.MapGet("/api/stream",(HttpContext ctx)=>hub.Stream(ctx)).RequireAuthorization("read");
app.MapPost("/api/note",(HttpContext ctx,NoteRequest r)=>
{
 if(string.IsNullOrWhiteSpace(r.Code)||r.Code.Length>40)return Bad("Codice cliente mancante.");
 try{store.SaveNote(r.Code,r.Note??"");}catch(InvalidDataException ex){return Bad(ex.Message);}
 store.Log("edit",$"{Who(ctx)} ha aggiornato le note del cliente {r.Code.TrimStart('0')}.");hub.Changed();return Results.Ok();
}).RequireAuthorization("read");
app.MapPost("/api/meta",(HttpContext ctx,MetaRequest r)=>
{
 if(string.IsNullOrWhiteSpace(r.Code)||r.Code.Length>40||r.Meta==null)return Bad("Dati del promemoria incompleti.");
 try{store.SaveMeta(r.Code,r.Meta with{NextContact=r.Meta.NextContact??"",FollowupState=r.Meta.FollowupState??""});}catch(InvalidDataException ex){return Bad(ex.Message);}
 hub.Changed();return Results.Ok();
}).RequireAuthorization("read");
app.MapGet("/api/export/clients.csv",()=>Results.File(Utf8Bom(Imports.ClientsCsv(store.Clients(),store.All(),store.Metadata(),store.Notes())),"text/csv; charset=utf-8",$"TopCash-anagrafica-clienti-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("export");
app.MapGet("/api/export/licenses.csv",()=>Results.File(Utf8Bom(Imports.CsvExport(store.All())),"text/csv; charset=utf-8",$"TopCash-licenze-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("export");
app.MapGet("/api/export/calendar.ics",()=>Results.File(Encoding.UTF8.GetBytes(Imports.Calendar(store.All().Where(r=>!r.Scope.EndsWith("-scadute")))),"text/calendar; charset=utf-8","TopCash-scadenze-90-giorni.ics")).RequireAuthorization("export");
app.MapGet("/api/backup",(HttpContext ctx)=>
{
 var tmp=Paths.TempFile(".sqlite");
 try{store.Backup(tmp);var bytes=File.ReadAllBytes(tmp);store.Log("backup",$"{Who(ctx)} ha scaricato un backup del database.");return Results.File(bytes,"application/vnd.sqlite3",$"TopCash-backup-{DateTime.Now:yyyyMMdd-HHmm}.sqlite");}
 finally{Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();try{File.Delete(tmp);}catch{}}
}).RequireAuthorization("admin");
app.MapPost("/api/import",async(HttpContext ctx)=>
{
 var form=await ctx.Request.ReadFormAsync();var file=form.Files.FirstOrDefault();if(file==null)return Bad("Nessun file ricevuto.");
 var ext=Path.GetExtension(file.FileName).ToLowerInvariant();if(ext is not (".csv" or ".json" or ".xlsx"))return Bad("Formato non supportato (CSV, JSON, XLSX).");
 var tmp=Paths.TempFile(ext);
 try
 {
  await using(var fs=File.Create(tmp))await file.CopyToAsync(fs);
  var (batch,hash)=Imports.Read(tmp);if(store.WasImported(hash))return Results.Ok(new{message="File già importato in precedenza."});
  var r=store.Import(batch,file.FileName,hash);store.Log("import",$"{Who(ctx)} ha importato {file.FileName}.");hub.Changed();
  return Results.Ok(new{message=$"{r.Rows} licenze · {r.Added} nuove · {r.Changed} aggiornate."});
 }
 catch(InvalidDataException ex){return Bad(ex.Message);}
 finally{try{File.Delete(tmp);}catch{}}
}).RequireAuthorization("export").DisableAntiforgery();

// ───────────────────────── gestionale: Mastro import & interventions
app.MapPost("/api/mastro/import",(HttpContext ctx,MastroBatch b)=>
{
 try{var msg=store.ImportMastro(b,Who(ctx));hub.Changed();return Results.Ok(new{message=msg});}
 catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("export");
app.MapPost("/api/interventions",(HttpContext ctx,Intervention i)=>
{
 try{var r=store.SaveIntervention(i,Who(ctx));hub.Changed();return Results.Ok(r);}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("read");
app.MapPost("/api/interventions/{id}/delete",(HttpContext ctx,string id)=>
{
 try{store.DeleteIntervention(id);store.Log("edit",$"{Who(ctx)} ha eliminato un intervento.");hub.Changed();return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("export");

// ───────────────────────── intervention report (signature + PDF)
app.MapPost("/api/interventions/{id}/sign",(HttpContext ctx,string id,SignRequest r)=>
{
 byte[] png;try{png=Convert.FromBase64String((r?.Signature??"").Split(',').Last());}catch{return Bad("Firma non valida.");}
 try{var i=store.SignIntervention(id,r!.Signer??"",r.Hours??"",png,r.Close,Who(ctx));hub.Changed();return Results.Ok(i);}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("read");
app.MapPost("/api/interventions/{id}/unsign",(HttpContext ctx,string id)=>{try{store.UnsignIntervention(id,Who(ctx));hub.Changed();return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("export");
app.MapGet("/api/interventions/{id}/report.pdf",(string id)=>
{
 var i=store.Interventions().FirstOrDefault(x=>x.Id==id);if(i==null)return Results.NotFound();
 try{return Results.File(InterventionReport.Build(store,id),"application/pdf",InterventionReport.FileName(i));}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("read");

// ───────────────────────── warehouse
bool CanManage(HttpContext ctx)=>ctx.User.IsInRole(Roles.Admin)||ctx.User.IsInRole(Roles.Office);
app.MapPost("/api/warehouse/movements",(HttpContext ctx,MovementsRequest r)=>
{
 if(r?.Movements==null||r.Movements.Count==0)return Bad("Nessun movimento.");
 // technicians move stock (load, unload, transfer); corrections and stock counts are for office / admin
 if(!CanManage(ctx)&&r.Movements.Any(m=>m?.Type is "rettifica" or "inventario"))return Results.StatusCode(403);
 try{var saved=store.AddMovements(r.Movements,Who(ctx));hub.Changed();return Results.Ok(new{count=saved.Count});}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("read");
app.MapPost("/api/warehouse/inventory",(HttpContext ctx,InventoryRequest r)=>
{
 if(r==null||string.IsNullOrWhiteSpace(r.Warehouse)||r.Counted==null)return Bad("Inventario incompleto.");
 try{var n=store.RecordInventory(r.Warehouse,r.Counted,Who(ctx));hub.Changed();return Results.Ok(new{differences=n});}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("export");
app.MapPost("/api/warehouse/articles",(HttpContext ctx,Article a)=>{try{var r=store.SaveArticle(a,Who(ctx));hub.Changed();return Results.Ok(r);}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("export");
app.MapPost("/api/warehouse/warehouses",(HttpContext ctx,Warehouse w)=>{try{var r=store.SaveWarehouse(w,Who(ctx));hub.Changed();return Results.Ok(r);}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("export");
app.MapPost("/api/warehouse/levels",(HttpContext ctx,StockLevel l)=>{try{store.SaveStockLevel(l);hub.Changed();return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("export");
app.MapGet("/api/export/stock.csv",()=>Results.File(Utf8Bom(WarehouseReports.StockCsv(store)),"text/csv; charset=utf-8",$"TopCash-giacenze-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("export");
app.MapGet("/api/export/reorder.csv",()=>Results.File(Utf8Bom(WarehouseReports.ReorderCsv(store)),"text/csv; charset=utf-8",$"TopCash-proposta-ordine-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("export");

// ───────────────────────── sync station (token, no user session)
bool Station(HttpContext ctx){var ok=users.CheckSyncToken(ctx.Request.Headers["X-Sync-Token"]);if(ok)hub.StationSeen();return ok;}
app.MapPost("/api/sync/batch",(HttpContext ctx,BatchRequest b)=>
{
 if(!Station(ctx))return Results.Unauthorized();
 if(b?.Rows==null||b.Headers==null||string.IsNullOrEmpty(b.Kind)||string.IsNullOrEmpty(b.Scope))return Bad("Elenco incompleto.");
 if(b.Rows.Count>200_000)return Bad("Elenco troppo grande.");
 try{var msg=store.ApplyPortalList(b.Kind,b.Product,b.Scope,b.Headers,b.Rows,b.CapturedAt,"Zucchetti · postazione di sincronizzazione");hub.Changed();return Results.Ok(msg);}
 catch(InvalidDataException ex){store.Log("error",b.Scope+": "+ex.Message);hub.Changed();return Bad(ex.Message);}
}).RequireRateLimiting("sync");
app.MapPost("/api/sync/log",(HttpContext ctx,LogRequest r)=>{if(!Station(ctx))return Results.Unauthorized();store.Log(r.Kind is "error" or "portal" or "check"?r.Kind:"portal",r.Message.Length>2000?r.Message[..2000]:r.Message);hub.Changed();return Results.Ok();}).RequireRateLimiting("sync");
app.MapPost("/api/sync/progress",(HttpContext ctx,ProgressRequest r)=>{if(!Station(ctx))return Results.Unauthorized();hub.SetProgress(r.Running,r.Connected,r.Text.Length>300?r.Text[..300]:r.Text);return Results.Ok();}).RequireRateLimiting("sync");
app.MapGet("/api/health",()=>Results.Ok(new{ok=true,version=hub.Version}));

// Automatic daily backup (data\backups, last 30 kept): consistent SQLite online backup, no downtime.
var backupDir=Path.Combine(dataDir,"backups");
void DailyBackup()
{
 try
 {
  Directory.CreateDirectory(backupDir);var target=Path.Combine(backupDir,$"TopCash-{DateTime.Now:yyyyMMdd}.sqlite");
  if(File.Exists(target))return;store.Backup(target);Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
  foreach(var old in new DirectoryInfo(backupDir).GetFiles("TopCash-*.sqlite").OrderByDescending(f=>f.Name).Skip(30))old.Delete();
  store.Log("backup","Backup automatico giornaliero creato.");hub.Changed();
 }
 catch(Exception ex){store.Log("error","Backup automatico: "+ex.Message);}
}
var backupTimer=new Timer(_=>DailyBackup(),null,TimeSpan.FromMinutes(1),TimeSpan.FromHours(1)); // hourly check, one file per day
app.Lifetime.ApplicationStopping.Register(()=>backupTimer.Dispose());

store.Log("server","Server Top Cash avviato.");
app.Run();

static byte[] Utf8Bom(string s)=>[..Encoding.UTF8.GetPreamble(),..Encoding.UTF8.GetBytes(s)];
static string FindDataFolder()
{
 for(var dir=new DirectoryInfo(AppContext.BaseDirectory);dir!=null;dir=dir.Parent)
 {var c=Path.Combine(dir.FullName,"data");if(File.Exists(Path.Combine(c,"clienti-licenze.sqlite")))return c;}
 return Path.Combine(AppContext.BaseDirectory,"data");
}

record SetupRequest(string Username,string Name,string Password);
record LoginRequest(string? Username,string? Password,bool Remember);
record PasswordRequest(string? Current,string? Password);
record UserRequest(string? Username,string? Name,string? Role,string? Password);
record UserUpdate(string? Role,bool? Disabled,string? Name,string? Password);
record NoteRequest(string Code,string? Note);
record MetaRequest(string Code,ClientMeta Meta);
record BatchRequest(string Kind,string Product,string Scope,List<string> Headers,List<List<string>> Rows,string CapturedAt);
record LogRequest(string Kind,string Message);
record ProgressRequest(bool Running,bool Connected,string Text);
record MovementsRequest(List<Movement> Movements);
record SignRequest(string? Signer,string? Hours,string? Signature,bool Close);
record InventoryRequest(string Warehouse,Dictionary<string,decimal> Counted);
