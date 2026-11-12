using System.Globalization;

namespace TopCash.Licenze;

/// Sales of one article to one customer in one month, with the agent of the invoices (from Mastro rig_fat/lis_fat).
public sealed record ClientSale(string Month,string Client,string Agent,string Code,string Name,string Family,decimal Qty,decimal Revenue,decimal CostedRevenue,decimal Cost,int Docs);
/// A code and its name (agents, suppliers).
public sealed record Party(string Code,string Name);
/// A purchase cost changed in Mastro's article history.
public sealed record CostChange(string Date,string Code,string Name,string Supplier,decimal Old,decimal New);

/// Agent: what the customers they follow bought in the last 12 months and the 12 before.
public sealed record AnaAgent(string Code,string Name,decimal Revenue,decimal Prev,decimal Margin,decimal MarginPct,int Clients,string TopFamily,decimal Discounts);
/// Technician: closed work in the last 12 months (interventions and periodic verifications).
public sealed record AnaTech(string Name,int Jobs,int Verifications,decimal Amount,decimal Hours,int Clients,int Open);
/// What a register yields in a year: contract fee, interventions on it, verification fee (every 2 years, counted per year).
public sealed record AnaMachine(string Id,string ClientCode,string Name,string Model,string Serial,decimal Contract,decimal Interventions,decimal Verification,decimal Total);
public sealed record AnaModel(string Model,int Machines,decimal Total,decimal Average);
/// ABC: A = the customers making 80% of the revenue, B = the next 15%, C = the rest.
public sealed record AnaAbc(string ClientCode,string Name,string Class,decimal Revenue,decimal Prev,decimal Share,int Rank);
public sealed record AnaAbcClass(string Class,int Clients,decimal Revenue,decimal Share);
/// Sold below the article's usual price (median of the last 12 months): what was given away.
public sealed record AnaDiscount(string Key,string Name,string Sub,decimal Given,decimal Revenue,int Lines);
/// Purchase cost changed: by how much, what it costs on what is sold in a year, and whether the selling price followed.
public sealed record AnaCost(string Code,string Name,string Supplier,string Date,decimal Old,decimal New,decimal Pct,decimal Qty12,decimal Price,decimal Impact,decimal MarginBefore,decimal MarginNow);
/// Cash forecast by week: expected collections (weighted by how each customer pays) and payments to suppliers.
public sealed record AnaWeek(string Start,decimal In,decimal InSure,decimal Out,decimal Net,decimal Cumulative);
public sealed record Analysis(string At,List<AnaWeek> Forecast,decimal ForecastIn,decimal ForecastOut,decimal OverdueExpected,
 List<AnaAbc> Abc,List<AnaAbcClass> AbcClasses,List<AnaAgent> Agents,List<AnaTech> Techs,List<AnaMachine> Machines,List<AnaModel> Models,
 List<AnaDiscount> DiscountClients,List<AnaDiscount> DiscountArticles,decimal DiscountTotal,List<AnaCost> Costs,decimal CostImpact);

public sealed partial class Store
{
 public List<ClientSale> ClientSales()=>MastroExtra<ClientSale>("clientsales");
 public List<Party> Agents()=>MastroExtra<Party>("agents");
 public List<CostChange> CostChanges()=>MastroExtra<CostChange>("costs");

