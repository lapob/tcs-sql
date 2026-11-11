'use strict';
/* Top Cash Studio — Contabilità. Loaded after app.js; uses its helpers and state.
   Mirror of MastroSQL (invoices, instalments, ledger balances): Mastro stays the accounting system of record,
   the app adds what the office does about each receivable (status + note, shared with the team). */

const eurA=n=>DFE.format(n||0);
const DFE=new Intl.NumberFormat('it-IT',{style:'currency',currency:'EUR',minimumFractionDigits:2,maximumFractionDigits:2});
const DFE0=new Intl.NumberFormat('it-IT',{style:'currency',currency:'EUR',maximumFractionDigits:0});
const DUE_ST=['','Sollecitato','Promesso','Incassato','Contestato'];
const DUE_CLS={Sollecitato:'info',Promesso:'acc',Incassato:'ok',Contestato:'bad'};
let A=null;
let aui={only:'open',year:''};
const ACC_PAGE={receivables:'accRec',invoices:'accInv',payables:'accPay',clients:'accCli'};

function deriveAccounting(){
 const today=new Date();today.setHours(0,0,0,0);
 const pd=s=>{if(!s)return null;const d=new Date(String(s).slice(0,10)+'T00:00:00');return isNaN(d)?null:d;};
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
 const months=[];for(let k=11;k>=0;k--){const d=new Date(y,today.getMonth()-k,1);months.push({d,v:0,n:0});}
 const mIdx=new Map(months.map((m,i)=>[m.d.getFullYear()*12+m.d.getMonth(),i]));
 for(const i of inv){if(!i.d)continue;const k=mIdx.get(i.d.getFullYear()*12+i.d.getMonth());if(k!==undefined){months[k].v+=i.net;months[k].n++;}}
 // per-client figures (used by the client drawer and the ranking)
 const perClient=new Map();const pc=c=>{if(!perClient.has(c.code))perClient.set(c.code,{inv:[],dues:[],revY:0,open:0,overdue:0});return perClient.get(c.code);};
 for(const i of inv)if(i.client){const p=pc(i.client);p.inv.push(i);if(i.d&&i.d.getFullYear()===y)p.revY+=i.net;}
 for(const x of rec)if(x.client){const p=pc(x.client);p.dues.push(x);if(x.open){p.open+=x.left;if(x.days!==null&&x.days<0)p.overdue+=x.left;}}
 const bal=new Map();for(const b of state.balances||[])bal.set(b.party+'|'+String(b.code).toUpperCase(),b);
 const top=[...perClient].map(([code,p])=>({c:D.byCode.get(code),...p})).filter(x=>x.c&&x.revY>0).sort((a,b)=>b.revY-a.revY).slice(0,8);
 const years=[...new Set(inv.filter(i=>i.d).map(i=>i.d.getFullYear()))].sort((a,b)=>b-a);
 A={inv,dues,rec,pay,recOpen,payOpen,overdue,months,perClient,bal,top,years,cl,
  revY:sum(invY,i=>i.net),revPY:sum(invPY,i=>i.net),toCollect:sum(recOpen,x=>x.left),overdueSum:sum(overdue,x=>x.left),
  toPay30:sum(payOpen.filter(x=>x.days!==null&&x.days<=30),x=>x.left),toPay:sum(payOpen,x=>x.left),has:(state.invoices||[]).length+(state.dues||[]).length>0};
}

function dueCls(x){if(!x.open)return'ok';if(x.days===null)return'';return x.days<0?'bad':x.days<=7?'due':'';}
function dueWhen(x){if(!x.open)return x.note?.status==='Incassato'?'incassata':'pagata';if(x.days===null)return'—';return x.days<0?`scaduta da ${fmt(-x.days)} gg`:x.days===0?'scade oggi':`tra ${fmt(x.days)} gg`;}

