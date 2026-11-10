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
var devices=new Devices(store.DatabasePath);
var updates=new Updates(dataDir);
var uiDir=Path.Combine(AppContext.BaseDirectory,"ui");

var builder=WebApplication.CreateBuilder(new WebApplicationOptions{Args=args,ContentRootPath=AppContext.BaseDirectory});
builder.Host.UseWindowsService(o=>o.ServiceName="TopCashStudio");
// Two doors: the office network (LAN, default 8470) and the private tunnel (Cloudflare → 127.0.0.1 only, default LAN+2).
// Everything that arrives on the tunnel door is "outside the office" and needs an approved device.
var lanUrl=new Uri((args.FirstOrDefault(a=>a.StartsWith("--urls="))?[7..]??Environment.GetEnvironmentVariable("ASPNETCORE_URLS")??"http://0.0.0.0:8470").Split(';')[0].Replace("://*","://0.0.0.0").Replace("://+","://0.0.0.0"));
// the short port is a convenience: if something else already owns it (IIS, another web server) skip it instead of failing the whole server
// public address (Cloudflare tunnel, e.g. https://tcsql.it): written by collega-esterno.ps1 in the data folder and handed to the apps,
// so every PC and phone learns it while in the office and switches to it by itself outside
string PublicUrl(){try{var f=Path.Combine(dataDir,"indirizzo-pubblico.txt");if(!File.Exists(f))return "";var h=File.ReadAllText(f).Trim().ToLowerInvariant();h=System.Text.RegularExpressions.Regex.Replace(h,"^https?://","").TrimEnd('/');
 return System.Text.RegularExpressions.Regex.IsMatch(h,@"^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")?"https://"+h:"";}catch{return "";}}
var tunnelPort=int.TryParse(args.FirstOrDefault(a=>a.StartsWith("--tunnel-port="))?[14..],out var tp)?tp:lanUrl.Port+2;
builder.WebHost.UseSetting("urls","");
builder.WebHost.ConfigureKestrel(k=>
{
 if(lanUrl.Host is "0.0.0.0")k.ListenAnyIP(lanUrl.Port);else k.Listen(System.Net.IPAddress.Parse(lanUrl.Host is "localhost"?"127.0.0.1":lanUrl.Host),lanUrl.Port);
 k.ListenLocalhost(tunnelPort);
});
builder.Services.AddSingleton(store).AddSingleton(users).AddSingleton(hub).AddSingleton(devices);
// the tunnel (cloudflared on this PC) forwards the real client IP and https: trusted only from loopback
builder.Services.Configure<Microsoft.AspNetCore.Builder.ForwardedHeadersOptions>(o=>{o.ForwardedHeaders=Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedFor|Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedProto;o.ForwardLimit=1;});
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
 o.AddPolicy("mail",ctx=>RateLimitPartition.GetFixedWindowLimiter(ctx.User.Identity?.Name??"?",_=>new FixedWindowRateLimiterOptions{PermitLimit=20,Window=TimeSpan.FromMinutes(10)}));
 o.AddPolicy("sync",ctx=>RateLimitPartition.GetFixedWindowLimiter(ctx.Connection.RemoteIpAddress?.ToString()??"?",_=>new FixedWindowRateLimiterOptions{PermitLimit=600,Window=TimeSpan.FromMinutes(1)}));
});
builder.Services.ConfigureHttpJsonOptions(o=>o.SerializerOptions.PropertyNameCaseInsensitive=true);
builder.WebHost.ConfigureKestrel(k=>k.Limits.MaxRequestBodySize=25_000_000);

var app=builder.Build();

