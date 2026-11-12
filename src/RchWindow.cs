using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace TopCash.Licenze;
/// Keeps an RCH XDealer (dealer area) session alive and copies the device list (read-only).
/// The user signs in personally in this window (RCH identity provider): Top Cash never sees or stores the password.
/// The session lives in a dedicated persistent browser profile and is resumed at every start of Studio.
/// Data is read with the portal's own JSON API (the same calls its pages make, GET only): nothing is ever changed on the portal.
public sealed class RchWindow : Window
{
 const string Host="xdealer.rch.it";
 public const string Origin="https://"+Host;
 const string Dealer=Origin+"/it/dealer/";
 public static string Profile=>Paths.Runtime("webview","Rch");
 public static bool Configured=>File.Exists(Path.Combine(Profile,"connected.flag"));
 readonly WebView2 browser=new();
 readonly TextBlock status=new(){Text="Accedi a RCH XDealer con le tue credenziali: la sessione resterà attiva su questo PC.",VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(16,0,12,0),TextTrimming=TextTrimming.CharacterEllipsis};
 readonly DispatcherTimer keepAlive=new(){Interval=TimeSpan.FromMinutes(15)},schedule=new(){Interval=TimeSpan.FromHours(2)},detect=new(){Interval=TimeSpan.FromSeconds(2.5)};
 readonly Func<Task> changed;
 ISyncTarget target;CancellationTokenSource? cancel;bool initialized,running,quitting,hiddenStart,warned,probing;int misses;DateTime lastSync;
 public ISyncTarget Target{get=>target;set=>target=value;}
 public bool Authenticated{get;private set;}
 public Action? SignInRequired{get;set;}

