using TopCash.Licenze;
namespace TopCash.Server;

/// Keeps the archive aligned with MastroSQL without anyone pressing "Importa": the server reads Mastro (STRICTLY read-only,
/// the same RETE.ini as the Windows app) every [Minutes] in the chosen hours (default every 30 minutes, 6 to 22; the owner changes it in Settings). An unchanged read is not imported again; a read that
/// suddenly has far fewer customers than the archive (network trouble, Mastro being restored) is refused and logged.
public static class MastroAuto
{
 static readonly SemaphoreSlim gate=new(1,1);
 static string lastError="";
 public static string LastError=>lastError;
 public static async Task Tick(Store store,Hub hub,bool force=false,string who="automatica")
 {
  var now=DateTime.Now;
  var rule=store.MastroAutoRule();
  if(!force&&(!rule.Enabled||now.Hour<rule.From||now.Hour>=rule.To))return;
  var last=store.LastMastroSync();
  if(!force&&last!=null&&DateTimeOffset.TryParse(last.At,out var at)&&DateTimeOffset.Now-at<TimeSpan.FromMinutes(rule.Minutes-2))return;
  var cfg=MastroReader.FromIni();if(cfg==null)return;   // Mastro is not on this PC: imports stay manual from the Windows app
  if(!await gate.WaitAsync(0))return;
  try
  {
   var (batch,_)=await MastroReader.Read(cfg);
   var hash=Store.MastroHash(batch);
   if(last!=null&&last.Hash==hash&&store.MastroClean()){store.SaveMastroSync(last with{At=DateTimeOffset.Now.ToString("O"),Ok=true,Who=who});hub.Changed();lastError="";return;}
   var have=store.MastroClientCount();
   if(have>100&&batch.Clients.Count<have*0.8)
   {
    var why=$"Lettura automatica Mastro scartata: {batch.Clients.Count} clienti invece di {have}. I dati restano quelli della lettura precedente.";
    if(why!=lastError){store.Log("error",why);lastError=why;}
    return;
   }
   store.ImportMastro(batch,who);hub.Changed();lastError="";
  }
  catch(Exception ex)
  {
   var why="Lettura automatica Mastro non riuscita: "+ex.GetBaseException().Message;
   if(why!=lastError){store.Log("error",why);lastError=why;}
  }
  finally{gate.Release();}
 }
}
