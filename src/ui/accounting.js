'use strict';
/* Top Cash Studio — Contabilità. Loaded after app.js; uses its helpers and state.
   Mirror of MastroSQL (invoices, instalments, ledger balances): Mastro stays the accounting system of record,
   the app adds what the office does about each receivable (status + note, shared with the team). */

const eurA=n=>DFE.format(n||0);
const DFE=new Intl.NumberFormat('it-IT',{useGrouping:'always',style:'currency',currency:'EUR',minimumFractionDigits:2,maximumFractionDigits:2});
const DFE0=new Intl.NumberFormat('it-IT',{useGrouping:'always',style:'currency',currency:'EUR',maximumFractionDigits:0});
const DUE_ST=['','Sollecitato','Promesso','Incassato','Contestato'];
const DUE_CLS={Sollecitato:'info',Promesso:'acc',Incassato:'ok',Contestato:'bad'};
let A=null;
let aui={only:'open',year:'',mm:'',kind:'',sel:null};
const MESI=['Gennaio','Febbraio','Marzo','Aprile','Maggio','Giugno','Luglio','Agosto','Settembre','Ottobre','Novembre','Dicembre'];
const DFMY=new Intl.DateTimeFormat('it-IT',{month:'long',year:'numeric'});
const monKey=d=>d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0');
const ACC_PAGE={receivables:'accRec',invoices:'accInv',payables:'accPay',clients:'accCli'};

const _accD=new Map(); // dates repeat a lot (same due days): parsed once
function deriveAccounting(){
 const today=new Date();today.setHours(0,0,0,0);
 const pd=s=>{if(!s)return null;const k=String(s).slice(0,10);let d=_accD.get(k);if(d!==undefined)return d;d=new Date(k+'T00:00:00');if(isNaN(d))d=null;_accD.set(k,d);return d;};
 // Mastro code → client of the app (a client can merge several Mastro codes)
 const byMastro=new Map();for(const c of D?.clients||[])for(const k of c.mastroCodes||(c.mastro?[c.mastro.code]:[]))if(k&&!byMastro.has(String(k).toUpperCase()))byMastro.set(String(k).toUpperCase(),c);
 const cl=code=>byMastro.get(String(code||'').toUpperCase())||null;
 const notes=state.dueNotes||{};
 const inv=(state.invoices||[]).map(i=>{const c=cl(i.clientCode);const sign=i.kind==='Nota di credito'?-1:1;
  return{...i,d:pd(i.date),client:c,name:c?.name||i.clientName||i.clientCode,sign,net:sign*Math.abs(i.taxable||0),gross:sign*Math.abs(i.total||0),
   search:norm([i.number,i.clientName,c?.name,i.clientCode,i.payment].join(' '))};}).sort((a,b)=>(b.d||0)-(a.d||0));
 const dues=(state.dues||[]).map(x=>{const c=x.party==='C'?cl(x.partyCode):null;const d=pd(x.dueDate);const left=Math.max(0,(x.amount||0)-(x.paid||0));
  const n=notes[x.id]||null;const open=left>0.005&&n?.status!=='Incassato';
  return{...x,d,docD:pd(x.docDate),client:c,name:c?.name||x.partyName||x.partyCode,left,open,note:n,days:d?Math.round((d-today)/864e5):null,
   search:norm([x.docNumber,x.partyName,c?.name,x.partyCode,x.payment,n?.note].join(' '))};}).sort((a,b)=>(a.d||0)-(b.d||0));
 const rec=dues.filter(x=>x.party==='C'),pay=dues.filter(x=>x.party==='F');
 const sum=(l,f)=>l.reduce((s,x)=>s+f(x),0);
 const recOpen=rec.filter(x=>x.open),payOpen=pay.filter(x=>x.open);
 const overdue=recOpen.filter(x=>x.days!==null&&x.days<0);
 const y=today.getFullYear();const invY=inv.filter(i=>i.d&&i.d.getFullYear()===y),invPY=inv.filter(i=>i.d&&i.d.getFullYear()===y-1&&(i.d.getMonth()<today.getMonth()||i.d.getMonth()===today.getMonth()&&i.d.getDate()<=today.getDate()));
 // last 12 months of net revenue
 const months=[];for(let k=11;k>=0;k--){const d=new Date(y,today.getMonth()-k,1);months.push({d,v:0,n:0,pv:0,pn:0,cn:0});}
 const mIdx=new Map(months.map((m,i)=>[m.d.getFullYear()*12+m.d.getMonth(),i]));
 for(const i of inv){if(!i.d)continue;const k=mIdx.get(i.d.getFullYear()*12+i.d.getMonth());if(k!==undefined){months[k].v+=i.net;months[k].n++;if(i.sign<0)months[k].cn++;}
  const kp=mIdx.get((i.d.getFullYear()+1)*12+i.d.getMonth());if(kp!==undefined&&(kp<11||i.d.getDate()<=today.getDate())){months[kp].pv+=i.net;months[kp].pn++;}}
 // per-client figures (used by the client drawer and the ranking)
 const perClient=new Map();const pc=c=>{if(!perClient.has(c.code))perClient.set(c.code,{inv:[],dues:[],revY:0,open:0,overdue:0});return perClient.get(c.code);};
 for(const i of inv)if(i.client){const p=pc(i.client);p.inv.push(i);if(i.d&&i.d.getFullYear()===y)p.revY+=i.net;}
 for(const x of rec)if(x.client){const p=pc(x.client);p.dues.push(x);if(x.open){p.open+=x.left;if(x.days!==null&&x.days<0)p.overdue+=x.left;}}
 const bal=new Map();for(const b of state.balances||[])bal.set(b.party+'|'+String(b.code).toUpperCase(),b);
 const top=[...perClient].map(([code,p])=>({c:D.byCode.get(code),...p})).filter(x=>x.c&&x.revY>0).sort((a,b)=>b.revY-a.revY).slice(0,8);
 const years=[...new Set(inv.filter(i=>i.d).map(i=>i.d.getFullYear()))].sort((a,b)=>b-a);
 A={inv,dues,rec,pay,recOpen,payOpen,overdue,months,perClient,bal,top,years,cl,
  revY:sum(invY,i=>i.net),revPY:sum(invPY,i=>i.net),toCollect:sum(recOpen,x=>x.left),overdueSum:sum(overdue,x=>x.left),
  toPay30:sum(payOpen.filter(x=>x.days!==null&&x.days<=30),x=>x.left),toPay:sum(payOpen,x=>x.left),
  // how old the overdue is (1-30, 31-60, 61-90, over 90 days late)
  aging:[[1,30,'1–30 gg'],[31,60,'31–60 gg'],[61,90,'61–90 gg'],[91,1e9,'oltre 90 gg']].map(([a,b,l])=>{const xs=overdue.filter(x=>-x.days>=a&&-x.days<=b);return{l,v:sum(xs,x=>x.left),n:xs.length};}),
  // next 8 weeks: what comes in (open receivables) and goes out (open payables); already-late ones count in the first week
  flow:Array.from({length:8},(_,w)=>{const inW=x=>x.days!==null&&(w===0?x.days<7:x.days>=w*7&&x.days<w*7+7);const d=new Date(today);d.setDate(d.getDate()+w*7);
   return{d,inc:sum(recOpen.filter(inW),x=>x.left),out:sum(payOpen.filter(inW),x=>x.left)};}),
  has:(state.invoices||[]).length+(state.dues||[]).length>0};
}

function dueCls(x){if(!x.open)return'ok';if(x.days===null)return'';return x.days<0?'bad':x.days<=7?'due':'';}
function dueWhen(x){if(!x.open)return x.note?.status==='Incassato'?'incassata':'pagata';if(x.days===null)return'—';return x.days<0?`scaduta da ${fmt(-x.days)} gg`:x.days===0?'scade oggi':`tra ${fmt(x.days)} gg`;}

