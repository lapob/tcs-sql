'use strict';
/* Top Cash Studio — Ital Retail OnePlus module (dealer area). Loaded after dtr.js; uses app.js helpers and state.
   Data: read-only copy of Gestione dispositivi (list + each device page) and of the order history, refreshed by the
   sync station. Every device is linked to Studio by Mastro machine serial, then by name (label written on the portal). */

let IT=null; // derived Ital Retail data, rebuilt with the archive
const IT_SUB_SOON=45;   // subscription ending within N days
const IT_CERT_SOON=90;  // device certificate expiring within N days
const IT_FREE_LOW=15;   // % of free electronic journal below which the DGFE is "almost full"
const IT_STALE=10;      // days without fresh device data

function deriveItal(){
 const list=state.ital||[];if(!D){IT=null;return;}
 for(const c of D.deleted||[])c.ital=[]; // a register can point to a removed customer
 const byName=new Map();for(const c of D.clients){c.ital=[];const k=dtrKey(c.name);if(k&&!byName.has(k))byName.set(k,c);}
 const exact=new Map();for(const m of (D.machines||[]).slice().sort((a,b)=>(b.active?1:0)-(a.active?1:0))){m.ital=null;for(const k of new Set([serKey(m.fiscalSerial),serKey(m.serial)]))if(k.length>=8&&!exact.has(k))exact.set(k,m);}
 const orders=new Map();for(const o of state.italOrders||[]){if(!orders.has(o.serial))orders.set(o.serial,[]);orders.get(o.serial).push(o);}
 const devs=list.map(r=>{
  const d=r.detail||null;const m=exact.get(serKey(r.serial))||null;const label=r.client||r.note||'';
  const cName=byName.get(dtrKey(r.client))||byName.get(dtrKey(r.note))||null;const cLink=D.byCode.get(state.deviceLinks?.[String(r.serial||'').toUpperCase()]);const c=cLink||m?.c||cName;const via=cLink?'manual':m?.c?'serial':cName?'name':'';
  const sub=d?.subTo?days(d.subTo):null,cert=d?.certDue?days(d.certDue):null,seen=d?.dataAt?dayDiff(parseDate(d.dataAt)):null;
  const x={...r,d,m,c,via,code:c?.code||null,label,sub,cert,seen,orders:(orders.get(r.serial)||[]).slice().sort((a,b)=>(b.date||'').localeCompare(a.date||'')),
   noSub:!r.subscribed,low:d&&d.dgfeFree>=0&&d.dgfeFree<=IT_FREE_LOW,stale:seen!==null&&seen<-IT_STALE,never:!!d&&!d.dataAt};
  x.search=norm([r.serial,r.type,r.client,r.note,d?.firmware,d?.rtState,c?.name,c?.city,m?.model].join(' '));
  if(m)m.ital=x;if(c)(c.ital||=[]).push(x);return x;});
 IT={devs,update:devs.filter(r=>r.update),noSub:devs.filter(r=>r.noSub),subSoon:devs.filter(r=>r.sub!==null&&r.sub<=IT_SUB_SOON),
  certSoon:devs.filter(r=>r.cert!==null&&r.cert<=IT_CERT_SOON),low:devs.filter(r=>r.low),stale:devs.filter(r=>r.stale||r.never),
  detailed:devs.filter(r=>r.d).length,linked:devs.filter(r=>r.c).length,orders:(state.italOrders||[]).slice().sort((a,b)=>(b.date||'').localeCompare(a.date||'')||b.number.localeCompare(a.number)),status:state.italStatus||{}};
 for(const c of D.clients)if(c.ital.length)c.search+=' '+norm(c.ital.map(r=>r.serial).join(' '));
}
const itVia=r=>r.via==='serial'?`<span class="link-tag ok" data-tip="Abbinato per matricola alla macchina di Mastro">${icon('check')}${esc(r.m.model||'Mastro')}</span>`
 :r.via==='name'?'<span class="link-tag" data-tip="Abbinato per nome, dall’etichetta scritta sul portale">per nome</span>':'<span class="link-tag bad">non collegato</span>';