function accountingPage(){
 if(!A)deriveAccounting();
 const delta=A.revPY>0?(A.revY-A.revPY)/A.revPY*100:null;
 const maxM=Math.max(1,...A.months.map(m=>Math.abs(m.v)));
 return`${hero('La <span class="grad">contabilità</span>.','Fatture, incassi e pagamenti letti da MastroSQL. Segna solleciti e promesse di pagamento: le note restano anche dopo ogni importazione.',
  `<button class="btn need-export" data-acc-exp="dues">${icon('download')}Scadenze CSV</button><button class="btn need-export" data-acc-exp="invoices">${icon('download')}Fatture CSV</button>`)}
 ${explain('accounting','I dati arrivano da <strong>MastroSQL</strong> con <em>Impostazioni → Importa da MastroSQL</em> (sola lettura: Mastro resta il programma di contabilità). Apri una scadenza per segnare <strong>Sollecitato</strong>, <strong>Promesso</strong> o <strong>Incassato</strong> e preparare un’email di sollecito.')}
 ${A.has?'':`<div class="card reveal" style="--i:2">${empty('euro','Nessun dato contabile','Importa fatture e scadenze da MastroSQL in Impostazioni: basta un clic, i dati di Mastro non vengono modificati.')}</div>`}
 <div class="kpis">
  <div class="card kpi t-info reveal" style="--i:2"><div class="kpi-top"><span>Fatturato ${new Date().getFullYear()}</span><span class="kpi-icon">${icon('euro')}</span></div><div class="kpi-value acc-v">${DFE0.format(A.revY)}</div><div class="kpi-note">${delta===null?'imponibile, note di credito dedotte':`<span style="color:var(--${delta>=0?'ok':'bad'})">${delta>=0?'▲':'▼'} ${Math.abs(delta).toFixed(1).replace('.',',')}%</span> rispetto allo stesso periodo ${new Date().getFullYear()-1}`}</div></div>
  <div class="card kpi reveal" style="--i:3" data-acc-tab="receivables" tabindex="0"><div class="kpi-top"><span>Da incassare</span><span class="kpi-icon">${icon('download')}</span></div><div class="kpi-value acc-v">${DFE0.format(A.toCollect)}</div><div class="kpi-note">${plur(A.recOpen.length,'rata aperta','rate aperte')}</div></div>
  <div class="card kpi ${A.overdue.length?'t-due':'t-ok'} reveal" style="--i:4" data-acc-tab="receivables" tabindex="0"><div class="kpi-top"><span>Scaduto</span><span class="kpi-icon">${icon('alert')}</span></div><div class="kpi-value acc-v">${DFE0.format(A.overdueSum)}</div><div class="kpi-note">${A.overdue.length?`${plur(new Set(A.overdue.map(x=>x.partyCode)).size,'cliente da sollecitare','clienti da sollecitare')}`:'nessun ritardo'}</div></div>
  <div class="card kpi reveal" style="--i:5" data-acc-tab="payables" tabindex="0"><div class="kpi-top"><span>Da pagare · 30 gg</span><span class="kpi-icon">${icon('upload')}</span></div><div class="kpi-value acc-v">${DFE0.format(A.toPay30)}</div><div class="kpi-note">${DFE0.format(A.toPay)} in totale ai fornitori</div></div></div>
 ${A.inv.length?`<div class="card reveal" style="--i:6"><div class="card-head"><div><h2>Fatturato mensile</h2><p>Ultimi 12 mesi, imponibile al netto delle note di credito.</p></div></div>
  <div class="bars acc-bars">${A.months.map((m,i)=>`<div class="bar"><i class="${m.v<0?'neg':''}" style="height:${Math.max(3,Math.abs(m.v)/maxM*100)}%;--i:${i}" data-v="${esc(DFE0.format(m.v))} · ${m.n} doc."></i><span>${DF3.format(m.d)}</span></div>`).join('')}</div></div>`:''}
 ${A.has?accFocus():''}`;
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
 ${A.has?T[2]():`<div class="card reveal" style="--i:2">${empty('euro','Nessun dato contabile','Importa fatture e scadenze da MastroSQL in Impostazioni: basta un clic, i dati di Mastro non vengono modificati.')}</div>`}`;
}

function dueRows(list,cols){
 return list.map((x,k)=>`<tr class="reveal" style="--i:${Math.min(k,14)}" data-due="${esc(x.id)}" tabindex="0">
  <td><div class="who"><span class="mark" style="--h:${hue(x.name)}">${esc(initials(x.name))}</span><div><strong>${hl(x.name,ui.q)}</strong><small class="mono">${esc(x.partyCode)}${x.payment?' · '+esc(x.payment):''}</small></div></div></td>
  <td class="mono"><span class="muted">doc.</span> ${x.docNumber?hl(x.docNumber,ui.q):'—'}<br><small class="muted">${x.docD?dShort(x.docD):''}</small></td>
  <td>${x.d?dShort(x.d):'—'}<br><small style="color:var(--${dueCls(x)==='bad'?'bad':dueCls(x)==='due'?'due':'text-3'})">${dueWhen(x)}</small></td>
  <td class="mono"><strong>${eurA(x.left)}</strong>${x.paid>0&&x.left>0?`<br><small class="muted">su ${eurA(x.amount)}</small>`:''}</td>
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
 <div class="table-wrap"><table class="t-acc"><thead><tr><th>Cliente</th><th>Documento</th><th>Scadenza</th><th>Residuo</th><th>Stato</th><th></th></tr></thead><tbody>
 ${dueRows(rows,true)||`<tr><td colspan="6">${empty('check','Nessuna rata','Nessun incasso corrisponde ai filtri: ottimo segno.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}
function payablesTab(){
 const list=filterDues(A.pay);const rows=pageOf(list,16);
 return`<div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Pagamenti ai fornitori<span class="count">${fmt(list.length)}</span></h2><p>Residuo ${eurA(list.reduce((s,x)=>s+x.left,0))} · in ordine di scadenza.</p></div></div>
 <div class="toolbar">${search('Cerca fornitore, documento…')}${onlySel(false)}</div>
 <div class="table-wrap"><table class="t-acc"><thead><tr><th>Fornitore</th><th>Documento</th><th>Scadenza</th><th>Residuo</th><th>Stato</th><th></th></tr></thead><tbody>
 ${dueRows(rows,true)||`<tr><td colspan="6">${empty('check','Nessun pagamento','Nessuna scadenza fornitore corrisponde ai filtri.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}
function invoicesTab(){
 const q=norm(ui.q);let list=A.inv;if(q)list=list.filter(i=>i.search.includes(q));
 if(aui.year)list=list.filter(i=>i.d&&String(i.d.getFullYear())===aui.year);
 const rows=pageOf(list,18);const tot=list.reduce((s,i)=>s+i.net,0);
 return`<div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Fatture e note di credito<span class="count">${fmt(list.length)}</span></h2><p>Imponibile ${eurA(tot)}${list.length?` · ${eurA(list.reduce((s,i)=>s+i.gross,0))} con IVA`:''}.</p></div></div>
 <div class="toolbar">${search('Cerca numero, cliente, pagamento…')}<select class="select" id="f-acc-year"><option value="">Tutti gli anni</option>${A.years.map(y=>`<option${aui.year===String(y)?' selected':''}>${y}</option>`).join('')}</select></div>
 <div class="table-wrap"><table class="t-acc"><thead><tr><th>Documento</th><th>Cliente</th><th>Imponibile</th><th>IVA</th><th>Totale</th><th>Pagamento</th></tr></thead><tbody>
 ${rows.map((i,k)=>`<tr class="reveal" style="--i:${Math.min(k,14)}" ${i.client?`data-client="${esc(i.client.code)}" tabindex="0"`:''}><td><strong class="mono">${hl(i.number,ui.q)}</strong> ${i.sign<0?'<span class="badge acc">Nota di credito</span>':''}<br><small class="muted">${i.d?dShort(i.d):esc(i.date)}</small></td>
  <td><strong style="font-weight:560">${hl(i.name,ui.q)}</strong><br><small class="muted mono">${esc(i.clientCode)}</small></td><td class="mono">${eurA(i.net)}</td><td class="mono muted">${eurA(i.sign*Math.abs(i.vat||0))}</td><td class="mono"><strong>${eurA(i.gross)}</strong></td><td>${esc(i.payment||'—')}</td></tr>`).join('')
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
 <div class="table-wrap"><table class="t-acc"><thead><tr><th>Cliente</th><th>Fatturato ${new Date().getFullYear()}</th><th>Da incassare</th><th>Scaduto</th><th>Ultima fattura</th><th></th></tr></thead><tbody>
 ${rows.map((x,k)=>`<tr class="reveal" style="--i:${Math.min(k,14)}" data-client="${esc(x.c.code)}" tabindex="0"><td><div class="who">${markEl(x.c.name,x.c.code)}<div><strong>${hl(x.c.name,ui.q)}</strong><small>${esc(x.c.city||'')}</small></div></div></td>
  <td class="mono">${eurA(x.revY)}</td><td class="mono">${x.open?eurA(x.open):'<span class="muted">—</span>'}</td><td class="mono" style="color:var(--${x.overdue?'bad':'text-3'})">${x.overdue?'<strong>'+eurA(x.overdue)+'</strong>':'—'}</td>
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
 const t=e.target.closest('[data-acc-tab],[data-due],[data-acc-exp],[data-acc-client]');if(!t)return;const ds=t.dataset;
 if(ds.accTab){if(ds.accTab!=='invoices')aui.only=aui.only||'open';return go(ACC_PAGE[ds.accTab]||'accRec');}
 if(ds.due)return openDue(ds.due);
 if(ds.accExp)return send(ds.accExp==='dues'?'exportDues':'exportInvoices');
 if(ds.accClient){const c=D.byCode.get(ds.accClient);closeDrawer();aui.only='all';go('accRec');ui.q=c?.name||'';render(false);}
});
document.addEventListener('keydown',e=>{if(e.key!=='Enter')return;const r=e.target.closest?.('tr[data-due]');if(r){e.preventDefault();openDue(r.dataset.due);}});
document.addEventListener('change',e=>{const id=e.target.id;
 if(id==='f-acc-only'){aui.only=e.target.value;ui.p=1;render(false);}
 else if(id==='f-acc-year'){aui.year=e.target.value;ui.p=1;render(false);}
});
