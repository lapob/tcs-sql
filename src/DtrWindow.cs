using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace TopCash.Licenze;
/// Keeps a DTR-Service session alive and copies the telematic cash register list (read-only).
/// The user signs in personally in this window: Top Cash never sees or stores the password. The session lives in a
/// dedicated persistent browser profile; the portal renews its own tokens while the page is reloaded every 15 minutes.
/// The only automated action on the portal is the visible "next page" arrow of the register list.
public sealed class DtrWindow : Window
{
 public const string Origin="https://www.dtr-service.it";
 const string ListUrl=Origin+"/rt_utility/cashregisters";
 public static string Profile=>Paths.Runtime("webview","Dtr");
 /// Studio starts the session by itself at launch once DTR has been connected on this PC.
 public static bool Configured=>File.Exists(Path.Combine(Profile,"connected.flag"));
 readonly WebView2 browser=new();
 readonly TextBlock status=new(){Text="Accedi a DTR-Service: la sessione resterà attiva su questo PC.",VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(16,0,12,0),TextTrimming=TextTrimming.CharacterEllipsis};
 readonly DispatcherTimer keepAlive=new(){Interval=TimeSpan.FromMinutes(15)},schedule=new(){Interval=TimeSpan.FromHours(2)};
 readonly Func<Task> changed;
 ISyncTarget target;CancellationTokenSource? cancel;bool initialized,running,quitting;
 public ISyncTarget Target{get=>target;set=>target=value;}
 public bool Authenticated{get;private set;}
 public bool Running=>running;
 public string Progress{get;private set;}="";
 public Action<string>? ProgressChanged{get;set;}
 /// Raised when the portal asks for a new sign-in: the host shows the window and notifies the user.
 public Action? SignInRequired{get;set;}

