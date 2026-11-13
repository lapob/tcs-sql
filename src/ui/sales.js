'use strict';
/* Top Cash Studio — Vendite. Loaded after accounting.js.
   What was sold (invoice lines by article and month), what was bought (purchase invoices by counter-account and supplier),
   the margin and what was lost; all read from MastroSQL, aggregated by month. */

// product families first (pieces count), then services; colours and icons stay the same everywhere
const FAM=[
 ['Registratori di cassa','register',212,true],['Cassetti automatici','euro',152,true],['PC e palmari','monitor',262,true],['DGFE e chip','file',32,true],
 ['Stampanti','print',292,true],['Bilance','grid',12,true],['Rotoli e consumabili','repeat',190,true],
 ['Contratti di assistenza','shield',232,false],['Noleggi','clock',330,false],['Software e licenze','cloud',200,false],['Servizi tecnici','tool',24,false],['Altro','sparkle',0,false]];
const FAM_BY=new Map(FAM.map(([n,ic,h,p],i)=>[n,{n,ic,h,p,i}]));
const FAM_UNIT={'Rotoli e consumabili':'pezzi','Contratti di assistenza':'contratti','Noleggi':'canoni','Software e licenze':'licenze','Servizi tecnici':'servizi','Altro':'righe'};
const famOf=n=>FAM_BY.get(n)||FAM_BY.get('Altro');
let sui={p:'ytd',fam:'',buy:'acc'};
let SC=null;// cache: same data + same period = same figures

const ym=d=>d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0');
const ymAdd=(s,k)=>{const d=new Date(+s.slice(0,4),+s.slice(5,7)-1+k,1);return ym(d);};
const MLAB=new Intl.DateTimeFormat('it-IT',{month:'short'});
const mLabel=s=>MLAB.format(new Date(+s.slice(0,4),+s.slice(5,7)-1,1)).replace('.','');
const MLONG=new Intl.DateTimeFormat('it-IT',{month:'long',year:'numeric'});
const mLong=s=>MLONG.format(new Date(+s.slice(0,4),+s.slice(5,7)-1,1));
function salesRange(p){
 // a month in progress would be compared with a whole month of the year before: the comparisons stop at the last
 // closed month (from the 25th the current month counts, it is almost over)
 const now=new Date(),y=now.getFullYear();const cur=now.getDate()>=25||now.getMonth()===0?ym(now):ymAdd(ym(now),-1);
 const span=(a,b)=>a===b?mLong(a):a.slice(0,4)===b.slice(0,4)?`${mLong(a).split(' ')[0]}–${mLong(b)}`:`${mLong(a)} – ${mLong(b)}`;
 if(p==='12m'){const from=ymAdd(cur,-11);return{from,to:cur,label:span(from,cur),cmpFrom:ymAdd(from,-12),cmpTo:ymAdd(cur,-12),cmpLabel:'i 12 mesi prima'};}
 if(/^\d{4}$/.test(p)){const n=+p;return{from:n+'-01',to:n+'-12',label:String(n),cmpFrom:(n-1)+'-01',cmpTo:(n-1)+'-12',cmpLabel:String(n-1)};}
 return{from:y+'-01',to:cur,label:span(y+'-01',cur),cmpFrom:(y-1)+'-01',cmpTo:ymAdd(cur,-12),cmpLabel:'lo stesso periodo del '+(y-1)};
}
function salesAgg(from,to){
 const inR=m=>m>=from&&m<=to;
 const fam=new Map(),prod=new Map();let rev=0,cRev=0,cost=0,below=0;
 for(const r of state.sales||[]){if(!inR(r.month))continue;
  const f=r.family==='Note di credito'?null:famOf(r.family).n;if(!f)continue;
  rev+=r.revenue||0;cRev+=r.costedRevenue||0;cost+=r.cost||0;below+=r.below||0;
  let a=fam.get(f);if(!a)fam.set(f,a={qty:0,rev:0,cRev:0,cost:0,below:0,docs:0});a.qty+=r.qty||0;a.rev+=r.revenue||0;a.cRev+=r.costedRevenue||0;a.cost+=r.cost||0;a.below+=r.below||0;a.docs+=r.docs||0;
  const k=r.code||r.name;let p=prod.get(k);if(!p)prod.set(k,p={code:r.code,name:r.name,fam:f,qty:0,rev:0,cRev:0,cost:0,below:0,last:''});
  p.qty+=r.qty||0;p.rev+=r.revenue||0;p.cRev+=r.costedRevenue||0;p.cost+=r.cost||0;p.below+=r.below||0;if(r.month>p.last){p.last=r.month;if(r.name)p.name=r.name;}}
 let R=0,C=0,F=0;const byAcc=new Map(),bySup=new Map(),months=new Map(),lossAcc=new Map();
 for(const l of state.ledger||[]){if(!inR(l.month))continue;
  let m=months.get(l.month);if(!m)months.set(l.month,m={r:0,c:0,f:0});
  if(l.kind==='R'){R+=l.amount;m.r+=l.amount;}
  else if(l.kind==='C'){C+=l.amount;m.c+=l.amount;if(/perdit|omagg|demoliz|insolut/i.test(l.label))lossAcc.set(l.label,(lossAcc.get(l.label)||0)+l.amount);}
  else if(l.kind==='F'){F+=l.amount;m.f+=l.amount;
   const a=byAcc.get(l.account)||{k:l.account,label:l.label,group:l.group,v:0,n:0};a.v+=l.amount;a.n+=l.docs||0;byAcc.set(l.account,a);
   const s=bySup.get(l.party)||{label:l.party,v:0,n:0,acc:new Map()};s.v+=l.amount;s.n+=l.docs||0;s.acc.set(l.label,(s.acc.get(l.label)||0)+l.amount);bySup.set(l.party,s);}}
 // credit notes come from the invoices (devices hold this year and the last one)
 let nc=0,ncN=0,ncKnown=false;
 if(typeof A!=='undefined'&&A){const first=A.inv.length?A.inv[A.inv.length-1].date?.slice(0,7):'';ncKnown=!!first&&first<=from;
  for(const i of A.inv){if(i.sign>0||!i.d)continue;const k=ym(i.d);if(k>=from&&k<=to){nc+=Math.abs(i.net);ncN++;}}}
 return{fam,prod,rev,cRev,cost,below,R,C,F,byAcc,bySup,months,lossAcc,nc,ncN,ncKnown,has:(state.sales||[]).length+(state.ledger||[]).length>0};
}
function deriveSales(){
 const key=sui.p;if(SC&&SC.key===key&&SC.s===state.sales&&SC.l===state.ledger&&SC.inv===state.invoices)return SC;
 if(typeof A==='undefined'||!A)deriveAccounting();
 const rg=salesRange(sui.p);const cur=salesAgg(rg.from,rg.to),cmp=salesAgg(rg.cmpFrom,rg.cmpTo);
 const all=[...(state.sales||[]),...(state.ledger||[])].map(x=>x.month);const years=[...new Set(all.map(m=>m.slice(0,4)))].sort().reverse();
 return SC={key,s:state.sales,l:state.ledger,inv:state.invoices,rg,cur,cmp,years};
}

