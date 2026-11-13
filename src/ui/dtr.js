'use strict';
/* Top Cash Studio — DTR-Service module (telematic cash registers). Loaded after app.js; uses its helpers and state.
   Data: read-only copy of DTR-Service → RT-Utility (list + each register's detail page), refreshed by the sync station.
   Every register is linked to Studio by, in order: Mastro machine serial → VAT number → company name. */

let DT=null; // derived DTR data, rebuilt with the archive
const DTR_SILENT=7;     // days without communication before a register is "silent"
const DTR_GE_SOON=60;   // electronic journal expiring within N days
const DTR_CERT_SOON=90; // device certificate expiring within N days
const DTR_LIC_SOON=45;  // RT-Utility licence expiring within N days
const DTR_LOW_Z=60;     // remaining daily closings before the journal is full

function dtrKey(s){return norm(s).replace(/\b(s\.?r\.?l\.?s?|s\.?n\.?c\.?|s\.?a\.?s\.?|s\.?p\.?a\.?|di|e|&|c\.?)\b/g,' ').replace(/[^a-z0-9]+/g,' ').trim();}
const vatKey=s=>String(s||'').toUpperCase().replace(/^IT/,'').replace(/[^0-9A-Z]/g,'');
function fwNum(v){const p=String(v||'').split('.').map(x=>+x||0);return p[0]*1000+(p[1]||0);}
/* Serial numbers are compared without spaces, dashes or case. Mastro may store the RT serial in "matricola fiscale"
   or in "matricola", sometimes without the leading manufacturer digit/letters: a tail of at least 8 characters must match. */
