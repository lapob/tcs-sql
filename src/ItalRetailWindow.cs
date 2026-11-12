using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace TopCash.Licenze;
/// Keeps an Ital Retail OnePlus (dealer area) session alive and copies devices, device pages and orders (read-only).
/// The user signs in personally in this window (ZIdentity, with its own anti-robot check): Top Cash never sees or stores
/// the password. The session lives in a dedicated persistent browser profile and is resumed at every start of Studio.
/// The window only opens list and device pages by address and reads their text: it never clicks anything on the portal.
public sealed class ItalRetailWindow : Window
{
 const string Host="oneplus.italretail.it";
 public const string Origin="https://"+Host;
 const string Dealer=Origin+"/it/dealer/";
 public static string Profile=>Paths.Runtime("webview","ItalRetail");
 /// Studio starts the session by itself at launch once Ital Retail has been connected on this PC.
 public static bool Configured=>File.Exists(Path.Combine(Profile,"connected.flag"));
 readonly WebView2 browser=new();
 readonly TextBlock status=new(){Text="Accedi a Ital Retail e spunta «Ricordati di me»: la sessione resterà attiva su questo PC.",VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(16,0,12,0),TextTrimming=TextTrimming.CharacterEllipsis};
 readonly DispatcherTimer keepAlive=new(){Interval=TimeSpan.FromMinutes(15)},schedule=new(){Interval=TimeSpan.FromHours(2)};
 readonly Func<Task> changed;
 ISyncTarget target;CancellationTokenSource? cancel;bool initialized,running,quitting,hiddenStart;
 public ISyncTarget Target{get=>target;set=>target=value;}
 public bool Authenticated{get;private set;}
 /// Raised when the portal asks for a new sign-in: the host shows the window and notifies the user.
 public Action? SignInRequired{get;set;}