function itFlags(r){return[r.update?`<span class="badge due" data-tip="Aggiornamento firmware disponibile">agg. ${esc(r.update)}</span>`:'',r.noSub?'<span class="badge bad">senza abbonamento</span>':'',r.sub!==null&&r.sub<=IT_SUB_SOON&&!r.d.autoRenew?'<span class="badge due">abbonamento</span>':'',r.low?'<span class="badge bad">DGFE quasi pieno</span>':'',r.cert!==null&&r.cert<=IT_CERT_SOON?'<span class="badge due">certificato</span>':'',r.stale?'<span class="badge">dati vecchi</span>':''].filter(Boolean).join(' ');}
function itSub(r){if(r.noSub)return'<span style="color:var(--bad)">Non attivo</span>';if(!r.d?.subTo)return'<span class="badge ok">Attivo</span>';
 return`${dShort(parseDate(r.d.subTo))}<br><small style="color:var(--${r.d.autoRenew?'text-3':tone(r.sub,IT_SUB_SOON)||'text-3'})">${r.d.autoRenew?'rinnovo automatico':rel(r.sub)}</small>`;}
function itMeter(r){const f=r.d?.dgfeFree;if(f===undefined||f<0)return'<span class="muted">—</span>';
 return`<div class="it-free${r.low?' low':''}" data-tip="${esc(r.d.dgfeSize||'')}"><span>${f}% libero</span><div class="dtr-meter" aria-hidden="true"><i style="width:${100-f}%"></i></div></div>`;}

function italStatusCard(){
 const s=IT?.status||{};const when=s.lastSync?dTime(s.lastSync):'mai';
 if(s.error)return`<div class="dtr-status bad reveal" style="--i:1"><span class="dot"></span><div class="grow"><strong>Accesso a Ital Retail richiesto</strong><small>${esc(s.error)}</small></div>${WEB?'':`<button class="btn accent" data-action="ital">${icon('external')}Accedi</button>`}</div>`;
 if(!s.lastSync)return`<div class="dtr-status reveal" style="--i:1"><span class="dot off"></span><div class="grow"><strong>Ital Retail non ancora collegato</strong><small>${WEB?'Si collega dall’app Windows sulla postazione di sincronizzazione dell’ufficio.':'Accedi una volta e spunta «Ricordati di me»: la sessione resta attiva su questo PC, riparte da sola a ogni avvio e i dati si aggiornano ogni 2 ore.'}</small></div>${WEB?'':`<button class="btn accent" data-action="ital">${icon('external')}Collega Ital Retail</button>`}</div>`;
 return`<div class="dtr-status ok reveal" style="--i:1"><span class="dot"></span><div class="grow"><strong>Collegato a Ital Retail</strong><small>Ultimo aggiornamento ${esc(when)} · schede di dettaglio ${fmt(IT.detailed)} di ${fmt(IT.devs.length)} · sessione controllata ogni 15 minuti</small></div>${WEB?'':`<div class="btn-row"><button class="btn ghost sm" data-action="ital">${icon('external')}Apri portale</button><button class="btn sm" data-action="italSync">${icon('sync')}Aggiorna ora</button></div>`}</div>`;
}

