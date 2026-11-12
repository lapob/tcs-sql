using System.Globalization;

namespace TopCash.Licenze;

/// Closed intervention with an amount and no invoice: from Mastro (no document generated) or made in the app (no invoice to the customer since).
public sealed record InsUnbilled(string Id,string ClientCode,string Name,string Date,string Title,string Technician,decimal Amount,string Source);
/// E-invoice that did not reach the customer: "Non inviata", "Scartata", "Mancata consegna" (the customer must be told it is in the cassetto fiscale).
public sealed record InsSdi(string Number,string Date,string ClientCode,string Name,decimal Total,string Status);
/// Customer with overdue instalments. Grade C = over 60 days or 3.000 €, B = over 30 days or 800 €, A = slight delay.
public sealed record InsPayer(string ClientCode,string Name,string Grade,decimal Overdue,int Rates,int OldestDays,decimal Open,decimal Revenue12);
/// Customer buying much less: last 12 months against the 12 before.
public sealed record InsChurn(string ClientCode,string Name,decimal Last12,decimal Prev12,string LastInvoice,decimal Lost);
/// Something to sell: "Contratto" (expired or expiring service contract), "Verifica" (periodic verification due), "Sostituzione" (register over 6 years old).
public sealed record InsOpp(string Kind,string ClientCode,string Name,string MachineId,string What,string When,decimal Value);
public sealed record Insights(string At,List<InsUnbilled> Unbilled,List<InsSdi> Sdi,List<InsPayer> Payers,List<InsChurn> Churn,List<InsOpp> Opps,decimal RegisterPrice);

public sealed partial class Store
{
 static DateTime? Day(string? s)=>s is {Length:>=10}&&DateTime.TryParseExact(s[..10],"yyyy-MM-dd",CultureInfo.InvariantCulture,DateTimeStyles.None,out var d)?d:null;
 static decimal Dec(string? s)=>decimal.TryParse((s??"").Replace(',','.'),NumberStyles.Any,CultureInfo.InvariantCulture,out var v)?v:0;

