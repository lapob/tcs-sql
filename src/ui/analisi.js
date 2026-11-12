/* Analisi: cash forecast, ABC customers, agents, technicians, registers, discounts and supplier costs.
   Worked out by the server from Mastro at every read (same figures on the site, the Windows app and the phones). */
'use strict';
const ANA_TABS=[
 ['cash','Incassi previsti','euro'],
 ['abc','Clienti ABC','users'],
 ['agents','Agenti','star'],
 ['techs','Tecnici','tool'],
 ['machines','Macchine','register'],
 ['disc','Sconti','bag'],
 ['costs','Costi fornitori','box']];
const ANA_E0=new Intl.NumberFormat('it-IT',{style:'currency',currency:'EUR',maximumFractionDigits:0,useGrouping:'always'});
/** whole euros from 100 up (the analyses are estimates), cents below */
const anaE=n=>Math.abs(n||0)>=100?ANA_E0.format(n):eurA(n);
let anaUi={tab:'cash',abc:'A',disc:'clients',mach:'models'};

function anaData(){const a=state.analysis||{};const L=k=>Array.isArray(a[k])?a[k]:[];
 return{at:a.at,forecast:L('forecast'),fIn:a.forecastIn||0,fOut:a.forecastOut||0,overdue:a.overdueExpected||0,abc:L('abc'),classes:L('abcClasses'),agents:L('agents'),techs:L('techs'),
  machines:L('machines'),models:L('models'),discClients:L('discountClients'),discArticles:L('discountArticles'),discTotal:a.discountTotal||0,costs:L('costs'),costImpact:a.costImpact||0};}
function anaStats(X){const up=X.costs.filter(c=>c.impact>0).length;const topAgent=X.agents[0];
 return{
  cash:{n:anaE(X.fIn),tone:X.fIn>=X.fOut?'ok':'due',note:`in 13 settimane · da pagare ${anaE(X.fOut)}`},
  abc:{n:fmt((X.classes.find(c=>c.class==='A')||{}).clients||0),tone:'info',note:'clienti A: l’80% del fatturato'},
  agents:{n:fmt(X.agents.length),tone:'info',note:topAgent?`primo: ${topAgent.name}`:'nessun dato'},
  techs:{n:fmt(X.techs.length),tone:'info',note:'lavori chiusi in 12 mesi'},
  machines:{n:fmt(X.models.length),tone:'info',note:'modelli con valore annuo'},
  disc:{n:anaE(X.discTotal),tone:X.discTotal>0?'due':'ok',note:'sotto il prezzo abituale, 12 mesi'},
  costs:{n:fmt(up),tone:up?'bad':'ok',note:X.costImpact>0?`rincari: ${anaE(X.costImpact)} l’anno`:'nessun rincaro'}};}
const anaPct=(a,b)=>b>0?Math.round((a-b)/b*100):null;
const anaTrend=(a,b)=>{const p=anaPct(a,b);return p===null?'':`<span class="ana-tr ${p<0?'down':'up'}">${p>0?'+':''}${p}%</span>`;};
/** a plain row (no customer to open) */
function anaRow(k,{title,sub,amount,note,badge,tone,code}){
 if(code)return insRow(k,{code,name:'',title,sub,amount,amountNote:note,badge,tone});
 return`<div class="list-row ins-row reveal" style="--i:${Math.min(k,12)}">${markEl(title,title)}<div class="grow"><strong>${hl(title,ui.q)}</strong><small>${sub||''}</small></div>
  ${badge?`<span class="badge ${tone||''}">${esc(badge)}</span>`:''}<div class="ins-amt"><strong>${amount}</strong>${note?`<small>${note}</small>`:''}</div></div>`;}