 public DtrWindow(ISyncTarget target,Func<Task> changed)
 {
  this.target=target;this.changed=changed;Title="Top Cash Studio · Collegamento DTR-Service";
  try{Icon=System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/topcash.ico"));}catch{}
  Width=1280;Height=860;WindowStartupLocation=WindowStartupLocation.CenterScreen;Background=System.Windows.Media.Brushes.White;
  var layout=new DockPanel();var bar=new DockPanel{Background=System.Windows.Media.Brushes.White,MinHeight=58};DockPanel.SetDock(bar,Dock.Top);layout.Children.Add(bar);
  var sync=new Button{Content="Aggiorna ora (sola lettura)",Margin=new Thickness(10),Padding=new Thickness(14,8,14,8)};sync.Click+=async(_,_)=>await StartSync();DockPanel.SetDock(sync,Dock.Right);bar.Children.Add(sync);
  var hide=new Button{Content="Nascondi (resta collegato)",Margin=new Thickness(6,10,0,10),Padding=new Thickness(12,8,12,8)};hide.Click+=(_,_)=>Hide();DockPanel.SetDock(hide,Dock.Right);bar.Children.Add(hide);
  bar.Children.Add(status);layout.Children.Add(browser);Content=layout;
  // Closing only hides: the session must stay alive in the background. The host calls Quit() when Studio exits.
  Closing+=(_,e)=>{if(!quitting){e.Cancel=true;Hide();}};
  Loaded+=Initialize;
  keepAlive.Tick+=async(_,_)=>await KeepAlive();
  schedule.Tick+=async(_,_)=>{if(Authenticated&&!running)await StartSync();};
 }
 public void Quit(){quitting=true;cancel?.Cancel();keepAlive.Stop();schedule.Stop();try{Close();}catch{}PortalKeep.Detach(browser.CoreWebView2);browser.Dispose();}
 /// Creates the browser without showing the window (used at startup when the session already exists).
 public void StartHidden(){hiddenStart=true;WindowStartupLocation=WindowStartupLocation.Manual;Left=-32000;Top=-32000;ShowInTaskbar=false;ShowActivated=false;Show();}
 bool hiddenStart;
 /// Brings the window on screen (sign-in or manual check).
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
   // The browser's own "save password" offer stays available to the user: a later sign-in is then one click.
   core.Settings.IsPasswordAutosaveEnabled=true;core.Settings.IsGeneralAutofillEnabled=true;
   core.PermissionRequested+=(_,ev)=>ev.State=CoreWebView2PermissionState.Deny;
   core.NavigationStarting+=(_,ev)=>{if(!Allowed(ev.Uri))ev.Cancel=true;};
   core.NewWindowRequested+=(_,ev)=>{ev.Handled=true;if(Allowed(ev.Uri))core.Navigate(ev.Uri);};
   core.DownloadStarting+=(_,ev)=>ev.Cancel=true;
   core.SourceChanged+=(_,_)=>Detect();core.HistoryChanged+=(_,_)=>Detect();core.NavigationCompleted+=(_,_)=>Detect();
   PortalKeep.Attach(core);core.Navigate(ListUrl);keepAlive.Start();schedule.Start();
   if(hiddenStart)Hide();
  }
  catch(Exception ex){SetStatus("Browser non disponibile: "+ex.Message);}
 }
 static bool Allowed(string url)=>Uri.TryCreate(url,UriKind.Absolute,out var u)&&u.Scheme=="https"&&(u.Host=="www.dtr-service.it"||u.Host=="dtr-service.it");
 bool OnLogin=>browser.CoreWebView2?.Source is string s&&Uri.TryCreate(s,UriKind.Absolute,out var u)&&u.AbsolutePath.StartsWith("/auth",StringComparison.OrdinalIgnoreCase);
 string lastSync="";
 void Detect()
 {
  if(browser.CoreWebView2==null)return;
  var now=!OnLogin;if(now==Authenticated)return;
  Authenticated=now;
  if(now)
  {
   try{File.WriteAllText(Path.Combine(Profile,"connected.flag"),DateTime.Now.ToString("O"));}catch{}
   SetStatus("Collegato a DTR-Service. Aggiornamento automatico ogni 2 ore, sessione mantenuta attiva.");_=Heartbeat(true,"Collegato");
   // first sign-in (or back after an expiry): read the list right away
   if(!running&&(lastSync.Length==0||DateTime.Now-DateTime.Parse(lastSync)>TimeSpan.FromMinutes(30)))_=Dispatcher.InvokeAsync(async()=>{await Task.Delay(2500);await StartSync();});
  }
  else
  {
   SetStatus("DTR-Service chiede di accedere di nuovo. Inserisci le tue credenziali qui sotto.");_=Heartbeat(false,"Accesso richiesto");
   SignInRequired?.Invoke();
  }
 }
 async Task KeepAlive()
 {
  if(running||browser.CoreWebView2==null)return;
  // Reloading the list lets the portal refresh its session tokens; a redirect to /auth means a new sign-in is needed.
  try{browser.CoreWebView2.Navigate(ListUrl);await Task.Delay(6000);Detect();if(Authenticated)await Heartbeat(true,"Sessione attiva");}catch{}
 }
 async Task Heartbeat(bool connected,string message,int total=0)
 {
  try{await target.Apply("dtrstatus","DTR","Stato DTR-Service",[connected?"1":"0",message,total.ToString()],[],DateTimeOffset.Now.ToString("O"));await changed();}catch{}
 }
 void SetStatus(string text){status.Text=text;Progress=text;ProgressChanged?.Invoke(text);}

 sealed record Page(List<List<string>> Rows,bool Next,int Total,int Page_,bool Login,bool Columns);
 const string ReadScript="""
 (()=>{const t=document.querySelector('table');const login=location.pathname.startsWith('/auth');if(!t)return JSON.stringify({Rows:[],Next:false,Total:0,Page_:0,Login:login,Columns:false});
  const head=[...t.querySelectorAll('thead th')].map(x=>x.innerText.trim().toUpperCase());
  const cols=['MATRICOLA','TAGS','CLIENTE','FIRMWARE','GE','ULTIMA COMUNICAZIONE'].every(h=>head.includes(h));
  const ix=h=>head.indexOf(h);const txt=c=>(c?.innerText||'').trim();
  const rows=[...t.querySelectorAll('tbody tr')].filter(r=>r.cells.length>=head.length&&head.length>0).map(r=>{const c=r.cells;
   const s=c[ix('MATRICOLA')];const serial=(s.querySelector('h6')?.innerText||s.innerText).trim();
   const tags=[...c[ix('TAGS')].querySelectorAll('span.label')].map(x=>x.innerText.trim()).filter(Boolean).join(' ');
   const lines=txt(c[ix('CLIENTE')]).split('\n').map(x=>x.trim()).filter(Boolean);
   const ge=txt(c[ix('GE')]),z=txt(c[ix('ULTIMA COMUNICAZIONE')]);
   return [serial,tags,lines[0]||'',(lines[1]||'').replace(/^\(|\)$/g,''),txt(c[ix('FIRMWARE')]),
    (ge.match(/GE\s*(\d+)/)||[])[1]||'',(ge.match(/(\d{2}\/\d{2}\/\d{4})/)||[])[1]||'',
    (z.match(/Z\s*(\d+)/)||[])[1]||'',(z.match(/(\d{2}\/\d{2}\/\d{4} \d{2}:\d{2})/)||[])[1]||''];});
  const next=[...document.querySelectorAll('button')].find(b=>b.querySelector('i.ki-bold-arrow-next'));
  const tot=(document.body.innerText.match(/Totale:\s*([\d.]+)/)||[])[1];const pg=(document.body.innerText.match(/Pagina\s*(\d+)/)||[])[1];
  return JSON.stringify({Rows:rows,Next:!!next&&!next.disabled,Total:tot?+tot.replace(/\./g,''):0,Page_:pg?+pg:0,Login:login,Columns:cols});})()
 """;
 async Task<Page> Read(CancellationToken ct)
 {
  var raw=await browser.ExecuteScriptAsync(ReadScript);ct.ThrowIfCancellationRequested();
  var json=JsonSerializer.Deserialize<string>(raw)??"{}";
  return JsonSerializer.Deserialize<Page>(json)??new Page([],false,0,0,false,false);
 }
 /// Waits until the list shows data for the expected page number (the portal loads asynchronously).
 async Task<Page> WaitPage(int page,string previous,CancellationToken ct)
 {
  for(int i=0;i<75;i++)
  {
   var p=await Read(ct);
   if(p.Login){Authenticated=false;Detect();throw new InvalidOperationException("Sessione DTR-Service scaduta: accedi di nuovo.");}
   if(p.Rows.Count>0&&p.Page_==page&&JsonSerializer.Serialize(p.Rows)!=previous)
   {if(!p.Columns)throw new InvalidDataException("Le colonne dell'elenco DTR sono cambiate: dati precedenti conservati.");return p;}
   await Task.Delay(400,ct);
  }
  throw new TimeoutException("DTR-Service non ha caricato la pagina "+page+" dell'elenco.");
 }
 const int DetailBudget=150;
 Dictionary<string,DateTime> ages=new(StringComparer.OrdinalIgnoreCase);
 static string AgesFile=>Path.Combine(Profile,"details-read.json");
 static Dictionary<string,DateTime> LoadAges(){try{return new(JsonSerializer.Deserialize<Dictionary<string,DateTime>>(File.ReadAllText(AgesFile))??[],StringComparer.OrdinalIgnoreCase);}catch{return new(StringComparer.OrdinalIgnoreCase);}}
 void SaveAges(){try{File.WriteAllText(AgesFile,JsonSerializer.Serialize(ages));}catch{}}
 /// Parses the open detail dialog; null while it is still loading. Reads the "Estensioni GE" tab too.
 const string DetailScript="""
 ((serial)=>{const d=document.querySelector('[role=dialog]');if(!d)return null;const L=d.innerText.split(String.fromCharCode(10)).map(s=>s.trim()).filter(Boolean);
  if(!L.includes(serial)||!L.includes('Ultimi interventi')||!L.includes('Scadenza cert.'))return null;
  const val=l=>{const i=L.indexOf(l);return i>=0?(L[i+1]||''):'';};const ci=L.indexOf('Scadenza cert.');
  const cert=/^\d{2}\/\d{2}\/\d{4}$/.test(L[ci+1]||'')?L[ci+1]:'';const li=L.indexOf('LICENZE',ci);const blk=L.slice(ci+(cert?2:1),li>ci?li:ci+7).filter(x=>!/^Attenzione/i.test(x));const warn=L.filter(x=>/^Attenzione/i.test(x)).join(' ');
  const vi=blk.findIndex(x=>/^(IT)?\d{11}$|^[A-Z0-9]{16}$/i.test(x));const vat=vi>=0?blk[vi]:'';const hi=vi>=0?vi-1:blk.length-1;const holder=hi>=0?blk[hi]:'';const address=blk.slice(0,Math.max(0,hi)).join(' ').replace(/^[,\s]+/,'').trim();
  const ev=(a,b)=>{const i=L.indexOf(a),j=b?L.indexOf(b,i+1):-1;if(i<0)return[];const seg=L.slice(i+1,j>i?j:undefined);const out=[];
   for(let k=0;k<seg.length;k++){if(/^\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}(:\d{2})?$/.test(seg[k])){out.push({At:seg[k],Text:seg[k+1]||''});k++;}}return out;};
  const lic=[...d.querySelectorAll('table tbody tr')].map(tr=>[...tr.cells].map(c=>c.innerText.trim())).filter(c=>c.length>=6&&c.some(Boolean)).map(c=>{const [f,t]=(c[1]||'').split(' - ');return{Client:c[0],From:f||'',To:t||'',Status:c[2],Name:c[3],Type:c[4],AutoRenew:c[5]};});
  const ge=val('GE');
  return JSON.stringify({Serial:serial,Holder:holder,Vat:vat,CertDue:cert,LastVp:val('Ultima VP'),GeInit:(ge.match(/iniz\.\s*(\d{2}\/\d{2}\/\d{4})/)||[])[1]||'',
   Licenses:lic,Errors:ev('Ultimi errori','Ultimi interventi'),Works:ev('Ultimi interventi','Chiudi'),Extensions:'',Warning:warn,Address:address});})
 """;
 async Task<string?> ReadDetail(string serial,CancellationToken ct)
 {
  var arg=JsonSerializer.Serialize(serial);
  var opened=await browser.ExecuteScriptAsync("(()=>{const r=[...document.querySelectorAll('tbody tr')].find(r=>(r.querySelector('h6')?.innerText||'').trim()==="+arg+");const i=r?.querySelector('.fa-info-circle');if(i){i.click();return true;}return false;})()");
  if(opened!="true")return null;
  string? json=null;
  // the licence table appears under its tab
  await Task.Delay(600,ct);
  await browser.ExecuteScriptAsync("(()=>{const b=[...document.querySelectorAll('[role=dialog] button,[role=dialog] [role=tab]')].find(x=>x.innerText.trim()==='LICENZE');b?.click();})()");
  // sections load one after another: accept the content once two reads 700 ms apart are identical
  string? prev=null;
  for(int i=0;i<30&&json==null;i++)
  {
   await Task.Delay(i==0?1200:700,ct);
   var cur=JsonSerializer.Deserialize<string?>(await browser.ExecuteScriptAsync("("+DetailScript+")("+arg+")"));
   if(cur!=null&&cur==prev)json=cur;prev=cur;
  }
  if(json==null){await CloseDetail(ct);throw new TimeoutException("scheda non caricata");}
  // "Estensioni GE" tab: requests for electronic journal extensions (text as shown)
  var ext="";
  if(await browser.ExecuteScriptAsync("(()=>{const b=[...document.querySelectorAll('[role=dialog] button,[role=dialog] [role=tab]')].find(x=>x.innerText.trim()==='ESTENSIONI GE');if(b){b.click();return true;}return false;})()")=="true")
  {
   await Task.Delay(900,ct);
   ext=JsonSerializer.Deserialize<string>(await browser.ExecuteScriptAsync("(()=>{const t=document.querySelector('[role=dialog]')?.innerText||'';const i=t.indexOf('ESTENSIONI GE'),j=t.indexOf('Ultimi errori');return i>=0&&j>i?t.slice(i+13,j).replace(/\\s+/g,' ').trim():'';})()"))??"";
  }
  await CloseDetail(ct);
  var d=JsonSerializer.Deserialize<Dictionary<string,JsonElement>>(json)!;var m=d.ToDictionary(k=>k.Key,v=>(object)v.Value);m["Extensions"]=ext.StartsWith("Non sono state trovate",StringComparison.OrdinalIgnoreCase)?"":ext;
  return JsonSerializer.Serialize(m);
 }
 async Task CloseDetail(CancellationToken ct)
 {
  for(int i=0;i<20;i++)
  {
   var open=await browser.ExecuteScriptAsync("(()=>{const d=document.querySelector('[role=dialog]');if(!d)return false;[...d.querySelectorAll('button')].find(b=>b.innerText.trim()==='Chiudi')?.click();return true;})()");
   if(open=="false")return;await Task.Delay(250,ct);
  }
 }
 public async Task StartSync()
 {
  if(running)return;
  if(browser.CoreWebView2==null){SetStatus("Il browser si sta avviando…");return;}
  if(!Authenticated){SetStatus("Accedi a DTR-Service, poi l'aggiornamento parte da solo.");SignInRequired?.Invoke();return;}
  running=true;cancel=new();var ct=cancel.Token;SetStatus("Lettura dei registratori telematici…");
  try
  {
   var done=new TaskCompletionSource<bool>();void Nav(object? s,CoreWebView2NavigationCompletedEventArgs e)=>done.TrySetResult(e.IsSuccess);
   browser.CoreWebView2.NavigationCompleted+=Nav;try{browser.CoreWebView2.Navigate(ListUrl);await done.Task.WaitAsync(TimeSpan.FromSeconds(40),ct);}finally{browser.CoreWebView2.NavigationCompleted-=Nav;}
   var details=new List<List<string>>();ages=LoadAges();
   var all=new List<List<string>>();var seen=new HashSet<string>(StringComparer.OrdinalIgnoreCase);string last="";int total=0;
   for(int page=1;page<=1000;page++)
   {
    var p=await WaitPage(page,last,ct);last=JsonSerializer.Serialize(p.Rows);if(p.Total>0)total=p.Total;
    foreach(var r in p.Rows)if(r.Count==9&&r[0].Length>0&&seen.Add(r[0]))all.Add(r);
    SetStatus($"Lettura registratori: {all.Count}{(total>0?" di "+total:"")}…");
    // detail pages, oldest first, within this run's budget (the list stays on the same page after "Chiudi")
    foreach(var r in p.Rows)
    {
     if(details.Count>=DetailBudget)break;var serial=r[0];
     if(ages.TryGetValue(serial,out var at)&&DateTime.Now-at<TimeSpan.FromHours(20))continue;
     try{var d=await ReadDetail(serial,ct);if(d!=null){details.Add([serial,d]);ages[serial]=DateTime.Now;}}
     catch(OperationCanceledException){throw;}
     catch(Exception ex){await target.Log("error","DTR-Service, dettaglio "+serial+": "+ex.Message);await CloseDetail(ct);}
     SetStatus($"Lettura registratori: {all.Count}{(total>0?" di "+total:"")} · schede di dettaglio {details.Count}…");
    }
    if(!p.Next)break;
    var clicked=await browser.ExecuteScriptAsync("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.querySelector('i.ki-bold-arrow-next'));if(b&&!b.disabled){b.click();return true;}return false;})()");
    if(clicked!="true")break;
    if(page==1000)throw new InvalidDataException("Troppe pagine: nessun dato parziale applicato.");
   }
   // The portal's total is refreshed once a day: accept small differences, never a clearly partial read.
   if(total>0&&all.Count<total*0.97)throw new InvalidDataException($"Letti {all.Count} registratori su {total}: lettura incompleta, dati precedenti conservati.");
   var now=DateTimeOffset.Now.ToString("O");
   var msg=await target.Apply("dtr","DTR","Registratori DTR-Service",[..Store.DtrHeaders],all,now);
   if(details.Count>0){msg+=" · "+await target.Apply("dtrdetail","DTR","Dettagli DTR-Service",["Matricola","Dettaglio"],details,now);SaveAges();}
   await Heartbeat(true,"Aggiornato",total);lastSync=DateTime.Now.ToString("O");
   SetStatus($"{msg} · prossimo aggiornamento tra 2 ore. Puoi nascondere questa finestra: resta collegata.");
  }
  catch(OperationCanceledException){SetStatus("Aggiornamento interrotto: dati precedenti conservati.");}
  catch(Exception ex){SetStatus(ex.Message);await target.Log("error","DTR-Service: "+ex.Message);}
  finally{running=false;cancel?.Dispose();cancel=null;await changed();}
 }
}