const serKey=s=>String(s||'').toUpperCase().replace(/[^A-Z0-9]/g,'');
function machineIndex(){
 const exact=new Map(),tail=new Map();
 // active machines win over dismissed ones when the same serial appears twice
 const ms=(D.machines||[]).slice().sort((a,b)=>(b.active?1:0)-(a.active?1:0));
 for(const m of ms){m.dtr=null;for(const k of new Set([serKey(m.fiscalSerial),serKey(m.serial)]))if(k.length>=8){if(!exact.has(k))exact.set(k,m);const t=k.slice(-8);if(!tail.has(t))tail.set(t,[]);tail.get(t).push([k,m]);}}
 return k=>{if(k.length<8)return null;const e=exact.get(k);if(e)return e;
  const c=(tail.get(k.slice(-8))||[]).filter(([mk])=>mk.endsWith(k)||k.endsWith(mk));return c.length===1?c[0][1]:null;}; // ambiguous tails are not guessed
}
function deriveDtr(){
 const list=state.dtr||[];if(!D){DT=null;return;}
 const byName=new Map(),byVat=new Map();
 for(const c of D.deleted||[])c.dtr=[]; // a register can point to a removed customer (manual link or serial)
 for(const c of D.clients){c.dtr=[];const k=dtrKey(c.name);if(k&&!byName.has(k))byName.set(k,c);for(const v of [c.vat,c.taxCode].map(vatKey))if(v.length>=11&&!byVat.has(v))byVat.set(v,c);}
 const find=machineIndex();
 const top=list.reduce((m,r)=>Math.max(m,fwNum(r.firmware)),0);
 const regs=list.map(r=>{
  const d=r.detail||null;const m=find(serKey(r.serial));
  const cVat=d?.vat?byVat.get(vatKey(d.vat)):null;
  const cName=byName.get(dtrKey(r.client))||(d?.holder?byName.get(dtrKey(d.holder)):null)||(r.alias?byName.get(dtrKey(r.alias)):null)||null;
  const cLink=D.byCode.get(state.deviceLinks?.[String(r.serial||'').toUpperCase()]);const c=cLink||m?.c||cVat||cName;const via=cLink?'manual':m?.c?'serial':cVat?'vat':cName?'name':'';
  const ge=days(r.geDue),seen=r.lastSync?dayDiff(parseDate(r.lastSync)):null;
  const cert=d?.certDue?days(d.certDue):null;
  const lic=(d?.licenses||[]).filter(l=>/rt-utility/i.test(l.name||'')).sort((a,b)=>(b.to||'').localeCompare(a.to||''))[0]||null;
  const licDays=lic?.to?days(lic.to):null;
  // the newest "CHIUSURE RIMANENTI:n" message tells how many daily closings fit in the journal
  const zMsg=(d?.errors||[]).find(e=>/CHIUSURE RIMANENTI\s*:?\s*(\d+)/i.test(e.text));const remaining=zMsg?+zMsg.text.match(/CHIUSURE RIMANENTI\s*:?\s*(\d+)/i)[1]:null;
  const recentErr=(d?.errors||[]).filter(e=>e.at&&dayDiff(parseDate(e.at))>=-7&&!/CHIUSURE RIMANENTI/i.test(e.text));
  const x={...r,d,m,c,via,code:c?.code||null,ge,seen,cert,lic,licDays,remaining,recentErr,
   silent:seen!==null&&seen<-DTR_SILENT,old:top>0&&fwNum(r.firmware)<top,tagList:(r.tags||'').split(/\s+/).filter(Boolean),
   warn:!!d?.warning||recentErr.length>0,lowZ:remaining!==null&&remaining<=DTR_LOW_Z};
  x.search=norm([r.serial,r.client,r.alias,r.tags,r.firmware,d?.holder,d?.vat,d?.address,c?.name,c?.city,m?.model].join(' '));
  if(m)m.dtr=x;if(c)(c.dtr||=[]).push(x);return x;});
 const tags=[...new Set(regs.flatMap(r=>r.tagList))].sort();
 // active fiscal machines in Mastro that DTR does not know (or not with that serial)
 const missing=(D.machines||[]).filter(m=>m.active&&!m.dtr&&serKey(m.fiscalSerial).length>=8);
 DT={regs,tags,top,missing,
  geSoon:regs.filter(r=>r.ge!==null&&r.ge<=DTR_GE_SOON||r.lowZ),silent:regs.filter(r=>r.silent),old:regs.filter(r=>r.old),
  errors:regs.filter(r=>r.warn),certSoon:regs.filter(r=>r.cert!==null&&r.cert<=DTR_CERT_SOON),licSoon:regs.filter(r=>r.licDays!==null&&r.licDays<=DTR_LIC_SOON),
  detailed:regs.filter(r=>r.d).length,linked:regs.filter(r=>r.c).length,bySerial:regs.filter(r=>r.via==='serial').length,byVat:regs.filter(r=>r.via==='vat').length,
  noMastro:regs.filter(r=>!r.m),status:state.dtrStatus||{}};
 for(const c of D.clients)if(c.dtr.length)c.search+=' '+norm(c.dtr.map(r=>r.serial).join(' '));
}
const tone=(n,soon)=>n===null?'':n<0?'bad':n<=soon?'due':'';
const dueSmall=(n,soon)=>n===null?'':`<br><small style="color:var(--${tone(n,soon)||'text-3'})">${rel(n)}</small>`;
const dtTime=v=>v?`${dShort(parseDate(v))}${v.length>10?' '+v.slice(11,16):''}`:'—';
/* compact DTR line under a Mastro machine (machines page) */
const viaBadge=r=>r.via==='serial'?`<span class="link-tag ok" data-tip="Abbinato per matricola alla macchina di Mastro">${icon('check')}${esc(r.m.model||'Mastro')}</span>`
 :r.via==='vat'?`<span class="link-tag ok" data-tip="Abbinato per partita IVA">${icon('check')}P.IVA</span>`
 :r.via==='name'?'<span class="link-tag" data-tip="Abbinato per nome: un collegamento certo arriva con la matricola in Mastro o con la partita IVA">per nome</span>':'<span class="link-tag bad">non collegato</span>';
const fwLabel=n=>n?`${Math.floor(n/1000)}.${String(n%1000).padStart(2,'0')}`:'';
function seenText(r){if(!r.lastSync)return'<span class="muted">mai</span>';return`${dtTime(r.lastSync)}<br><small style="color:var(--${r.silent?'bad':'text-3'})">${rel(r.seen)}</small>`;}
function geText(r){if(!r.geDue)return'<span class="muted">—</span>';return`<span class="mono">GE ${r.geNumber||'—'}</span> · ${dShort(parseDate(r.geDue))}${dueSmall(r.ge,DTR_GE_SOON)}${r.remaining!==null?`<br><small style="color:var(--${r.lowZ?'bad':'text-3'})">${plur(r.remaining,'chiusura rimanente','chiusure rimanenti')}</small>`:''}`;}
function flags(r){return[r.warn?`<span class="badge bad" data-tip="${esc(r.d?.warning||r.recentErr[0]?.text||'')}">errori</span>`:'',r.silent?'<span class="badge bad">silenziosa</span>':'',r.lowZ?'<span class="badge due">GE quasi pieno</span>':'',r.cert!==null&&r.cert<=DTR_CERT_SOON?'<span class="badge due">certificato</span>':'',r.licDays!==null&&r.licDays<=DTR_LIC_SOON?'<span class="badge due">licenza</span>':'',r.old?'<span class="badge">firmware</span>':''].filter(Boolean).join(' ');}