function accountingPage(){
 if(!A)deriveAccounting();
 const delta=A.revPY>0?(A.revY-A.revPY)/A.revPY*100:null;
 const maxM=Math.max(1,...A.months.map(m=>Math.abs(m.v)));
 return`${hero('La <span class="grad">contabilità</span>.','Fatture, incassi e pagamenti letti da MastroSQL. Segna solleciti e promesse di pagamento: le note restano anche dopo ogni importazione.',
  `<button class="btn need-export" data-acc-exp="dues">${icon('download')}Scadenze CSV</button><button class="btn need-export" data-acc-exp="invoices">${icon('download')}Fatture CSV</button>`)}
 ${explain('accounting','I dati arrivano da <strong>MastroSQL</strong> da soli: il server lo legge ogni mezz’ora, in sola lettura (Mastro resta il programma di contabilità). Tocca un mese nel grafico per leggerlo e aprirne le fatture. Apri una scadenza per segnare <strong>Sollecitato</strong>, <strong>Promesso</strong> o <strong>Incassato</strong> e preparare un’email di sollecito.')}
 ${A.has?'':`<div class="card reveal" style="--i:2">${empty('euro','Nessun dato contabile','Il server legge fatture e scadenze da MastroSQL da solo ogni mezz’ora (sola lettura). Per averle subito: Impostazioni → Importa da MastroSQL.')}</div>`}
 <div class="kpis">
  <div class="card kpi t-info reveal" style="--i:2"><div class="kpi-top"><span>Fatturato ${new Date().getFullYear()}</span><span class="kpi-icon">${icon('euro')}</span></div><div class="kpi-value acc-v">${DFE0.format(A.revY)}</div><div class="kpi-note">${delta===null?'imponibile, note di credito dedotte':`<span style="color:var(--${delta>=0?'ok':'bad'})">${delta>=0?'▲':'▼'} ${Math.abs(delta).toFixed(1).replace('.',',')}%</span> rispetto allo stesso periodo ${new Date().getFullYear()-1}`}</div></div>
  <div class="card kpi reveal" style="--i:3" data-acc-tab="receivables" tabindex="0"><div class="kpi-top"><span>Da incassare</span><span class="kpi-icon">${icon('download')}</span></div><div class="kpi-value acc-v">${DFE0.format(A.toCollect)}</div><div class="kpi-note">${plur(A.recOpen.length,'rata aperta','rate aperte')}</div></div>
  <div class="card kpi ${A.overdue.length?'t-due':'t-ok'} reveal" style="--i:4" data-acc-tab="receivables" tabindex="0"><div class="kpi-top"><span>Scaduto</span><span class="kpi-icon">${icon('alert')}</span></div><div class="kpi-value acc-v">${DFE0.format(A.overdueSum)}</div><div class="kpi-note">${A.overdue.length?`${plur(new Set(A.overdue.map(x=>x.partyCode)).size,'cliente da sollecitare','clienti da sollecitare')}`:'nessun ritardo'}</div></div>
  <div class="card kpi reveal" style="--i:5" data-acc-tab="payables" tabindex="0"><div class="kpi-top"><span>Da pagare · 30 gg</span><span class="kpi-icon">${icon('upload')}</span></div><div class="kpi-value acc-v">${DFE0.format(A.toPay30)}</div><div class="kpi-note">${DFE0.format(A.toPay)} in totale ai fornitori</div></div></div>
 ${typeof insStrip==='function'?insStrip():''}
 ${A.inv.length?`<div class="card reveal" style="--i:6"><div class="card-head"><div><h2>Fatturato mensile</h2><p>Ultimi 12 mesi, imponibile al netto delle note di credito.</p></div></div>
  ${accMonthReadout()}
  <div class="bars acc-bars" role="group" aria-label="Fatturato per mese">${A.months.map((m,i)=>`<button type="button" class="bar${i===accSel()?' on':''}" data-acc-m="${i}" aria-pressed="${i===accSel()}" aria-label="${esc(DFMY.format(m.d))}: ${esc(DFE0.format(m.v))}"><i class="${m.v<0?'neg':''}" style="height:${Math.max(3,Math.abs(m.v)/maxM*100)}%;--i:${i}"></i><span>${DF3.format(m.d)}</span></button>`).join('')}</div></div>`:''}
 ${A.has?accCash():''}
 ${A.has?accFocus():''}`;
}
/** month picked on the monthly chart (the current one at first) */
function accSel(){return aui.sel??A.months.length-1;}
function accMonthReadout(){
 const i=accSel(),m=A.months[i];if(!m)return'';const d=m.pv>0.5?(m.v-m.pv)/m.pv*100:null;const cur=i===A.months.length-1;
 return`<div class="acc-mon" aria-live="polite"><div><span class="acc-mon-l">${esc(DFMY.format(m.d))}${cur?' · in corso':''}</span>
  <strong class="acc-mon-v">${DFE0.format(m.v)}</strong>
  <small>${plur(m.n,'documento','documenti')}${m.cn?` · ${plur(m.cn,'nota di credito','note di credito')}`:''}${d===null?'':` · <b style="color:var(--${d>=0?'ok':'bad'})">${d>=0?'▲':'▼'} ${Math.abs(d).toFixed(1).replace('.',',')}%</b> su ${cur?`1–${new Date().getDate()} `:''}${esc(DF3.format(m.d))} ${m.d.getFullYear()-1} (${DFE0.format(m.pv)})`}</small></div>
  ${m.n?`<button class="btn sm ghost" data-acc-mon="${monKey(m.d)}">Vedi le fatture${icon('chevron')}</button>`:''}</div>`;
}
/** cash flow of the next 8 weeks + how old the overdue is */
function accCash(){
 let run=0;const fl=A.flow.map(f=>({...f,bal:run+=f.inc-f.out}));const max=Math.max(1,...fl.map(f=>Math.max(f.inc,f.out)));
 const ag=A.aging,agT=Math.max(1,A.overdueSum),tones=['var(--due)','color-mix(in srgb,var(--due) 45%,var(--bad))','var(--bad)','color-mix(in srgb,var(--bad) 65%,#000)'];
 const k=v=>{const a=Math.abs(v);return a>=1e6?(a/1e6).toFixed(1).replace('.',',')+' M':a>=1e3?Math.round(a/1e3)+' k':String(Math.round(a));};
 return`<div class="layout-half acc-cash">
  <div class="card reveal" style="--i:7"><div class="card-head"><div><h2>Flusso di cassa</h2><p>Prossime 8 settimane: incassi attesi e pagamenti ai fornitori. Le rate già scadute contano nella prima.</p></div>
   <div class="legend"><span><i style="background:var(--ok)"></i>Entrate</span><span><i style="background:var(--bad)"></i>Uscite</span></div></div>
   <div class="flow">${fl.map((f,i)=>`<div class="flow-w" title="${esc(`Settimana dal ${dShort(f.d)}: entrate ${DFE0.format(f.inc)}, uscite ${DFE0.format(f.out)}, saldo ${DFE0.format(f.bal)}`)}">
    <div class="flow-bars"><i class="in" style="height:${f.inc/max*100}%;--i:${i}"></i><i class="out" style="height:${f.out/max*100}%;--i:${i}"></i></div>
    <b class="mono ${f.bal<0?'neg':''}">${f.bal<0?'−':'+'}${k(f.bal)}</b><span>${i?dShort(f.d).replace(/\s?\d{4}$/,''):'Ora'}</span></div>`).join('')}</div>
   <p class="flow-note">Saldo previsto a 8 settimane: <strong class="mono" style="color:var(--${run<0?'bad':'ok'})">${DFE0.format(run)}</strong></p></div>
  <div class="card reveal" style="--i:8"><div class="card-head"><div><h2>Anzianità dello scaduto</h2><p>${A.overdue.length?`${DFE0.format(A.overdueSum)} in ${plur(A.overdue.length,'rata','rate')}: più è vecchio, più è urgente.`:'Nessuna rata scaduta.'}</p></div><button class="btn sm ghost" data-acc-tab="receivables">Sollecita${icon('chevron')}</button></div>
   <div class="aging-bar" role="img" aria-label="Scaduto per anzianità">${ag.map((g,i)=>g.v>0?`<i style="flex:${g.v/agT};background:${tones[i]};--i:${i}"></i>`:'').join('')||'<i style="flex:1;background:var(--ok)"></i>'}</div>
   <div class="aging">${ag.map((g,i)=>`<div><span><i style="background:${tones[i]}"></i>${g.l}</span><strong class="tab">${DFE0.format(g.v)}</strong><small>${plur(g.n,'rata','rate')}</small></div>`).join('')}</div></div>
 </div>`;
}
/** what the office should look at today: overdue receivables to chase, payments due soon, best customers of the year */
function accFocus(){
 const chase=A.overdue.filter(x=>!x.note?.status||x.note.status==='Sollecitato').sort((a,b)=>b.left-a.left).slice(0,6);
 const soon=A.payOpen.filter(x=>x.days!==null&&x.days<=30).slice(0,6);
 const more=(t,n)=>`<button class="btn sm ghost" data-acc-tab="${t}">${n}${icon('chevron')}</button>`;
 const row=x=>`<div class="list-row" data-due="${esc(x.id)}" tabindex="0"><span class="mark" style="--h:${hue(x.name)}">${esc(initials(x.name))}</span><div class="grow"><strong>${esc(x.name)}</strong><small>${x.d?dShort(x.d):'—'} · <span style="color:var(--${dueCls(x)==='bad'?'bad':dueCls(x)==='due'?'due':'text-3'})">${dueWhen(x)}</span>${x.note?.status?' · '+esc(x.note.status):''}</small></div><span class="mono acc-amt">${eurA(x.left)}</span></div>`;
 return`<div class="layout-half acc-focus">
  <div class="card reveal" style="--i:7"><div class="card-head"><div><h2>Da sollecitare<span class="count">${fmt(A.overdue.length)}</span></h2><p>Rate scadute, prima le più alte.</p></div>${more('receivables','Tutte')}</div>
   ${chase.map(row).join('')||empty('check','Nessun ritardo','Tutti i clienti sono in regola con i pagamenti.')}</div>
  <div class="card reveal" style="--i:8"><div class="card-head"><div><h2>Da pagare entro 30 giorni</h2><p>${eurA(A.toPay30)} ai fornitori.</p></div>${more('payables','Tutti')}</div>
   ${soon.map(row).join('')||empty('check','Niente in scadenza','Nessun pagamento ai fornitori nei prossimi 30 giorni.')}</div>
  ${A.top.length?`<div class="card reveal" style="--i:9"><div class="card-head"><div><h2>Migliori clienti ${new Date().getFullYear()}</h2><p>Fatturato imponibile dell'anno.</p></div>${more('clients','Situazione')}</div>
   ${A.top.map(x=>`<div class="list-row" data-client="${esc(x.c.code)}" tabindex="0">${markEl(x.c.name,x.c.code)}<div class="grow"><strong>${esc(x.c.name)}</strong><small>${esc(x.c.city||'')}${x.overdue?` · <span style="color:var(--bad)">${eurA(x.overdue)} scaduti</span>`:''}</small></div><span class="mono acc-amt">${eurA(x.revY)}</span></div>`).join('')}</div>`:''}
 </div>`;
}
/** one register per page (Da incassare, Fatture, Da pagare, Situazione clienti), same filters as before */
function accRegister(kind){
 if(!A)deriveAccounting();
 const T={receivables:['Da <span class="grad">incassare</span>.','Le rate dei clienti in ordine di scadenza: apri una rata per segnare sollecito, promessa o incasso e preparare l’email.',receivablesTab,'dues'],
  invoices:['Le <span class="grad">fatture</span>.','Fatture e note di credito emesse, lette da MastroSQL.',invoicesTab,'invoices'],
  payables:['Da <span class="grad">pagare</span>.','Le scadenze verso i fornitori, in ordine di data.',payablesTab,'dues'],
  clients:['Situazione <span class="grad">clienti</span>.','Fatturato, residui e scaduto di ogni cliente: prima chi è in ritardo.',clientsAccTab,'']}[kind];
 return`${hero(T[0],T[1],T[3]?`<button class="btn need-export" data-acc-exp="${T[3]}">${icon('download')}${T[3]==='dues'?'Scadenze':'Fatture'} CSV</button>`:'')}
 ${A.has?T[2]():`<div class="card reveal" style="--i:2">${empty('euro','Nessun dato contabile','Il server legge fatture e scadenze da MastroSQL da solo ogni mezz’ora (sola lettura). Per averle subito: Impostazioni → Importa da MastroSQL.')}</div>`}`;
}

