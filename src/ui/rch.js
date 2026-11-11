'use strict';
/* Top Cash Studio — RCH XDealer module (dealer area). Loaded after ital.js; uses app.js / dtr.js helpers and state.
   Data: read-only copy of XDealer → Dispositivi (with the diagnostic data the registers send), refreshed by the sync
   station. Every device is linked to Studio by Mastro machine serial, then by the customer's VAT number, then by name. */

let RC=null; // derived RCH data, rebuilt with the archive
const RC_VER_SOON=60;   // periodic verification due within N days (every 2 years from the last one)
const RC_CERT_SOON=90;  // device certificate expiring within N days
const RC_FREE_LOW=15;   // % of free electronic journal below which the DGFE is "almost full"
const RC_SILENT=10;     // days without a daily closure (Z report) sent to the portal

function deriveRch(){
 const list=state.rch||[];if(!D){RC=null;return;}
 const byName=new Map(),byVat=new Map();
 for(const c of D.clients){c.rch=[];const k=dtrKey(c.name);if(k&&!byName.has(k))byName.set(k,c);for(const v of [c.vat,c.taxCode].map(vatKey))if(v.length>=11&&!byVat.has(v))byVat.set(v,c);}
 const exact=new Map();for(const m of (D.machines||[]).slice().sort((a,b)=>(b.active?1:0)-(a.active?1:0))){m.rch=null;for(const k of new Set([serKey(m.fiscalSerial),serKey(m.serial)]))if(k.length>=8&&!exact.has(k))exact.set(k,m);}
 const addY=(s,y)=>{const p=parseDate(s);if(!p)return null;const d=new Date(p);d.setFullYear(d.getFullYear()+y);return d;};
 const devs=list.map(r=>{
  const m=exact.get(serKey(r.serial))||null;const label=r.client||r.note||'';
  const cLink=D.byCode.get(state.deviceLinks?.[String(r.serial||'').toUpperCase()]);const cVat=byVat.get(vatKey(r.vat))||null;const cName=byName.get(dtrKey(r.client))||null;
  const c=cLink||m?.c||cVat||cName;const via=cLink?'manual':m?.c?'serial':cVat?'vat':cName?'name':'';
  const verNext=r.lastVerification?addY(r.lastVerification,2):null;const ver=verNext?dayDiff(verNext):null;
  const cert=r.certDue?days(r.certDue):null,z=r.zDate?dayDiff(parseDate(r.zDate)):null;
  const off=/dismess|disattiv|sostitu/i.test(r.state||'');
  const x={...r,m,c,via,code:c?.code||null,label,verNext,ver,cert,z,off,
   low:r.dgfeFree>=0&&r.dgfeFree<=RC_FREE_LOW,silent:!off&&z!==null&&z<-RC_SILENT,noContract:!r.contract&&!off};
  x.search=norm([r.serial,r.part,r.type,r.client,r.vat,r.city,r.note,r.labels,r.firmware,r.state,c?.name,c?.city,m?.model].join(' '));
  if(m)m.rch=x;if(c)c.rch.push(x);return x;});
 const live=devs.filter(r=>!r.off);
 RC={devs,live,update:live.filter(r=>r.update),noContract:live.filter(r=>r.noContract),verSoon:live.filter(r=>r.ver!==null&&r.ver<=RC_VER_SOON),
  certSoon:live.filter(r=>r.cert!==null&&r.cert<=RC_CERT_SOON),low:live.filter(r=>r.low),silent:live.filter(r=>r.silent),
  linked:devs.filter(r=>r.c).length,status:state.rchStatus||{}};
 for(const c of D.clients)if(c.rch.length)c.search+=' '+norm(c.rch.map(r=>r.serial).join(' '));
}
const rcVia=r=>r.via==='serial'?`<span class="link-tag ok" data-tip="Abbinato per matricola alla macchina di Mastro">${icon('check')}${esc(r.m.model||'Mastro')}</span>`
 :r.via==='vat'?'<span class="link-tag ok" data-tip="Abbinato per partita IVA del cliente sul portale">per P.IVA</span>'
 :r.via==='name'?'<span class="link-tag" data-tip="Abbinato per nome del cliente sul portale">per nome</span>':r.via==='manual'?'<span class="link-tag ok">abbinato a mano</span>':'<span class="link-tag bad">non collegato</span>';
