using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Threading;

namespace TopCash.Licenze;
public partial class MainWindow : Window
{
 readonly Store store;
 readonly DispatcherTimer timer=new(){Interval=TimeSpan.FromMinutes(1)};
 readonly JsonSerializerOptions json=new(){PropertyNamingPolicy=JsonNamingPolicy.CamelCase};
 string sourceFolder;bool busy;PortalWindow? portal;
 string SettingsPath=>Path.Combine(store.Folder,"settings.json");
 // Shared-archive mode: when set, the UI is served by the Top Cash server and this PC is a client (or the sync station).
 string serverUrl="";string syncToken="";
 // optional external address (Cloudflare tunnel, https): used automatically when the office server cannot be reached
 string serverExternal="";string activeServer="";
 bool ServerMode=>serverUrl.Length>0;
 string Active=>activeServer.Length>0?activeServer:serverUrl;
 string ServerOrigin=>ServerMode?new Uri(Active).GetLeftPart(UriPartial.Authority):"";
 void SaveSettings()
 {
  var protectedToken=syncToken.Length==0?"":Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(syncToken),null,DataProtectionScope.CurrentUser));
  File.WriteAllText(SettingsPath,JsonSerializer.Serialize(new{sourceFolder,serverUrl,serverExternal,syncToken=protectedToken}));
 }
 void LoadSettings()
 {
  if(!File.Exists(SettingsPath))return;
  try{using var s=JsonDocument.Parse(File.ReadAllText(SettingsPath));var r=s.RootElement;
   if(r.TryGetProperty("sourceFolder",out var sf))sourceFolder=sf.GetString()??sourceFolder;
   if(r.TryGetProperty("serverUrl",out var su))serverUrl=su.GetString()??"";
   if(r.TryGetProperty("serverExternal",out var se))serverExternal=se.GetString()??"";
   if(r.TryGetProperty("syncToken",out var st)&&st.GetString() is {Length:>0} enc)syncToken=Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(enc),null,DataProtectionScope.CurrentUser));
  }catch(Exception ex){store.Log("error","Impostazioni: "+ex.Message);}
 }
 static string? NormalizeServer(string url){url=url.Trim();if(url.Length==0)return null;if(!url.Contains("://"))url="http://"+url;return Uri.TryCreate(url,UriKind.Absolute,out var u)&&(u.Scheme=="http"||u.Scheme=="https")?u.GetLeftPart(UriPartial.Authority)+"/":null;}
 public MainWindow()
 {
  InitializeComponent();var args=Environment.GetCommandLineArgs();
  var arg=args.FirstOrDefault(a=>a.StartsWith("--data="));var folder=arg?[7..]??FindDataFolder();
  store=new Store(folder);sourceFolder=folder;
  var catalog=Path.Combine(folder,"portal-catalog.json");
  if(File.Exists(catalog)){try{using var c=JsonDocument.Parse(File.ReadAllText(catalog));foreach(var s in c.RootElement.GetProperty("sources").EnumerateArray())if(s.GetProperty("rowCount").GetInt32()==0)store.SaveEmpty(s.GetProperty("scope").GetString()!,s.GetProperty("capturedAt").GetString()!);}catch(Exception ex){store.Log("error","Catalogo del portale: "+ex.Message);}}
  LoadSettings();
  // Fit any screen: 92% of the work area, never above the design size (small laptops get a usable window).
  var area=SystemParameters.WorkArea;Width=Math.Min(1480,area.Width*0.92);Height=Math.Min(960,area.Height*0.92);MinWidth=Math.Min(MinWidth,area.Width);MinHeight=Math.Min(MinHeight,area.Height);
  pushTimer.Tick+=async(_,_)=>{pushTimer.Stop();if(pushPending){pushPending=false;await PushNow();}};
  TitleBar.Attach(this);Loaded+=Initialize;Closed+=(_,_)=>{timer.Stop();pushTimer.Stop();updateTimer.Stop();portal?.Quit();dtr?.Quit();ital?.Quit();rch?.Quit();if(!updateStarted&&Updater.ReadyVersion!=null)Updater.Apply(false,false);};
  updateTimer.Tick+=async(_,_)=>await UpdateTick();
  StateChanged+=(_,_)=>{try{if(Dashboard.CoreWebView2!=null)Dashboard.CoreWebView2.MemoryUsageTargetLevel=WindowState==WindowState.Minimized?CoreWebView2MemoryUsageTargetLevel.Low:CoreWebView2MemoryUsageTargetLevel.Normal;}catch{}};
 }
 // ───────── updates: a newer build is shown in the interface; one tap downloads it, verifies it and restarts Studio
 readonly DispatcherTimer updateTimer=new(){Interval=TimeSpan.FromMinutes(1)};DateTime nextUpdateCheck=DateTime.Now.AddSeconds(20);bool updateStarted,updateBusy;string updateError="";
 Updater.Offer? updateOffer;double? updateProgress;
 object UpdatePayload()=>new{type="update",current=Updater.CurrentText,available=updateOffer?.Version??"",size=updateOffer?.Size??0,ready=Updater.ReadyVersion??"",
  notes=updateOffer?.Notes is {Length:>0} n?n:Updater.ReadyNotes,progress=updateProgress,error=updateError,serverMode=ServerMode};
 async Task UpdateTick()
 {
  if(updateBusy||updateStarted)return;
  // on the sync station the update installs itself at night, then the app comes back minimised
  if(Updater.ReadyVersion!=null&&syncToken.Length>0&&DateTime.Now.Hour is 3&&!busy){InstallUpdate(true);return;}
  if(DateTime.Now<nextUpdateCheck)return;
  // a release shows up on every PC within half an hour: a tiny question to the server, or a file read on the PC with the data
  nextUpdateCheck=DateTime.Now.AddMinutes(30);await CheckUpdate(false);
 }
 async Task CheckUpdate(bool manual)
 {
  if(updateBusy)return;updateBusy=true;
  try
  {
   updateOffer=await Updater.Find(ServerMode?Active:"",store.Folder);updateError="";
   if(manual&&updateOffer==null)await Notify($"Studio è aggiornato (versione {Updater.CurrentText}).");
   // the unattended sync station gets the build ready in advance, for its night install
   if(updateOffer!=null&&syncToken.Length>0&&Updater.ReadyVersion!=updateOffer.Version)await Updater.Download(updateOffer);
  }
  catch(Exception ex){updateError=ex is System.Net.Http.HttpRequestException?"Server non raggiungibile per gli aggiornamenti.":ex.Message;if(manual)await Notify(updateError);}
  finally{updateBusy=false;Post(UpdatePayload());}
 }
 /// The button in the interface: download with live progress, then close, install and reopen right away.
 async Task UpdateNow()
 {
  if(updateBusy||updateStarted)return;
  if(Updater.ReadyVersion!=null&&(updateOffer==null||Updater.ReadyVersion==updateOffer.Version)){updateProgress=1;Post(UpdatePayload());await Task.Delay(500);InstallUpdate(false);return;}
  if(updateOffer==null){await CheckUpdate(true);if(updateOffer==null)return;}
  updateBusy=true;updateError="";updateProgress=0;Post(UpdatePayload());
  try
  {
   var last=0.0;
   await Updater.Download(updateOffer,new Progress<double>(p=>{updateProgress=p;if(p-last>=0.02||p>=1){last=p;Post(UpdatePayload());}}));
   updateProgress=1;Post(UpdatePayload());await Task.Delay(700);// the bar reaches the end before the window goes
   updateBusy=false;InstallUpdate(false);
  }
  catch(Exception ex){updateProgress=null;updateError=ex is System.Net.Http.HttpRequestException?"Server non raggiungibile: riprova tra poco.":ex.Message;}
  finally{updateBusy=false;Post(UpdatePayload());}
 }
 void InstallUpdate(bool minimized){if(Updater.Apply(true,minimized||WindowState==WindowState.Minimized)){updateStarted=true;Close();}}
 readonly DispatcherTimer pushTimer=new(){Interval=TimeSpan.FromMilliseconds(4000)};bool pushPending;DateTime lastPush=DateTime.MinValue;
 /// Uses the nearest "data" folder that already holds the database (e.g. D:\TopCashSQL\data when run from src\bin), else creates one next to the exe.
 static string FindDataFolder()
 {
  for(var dir=new DirectoryInfo(AppContext.BaseDirectory);dir!=null;dir=dir.Parent)
  {var candidate=Path.Combine(dir.FullName,"data");if(File.Exists(Path.Combine(candidate,"clienti-licenze.sqlite")))return candidate;}
  return Path.Combine(AppContext.BaseDirectory,"data");
 }
 static bool SafeExternal(string url)=>url.StartsWith("mailto:",StringComparison.OrdinalIgnoreCase)||url.StartsWith("tel:",StringComparison.OrdinalIgnoreCase)||url.StartsWith("https://www.google.com/maps/search/",StringComparison.Ordinal)||url.StartsWith("https://github.com/lapob/tcs-sql/releases/",StringComparison.Ordinal); // official app downloads
 async void Initialize(object? sender,RoutedEventArgs e)
 {
  try
  {
   var env=await CoreWebView2Environment.CreateAsync(null,Paths.Runtime("webview","Dashboard"));
   await Dashboard.EnsureCoreWebView2Async(env);
   var core=Dashboard.CoreWebView2;core.Settings.AreDevToolsEnabled=false;core.Settings.AreDefaultContextMenusEnabled=false;core.Settings.IsStatusBarEnabled=false;
   core.SetVirtualHostNameToFolderMapping("topcash.local",Path.Combine(AppContext.BaseDirectory,"ui"),CoreWebView2HostResourceAccessKind.DenyCors);
   core.NavigationStarting+=(_,ev)=>
   {
    if(ev.Uri.StartsWith("https://topcash.local/",StringComparison.Ordinal)||(ServerMode&&ev.Uri.StartsWith(ServerOrigin,StringComparison.OrdinalIgnoreCase)))return;
    ev.Cancel=true;if(SafeExternal(ev.Uri))Process.Start(new ProcessStartInfo(ev.Uri){UseShellExecute=true}); // downloads/links from the web UI
   };
   core.NewWindowRequested+=(_,ev)=>{ev.Handled=true;if(SafeExternal(ev.Uri))Process.Start(new ProcessStartInfo(ev.Uri){UseShellExecute=true});};
   core.PermissionRequested+=(_,ev)=>ev.State=CoreWebView2PermissionState.Deny;
   core.Settings.IsPinchZoomEnabled=false;core.Settings.IsSwipeNavigationEnabled=false;core.Settings.AreBrowserAcceleratorKeysEnabled=false;
   core.WebMessageReceived+=Message; // the page sends "ready" once loaded: a single initial push
   // Only real connection failures to the server count (a cancelled mailto:/tel:/download navigation is not an error).
   core.NavigationCompleted+=(_,ev)=>{if(!ev.IsSuccess&&ServerMode&&!serverErrorOpen&&ev.WebErrorStatus is CoreWebView2WebErrorStatus.CannotConnect or CoreWebView2WebErrorStatus.ConnectionAborted or CoreWebView2WebErrorStatus.ConnectionReset or CoreWebView2WebErrorStatus.Disconnected or CoreWebView2WebErrorStatus.HostNameNotResolved or CoreWebView2WebErrorStatus.ServerUnreachable or CoreWebView2WebErrorStatus.Timeout)ShowServerError();};
   await ChooseServer();Navigate();
   await Task.Yield();if(!ServerMode&&Scan(false)>0)await Push();
   timer.Tick+=async(_,_)=>{if(!busy&&!ServerMode){var count=Scan(false);if(count>0)await Push();}};timer.Start();updateTimer.Start();
   // DTR-Service: once connected on this PC the session is resumed in the background at every start
   if(DtrWindow.Configured&&(!ServerMode||syncToken.Length>0))OpenDtr(false);
   if(ItalRetailWindow.Configured&&(!ServerMode||syncToken.Length>0))OpenItal(false);
   if(RchWindow.Configured&&(!ServerMode||syncToken.Length>0))OpenRch(false);
  }
  catch(Exception ex)
  {
   var answer=MessageBox.Show("Per mostrare l'interfaccia serve Microsoft Edge WebView2 Runtime (gratuito, già presente su Windows 11).\n\nVuoi aprire la pagina di download ufficiale Microsoft?\n\nDettagli: "+ex.Message,"Top Cash Studio",MessageBoxButton.YesNo,MessageBoxImage.Warning);
   if(answer==MessageBoxResult.Yes)Process.Start(new ProcessStartInfo("https://developer.microsoft.com/microsoft-edge/webview2/"){UseShellExecute=true});
  }
 }
 void Navigate()=>Dashboard.CoreWebView2?.Navigate(ServerMode?Active:"https://topcash.local/index.html");
 /// Office first (fast, stays on the LAN); outside the office the external address takes over without waiting for a timeout.
 async Task ChooseServer()
 {
  activeServer=serverUrl;if(!ServerMode||serverExternal.Length==0)return;
  try{using var http=new System.Net.Http.HttpClient{Timeout=TimeSpan.FromMilliseconds(1800)};var res=await http.GetAsync(serverUrl+"api/health");if(res.IsSuccessStatusCode)return;}catch{}
  activeServer=serverExternal;
 }
 bool serverErrorOpen,triedOfficeAgain;
 void ShowServerError()
 {
  // try the other address before bothering the user
  if(serverExternal.Length>0&&Active!=serverExternal){activeServer=serverExternal;Navigate();return;}
  if(serverExternal.Length>0&&Active==serverExternal&&!triedOfficeAgain){triedOfficeAgain=true;activeServer=serverUrl;Navigate();return;}
  serverErrorOpen=true;try{
  var r=MessageBox.Show(this,$"Il server Top Cash ({serverUrl}{(serverExternal.Length>0?" e "+serverExternal:"")}) non risponde.\n\nSì = riprova\nNo = usa l'archivio locale di questo PC",
   "Top Cash Studio",MessageBoxButton.YesNo,MessageBoxImage.Warning);
  triedOfficeAgain=false;
  if(r==MessageBoxResult.No){serverUrl="";SaveSettings();}Navigate();
  }finally{serverErrorOpen=false;}
 }
 static readonly HashSet<string> RemoteActions=["external","portal","syncPortal","disconnectServer","setSyncToken","hostInfo","mastroRead","setExternal","dtr","dtrSync","ital","italSync","rch","rchSync","autostart","applyUpdate","checkUpdate"];
 MastroBatch? lastMastro;
 static readonly JsonSerializerOptions jsonIn=new(){PropertyNameCaseInsensitive=true};
 /// Reads Mastro (read-only) on this PC and hands a preview to the page. Local mode keeps the batch for "mastroImport";
 /// in shared-archive mode the page forwards the batch to the server with the signed-in user's permissions.
 async Task MastroRead()
 {
  var cfg=MastroReader.FromIni();
  if(cfg==null){Post(new{type="mastro",ok=false,error="Non trovo la configurazione di MastroSQL (RETE.ini) su questo PC. L'importazione va avviata da un PC dove Mastro è installato."});return;}
  Post(new{type="mastro",ok=true,stage="reading",source=$"{cfg.Host} · database {cfg.Database}"});
  try
  {
   var (batch,preview)=await Task.Run(()=>MastroReader.Read(cfg));lastMastro=batch;
   Post(new{type="mastro",ok=true,stage="preview",source=batch.Source,preview,batch=ServerMode?batch:null});
  }
  catch(Exception ex){Post(new{type="mastro",ok=false,error="Lettura da MastroSQL non riuscita: "+ex.Message});}
 }
 async void Message(object? sender,CoreWebView2WebMessageReceivedEventArgs e)
 {
  bool local=e.Source.StartsWith("https://topcash.local/",StringComparison.Ordinal);
  bool remote=ServerMode&&e.Source.StartsWith(ServerOrigin,StringComparison.OrdinalIgnoreCase);
  if(!local&&!remote)return;
  try
  {
   using var doc=JsonDocument.Parse(e.WebMessageAsJson);var root=doc.RootElement;var action=root.GetProperty("action").GetString()??"";
   // Pages served by the shared server may only use the host features that make sense on a client PC.
   if(remote&&!RemoteActions.Contains(action))return;
   switch(action)
   {
    case "mastroRead":await MastroRead();break;
    case "mastroImport":
     if(lastMastro==null){await Notify("Prima esegui la lettura da MastroSQL.");break;}
     var mmsg=store.ImportMastro(lastMastro,Environment.UserName);lastMastro=null;await Push();await Notify(mmsg);break;
    case "intervention":
     var iv=JsonSerializer.Deserialize<Intervention>(root.GetProperty("item").GetRawText(),jsonIn)!;
     try{store.SaveIntervention(iv,Environment.UserName);await Push();await Notify("Intervento salvato.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "movements":
     try{var mv=JsonSerializer.Deserialize<List<Movement>>(root.GetProperty("movements").GetRawText(),jsonIn)!;store.AddMovements(mv,Environment.UserName);await Push();await Notify(mv.Count==1?"Movimento registrato.":$"{mv.Count} movimenti registrati.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "inventory":
     try{var counted=JsonSerializer.Deserialize<Dictionary<string,decimal>>(root.GetProperty("counted").GetRawText(),jsonIn)!;var nd=store.RecordInventory(root.GetProperty("warehouse").GetString()??"",counted,Environment.UserName);await Push();await Notify($"Inventario registrato: {nd} differenze.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "articleSave":
     try{store.SaveArticle(JsonSerializer.Deserialize<Article>(root.GetProperty("item").GetRawText(),jsonIn)!,Environment.UserName);await Push();await Notify("Articolo salvato.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "levelSave":
     try{store.SaveStockLevel(JsonSerializer.Deserialize<StockLevel>(root.GetProperty("item").GetRawText(),jsonIn)!);await Push();}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "dueNote":
     try{store.SaveDueNote(root.GetProperty("id").GetString()??"",root.GetProperty("status").GetString()??"",root.GetProperty("note").GetString()??"",Environment.UserName);await Push();}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "exportInvoices":case "exportDues":
     var ap=new SaveFileDialog{Filter="CSV italiano|*.csv",FileName=(action=="exportInvoices"?"TopCash-fatture-":"TopCash-scadenze-aperte-")+DateTime.Today.ToString("yyyyMMdd")+".csv"};
     if(ap.ShowDialog(this)==true){File.WriteAllText(ap.FileName,action=="exportInvoices"?AccountingReports.InvoicesCsv(store):AccountingReports.DuesCsv(store),new UTF8Encoding(true));await Notify("File salvato.");}break;
    case "exportStock":case "exportReorder":
     var sp=new SaveFileDialog{Filter="CSV italiano|*.csv",FileName=(action=="exportStock"?"TopCash-giacenze-":"TopCash-proposta-ordine-")+DateTime.Today.ToString("yyyyMMdd")+".csv"};
     if(sp.ShowDialog(this)==true){File.WriteAllText(sp.FileName,action=="exportStock"?WarehouseReports.StockCsv(store):WarehouseReports.ReorderCsv(store),new UTF8Encoding(true));await Notify("File salvato.");}break;
    case "signIntervention":
     try{var b64=(root.GetProperty("signature").GetString()??"").Split(',').Last();var signed=store.SignIntervention(root.GetProperty("id").GetString()??"",root.GetProperty("signer").GetString()??"",root.GetProperty("hours").GetString()??"",Convert.FromBase64String(b64),root.GetProperty("close").GetBoolean(),Environment.UserName);
      await Push();await Notify($"Rapportino n. {signed.ReportNo} firmato.");Post(new{type="signed",id=signed.Id,reportNo=signed.ReportNo});}catch(Exception ex) when(ex is InvalidDataException or FormatException){await Notify(ex.Message);}break;
    case "unsignIntervention":
     try{store.UnsignIntervention(root.GetProperty("id").GetString()??"",Environment.UserName);await Push();await Notify("Firma annullata: ora il rapportino si può modificare.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "reportPdf":
     {var rid=root.GetProperty("id").GetString()??"";var rv=store.Interventions().FirstOrDefault(x=>x.Id==rid);if(rv==null)break;
      var rp=new SaveFileDialog{Filter="PDF|*.pdf",FileName=InterventionReport.FileName(rv)};
      if(rp.ShowDialog(this)==true){File.WriteAllBytes(rp.FileName,InterventionReport.Build(store,rid));Process.Start(new ProcessStartInfo(rp.FileName){UseShellExecute=true});}}break;
    case "reportPrint":
     {var rid=root.GetProperty("id").GetString()??"";var rv=store.Interventions().FirstOrDefault(x=>x.Id==rid);if(rv==null)break;
      var dir=Path.Combine(Path.GetTempPath(),"TopCashStudio-stampa");Directory.CreateDirectory(dir);
      foreach(var old in new DirectoryInfo(dir).GetFiles("*.pdf").Where(f=>f.LastWriteTime<DateTime.Now.AddDays(-1)))try{old.Delete();}catch{}
      var tmp=Path.Combine(dir,InterventionReport.FileName(rv));File.WriteAllBytes(tmp,InterventionReport.Build(store,rid));
      Process.Start(new ProcessStartInfo(tmp){UseShellExecute=true});await Notify("PDF aperto: stampalo con Ctrl+P.");}break;
    case "interventionDelete":
     try{store.DeleteIntervention(root.GetProperty("id").GetString()??"");await Push();await Notify("Intervento eliminato.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "titleBar":TitleBar.Set(root.TryGetProperty("dark",out var tdk)&&tdk.ValueKind==JsonValueKind.True,(byte)root.GetProperty("r").GetInt32(),(byte)root.GetProperty("g").GetInt32(),(byte)root.GetProperty("b").GetInt32());break;
    case "hostInfo":Post(new{type="host",syncStation=syncToken.Length>0,serverUrl,serverExternal,activeServer=Active,portalOpen=portal!=null,autostart=Autostart,perf=PerfInfo()});
     if(!perfWatch){perfWatch=true;SystemEvents.PowerModeChanged+=(_,_)=>Dispatcher.BeginInvoke(()=>Post(new{type="host",perf=PerfInfo()}));SystemParameters.StaticPropertyChanged+=(_,e)=>{if(e.PropertyName is nameof(SystemParameters.ClientAreaAnimation) or nameof(SystemParameters.IsRemoteSession))Dispatcher.BeginInvoke(()=>Post(new{type="host",perf=PerfInfo()}));};}Post(UpdatePayload());break;
    case "checkUpdate":await CheckUpdate(true);break;
    case "applyUpdate":await UpdateNow();break;
    case "autostart":
     Autostart=root.GetProperty("on").GetBoolean();Post(new{type="host",autostart=Autostart});
     await Notify(Autostart?"Studio partirà da solo all'accesso a Windows, ridotto a icona.":"Avvio automatico disattivato.");break;
    case "setExternal":
     {
      var raw=(root.GetProperty("url").GetString()??"").Trim();
      if(raw.Length==0){serverExternal="";SaveSettings();await Notify("Indirizzo esterno rimosso.");break;}
      if(!raw.Contains("://"))raw="https://"+raw;
      if(!Uri.TryCreate(raw,UriKind.Absolute,out var eu)||eu.Scheme!="https"||!eu.Host.Contains('.')){await Notify("Indirizzo esterno non valido: usa il nome del dominio, es. topcash.tuodominio.it");break;}
      serverExternal=eu.GetLeftPart(UriPartial.Authority)+"/";SaveSettings();
      Post(new{type="host",syncStation=syncToken.Length>0,serverUrl,serverExternal,activeServer=Active,portalOpen=portal!=null});// auto: the address came from the office server itself, no message needed
      if(!(root.TryGetProperty("auto",out var au)&&au.ValueKind==JsonValueKind.True))await Notify("Indirizzo esterno salvato: fuori ufficio verrà usato in automatico.");break;
     }
    case "connectServer":
     var url=NormalizeServer(root.GetProperty("url").GetString()??"");if(url==null){await Notify("Indirizzo del server non valido.");break;}
     try{using var http=new System.Net.Http.HttpClient{Timeout=TimeSpan.FromSeconds(6)};var res=await http.GetAsync(url+"api/health");if(!res.IsSuccessStatusCode)throw new Exception("HTTP "+(int)res.StatusCode);}
     catch(Exception ex){await Notify("Server non raggiungibile: "+ex.Message);break;}
     serverUrl=url;activeServer=url;SaveSettings();portal?.Quit();Navigate();break;
    case "disconnectServer":serverUrl="";serverExternal="";activeServer="";syncToken="";SaveSettings();portal?.Quit();Navigate();break;
    case "setSyncToken":syncToken=root.GetProperty("token").GetString()??"";SaveSettings();if(portal!=null)portal.Target=SyncTarget();if(dtr!=null)dtr.Target=SyncTarget();if(ital!=null)ital.Target=SyncTarget();if(rch!=null)rch.Target=SyncTarget();
     Post(new{type="host",syncStation=syncToken.Length>0,serverUrl,portalOpen=portal!=null});await Notify(syncToken.Length>0?"Questo PC è ora la postazione di sincronizzazione.":"Postazione di sincronizzazione disattivata.");break;
    case "ready":await Push();break;
    case "scan":Scan(true);await Push();await Notify("Cartella controllata. Consulta il registro per i dettagli.");break;
    case "import":
     var picker=new OpenFileDialog{Title="Importa clienti e licenze",Filter="Esportazioni Zucchetti|*.json;*.csv;*.xlsx",Multiselect=true};
     if(picker.ShowDialog(this)==true){foreach(var f in picker.FileNames)ImportOne(f,false);await Push();await Notify("Importazione terminata. Esito disponibile nel registro.");}break;
    case "folder":
     var fp=new OpenFolderDialog{Title="Cartella delle esportazioni da aggiornare automaticamente"};
     if(fp.ShowDialog(this)==true){sourceFolder=fp.FolderName;SaveSettings();Scan(false);await Push();}break;
    case "backup":
     var bp=new SaveFileDialog{Filter="Backup SQLite|*.sqlite",FileName="TopCash-backup-"+DateTime.Now.ToString("yyyyMMdd-HHmm")+".sqlite"};
     if(bp.ShowDialog(this)==true){store.Backup(bp.FileName);store.Log("backup","Backup locale creato.");await Notify("Backup creato correttamente.");await Push();}break;
    case "export":
     var ep=new SaveFileDialog{Filter="CSV italiano|*.csv",FileName="TopCash-clienti-licenze-"+DateTime.Today.ToString("yyyyMMdd")+".csv"};
     if(ep.ShowDialog(this)==true){var records=store.Licenses();if(root.TryGetProperty("ids",out var ids)){var selected=ids.EnumerateArray().Select(x=>x.GetString()).ToHashSet();records=records.Where(r=>selected.Contains(r.Id)).ToList();}File.WriteAllText(ep.FileName,Imports.CsvExport(records),new UTF8Encoding(true));await Notify("Esportazione CSV salvata sul computer.");}break;
    case "calendar":
     var cp=new SaveFileDialog{Filter="Calendario iCalendar|*.ics",FileName="TopCash-scadenze-90-giorni.ics"};
     if(cp.ShowDialog(this)==true){File.WriteAllText(cp.FileName,Imports.Calendar(store.Licenses().Where(r=>!r.Scope.EndsWith("-scadute"))),new UTF8Encoding(false));await Notify("Calendario delle scadenze salvato. Aprilo nell'app calendario che preferisci.");}break;
    case "note":store.SaveNote(root.GetProperty("code").GetString()!,root.GetProperty("note").GetString()!);await Notify("Nota salvata sul computer.");break;
    case "clientSave":{var ed=JsonSerializer.Deserialize<ClientEdit>(root.GetProperty("edit").GetRawText(),new JsonSerializerOptions{PropertyNameCaseInsensitive=true})!;
     try{var code=store.SaveClient(root.TryGetProperty("code",out var cc)?cc.GetString():"",ed,Environment.UserName);await Push();await Notify("Cliente salvato.");Post(new{type="clientSaved",code});}catch(InvalidDataException ex){await Notify(ex.Message);}break;}
    case "clientDelete":{var del=root.GetProperty("deleted").GetBoolean();store.DeleteClient(root.GetProperty("code").GetString()!,del,Environment.UserName);await Push();await Notify(del?"Cliente eliminato: puoi ripristinarlo da Clienti → Eliminati.":"Cliente ripristinato.");break;}
    case "clientReset":try{store.ResetClient(root.GetProperty("code").GetString()!,Environment.UserName);await Push();await Notify("Modifiche annullate: il cliente torna ai dati dei portali e di Mastro.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "assetSave":try{store.SaveAsset(JsonSerializer.Deserialize<Asset>(root.GetProperty("asset").GetRawText(),new JsonSerializerOptions{PropertyNameCaseInsensitive=true})!,Environment.UserName);await Push();await Notify("Inventario aggiornato.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "assetDelete":store.DeleteAsset(root.GetProperty("id").GetString()??"",Environment.UserName);await Push();await Notify("Elemento tolto dall'inventario.");break;
    case "routeDone":try{store.SaveRouteDone(S(root,"key"),root.TryGetProperty("done",out var rd)&&rd.ValueKind==JsonValueKind.True,Environment.UserName);await Push();}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "machineFlag":try{var n=store.SaveMachineFlags(root.GetProperty("ids").EnumerateArray().Select(x=>x.GetString()??""),S(root,"state"),Environment.UserName);await Push();await Notify(n==1?"Macchina aggiornata.":$"{n} macchine aggiornate.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "renewalSave":try{store.SaveRenewal(root.GetProperty("key").GetString()??"",S(root,"status"),S(root,"note"),S(root,"nextContact"),Environment.UserName);await Push();}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "linkSave":try{store.SaveDeviceLink(root.GetProperty("serial").GetString()??"",S(root,"clientCode"),Environment.UserName);await Push();await Notify("Abbinamento salvato.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "clientMerge":try{store.MergeClient(root.GetProperty("code").GetString()??"",S(root,"into"),Environment.UserName);await Push();await Notify("Clienti uniti.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;
    case "brandsSave":store.SaveHiddenBrands(root.GetProperty("brands").EnumerateArray().Select(x=>x.GetString()??""),Environment.UserName);await Push();await Notify("Regola sulle marche salvata.");break;
    case "licenseSave":{var le=JsonSerializer.Deserialize<LicenseEdit>(root.GetProperty("edit").GetRawText(),new JsonSerializerOptions{PropertyNameCaseInsensitive=true})!;
     try{store.SaveLicense(root.GetProperty("id").GetString()!,le,Environment.UserName);await Push();await Notify(le.Hidden?"Licenza tolta dall’elenco.":"Licenza aggiornata.");}catch(InvalidDataException ex){await Notify(ex.Message);}break;}
    case "meta":var meta=JsonSerializer.Deserialize<ClientMeta>(root.GetProperty("meta").GetRawText(),new JsonSerializerOptions{PropertyNameCaseInsensitive=true})!;store.SaveMeta(root.GetProperty("code").GetString()!,meta);await Push();await Notify("Scheda di lavoro aggiornata.");break;
    case "portal":if(CanSync())OpenPortal();break;
    case "dtr":if(CanSync())OpenDtr(true);break;
    case "ital":if(CanSync())OpenItal(true);break;
    case "italSync":if(CanSync()){OpenItal(false);if(ital!=null)await ital.StartSync();}break;
    case "rch":if(CanSync())OpenRch(true);break;
    case "rchSync":if(CanSync()){OpenRch(false);if(rch!=null)await rch.StartSync();}break;
    case "dtrSync":if(CanSync()){OpenDtr(false);if(dtr!=null)await dtr.StartSync();}break;
    case "syncPortal":if(CanSync()){OpenPortal();if(portal!=null)await portal.StartSync();}break;
    case "external":var ext=root.GetProperty("url").GetString()??"";if(SafeExternal(ext))Process.Start(new ProcessStartInfo(ext){UseShellExecute=true});break;
    case "exportClients":
     var xp=new SaveFileDialog{Filter="CSV italiano|*.csv",FileName="TopCash-anagrafica-clienti-"+DateTime.Today.ToString("yyyyMMdd")+".csv"};
     if(xp.ShowDialog(this)==true){File.WriteAllText(xp.FileName,Imports.ClientsCsv(store.Clients(),store.Licenses(),store.Metadata(),store.Notes()),new UTF8Encoding(true));await Notify("Anagrafica clienti esportata.");}break;
    case "dataFolder":Process.Start(new ProcessStartInfo("explorer.exe",store.Folder){UseShellExecute=true});break;
   }
  }
  catch(Exception ex){store.Log("error",ex.Message);await Notify(ex.Message);await Push();}
 }
 ISyncTarget SyncTarget()=>ServerMode&&syncToken.Length>0?new RemoteSyncTarget(serverUrl,syncToken):new LocalSyncTarget(store);
 bool CanSync()
 {
  if(!ServerMode||syncToken.Length>0)return true;
  _=Notify("La sincronizzazione con Zucchetti si avvia dalla postazione dell'ufficio. Un amministratore può attivarla da Impostazioni → Postazione di sincronizzazione.");return false;
 }
 static string S(JsonElement e,string k)=>e.TryGetProperty(k,out var v)&&v.ValueKind==JsonValueKind.String?v.GetString()??"":"";
 // What the page needs to pick its graphics profile by itself: real memory, remote desktop, Windows animations, battery saver.
 bool perfWatch;
 [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)] struct PowerStatus{public byte AC,Battery,Percent,Saver;public int Life,Full;}
 [System.Runtime.InteropServices.DllImport("kernel32.dll")] static extern bool GetSystemPowerStatus(out PowerStatus s);
 static object PerfInfo()
 {
  bool saver=false;try{if(GetSystemPowerStatus(out var p))saver=p.Saver==1;}catch{}
  double ram=0;try{ram=Math.Round(GC.GetGCMemoryInfo().TotalAvailableMemoryBytes/1073741824.0,1);}catch{}
  return new{ramGb=ram,remote=SystemParameters.IsRemoteSession,animations=SystemParameters.ClientAreaAnimation,saver,cores=Environment.ProcessorCount};
 }
 void Post(object payload){try{Dashboard.CoreWebView2?.PostWebMessageAsJson(JsonSerializer.Serialize(payload,json));}catch{}}
 /// Start with Windows (current user only, HKCU "Run"): keeps the sync station working after a restart.
 const string RunKey=@"Software\Microsoft\Windows\CurrentVersion\Run",RunName="TopCashStudio";
 static bool Autostart
 {
  get{try{using var k=Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey);return k?.GetValue(RunName) is string v&&v.Contains(Environment.ProcessPath??"?",StringComparison.OrdinalIgnoreCase);}catch{return false;}}
  set{using var k=Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RunKey);if(value)k.SetValue(RunName,$"\"{Environment.ProcessPath}\" --minimized");else k.DeleteValue(RunName,false);}
 }
 RchWindow? rch;
 void OpenRch(bool show)
 {
  if(rch==null)
  {
   rch=new RchWindow(SyncTarget(),async()=>await Push()){Owner=this};
   rch.SignInRequired=()=>Dispatcher.InvokeAsync(async()=>await SignInLost("RCH XDealer"));
   if(show)rch.Reveal();else rch.StartHidden();
  }
  else if(show)rch.Reveal();
 }
 ItalRetailWindow? ital;
 void OpenItal(bool show)
 {
  if(ital==null)
  {
   ital=new ItalRetailWindow(SyncTarget(),async()=>await Push()){Owner=this};
   ital.SignInRequired=()=>Dispatcher.InvokeAsync(async()=>await SignInLost("Ital Retail"));
   if(show)ital.Reveal();else ital.StartHidden();
  }
  else if(show)ital.Reveal();
 }
 DtrWindow? dtr;
 void OpenDtr(bool show)
 {
  if(dtr==null)
  {
   dtr=new DtrWindow(SyncTarget(),async()=>await Push()){Owner=this};
   dtr.SignInRequired=()=>Dispatcher.InvokeAsync(async()=>await SignInLost("DTR-Service"));
   if(show)dtr.Reveal();else dtr.StartHidden();
  }
  else if(show)dtr.Reveal();
 }
 // A portal that asks to sign in again never pops up by itself (not at start, not later): a short notice, and the
 // window opens only when the user chooses to reconnect (Sincronizzazione → Portali e accessi). One notice every 6 hours.
 readonly Dictionary<string,DateTime> signInNoticed=[];
 async Task SignInLost(string label)
 {
  if(signInNoticed.TryGetValue(label,out var t)&&DateTime.Now-t<TimeSpan.FromHours(6))return;
  signInNoticed[label]=DateTime.Now;
  await Notify($"{label} si è scollegato: quando vuoi, ricollegalo da Sincronizzazione → Portali e accessi.");
 }
 // the portals' sessions are written to disk one last time before Studio closes, so they are still there next time
 bool sessionsSaved;
 protected override async void OnClosing(System.ComponentModel.CancelEventArgs e)
 {
  if(!sessionsSaved){e.Cancel=true;sessionsSaved=true;try{await Task.WhenAny(PortalKeep.SaveAll(),Task.Delay(2500));}catch{}Close();return;}
  base.OnClosing(e);
 }
 void OpenPortal(){if(portal==null){portal=new PortalWindow(SyncTarget(),async()=>await Push());portal.ProgressChanged=Progress;portal.Owner=this;portal.Closed+=(_,_)=>portal=null;portal.Show();}else portal.Reveal();}
 int Scan(bool log)
 {
  if(busy)return 0;busy=true;int count=0;
  try{if(!Directory.Exists(sourceFolder)){if(log)store.Log("error","Cartella sorgente non disponibile.");return 0;}
   foreach(var f in Directory.EnumerateFiles(sourceFolder).Where(f=>new[]{".csv",".json",".xlsx"}.Contains(Path.GetExtension(f).ToLowerInvariant())&&!Path.GetFileName(f).Equals("settings.json",StringComparison.OrdinalIgnoreCase)&&!Path.GetFileName(f).StartsWith("portal-")))
    if(ImportOne(f,true))count++;
   if(log&&count==0)store.Log("check","Nessuna nuova esportazione da importare.");return count;
  }finally{busy=false;}
 }
 // Files already checked (same size and timestamp) are skipped without being read or hashed again.
 readonly Dictionary<string,(long Size,DateTime Time)> seen=new(StringComparer.OrdinalIgnoreCase);
 bool ImportOne(string file,bool skipKnown)
 {
  var info=new FileInfo(file);var stamp=(info.Length,info.LastWriteTimeUtc);
  if(skipKnown&&seen.TryGetValue(file,out var old)&&old==stamp)return false;
  try{var (batch,hash)=Imports.Read(file);seen[file]=stamp;if(skipKnown&&store.WasImported(hash))return false;store.Import(batch,file,hash);return true;}
  catch(Exception ex){seen[file]=stamp;var fingerprint=Path.GetFileName(file)+":"+File.GetLastWriteTimeUtc(file).Ticks;
   if(failedFiles.Add(fingerprint))store.Log("error",Path.GetFileName(file)+": "+ex.Message);return false;}
 }
 readonly HashSet<string> failedFiles=[];
 void Progress(string text){try{Dashboard.CoreWebView2?.PostWebMessageAsJson(JsonSerializer.Serialize(new{type="progress",running=portal?.Running??false,connected=portal?.Authenticated??false,text},json));}catch{}}
 /// Full archive refresh. While a sync is running, refreshes are coalesced (max one every 1.5 s, trailing one guaranteed).
 public async Task Push()
 {
  if((portal?.Running??false)&&(DateTime.UtcNow-lastPush).TotalMilliseconds<4000){pushPending=true;if(!pushTimer.IsEnabled)pushTimer.Start();return;}
  await PushNow();
 }
 /// The archive is read and turned into text away from the window (the interface never freezes on it); an identical
 /// archive is not sent again, and two refreshes never overlap (the last one asked always arrives).
 bool pushBusy;string lastJson="";
 async Task PushNow(){lastPush=DateTime.UtcNow;if(ServerMode)return; // the shared server feeds the UI itself
  if(pushBusy){pushPending=true;if(!pushTimer.IsEnabled)pushTimer.Start();return;}
  pushBusy=true;
  try{
  bool running=portal?.Running??false,connected=portal?.Authenticated??false;string progress=portal?.Progress??"";
  var text=await Task.Run(()=>{var mv=store.Mastro();
 
  return JsonSerializer.Serialize(new{type="state",records=store.Licenses(),licenseEdits=store.LicenseEdits(),licenseCleanup=store.LicenseCleanup(),clientEdits=store.ClientEdits(),clients=store.Clients(),orders=store.Orders(),mastroClients=mv.Clients,machines=mv.Machines,interventions=mv.Interventions,brandRule=mv.Excluded,assets=store.Assets(),renewals=store.Renewals(),deviceLinks=store.DeviceLinks(),rt=store.RtInfos(),machineFlags=store.MachineFlags(),routeDone=store.RouteDone(),photos=store.Photos(),sites=store.Sites(),riba=store.RibaList(),warehouses=store.Warehouses(),articles=store.Articles(),stockLevels=store.StockLevels(),stock=store.Stock(),movements=store.Movements(),invoices=store.Invoices(),dues=store.Dues(),balances=store.Balances(),dueNotes=store.DueNotes(),sales=store.Sales(),ledger=store.Ledger(),mastroSync=store.LastMastroSync(),insights=store.Insights(),team=store.Team(),syncRunning=running,syncProgress=progress,sources=store.Sources(),events=store.Events(),notes=store.Notes(),metadata=store.Metadata(),dtr=store.DtrRegisters(),dtrStatus=store.DtrState(),ital=store.ItalDevices(),italOrders=store.ItalOrders(),italStatus=store.ItalState(),rch=store.RchDevices(),rchStatus=store.RchState(),sourceFolder,dataFolder=store.Folder,autoImport=true,portalConnected=connected},json);});
  if(text==lastJson)return;lastJson=text;
  Dashboard.CoreWebView2?.PostWebMessageAsJson(text);
  }catch(Exception ex){System.Diagnostics.Debug.WriteLine(ex);}
  finally{pushBusy=false;}
 }
 // In server mode the progress also reaches every browser through the server; locally it goes straight to the page.
 Task Notify(string message){Dashboard.CoreWebView2?.PostWebMessageAsJson(JsonSerializer.Serialize(new{type="toast",message},json));return Task.CompletedTask;}
}
