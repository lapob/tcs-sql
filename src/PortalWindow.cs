using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace TopCash.Licenze;
/// Hosts the MY Zucchetti Group portal. The user signs in personally (no password is stored);
/// the sync then only navigates to list pages and presses the visible "next page" control: read-only.
public sealed class PortalWindow : Window
{
 const string Base="https://www.zucchetti.it/myzucchettigroup/jsp/";
 const string Home=Base+"home.jsp";
 readonly WebView2 browser=new();readonly TextBlock status=new(){Text="Accedi a Zucchetti per collegare l'aggiornamento automatico.",VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(16,0,12,0)};
 ISyncTarget target;readonly Func<Task> changed;
 public ISyncTarget Target {get=>target;set=>target=value;}readonly DispatcherTimer schedule=new(){Interval=TimeSpan.FromHours(6)};
 /// Lightweight progress channel: only a short text goes to the dashboard, never the full archive.
 public Action<string>? ProgressChanged {get;set;}
 bool initialized,running,quitting;CancellationTokenSource? cancel;
 // a light read-only visit to the home page now and then keeps the portal session alive while the window is hidden
 readonly DispatcherTimer keepAlive=new(){Interval=TimeSpan.FromMinutes(10)};
 public bool Authenticated {get;private set;}
 public bool Running=>running;
 public string Progress {get;private set;}="";
 public PortalWindow(ISyncTarget target,Func<Task> changed)
 {
  this.target=target;this.changed=changed;Title="Top Cash Studio · Collegamento Zucchetti";try{Icon=System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/topcash.ico"));}catch{}Width=1320;Height=900;WindowStartupLocation=WindowStartupLocation.CenterOwner;
  var layout=new DockPanel();var bar=new DockPanel{Background=System.Windows.Media.Brushes.White,MinHeight=62};DockPanel.SetDock(bar,Dock.Top);layout.Children.Add(bar);
  var sync=new Button{Content="Sincronizza tutto (sola lettura)",Margin=new Thickness(10),Padding=new Thickness(14,9,14,9)};sync.Click+=async(_,_)=>await StartSync();DockPanel.SetDock(sync,Dock.Right);bar.Children.Add(sync);
  var stop=new Button{Content="Interrompi",Margin=new Thickness(6,10,0,10),Padding=new Thickness(12,8,12,8)};stop.Click+=(_,_)=>cancel?.Cancel();DockPanel.SetDock(stop,Dock.Right);bar.Children.Add(stop);
  var hide=new Button{Content="Nascondi (resta collegato)",Margin=new Thickness(6,10,0,10),Padding=new Thickness(12,8,12,8)};hide.Click+=(_,_)=>Hide();DockPanel.SetDock(hide,Dock.Right);bar.Children.Add(hide);bar.Children.Add(status);layout.Children.Add(browser);Content=layout;
  Loaded+=Initialize;schedule.Tick+=async(_,_)=>{if(Authenticated&&!running)await StartSync(false);};keepAlive.Tick+=(_,_)=>{try{if(Authenticated&&!running&&(!IsVisible||WindowState==WindowState.Minimized)&&browser.CoreWebView2!=null)browser.CoreWebView2.Navigate(Home);}catch{}};keepAlive.Start();
  // closing only hides the window: the session stays signed in until the app itself exits
  Closing+=(_,e)=>{if(!quitting){e.Cancel=true;Hide();}};Closed+=(_,_)=>{cancel?.Cancel();schedule.Stop();keepAlive.Stop();PortalKeep.Detach(browser.CoreWebView2);browser.Dispose();};
 }
 public void Quit(){quitting=true;cancel?.Cancel();keepAlive.Stop();schedule.Stop();try{Close();}catch{}}
 public void Reveal(){Show();if(WindowState==WindowState.Minimized)WindowState=WindowState.Normal;Activate();}
 async void Initialize(object? sender,RoutedEventArgs e)
 {
  try{var env=await CoreWebView2Environment.CreateAsync(null,Paths.Runtime("webview","Portal"));
   await browser.EnsureCoreWebView2Async(env);var core=browser.CoreWebView2;core.Settings.IsPasswordAutosaveEnabled=false;core.Settings.IsGeneralAutofillEnabled=false;
   core.PermissionRequested+=(_,ev)=>ev.State=CoreWebView2PermissionState.Deny;
   core.NavigationStarting+=(_,ev)=>{if(!Allowed(ev.Uri))ev.Cancel=true;};core.NewWindowRequested+=(_,ev)=>{ev.Handled=true;if(Allowed(ev.Uri))core.Navigate(ev.Uri);};
   core.NavigationCompleted+=(_,_)=>{Authenticated=Uri.TryCreate(core.Source,UriKind.Absolute,out var u)&&u.Host=="www.zucchetti.it"&&!u.AbsolutePath.Contains("login",StringComparison.OrdinalIgnoreCase);if(!running)SetStatus(Authenticated?"Sessione disponibile · aggiornamento ogni 6 ore dopo la prima sincronizzazione.":"Accedi al portale. La password non viene salvata dall'app.");};
   StateChanged+=(_,_)=>{try{core.MemoryUsageTargetLevel=WindowState==WindowState.Minimized&&!running?CoreWebView2MemoryUsageTargetLevel.Low:CoreWebView2MemoryUsageTargetLevel.Normal;}catch{}};
   PortalKeep.Attach(core);core.Navigate(Home);initialized=true;
  }catch(Exception ex){SetStatus(ex.Message);}
 }
 void SetStatus(string text){status.Text=text;Progress=text;ProgressChanged?.Invoke(text);_=target.Progress(running,Authenticated,text);}
 // The customer registry lives on the cancellation page: only that listing is reachable, never ordering/renewal/blocking flows.
 static bool Allowed(string url)=>Uri.TryCreate(url,UriKind.Absolute,out var u)&&u.Scheme=="https"&&new[]{"www.zucchetti.it","sso.zucchetti.it"}.Contains(u.Host)
  &&(u.AbsolutePath.EndsWith("/sior_wdisdette.jsp",StringComparison.OrdinalIgnoreCase)||!new[]{"ptblocco","rinnova","disdett","carrello","checkout"}.Any(s=>u.AbsolutePath.Contains(s,StringComparison.OrdinalIgnoreCase)));
 public static readonly (string Product,string Cat,string Label)[] Families=[("ILCONTO","IC","Il Conto"),("ZMENU","ZM","ZMenu"),("ILCONTOAN","ICN","Il Conto noleggio"),("POSSISTEMI","PS","Pos Sistemi"),("SELLBY","SL","Sellby"),("TILBY","TI","Tilby")];
 enum Kind{Licenses,Clients,Orders}
 public async Task StartSync(bool foreground=true)
 {
  if(!initialized){SetStatus("Il browser si sta avviando. Attendi il caricamento del portale.");return;}if(running)return;
  if(!Authenticated){SetStatus("Completa l'accesso nel portale, poi premi Sincronizza.");return;}
  var steps=new List<(Kind Kind,string Product,string Cat,bool Expired,string Scope)>();
  foreach(var f in Families){steps.Add((Kind.Licenses,f.Product,f.Cat,false,f.Product+"-attive"));steps.Add((Kind.Licenses,f.Product,f.Cat,true,f.Product+"-scadute"));}
  foreach(var f in Families)steps.Add((Kind.Clients,f.Product,f.Cat,false,"Anagrafica "+f.Label));
  foreach(var f in Families)steps.Add((Kind.Orders,f.Product,f.Cat,false,"Ordini "+f.Label));
  running=true;cancel=new();int ok=0,licenses=0,clients=0,orders=0;var token=cancel.Token;SetStatus("Avvio sincronizzazione…");
  try
  {
   foreach(var step in steps)
   {
    token.ThrowIfCancellationRequested();SetStatus($"[{ok+1}/{steps.Count}] Lettura {step.Scope}…");
    try
    {
     var now=DateTimeOffset.Now.ToString("O");
     if(step.Kind==Kind.Licenses)
     {
      var page=step.Expired?"sior_wlar_storico_chiavicatlisParScad.jsp":"sior_wlar_storico_chiavicatlisPartner.jsp";
      var (headers,rows)=await ReadList(Base+page+"?w_MOCODPROD="+step.Product+"&m_cWindowName=main&m_cWindowName=main","_Grid5",true,step.Scope,token);
      await target.Apply("licenses",step.Product,step.Scope,headers,rows,now);licenses+=rows.Count;
     }
     else if(step.Kind==Kind.Clients)
     {
      var (headers,rows)=await ReadList(Base+"sior_wdisdette.jsp?w_LISTCATID="+step.Cat+"&w_LISTCATTYPE=ART&m_cWindowName=main","_Grid",false,step.Scope,token);
      await target.Apply("clients",step.Product,step.Scope,headers,rows,now);clients+=rows.Count;
     }
     else
     {
      var (headers,rows)=await ReadList(Base+"sior_view_orders_portlet.jsp?listCatType=ART&listCatId="+step.Cat,"_Grid0",false,step.Scope,token);
      await target.Apply("orders",step.Product,step.Scope,headers,rows,now);orders+=rows.Count;
     }
     ok++;await changed(); // host throttles full refreshes during the sync
    }
    catch(OperationCanceledException){throw;}
    catch(Exception ex){await target.Log("error",step.Scope+": "+ex.Message);if(!Authenticated)throw new InvalidOperationException("Sessione scaduta. Accedi nuovamente al portale.");}
   }
   await target.Log("portal",$"Sincronizzazione conclusa: {ok}/{steps.Count} elenchi · {licenses} licenze · {clients} schede anagrafiche · {orders} ordini. Nessun ordine, rinnovo, blocco o disdetta modificato.");
   SetStatus($"Letti {ok}/{steps.Count} elenchi · {licenses} licenze · {clients} anagrafiche · {orders} ordini · prossimo controllo tra 6 ore.");
   schedule.Start();
  }
  catch(OperationCanceledException){SetStatus("Sincronizzazione interrotta. Conservati gli elenchi già completati.");}
  catch(Exception ex){SetStatus(ex.Message);await target.Log("error",ex.Message);}
  finally{running=false;cancel?.Dispose();cancel=null;_=target.Progress(false,Authenticated,Progress);await changed();}
 }
 /// Reads every page of a portal grid. Throws (and nothing is saved) unless the last page is reached.
 async Task<(List<string> Headers,List<List<string>> Rows)> ReadList(string url,string grid,bool sortBySerial,string label,CancellationToken token)
 {
  await Navigate(url,token);
  var initial=await WaitGrid(grid,"",token);
  if(initial.NoData)return(initial.Headers,[]);
  if(sortBySerial&&initial.Rows.Count>0)
  {
   // A unique serial order prevents overlapping pages when many licenses share an expiry date.
   await browser.ExecuteScriptAsync(Js("""(()=>{const t=__GRID__;const h=[...t.querySelectorAll('[id$="_title_cell"]')].find(h=>h.textContent.trim().toUpperCase()==='MATRICOLA');if(h)h.click();})()""",grid));
   bool sorted=false;for(int retry=0;retry<10;retry++){var done=await browser.ExecuteScriptAsync("""(()=>{const li=[...document.querySelectorAll('li.grid_orderby_li.asc')].find(li=>li.textContent.trim()==='Crescente');if(li){li.click();return true;}return false;})()""");if(done=="true"){sorted=true;break;}await Task.Delay(200,token);}
   if(!sorted)throw new InvalidDataException("Impossibile impostare un ordinamento stabile per matricola.");await Task.Delay(700,token);
  }
  var all=new List<List<string>>();List<string>? headers=null;string last="";
  for(int p=0;p<1000;p++)
  {
   var snap=await WaitGrid(grid,last,token);headers??=snap.Headers;
   if(snap.NoData)return(headers,all);
   if(snap.Rows.Count==0)throw new InvalidDataException("Griglia senza dati e senza conferma di elenco vuoto.");
   var signature=JsonSerializer.Serialize(snap.Rows);if(signature==last)throw new InvalidDataException("La pagina non è avanzata. Dati precedenti conservati.");last=signature;all.AddRange(snap.Rows);
   SetStatus($"Lettura {label}: {all.Count} righe…");
   if(!snap.Next)return(headers,all);
   // The only automated portal action is the visible "Pagina successiva" control.
   var clicked=await browser.ExecuteScriptAsync(Js("""(()=>{const t=__GRID__;const a=t?.querySelector('a[title="Pagina successiva"]:not([class*="_dis"])');if(a){a.click();return true;}return false;})()""",grid));
   if(clicked!="true")throw new InvalidDataException("Paginazione non disponibile.");
  }
  throw new InvalidDataException("Limite di pagine raggiunto: nessun dato parziale applicato.");
 }
 static string Js(string script,string grid)=>script.Replace("__GRID__","[...document.querySelectorAll('table[id]')].find(t=>t.id.endsWith('"+grid+"'))");
 async Task Navigate(string url,CancellationToken ct)
 {
  var completion=new TaskCompletionSource<bool>();void Done(object? s,CoreWebView2NavigationCompletedEventArgs e){completion.TrySetResult(e.IsSuccess);}
  browser.NavigationCompleted+=Done;try{browser.Source=new Uri(url);if(!await completion.Task.WaitAsync(TimeSpan.FromSeconds(30),ct))throw new IOException("Pagina Zucchetti non disponibile.");}finally{browser.NavigationCompleted-=Done;}
  if(browser.Source.Host!="www.zucchetti.it"){Authenticated=false;throw new InvalidOperationException("Accesso Zucchetti richiesto.");}
 }
 sealed record GridSnapshot(List<string> Headers,List<List<string>> Rows,bool Next,bool NoData);
 async Task<GridSnapshot> WaitGrid(string grid,string previous,CancellationToken ct)
 {
  var script=Js("""
   (()=>{const t=__GRID__;if(!t)return null;const h=t.querySelector('tr[id$="_titles_row"]');if(!h)return null;const clean=c=>(c.innerText||'').replace(/[-]/g,'').replace(/\s+/g,' ').trim();return {Headers:[...h.children].map(c=>(c.querySelector('[id$="_title_cell"]')?.textContent||c.innerText||'').replace(/[-]/g,'').replace(/\s+/g,' ').trim()),Rows:[...t.querySelectorAll('tr')].filter(r=>/_row\d+$/.test(r.id)).map(r=>[...r.children].map(c=>clean(c)||c.querySelector('img')?.getAttribute('alt')||'')),Next:!!t.querySelector('a[title="Pagina successiva"]:not([class*="_dis"])'),NoData:/Nessun dato/i.test(t.innerText)};})()
   """,grid);
  for(int attempt=0;attempt<50;attempt++)
  {
   ct.ThrowIfCancellationRequested();var raw=await browser.ExecuteScriptAsync(script);
   if(raw!="null"){var g=JsonSerializer.Deserialize<GridSnapshot>(raw);if(g!=null&&(g.NoData||g.Rows.Any(r=>r.Any(s=>!string.IsNullOrWhiteSpace(s)))&&JsonSerializer.Serialize(g.Rows)!=previous))return g;}
   await Task.Delay(400,ct);
  }
  throw new TimeoutException("Il portale non ha caricato la griglia o la pagina successiva.");
 }
}