const pct=(a,b)=>b>0.5?(a-b)/b*100:null;
const pctTxt=v=>v===null?'':`${v>=0?'▲':'▼'} ${Math.abs(v).toFixed(Math.abs(v)<10?1:0).replace('.',',')}%`;
const delta=(a,b,inv)=>{const v=pct(a,b);if(v===null)return'';const good=inv?v<=0:v>=0;return`<span class="sal-delta ${good?'up':'down'}">${pctTxt(v)}</span>`;};
const kEur=v=>{const a=Math.abs(v),s=v<0?'−':'';return a>=1e6?s+(a/1e6).toFixed(2).replace('.',',')+' M€':a>=1e4?s+Math.round(a/1e3)+' k€':DFE0.format(v);};

const kShort=v=>{const a=Math.abs(v);return a>=1e6?(a/1e6).toFixed(1).replace('.',',')+'M':a>=1e4?Math.round(a/1e3)+'k':a>=1e3?(a/1e3).toFixed(1).replace('.',',')+'k':String(Math.round(a));};
/** when Mastro was last read (the server does it by itself every hour) */
function salesSynced(){const at=state.mastroSync?.at?new Date(state.mastroSync.at):null;if(!at||isNaN(at))return'';
 const today=at.toDateString()===new Date().toDateString();
 return ` <span class="sal-sync"><i></i>Aggiornato da Mastro ${today?'alle '+at.toLocaleTimeString('it-IT',{hour:'2-digit',minute:'2-digit'}):'il '+at.toLocaleDateString('it-IT',{day:'numeric',month:'short'})} · in automatico ogni mezz’ora</span>`;}
