using System.Windows;
namespace TopCash.Licenze;
public partial class App : Application
{
 protected override void OnStartup(StartupEventArgs e)
 {
  base.OnStartup(e);
  if(e.Args.FirstOrDefault(a=>a.StartsWith("--selftest=")) is string test)
  {
   var folder=test[11..];try{Store.SelfTest(folder);File.WriteAllText(Path.Combine(folder,"result.txt"),"PASS: upsert, deduplicazione, validazione atomica, note, magazzino, rapportini, email, contabilità, backup interni ed esterni, DTR-Service, Ital Retail, RCH XDealer, modifiche amministratore, inventario e rinnovi.");Shutdown(0);}
   catch(Exception ex){Directory.CreateDirectory(folder);File.WriteAllText(Path.Combine(folder,"result.txt"),"FAIL: "+ex);Shutdown(1);}return;
  }
  // "--aggiorna=http://server:porta/": checks the office server, downloads the new version and installs it, without opening the window
  if(e.Args.FirstOrDefault(a=>a.StartsWith("--aggiorna="))is string up)
  {
   var log=Path.Combine(Paths.Runtime("update"),"aggiorna.txt");
   try{var v=Task.Run(async()=>{var o=await Updater.Find(up[11..],"");return o==null?null:await Updater.Download(o);}).GetAwaiter().GetResult();var ok=v!=null&&Updater.Apply(false,false);File.WriteAllText(log,ok?$"INSTALLO {v}":$"AGGIORNATO {Updater.CurrentText}");Shutdown(0);}
   catch(Exception ex){File.WriteAllText(log,"ERRORE "+ex.Message);Shutdown(1);}
   return;
  }
  // "--minimized": started with Windows on the sync station, it works in the background from the taskbar
  var w=new MainWindow();if(e.Args.Contains("--minimized"))w.WindowState=System.Windows.WindowState.Minimized;w.Show();
 }
}
