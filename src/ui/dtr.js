'use strict';
/* Top Cash Studio — DTR-Service module (telematic cash registers). Loaded after app.js; uses its helpers and state.
   Data: read-only copy of DTR-Service → RT-Utility → Registratori telematici, refreshed by the sync station. */

let DT=null; // derived DTR data, rebuilt with the archive
const DTR_SILENT=7;   // days without a closing/communication before a register is "silent"
const DTR_GE_SOON=60; // electronic journal expiring within N days

function dtrKey(s){return norm(s).replace(/\b(s\.?r\.?l\.?s?|s\.?n\.?c\.?|s\.?a\.?s\.?|s\.?p\.?a\.?|di|e|&|c\.?)\b/g,' ').replace(/[^a-z0-9]+/g,' ').trim();}
function fwNum(v){const p=String(v||'').split('.').map(x=>+x||0);return p[0]*1000+(p[1]||0);}
function deriveDtr(){
 const list=state.dtr||[];if(!D){DT=null;return;}
 const byName=new Map();for(const c of D.clients){const k=dtrKey(c.name);if(k&&!byName.has(k))byName.set(k,c);c.dtr=[];}
 const top=list.reduce((m,r)=>Math.max(m,fwNum(r.firmware)),0);
 const regs=list.map(r=>{const c=byName.get(dtrKey(r.client))||(r.alias?byName.get(dtrKey(r.alias)):null)||null;
  const ge=days(r.geDue),seen=r.lastSync?dayDiff(parseDate(r.lastSync)):null;
  const x={...r,c,code:c?.code||null,ge,seen,silent:seen!==null&&seen<-DTR_SILENT,old:top>0&&fwNum(r.firmware)<top,tagList:(r.tags||'').split(/\s+/).filter(Boolean)};
  if(c)c.dtr.push(x);return x;});
 const tags=[...new Set(regs.flatMap(r=>r.tagList))].sort();
 DT={regs,tags,top,geSoon:regs.filter(r=>r.ge!==null&&r.ge<=DTR_GE_SOON),silent:regs.filter(r=>r.silent),old:regs.filter(r=>r.old),linked:regs.filter(r=>r.c).length,status:state.dtrStatus||{}};
 for(const c of D.clients)if(c.dtr.length)c.search+=' '+norm(c.dtr.map(r=>r.serial).join(' '));
}
const fwLabel=n=>n?`${Math.floor(n/1000)}.${String(n%1000).padStart(2,'0')}`:'';
function seenText(r){if(!r.lastSync)return'<span class="muted">mai</span>';const t=r.lastSync.slice(11,16);return`${dShort(parseDate(r.lastSync))}${t?' · '+t:''}<br><small style="color:var(--${r.silent?'bad':'text-3'})">${rel(r.seen)}</small>`;}
function geText(r){if(!r.geDue)return'<span class="muted">—</span>';const cls=r.ge<0?'bad':r.ge<=DTR_GE_SOON?'due':'text-3';return`<span class="mono">GE ${r.geNumber||'—'}</span> · ${dShort(parseDate(r.geDue))}<br><small style="color:var(--${cls})">${rel(r.ge)}</small>`;}

function dtrStatusCard(){
 const s=DT?.status||{};const when=s.lastSync?dTime(s.lastSync):'mai';
 if(s.error)return`<div class="dtr-status bad reveal" style="--i:1"><span class="dot"></span><div class="grow"><strong>Accesso a DTR-Service richiesto</strong><small>${esc(s.error)}</small></div>${WEB?'':`<button class="btn accent" data-action="dtr">${icon('external')}Accedi</button>`}</div>`;
 if(!s.lastSync)return`<div class="dtr-status reveal" style="--i:1"><span class="dot off"></span><div class="grow"><strong>DTR-Service non ancora collegato</strong><small>${WEB?'Si collega dall’app Windows sulla postazione di sincronizzazione dell’ufficio.':'Accedi una volta: la sessione resta attiva su questo PC e i dati si aggiornano da soli ogni 2 ore.'}</small></div>${WEB?'':`<button class="btn accent" data-action="dtr">${icon('external')}Collega DTR-Service</button>`}</div>`;
 return`<div class="dtr-status ok reveal" style="--i:1"><span class="dot"></span><div class="grow"><strong>Collegato a DTR-Service</strong><small>Ultimo aggiornamento ${esc(when)} · sessione controllata ogni 15 minuti · aggiornamento automatico ogni 2 ore</small></div>${WEB?'':`<div class="btn-row"><button class="btn ghost sm" data-action="dtr">${icon('external')}Apri portale</button><button class="btn sm" data-action="dtrSync">${icon('sync')}Aggiorna ora</button></div>`}</div>`;
}

