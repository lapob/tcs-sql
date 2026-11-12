/* Priorità: what to fix and what to sell, worked out by the server from Mastro every hour (same lists on every app). */
'use strict';
const INS_TABS=[
 ['unbilled','Da fatturare','file'],
 ['sdi','Fatture SDI','alert'],
 ['payers','Pagano tardi','clock'],
 ['churn','Clienti in calo','users'],
 ['opps','Da vendere','sparkle']];
const INS_OPP=[['Contratto','Contratti da rinnovare'],['Verifica','Verifiche periodiche'],['Sostituzione','Registratori da sostituire']];
let iui={tab:'unbilled',opp:'Contratto'};

function insData(){
 const s=state.insights||{};const L=k=>Array.isArray(s[k])?s[k]:[];
 const opps=L('opps');
 return{at:s.at,unbilled:L('unbilled'),sdi:L('sdi'),payers:L('payers'),churn:L('churn'),opps,regPrice:s.registerPrice||0,
  byKind:Object.fromEntries(INS_OPP.map(([k])=>[k,opps.filter(o=>o.kind===k)]))};
}
/** count + money of each tab (the summary chips and the Riepilogo strip use the same figures) */
function insStats(I){
 const sum=(l,f)=>l.reduce((s,x)=>s+(f(x)||0),0);
 const notSent=I.sdi.filter(x=>x.status!=='Mancata consegna');
 return{
  unbilled:{n:I.unbilled.length,v:sum(I.unbilled,x=>x.amount),tone:I.unbilled.length?'due':'ok',note:'interventi chiusi senza fattura'},
  sdi:{n:I.sdi.length,v:sum(I.sdi,x=>Math.abs(x.total)),tone:notSent.length?'bad':I.sdi.length?'due':'ok',note:notSent.length?`${plur(notSent.length,'non inviata','non inviate')} o scartate`:'consegna non riuscita'},
  payers:{n:I.payers.length,v:sum(I.payers,x=>x.overdue),tone:I.payers.some(p=>p.grade==='C')?'bad':I.payers.length?'due':'ok',note:`${fmt(I.payers.filter(p=>p.grade==='C').length)} critici`},
  churn:{n:I.churn.length,v:sum(I.churn,x=>x.lost),tone:I.churn.length?'due':'ok',note:'fatturato perso in un anno'},
  opps:{n:I.opps.length,v:sum(I.opps,x=>x.value),tone:I.opps.length?'info':'ok',note:'contratti, verifiche, sostituzioni'}};
}
const insClient=code=>{if(!A)deriveAccounting();return A.cl(code);};
const insWhen=s=>{if(!s)return'—';const d=new Date(String(s).slice(0,10)+'T00:00:00');return isNaN(d)?esc(s):dShort(d);};
const insDays=s=>{const d=new Date(String(s||'').slice(0,10)+'T00:00:00');if(isNaN(d))return null;const t=new Date();t.setHours(0,0,0,0);return Math.round((d-t)/864e5);};

/** one line of a list: who, what, when, how much; opens the customer */
function insRow(k,{code,name,title,sub,amount,amountNote,tone,badge}){
 const c=insClient(code);
 return`<div class="list-row ins-row reveal" style="--i:${Math.min(k,12)}" ${c?`data-client="${esc(c.code)}" tabindex="0"`:''}>
  ${markEl(name||code,code)}<div class="grow"><strong>${hl(title,ui.q)}</strong><small>${hl(name||code,ui.q)}${sub?' · '+sub:''}</small></div>
  ${badge?`<span class="badge ${tone||''}">${esc(badge)}</span>`:''}
  <div class="ins-amt"><strong>${amount}</strong>${amountNote?`<small${amountNote===badge?' class="m-only"':''}>${amountNote}</small>`:''}</div></div>`;
}