 /// What the office should look at: computed once here, shown the same on the site, the Windows app and the phones.
 public Insights Insights(DateTime? now=null)
 {
  var today=(now??DateTime.Now).Date;
  var mv=Mastro();
  var names=mv.Clients.GroupBy(c=>c.Code).ToDictionary(g=>g.Key,g=>g.First().Name);
  string Name(string code,string fallback="")=>names.TryGetValue(code,out var n)?n:fallback.Length>0?fallback:code;
  var invoices=Invoices();
  decimal Net(Invoice i)=>(i.Kind=="Nota di credito"?-1:1)*Math.Abs(i.Taxable);

  // 1. not invoiced: Mastro's own list + app interventions closed in the last 90 days with no invoice to that customer since
  var unbilled=MastroUnbilled().Where(u=>names.ContainsKey(u.ClientCode)||mv.Clients.Count==0).Select(u=>u with{Name=Name(u.ClientCode,u.Name)}).ToList();
  var lastInv=invoices.Where(i=>i.Kind!="Nota di credito").GroupBy(i=>i.ClientCode).ToDictionary(g=>g.Key,g=>g.Max(i=>i.Date)??"");
  foreach(var i in Interventions())
  {
   if(i.Source!="app"||i.Status!="Chiuso"||!i.ClientCode.StartsWith('M'))continue;
   var d=Day(i.Date)??Day(i.Updated);if(d==null||d<today.AddDays(-90))continue;
   var code=i.ClientCode[1..];var amount=Dec(i.Total);
   if(amount<=0&&(i.Parts==null||i.Parts.Count==0)&&string.IsNullOrEmpty(i.Hours))continue;   // nothing to charge
   if(lastInv.TryGetValue(code,out var li)&&string.CompareOrdinal(li,i.Date)>=0)continue;     // invoiced since
   unbilled.Add(new(i.Id,code,Name(code,i.ClientName),i.Date,i.Title,i.Technician,amount,"app"));
  }
  unbilled=unbilled.OrderByDescending(u=>u.Date).ToList();

  // 2. e-invoices: as read from Mastro (the ones not sent for more than 2 days and the undelivered ones)
  var sdi=MastroSdi().Where(s=>s.Status!="Non inviata"||(Day(s.Date) is DateTime d&&d<=today.AddDays(-2))).Select(s=>s with{Name=Name(s.ClientCode,s.Name)}).ToList();

  // 3. who pays late: open instalments past due (an instalment marked "Incassato" in the app is closed)
  var notes=DueNotes();
  var rev12=invoices.Where(i=>Day(i.Date)>=today.AddMonths(-12)).GroupBy(i=>i.ClientCode).ToDictionary(g=>g.Key,g=>g.Sum(Net));
  var payers=Dues().Where(x=>x.Party=="C").Select(x=>(x,left:Math.Max(0,x.Amount-x.Paid),due:Day(x.DueDate)))
   .Where(t=>t.left>0.005m&&!(notes.TryGetValue(t.x.Id,out var n)&&n.Status=="Incassato"))
   .GroupBy(t=>t.x.PartyCode).Select(g=>
   {
    var late=g.Where(t=>t.due!=null&&t.due<today).ToList();if(late.Count==0)return null;
    var od=late.Sum(t=>t.left);var oldest=late.Max(t=>(int)(today-t.due!.Value).TotalDays);
    var grade=oldest>60||od>=3000?"C":oldest>30||od>=800?"B":"A";
    return new InsPayer(g.Key,Name(g.Key,g.First().x.PartyName),grade,od,late.Count,oldest,g.Sum(t=>t.left),rev12.GetValueOrDefault(g.Key));
   }).OfType<InsPayer>().OrderByDescending(p=>p.Grade).ThenByDescending(p=>p.Overdue*(1+p.OldestDays/30m)).ToList();

  // 4. customers buying much less (at least 500 € the year before, at least halved)
  var y1=today.AddMonths(-12);var y2=today.AddMonths(-24);
  var churn=invoices.GroupBy(i=>i.ClientCode).Select(g=>
  {
   decimal last=0,prev=0;string lastDate="";
   foreach(var i in g){var d=Day(i.Date);if(d==null)continue;if(d>y1&&d<=today)last+=Net(i);else if(d>y2&&d<=y1)prev+=Net(i);if(i.Kind!="Nota di credito"&&string.CompareOrdinal(i.Date,lastDate)>0)lastDate=i.Date;}
   return prev>=500&&last<=prev/2?new InsChurn(g.Key,Name(g.Key,g.First().ClientName),last,prev,lastDate,prev-last):null;
  }).OfType<InsChurn>().OrderByDescending(c=>c.Lost).Take(150).ToList();

  // 5. opportunities on the registers in service (ceased ones are already out of Mastro())
  var regs=Sales().Where(s=>s.Family=="Registratori di cassa"&&string.CompareOrdinal(s.Month,today.AddMonths(-12).ToString("yyyy-MM"))>=0).ToList();
  var qty=regs.Sum(s=>s.Qty);var regPrice=qty>0?Math.Round(regs.Sum(s=>s.Revenue)/qty):0;
  var opps=new List<InsOpp>();
  foreach(var m in mv.Machines)
  {
   var who=Name(m.ClientCode);var what=string.Join(" · ",new[]{m.Model,m.Serial.Length>0?"matr. "+m.Serial:""}.Where(x=>x.Length>0));
   if(m.Subscribed&&Day(m.ContractEnd) is DateTime end&&end>=today.AddDays(-120)&&end<=today.AddDays(60))
    opps.Add(new("Contratto",m.ClientCode,who,m.Id,what,m.ContractEnd[..10],Dec(m.Amount)));
   if(Day(m.Verification) is DateTime ver&&m.FiscalSerial.Length>0&&ver.AddYears(2)<=today.AddDays(60)&&ver.AddYears(2)>=today.AddYears(-1))
    opps.Add(new("Verifica",m.ClientCode,who,m.Id,what,ver.AddYears(2).ToString("yyyy-MM-dd"),0));
   if(m.FiscalSerial.Length>0&&(Day(m.Installed)??Day(m.FirstInstall)) is DateTime ins&&ins<=today.AddYears(-6))
    opps.Add(new("Sostituzione",m.ClientCode,who,m.Id,what,ins.ToString("yyyy-MM-dd"),regPrice));
  }
  opps=opps.OrderBy(o=>o.Kind=="Contratto"?0:o.Kind=="Verifica"?1:2).ThenBy(o=>o.Kind=="Sostituzione"?o.When:"").ThenBy(o=>o.When).ToList();
  return new(DateTimeOffset.Now.ToString("O"),unbilled,sdi,payers,churn,opps,regPrice);
 }
}