function dtrStatusCard(){
 const s=DT?.status||{};const when=s.lastSync?dTime(s.lastSync):'mai';
 if(s.error)return`<div class="dtr-status bad reveal" style="--i:1"><span class="dot"></span><div class="grow"><strong>Accesso a DTR-Service richiesto</strong><small>${esc(s.error)}</small></div>${WEB?'':`<button class="btn accent" data-action="dtr">${icon('external')}Accedi</button>`}</div>`;
 if(!s.lastSync)return`<div class="dtr-status reveal" style="--i:1"><span class="dot off"></span><div class="grow"><strong>DTR-Service non ancora collegato</strong><small>${WEB?((state.serverPortals||[]).includes('dtr')?'Lo legge il server: il primo accesso lo fa il proprietario da Sincronizzazione → Portali e accessi.':'Si collega da Sincronizzazione → Portali e accessi (proprietario) o dall’app Windows della postazione dell’ufficio.'):'Accedi una volta: la sessione resta attiva su questo PC e i dati si aggiornano da soli ogni 2 ore.'}</small></div>${WEB?'':`<button class="btn accent" data-action="dtr">${icon('external')}Collega DTR-Service</button>`}</div>`;
 const n=DT.regs.length;
 return`<div class="dtr-status ok reveal" style="--i:1"><span class="dot"></span><div class="grow"><strong>Collegato a DTR-Service</strong><small>Ultimo aggiornamento ${esc(when)} · schede di dettaglio ${fmt(DT.detailed)} di ${fmt(n)}${DT.detailed<n?' (completate a ogni giro)':''} · sessione controllata ogni 15 minuti</small><div class="dtr-meter" aria-hidden="true"><i style="width:${n?Math.round(DT.detailed/n*100):0}%"></i></div></div>${WEB?'':`<div class="btn-row"><button class="btn ghost sm" data-action="dtr">${icon('external')}Apri portale</button><button class="btn sm" data-action="dtrSync">${icon('sync')}Aggiorna ora</button></div>`}</div>`;
}

