namespace TopCash.Licenze;
/// Everything Top Cash Studio writes stays inside the installation root (e.g. D:\TopCashSQL):
/// data in .\data, browser caches and temporary files in .\.runtime. Nothing goes to C:\Users\...\AppData or %TEMP%.
public static class Paths
{
 static string? root;
 /// The folder that contains "data" (walking up from the executable), e.g. D:\TopCashSQL.
 public static string Root=>root??=FindRoot();
 static string FindRoot()
 {
  for(var dir=new DirectoryInfo(AppContext.BaseDirectory);dir!=null;dir=dir.Parent)
   if(File.Exists(Path.Combine(dir.FullName,"data","clienti-licenze.sqlite"))||File.Exists(Path.Combine(dir.FullName,"NuGet.Config")))return dir.FullName;
  return AppContext.BaseDirectory;
 }
 public static string Runtime(params string[] parts){var p=Path.Combine([Root,".runtime",..parts]);Directory.CreateDirectory(p);return p;}
 public static string Temp=>Runtime("tmp");
 public static string TempFile(string ext)=>Path.Combine(Temp,"tcs-"+Guid.NewGuid().ToString("N")+ext);
}