function dueRows(list,cols){
 return list.map((x,k)=>`<tr class="reveal" style="--i:${Math.min(k,14)}" data-due="${esc(x.id)}" tabindex="0">
  <td><div class="who"><span class="mark" style="--h:${hue(x.name)}">${esc(initials(x.name))}</span><div><strong>${hl(x.name,ui.q)}</strong><small class="mono">${esc(x.partyCode)}${x.payment?' · '+esc(x.payment):''}</small></div></div></td>
  <td class="mono"><span class="muted">doc.</span> ${x.docNumber?hl(x.docNumber,ui.q):'—'}<br><small class="muted">${x.docD?dShort(x.docD):''}</small></td>
  <td>${x.d?dShort(x.d):'—'}<br><small style="color:var(--${dueCls(x)==='bad'?'bad':dueCls(x)==='due'?'due':'text-3'})">${dueWhen(x)}</small></td>
  <td class="num"><strong>${eurA(x.left)}</strong>${x.paid>0&&x.left>0?`<br><small class="muted">su ${eurA(x.amount)}</small>`:''}</td>
  ${cols?`<td>${x.note?.status?`<span class="badge ${DUE_CLS[x.note.status]||''}">${esc(x.note.status)}</span>`:x.open&&x.days<0?'<span class="badge bad">Scaduta</span>':x.open?'<span class="badge">Aperta</span>':'<span class="badge ok">Pagata</span>'}${x.note?.note?`<br><small class="muted acc-note">${esc(x.note.note)}</small>`:''}</td>`:''}
  <td><span class="row-go">${icon('chevron')}</span></td></tr>`).join('');
}
function filterDues(list){
 const q=norm(ui.q);if(q)list=list.filter(x=>x.search.includes(q));
 if(aui.only==='open')list=list.filter(x=>x.open);else if(aui.only==='late')list=list.filter(x=>x.open&&x.days!==null&&x.days<0);else if(aui.only==='week')list=list.filter(x=>x.open&&x.days!==null&&x.days>=0&&x.days<=7);else if(aui.only==='todo')list=list.filter(x=>x.open&&x.days<0&&!x.note?.status);
 return list;
}
function onlySel(withTodo){return`<select class="select" id="f-acc-only"><option value="open"${aui.only==='open'?' selected':''}>Aperte</option><option value="late"${aui.only==='late'?' selected':''}>Scadute</option><option value="week"${aui.only==='week'?' selected':''}>In scadenza 7 gg</option>${withTodo?`<option value="todo"${aui.only==='todo'?' selected':''}>Scadute da sollecitare</option>`:''}<option value="all"${aui.only==='all'?' selected':''}>Tutte (anche pagate)</option></select>`;}