const DTR_TABS=[['all','Tutti'],['ge','GE in scadenza'],['errors','Errori'],['silent','Silenziose'],['cert','Certificati'],['lic','Licenze RT-Utility'],['fw','Firmware'],['nomastro','Non in Mastro']];
function dtrPage(){
 if(!DT)deriveDtr();
 const all=DT?.regs||[];let list=all;const q=norm(ui.q);const tab=ui.dtab||'all';
 list={ge:DT.geSoon,errors:DT.errors,silent:DT.silent,cert:DT.certSoon,lic:DT.licSoon,fw:DT.old,nomastro:DT.noMastro}[tab]||all;
 if(ui.dtag)list=list.filter(r=>r.tagList.includes(ui.dtag));
 if(q)list=list.filter(r=>r.search.includes(q));
 if(!['client','serial','ge','seen','fw','cert'].includes(ui.sort.key))ui.sort={key:tab==='silent'?'seen':tab==='cert'?'cert':'ge',dir:1};
 list=sortBy(list,{client:r=>r.client.toLowerCase(),serial:r=>r.serial,ge:r=>r.lowZ?'0000':r.geDue||'9999',seen:r=>r.lastSync||'0000',fw:r=>fwNum(r.firmware),cert:r=>r.d?.certDue||'9999'});
 const rows=pageOf(list,18);
 return`${hero('Il portale <span class="grad">DTR</span>.','Il parco RT su DTR-Service: giornale elettronico, certificato, verificazione, licenze, errori e interventi di ogni cassa, sempre aggiornati.','')}
 ${dtrStatusCard()}
 ${explain('dtr','Copia in <strong>sola lettura</strong> di DTR-Service → RT-Utility. Ogni cassa è collegata al cliente di Studio per <strong>matricola</strong> (macchina di Mastro), poi per <strong>partita IVA</strong>, infine per nome. Una cassa è <strong>silenziosa</strong> se non comunica da più di '+DTR_SILENT+' giorni; il <strong>GE</strong> (giornale elettronico) va rinnovato prima della scadenza o quando le chiusure rimanenti sono poche. Clicca una riga per la scheda completa.')}
 ${all.length?`<div class="kpis dtr-kpis">${kpi('Registratori',all.length,`${fmt(DT.bySerial)} per matricola · ${fmt(DT.byVat)} per P.IVA · ${fmt(DT.linked-DT.bySerial-DT.byVat)} per nome`,'register','','dtr',2)}${kpi('GE da rinnovare',DT.geSoon.length,`Scadenza entro ${DTR_GE_SOON} giorni o meno di ${DTR_LOW_Z} chiusure`,'calendar','t-due','dtr',3)}${kpi('Con errori',DT.errors.length,'Errori o avvisi negli ultimi 7 giorni','alert',DT.errors.length?'t-bad':'','dtr',4)}
  ${kpi('Casse silenziose',DT.silent.length,`Nessuna comunicazione da oltre ${DTR_SILENT} giorni`,'clock',DT.silent.length?'t-bad':'','dtr',5)}${kpi('Certificati in scadenza',DT.certSoon.length,`Entro ${DTR_CERT_SOON} giorni`,'shield','t-due','dtr',6)}${kpi('Licenze RT-Utility',DT.licSoon.length,`In scadenza entro ${DTR_LIC_SOON} giorni · firmware vecchio ${fmt(DT.old.length)}`,'key','','dtr',7)}</div>`:''}
 <div class="card reveal" style="--i:8"><div class="card-head"><div><h2>Registratori<span class="count">${fmt(list.length)}</span></h2></div><div class="seg dtr-seg">${DTR_TABS.map(([k,l])=>`<button class="${tab===k?'active':''}" data-dtab="${k}">${l}</button>`).join('')}</div></div>
 <div class="toolbar">${search('Cerca cliente, P.IVA, indirizzo, matricola, tag, modello…')}${DT?.tags.length>1?`<div class="dtr-tags">${DT.tags.map(t=>`<button class="dtr-tag${ui.dtag===t?' on':''}" data-dtag="${esc(t)}">${esc(t)}</button>`).join('')}</div>`:''}</div>
 <div class="table-wrap"><table><thead><tr>${th('Cliente','client')}${th('Matricola','serial')}${th('Giornale elettronico','ge')}${th('Certificato','cert')}${th('Ultima comunicazione','seen')}${th('Firmware','fw')}<th></th></tr></thead><tbody>
 ${rows.map((r,k)=>`<tr class="reveal" style="--i:${k}" data-dtr="${esc(r.serial)}" tabindex="0"><td><div class="who">${markEl(r.c?.name||r.client,r.code||r.client)}<div><strong>${hl(r.c?.name||r.client||'—',ui.q)}</strong><small>${esc([r.alias,r.c?.city||(r.d?.address||'').replace(/\s+IT$/,'')].filter(Boolean).join(' · '))} ${viaBadge(r)}</small></div></div></td>
  <td><span class="mono">${hl(r.serial,ui.q)}</span><br>${r.tagList.map(t=>`<span class="badge">${esc(t)}</span>`).join(' ')} ${flags(r)}</td>
  <td>${geText(r)}</td><td>${r.d?.certDue?`${dShort(parseDate(r.d.certDue))}${dueSmall(r.cert,DTR_CERT_SOON)}`:'<span class="muted">—</span>'}</td>
  <td>${seenText(r)}${r.lastZ?`<br><small class="muted">Z n. ${fmt(r.lastZ)}</small>`:''}</td>
  <td><span class="mono">${esc(r.firmware||'—')}</span>${r.old?'<br><small style="color:var(--due)">da aggiornare</small>':''}</td><td><span class="row-go">${icon('chevron')}</span></td></tr>`).join('')||`<tr><td colspan="7">${empty('register','Nessun registratore',all.length?'Modifica i filtri.':'Collega DTR-Service per vedere il parco RT.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,18)}</div>
 ${DT?.missing.length?`<div class="card reveal" style="--i:9"><div class="card-head"><div><h2>In Mastro ma non su DTR<span class="count">${fmt(DT.missing.length)}</span></h2><p class="sub">Macchine attive con matricola fiscale che DTR-Service non elenca: registratori di altri portali, dismessi o con la matricola scritta diversamente.</p></div></div>
  <div>${DT.missing.slice(0,60).map(m=>`<div class="list-row" ${m.code?`data-client="${esc(m.code)}"`:''}>${markEl(m.client,m.code||m.client)}<div class="grow"><strong>${esc(m.client)}</strong><small>${esc(m.model||'Macchina')} · <span class="mono">${esc(m.fiscalSerial)}</span>${m.serial?` · matr. <span class="mono">${esc(m.serial)}</span>`:''}</small></div>${m.code?`<span class="row-go">${icon('chevron')}</span>`:''}</div>`).join('')}</div></div>`:''}`;
}

/* Full register card (modal) */
function openDtr(serial){
 if(!DT)deriveDtr();const r=DT?.regs.find(x=>x.serial===serial);if(!r)return;const d=r.d||{};
 const fact=(l,v,cls)=>`<div class="fact"><small>${l}</small><span${cls?` style="color:var(--${cls})"`:''}>${v||'<span class="muted">—</span>'}</span></div>`;
 const evs=(list,empt)=>list?.length?list.map(e=>`<div class="dtr-ev"><time>${esc(dtTime(e.at))}</time><span>${esc(e.text)}</span></div>`).join(''):`<p class="muted">${empt}</p>`;
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<div class="modal-box wide dtr-card" role="dialog" aria-label="Registratore ${esc(r.serial)}">
  <div class="dtr-card-head"><div><span class="tag">Registratore telematico</span><h3 class="mono">${esc(r.serial)}</h3><p>${esc(d.holder||r.client)}${d.vat?` · P.IVA <span class="mono">${esc(d.vat)}</span>`:''}</p></div><button class="icon-btn" data-x aria-label="Chiudi">${icon('close')}</button></div>
  <div class="badge-row">${r.tagList.map(t=>`<span class="badge">${esc(t)}</span>`).join(' ')} ${viaBadge(r)} ${flags(r)}</div>
  ${d.warning?`<div class="dtr-warn">${icon('alert')}<span>${esc(d.warning)}</span></div>`:''}
  <div class="facts">
   ${fact('Cliente in Studio',r.c?esc(r.c.name):'')}${fact('Indirizzo di installazione',esc((d.address||'').replace(/\s+IT$/,'')))}
   ${fact('Giornale elettronico',r.geDue?`GE ${r.geNumber||'—'}${d.geInit?' · iniziato il '+dShort(parseDate(d.geInit)):''}`:'')}${fact('Scadenza GE',r.geDue?`${dShort(parseDate(r.geDue))} · ${rel(r.ge)}`:'',tone(r.ge,DTR_GE_SOON))}
   ${fact('Chiusure rimanenti',r.remaining!==null?fmt(r.remaining):'',r.lowZ?'bad':'')}${fact('Ultima chiusura Z',r.lastZ?'n. '+fmt(r.lastZ):'')}
   ${fact('Ultima comunicazione',r.lastSync?`${dtTime(r.lastSync)} · ${rel(r.seen)}`:'mai',r.silent?'bad':'')}${fact('Firmware',esc(r.firmware)+(r.old?` · disponibile ${esc(fwLabel(DT.top))}`:''),r.old?'due':'')}
   ${fact('Scadenza certificato',d.certDue?`${dShort(parseDate(d.certDue))} · ${rel(r.cert)}`:'',tone(r.cert,DTR_CERT_SOON))}${fact('Ultima verificazione periodica',d.lastVp?dShort(parseDate(d.lastVp)):'')}
   ${r.m?fact('Macchina in Mastro',`${esc(r.m.model||'Macchina')}${r.m.serial?' · matr. '+esc(r.m.serial):''}`)+fact('Verificazione (Mastro)',r.m.verNext?`prossima ${dShort(r.m.verNext)}`:''):''}
  </div>
  <div class="d-section"><h3>Licenze <span>${(d.licenses||[]).length}</span></h3>${(d.licenses||[]).map(l=>`<div class="lic"><div class="lic-top"><strong>${esc(l.name||'Licenza')} <span class="muted">${esc(l.type||'')}</span></strong><span class="badge ${/valid/i.test(l.status)?'ok':'bad'}">${esc(l.status||'—')}</span></div><small>${dShort(parseDate(l.from))} → ${dShort(parseDate(l.to))}${l.to?' · '+rel(days(l.to)):''}${l.autoRenew?' · rinnovo automatico '+esc(l.autoRenew):''}</small></div>`).join('')||'<p class="muted">Nessuna licenza letta.</p>'}</div>
  ${d.extensions?`<div class="d-section"><h3>Estensioni GE</h3><p>${esc(d.extensions)}</p></div>`:''}
  <div class="d-section"><h3>Ultimi errori <span>${(d.errors||[]).length}</span></h3>${evs(d.errors,'Nessun errore segnalato.')}</div>
  <div class="d-section"><h3>Ultimi interventi <span>${(d.works||[]).length}</span></h3>${evs(d.works,'Nessun intervento registrato.')}</div>
  <p class="sub">${d.readAt?'Scheda letta da DTR-Service '+esc(dTime(d.readAt)):'Scheda di dettaglio non ancora letta: arriva con i prossimi aggiornamenti automatici.'}</p>
  <div class="btn-row">${r.code?`<button class="btn" data-open-client="${esc(r.code)}">${icon('users')}Apri cliente</button><button class="btn accent" data-newint="${esc(r.code)}">${icon('plus')}Nuovo intervento</button>`:''}${r.d?.vat?`<button class="btn ghost" data-copy="${esc(r.d.vat)}">Copia P.IVA</button>`:''}</div>
 </div>`;
 document.body.append(m);if(typeof hideTip==='function')hideTip();
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();close();}};document.addEventListener('keydown',key,true);
 m.addEventListener('click',e=>{
  if(e.target===m||e.target.closest('[data-x]'))return close();
  const oc=e.target.closest('[data-open-client]');if(oc){close();openClient(oc.dataset.openClient);return;}
  if(e.target.closest('[data-newint]'))close(); // the global handler opens the intervention editor
 });
 m.querySelector('[data-x]').focus({preventScroll:true});
}