 /// The office analyses (Analisi page): computed once here from the Mastro data, shown the same everywhere.
 public Analysis Analysis(DateTime? now=null)
 {
  var today=(now??DateTime.Now).Date;
  var mv=Mastro();
  var names=mv.Clients.GroupBy(c=>c.Code).ToDictionary(g=>g.Key,g=>g.First().Name);
  string Name(string code,string fallback="")=>names.TryGetValue(code,out var n)?n:fallback.Length>0?fallback:code;
  var m12=today.AddMonths(-12).ToString("yyyy-MM");var m24=today.AddMonths(-24).ToString("yyyy-MM");
  bool Last12(string month)=>string.CompareOrdinal(month,m12)>0;
  bool Prev12(string month)=>string.CompareOrdinal(month,m24)>0&&string.CompareOrdinal(month,m12)<=0;
  var sales=ClientSales();

  // ── 1. cash forecast, next 13 weeks. Each customer is weighted by how they pay today (the same grades as Priorità):
  // nothing overdue 97% on time; A 90% about 10 days late; B 75% a month late; C 50% two months late.
  var notes=DueNotes();var dues=Dues();
  var monday=today.AddDays(-(((int)today.DayOfWeek+6)%7));
  var weeksIn=new decimal[13];var weeksSure=new decimal[13];var weeksOut=new decimal[13];
  var open=dues.Select(x=>(x,left:Math.Max(0,x.Amount-x.Paid),due:Day(x.DueDate))).Where(t=>t.left>0.005m&&t.due!=null&&!(notes.TryGetValue(t.x.Id,out var n)&&n.Status=="Incassato")).ToList();
  var grade=open.Where(t=>t.x.Party=="C").GroupBy(t=>t.x.PartyCode).ToDictionary(g=>g.Key,g=>
  {
   var late=g.Where(t=>t.due<today).ToList();if(late.Count==0)return "";
   var od=late.Sum(t=>t.left);var oldest=late.Max(t=>(int)(today-t.due!.Value).TotalDays);
   return oldest>60||od>=3000?"C":oldest>30||od>=800?"B":"A";
  });
  decimal overdueExpected=0;
  foreach(var(x,left,due) in open)
  {
   if(x.Party=="C")
   {
    var g=grade.GetValueOrDefault(x.PartyCode,"");
    var(p,delay)=g switch{"C"=>(.5m,60),"B"=>(.75m,30),"A"=>(.9m,10),_=>(.97m,3)};
    var when=due!.Value<today?today.AddDays(Math.Max(7,delay/2)):due.Value.AddDays(delay);   // already late: expected within the next weeks
    if(due.Value<today){p=Math.Min(p,g=="C"?.35m:.6m);overdueExpected+=left*p;}
    var w=(int)((when-monday).TotalDays/7);if(w<0||w>12)continue;
    weeksIn[w]+=left*p;if(g is ""&&due.Value>=today)weeksSure[w]+=left*p;   // "sure": customers with nothing overdue
   }
   else if(x.Party=="F")
   {
    var w=(int)((due!.Value-monday).TotalDays/7);if(w>12)continue;if(w<0)w=0;   // overdue supplier invoices: to pay now
    weeksOut[w]+=left;
   }
  }
  var forecast=new List<AnaWeek>();decimal cum=0;
  for(int i=0;i<13;i++){var net=weeksIn[i]-weeksOut[i];cum+=net;forecast.Add(new(monday.AddDays(7*i).ToString("yyyy-MM-dd"),Math.Round(weeksIn[i]),Math.Round(weeksSure[i]),Math.Round(weeksOut[i]),Math.Round(net),Math.Round(cum)));}

  // ── 2. ABC on the invoiced revenue of the last 12 months (credit notes subtract)
  var invoices=Invoices();
  decimal Net(Invoice i)=>(i.Kind=="Nota di credito"?-1:1)*Math.Abs(i.Taxable);
  var y1=today.AddMonths(-12);var y2=today.AddMonths(-24);
  var byClient=invoices.GroupBy(i=>i.ClientCode).Select(g=>
  {
   decimal last=0,prev=0;foreach(var i in g){var d=Day(i.Date);if(d==null)continue;if(d>y1&&d<=today)last+=Net(i);else if(d>y2&&d<=y1)prev+=Net(i);}
   return(code:g.Key,name:Name(g.Key,g.First().ClientName),last,prev);
  }).Where(x=>x.last>0).OrderByDescending(x=>x.last).ToList();
  var total=byClient.Sum(x=>x.last);decimal run=0;var abc=new List<AnaAbc>();
  for(int i=0;i<byClient.Count;i++)
  {
   var x=byClient[i];var before=run;run+=x.last;
   var cls=total<=0?"C":before<total*.8m?"A":before<total*.95m?"B":"C";
   abc.Add(new(x.code,x.name,cls,Math.Round(x.last,2),Math.Round(x.prev,2),total>0?Math.Round(x.last/total*100,2):0,i+1));
  }
  var classes=new[]{"A","B","C"}.Select(c=>{var l=abc.Where(a=>a.Class==c).ToList();var r=l.Sum(a=>a.Revenue);return new AnaAbcClass(c,l.Count,r,total>0?Math.Round(r/total*100,1):0);}).ToList();

  // ── 3. real discounts: a sale below the usual price of that article (median unit price of the last 12 months).
  // Mastro's discount fields are not used by the office, so this is the only reliable measure.
  var s12=sales.Where(s=>Last12(s.Month)&&s.Code.Length>0&&s.Qty>0&&s.Revenue>0&&s.Family!="Note di credito").ToList();
  // contracts, rentals, services and generic codes have a different price by nature: only articles sold at a steady price
  // (at least 60% of the sales within 5% of the median) say something when they are sold for less
  string[] priced=["Registratori di cassa","Stampanti","PC e palmari","Bilance","Cassetti automatici","Rotoli e consumabili","DGFE e chip","Software e licenze"];
  var usual=new Dictionary<string,decimal>();
  var service=new System.Text.RegularExpressions.Regex(@"CONTRATT|ASSISTENZA|CANONE|ABBONAM|NOLEGG|SERVIZ|MANODOPERA|INTERVENT|TRASFERT|VERIFIC|PROGRAMMAZ|INSTALLAZ|CONSULEN|SPESE|ACCONTO",System.Text.RegularExpressions.RegexOptions.IgnoreCase);
  var eligible=s12.Where(s=>priced.Contains(s.Family)&&!service.IsMatch(s.Name)).ToList();
  foreach(var g in eligible.GroupBy(s=>s.Code))
  {
   if(g.Count()<5)continue;
   var prices=g.SelectMany(s=>Enumerable.Repeat(s.Revenue/s.Qty,(int)Math.Min(50,Math.Max(1,Math.Round(s.Qty))))).OrderBy(p=>p).ToList();
   var med=prices[prices.Count/2];if(med<=0)continue;
   if(prices.Count(p=>Math.Abs(p-med)<=med*.05m)>=prices.Count*.6m)usual[g.Key]=med;
  }
  var given=new List<(ClientSale s,decimal g)>();
  foreach(var s in eligible)if(usual.TryGetValue(s.Code,out var u)&&u>0&&s.Revenue/s.Qty<u*.9m)given.Add((s,s.Qty*u-s.Revenue));
  var agentNames=Agents().GroupBy(a=>a.Code).ToDictionary(g=>g.Key,g=>g.First().Name);
  string AgentName(string c)=>c.Length==0||c=="0"?"Senza agente":agentNames.GetValueOrDefault(c,"Agente "+c);
  var discClients=given.GroupBy(x=>x.s.Client).Select(g=>new AnaDiscount(g.Key,Name(g.Key),AgentName(g.First().s.Agent),Math.Round(g.Sum(x=>x.g)),Math.Round(g.Sum(x=>x.s.Revenue)),g.Count()))
   .OrderByDescending(d=>d.Given).Take(60).ToList();
  var discArticles=given.GroupBy(x=>x.s.Code).Select(g=>new AnaDiscount(g.Key,g.First().s.Name,$"prezzo abituale {usual[g.Key]:0.00} €",Math.Round(g.Sum(x=>x.g)),Math.Round(g.Sum(x=>x.s.Revenue)),g.Count()))
   .OrderByDescending(d=>d.Given).Take(60).ToList();
  var discByAgent=given.GroupBy(x=>x.s.Agent).ToDictionary(g=>g.Key,g=>g.Sum(x=>x.g));

  // ── 4. agents (from the invoices they are on)
  var agents=sales.Where(s=>Last12(s.Month)||Prev12(s.Month)).GroupBy(s=>s.Agent).Select(g=>
  {
   var l=g.Where(s=>Last12(s.Month)).ToList();var rev=l.Sum(s=>s.Revenue);var cr=l.Sum(s=>s.CostedRevenue);var margin=cr-l.Sum(s=>s.Cost);
   var top=l.GroupBy(s=>s.Family).OrderByDescending(f=>f.Sum(s=>s.Revenue)).FirstOrDefault()?.Key??"";
   return new AnaAgent(g.Key,AgentName(g.Key),Math.Round(rev),Math.Round(g.Where(s=>Prev12(s.Month)).Sum(s=>s.Revenue)),Math.Round(margin),cr>0?Math.Round(margin/cr*100,1):0,
    l.Select(s=>s.Client).Distinct().Count(),top,Math.Round(discByAgent.GetValueOrDefault(g.Key)));
  }).Where(a=>a.Revenue>0||a.Prev>0).OrderByDescending(a=>a.Revenue).ToList();

  // ── 5. technicians: closed work of the last 12 months (Mastro history + app), periodic verifications counted apart
  var techs=mv.Interventions.Concat(Interventions().Where(i=>i.Source=="app")).DistinctBy(i=>i.Id).Where(i=>i.Technician.Length>0)
   .GroupBy(i=>i.Technician.Trim(),StringComparer.OrdinalIgnoreCase).Select(g=>
   {
    var done=g.Where(i=>i.Status=="Chiuso"&&Day(i.Date) is DateTime d&&d>y1&&d<=today).ToList();
    return new AnaTech(g.First().Technician.Trim(),done.Count(i=>i.Kind!="Verifica"),done.Count(i=>i.Kind=="Verifica"),Math.Round(done.Sum(i=>Dec(i.Total))),
     Math.Round(done.Sum(i=>Dec(i.Hours)),1),done.Select(i=>i.ClientCode).Distinct().Count(),g.Count(i=>i.Status!="Chiuso"));
   }).Where(t=>t.Jobs+t.Verifications+t.Open>0).OrderByDescending(t=>t.Amount).ThenByDescending(t=>t.Jobs).ToList();

  // ── 6. what each register yields in a year: contract (fee over its months), interventions on it, verification every 2 years
  var verFee=RtInfos().ToDictionary(r=>r.MachineId,r=>r.Fields.TryGetValue("Importo verificazione",out var v)?Dec(v):0);
  var ivByMachine=mv.Interventions.Where(i=>i.Machine.Length>0&&i.Status=="Chiuso"&&Day(i.Date) is DateTime d&&d>y1&&d<=today)
   .GroupBy(i=>i.Machine.StartsWith('m')?i.Machine:"m"+i.Machine).ToDictionary(g=>g.Key,g=>g.Sum(i=>Dec(i.Total)));
  var machines=mv.Machines.Select(m=>
  {
   var months=Dec(m.Months);var fee=Dec(m.Amount);var contract=m.Subscribed&&fee>0?(months>0?fee*12/months:fee):0;
   var ver=verFee.GetValueOrDefault(m.Id)/2;var iv=ivByMachine.GetValueOrDefault(m.Id);
   return new AnaMachine(m.Id,m.ClientCode,Name(m.ClientCode),m.Model,m.Serial,Math.Round(contract),Math.Round(iv),Math.Round(ver),Math.Round(contract+iv+ver));
  }).ToList();
  var models=machines.GroupBy(m=>string.IsNullOrWhiteSpace(m.Model)?"Senza modello":m.Model.Trim().ToUpperInvariant()).Select(g=>new AnaModel(g.First().Model.Trim().Length>0?g.First().Model.Trim():"Senza modello",g.Count(),g.Sum(m=>m.Total),Math.Round(g.Average(m=>m.Total))))
   .Where(m=>m.Total>0).OrderByDescending(m=>m.Total).Take(40).ToList();
  var topMachines=machines.Where(m=>m.Total>0).OrderByDescending(m=>m.Total).Take(200).ToList();

  // ── 7. supplier costs: the latest change of each article in the last 12 months, against what was sold
  var sold=s12.GroupBy(s=>s.Code).ToDictionary(g=>g.Key,g=>(qty:g.Sum(s=>s.Qty),rev:g.Sum(s=>s.Revenue)));
  // a cost multiplied or divided by more than 2.5 is a correction of a wrong figure, not a supplier's change
  var costs=CostChanges().Where(c=>Day(c.Date) is DateTime d&&d>y1&&c.New<=c.Old*2.5m&&c.New>=c.Old/2.5m).GroupBy(c=>c.Code).Select(g=>
  {
   var last=g.OrderBy(c=>c.Date).Last();var first=g.OrderBy(c=>c.Date).First();
   var(qty,rev)=sold.GetValueOrDefault(g.Key);var price=qty>0?rev/qty:0;
   var pct=first.Old>0?(last.New-first.Old)/first.Old*100:0;
   return new AnaCost(g.Key,last.Name,last.Supplier,last.Date.Length>=10?last.Date[..10]:last.Date,first.Old,last.New,Math.Round(pct,1),Math.Round(qty,2),Math.Round(price,2),
    Math.Round(qty*(last.New-first.Old)),price>0?Math.Round((price-first.Old)/price*100,1):0,price>0?Math.Round((price-last.New)/price*100,1):0);
  }).Where(c=>c.Pct!=0).OrderByDescending(c=>c.Impact).ThenByDescending(c=>c.Pct).Take(150).ToList();

  return new(DateTimeOffset.Now.ToString("O"),forecast,forecast.Sum(w=>w.In),forecast.Sum(w=>w.Out),Math.Round(overdueExpected),
   abc.GroupBy(x=>x.Class).SelectMany(g=>g.Take(300)).ToList(),classes,agents,techs,topMachines,models,discClients,discArticles,Math.Round(given.Sum(x=>x.g)),costs,costs.Where(c=>c.Impact>0).Sum(c=>c.Impact));
 }
}