function insightsPage(){
 if(!A)deriveAccounting();
 const I=insData(),S=insStats(I);const tab=INS_TABS.some(t=>t[0]===iui.tab)?iui.tab:'unbilled';
 const at=I.at?new Date(I.at):null;
 const q=norm(ui.q);const f=l=>q?l.filter(x=>norm([x.name,x.title,x.number,x.what,x.clientCode,x.technician].join(' ')).includes(q)):l;
 let body='';
 if(tab==='unbilled'){
  const l=f(I.unbilled);const rows=pageOf(l,14);
  body=`<div class="card-head"><div><h2>Interventi da fatturare<span class="count">${fmt(l.length)}</span></h2><p>Chiusi con un importo ma senza documento in Mastro, o fatti con l’app e senza una fattura al cliente da allora. Valore ${eurA(l.reduce((s,x)=>s+x.amount,0))}.</p></div></div>
  <div class="toolbar">${search('Cerca cliente, intervento, tecnico…')}</div>
  <div class="list">${rows.map((x,k)=>insRow(k,{code:x.clientCode,name:x.name,title:x.title,sub:`${insWhen(x.date)}${x.technician?' · '+esc(x.technician):''}`,amount:x.amount?eurA(x.amount):'—',amountNote:x.source==='app'?'dall’app':'da Mastro',badge:x.source==='app'?'Rapportino':'Mastro',tone:x.source==='app'?'info':''})).join('')
   ||empty('check','Tutto fatturato','Nessun intervento chiuso resta senza fattura.')}</div>${pager(l.length,14)}`;
 }else if(tab==='sdi'){
  const l=f(I.sdi);const rows=pageOf(l,14);const mc=l.filter(x=>x.status==='Mancata consegna').length;
  body=`<div class="card-head"><div><h2>Fatture elettroniche da controllare<span class="count">${fmt(l.length)}</span></h2><p>Non inviate allo SDI o scartate: vanno sistemate e inviate entro 12 giorni. <strong>Mancata consegna</strong>${mc?` (${fmt(mc)})`:''}: la fattura è valida ma va detto al cliente che la trova nel suo cassetto fiscale.</p></div></div>
  <div class="toolbar">${search('Cerca numero o cliente…')}</div>
  <div class="list">${rows.map((x,k)=>{const days=-(insDays(x.date)??0);const late=x.status!=='Mancata consegna';
   return insRow(k,{code:x.clientCode,name:x.name,title:`Fattura ${esc(x.number)}`,sub:`${insWhen(x.date)}${late&&days>0?` · da ${plur(days,'giorno','giorni')}`:''}`,amount:eurA(x.total),amountNote:x.status,badge:x.status,tone:x.status==='Mancata consegna'?'due':'bad'});}).join('')
   ||empty('check','Tutto consegnato','Ogni fattura elettronica è stata consegnata al cliente.')}</div>${pager(l.length,14)}`;
 }else if(tab==='payers'){
  const l=f(I.payers);const rows=pageOf(l,14);const G={C:['bad','Critico'],B:['due','Da seguire'],A:['info','Lieve']};
  body=`<div class="card-head"><div><h2>Clienti che pagano in ritardo<span class="count">${fmt(l.length)}</span></h2><p>Rate scadute e non incassate. <strong>Critico</strong>: oltre 60 giorni o 3.000 €; <strong>da seguire</strong>: oltre 30 giorni o 800 €. Prima i più a rischio.</p></div></div>
  <div class="toolbar">${search('Cerca cliente…')}</div>
  <div class="list">${rows.map((x,k)=>insRow(k,{code:x.clientCode,name:x.name,title:x.name,sub:`${plur(x.rates,'rata scaduta','rate scadute')} · la più vecchia da ${plur(x.oldestDays,'giorno','giorni')}${x.revenue12>0?` · fatturato 12 mesi ${eurA(x.revenue12)}`:''}`,amount:eurA(x.overdue),amountNote:x.open>x.overdue+0.5?`su ${eurA(x.open)} aperti`:'scaduti',badge:G[x.grade]?.[1],tone:G[x.grade]?.[0]})).join('')
   ||empty('check','Nessun ritardo','Tutti i clienti sono in regola.')}</div>${pager(l.length,14)}`;
 }else if(tab==='churn'){
  const l=f(I.churn);const rows=pageOf(l,14);
  body=`<div class="card-head"><div><h2>Clienti che comprano meno<span class="count">${fmt(l.length)}</span></h2><p>Ultimi 12 mesi contro i 12 precedenti: almeno dimezzati, da almeno 500 €. Una telefonata adesso vale più di un cliente nuovo.</p></div></div>
  <div class="toolbar">${search('Cerca cliente…')}</div>
  <div class="list">${rows.map((x,k)=>{const p=x.prev12>0?Math.round((x.last12-x.prev12)/x.prev12*100):0;
   return insRow(k,{code:x.clientCode,name:x.name,title:x.name,sub:`${eurA(x.prev12)} → ${eurA(x.last12)} · ultima fattura ${insWhen(x.lastInvoice)}`,amount:'−'+eurA(x.lost).replace('-',''),amountNote:`${p}%`,badge:x.last12<=0?'Fermo':'In calo',tone:x.last12<=0?'bad':'due'});}).join('')
   ||empty('check','Nessun calo','Nessun cliente ha dimezzato gli acquisti.')}</div>${pager(l.length,14)}`;
 }else{
  const kind=INS_OPP.some(o=>o[0]===iui.opp)?iui.opp:'Contratto';const l=f(I.byKind[kind]||[]);const rows=pageOf(l,14);
  const note={Contratto:'Contratti di assistenza scaduti negli ultimi 4 mesi o in scadenza nei prossimi 2: rinnovo e fattura.',Verifica:'Verifiche periodiche (ogni 2 anni) scadute o in scadenza nei prossimi 60 giorni.',Sostituzione:`Registratori in servizio da oltre 6 anni: proponi il nuovo${I.regPrice?` (prezzo medio di vendita ${eurA(I.regPrice)})`:''}.`}[kind];
  body=`<div class="card-head"><div><h2>Da vendere<span class="count">${fmt(I.opps.length)}</span></h2><p>${note}</p></div></div>
  <div class="toolbar"><div class="seg" role="tablist">${INS_OPP.map(([k,lb])=>`<button role="tab" class="${k===kind?'active':''}" data-ins-opp="${k}">${lb} · ${fmt((I.byKind[k]||[]).length)}</button>`).join('')}</div>${search('Cerca cliente, modello, matricola…')}</div>
  <div class="list">${rows.map((x,k)=>{const d=insDays(x.when);const past=d!==null&&d<0;
   const sub=kind==='Sostituzione'?`${esc(x.what)} · installato ${insWhen(x.when)}`:`${esc(x.what)} · ${past?'scaduto':'scade'} ${insWhen(x.when)}`;
   return insRow(k,{code:x.clientCode,name:x.name,title:x.name,sub,amount:x.value?eurA(x.value):'—',amountNote:kind==='Contratto'?'canone':kind==='Sostituzione'?'stima':'',badge:kind==='Sostituzione'?`${Math.floor(-(d??0)/365)} anni`:past?'Scaduto':'In scadenza',tone:past?'bad':'due'});}).join('')
   ||empty('check','Niente da proporre','Nessuna occasione in questa categoria.')}</div>${pager(l.length,14)}`;
 }
 return`${hero('Le <span class="grad">priorità</span>.',`Cosa sistemare e cosa vendere, ricavato da Mastro in automatico.${at&&!isNaN(at)?` <span class="sal-sync"><i></i>Aggiornato alle ${at.toLocaleTimeString('it-IT',{hour:'2-digit',minute:'2-digit'})}</span>`:''}`,WEB&&['admin','owner'].includes(me?.role)?`<button class="btn" data-mastro-sync>${icon('sync')}Aggiorna ora</button>`:'')}
 <div class="ins-tiles reveal" style="--i:1" role="tablist" aria-label="Priorità">${INS_TABS.map(([k,l,ic],i)=>{const s=S[k];
  return`<button role="tab" aria-selected="${k===tab}" class="ins-tile t-${s.tone}${k===tab?' on':''}" data-ins-tab="${k}" style="--i:${i}"><span class="ins-ic">${icon(ic)}</span><span class="ins-l">${l}</span><strong>${fmt(s.n)}</strong><small>${s.v?eurA(s.v)+' · ':''}${esc(s.note)}</small></button>`;}).join('')}</div>
 <div class="card ins-body reveal" style="--i:2">${body}</div>`;
}
/** the strip on Riepilogo: five numbers, a tap opens the list */
function insStrip(){
 if(!state.insights)return'';const S=insStats(insData());
 return`<div class="card reveal ins-strip-card" style="--i:6"><div class="card-head"><div><h2>Oggi da guardare</h2><p>Ricavato da Mastro in automatico: tocca per aprire l’elenco.</p></div><button class="btn sm ghost" data-ins-tab="unbilled">Tutte${icon('chevron')}</button></div>
 <div class="ins-strip">${INS_TABS.map(([k,l,ic])=>`<button class="ins-mini t-${S[k].tone}" data-ins-tab="${k}"><span class="ins-ic">${icon(ic)}</span><span><strong>${fmt(S[k].n)}</strong><small>${l}</small></span></button>`).join('')}</div></div>`;
}
document.addEventListener('click',e=>{
 const t=e.target.closest('[data-ins-tab],[data-ins-opp]');if(!t)return;
 if(t.dataset.insTab){iui.tab=t.dataset.insTab;ui.q='';}else iui.opp=t.dataset.insOpp;ui.p=1;
 if(page!=='accOpp')return go('accOpp');
 typeof morph==='function'?morph():render(false);
});
document.addEventListener('keydown',e=>{if(e.key!=='Enter')return;const r=e.target.closest?.('.ins-row[data-client]');if(r){e.preventDefault();r.click();}});
// "Aggiorna ora": the server reads Mastro at once (read-only) instead of waiting for the hourly read
document.addEventListener('click',async e=>{const b=e.target.closest('[data-mastro-sync]');if(!b||b.disabled)return;
 b.disabled=true;b.classList.add('busy');const was=b.innerHTML;b.innerHTML=`${icon('sync')}Lettura di Mastro…`;
 try{const r=await post('/api/mastro/sync',{});toast(r.message||'Dati aggiornati da Mastro');}catch(x){toast(x.message,'alert');}
 finally{b.disabled=false;b.classList.remove('busy');b.innerHTML=was;}});
