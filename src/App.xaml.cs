using System.Windows;
namespace TopCash.Licenze;
public partial class App : Application
{
 protected override void OnStartup(StartupEventArgs e)
 {
  base.OnStartup(e);
  if(e.Args.FirstOrDefault(a=>a.StartsWith("--selftest=")) is string test)
  {
   var folder=test[11..];try{Store.SelfTest(folder);File.WriteAllText(Path.Combine(folder,"result.txt"),"PASS: upsert, deduplicazione, validazione atomica, note, magazzino, rapportini, email, contabilità, backup interni ed esterni, DTR-Service.");Shutdown(0);}
   catch(Exception ex){Directory.CreateDirectory(folder);File.WriteAllText(Path.Combine(folder,"result.txt"),"FAIL: "+ex);Shutdown(1);}return;
  }
  // "--minimized": started with Windows on the sync station, it works in the background from the taskbar
  var w=new MainWindow();if(e.Args.Contains("--minimized"))w.WindowState=System.Windows.WindowState.Minimized;w.Show();
 }
}