// ───────────────────────── pipeline
bool IsRemote(HttpContext c)=>c.Connection.LocalPort==tunnelPort;
// forwarded headers only make sense on the tunnel door (on the LAN door they could be spoofed)
app.UseWhen(IsRemote,a=>a.UseForwardedHeaders());
string? DeviceKey(HttpContext c){var h=c.Request.Headers["X-Device-Key"].ToString();if(Devices.ValidKey(h))return h;var k=c.Request.Cookies["tcs.dev"];return Devices.ValidKey(k)?k:null;}
string Ip(HttpContext c)=>c.Connection.RemoteIpAddress?.ToString()??"";
app.Use(async(ctx,next)=>
{
 var remote=IsRemote(ctx);var key=DeviceKey(ctx);var dev=key==null?null:devices.Find(key);
 ctx.Items["device"]=dev;
 if(remote)
 {
  ctx.Response.Headers.StrictTransportSecurity="max-age=31536000";
  // outside the office: only approved devices reach the API (the app shell itself is public and harmless)
  var p=ctx.Request.Path;
  if(p.StartsWithSegments("/api")&&!p.StartsWithSegments("/api/device/hello")&&!p.StartsWithSegments("/api/health")&&!(HttpMethods.IsGet(ctx.Request.Method)&&p.StartsWithSegments("/api/update"))&&dev?.Status!=Devices.Approved)
  {
   ctx.Response.StatusCode=403;
   await ctx.Response.WriteAsJsonAsync(new{error=dev?.Status==Devices.Revoked?"Questo dispositivo è stato bloccato dall'amministratore.":"Dispositivo non ancora autorizzato per l'accesso da fuori ufficio.",device=dev?.Status??"unknown",code=dev?.Id??""});
   return;
  }
 }
 if(dev!=null&&key!=null)devices.Touch(key,Ip(ctx),remote,null);
 await next();
});
app.Use(async(ctx,next)=>
{
 var h=ctx.Response.Headers;h["X-Content-Type-Options"]="nosniff";h["Referrer-Policy"]="no-referrer";h["X-Frame-Options"]="DENY";h["Permissions-Policy"]="camera=(), microphone=(), geolocation=()";h["Content-Security-Policy"]="frame-ancestors 'none'; base-uri 'none'";h["Cross-Origin-Opener-Policy"]="same-origin";h["Cross-Origin-Resource-Policy"]="same-origin";
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
 return Results.Ok(new{setupNeeded=!users.Any(),user=u==null?null:Me(u.Value.User),version=hub.Version,publicUrl=u==null?"":PublicUrl()});
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
 await SignIn(ctx,v.Value.User,v.Value.Stamp,r.Remember);
 if(DeviceKey(ctx) is string key)
 {
  try
  {
   var d=devices.Find(key)??devices.Register(key,r.DeviceName??"",r.Platform??"",Ip(ctx),IsRemote(ctx),v.Value.User.Name);
   devices.Touch(key,Ip(ctx),IsRemote(ctx),v.Value.User.Name);
   // an administrator signing in from the office network trusts their own device
   if(d.Status==Devices.Pending&&!IsRemote(ctx)&&v.Value.User.Role==Roles.Admin){devices.SetStatus(d.Id,Devices.Approved,v.Value.User.Name);store.Log("users",$"Dispositivo {d.Id} di {v.Value.User.Name} autorizzato.");}
  }catch(InvalidDataException){}
 }
 return Results.Ok(Me(v.Value.User));
}).RequireRateLimiting("login");
app.MapPost("/api/logout",async(HttpContext ctx)=>{await ctx.SignOutAsync();return Results.Ok();});
app.MapPost("/api/password",(HttpContext ctx,PasswordRequest r)=>
{
 if(users.Verify(ctx.User.Identity!.Name!,r.Current??"")==null)return Bad("La password attuale non è corretta.");
 try{users.SetPassword(ctx.User.Identity!.Name!,r.Password??"");}catch(InvalidDataException ex){return Bad(ex.Message);}
 return Results.Ok(new{message="Password aggiornata. Accedi di nuovo con la nuova password."});
}).RequireAuthorization("read");