function dtrPage(){
 if(!DT)deriveDtr();
 const all=DT?.regs||[];let list=all;const q=norm(ui.q);const tab=ui.dtab||'all';
 if(tab==='ge')list=DT.geSoon;else if(tab==='silent')list=DT.silent;else if(tab==='fw')list=DT.old;
 if(ui.dtag)list=list.filter(r=>r.tagList.includes(ui.dtag));
 if(q)list=list.filter(r=>norm([r.serial,r.client,r.alias,r.tags,r.firmware,r.c?.city].join(' ')).includes(q));
 if(!['client','serial','ge','seen','fw'].includes(ui.sort.key))ui.sort={key:tab==='silent'?'seen':'ge',dir:1};
 list=sortBy(list,{client:r=>r.client.toLowerCase(),serial:r=>r.serial,ge:r=>r.geDue||'9999',seen:r=>r.lastSync||'0000',fw:r=>fwNum(r.firmware)});
 const rows=pageOf(list,18);
 return`${hero('I <span class="grad">registratori telematici</span>.','Il parco RT su DTR-Service: giornale elettronico, firmware e ultima chiusura di ogni cassa, sempre aggiornati.','')}
 ${dtrStatusCard()}
 ${explain('dtr','Copia in <strong>sola lettura</strong> di DTR-Service → RT-Utility. Una cassa è <strong>silenziosa</strong> se non comunica da più di '+DTR_SILENT+' giorni; il <strong>GE</strong> è il giornale elettronico, da rinnovare prima della scadenza. Le righe collegate a un cliente di Studio aprono la sua scheda.')}
 ${all.length?`<div class="kpis">${kpi('Registratori',all.length,`${fmt(DT.linked)} collegati ai clienti`,'register','','dtr',2)}${kpi('GE in scadenza',DT.geSoon.length,`Entro ${DTR_GE_SOON} giorni o già scaduti`,'calendar','t-due','dtr',3)}${kpi('Casse silenziose',DT.silent.length,`Nessuna chiusura da oltre ${DTR_SILENT} giorni`,'alert',DT.silent.length?'t-bad':'','dtr',4)}${kpi('Firmware da aggiornare',DT.old.length,`Ultima versione ${esc(fwLabel(DT.top))}`,'sync','','dtr',5)}</div>`:''}
 <div class="card reveal" style="--i:6"><div class="card-head"><div><h2>Registratori<span class="count">${fmt(list.length)}</span></h2></div><div class="seg">${[['all','Tutti'],['ge','GE in scadenza'],['silent','Silenziosi'],['fw','Firmware']].map(([k,l])=>`<button class="${tab===k?'active':''}" data-dtab="${k}">${l}</button>`).join('')}</div></div>
 <div class="toolbar">${search('Cerca cliente, insegna, matricola, tag…')}${DT?.tags.length>1?`<div class="dtr-tags">${DT.tags.map(t=>`<button class="dtr-tag${ui.dtag===t?' on':''}" data-dtag="${esc(t)}">${esc(t)}</button>`).join('')}</div>`:''}</div>
 <div class="table-wrap"><table><thead><tr>${th('Cliente','client')}${th('Matricola','serial')}${th('Giornale elettronico','ge')}${th('Ultima comunicazione','seen')}${th('Firmware','fw')}<th></th></tr></thead><tbody>
 ${rows.map((r,k)=>`<tr class="reveal" style="--i:${k}" ${r.code?`data-client="${esc(r.code)}" tabindex="0"`:''}><td><div class="who">${markEl(r.client,r.code||r.client)}<div><strong>${hl(r.client||'—',ui.q)}</strong><small>${esc([r.alias,r.c?.city].filter(Boolean).join(' · '))}${r.c?'':'<span class="muted"> non collegato</span>'}</small></div></div></td>
  <td><span class="mono">${hl(r.serial,ui.q)}</span><br>${r.tagList.map(t=>`<span class="badge">${esc(t)}</span>`).join(' ')}</td>
  <td>${geText(r)}</td><td>${seenText(r)}${r.lastZ?`<br><small class="muted">Z n. ${fmt(r.lastZ)}</small>`:''}</td>
  <td><span class="mono">${esc(r.firmware||'—')}</span>${r.old?'<br><small style="color:var(--due)">da aggiornare</small>':''}</td><td>${r.code?`<span class="row-go">${icon('chevron')}</span>`:''}</td></tr>`).join('')||`<tr><td colspan="6">${empty('register','Nessun registratore',all.length?'Modifica i filtri.':'Collega DTR-Service per vedere il parco RT.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,18)}</div>`;
}

/* Section in the client panel */
function dtrClientSection(c){
 if(!c.dtr?.length)return'';
 return`<div class="d-section"><h3>Registratori DTR <span>${c.dtr.length}</span></h3>${c.dtr.map(r=>`<div class="lic"><div class="lic-top"><strong class="mono">${esc(r.serial)}</strong>${r.silent?'<span class="badge bad">silenzioso</span>':r.ge!==null&&r.ge<=DTR_GE_SOON?'<span class="badge due">GE in scadenza</span>':'<span class="badge ok">attivo</span>'}</div>
  <small>GE ${r.geNumber||'—'} · scade ${dShort(parseDate(r.geDue))} (${rel(r.ge)||'—'}) · firmware ${esc(r.firmware||'—')} · ultima chiusura ${r.lastSync?dShort(parseDate(r.lastSync))+' '+r.lastSync.slice(11,16):'mai'}${r.lastZ?' · Z n. '+fmt(r.lastZ):''}</small></div>`).join('')}</div>`;
}

document.addEventListener('click',e=>{
 const t=e.target.closest?.('[data-dtab],[data-dtag]');if(!t)return;
 if(t.dataset.dtab){ui.dtab=t.dataset.dtab;ui.p=1;render(false);}
 else{ui.dtag=ui.dtag===t.dataset.dtag?'':t.dataset.dtag;ui.p=1;render(false);}
});