/* Section in the client panel */
function dtrClientSection(c){
 if(!c.dtr?.length)return'';
 return`<div class="d-section"><h3>Portale DTR <span>${c.dtr.length}</span></h3>${c.dtr.map(r=>`<div class="lic" data-dtr="${esc(r.serial)}" style="cursor:pointer"><div class="lic-top"><strong class="mono">${esc(r.serial)}${r.m?` <small class="muted">${esc(r.m.model||'')}</small>`:''}</strong>${flags(r)||'<span class="badge ok">regolare</span>'}</div>
  <small>GE ${r.geNumber||'—'} · scade ${dShort(parseDate(r.geDue))} (${rel(r.ge)||'—'})${r.remaining!==null?` · ${plur(r.remaining,'chiusura rimanente','chiusure rimanenti')}`:''} · firmware ${esc(r.firmware||'—')} · ultima comunicazione ${r.lastSync?dtTime(r.lastSync):'mai'}${r.d?.certDue?' · certificato '+dShort(parseDate(r.d.certDue)):''}${r.d?.lastVp?' · VP '+dShort(parseDate(r.d.lastVp)):''}${r.d?.address?' · '+esc(r.d.address.replace(/\s+IT$/,'')):''}</small></div>`).join('')}</div>`;
}

document.addEventListener('click',e=>{
 const t=e.target.closest?.('[data-dtab],[data-dtag],[data-dtr]');if(!t)return;
 if(t.dataset.dtr){e.stopPropagation();openDtr(t.dataset.dtr);return;}
 if(t.dataset.dtab){ui.dtab=t.dataset.dtab;ui.p=1;render(false);}
 else{ui.dtag=ui.dtag===t.dataset.dtag?'':t.dataset.dtag;ui.p=1;render(false);}
},true);
document.addEventListener('keydown',e=>{if(e.key==='Enter'){const t=e.target.closest?.('tr[data-dtr]');if(t){e.preventDefault();openDtr(t.dataset.dtr);}}});
