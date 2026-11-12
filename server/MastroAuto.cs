using TopCash.Licenze;
namespace TopCash.Server;

/// Keeps the archive aligned with MastroSQL without anyone pressing "Importa": the server reads Mastro (STRICTLY read-only,
/// the same RETE.ini as the Windows app) once an hour from 6 to 22. An unchanged read is not imported again; a read that
/// suddenly has far fewer customers than the archive (network trouble, Mastro being restored) is refused and logged.
public static class MastroAuto
{
 static readonly SemaphoreSlim gate=new(1,1);
 static string lastError="";
 public static async Task Tick(Store store,Hub hub,bool force=false)
 {
  var now=DateTime.Now;
  if(!force&&(now.Hour<6||now.Hour>=22))return;
  var last=store.LastMastroSync();
  if(!force&&last!=null&&DateTimeOffset.TryParse(last.At,out var at)&&DateTimeOffset.Now-at<TimeSpan.FromMinutes(55))return;
  var cfg=MastroReader.FromIni();if(cfg==null)return;   // Mastro is not on this PC: imports stay manual from the Windows app
  if(!await gate.WaitAsync(0))return;
  try
  {
   var (batch,_)=await MastroReader.Read(cfg);
   var hash=Store.MastroHash(batch);
   if(last!=null&&last.Hash==hash){store.SaveMastroSync(last with{At=DateTimeOffset.Now.ToString("O"),Ok=true});hub.Changed();lastError="";return;}
   var have=store.MastroClientCount();
   if(have>100&&batch.Clients.Count<have*0.8)
   {
    var why=$"Lettura automatica Mastro scartata: {batch.Clients.Count} clienti invece di {have}. I dati restano quelli della lettura precedente.";
    if(why!=lastError){store.Log("error",why);lastError=why;}
    return;
   }
   store.ImportMastro(batch,"automatica");hub.Changed();lastError="";
  }
  catch(Exception ex)
  {
   var why="Lettura automatica Mastro non riuscita: "+ex.GetBaseException().Message;
   if(why!=lastError){store.Log("error",why);lastError=why;}
  }
  finally{gate.Release();}
 }
}