 public RchWindow(ISyncTarget target,Func<Task> changed)
 {
  TitleBar.Attach(this);
  this.target=target;this.changed=changed;Title="Top Cash Studio · Collegamento RCH XDealer";
  try{Icon=System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/topcash.ico"));}catch{}
  Width=1280;Height=860;WindowStartupLocation=WindowStartupLocation.CenterScreen;Background=System.Windows.Media.Brushes.White;
  var layout=new DockPanel();var bar=new DockPanel{Background=System.Windows.Media.Brushes.White,MinHeight=58};DockPanel.SetDock(bar,Dock.Top);layout.Children.Add(bar);
  var sync=new Button{Content="Aggiorna ora (sola lettura)",Margin=new Thickness(10),Padding=new Thickness(14,8,14,8)};sync.Click+=async(_,_)=>await StartSync();DockPanel.SetDock(sync,Dock.Right);bar.Children.Add(sync);
  var hide=new Button{Content="Nascondi (resta collegato)",Margin=new Thickness(6,10,0,10),Padding=new Thickness(12,8,12,8)};hide.Click+=(_,_)=>Hide();DockPanel.SetDock(hide,Dock.Right);bar.Children.Add(hide);
  bar.Children.Add(status);layout.Children.Add(browser);Content=layout;
  Closing+=(_,e)=>{if(!quitting){e.Cancel=true;Hide();}};
  Loaded+=Initialize;
  keepAlive.Tick+=async(_,_)=>await KeepAlive();
  schedule.Tick+=async(_,_)=>{if(Authenticated&&!running)await StartSync();};
  // the portal is a single-page app: it shows its own page first and only then moves to the sign-in, so the session is
  // confirmed with a real (read-only) API call a moment after each page change, never from the address alone
  detect.Tick+=async(_,_)=>{detect.Stop();await Detect();};
 }
 public void Quit(){quitting=true;cancel?.Cancel();keepAlive.Stop();schedule.Stop();detect.Stop();try{Close();}catch{}PortalKeep.Detach(browser.CoreWebView2);browser.Dispose();}
 public void StartHidden(){hiddenStart=true;WindowStartupLocation=WindowStartupLocation.Manual;Left=-32000;Top=-32000;ShowInTaskbar=false;ShowActivated=false;Show();}
 public void Reveal()
 {
  if(hiddenStart){hiddenStart=false;var wa=SystemParameters.WorkArea;Left=wa.Left+Math.Max(0,(wa.Width-Width)/2);Top=wa.Top+Math.Max(0,(wa.Height-Height)/2);ShowInTaskbar=true;ShowActivated=true;}
  Show();if(WindowState==WindowState.Minimized)WindowState=WindowState.Normal;Activate();
 }

 async void Initialize(object? sender,RoutedEventArgs e)
 {
  if(initialized)return;initialized=true;
  try
  {
   var env=await CoreWebView2Environment.CreateAsync(null,Profile);
   await browser.EnsureCoreWebView2Async(env);var core=browser.CoreWebView2;
   core.Settings.IsPasswordAutosaveEnabled=true;core.Settings.IsGeneralAutofillEnabled=true;
   core.PermissionRequested+=(_,ev)=>ev.State=CoreWebView2PermissionState.Deny;
   core.NavigationStarting+=(_,ev)=>{if(!Https(ev.Uri))ev.Cancel=true;};
   core.NewWindowRequested+=(_,ev)=>{ev.Handled=true;if(Https(ev.Uri))core.Navigate(ev.Uri);};
   core.DownloadStarting+=(_,ev)=>ev.Cancel=true;
   core.SourceChanged+=(_,_)=>Later();core.HistoryChanged+=(_,_)=>Later();core.NavigationCompleted+=(_,_)=>Later();
   PortalKeep.Attach(core);core.Navigate(Dealer+"dashboard");keepAlive.Start();schedule.Start();
   if(hiddenStart)Hide();
  }
  catch(Exception ex){SetStatus("Browser non disponibile: "+ex.Message);}
 }
 static bool Https(string url)=>url=="about:blank"||Uri.TryCreate(url,UriKind.Absolute,out var u)&&u.Scheme=="https";
 bool OnPortal=>browser.CoreWebView2?.Source is string s&&Uri.TryCreate(s,UriKind.Absolute,out var u)&&u.Host==Host;
 void Later(){if(!running){detect.Stop();detect.Start();}}

 /// GET call made by the portal page itself (its configured client carries the session). Login = the portal refused it.
 const string ApiScript="""
 (async(path,params)=>{
  if(location.hostname!=='xdealer.rch.it')return{login:true};
  try{
   if(window.axios&&window.axios.get)return{data:(await window.axios.get(path,{params})).data};
   const r=await fetch(path+'?'+new URLSearchParams(params),{credentials:'include',headers:{Accept:'application/json','X-Requested-With':'XMLHttpRequest'}});
   if([401,403,419].includes(r.status))return{login:true};if(!r.ok)return{error:'HTTP '+r.status};return{data:await r.json()};
  }catch(e){const s=e&&e.response&&e.response.status;if([401,403,419].includes(s))return{login:true};return{error:String(s?'HTTP '+s:(e&&e.message)||e)};}
 })
 """;
 /// One page of devices mapped to Studio's fields (the portal's names are read with fallbacks).
 const string MapScript="""
 ((d)=>{const S=v=>v==null?'':typeof v==='object'?String(v.name??v.description??v.label??v.code??v.value??''):String(v);
  const N=v=>{const n=parseInt(v,10);return isNaN(n)?0:n;};
  return(d.data||[]).map(e=>{const dd=(e.dealer_devices||e['dealer-devices']||e.dealerDevices||[])[0]||{};const c=e.customer||dd.customer||{};const g=e.diagnostic_data||{};
   const live=!e.is_replaced_by;const up=live&&(e.has_update||e.purchased_version)?(S(e.has_update?e.available_version:e.purchased_version)||'sì'):live&&e.has_fiscal_updates&&e.available_version_fiscal_update?S(e.available_version_fiscal_update):'';
   const free=g.dgfe_perc_free;const ser=S(e.serial_number).trim();
   return[ser,JSON.stringify({Serial:ser,PortalId:S(e.id),Part:S(e.part_number),Type:S(e.device_type??e.type),State:S(e.state??e.status??e.device_state),
    Client:S(c.name),Vat:S(c.vat_number),City:S(c.city),Province:S(c.province),Phone:S(c.phone),Email:S(c.email),Note:S(e.notes??dd.notes),Labels:(e.labels||dd.labels||[]).map(S).filter(Boolean).join(', '),
    Contract:!!e.has_maintenance_contract,Update:up,Firmware:S(e.version??e.firmware_version??e.current_version??e.fw_version),
    DgfeFree:free==null||free===''||isNaN(+free)?-1:Math.round(+free),DgfeNumber:N(g.dgfe_number),LastVerification:S(g.last_verification_date),ZDate:S(g.z_report_date),ZNumber:N(g.z_report_number),
    CertFrom:S(g.cert_start_date),CertDue:S(g.cert_expire_date),Activated:S(g.act_date),Registered:S(g.device_reg_date)})];}).filter(r=>r[0]);})
 """;
 async Task<JsonElement> Api(string path,object query,CancellationToken ct)
 {
  var id="__tcs"+Guid.NewGuid().ToString("N");
  await browser.ExecuteScriptAsync($"window['{id}']=null;({ApiScript})({JsonSerializer.Serialize(path)},{JsonSerializer.Serialize(query)}).then(v=>window['{id}']=JSON.stringify(v),e=>window['{id}']=JSON.stringify({{error:String(e)}}));");
  for(int i=0;i<150;i++)
  {
   await Task.Delay(i<5?150:400,ct);
   var s=JsonSerializer.Deserialize<string?>(await browser.ExecuteScriptAsync($"window['{id}']"));
   if(s==null)continue;
   _=browser.ExecuteScriptAsync($"delete window['{id}']");
   var r=JsonDocument.Parse(s).RootElement.Clone();
   if(r.TryGetProperty("login",out _))throw new UnauthorizedAccessException("Sessione RCH XDealer scaduta: accedi di nuovo.");
   if(r.TryGetProperty("error",out var er))throw new InvalidDataException("RCH XDealer: "+er.GetString());
   return r.GetProperty("data");
  }
  throw new TimeoutException("RCH XDealer non ha risposto.");
 }

 async Task Detect()
 {
  if(browser.CoreWebView2==null||running||probing)return;
  var src=browser.CoreWebView2.Source;if(src is null or "" or "about:blank")return;
  bool now=false;
  if(OnPortal){probing=true;try{await Api("/dealer/api/v2/summary/counters",new{},CancellationToken.None);now=true;}catch(UnauthorizedAccessException){now=false;}catch{return;}finally{probing=false;}}
  // signed out only when confirmed twice, 20 s apart: the portal passes through RCH's sign-in site by itself to renew the session
  if(!now&&Authenticated&&misses==0){misses=1;_=Dispatcher.InvokeAsync(async()=>{await Task.Delay(20000);await Detect();});return;}
  misses=0;
  if(now==Authenticated&&(now||warned))return;
  Authenticated=now;
  if(now)
  {
   warned=false;
   try{File.WriteAllText(Path.Combine(Profile,"connected.flag"),DateTime.Now.ToString("O"));}catch{}
   SetStatus("Collegato a RCH XDealer. Aggiornamento automatico ogni 2 ore, sessione mantenuta attiva.");_=Heartbeat(true,"Collegato");
   if(!running&&DateTime.Now-lastSync>TimeSpan.FromMinutes(30))_=Dispatcher.InvokeAsync(async()=>{await Task.Delay(1500);await StartSync();});
  }
  else
  {
   warned=true;
   SetStatus("RCH XDealer chiede di accedere. Inserisci le tue credenziali qui sotto.");
   if(Configured){_=Heartbeat(false,"Accesso richiesto");SignInRequired?.Invoke();}
  }
 }
 async Task KeepAlive()
 {
  if(running||browser.CoreWebView2==null||!Authenticated)return;
  try{browser.CoreWebView2.Navigate(Dealer+"dashboard");await Task.Delay(8000);await Detect();if(Authenticated)await Heartbeat(true,"Sessione attiva");}catch{}
 }
 async Task Heartbeat(bool connected,string message,int total=0)
 {
  try{await target.Apply("rchstatus","RCH","Stato RCH XDealer",[connected?"1":"0",message,total.ToString()],[],DateTimeOffset.Now.ToString("O"));await changed();}catch{}
 }
 void SetStatus(string text)=>status.Text=text;

 const int PerPage=100;
 public async Task StartSync()
 {
  if(running)return;
  if(browser.CoreWebView2==null){SetStatus("Il browser si sta avviando…");return;}
  if(!Authenticated){SetStatus("Accedi a RCH XDealer, poi l'aggiornamento parte da solo.");SignInRequired?.Invoke();return;}
  running=true;cancel=new();var ct=cancel.Token;SetStatus("Lettura dei dispositivi RCH…");
  try
  {
   var all=new List<List<string>>();var seen=new HashSet<string>(StringComparer.OrdinalIgnoreCase);int total=0,read=0;
   for(int page=1;page<=300;page++)
   {
    var data=await Api("/dealer/api/v2/devices",new Dictionary<string,object>{["page"]=page,["per_page"]=PerPage,
     ["include"]="dealer-devices.customer,purchased_version,is_replaced_by,diagnostic_data",["response_variant"]="index-table"},ct);
    if(!data.TryGetProperty("meta",out var meta)||!meta.TryGetProperty("total",out var tot))throw new InvalidDataException("Risposta RCH XDealer cambiata: dati precedenti conservati.");
    total=tot.GetInt32();
    if(page==1&&data.TryGetProperty("data",out var first)&&first.GetArrayLength()>0)
     await target.Log("info","RCH XDealer, campi del dispositivo: "+string.Join(",",first[0].EnumerateObject().Select(p=>p.Name)));
    var mapped=JsonSerializer.Deserialize<List<List<string>>>(JsonSerializer.Deserialize<string>(await browser.ExecuteScriptAsync($"JSON.stringify(({MapScript})({data.GetRawText()}))"))??"[]")??[];
    var got=data.TryGetProperty("data",out var rows)?rows.GetArrayLength():0;read+=got;
    foreach(var r in mapped)if(r.Count==2&&seen.Add(r[0]))all.Add(r);
    SetStatus($"Lettura dispositivi RCH: {all.Count} di {total}…");
    var last=meta.TryGetProperty("last_page",out var lp)?lp.GetInt32():1;
    if(read>=total||page>=last||got==0)break;
   }
   if(total==0){await Heartbeat(true,"Nessun dispositivo",0);SetStatus("Nessun dispositivo sul portale RCH.");return;}
   if(read<total)throw new InvalidDataException($"Letti {read} dispositivi RCH su {total}: lettura incompleta, dati precedenti conservati.");
   if(read>all.Count)await target.Log("info",$"RCH XDealer: {read-all.Count} dispositivi senza matricola o doppi non importati.");
   var now=DateTimeOffset.Now.ToString("O");
   var msg=await target.Apply("rch","RCH","Dispositivi RCH XDealer",[..Store.RchHeaders],all,now);
   await Heartbeat(true,"Aggiornato",total);lastSync=DateTime.Now;
   SetStatus($"{msg} · prossimo aggiornamento tra 2 ore. Puoi nascondere questa finestra: resta collegata.");
  }
  catch(OperationCanceledException){SetStatus("Aggiornamento interrotto: dati precedenti conservati.");}
  catch(ObjectDisposedException){} // Studio is closing
  catch(UnauthorizedAccessException ex){Authenticated=true;running=false;await Detect();SetStatus(ex.Message);}
  catch(Exception ex){SetStatus(ex.Message);await target.Log("error","RCH XDealer: "+ex.Message);}
  finally
  {
   running=false;cancel?.Dispose();cancel=null;
   await changed();
  }
 }
}