// ───────────────────────── trusted devices
app.MapPost("/api/device/hello",(HttpContext ctx,DeviceHello r)=>
{
 var key=DeviceKey(ctx);
 if(key==null)
 {
  // browsers / WebView: the secret lives in an HttpOnly cookie that scripts cannot read
  key=Devices.NewKey();
  ctx.Response.Cookies.Append("tcs.dev",key,new CookieOptions{HttpOnly=true,SameSite=SameSiteMode.Strict,Secure=ctx.Request.IsHttps,MaxAge=TimeSpan.FromDays(400),IsEssential=true});
 }
 var remote=IsRemote(ctx);var dev=devices.Find(key);
 var who=ctx.User.Identity?.IsAuthenticated==true?Who(ctx):"";
 if(dev==null&&(remote||who!=""))
 {
  try{dev=devices.Register(key,r?.Name??"",r?.Platform??"",Ip(ctx),remote,who);if(remote)store.Log("users",$"Nuova richiesta di accesso da fuori ufficio: dispositivo {dev.Id} ({dev.Name}).");}
  catch(InvalidDataException ex){return Bad(ex.Message);}
 }
 if(dev!=null&&dev.Status==Devices.Pending&&!remote&&ctx.User.IsInRole(Roles.Admin)){devices.SetStatus(dev.Id,Devices.Approved,Who(ctx));dev=devices.Find(key);}
 return Results.Ok(new{status=dev?.Status??(remote?"unknown":"office"),code=dev?.Id??"",remote,name=dev?.Name??""});
}).RequireRateLimiting("login");
app.MapPost("/api/devices/{id}/{action}",(HttpContext ctx,string id,string action,DeviceRename? r)=>
{
 try
 {
  switch(action)
  {
   case "approve":devices.SetStatus(id,Devices.Approved,Who(ctx));store.Log("users",$"{Who(ctx)} ha autorizzato il dispositivo {id}.");break;
   case "revoke":devices.SetStatus(id,Devices.Revoked,Who(ctx));store.Log("users",$"{Who(ctx)} ha bloccato il dispositivo {id}.");break;
   case "delete":devices.Delete(id);store.Log("users",$"{Who(ctx)} ha eliminato il dispositivo {id}.");break;
   case "rename":devices.Rename(id,r?.Name??"");break;
   default:return Results.NotFound();
  }
 }catch(InvalidDataException ex){return Bad(ex.Message);}
 return Results.Ok();
}).RequireAuthorization("admin");
app.MapGet("/api/users",(HttpContext ctx)=>Results.Ok(new{devices=devices.List(),thisDevice=(ctx.Items["device"] as DeviceInfo)?.Id??"",tunnelPort,users=users.List().Select(u=>new{u.Username,u.Name,u.Role,roleLabel=Roles.Label(u.Role),u.Disabled,u.Created,u.LastLogin}),syncTokenCreated=users.SyncTokenCreated(),stationLastContact=hub.LastStationContact==default?"":hub.LastStationContact.ToString("O"),connected=hub.Connected})).RequireAuthorization("admin");
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
app.MapPost("/api/users/{username}/delete",(HttpContext ctx,string username)=>
{
 if(username==ctx.User.Identity!.Name)return Bad("Non puoi eliminare il tuo stesso utente: chiedi a un altro amministratore.");
 try{users.Delete(username);}catch(InvalidDataException ex){return Bad(ex.Message);}
 store.Log("users",$"{Who(ctx)} ha eliminato l'utente {username}.");hub.Changed();return Results.Ok();
}).RequireAuthorization("admin");
// your own name, from any device you are signed in with
app.MapPost("/api/me",(HttpContext ctx,UserUpdate r)=>
{
 try{users.Update(ctx.User.Identity!.Name!,null,null,string.IsNullOrWhiteSpace(r.Name)?null:r.Name);}catch(InvalidDataException ex){return Bad(ex.Message);}
 return Results.Ok();
}).RequireAuthorization("read");
app.MapPost("/api/sync/token",(HttpContext ctx)=>{var t=users.NewSyncToken();store.Log("users",$"{Who(ctx)} ha creato una nuova chiave per la postazione di sincronizzazione.");return Results.Ok(new{token=t});}).RequireAuthorization("admin");
app.MapPost("/api/sync/token/revoke",(HttpContext ctx)=>{users.RevokeSyncToken();store.Log("users",$"{Who(ctx)} ha revocato la chiave della postazione di sincronizzazione.");return Results.Ok();}).RequireAuthorization("admin");