function receivablesTab(){
 const list=filterDues(A.rec);const rows=pageOf(list,16);
 return`<div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Scadenzario clienti<span class="count">${fmt(list.length)}</span></h2><p>Residuo ${eurA(list.reduce((s,x)=>s+x.left,0))} · apri una rata per segnare il sollecito o la promessa di pagamento.</p></div></div>
 <div class="toolbar">${search('Cerca cliente, documento, nota…')}${onlySel(true)}</div>
 <div class="table-wrap"><table class="t-acc"><thead><tr><th>Cliente</th><th>Documento</th><th>Scadenza</th><th class="num">Residuo</th><th>Stato</th><th></th></tr></thead><tbody>
 ${dueRows(rows,true)||`<tr><td colspan="6">${empty('check','Nessuna rata','Nessun incasso corrisponde ai filtri: ottimo segno.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}
function payablesTab(){
 const list=filterDues(A.pay);const rows=pageOf(list,16);
 return`<div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Pagamenti ai fornitori<span class="count">${fmt(list.length)}</span></h2><p>Residuo ${eurA(list.reduce((s,x)=>s+x.left,0))} · in ordine di scadenza.</p></div></div>
 <div class="toolbar">${search('Cerca fornitore, documento…')}${onlySel(false)}</div>
 <div class="table-wrap"><table class="t-acc"><thead><tr><th>Fornitore</th><th>Documento</th><th>Scadenza</th><th class="num">Residuo</th><th>Stato</th><th></th></tr></thead><tbody>
 ${dueRows(rows,true)||`<tr><td colspan="6">${empty('check','Nessun pagamento','Nessuna scadenza fornitore corrisponde ai filtri.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}
function invoicesTab(){
 const q=norm(ui.q);let list=A.inv;if(q)list=list.filter(i=>i.search.includes(q));
 if(aui.year)list=list.filter(i=>i.d&&String(i.d.getFullYear())===aui.year);
 if(aui.mm)list=list.filter(i=>i.d&&i.d.getMonth()+1===+aui.mm);
 if(aui.kind)list=list.filter(i=>aui.kind==='nc'?i.sign<0:i.sign>0);
 const rows=pageOf(list,18);const tot=list.reduce((s,i)=>s+i.net,0);
 return`<div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Fatture e note di credito<span class="count">${fmt(list.length)}</span></h2><p>Imponibile ${eurA(tot)}${list.length?` · ${eurA(list.reduce((s,i)=>s+i.gross,0))} con IVA`:''}.</p></div></div>
 <div class="toolbar">${search('Cerca numero, cliente, pagamento…')}<select class="select" id="f-acc-year" aria-label="Anno"><option value="">Tutti gli anni</option>${A.years.map(y=>`<option${aui.year===String(y)?' selected':''}>${y}</option>`).join('')}</select><select class="select" id="f-acc-mm" aria-label="Mese"><option value="">Tutti i mesi</option>${MESI.map((m,k)=>`<option value="${k+1}"${aui.mm===String(k+1)?' selected':''}>${m}</option>`).join('')}</select><select class="select" id="f-acc-kind" aria-label="Tipo"><option value="">Fatture e note</option><option value="f"${aui.kind==='f'?' selected':''}>Solo fatture</option><option value="nc"${aui.kind==='nc'?' selected':''}>Solo note di credito</option></select>${aui.year||aui.mm||aui.kind?`<button class="btn sm ghost" data-acc-mon="" title="Togli i filtri">${icon('close')}Azzera</button>`:''}</div>
 <div class="table-wrap"><table class="t-acc"><thead><tr><th>Documento</th><th>Cliente</th><th class="num">Imponibile</th><th class="num">IVA</th><th class="num">Totale</th><th>Pagamento</th></tr></thead><tbody>
 ${rows.map((i,k)=>`<tr class="reveal" style="--i:${Math.min(k,14)}" ${i.client?`data-client="${esc(i.client.code)}" tabindex="0"`:''}><td><strong class="mono">${hl(i.number,ui.q)}</strong> ${i.sign<0?'<span class="badge acc">Nota di credito</span>':''}<br><small class="muted">${i.d?dShort(i.d):esc(i.date)}</small></td>
  <td><strong style="font-weight:560">${hl(i.name,ui.q)}</strong><br><small class="muted mono">${esc(i.clientCode)}</small></td><td class="num">${eurA(i.net)}</td><td class="num muted">${eurA(i.sign*Math.abs(i.vat||0))}</td><td class="num"><strong>${eurA(i.gross)}</strong></td><td>${esc(i.payment||'—')}</td></tr>`).join('')
  ||`<tr><td colspan="6">${empty('file','Nessuna fattura','Modifica la ricerca o l’anno.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,18)}</div>`;
}
function clientsAccTab(){
 const q=norm(ui.q);let list=[...A.perClient].map(([code,p])=>({c:D.byCode.get(code),...p})).filter(x=>x.c&&(x.open>0||x.revY>0||x.inv.length));
 if(q)list=list.filter(x=>norm(x.c.name+' '+x.c.code).includes(q));
 list.sort((a,b)=>b.overdue-a.overdue||b.open-a.open||b.revY-a.revY);
 const rows=pageOf(list,16);
 return`<div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Situazione per cliente<span class="count">${fmt(list.length)}</span></h2><p>Prima chi ha importi scaduti. Clicca per aprire la scheda cliente.</p></div></div>
 <div class="toolbar">${search('Cerca cliente…')}</div>
 <div class="table-wrap"><table class="t-acc"><thead><tr><th>Cliente</th><th class="num">Fatturato ${new Date().getFullYear()}</th><th class="num">Da incassare</th><th class="num">Scaduto</th><th>Ultima fattura</th><th></th></tr></thead><tbody>
 ${rows.map((x,k)=>`<tr class="reveal" style="--i:${Math.min(k,14)}" data-client="${esc(x.c.code)}" tabindex="0"><td><div class="who">${markEl(x.c.name,x.c.code)}<div><strong>${hl(x.c.name,ui.q)}</strong><small>${esc(x.c.city||'')}</small></div></div></td>
  <td class="num">${eurA(x.revY)}</td><td class="num">${x.open?eurA(x.open):'<span class="muted">—</span>'}</td><td class="num" style="color:var(--${x.overdue?'bad':'text-3'})">${x.overdue?'<strong>'+eurA(x.overdue)+'</strong>':'—'}</td>
  <td>${x.inv[0]?.d?dShort(x.inv[0].d):'—'}</td><td><span class="row-go">${icon('chevron')}</span></td></tr>`).join('')||`<tr><td colspan="6">${empty('users','Nessun cliente','Nessun cliente con movimenti contabili.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}

// ── client drawer section
function accClientSection(c){
 if(!A)deriveAccounting();
 const p=A.perClient.get(c.code);const codes=c.mastroCodes||(c.mastro?[c.mastro.code]:[]);
 const b=codes.map(k=>A.bal.get('C|'+String(k).toUpperCase())).filter(Boolean);
 if(!p&&!b.length)return'';
 const saldo=b.reduce((s,x)=>s+(x.debit||0)-(x.credit||0),0);
 const open=(p?.dues||[]).filter(x=>x.open);
 return`<div class="d-section"><h3>Contabilità <span><button class="btn sm ghost" data-acc-client="${esc(c.code)}">Tutto</button></span></h3>
  <div class="facts"><div class="fact"><small>Fatturato ${new Date().getFullYear()}</small><span>${eurA(p?.revY||0)}</span></div><div class="fact"><small>Da incassare</small><span>${eurA(p?.open||0)}</span></div>
   <div class="fact"><small>Scaduto</small><span style="color:var(--${p?.overdue?'bad':'text'})">${eurA(p?.overdue||0)}</span></div>${b.length?`<div class="fact"><small>Saldo contabile Mastro</small><span>${eurA(saldo)} <span class="muted">${saldo>0.005?'dare':saldo<-0.005?'avere':''}</span></span></div>`:''}</div>
  ${open.slice(0,6).map(x=>`<div class="list-row" data-due="${esc(x.id)}"><div class="grow"><strong>${eurA(x.left)} <span class="muted">· doc. ${esc(x.docNumber||'—')}</span></strong><small>${x.d?dShort(x.d):'—'} · ${dueWhen(x)}${x.note?.status?' · '+esc(x.note.status):''}</small></div><span class="badge ${dueCls(x)}">${x.days<0?'Scaduta':'Aperta'}</span></div>`).join('')}
  ${(p?.inv||[]).slice(0,5).map(i=>`<div class="list-row" style="cursor:default"><div class="grow"><strong class="mono">${esc(i.number)}${i.sign<0?' <span class="badge acc">NC</span>':''}</strong><small>${i.d?dShort(i.d):esc(i.date)}${i.payment?' · '+esc(i.payment):''}</small></div><span class="mono">${eurA(i.gross)}</span></div>`).join('')}</div>`;
}

// ── receivable dialog: status, note, reminder email
function openDue(id){
 if(!A)deriveAccounting();
 const x=A.dues.find(d=>d.id===id);if(!x)return toast('Scadenza non trovata','alert');
 const c=x.client;const email=c?.email||c?.emails?.[0]||'';
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<div class="modal-box" role="dialog" aria-modal="true" aria-label="Scadenza ${esc(x.name)}">
  <div style="display:flex;gap:14px;align-items:center"><span class="mark" style="--h:${hue(x.name)};width:46px;height:46px">${esc(initials(x.name))}</span><div style="flex:1;min-width:0"><h3>${esc(x.name)}</h3><p>${x.party==='F'?'Pagamento a fornitore':'Incasso da cliente'} · doc. <span class="mono">${esc(x.docNumber||'—')}</span>${x.docD?' del '+dShort(x.docD):''}</p></div></div>
  <div class="facts"><div class="fact"><small>Scadenza</small><span>${x.d?dShort(x.d):'—'} <span style="color:var(--${dueCls(x)==='bad'?'bad':'text-3'})">${dueWhen(x)}</span></span></div><div class="fact"><small>Residuo</small><span class="mono"><strong>${eurA(x.left)}</strong>${x.paid>0?` <span class="muted">su ${eurA(x.amount)}</span>`:''}</span></div>
   <div class="fact"><small>Pagamento</small><span>${esc(x.payment||'—')}</span></div><div class="fact"><small>Ultimo aggiornamento</small><span>${x.note?`${esc(x.note.by||'')} · ${dShort(new Date(x.note.date))}`:'—'}</span></div></div>
  <label class="field">Stato<select class="select" id="due-st">${DUE_ST.map(s=>`<option value="${s}"${(x.note?.status||'')===s?' selected':''}>${s||'Nessuno'}</option>`).join('')}</select></label>
  <label class="field">Nota<textarea class="textarea" id="due-note" maxlength="2000" placeholder="Es. promette bonifico entro venerdì">${esc(x.note?.note||'')}</textarea></label>
  <div class="btn-row">${x.party==='C'?`<button class="btn ghost" data-dm="mail" style="margin-right:auto" ${email?'':'disabled data-tip="Il cliente non ha un indirizzo email"'}>${icon('mail')}Email di sollecito</button>`:''}${c?`<button class="btn ghost" data-dm="client">Scheda cliente</button>`:''}<button class="btn ghost" data-dm="close">Annulla</button><button class="btn accent" data-dm="save">${icon('check')}Salva</button></div></div>`;
 document.body.append(m);const prev=document.activeElement;
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);prev?.focus?.({preventScroll:true});};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();close();}};document.addEventListener('keydown',key,true);
 setTimeout(()=>m.querySelector('#due-st').focus(),30);
 m.addEventListener('click',async e=>{if(e.target===m)return close();const b=e.target.closest('[data-dm]');if(!b||b.disabled)return;const a=b.dataset.dm;
  if(a==='close')return close();
  if(a==='client'){close();return openClient(c.code);}
  if(a==='mail'){const subj=`Sollecito di pagamento - documento ${x.docNumber||''}`.trim();
   const body=`Gentile ${x.name},\n\nda un controllo risulta ancora aperto l'importo di ${eurA(x.left)} relativo al documento n. ${x.docNumber||'—'}${x.docD?' del '+dShort(x.docD):''}, scaduto il ${x.d?dShort(x.d):'—'}.\n\nVi chiediamo cortesemente di provvedere al pagamento o di inviarci la contabile se già effettuato.\n\nCordiali saluti\nTop Cash Service S.r.l.`;
   external(`mailto:${encodeURIComponent(email)}?subject=${encodeURIComponent(subj)}&body=${encodeURIComponent(body)}`);
   if(!m.querySelector('#due-st').value)m.querySelector('#due-st').value='Sollecitato';return;}
  if(a==='save'){const status=m.querySelector('#due-st').value,note=m.querySelector('#due-note').value.trim();b.disabled=true;
   const n={id:x.id,status,note,date:new Date().toISOString(),by:me?.name||me?.username||''};
   try{if(WEB)await post('/api/accounting/due-note',{id:x.id,status,note});else send('dueNote',{id:x.id,status,note});}catch(err){b.disabled=false;return toast(err.message,'alert');}
   state.dueNotes={...(state.dueNotes||{})};if(status||note)state.dueNotes[x.id]=n;else delete state.dueNotes[x.id];
   deriveAccounting();close();toast(status?`Scadenza segnata come “${status}”`:'Nota salvata');render(false);if(drawerCode)openClient(drawerCode,true);}
 });
}

document.addEventListener('click',e=>{
 const t=e.target.closest('[data-acc-tab],[data-due],[data-acc-exp],[data-acc-client],[data-acc-m],[data-acc-mon]');if(!t)return;const ds=t.dataset;
 if(ds.accM!==undefined){aui.sel=+ds.accM;const card=t.closest('.card');
  const swap=()=>{card.querySelectorAll('[data-acc-m]').forEach(b=>{const on=b===t;b.classList.toggle('on',on);b.setAttribute('aria-pressed',on);});
   const r=card.querySelector('.acc-mon');if(r){r.outerHTML=accMonthReadout();card.querySelector('.acc-mon')?.classList.add('swap');}};
  return document.startViewTransition&&!matchMedia('(prefers-reduced-motion: reduce)').matches?void document.startViewTransition(swap):swap();}
 if(ds.accMon!==undefined){const m=ds.accMon;aui.year=m?m.slice(0,4):'';aui.mm=m?String(+m.slice(5)):'';aui.kind='';ui.p=1;if(m)ui.q='';return page==='accInv'?render(false):go('accInv');}
 if(ds.accTab){if(ds.accTab!=='invoices')aui.only=aui.only||'open';return go(ACC_PAGE[ds.accTab]||'accRec');}
 if(ds.due)return openDue(ds.due);
 if(ds.accExp)return send(ds.accExp==='dues'?'exportDues':'exportInvoices');
 if(ds.accClient){const c=D.byCode.get(ds.accClient);closeDrawer();aui.only='all';go('accRec');ui.q=c?.name||'';render(false);}
});
document.addEventListener('keydown',e=>{if(e.key!=='Enter')return;const r=e.target.closest?.('tr[data-due]');if(r){e.preventDefault();openDue(r.dataset.due);}});
document.addEventListener('change',e=>{const id=e.target.id;
 if(id==='f-acc-only'){aui.only=e.target.value;ui.p=1;render(false);}
 else if(id==='f-acc-year'){aui.year=e.target.value;ui.p=1;render(false);}
 else if(id==='f-acc-mm'){aui.mm=e.target.value;ui.p=1;render(false);}
 else if(id==='f-acc-kind'){aui.kind=e.target.value;ui.p=1;render(false);}
});

// ───────────────────────── Incassi e note: unpaid RiBa/RID, invoice balances, deposits and credit notes that Mastro records as
// interventions. Same records (open one to change it), kept apart from the technicians' work and never closed automatically.
let MQ={tab:'open'};
function moneyPage(){
 const all=D.money||[],q=norm(ui.q);const tabs=[['open','Da incassare'],['paid','Incassati'],['credit','Note di credito'],['all','Tutti']];
 if(!tabs.some(([k])=>k===MQ.tab))MQ.tab='open';
 const coll=all.filter(i=>i.kind==='Incasso'),open=coll.filter(i=>i.status!=='Chiuso'),paid=coll.filter(i=>i.status==='Chiuso'),credit=all.filter(i=>i.kind==='Nota di credito');
 const sum=l=>l.reduce((s,i)=>s+(i.amount||0),0);const y1=all.filter(i=>i.d&&dayDiff(i.d)>=-365);
 const paid90=paid.filter(i=>i.d&&dayDiff(i.d)>=-90),credit12=credit.filter(i=>i.d&&dayDiff(i.d)>=-365);
 let list=MQ.tab==='open'?open:MQ.tab==='paid'?paid:MQ.tab==='credit'?credit:all;
 if(q)list=list.filter(i=>norm([i.client,i.title,i.work,i.technician].join(' ')).includes(q));
 if(ui.tech)list=list.filter(i=>i.technician===ui.tech);
 if(!['date','client','amount'].includes(ui.sort.key))ui.sort={key:'date',dir:-1};
 list=sortBy(list,{date:i=>i.d?+i.d:0,client:i=>i.client,amount:i=>i.amount||0});
 const rows=pageOf(list,20);const who=[...new Set(all.map(i=>i.technician).filter(Boolean))].sort();
 const late=open.filter(i=>i.d&&dayDiff(i.d)<-30).length;
 const rule=state.moneyRule||{enabled:true};
 return`${hero('Incassi e <span class="grad">note</span>.','Insoluti, saldi di fattura, acconti e note di credito: in Mastro sono registrati come interventi, qui stanno con la contabilità.','')}
 ${explain('money','Studio riconosce da titolo e articolo di Mastro gli <strong>incassi</strong> (insoluti RiBa e RID, saldi, acconti) e le <strong>note di credito</strong>, e li toglie dagli interventi tecnici: i tecnici non li vedono e non si chiudono mai da soli. Se uno è finito qui per sbaglio aprilo e cambia <strong>Tipo</strong>. La regola si spegne da Impostazioni → Interventi.')}
 ${rule.enabled===false?`<div class="banner reveal" style="--i:1"><span class="orb">${icon('alert')}</span><div class="grow"><h3>Riconoscimento automatico spento</h3><p>Qui compaiono solo quelli spostati a mano. Riaccendilo da Impostazioni → Interventi.</p></div></div>`:''}
 <div class="kpis" style="grid-template-columns:repeat(3,1fr)">
  <div class="card kpi t-bad reveal" style="--i:2" data-mq-tab="open" tabindex="0"><div class="kpi-top"><span>Da incassare</span><span class="kpi-icon">${icon('euro')}</span></div><div class="kpi-value">${eurA(sum(open))}</div><div class="kpi-note">${plur(open.length,'pratica aperta','pratiche aperte')}${late?` · <b>${fmt(late)} da oltre 30 giorni</b>`:''}</div></div>
  <div class="card kpi t-ok reveal" style="--i:3" data-mq-tab="paid" tabindex="0"><div class="kpi-top"><span>Incassati in 90 giorni</span><span class="kpi-icon">${icon('check')}</span></div><div class="kpi-value">${eurA(sum(paid90))}</div><div class="kpi-note">${plur(paid90.length,'incasso','incassi')}</div></div>
  <div class="card kpi t-info reveal" style="--i:4" data-mq-tab="credit" tabindex="0"><div class="kpi-top"><span>Note di credito</span><span class="kpi-icon">${icon('file')}</span></div><div class="kpi-value">${fmt(credit12.length)}</div><div class="kpi-note">negli ultimi 12 mesi, su ${plur(y1.length,'pratica','pratiche')} in tutto</div></div>
 </div>
 <div class="card reveal" style="--i:5"><div class="card-head"><div><h2>${tabs.find(([k])=>k===MQ.tab)[1]}<span class="count">${fmt(list.length)}</span></h2>${list.length&&MQ.tab!=='credit'?`<p>Totale ${eurA(sum(list))}</p>`:''}</div><div class="seg">${tabs.map(([k,l])=>`<button class="${MQ.tab===k?'active':''}" data-mq-tab="${k}">${l}<span class="count">${fmt(k==='open'?open.length:k==='paid'?paid.length:k==='credit'?credit.length:all.length)}</span></button>`).join('')}</div></div>
 <div class="toolbar">${search('Cerca cliente, fattura, importo…')}${who.length?`<select class="select" id="f-tech"><option value="">Chiunque</option>${who.map(t=>`<option${t===ui.tech?' selected':''}>${esc(t)}</option>`).join('')}</select>`:''}${sortMenu([['date',-1,'Più recenti'],['date',1,'Meno recenti'],['amount',-1,'Importo più alto'],['amount',1,'Importo più basso'],['client',1,'Cliente A–Z']])}</div>
 ${list.length?`<div class="table-wrap"><table class="t-money"><thead><tr>${th('Data','date')}${th('Cliente','client')}<th>Descrizione</th>${th('Importo','amount')}<th>Incaricato</th><th>Stato</th><th></th></tr></thead><tbody>
 ${rows.map((i,k)=>`<tr class="reveal" style="--i:${k}" data-int="${esc(i.id)}" tabindex="0"><td>${i.d?dShort(i.d):'<span class="muted">—</span>'}<br><small class="muted">${i.d?rel(dayDiff(i.d)):''}</small></td><td><div class="who">${markEl(i.client,i.code||i.client)}<div><strong>${hl(i.client,ui.q)}</strong><small>${i.kind==='Nota di credito'?'Nota di credito':'Incasso'}</small></div></div></td><td><span class="money-title">${hl(i.title,ui.q)}</span></td><td class="num">${i.amount?eurA(i.amount):'<span class="muted">—</span>'}</td><td>${i.technician?esc(i.technician):'<span class="muted">—</span>'}</td><td><span class="badge ${i.status==='Chiuso'?'ok':i.d&&dayDiff(i.d)<-30?'bad':'due'}">${i.status==='Chiuso'?(i.kind==='Incasso'?'Incassato':'Registrata'):esc(i.status)}</span></td><td>${i.kind==='Incasso'&&i.status!=='Chiuso'&&canEditClients()?`<button class="btn sm" data-mq-paid="${esc(i.id)}">${icon('check')}Incassato</button>`:''}</td></tr>`).join('')}
 </tbody></table></div>${pager(list.length,20)}`:empty(MQ.tab==='open'?'check':'file',MQ.tab==='open'?'Niente da incassare':'Nessuna pratica',q||ui.tech?'Prova a cambiare la ricerca o il filtro.':MQ.tab==='open'?'Tutti gli incassi registrati risultano chiusi.':'Qui compariranno appena registrati in Mastro o spostati a mano.')}</div>`;
}
document.addEventListener('click',e=>{
 const t=e.target.closest?.('[data-mq-tab]');if(t&&page==='accMoney'){MQ.tab=t.dataset.mqTab;ui.p=1;render(false);return;}
 const p=e.target.closest?.('[data-mq-paid]');if(p){e.stopPropagation();e.preventDefault();saveIntQuick(p.dataset.mqPaid,{status:'Chiuso'},'Segnato come incassato');}
},true);

// ───────────────────────── Bilancio: income statement and balance sheet from Mastro's general ledger (computed by the server,
// the same on every app). Year to date against the same months of last year; balance sheet at the month end against 31 December.
let BQ={sel:null,open:new Set()};
function bilancioPage(){
 const b=state.bilancio,y=new Date().getFullYear();
 const head=hero('Il <span class="grad">bilancio</span>.','Conto economico e stato patrimoniale letti da MastroSQL, aggiornati a ogni lettura. Il bilancio ufficiale resta quello del commercialista.',
  b?.hasIncome||b?.hasBalance?`<button class="btn need-export" data-action="exportBilancio">${icon('download')}Esporta per il commercialista</button>`:'');
 if(!b||!(b.hasIncome||b.hasBalance))return head+`<div class="card reveal" style="--i:2">${empty('chart','Bilancio non ancora disponibile','Compare alla prossima lettura di MastroSQL (il server la fa da sola ogni mezz’ora).')}</div>`;
 const pct=(a,p)=>p?((a-p)/Math.abs(p)*100):null;
 const delta=(a,p,good=1)=>{const d=pct(a,p);return d===null||!isFinite(d)?'':`<span class="bil-d ${(d>=0)===(good>0)?'up':'down'}">${d>=0?'▲':'▼'} ${Math.abs(d).toFixed(1).replace('.',',')}%</span>`;};
 const mon=k=>{const[yy,mm]=k.split('-').map(Number);return new Date(yy,mm-1,1);};
 const upTo=DF3.format(mon(b.month)),endPrev=`31/12/${y-1}`;
 const marg=b.revenue?b.result/b.revenue*100:0;
 const k=(label,value,note,ic,tone,i,tip)=>`<div class="card kpi ${tone} reveal" style="--i:${i}"${tip?` data-tip="${esc(tip)}"`:''}><div class="kpi-top"><span>${label}</span><span class="kpi-icon">${icon(ic)}</span></div><div class="kpi-value acc-v">${value}</div><div class="kpi-note">${note}</div></div>`;
 const kpis=`<div class="kpis bil-kpis">
  ${k(b.result>=0?`Utile ${y} a ${upTo}`:`Perdita ${y} a ${upTo}`,DFE0.format(b.result),`${delta(b.result,b.resultPrev)} su ${DFE0.format(b.resultPrev)} nel ${y-1} · margine ${marg.toFixed(1).replace('.',',')}%`,'trend',b.result>=0?'t-ok':'t-bad',2,'Ricavi meno costi registrati finora: ammortamenti, ratei e rimanenze finali li registra il commercialista a fine anno')}
  ${k('Ricavi',DFE0.format(b.revenue),`${delta(b.revenue,b.revenuePrev)} su ${DFE0.format(b.revenuePrev)} (gen–${upTo} ${y-1})`,'euro','t-info',3)}
  ${b.hasBalance?k('Cassa e banche',DFE0.format(b.liquidity),`${DFE0.format(b.liquidityPrev)} al ${endPrev}`,'shield',b.liquidity>=0?'t-ok':'t-bad',4,'Contanti, assegni, conti correnti e carte: gli anticipi su RiBa sono tra i debiti finanziari'):''}
  ${b.hasBalance?k('Posizione finanziaria netta',DFE0.format(b.netFinancial),`debiti verso banche ${DFE0.format(b.financialDebt)}`,'chart',b.netFinancial>=0?'t-ok':'t-due',5,'Cassa e banche meno mutui, finanziamenti e anticipi su effetti'):''}
 </div>
 ${b.hasBalance?`<div class="kpis bil-kpis">
  ${k('Crediti verso clienti',DFE0.format(b.receivables),b.dso?`incassati in media in <b>${fmt(b.dso)} giorni</b>`:'','download','t-info',6,'Clienti e portafoglio effetti, IVA compresa; giorni medi = crediti / fatturato degli ultimi 12 mesi')}
  ${k('Debiti verso fornitori',DFE0.format(b.payables),b.dpo?`pagati in media in <b>${fmt(b.dpo)} giorni</b>`:'','upload','t-due',7,'Giorni medi = debiti / acquisti degli ultimi 12 mesi, IVA compresa')}
  ${k(b.vat<0?'IVA da versare':'Credito IVA',DFE0.format(Math.abs(b.vat)),'saldo dei conti IVA in Mastro','file',b.vat<0?'t-due':'t-ok',8)}
  ${k('Patrimonio netto',DFE0.format(b.equity),`${DFE0.format(b.equityPrev)} al ${endPrev}`,'star','t-info',9,'Capitale, riserve, risultati non ancora destinati e utile in corso')}
 </div>`:''}`;
 // months: result bars (green/red) with last year's line as a tick, tap one to read it
 const ms=b.months||[],pm=b.monthsPrev||[];const max=Math.max(1,...ms.map(m=>Math.abs(m.result)),...pm.map(m=>Math.abs(m.result)));
 const sel=Math.min(BQ.sel??ms.length-1,ms.length-1),sm=ms[sel],sp=pm[sel];
 const chart=ms.length?`<div class="card reveal" style="--i:10"><div class="card-head"><div><h2>Risultato mese per mese</h2><p>Ricavi meno costi registrati in ogni mese del ${y}; la tacca è lo stesso mese del ${y-1}.</p></div></div>
  ${sm?`<div class="acc-mon bil-mon" aria-live="polite"><div><span class="acc-mon-l">${esc(DFMY.format(mon(sm.month)).replace(/^./,c=>c.toUpperCase()))}${sel===ms.length-1?' · in corso':''}</span><strong class="acc-mon-v" style="color:var(--${sm.result>=0?'ok':'bad'})">${DFE0.format(sm.result)}</strong>
   <small>ricavi ${DFE0.format(sm.revenue)} · costi ${DFE0.format(sm.costs)}${b.hasBalance?` · cassa e banche a fine mese ${DFE0.format(sm.liquidity)}`:''}${sp?` · ${y-1}: ${DFE0.format(sp.result)}`:''}</small></div></div>`:''}
  <div class="bars acc-bars bil-bars" role="group" aria-label="Risultato per mese">${ms.map((m,i)=>{const p=pm[i];return`<button type="button" class="bar${i===sel?' on':''}" data-bil-m="${i}" aria-pressed="${i===sel}" aria-label="${esc(DFMY.format(mon(m.month)))}: ${esc(DFE0.format(m.result))}"><i class="${m.result<0?'neg':''}" style="height:${Math.max(3,Math.abs(m.result)/max*100)}%;--i:${i}"></i>${p?`<b class="bil-tick" style="bottom:${Math.min(100,Math.abs(p.result)/max*100)}%"></b>`:''}<span>${DF3.format(mon(m.month))}</span></button>`;}).join('')}</div></div>`:'';
 // statements: groups that open on a tap to show their accounts
 const grp=(g,i,cmp)=>{const key=g.section+'|'+g.code;const on=BQ.open.has(key);return`<div class="bil-g${on?' open':''}"><button type="button" class="bil-row" data-bil-g="${esc(key)}" aria-expanded="${on}">${g.accounts.length>1?icon('chevron'):'<i class="bil-dot"></i>'}<span class="grow">${esc(g.label)}</span><b class="num">${DFE0.format(g.amount)}</b><small class="num muted">${DFE0.format(g.prev)}</small></button>
  ${on&&g.accounts.length>1?`<div class="bil-acc">${g.accounts.map(a=>`<div class="bil-row sub"><span class="grow">${esc(a.label)}</span><span class="num">${DFE0.format(a.amount)}</span><small class="num muted">${DFE0.format(a.prev)}</small></div>`).join('')}</div>`:''}</div>`;};
 const block=(title,list,secs,cmpLabel)=>`${secs.map(s=>{const gs=list.filter(g=>g.section===s);if(!gs.length)return'';const t=gs.reduce((a,g)=>a+g.amount,0),tp=gs.reduce((a,g)=>a+g.prev,0);
   return`<div class="bil-sec"><div class="bil-row head"><span class="grow">${esc(s)}</span><b class="num">${DFE0.format(t)}</b><small class="num muted">${DFE0.format(tp)}</small></div>${gs.map(grp).join('')}</div>`;}).join('')}`;
 const colHead=(a,b2)=>`<div class="bil-row cols"><span class="grow"></span><b class="num">${a}</b><small class="num muted">${b2}</small></div>`;
 const ce=b.hasIncome?`<div class="card reveal" style="--i:11"><div class="card-head"><div><h2>Conto economico</h2><p>Da gennaio a ${esc(DFMY.format(mon(b.month)))}, confrontato con gli stessi mesi del ${y-1}. Tocca una voce per vederne i conti.</p></div></div>
  ${colHead(String(y),String(y-1))}
  ${block('Ricavi',b.income,['Ricavi'])}${block('Costi',b.income,['Costi'])}
  <div class="bil-row total"><span class="grow">${b.result>=0?'Utile':'Perdita'} del periodo</span><b class="num" style="color:var(--${b.result>=0?'ok':'bad'})">${DFE0.format(b.result)}</b><small class="num muted">${DFE0.format(b.resultPrev)}</small></div>
  <p class="sub" style="margin-top:10px">Tutto il ${y-1}: ricavi ${DFE0.format(b.revenueLastYear)}, ${b.resultLastYear>=0?'utile':'perdita'} ${DFE0.format(b.resultLastYear)} prima delle scritture di chiusura.</p></div>`:'';
 const sp2=b.hasBalance?`<div class="layout-half bil-sp">
  <div class="card reveal" style="--i:12"><div class="card-head"><div><h2>Attivo</h2><p>Al ${esc(DFMY.format(mon(b.month)))} e al ${endPrev}.</p></div></div>${colHead('Oggi',endPrev)}
   ${block('Attivo',b.assets,['Immobilizzazioni','Rimanenze','Crediti','Liquidità'])}<div class="bil-row total"><span class="grow">Totale attivo</span><b class="num">${DFE0.format(b.totalAssets)}</b><small class="num muted">${DFE0.format(b.totalAssetsPrev)}</small></div></div>
  <div class="card reveal" style="--i:13"><div class="card-head"><div><h2>Passivo e netto</h2><p>Debiti, fondi e patrimonio dell’azienda.</p></div></div>${colHead('Oggi',endPrev)}
   ${block('Passivo',b.liabilities,['Patrimonio netto','Fondi e TFR','Debiti finanziari','Debiti'])}<div class="bil-row total"><span class="grow">Totale passivo e netto</span><b class="num">${DFE0.format(b.totalLiabilities)}</b><small class="num muted">${DFE0.format(b.totalLiabilitiesPrev)}</small></div></div>
 </div>`:'';
 return`${head}
 ${explain('bilancio','Numeri calcolati dalla <strong>contabilità generale di Mastro</strong> (sola lettura) a ogni lettura del server. Il risultato dell’anno è <strong>provvisorio</strong>: ammortamenti, ratei, risconti e rimanenze finali li registra il commercialista a fine esercizio. <strong>Esporta</strong> scarica conto economico e stato patrimoniale, conto per conto, in un file che si apre con Excel.')}
 ${b.note?`<div class="note reveal" style="--i:1">${icon('alert')}<span>${esc(b.note)}</span></div>`:''}
 ${kpis}${chart}${ce}${sp2}${typeof fiscalCard==='function'?fiscalCard(14):''}`;
}
document.addEventListener('click',e=>{if(page!=='accBil')return;
 const m=e.target.closest?.('[data-bil-m]');if(m){BQ.sel=+m.dataset.bilM;render(false);return;}
 const g=e.target.closest?.('[data-bil-g]');if(g){const k=g.dataset.bilG;BQ.open.has(k)?BQ.open.delete(k):BQ.open.add(k);render(false);}});

// ───────────────────────── Scadenze fiscali: the usual deadlines of an Italian S.r.l. (reminder for the office; dates that fall on
// a Saturday, Sunday or public holiday move to the next working day). The accountant's calendar always prevails.
function fiscalDeadlines(from,days){
 const out=[],y0=from.getFullYear();
 const hol=y=>{const e=easter(y);const em=new Date(e);em.setDate(e.getDate()+1);return new Set([`${y}-01-01`,`${y}-01-06`,`${y}-04-25`,`${y}-05-01`,`${y}-06-02`,`${y}-08-15`,`${y}-11-01`,`${y}-12-08`,`${y}-12-25`,`${y}-12-26`,isoDay(em)]);};
 const work=d=>{const h=hol(d.getFullYear());while(d.getDay()===0||d.getDay()===6||h.has(isoDay(d)))d.setDate(d.getDate()+1);return d;};
 for(let y=y0;y<=y0+1;y++){
  for(let m=0;m<12;m++)out.push([new Date(y,m,16),'F24 del mese','IVA mensile, ritenute su stipendi e compensi, contributi INPS']);
  const lastFeb=new Date(y,2,0);
  out.push([lastFeb,'Comunicazione liquidazioni IVA','4° trimestre dell’anno prima'],[new Date(y,4,31),'Comunicazione liquidazioni IVA','1° trimestre'],[new Date(y,8,30),'Comunicazione liquidazioni IVA','2° trimestre'],[new Date(y,10,30),'Comunicazione liquidazioni IVA','3° trimestre'],
   [new Date(y,2,16),'Certificazione Unica','Invio all’Agenzia e consegna a dipendenti e collaboratori; saldo IVA annuale'],[new Date(y,3,30),'Dichiarazione IVA annuale',''],
   [new Date(y,5,16),'IMU','Acconto'],[new Date(y,11,16),'IMU','Saldo'],[new Date(y,5,30),'Saldo e 1° acconto IRES e IRAP','Esercizio solare'],
   [new Date(y,9,31),'Dichiarazioni dei redditi, IRAP e 770',''],[new Date(y,10,30),'2° acconto IRES e IRAP',''],[new Date(y,11,27),'Acconto IVA','']);
 }
 const end=new Date(from);end.setDate(end.getDate()+days);
 return out.map(([d,t,s])=>({d:work(d),t,s})).filter(x=>x.d>=from&&x.d<=end).sort((a,b)=>a.d-b.d);
}
function easter(y){const a=y%19,b=Math.floor(y/100),c=y%100,d=Math.floor(b/4),e=b%4,f=Math.floor((b+8)/25),g=Math.floor((b-f+1)/3),h=(19*a+b-d-g+15)%30,i=Math.floor(c/4),k=c%4,l=(32+2*e+2*i-h-k)%7,m=Math.floor((a+11*h+22*l)/451),mo=Math.floor((h+l-7*m+114)/31),da=((h+l-7*m+114)%31)+1;return new Date(y,mo-1,da);}
function fiscalCard(i){const t=new Date();t.setHours(0,0,0,0);const l=fiscalDeadlines(t,75);
 return`<div class="card reveal" style="--i:${i}"><div class="card-head"><div><h2>Scadenze fiscali</h2><p>Le prossime scadenze abituali di una S.r.l., spostate al primo giorno lavorativo. Promemoria per l’ufficio: vale sempre il calendario del commercialista.</p></div></div>
  ${l.length?l.map(x=>{const n=dayDiff(x.d);return`<div class="list-row" style="cursor:default"><span class="date-chip ${n<=3?'bad':n<=10?'due':''}"><b>${x.d.getDate()}</b><span>${DF3.format(x.d)}</span></span><div class="grow"><strong>${esc(x.t)}</strong><small>${esc(x.s||'')}</small></div><span class="in-days">${n===0?'oggi':rel(n)}</span></div>`;}).join(''):empty('calendar','Nessuna scadenza','Niente nei prossimi 75 giorni.')}</div>`;}