 public ItalRetailWindow(ISyncTarget target,Func<Task> changed)
 {
  TitleBar.Attach(this);
  this.target=target;this.changed=changed;Title="Top Cash Studio · Collegamento Ital Retail";
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
   // The sign-in runs on the identity provider's own pages: any https page may be shown, but data is read only on the portal host.
   core.NavigationStarting+=(_,ev)=>{if(!Https(ev.Uri))ev.Cancel=true;};
   core.NewWindowRequested+=(_,ev)=>{ev.Handled=true;if(Https(ev.Uri))core.Navigate(ev.Uri);};
   core.DownloadStarting+=(_,ev)=>ev.Cancel=true;
   core.SourceChanged+=(_,_)=>Detect();core.HistoryChanged+=(_,_)=>Detect();core.NavigationCompleted+=(_,_)=>Detect();
   PortalKeep.Attach(core);core.Navigate(Dealer+"dashboard");keepAlive.Start();schedule.Start();
   if(hiddenStart)Hide();
  }
  catch(Exception ex){SetStatus("Browser non disponibile: "+ex.Message);}
 }
 static bool Https(string url)=>url=="about:blank"||Uri.TryCreate(url,UriKind.Absolute,out var u)&&u.Scheme=="https";
 /// Signed in = a dealer-area page of the portal is on screen (a sign-in request moves to the identity provider's host).
 bool OnPortal=>browser.CoreWebView2?.Source is string s&&Uri.TryCreate(s,UriKind.Absolute,out var u)&&u.Host==Host&&u.AbsolutePath.Contains("/dealer",StringComparison.OrdinalIgnoreCase);
 bool Loading=>browser.CoreWebView2?.Source is null or ""or "about:blank";
 DateTime lastSync;
 void Detect()
 {
  if(browser.CoreWebView2==null||Loading)return;
  var now=OnPortal;if(now==Authenticated&&(now||warned))return;
  Authenticated=now;
  if(now)
  {
   warned=false;
   try{File.WriteAllText(Path.Combine(Profile,"connected.flag"),DateTime.Now.ToString("O"));}catch{}
   SetStatus("Collegato a Ital Retail. Aggiornamento automatico ogni 2 ore, sessione mantenuta attiva.");_=Heartbeat(true,"Collegato");
   if(!running&&DateTime.Now-lastSync>TimeSpan.FromMinutes(30))_=Dispatcher.InvokeAsync(async()=>{await Task.Delay(2500);await StartSync();});
  }
  else
  {
   warned=true;
   SetStatus("Ital Retail chiede di accedere. Inserisci le tue credenziali qui sotto e spunta «Ricordati di me».");
   // only a session that existed before counts as "expired": the very first sign-in is not an error
   if(Configured){_=Heartbeat(false,"Accesso richiesto");SignInRequired?.Invoke();}
  }
 }
 bool warned;
 async Task KeepAlive()
 {
  if(running||browser.CoreWebView2==null||!Authenticated)return;
  // Reloading a dealer page renews the session cookie; a redirect to the identity provider means a new sign-in is needed.
  try{browser.CoreWebView2.Navigate(Dealer+"dashboard");await Task.Delay(8000);Detect();if(Authenticated)await Heartbeat(true,"Sessione attiva");}catch{}
 }
 async Task Heartbeat(bool connected,string message,int total=0)
 {
  try{await target.Apply("italstatus","ITAL","Stato Ital Retail",[connected?"1":"0",message,total.ToString()],[],DateTimeOffset.Now.ToString("O"));await changed();}catch{}
 }
 void SetStatus(string text)=>status.Text=text;

 sealed record Page(List<List<string>> Rows,int Total,bool Login,bool Columns);
 /// Reads the table of a list page. __NEED__ = expected column titles (prefix match), __ID__ = true to add the device id.
 const string ListScript="""
 ((need,dev)=>{const ok=location.hostname==='oneplus.italretail.it';const t=document.querySelector('table');const tot=(document.body.innerText.match(/Totale elementi:\s*([\d.]+)/)||[])[1];
  if(!ok||!t||tot===undefined)return JSON.stringify({Rows:[],Total:-1,Login:!ok,Columns:false});
  const head=[...t.querySelectorAll('thead th')].map(x=>x.innerText.trim().split(String.fromCharCode(10))[0].trim().toUpperCase());const ix=h=>head.findIndex(x=>x.startsWith(h));
  const cols=need.every(h=>ix(h)>=0);const txt=c=>(c?.innerText||'').trim().replace(/\s+/g,' ');
  const rows=!cols?[]:[...t.querySelectorAll('tbody tr')].filter(r=>r.cells.length>=head.length&&txt(r.cells[ix(need[0])]).length>0).map(r=>{const v=need.map(h=>txt(r.cells[ix(h)]));
   if(dev){const a=r.cells[ix(need[0])].querySelector('a');v.splice(1,0,((a?.getAttribute('href')||'').match(/devices\/(\d+)/)||[])[1]||'');}return v;});
  return JSON.stringify({Rows:rows,Total:+tot.replace(/\./g,''),Login:false,Columns:cols});})
 """;
 /// Parses a device page; null while it is still loading, "LOGIN" when the portal moved to the sign-in page.
 const string DetailScript="""
 ((serial)=>{if(location.hostname!=='oneplus.italretail.it')return 'LOGIN';const T=(document.querySelector('main')||document.body).innerText;const L=T.split(String.fromCharCode(10)).map(s=>s.trim()).filter(Boolean);
  if(!L.includes('Matricola: '+serial)||!L.includes('Cronologia aggiornamenti'))return null;
  const val=l=>{const x=L.find(s=>s.startsWith(l+':'));return x?x.slice(l.length+1).trim():'';};const num=s=>{const m=String(s).match(/\d+/);return m?+m[0]:0;};
  const tab=h=>[...document.querySelectorAll('table')].find(t=>[...t.querySelectorAll('thead th')].some(x=>x.innerText.trim().startsWith(h)));
  const rows=t=>t?[...t.querySelectorAll('tbody tr')].map(r=>[...r.cells].map(c=>c.innerText.trim().replace(/\s+/g,' '))):[];const dt=/^\d{2}-\d{2}-\d{4}/;
  const free=val('Spazio libero DGFE');
  return JSON.stringify({Serial:serial,SubFrom:val('Data inizio'),SubTo:val('Data fine'),AutoRenew:T.includes('Disattiva rinnovo automatico'),Firmware:val('Versione firmware'),Closures:num(val('Chiusure N°')),DgfeNumber:num(val('DGFE Numero')),DgfeSize:val('Dimensione del DGFE'),DgfeFree:free?num(free):-1,RtState:val('Stato RT'),Activated:val('Data di attivazione'),Registered:val('Data del censimento'),CertFrom:val('Data creazione del certificato'),CertDue:val('Data scadenza del certificato'),DataAt:(T.match(/dati aggiornati il\s*(\d{2}-\d{2}-\d{4}( \d{2}:\d{2}(:\d{2})?)?)/)||[])[1]||'',Hw:val('Versione HW'),Wifi:val('WiFi firmware version'),Seasonal:val('Stagionale'),Clock:val('Impostazione orario'),
   Updates:rows(tab('Data aggiornamento')).filter(c=>c.length>=3&&dt.test(c[0])).map(c=>({At:c[0],From:c[1],To:c[2].split(' ')[0]})),
   Dgfe:rows(tab('Matricola')).filter(c=>c.length>=5&&dt.test(c[3])).map(c=>({Duration:c[1].replace(/^I\s+/,''),Number:c[2],At:c[3],Status:c[4]}))});})
 """;
 void Expired(){Authenticated=true;Detect();throw new InvalidOperationException("Sessione Ital Retail scaduta: accedi di nuovo.");}
 async Task Go(string url,CancellationToken ct)
 {
  var done=new TaskCompletionSource<bool>();void Nav(object? s,CoreWebView2NavigationCompletedEventArgs e)=>done.TrySetResult(e.IsSuccess);
  browser.CoreWebView2.NavigationCompleted+=Nav;try{browser.CoreWebView2.Navigate(url);await done.Task.WaitAsync(TimeSpan.FromSeconds(40),ct);}finally{browser.CoreWebView2.NavigationCompleted-=Nav;}
 }
 /// Opens a list page and waits for its table (the portal fills it after the page has loaded). Accepts two identical reads.
 async Task<Page> ReadList(string url,string[] need,bool devices,CancellationToken ct)
 {
  await Go(url,ct);string? prev=null;var call="("+ListScript+")("+JsonSerializer.Serialize(need)+","+(devices?"true":"false")+")";
  for(int i=0;i<60;i++)
  {
   await Task.Delay(500,ct);
   var json=JsonSerializer.Deserialize<string>(await browser.ExecuteScriptAsync(call))??"{}";
   var p=JsonSerializer.Deserialize<Page>(json)??new Page([],-1,false,false);
   if(p.Login)Expired();
   if(p.Total>=0&&json==prev&&(p.Rows.Count>0||p.Total==0||i>12))
   {if(!p.Columns)throw new InvalidDataException("Le colonne di Ital Retail sono cambiate: dati precedenti conservati.");return p;}
   prev=json;
  }
  throw new TimeoutException("Ital Retail non ha caricato l'elenco.");
 }
 async Task<string?> ReadDetail(string id,string serial,CancellationToken ct)
 {
  await Go(Dealer+"devices/"+id,ct);string? prev=null;var call="("+DetailScript+")("+JsonSerializer.Serialize(serial)+")";
  for(int i=0;i<30;i++)
  {
   await Task.Delay(i==0?900:600,ct);
   var cur=JsonSerializer.Deserialize<string?>(await browser.ExecuteScriptAsync(call));
   if(cur=="LOGIN")Expired();
   if(cur!=null&&cur==prev)return cur;prev=cur;
  }
  return null;
 }
 const int PerPage=100,DetailBudget=80;
 static string AgesFile=>Path.Combine(Profile,"details-read.json");
 static Dictionary<string,DateTime> LoadAges(){try{return new(JsonSerializer.Deserialize<Dictionary<string,DateTime>>(File.ReadAllText(AgesFile))??[],StringComparer.OrdinalIgnoreCase);}catch{return new(StringComparer.OrdinalIgnoreCase);}}
 public async Task StartSync()
 {
  if(running)return;
  if(browser.CoreWebView2==null){SetStatus("Il browser si sta avviando…");return;}
  if(!Authenticated){SetStatus("Accedi a Ital Retail, poi l'aggiornamento parte da solo.");SignInRequired?.Invoke();return;}
  running=true;cancel=new();var ct=cancel.Token;SetStatus("Lettura dei dispositivi Ital Retail…");
  try
  {
   var all=new List<List<string>>();var seen=new HashSet<string>(StringComparer.OrdinalIgnoreCase);int total=0;
   for(int page=1;page<=200;page++)
   {
    var p=await ReadList($"{Dealer}devices/?perPage={PerPage}&currentPage={page}&show_discontinued_devices=0",["MATRICOLA","TIPO DISPOSITIVO","AGGIORNAMENTO","ABBONAMENTO","CLIENTE","NOTE"],true,ct);total=p.Total;
    int added=0;foreach(var r in p.Rows)if(r.Count==7&&r[0].Length>0&&seen.Add(r[0])){all.Add(r);added++;}
    SetStatus($"Lettura dispositivi: {all.Count} di {total}…");
    if(all.Count>=total||added==0)break;
   }
   if(all.Count<total)throw new InvalidDataException($"Letti {all.Count} dispositivi su {total}: lettura incompleta, dati precedenti conservati.");
   // device pages: the ones read longest ago first, within this run's budget
   var ages=LoadAges();var details=new List<List<string>>();
   foreach(var r in all.Where(r=>r[1].Length>0).OrderBy(r=>ages.TryGetValue(r[0],out var a)?a:DateTime.MinValue).Take(DetailBudget))
   {
    try{var d=await ReadDetail(r[1],r[0],ct);if(d!=null){details.Add([r[0],d]);ages[r[0]]=DateTime.Now;}else await target.Log("error","Ital Retail, scheda "+r[0]+": non caricata.");}
    catch(OperationCanceledException){throw;}
    catch(InvalidOperationException){throw;}
    catch(Exception ex){await target.Log("error","Ital Retail, scheda "+r[0]+": "+ex.Message);}
    SetStatus($"Lettura dispositivi: {all.Count} · schede di dettaglio {details.Count}…");
   }
   // order history (first 100, newest numbers included: the list is short)
   Page? orders=null;
   try{orders=await ReadList($"{Dealer}orders?perPage={PerPage}&currentPage=1",["NUMERO","DATA","MATRICOLA","CLIENTE","TIPO DI ACQUISTO","IMPORTO"],false,ct);}
   catch(OperationCanceledException){throw;}
   catch(InvalidOperationException){throw;}
   catch(Exception ex){await target.Log("error","Ital Retail, ordini: "+ex.Message);}
   var now=DateTimeOffset.Now.ToString("O");
   var msg=await target.Apply("ital","ITAL","Dispositivi Ital Retail",[..Store.ItalHeaders],all,now);
   if(details.Count>0){msg+=" · "+await target.Apply("italdetail","ITAL","Dettagli Ital Retail",["Matricola","Dettaglio"],details,now);try{File.WriteAllText(AgesFile,JsonSerializer.Serialize(ages));}catch{}}
   if(orders!=null)msg+=" · "+await target.Apply("italorders","ITAL","Ordini Ital Retail",[..Store.ItalOrderHeaders],orders.Rows.Where(r=>r.Count==6).ToList(),now);
   await Heartbeat(true,"Aggiornato",total);lastSync=DateTime.Now;
   SetStatus($"{msg} · prossimo aggiornamento tra 2 ore. Puoi nascondere questa finestra: resta collegata.");
  }
  catch(OperationCanceledException){SetStatus("Aggiornamento interrotto: dati precedenti conservati.");}
  catch(Exception ex){SetStatus(ex.Message);await target.Log("error","Ital Retail: "+ex.Message);}
  finally
  {
   running=false;cancel?.Dispose();cancel=null;
   try{if(Authenticated)browser.CoreWebView2.Navigate(Dealer+"dashboard");}catch{}
   await changed();
  }
 }
}