// ───────────────────────── archive
app.MapGet("/api/state",(HttpContext ctx)=>
{
 var etag=$"\"v{hub.Version}-{hub.Boot}\"";ctx.Response.Headers.ETag=etag;ctx.Response.Headers.CacheControl="no-cache";
 if(ctx.Request.Headers.IfNoneMatch.ToString()==etag)return Results.StatusCode(304);
 var(body,enc)=hub.SnapshotFor(ctx.Request.Headers.AcceptEncoding.ToString());
 if(enc!=null){ctx.Response.Headers.ContentEncoding=enc;ctx.Response.Headers.Vary="Accept-Encoding";}
 return Results.Bytes(body,"application/json");
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
// ───────────────────────── administrator edits: customers and licences (kept apart from the synchronised copies)
app.MapPost("/api/clients/save",(HttpContext ctx,ClientSaveRequest r)=>
{
 try{var code=store.SaveClient(r.Code,r.Edit??new ClientEdit(),Who(ctx));hub.Changed();return Results.Ok(new{code});}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("admin");
app.MapPost("/api/clients/delete",(HttpContext ctx,ClientDeleteRequest r)=>
{
 if(string.IsNullOrWhiteSpace(r.Code)||r.Code.Length>40)return Bad("Codice cliente mancante.");
 store.DeleteClient(r.Code,r.Deleted,Who(ctx));hub.Changed();return Results.Ok();
}).RequireAuthorization("admin");
app.MapPost("/api/clients/reset",(HttpContext ctx,ClientDeleteRequest r)=>
{
 try{store.ResetClient(r.Code??"",Who(ctx));hub.Changed();return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("admin");
app.MapPost("/api/assets/save",(HttpContext ctx,Asset a)=>{try{var r=store.SaveAsset(a,Who(ctx));hub.Changed();return Results.Ok(r);}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("read");
app.MapPost("/api/assets/delete",(HttpContext ctx,IdRequest r)=>{store.DeleteAsset(r.Id,Who(ctx));hub.Changed();return Results.Ok();}).RequireAuthorization("export");
app.MapPost("/api/renewals/save",(HttpContext ctx,RenewalRequest r)=>{try{var m=store.SaveRenewal(r.Key,r.Status??"",r.Note??"",r.NextContact??"",Who(ctx));hub.Changed();return Results.Ok(m);}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("read");
app.MapPost("/api/links/save",(HttpContext ctx,LinkRequest r)=>{try{store.SaveDeviceLink(r.Serial,r.ClientCode??"",Who(ctx));hub.Changed();return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("admin");
app.MapPost("/api/clients/merge",(HttpContext ctx,MergeRequest r)=>{try{store.MergeClient(r.Code,r.Into??"",Who(ctx));hub.Changed();return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("admin");
app.MapPost("/api/settings/brands",(HttpContext ctx,BrandsRequest r)=>{store.SaveHiddenBrands(r?.Brands??[],Who(ctx));hub.Changed();return Results.Ok();}).RequireAuthorization("admin");
app.MapPost("/api/licenses/save",(HttpContext ctx,LicenseSaveRequest r)=>
{
 try{store.SaveLicense(r.Id??"",r.Edit??new LicenseEdit(),Who(ctx));hub.Changed();return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("admin");
app.MapGet("/api/export/clients.csv",()=>Results.File(Utf8Bom(Imports.ClientsCsv(store.Clients(),store.Licenses(),store.Metadata(),store.Notes())),"text/csv; charset=utf-8",$"TopCash-anagrafica-clienti-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("export");
app.MapGet("/api/export/licenses.csv",()=>Results.File(Utf8Bom(Imports.CsvExport(store.Licenses())),"text/csv; charset=utf-8",$"TopCash-licenze-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("export");
app.MapGet("/api/export/calendar.ics",()=>Results.File(Encoding.UTF8.GetBytes(Imports.Calendar(store.Licenses().Where(r=>!r.Scope.EndsWith("-scadute")))),"text/calendar; charset=utf-8","TopCash-scadenze-90-giorni.ics")).RequireAuthorization("export");
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

app.MapPost("/api/interventions/{id}/send-report",async(HttpContext ctx,string id,SendReportRequest r)=>
{
 try{var i=await Mailer.SendReport(store,id,r?.To??"",Who(ctx));hub.Changed();return Results.Ok(i);}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("read").RequireRateLimiting("mail");

// ───────────────────────── settings: e-mail for reports, external backups (administrators)
app.MapGet("/api/settings/mail",()=>Results.Ok(store.MailView())).RequireAuthorization("admin");
app.MapPost("/api/settings/mail",(HttpContext ctx,MailConfigInput i)=>{try{var v=store.SaveMail(i,Who(ctx));hub.Changed();return Results.Ok(v);}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("admin");
app.MapPost("/api/settings/mail/test",async(HttpContext ctx,SendReportRequest r)=>{try{await Mailer.SendTest(store,r?.To??"",Who(ctx));return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("admin").RequireRateLimiting("mail");
object BackupInfo(BackupStatus? st=null)=>new{config=Backups.Config(store),status=st??Backups.Status(store),local=Path.GetFullPath(Path.Combine(dataDir,"backups"))};
app.MapGet("/api/settings/backup",()=>Results.Ok(BackupInfo())).RequireAuthorization("admin");
app.MapPost("/api/settings/backup",(HttpContext ctx,BackupRequest r)=>{try{Backups.Save(store,r?.External,Who(ctx));var st=Backups.Run(store,dataDir);hub.Changed();return Results.Ok(BackupInfo(st));}catch(Exception ex) when(ex is IOException or InvalidDataException){return Bad(ex.Message);}}).RequireAuthorization("admin");
app.MapPost("/api/backup/now",(HttpContext ctx)=>{try{var st=Backups.Run(store,dataDir,true,Who(ctx));hub.Changed();return Results.Ok(BackupInfo(st));}catch(Exception ex) when(ex is IOException or InvalidDataException){return Bad("Backup non riuscito: "+ex.Message);}}).RequireAuthorization("admin");

// ───────────────────────── accounting (mirror of MastroSQL + reminder notes kept by the app)
app.MapPost("/api/accounting/due-note",(HttpContext ctx,DueNoteRequest r)=>
{
 try{var n=store.SaveDueNote(r?.Id??"",r?.Status??"",r?.Note??"",Who(ctx));hub.Changed();return Results.Ok(n);}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("read");
app.MapGet("/api/export/invoices.csv",()=>Results.File(Utf8Bom(AccountingReports.InvoicesCsv(store)),"text/csv; charset=utf-8",$"TopCash-fatture-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("export");
app.MapGet("/api/export/dues.csv",()=>Results.File(Utf8Bom(AccountingReports.DuesCsv(store)),"text/csv; charset=utf-8",$"TopCash-scadenze-aperte-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("export");

// ───────────────────────── warehouse
// warehouse management is reserved to administrators (spare parts used in interventions still unload the van automatically)
bool CanManage(HttpContext ctx)=>ctx.User.IsInRole(Roles.Admin);
app.MapPost("/api/warehouse/movements",(HttpContext ctx,MovementsRequest r)=>
{
 if(r?.Movements==null||r.Movements.Count==0)return Bad("Nessun movimento.");
 // technicians move stock (load, unload, transfer); corrections and stock counts are for office / admin
 if(!CanManage(ctx)&&r.Movements.Any(m=>m?.Type is "rettifica" or "inventario"))return Results.StatusCode(403);
 try{var saved=store.AddMovements(r.Movements,Who(ctx));hub.Changed();return Results.Ok(new{count=saved.Count});}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("admin");
app.MapPost("/api/warehouse/inventory",(HttpContext ctx,InventoryRequest r)=>
{
 if(r==null||string.IsNullOrWhiteSpace(r.Warehouse)||r.Counted==null)return Bad("Inventario incompleto.");
 try{var n=store.RecordInventory(r.Warehouse,r.Counted,Who(ctx));hub.Changed();return Results.Ok(new{differences=n});}catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("admin");
app.MapPost("/api/warehouse/articles",(HttpContext ctx,Article a)=>{try{var r=store.SaveArticle(a,Who(ctx));hub.Changed();return Results.Ok(r);}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("admin");
app.MapPost("/api/warehouse/warehouses",(HttpContext ctx,Warehouse w)=>{try{var r=store.SaveWarehouse(w,Who(ctx));hub.Changed();return Results.Ok(r);}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("admin");
app.MapPost("/api/warehouse/levels",(HttpContext ctx,StockLevel l)=>{try{store.SaveStockLevel(l);hub.Changed();return Results.Ok();}catch(InvalidDataException ex){return Bad(ex.Message);}}).RequireAuthorization("admin");
app.MapGet("/api/export/stock.csv",()=>Results.File(Utf8Bom(WarehouseReports.StockCsv(store)),"text/csv; charset=utf-8",$"TopCash-giacenze-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("admin");
app.MapGet("/api/export/reorder.csv",()=>Results.File(Utf8Bom(WarehouseReports.ReorderCsv(store)),"text/csv; charset=utf-8",$"TopCash-proposta-ordine-{DateTime.Today:yyyyMMdd}.csv")).RequireAuthorization("admin");

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
// addresses other devices of the office use to open Studio in the browser (physical adapters only: no VirtualBox, WSL, VPN)
app.MapGet("/api/lan",()=>{
 var host=Environment.MachineName;var port=lanUrl.Port;
 var ips=System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
  .Where(n=>n.OperationalStatus==System.Net.NetworkInformation.OperationalStatus.Up&&n.NetworkInterfaceType is System.Net.NetworkInformation.NetworkInterfaceType.Ethernet or System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211 or System.Net.NetworkInformation.NetworkInterfaceType.GigabitEthernet
   &&!System.Text.RegularExpressions.Regex.IsMatch(n.Description+" "+n.Name,"virtual|vmware|virtualbox|hyper-v|vethernet|wsl|tailscale|vpn|loopback|npcap|bluetooth",System.Text.RegularExpressions.RegexOptions.IgnoreCase))
  .SelectMany(n=>n.GetIPProperties().UnicastAddresses).Where(a=>a.Address.AddressFamily==System.Net.Sockets.AddressFamily.InterNetwork&&!System.Net.IPAddress.IsLoopback(a.Address)&&!a.Address.ToString().StartsWith("169.254."))
  .Select(a=>a.Address.ToString()).Distinct().ToList();
 var urls=ips.Select(i=>$"http://{i}:{port}").Append($"http://{host}:{port}").ToList();
 return Results.Ok(new{host,port,urls,publicUrl=PublicUrl()});
}).RequireAuthorization();
app.MapGet("/api/health",()=>Results.Ok(new{ok=true,version=hub.Version,server=typeof(Hub).Assembly.GetName().Version?.ToString(3)}));

// ───────────────────────── app updates over the network: the administrator uploads a build once, every PC and phone picks it up.
// Downloads are open (same files as the public GitHub release, no data inside); only an administrator can upload.
app.MapGet("/api/update/info",(HttpContext ctx)=>{ctx.Response.Headers.CacheControl="no-store";return Results.Ok(updates.Info());});
app.MapGet("/api/update/{kind}",(string kind)=>
{
 if(kind is not("windows" or "android"))return Results.NotFound();
 var f=updates.FilePath(kind);var v=(kind=="android"?updates.Info().Android:updates.Info().Windows)?.Version??"";
 if(!File.Exists(f))return Results.NotFound();
 return Results.File(f,kind=="android"?"application/vnd.android.package-archive":"application/zip",kind=="android"?$"TopCash-Studio-{v}.apk":$"TopCash-Studio-Windows-{v}.zip",enableRangeProcessing:true);
});
app.MapPost("/api/update/upload",async(HttpContext ctx,string kind,string version,string? notes)=>
{
 var lim=ctx.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpMaxRequestBodySizeFeature>();if(lim is{IsReadOnly:false})lim.MaxRequestBodySize=400_000_000;
 try
 {
  var b=await updates.Save(kind,version,notes??"",ctx.Request.Body,Who(ctx));
  store.Log("users",$"{Who(ctx)} ha pubblicato in rete la versione {b.Version} dell'app {(kind=="android"?"Android":"Windows")}.");
  hub.Changed();return Results.Ok(updates.Info());
 }
 catch(InvalidDataException ex){return Bad(ex.Message);}
}).RequireAuthorization("admin").DisableAntiforgery();

// Automatic daily backup: data\backups (verified) + copy on the external folder set by the administrator (default H:\TcsSqlOfflineBackups).
// Hourly check: one file per day; the external copy is retried every hour while the drive is missing.
void DailyBackup(){try{Backups.Run(store,dataDir);hub.Changed();}catch(Exception ex){store.Log("error","Backup automatico: "+ex.Message);}}
var backupTimer=new Timer(_=>DailyBackup(),null,TimeSpan.FromMinutes(1),TimeSpan.FromHours(1));
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
record LoginRequest(string? Username,string? Password,bool Remember,string? DeviceName=null,string? Platform=null);
record DeviceHello(string? Name,string? Platform);
record DeviceRename(string? Name);
record PasswordRequest(string? Current,string? Password);
record UserRequest(string? Username,string? Name,string? Role,string? Password);
record BrandsRequest(List<string> Brands);
record IdRequest(string Id);
record RenewalRequest(string Key,string? Status,string? Note,string? NextContact);
record LinkRequest(string Serial,string? ClientCode);
record MergeRequest(string Code,string? Into);
record ClientSaveRequest(string? Code,ClientEdit? Edit);
record ClientDeleteRequest(string? Code,bool Deleted);
record LicenseSaveRequest(string? Id,LicenseEdit? Edit);
record UserUpdate(string? Role,bool? Disabled,string? Name,string? Password);
record NoteRequest(string Code,string? Note);
record MetaRequest(string Code,ClientMeta Meta);
record BatchRequest(string Kind,string Product,string Scope,List<string> Headers,List<List<string>> Rows,string CapturedAt);
record LogRequest(string Kind,string Message);
record ProgressRequest(bool Running,bool Connected,string Text);
record MovementsRequest(List<Movement> Movements);
record DueNoteRequest(string? Id,string? Status,string? Note);
record SignRequest(string? Signer,string? Hours,string? Signature,bool Close);
record SendReportRequest(string? To);
record BackupRequest(string? External);
record InventoryRequest(string Warehouse,Dictionary<string,decimal> Counted);