function analisiPage(){
 if(!A)deriveAccounting();
 const X=anaData(),S=anaStats(X);const tab=ANA_TABS.some(t=>t[0]===anaUi.tab)?anaUi.tab:'cash';
 const q=norm(ui.q);const f=(l,keys)=>q?l.filter(x=>norm(keys.map(k=>x[k]).join(' ')).includes(q)):l;
 let body='';
 if(!state.analysis)body=empty('chart','Analisi in arrivo','Si calcolano alla prossima lettura di Mastro (ogni ora, o con “Aggiorna ora” nelle Priorità).');
 else if(tab==='cash'){
  const max=Math.max(1,...X.forecast.map(w=>Math.max(w.in,w.out)));
  body=`<div class="card-head"><div><h2>Incassi e pagamenti delle prossime 13 settimane</h2><p>Le rate dei clienti pesate su come pagano oggi: chi è in regola incassa quasi sempre alla scadenza, chi è in ritardo più tardi e non sempre (come nelle Priorità). I pagamenti ai fornitori alla loro scadenza; quelli già scaduti nella prima settimana. Degli scaduti dei clienti si stimano ${anaE(X.overdue)} in arrivo.</p></div></div>
  <div class="ana-kpis">${[['Incassi attesi',X.fIn,'ok'],['Pagamenti',X.fOut,'bad'],['Saldo',X.fIn-X.fOut,X.fIn>=X.fOut?'ok':'bad']].map(([l,v,t])=>`<div class="ana-kpi t-${t}"><small>${l}</small><strong>${anaE(v)}</strong></div>`).join('')}</div>
  <div class="ana-weeks" role="list">${X.forecast.map((w,i)=>{const d=new Date(w.start+'T00:00:00');
   return`<div class="ana-week reveal" style="--i:${Math.min(i,12)}" role="listitem" data-tip="Settimana dal ${dShort(d)}: incassi ${anaE(w.in)} (${anaE(w.inSure)} da clienti in regola), pagamenti ${anaE(w.out)}, saldo progressivo ${anaE(w.cumulative)}">
    <div class="ana-bars"><i class="in" style="--h:${(w.in/max*100).toFixed(1)}%"></i><i class="out" style="--h:${(w.out/max*100).toFixed(1)}%"></i></div>
    <small>${i===0?'Questa':d.toLocaleDateString('it-IT',{day:'numeric',month:'short'})}</small><b class="${w.net<0?'neg':''}">${w.net<0?'−':'+'}${anaE(Math.abs(w.net))}</b></div>`;}).join('')}</div>
  <p class="sub ana-legend"><i class="in"></i>Incassi attesi <i class="out"></i>Pagamenti ai fornitori</p>`;
 }else if(tab==='abc'){
  const cls=['A','B','C'].includes(anaUi.abc)?anaUi.abc:'A';const l=f(X.abc.filter(x=>x.class===cls),['name','clientCode']);const rows=pageOf(l,14);
  const C=Object.fromEntries(X.classes.map(c=>[c.class,c]));
  body=`<div class="card-head"><div><h2>Clienti per fatturato<span class="count">${fmt(l.length)}</span></h2><p>Ultimi 12 mesi, al netto delle note di credito. <strong>A</strong>: i clienti che fanno l’80% del fatturato, da seguire per primi; <strong>B</strong>: il 15% successivo; <strong>C</strong>: il resto.</p></div></div>
  <div class="toolbar"><div class="seg" role="tablist">${['A','B','C'].map(k=>`<button role="tab" class="${k===cls?'active':''}" data-ana-abc="${k}">Classe ${k} · ${fmt(C[k]?.clients||0)} · ${C[k]?.share||0}%</button>`).join('')}</div>${search('Cerca cliente…')}</div>
  ${(C[cls]?.clients||0)>l.length&&!q?`<p class="sub" style="margin:-4px 0 10px">I primi ${fmt(l.length)} per fatturato su ${fmt(C[cls].clients)}.</p>`:''}<div class="list">${rows.map((x,k)=>insRow(k,{code:x.clientCode,name:x.name,title:x.name,sub:`${fmt(x.rank)}° · ${String(x.share).replace('.',',')}% del fatturato${x.prev>0?' · anno prima '+anaE(x.prev):' · nuovo'}`,amount:anaE(x.revenue),amountNote:anaTrend(x.revenue,x.prev)||'nuovo',badge:'Classe '+x.class,tone:x.class==='A'?'ok':x.class==='B'?'info':''})).join('')||empty('users','Nessun cliente','Nessun cliente in questa classe.')}</div>${pager(l.length,14)}`;
 }else if(tab==='agents'){
  const l=f(X.agents,['name','topFamily']);const tot=X.agents.reduce((s,a)=>s+a.revenue,0);
  body=`<div class="card-head"><div><h2>Vendite per agente<span class="count">${fmt(l.length)}</span></h2><p>Fatturato degli ultimi 12 mesi sulle fatture di ciascun agente in Mastro, con il margine sugli articoli che hanno un costo e gli sconti fatti rispetto al prezzo abituale.</p></div></div>
  <div class="toolbar">${search('Cerca agente…')}</div>
  <div class="list">${l.map((x,k)=>anaRow(k,{title:x.name,sub:`${plur(x.clients,'cliente','clienti')} · margine ${anaE(x.margin)} (${String(x.marginPct).replace('.',',')}%) · più venduto: ${esc(x.topFamily||'—')}${x.discounts>0?` · sconti ${anaE(x.discounts)}`:''}`,amount:anaE(x.revenue),note:`${tot>0?Math.round(x.revenue/tot*100)+'% · ':''}${anaTrend(x.revenue,x.prev)||'—'}`})).join('')||empty('user','Nessun agente','Mastro non indica agenti sulle fatture.')}</div>`;
 }else if(tab==='techs'){
  const l=f(X.techs,['name']);const hours=X.techs.some(t=>t.hours>0);
  body=`<div class="card-head"><div><h2>Lavoro dei tecnici<span class="count">${fmt(l.length)}</span></h2><p>Interventi chiusi negli ultimi 12 mesi (storico Mastro e app), con l’importo degli interventi; le verifiche periodiche sono contate a parte.</p></div></div>
  <div class="toolbar">${search('Cerca tecnico…')}</div>
  <div class="list">${l.map((x,k)=>anaRow(k,{title:x.name,sub:`${plur(x.jobs,'intervento','interventi')} · ${plur(x.verifications,'verifica','verifiche')} · ${plur(x.clients,'cliente','clienti')}${hours?` · ${String(x.hours).replace('.',',')} ore`:''}`,amount:anaE(x.amount),note:x.jobs?`${anaE(x.amount/x.jobs)} a intervento`:'',badge:x.open?`${fmt(x.open)} aperti`:'',tone:x.open>40?'due':'info'})).join('')||empty('tool','Nessun dato','Nessun intervento chiuso negli ultimi 12 mesi.')}</div>`;
 }else if(tab==='machines'){
  const v=anaUi.mach==='list'?'list':'models';
  const seg=`<div class="seg" role="tablist">${[['models','Per modello'],['list','Per registratore']].map(([k,lb])=>`<button role="tab" class="${k===v?'active':''}" data-ana-mach="${k}">${lb}</button>`).join('')}</div>`;
  const head=`<div class="card-head"><div><h2>Quanto rende ogni registratore in un anno</h2><p>Canone del contratto riportato all’anno, interventi chiusi su quella macchina negli ultimi 12 mesi e verificazione (ogni 2 anni, metà all’anno). Stima dai dati di Mastro: le fatture non indicano la macchina.</p></div></div>`;
  if(v==='models'){const l=f(X.models,['model']);
   body=`${head}<div class="toolbar">${seg}${search('Cerca modello…')}</div><div class="list">${l.map((x,k)=>anaRow(k,{title:x.model,sub:`${plur(x.machines,'registratore','registratori')} attivi · in media ${anaE(x.average)} l’anno ciascuno`,amount:anaE(x.total),note:'l’anno'})).join('')||empty('register','Nessun dato','')}</div>`;}
  else{const l=f(X.machines,['name','model','serial']);const rows=pageOf(l,14);
   body=`${head}<div class="toolbar">${seg}${search('Cerca cliente, modello, matricola…')}</div><div class="list">${rows.map((x,k)=>insRow(k,{code:x.clientCode,name:x.name,title:x.name,sub:`${esc(x.model)}${x.serial?' · matr. '+esc(x.serial):''} · canone ${anaE(x.contract)} · interventi ${anaE(x.interventions)}${x.verification?' · verifica '+anaE(x.verification):''}`,amount:anaE(x.total),amountNote:'l’anno'})).join('')||empty('register','Nessun dato','')}</div>${pager(l.length,14)}`;}
 }else if(tab==='disc'){
  const v=anaUi.disc==='articles'?'articles':'clients';const src=v==='clients'?X.discClients:X.discArticles;const l=f(src,['name','sub','key']);const rows=pageOf(l,14);
  body=`<div class="card-head"><div><h2>Sconti concessi<span class="count">${anaE(X.discTotal)}</span></h2><p>In Mastro gli sconti non sono scritti a parte: qui ogni vendita è confrontata con il <strong>prezzo abituale</strong> dello stesso articolo negli ultimi 12 mesi. Conta solo chi ha pagato almeno il 10% in meno, e solo per articoli venduti a prezzo stabile (niente contratti, noleggi o servizi).</p></div></div>
  <div class="toolbar"><div class="seg" role="tablist">${[['clients','Per cliente'],['articles','Per articolo']].map(([k,lb])=>`<button role="tab" class="${k===v?'active':''}" data-ana-disc="${k}">${lb}</button>`).join('')}</div>${search(v==='clients'?'Cerca cliente o agente…':'Cerca articolo…')}</div>
  <div class="list">${rows.map((x,k)=>v==='clients'?insRow(k,{code:x.key,name:x.name,title:x.name,sub:`agente ${esc(x.sub)} · ${plur(x.lines,'vendita','vendite')} scontate per ${anaE(x.revenue)}`,amount:anaE(x.given),amountNote:'di sconto'})
   :anaRow(k,{title:x.name,sub:`${esc(x.key)} · ${esc(x.sub)} · ${plur(x.lines,'vendita','vendite')} scontate per ${anaE(x.revenue)}`,amount:anaE(x.given),note:'di sconto'})).join('')||empty('check','Nessuno sconto','Tutto venduto al prezzo abituale.')}</div>${pager(l.length,14)}`;
 }else{
  const l=f(X.costs,['name','supplier','code']);const rows=pageOf(l,14);
  body=`<div class="card-head"><div><h2>Costi d’acquisto cambiati<span class="count">${fmt(l.length)}</span></h2><p>Articoli il cui costo è cambiato in Mastro negli ultimi 12 mesi, con il fornitore. <strong>Impatto</strong>: quanto costa in più (o in meno) su quello che hai venduto in un anno. Se il margine è sceso, è il momento di aggiornare il prezzo di vendita. Le correzioni di cifre sbagliate non sono contate.</p></div></div>
  <div class="toolbar">${search('Cerca articolo o fornitore…')}</div>
  <div class="list">${rows.map((x,k)=>{const up=x.new>x.old;
   return anaRow(k,{title:x.name,sub:`${esc(x.supplier||'fornitore non indicato')} · ${insWhen(x.date)} · costo ${anaE(x.old)} → ${anaE(x.new)}${x.price?` · venduto a ${anaE(x.price)}, margine ${String(x.marginBefore).replace('.',',')}% → ${String(x.marginNow).replace('.',',')}%`:''}`,amount:x.impact?(x.impact>0?'+':'−')+anaE(Math.abs(x.impact)):'—',note:x.qty12?'sull’anno':'non venduto',badge:`${x.pct>0?'+':''}${String(x.pct).replace('.',',')}%`,tone:up?(x.marginNow<15?'bad':'due'):'ok'});}).join('')||empty('check','Nessun cambio','Nessun costo d’acquisto cambiato negli ultimi 12 mesi.')}</div>${pager(l.length,14)}`;
 }
 const at=X.at?new Date(X.at):null;
 return`${hero('Le <span class="grad">analisi</span>.',`Incassi previsti, clienti più importanti, agenti, tecnici, macchine, sconti e costi dei fornitori: calcolati da Mastro a ogni lettura.${at&&!isNaN(at)?` <span class="sal-sync"><i></i>Aggiornate alle ${at.toLocaleTimeString('it-IT',{hour:'2-digit',minute:'2-digit'})}</span>`:''}`,'')}
 <div class="ins-tiles ana-tiles reveal" style="--i:1" role="tablist" aria-label="Analisi">${ANA_TABS.map(([k,l,ic],i)=>{const s=S[k];
  return`<button role="tab" aria-selected="${k===tab}" class="ins-tile t-${s.tone}${k===tab?' on':''}" data-ana-tab="${k}" style="--i:${i}"><span class="ins-ic">${icon(ic)}</span><span class="ins-l">${l}</span><strong>${s.n}</strong><small>${esc(s.note)}</small></button>`;}).join('')}</div>
 <div class="card ins-body reveal" style="--i:2">${body}</div>`;
}
document.addEventListener('click',e=>{
 const t=e.target.closest('[data-ana-tab],[data-ana-abc],[data-ana-disc],[data-ana-mach]');if(!t)return;const d=t.dataset;
 if(d.anaTab){anaUi.tab=d.anaTab;ui.q='';}else if(d.anaAbc)anaUi.abc=d.anaAbc;else if(d.anaDisc)anaUi.disc=d.anaDisc;else anaUi.mach=d.anaMach;ui.p=1;
 if(page!=='accAna')return go('accAna');
 typeof morph==='function'?morph():render(false);
});