function rcFlags(r){return[r.off?`<span class="badge">${esc(r.state)}</span>`:'',r.update?`<span class="badge due" data-tip="Aggiornamento disponibile sul portale">agg. ${esc(r.update)}</span>`:'',r.ver!==null&&r.ver<=RC_VER_SOON?`<span class="badge ${r.ver<0?'bad':'due'}">verificazione</span>`:'',
 r.low?'<span class="badge bad">DGFE quasi pieno</span>':'',r.cert!==null&&r.cert<=RC_CERT_SOON?'<span class="badge due">certificato</span>':'',r.silent?'<span class="badge">nessuna chiusura</span>':'',r.noContract?'<span class="badge" data-tip="Nessun contratto di manutenzione sul portale">senza contratto</span>':''].filter(Boolean).join(' ');}
function rcMeter(r){const f=r.dgfeFree;if(f===undefined||f<0)return'<span class="muted">—</span>';
 return`<div class="it-free${r.low?' low':''}"><span>${f}% libero</span><div class="dtr-meter" aria-hidden="true"><i style="width:${100-f}%"></i></div></div>`;}

function rchStatusCard(){
 const s=RC?.status||{};const when=s.lastSync?dTime(s.lastSync):'mai';
 if(s.error)return`<div class="dtr-status bad reveal" style="--i:1"><span class="dot"></span><div class="grow"><strong>Accesso a RCH XDealer richiesto</strong><small>${esc(s.error)}</small></div>${WEB?'':`<button class="btn accent" data-action="rch">${icon('external')}Accedi</button>`}</div>`;
 if(!s.lastSync)return`<div class="dtr-status reveal" style="--i:1"><span class="dot off"></span><div class="grow"><strong>RCH XDealer non ancora collegato</strong><small>${WEB?'Si collega dall’app Windows sulla postazione di sincronizzazione dell’ufficio.':'Accedi una volta con le tue credenziali RCH: la sessione resta attiva su questo PC, riparte da sola a ogni avvio e i dati si aggiornano ogni 2 ore.'}</small></div>${WEB?'':`<button class="btn accent" data-action="rch">${icon('external')}Collega RCH XDealer</button>`}</div>`;
 return`<div class="dtr-status ok reveal" style="--i:1"><span class="dot"></span><div class="grow"><strong>Collegato a RCH XDealer</strong><small>Ultimo aggiornamento ${esc(when)} · ${plur(RC.devs.length,"dispositivo","dispositivi")} · sessione controllata ogni 15 minuti</small></div>${WEB?'':`<div class="btn-row"><button class="btn ghost sm" data-action="rch">${icon('external')}Apri portale</button><button class="btn sm" data-action="rchSync">${icon('sync')}Aggiorna ora</button></div>`}</div>`;
}