const IT_TABS=[['all','Tutti'],['update','Da aggiornare'],['sub','Abbonamenti'],['dgfe','DGFE'],['cert','Certificati'],['stale','Senza dati recenti']];
function italPage(){
 if(!IT)deriveItal();
 const all=IT?.devs||[];const q=norm(ui.q);const tab=ui.itab||'all';
 let list={update:IT.update,sub:[...new Set([...IT.noSub,...IT.subSoon])],dgfe:IT.low,cert:IT.certSoon,stale:IT.stale}[tab]||all;
 if(q)list=list.filter(r=>r.search.includes(q));
 if(!['client','serial','sub','free','fw','cert'].includes(ui.sort.key))ui.sort={key:'client',dir:1};
 list=sortBy(list,{client:r=>(r.c?.name||r.label).toLowerCase(),serial:r=>r.serial,sub:r=>r.noSub?'0000':r.d?.subTo||'9999',free:r=>r.d&&r.d.dgfeFree>=0?r.d.dgfeFree:999,fw:r=>fwNum(r.d?.firmware),cert:r=>r.d?.certDue||'9999'});
 const rows=pageOf(list,18);
 return`${hero('Il portale <span class="grad">Ital Retail</span>.','L’area rivenditore OnePlus: abbonamenti, aggiornamenti firmware, giornale elettronico, certificato e ordini di ogni dispositivo, sempre aggiornati.','')}
 ${italStatusCard()}
 ${explain('ital','Copia in <strong>sola lettura</strong> di Ital Retail OnePlus → Gestione dispositivi. Ogni cassa è collegata al cliente di Studio per <strong>matricola</strong> (macchina di Mastro) oppure per nome, dall’etichetta scritta sul portale. Studio non compra, non rinnova e non aggiorna nulla sul portale: ti dice solo cosa richiede attenzione. Clicca una riga per la scheda completa.')}
 ${all.length?`<div class="kpis dtr-kpis">${kpi('Dispositivi',all.length,`${fmt(IT.linked)} collegati a un cliente`,'register','','ital',2)}${kpi('Da aggiornare',IT.update.length,'Nuovo firmware disponibile','download',IT.update.length?'t-due':'','ital',3)}${kpi('Senza abbonamento',IT.noSub.length,'Aggiornamenti non compresi','alert',IT.noSub.length?'t-bad':'','ital',4)}
  ${kpi('Abbonamenti in scadenza',IT.subSoon.length,`Entro ${IT_SUB_SOON} giorni (anche con rinnovo automatico)`,'calendar','t-due','ital',5)}${kpi('DGFE quasi pieno',IT.low.length,`Meno del ${IT_FREE_LOW}% libero`,'box',IT.low.length?'t-bad':'','ital',6)}${kpi('Certificati in scadenza',IT.certSoon.length,`Entro ${IT_CERT_SOON} giorni`,'shield','t-due','ital',7)}</div>`:''}
 <div class="card reveal" style="--i:8"><div class="card-head"><div><h2>Dispositivi<span class="count">${fmt(list.length)}</span></h2></div><div class="seg dtr-seg">${IT_TABS.map(([k,l])=>`<button class="${tab===k?'active':''}" data-itab="${k}">${l}</button>`).join('')}</div></div>
 <div class="toolbar">${search('Cerca cliente, matricola, modello, firmware…')}</div>
 <div class="table-wrap"><table><thead><tr>${th('Cliente','client')}${th('Matricola','serial')}${th('Abbonamento','sub')}${th('Giornale (DGFE)','free')}${th('Firmware','fw')}${th('Certificato','cert')}<th></th></tr></thead><tbody>
 ${rows.map((r,k)=>`<tr class="reveal" style="--i:${k}" data-ital="${esc(r.serial)}" tabindex="0"><td><div class="who">${markEl(r.c?.name||r.label||r.serial,r.code||r.serial)}<div><strong>${hl(r.c?.name||r.label||'—',ui.q)}</strong><small>${esc(r.c?.city||'')} ${itVia(r)}</small></div></div></td>
  <td><span class="mono">${hl(r.serial,ui.q)}</span><br><span class="badge">${esc(r.type||'—')}</span> ${itFlags(r)}</td>
  <td>${itSub(r)}</td><td>${itMeter(r)}${r.d?.closures?`<small class="muted">chiusura n. ${fmt(r.d.closures)}</small>`:''}</td>
  <td><span class="mono">${esc(r.d?.firmware||'—')}</span>${r.update?`<br><small style="color:var(--due)">disponibile ${esc(r.update)}</small>`:''}</td>
  <td>${r.d?.certDue?`${dShort(parseDate(r.d.certDue))}${dueSmall(r.cert,IT_CERT_SOON)}`:'<span class="muted">—</span>'}</td><td><span class="row-go">${icon('chevron')}</span></td></tr>`).join('')||`<tr><td colspan="7">${empty('register','Nessun dispositivo',all.length?'Modifica i filtri.':'Collega Ital Retail per vedere le casse.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,18)}</div>
 ${IT?.orders.length?`<div class="card reveal" style="--i:9"><div class="card-head"><div><h2>Ordini sul portale<span class="count">${fmt(IT.orders.length)}</span></h2><p class="sub">Contratti di manutenzione e acquisti di aggiornamenti, dal più recente.</p></div></div>
  <div>${IT.orders.slice(0,40).map(o=>{const r=IT.devs.find(x=>x.serial===o.serial);return`<div class="list-row"${r?` data-ital="${esc(o.serial)}"`:''}><span class="date-chip"><b>${o.date?parseDate(o.date).getDate():'—'}</b><span>${o.date?DF3.format(parseDate(o.date)):''}</span></span><div class="grow"><strong>${esc(r?.c?.name||r?.label||o.client||o.serial)}</strong><small>${esc(o.kind)} · <span class="mono">${esc(o.serial)}</span> · ordine n. ${esc(o.number)}</small></div><span class="in-days">${esc(o.amount)}</span></div>`;}).join('')}</div></div>`:''}`;
}

/* Full device card (modal) */
function openItal(serial){
 if(!IT)deriveItal();const r=IT?.devs.find(x=>x.serial===serial);if(!r)return;const d=r.d||{};
 const fact=(l,v,cls)=>`<div class="fact"><small>${l}</small><span${cls?` style="color:var(--${cls})"`:''}>${v||'<span class="muted">—</span>'}</span></div>`;
 const day=v=>v?dShort(parseDate(v)):'';
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<div class="modal-box wide dtr-card" role="dialog" aria-label="Dispositivo ${esc(r.serial)}">
  <div class="dtr-card-head"><div><span class="tag">Ital Retail · ${esc(r.type||'dispositivo')}</span><h3 class="mono">${esc(r.serial)}</h3><p>${esc(r.c?.name||r.label||'Nessun cliente indicato sul portale')}</p></div><button class="icon-btn" data-x aria-label="Chiudi">${icon('close')}</button></div>
  <div>${itVia(r)} ${itFlags(r)||'<span class="badge ok">regolare</span>'}</div>
  <div class="facts">
   ${fact('Cliente in Studio',r.c?esc(r.c.name):'')}${fact('Etichetta sul portale',esc(r.label))}
   ${fact('Abbonamento',r.noSub?'Non attivo':d.subFrom?`dal ${day(d.subFrom)} al ${day(d.subTo)}`:'Attivo',r.noSub?'bad':'')}${fact('Rinnovo',r.noSub?'':d.autoRenew?'Automatico (tacito)':d.subTo?`Scade ${rel(r.sub)}`:'',!r.noSub&&!d.autoRenew?tone(r.sub,IT_SUB_SOON):'')}
   ${fact('Firmware',esc(d.firmware||'')+(r.update?`${d.firmware?' · ':''}disponibile ${esc(r.update)}`:''),r.update?'due':'')}${fact('Stato RT',esc(d.rtState||''))}
   ${fact('Giornale elettronico',d.dgfeNumber?`DGFE n. ${d.dgfeNumber} · ${esc(d.dgfeSize||'')}`:'')}${fact('Spazio libero DGFE',d.dgfeFree>=0?d.dgfeFree+'%':'',r.low?'bad':'')}
   ${fact('Chiusure fatte',d.closures?'n. '+fmt(d.closures):'')}${fact('Dati della cassa aggiornati',d.dataAt?`${dtTime(d.dataAt)} · ${rel(r.seen)}`:d.readAt?'mai ricevuti dal portale':'',r.stale||r.never?'due':'')}
   ${fact('Certificato',d.certDue?`dal ${day(d.certFrom)} al ${day(d.certDue)} · ${rel(r.cert)}`:'',tone(r.cert,IT_CERT_SOON))}${fact('Attivazione',d.activated?`${day(d.activated)}${d.registered?' · censita il '+day(d.registered):''}`:'')}
   ${fact('Hardware',[d.hw?'versione '+esc(d.hw):'',esc(d.wifi||'')].filter(Boolean).join(' · '))}${fact('Impostazioni',[d.clock?esc(d.clock):'',d.seasonal?'stagionale: '+esc(d.seasonal):''].filter(Boolean).join(' · '))}
   ${r.m?fact('Macchina in Mastro',`${esc(r.m.model||'Macchina')}${r.m.serial?' · matr. '+esc(r.m.serial):''}`)+fact('Verificazione (Mastro)',r.m.verNext?`prossima ${dShort(r.m.verNext)}`:''):''}
  </div>
  <div class="d-section"><h3>Aggiornamenti installati <span>${(d.updates||[]).length}</span></h3>${(d.updates||[]).slice().reverse().map(u=>`<div class="dtr-ev"><time>${esc(day(u.at))}</time><span>dalla ${esc(u.from)} alla <b>${esc(u.to)}</b></span></div>`).join('')||'<p class="muted">Nessun aggiornamento registrato.</p>'}</div>
  <div class="d-section"><h3>Giornali elettronici (DGFE) <span>${(d.dgfe||[]).length}</span></h3>${(d.dgfe||[]).map(g=>`<div class="dtr-ev"><time>${esc(dtTime(g.at))}</time><span>n. ${esc(g.number)} · ${esc(g.duration)} · ${esc(g.status)}</span></div>`).join('')||'<p class="muted">Nessuna inizializzazione registrata.</p>'}</div>
  <div class="d-section"><h3>Ordini <span>${r.orders.length}</span></h3>${r.orders.map(o=>`<div class="dtr-ev"><time>${esc(day(o.date))}</time><span>${esc(o.kind)} · n. ${esc(o.number)} · ${esc(o.amount)}</span></div>`).join('')||'<p class="muted">Nessun ordine per questa cassa.</p>'}</div>
  <p class="sub">${d.readAt?'Scheda letta da Ital Retail '+esc(dTime(d.readAt)):'Scheda di dettaglio non ancora letta: arriva con il prossimo aggiornamento automatico.'}</p>
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

/* compact line under a Mastro machine (machines page) and section in the client panel */
function italClientSection(c){
 if(!c.ital?.length)return'';
 return`<div class="d-section"><h3>Portale Ital Retail <span>${c.ital.length}</span></h3>${c.ital.map(r=>`<div class="lic" data-ital="${esc(r.serial)}" style="cursor:pointer"><div class="lic-top"><strong class="mono">${esc(r.serial)} <small class="muted">${esc(r.type||'')}</small></strong>${itFlags(r)||'<span class="badge ok">regolare</span>'}</div>
  <small>${r.noSub?'Abbonamento non attivo':'Abbonamento'+(r.d?.subTo?' fino al '+dShort(parseDate(r.d.subTo)):' attivo')+(r.d?.autoRenew?' (rinnovo automatico)':'')}${r.d?.firmware?' · firmware '+esc(r.d.firmware):''}${r.d&&r.d.dgfeFree>=0?' · DGFE libero '+r.d.dgfeFree+'%':''}${r.d?.certDue?' · certificato '+dShort(parseDate(r.d.certDue)):''}</small></div>`).join('')}</div>`;
}

document.addEventListener('click',e=>{
 const t=e.target.closest?.('[data-itab],[data-ital]');if(!t)return;
 if(t.dataset.ital){e.stopPropagation();openItal(t.dataset.ital);return;}
 ui.itab=t.dataset.itab;ui.p=1;render(false);
},true);
document.addEventListener('keydown',e=>{if(e.key==='Enter'){const t=e.target.closest?.('tr[data-ital]');if(t){e.preventDefault();openItal(t.dataset.ital);}}});