function salesPage(){
 const S=deriveSales(),c=S.cur,p=S.cmp,rg=S.rg;
 const margin=c.cRev-c.cost,mPct=c.cRev>0?margin/c.cRev*100:null,pMargin=p.cRev-p.cost;
 const result=c.R-c.C,pResult=p.R-p.C;
 const lostAcc=[...c.lossAcc.values()].reduce((s,v)=>s+v,0);
 const old=typeof A!=='undefined'&&A?A.aging[3]:{v:0,n:0};
 const lost=c.below+c.nc+lostAcc;
 const periods=[['ytd','Quest’anno'],['12m','12 mesi'],...S.years.filter(y=>y<String(new Date().getFullYear())).slice(0,4).map(y=>[y,y])];
 return`${hero('Vendite e <span class="grad">margini</span>.',`Cosa hai venduto, cosa hai comprato e quanto ti resta: righe delle fatture e conti di Mastro, ${esc(rg.label)}, confrontati con ${esc(rg.cmpLabel)}.${salesSynced()}`)}
 <div class="sal-period reveal" style="--i:1"><div class="seg" role="tablist" aria-label="Periodo">${periods.map(([k,l])=>`<button role="tab" class="${sui.p===k?'active':''}" aria-selected="${sui.p===k}" data-sal-p="${k}">${l}</button>`).join('')}</div></div>
 ${c.has||p.has?'':`<div class="card reveal" style="--i:2">${empty('chart','Le vendite stanno arrivando','Il server legge MastroSQL da solo ogni mezz’ora (sola lettura): righe delle fatture e conti compaiono qui alla prossima lettura. Per averle subito: Impostazioni → Importa da MastroSQL.')}</div>`}
 <div class="kpis sal-kpis">
  <div class="card kpi t-info reveal" style="--i:2" data-tip="Imponibile delle righe di fattura (quantità × prezzo), note di credito escluse"><div class="kpi-top"><span>Emesso</span><span class="kpi-icon">${icon('upload')}</span></div><div class="kpi-value acc-v">${DFE0.format(c.rev)}</div><div class="kpi-note">${delta(c.rev,p.rev)} ${c.nc?`− ${DFE0.format(c.nc)} di note di credito`:'fatture ai clienti'}</div></div>
  <div class="card kpi reveal" style="--i:3" data-tip="Fatture dei fornitori registrate in Mastro, per conto di contropartita"><div class="kpi-top"><span>Acquisti</span><span class="kpi-icon">${icon('download')}</span></div><div class="kpi-value acc-v">${DFE0.format(c.F)}</div><div class="kpi-note">${delta(c.F,p.F,true)} ${plur([...c.bySup.keys()].length,'fornitore','fornitori')}</div></div>
  <div class="card kpi ${margin>=0?'t-ok':'t-bad'} reveal" style="--i:4" data-tip="Prezzo di vendita meno costo d'acquisto, sulle righe di cui Mastro conosce il costo"><div class="kpi-top"><span>Margine sui prodotti</span><span class="kpi-icon">${icon('chart')}</span></div><div class="kpi-value acc-v">${DFE0.format(margin)}</div><div class="kpi-note">${delta(margin,pMargin)} ${mPct===null?'nessun costo noto':`<strong>${mPct.toFixed(1).replace('.',',')}%</strong> del venduto`}</div></div>
  <div class="card kpi ${result>=0?'t-ok':'t-bad'} reveal" style="--i:5" data-tip="Ricavi meno costi registrati in contabilità (personale, affitti, veicoli, imposte compresi, quando registrati)"><div class="kpi-top"><span>${result>=0?'Utile':'Perdita'} contabile</span><span class="kpi-icon">${icon(result>=0?'check':'alert')}</span></div><div class="kpi-value acc-v">${DFE0.format(result)}</div><div class="kpi-note">${delta(result,pResult)} ricavi ${kEur(c.R)} · costi ${kEur(c.C)}</div></div>
 </div>
 ${salesTrend(c,rg)}
 ${salesFamilies(c,p)}
 <div class="layout-half">
  <div class="card reveal" style="--i:9"><div class="card-head"><div><h2>Quanto hai perso</h2><p>${esc(rg.label)}: vendite sotto costo, note di credito, perdite e omaggi registrati.</p></div><span class="sal-big ${lost>0?'bad':''}">${DFE0.format(lost)}</span></div>
   ${salesLoss([['Vendite sotto costo',c.below,'Righe vendute a un prezzo più basso del costo d’acquisto scritto in Mastro sulla riga'],['Note di credito emesse',c.nc,c.ncKnown?plur(c.ncN,'nota','note'):'solo per il periodo di cui il dispositivo ha le fatture'],...[...c.lossAcc].map(([l,v])=>[l,v,'Conto di Mastro'])])}
   ${old.v>0?`<div class="sal-warn" data-acc-tab="receivables" tabindex="0">${icon('alert')}<span>Oggi ci sono <strong>${DFE0.format(old.v)}</strong> di rate scadute da oltre 90 giorni (${plur(old.n,'rata','rate')}): rischiano di diventare perdite.</span>${icon('chevron')}</div>`:''}
   ${salesBelowList(c)}</div>
  ${salesBuys(c,p)}
 </div>
 ${salesProducts(c,p)}`;
}
/** revenues and costs month by month, with the result of each month */
function salesTrend(c,rg){
 const ms=[];for(let m=rg.from;m<=rg.to&&ms.length<24;m=ymAdd(m,1))ms.push(m);
 const rows=ms.map(m=>({m,...(c.months.get(m)||{r:0,c:0,f:0})}));if(!rows.some(x=>x.r||x.c))return'';
 const max=Math.max(1,...rows.map(x=>Math.max(x.r,x.c)));
 return`<div class="card reveal" style="--i:6"><div class="card-head"><div><h2>Ricavi e costi per mese</h2><p>Dalla contabilità di Mastro, chiusure di bilancio escluse. Sotto ogni mese, quanto è rimasto.</p></div>
  <div class="legend"><span><i style="background:var(--accent)"></i>Ricavi</span><span><i style="background:var(--bad)"></i>Costi</span></div></div>
  <div class="flow sal-flow" style="grid-template-columns:repeat(${rows.length},minmax(0,1fr))">${rows.map((x,i)=>{const net=x.r-x.c;return`<div class="flow-w" data-tip="${esc(`${mLabel(x.m)} ${x.m.slice(0,4)} · ricavi ${DFE0.format(x.r)} · costi ${DFE0.format(x.c)} · ${net>=0?'utile':'perdita'} ${DFE0.format(net)}`)}">
   <div class="flow-bars"><i class="rv" style="height:${Math.max(0,x.r)/max*100}%;--i:${i}"></i><i class="out" style="height:${Math.max(0,x.c)/max*100}%;--i:${i}"></i></div>
   <b class="mono ${net<0?'neg':''}">${net<0?'−':'+'}${kShort(net)}</b><span>${mLabel(x.m)}</span></div>`;}).join('')}</div></div>`;
}
/** one tile per family: pieces, revenue, margin, change */
function salesFamilies(c,p){
 const list=FAM.map(([n])=>({f:famOf(n),a:c.fam.get(n),b:p.fam.get(n)})).filter(x=>x.a&&(x.a.rev||x.a.qty));
 if(!list.length)return'';
 const tot=Math.max(1,c.rev);
 return`<div class="card reveal" style="--i:7"><div class="card-head"><div><h2>Cosa hai venduto</h2><p>Pezzi e incasso per famiglia di prodotto. Tocca una famiglia per vedere gli articoli.</p></div>${sui.fam?`<button class="btn sm ghost" data-sal-fam="">${icon('close')}Tutte</button>`:''}</div>
  <div class="sal-fams">${list.map((x,i)=>{const m=x.a.cRev>0?(x.a.cRev-x.a.cost)/x.a.cRev*100:null;
   return`<button class="sal-fam${sui.fam===x.f.n?' on':''}${x.f.p?'':' svc'}" style="--h:${x.f.h};--i:${i}" data-sal-fam="${esc(x.f.n)}" aria-pressed="${sui.fam===x.f.n}">
    <span class="sal-ic">${icon(x.f.ic)}</span><span class="sal-name">${esc(x.f.n)}</span>
    <strong class="sal-qty"><span data-count="${Math.round(x.a.qty)}">${fmt(Math.round(x.a.qty))}</span><small> ${FAM_UNIT[x.f.n]||'venduti'}</small></strong>
    <span class="sal-rev">${DFE0.format(x.a.rev)}${m!==null&&x.f.p?`<i class="sep"> · </i><em class="${m<0?'neg':''}">${m.toFixed(0)}% margine</em>`:''}</span>
    <span class="sal-share"><i style="width:${Math.max(2,x.a.rev/tot*100)}%"></i></span>
    ${x.b?`<span class="sal-cmp">${delta(x.a.qty,x.b.qty)||'<span class="muted">=</span>'}<small>${fmt(Math.round(x.b.qty))} prima</small></span>`:'<span class="sal-cmp"><span class="sal-delta up">nuovo</span></span>'}
   </button>`;}).join('')}</div></div>`;
}
function salesLoss(items){
 items=items.filter(x=>x[1]>0.5);if(!items.length)return empty('check','Nessuna perdita','Nel periodo non risultano vendite sotto costo, note di credito o perdite registrate.');
 const max=Math.max(...items.map(x=>x[1]));
 return`<div class="sal-loss">${items.map(([l,v,t],i)=>`<div class="sal-row" data-tip="${esc(t)}"><span>${esc(l)}</span><span class="track"><i style="width:${v/max*100}%;--i:${i}"></i></span><strong>${DFE0.format(v)}</strong></div>`).join('')}</div>`;
}
function salesBelowList(c){
 const l=[...c.prod.values()].filter(x=>x.below>0.5).sort((a,b)=>b.below-a.below).slice(0,5);if(!l.length)return'';
 return`<h3 class="sal-sub">Venduti sotto costo</h3>${l.map(x=>`<div class="list-row" data-sal-prod="${esc(x.code||x.name)}" tabindex="0"><span class="sal-dot" style="--h:${famOf(x.fam).h}"></span><div class="grow"><strong>${esc(x.name||x.code)}</strong><small class="mono">${esc(x.code)} · ${fmt(Math.round(x.qty))} pz</small></div><span class="mono" style="color:var(--bad)">−${DFE0.format(x.below)}</span></div>`).join('')}`;
}
/** purchases by counter-account (what was bought) or by supplier (from whom) */
function salesBuys(c,p){
 const acc=sui.buy==='acc';
 const pm=acc?p.byAcc:p.bySup;
 const list=[...(acc?c.byAcc.values():c.bySup.values())].sort((a,b)=>b.v-a.v);
 const top=list.slice(0,9),rest=list.slice(9).reduce((s,x)=>s+x.v,0);const max=Math.max(1,...top.map(x=>x.v));
 return`<div class="card reveal" style="--i:10"><div class="card-head"><div><h2>Acquisti</h2><p>${DFE0.format(c.F)} di fatture fornitori ${acc?'per conto di contropartita':'per fornitore'}.</p></div>
  <div class="seg" role="tablist"><button class="${acc?'active':''}" data-sal-buy="acc">Contropartita</button><button class="${acc?'':'active'}" data-sal-buy="sup">Fornitori</button></div></div>
  ${top.length?`<div class="sal-buys">${top.map((x,i)=>{const prev=pm.get(acc?x.k:x.label);const sub=acc?x.group:[...x.acc].sort((a,b)=>b[1]-a[1]).slice(0,2).map(a=>a[0]).join(' · ');
   return`<div class="sal-buy" style="--i:${i}"><div class="grow"><strong>${esc(x.label)}</strong><small>${esc(sub||'')}${x.n?` · ${plur(x.n,'fattura','fatture')}`:''}</small><span class="track"><i style="width:${x.v/max*100}%;--i:${i}"></i></span></div>
    <div class="sal-amt"><strong>${DFE0.format(x.v)}</strong>${prev?delta(x.v,prev.v,true):''}</div></div>`;}).join('')}
   ${rest>0.5?`<p class="sal-rest">Altre ${list.length-9} voci: <strong class="mono">${DFE0.format(rest)}</strong></p>`:''}</div>`
  :empty('download','Nessun acquisto','Nel periodo non risultano fatture di fornitori registrate in Mastro.')}</div>`;
}
/** every article sold in the period */
function salesProducts(c,p){
 const q=norm(ui.q);let list=[...c.prod.values()];if(sui.fam)list=list.filter(x=>x.fam===sui.fam);
 if(q)list=list.filter(x=>norm(x.code+' '+x.name+' '+x.fam).includes(q));
 list.sort((a,b)=>b.rev-a.rev);const rows=pageOf(list,16);const max=Math.max(1,...list.slice(0,1).map(x=>x.rev));
 const fams=FAM.filter(([n])=>c.fam.has(n));
 return`<div class="card reveal" style="--i:11" id="sal-prods"><div class="card-head"><div><h2>${sui.fam?esc(sui.fam):'Articoli venduti'}<span class="count">${fmt(list.length)}</span></h2><p>${fmt(Math.round(list.reduce((s,x)=>s+x.qty,0)))} pezzi · ${DFE0.format(list.reduce((s,x)=>s+x.rev,0))} · prima i più venduti per incasso.</p></div></div>
 <div class="toolbar">${search('Cerca articolo o codice…')}<select class="select" id="f-sal-fam"><option value="">Tutte le famiglie</option>${fams.map(([n])=>`<option${sui.fam===n?' selected':''}>${esc(n)}</option>`).join('')}</select></div>
 <div class="table-wrap"><table class="t-acc t-sal"><thead><tr><th>Articolo</th><th>Pezzi</th><th>Incasso</th><th>Costo</th><th>Margine</th><th>Rispetto a prima</th></tr></thead><tbody>
 ${rows.map((x,k)=>{const m=x.cRev>0?x.cRev-x.cost:null,mp=x.cRev>0?(x.cRev-x.cost)/x.cRev*100:null;const b=p.prod.get(x.code||x.name);
  return`<tr class="reveal" style="--i:${Math.min(k,14)}"><td><div class="who"><span class="sal-dot" style="--h:${famOf(x.fam).h}"></span><div><strong>${hl(x.name||x.code,ui.q)}</strong><small class="mono">${hl(x.code,ui.q)} · ${esc(x.fam)}</small></div></div><span class="sal-bar"><i style="width:${x.rev/max*100}%"></i></span></td>
  <td class="mono"><strong>${fmt(Math.round(x.qty))}</strong></td><td class="mono">${DFE0.format(x.rev)}</td><td class="mono muted">${x.cost?DFE0.format(x.cost):'—'}</td>
  <td class="mono">${m===null?'<span class="muted">—</span>':`<span style="color:var(--${m<0?'bad':'ok'})">${DFE0.format(m)}</span> <small class="muted">${mp.toFixed(0)}%</small>`}${x.below>0.5?`<br><small style="color:var(--bad)">−${DFE0.format(x.below)} sotto costo</small>`:''}</td>
  <td>${b?`${delta(x.qty,b.qty)}<br><small class="muted">${fmt(Math.round(b.qty))} pz prima</small>`:'<span class="sal-delta up">nuovo</span>'}</td></tr>`;}).join('')
  ||`<tr><td colspan="6">${empty('box','Nessun articolo','Nessuna vendita corrisponde alla ricerca nel periodo.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}

document.addEventListener('click',e=>{
 const t=e.target.closest('[data-sal-p],[data-sal-fam],[data-sal-buy],[data-sal-prod]');if(!t)return;const ds=t.dataset;
 if(ds.salP!==undefined){if(sui.p===ds.salP)return;sui.p=ds.salP;ui.p=1;return morph();}
 if(ds.salBuy!==undefined){if(sui.buy===ds.salBuy)return;sui.buy=ds.salBuy;return morph();}
 if(ds.salFam!==undefined){sui.fam=sui.fam===ds.salFam?'':ds.salFam;ui.p=1;morph();if(sui.fam)requestAnimationFrame(()=>$('#sal-prods')?.scrollIntoView({behavior:reduced?'auto':'smooth',block:'start'}));return;}
 if(ds.salProd!==undefined){ui.q=ds.salProd;ui.p=1;morph();requestAnimationFrame(()=>$('#sal-prods')?.scrollIntoView({behavior:reduced?'auto':'smooth',block:'start'}));}
});
document.addEventListener('change',e=>{if(e.target.id==='f-sal-fam'){sui.fam=e.target.value;ui.p=1;render(false);}});
/** a change of period or filter cross-fades the page in place (one movement), numbers count up again */
function morph(){
 const go=()=>{render(false);countUp();};
 if(!document.startViewTransition||reduced||document.hidden)return go();
 document.documentElement.classList.add('sal-vt');const t=document.startViewTransition(go);t.finished.finally(()=>document.documentElement.classList.remove('sal-vt')).catch(()=>{});
}