const RC_TABS=[['all','Tutti'],['update','Da aggiornare'],['ver','Verificazioni'],['dgfe','DGFE'],['cert','Certificati'],['silent','Senza chiusure'],['contract','Senza contratto']];
function rchPage(){
 if(!RC)deriveRch();
 const all=RC?.devs||[];const q=norm(ui.q);const tab=ui.rtab||'all';
 let list={update:RC.update,ver:RC.verSoon,dgfe:RC.low,cert:RC.certSoon,silent:RC.silent,contract:RC.noContract}[tab]||all;
 if(q)list=list.filter(r=>r.search.includes(q));
 if(!['client','serial','ver','free','z','cert'].includes(ui.sort.key))ui.sort={key:'client',dir:1};
 list=sortBy(list,{client:r=>(r.c?.name||r.label).toLowerCase(),serial:r=>r.serial,ver:r=>r.verNext?r.verNext.toISOString():'9999',free:r=>r.dgfeFree>=0?r.dgfeFree:999,z:r=>r.zDate||'0000',cert:r=>r.certDue||'9999'});
 const rows=pageOf(list,18);
 return`${hero('Il portale <span class="grad">RCH</span>.','XDealer, l’area rivenditore RCH: verificazioni, giornale elettronico, chiusure giornaliere, certificati e aggiornamenti di ogni registratore, sempre aggiornati.','')}
 ${rchStatusCard()}
 ${explain('rch','Copia in <strong>sola lettura</strong> di RCH XDealer → Dispositivi, con i dati che i registratori inviano al portale. Ogni cassa è collegata al cliente di Studio per <strong>matricola</strong> (macchina di Mastro), poi per <strong>partita IVA</strong> o per nome del cliente sul portale. Studio non modifica nulla su XDealer: ti dice solo cosa richiede attenzione. Clicca una riga per la scheda completa.')}
 ${all.length?`<div class="kpis dtr-kpis">${kpi('Dispositivi',RC.live.length,`${fmt(RC.linked)} collegati a un cliente`,'print','','rch',2)}${kpi('Verificazioni',RC.verSoon.length,`Scadute o entro ${RC_VER_SOON} giorni`,'calendar',RC.verSoon.length?'t-due':'','rch',3)}${kpi('Da aggiornare',RC.update.length,'Aggiornamento disponibile','download',RC.update.length?'t-due':'','rch',4)}
  ${kpi('DGFE quasi pieno',RC.low.length,`Meno del ${RC_FREE_LOW}% libero`,'box',RC.low.length?'t-bad':'','rch',5)}${kpi('Certificati in scadenza',RC.certSoon.length,`Entro ${RC_CERT_SOON} giorni`,'shield','t-due','rch',6)}${kpi('Senza chiusure',RC.silent.length,`Nessuna chiusura da più di ${RC_SILENT} giorni`,'clock',RC.silent.length?'t-due':'','rch',7)}</div>`:''}
 <div class="card reveal" style="--i:8"><div class="card-head"><div><h2>Dispositivi<span class="count">${fmt(list.length)}</span></h2></div><div class="seg dtr-seg">${RC_TABS.map(([k,l])=>`<button class="${tab===k?'active':''}" data-rtab="${k}">${l}</button>`).join('')}</div></div>
 <div class="toolbar">${search('Cerca cliente, P.IVA, matricola, modello…')}</div>
 <div class="table-wrap"><table><thead><tr>${th('Cliente','client')}${th('Matricola','serial')}${th('Verificazione','ver')}${th('Giornale (DGFE)','free')}${th('Ultima chiusura','z')}${th('Certificato','cert')}<th></th></tr></thead><tbody>
 ${rows.map((r,k)=>`<tr class="reveal" style="--i:${k}" data-rch="${esc(r.serial)}" tabindex="0"><td><div class="who">${markEl(r.c?.name||r.label||r.serial,r.code||r.serial)}<div><strong>${hl(r.c?.name||r.label||'—',ui.q)}</strong><small>${esc(r.c?.city||r.city||'')} ${rcVia(r)}</small></div></div></td>
  <td><span class="mono">${hl(r.serial,ui.q)}</span><br><span class="badge">${esc(r.type||'—')}</span> ${rcFlags(r)}</td>
  <td>${r.verNext?`${dShort(r.verNext)}${dueSmall(r.ver,RC_VER_SOON)}`:'<span class="muted">—</span>'}</td><td>${rcMeter(r)}${r.dgfeNumber?`<small class="muted">DGFE n. ${fmt(r.dgfeNumber)}</small>`:''}</td>
  <td>${r.zDate?`${dShort(parseDate(r.zDate))}<br><small class="muted">${r.zNumber?'n. '+fmt(r.zNumber)+' · ':''}${rel(r.z)}</small>`:'<span class="muted">—</span>'}</td>
  <td>${r.certDue?`${dShort(parseDate(r.certDue))}${dueSmall(r.cert,RC_CERT_SOON)}`:'<span class="muted">—</span>'}</td><td><span class="row-go">${icon('chevron')}</span></td></tr>`).join('')||`<tr><td colspan="7">${empty('print','Nessun dispositivo',all.length?'Modifica i filtri.':'Collega RCH XDealer per vedere i registratori.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,18)}</div>`;
}

/* Full device card (modal) */
function openRch(serial){
 if(!RC)deriveRch();const r=RC?.devs.find(x=>x.serial===serial);if(!r)return;
 const fact=(l,v,cls)=>`<div class="fact"><small>${l}</small><span${cls?` style="color:var(--${cls})"`:''}>${v||'<span class="muted">—</span>'}</span></div>`;
 const day=v=>v?dShort(parseDate(v)):'';
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<div class="modal-box wide dtr-card" role="dialog" aria-label="Dispositivo ${esc(r.serial)}">
  <div class="dtr-card-head"><div><span class="tag">RCH XDealer · ${esc(r.type||'dispositivo')}</span><h3 class="mono">${esc(r.serial)}</h3><p>${esc(r.c?.name||r.label||'Nessun cliente indicato sul portale')}</p></div><button class="icon-btn" data-x aria-label="Chiudi">${icon('close')}</button></div>
  <div>${rcVia(r)} ${rcFlags(r)||'<span class="badge ok">regolare</span>'}</div>
  <div class="facts">
   ${fact('Cliente in Studio',r.c?esc(r.c.name):'')}${fact('Cliente sul portale',[esc(r.client),r.vat?'P.IVA '+esc(r.vat):'',esc([r.city,r.province].filter(Boolean).join(' '))].filter(Boolean).join(' · '))}
   ${fact('Stato sul portale',esc(r.state||''),r.off?'text-3':'')}${fact('Contratto di manutenzione',r.contract?'Attivo':'Non presente',r.contract?'ok':'')}
   ${fact('Ultima verificazione',r.lastVerification?`${day(r.lastVerification)} · prossima ${dShort(r.verNext)} (${rel(r.ver)})`:'',tone(r.ver,RC_VER_SOON))}${fact('Firmware',esc(r.firmware||'')+(r.update?`${r.firmware?' · ':''}disponibile ${esc(r.update)}`:''),r.update?'due':'')}
   ${fact('Giornale elettronico',r.dgfeNumber?`DGFE n. ${r.dgfeNumber}`:'')}${fact('Spazio libero DGFE',r.dgfeFree>=0?r.dgfeFree+'%':'',r.low?'bad':'')}
   ${fact('Ultima chiusura',r.zDate?`${day(r.zDate)}${r.zNumber?' · n. '+fmt(r.zNumber):''} · ${rel(r.z)}`:'',r.silent?'due':'')}${fact('Certificato',r.certDue?`${r.certFrom?'dal '+day(r.certFrom)+' ':''}al ${day(r.certDue)} · ${rel(r.cert)}`:'',tone(r.cert,RC_CERT_SOON))}
   ${fact('Attivazione',r.activated?`${day(r.activated)}${r.registered?' · censita il '+day(r.registered):''}`:'')}${fact('Etichette e note',[esc(r.labels),esc(r.note)].filter(Boolean).join(' · '))}
   ${fact('Contatti sul portale',[esc(r.phone),esc(r.email)].filter(Boolean).join(' · '))}${r.part?fact('Codice prodotto',`<span class="mono">${esc(r.part)}</span>`):''}
   ${r.m?fact('Macchina in Mastro',`${esc(r.m.model||'Macchina')}${r.m.serial?' · matr. '+esc(r.m.serial):''}`)+fact('Verificazione (Mastro)',r.m.verNext?`prossima ${dShort(r.m.verNext)}`:''):''}
  </div>
  <p class="sub">${r.capturedAt?'Dati letti da RCH XDealer '+esc(dTime(r.capturedAt)):''}</p>
  <div class="btn-row">${r.code?`<button class="btn" data-open-client="${esc(r.code)}">${icon('users')}Apri cliente</button><button class="btn accent" data-newint="${esc(r.code)}">${icon('plus')}Nuovo intervento</button>`:''}<button class="btn ghost" data-copy="${esc(r.serial)}">Copia matricola</button></div>
 </div>`;
 document.body.append(m);if(typeof hideTip==='function')hideTip();
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();close();}};document.addEventListener('keydown',key,true);
 m.addEventListener('click',e=>{
  if(e.target===m||e.target.closest('[data-x]'))return close();
  const oc=e.target.closest('[data-open-client]');if(oc){close();openClient(oc.dataset.openClient);return;}
  if(e.target.closest('[data-newint]'))close();
 });
 m.querySelector('[data-x]').focus({preventScroll:true});
}

/* compact line under a Mastro machine and section in the client panel */
function rchMini(r){return`<span class="dtr-mini">RCH XDealer${r.lastVerification?' · verificata '+dShort(parseDate(r.lastVerification)):''}${r.firmware?' · fw '+esc(r.firmware):''}${r.update?` · <b style="color:var(--due)">agg. ${esc(r.update)}</b>`:''}${r.low?' · <b style="color:var(--bad)">DGFE quasi pieno</b>':''}</span>`;}
function rchClientSection(c){
 if(!c.rch?.length)return'';
 return`<div class="d-section"><h3>Portale RCH <span>${c.rch.length}</span></h3>${c.rch.map(r=>`<div class="lic" data-rch="${esc(r.serial)}" style="cursor:pointer"><div class="lic-top"><strong class="mono">${esc(r.serial)} <small class="muted">${esc(r.type||'')}</small></strong>${rcFlags(r)||'<span class="badge ok">regolare</span>'}</div>
  <small>${r.verNext?'Verificazione '+(r.ver<0?'scaduta il ':'entro il ')+dShort(r.verNext):'Verificazione non indicata'}${r.dgfeFree>=0?' · DGFE libero '+r.dgfeFree+'%':''}${r.zDate?' · ultima chiusura '+dShort(parseDate(r.zDate)):''}${r.contract?' · contratto attivo':''}</small></div>`).join('')}</div>`;
}

document.addEventListener('click',e=>{
 const t=e.target.closest?.('[data-rtab],[data-rch]');if(!t)return;
 if(t.dataset.rch){e.stopPropagation();openRch(t.dataset.rch);return;}
 ui.rtab=t.dataset.rtab;ui.p=1;render(false);
},true);
document.addEventListener('keydown',e=>{if(e.key==='Enter'){const t=e.target.closest?.('tr[data-rch]');if(!t)return;e.preventDefault();openRch(t.dataset.rch);}});
