'use strict';
/* Top Cash Studio — client card organised in tabs (summary, devices, licences, interventions, accounting) and the quick
   intervention actions used every day in the field: sign on the phone, print to take along, close, postpone.
   Loaded after app.js, registers.js and report.js. */

let CT={code:'',t:'sum',off:false};
const DEV_GROUPS=[['rt','Registratori di cassa','register'],['cash','Casse automatiche · Cashmatic','euro'],['soft','Software e app','grid'],['printer','Stampanti','print'],['scale','Bilance','box']];
const devCat=m=>m.cat&&DEV_GROUPS.some(g=>g[0]===m.cat)?m.cat:'rt';
const devName=m=>m.brand&&m.cat==='rt'&&m.brand.k!=='other'?`${m.brand.l} ${m.family||''}`.trim():(m.model||'Dispositivo');

function clientCard(c,fact){
 if(CT.code!==c.code)CT={code:c.code,t:'sum',off:false};
 if(c.sites===undefined&&typeof deriveRegisters==='function')deriveRegisters();
 const meta=c.meta;
 const act=c.machines.filter(x=>x.active),off=c.machines.length-act.length;
 const cur=c.records.filter(isCurrent);
 const open=c.interventions.filter(i=>i.source!=='mastro'&&i.status!=='Chiuso');
 const acc=canAccounting()&&typeof accClientSection==='function';
 const ver=act.filter(x=>x.verNext).sort((a,b)=>a.verNext-b.verNext)[0];
 const lic=cur.map(r=>({r,d:parseDate(r.expiry)})).filter(x=>x.d).sort((a,b)=>a.d-b.d)[0];
 const tabs=[['sum','Riepilogo','home',0],['dev','Dispositivi','register',act.length+(c.assets||[]).length],['lic','Licenze','key',cur.length],['int','Interventi','tool',open.length],...(acc?[['acc','Contabilità','euro',0]]:[])];
 if(!tabs.some(t=>t[0]===CT.t))CT.t='sum';
 // what a colleague needs at a glance before calling or leaving for the customer
 const brands=[...act.filter(x=>devCat(x)==='rt').reduce((m,x)=>m.set(x.brand?.l||'Altro',(m.get(x.brand?.l||'Altro')||0)+1),new Map())].map(([b,n])=>`${n} ${b}`).join(' · ');
 const tone=n=>n==null?'':n<0?'bad':n<=30?'due':'ok';
 const glance=[
  ['dev','register','Casse e dispositivi',act.length,brands||(act.length?'attivi':'nessuno attivo'),''],
  ['lic','key','Licenze Zucchetti',cur.length,lic?(dayDiff(lic.d)<0?`una scaduta ${rel(dayDiff(lic.d))}`:`prossima scadenza ${rel(dayDiff(lic.d))}`):'nessuna in corso',lic?tone(dayDiff(lic.d)):''],
  ['int','tool','Interventi aperti',open.length,open[0]?esc(open[0].title):'tutto in ordine',open.some(i=>i.priority==='Urgente'||i.priority==='Alta')?'bad':open.length?'due':'ok'],
  ['dev','calendar','Verificazione',ver?dShort(ver.verNext):'—',ver?rel(ver.verDays):'nessuna prevista',ver?tone(ver.verDays):''],
 ];
 const head=`<div class="d-head"><span class="tag">Scheda cliente</span><div class="btn-row">${canEditClients()?`<button class="btn sm ghost" data-edit-client="${esc(c.code)}">${icon('edit')}Modifica</button>`:''}<button class="icon-btn" data-close aria-label="Chiudi">${icon('close')}</button></div></div>`;
 const top=`<div class="d-profile">${markEl(c.name,c.code)}<div><h2>${esc(c.name)}</h2><p>${[c.city,c.code&&'cod. '+esc(c.code)].filter(Boolean).join(' · ')} ${badge(c.status)}</p></div></div>
 <div class="quick"><button ${telOk(c.phone)?`data-ext="tel:${esc(c.phone.replace(/[^\d+]/g,''))}"`:'disabled'}>${icon('phone')}Chiama</button><button ${c.email?`data-ext="mailto:${esc(c.email)}"`:'disabled'}>${icon('mail')}Email</button><button ${c.address?`data-ext="${mapsUrl(c)}"`:'disabled'}>${icon('pin')}Mappa</button><button data-newint="${esc(c.code)}">${icon('plus')}Intervento</button><button class="${meta.favorite?'on':''}" data-fav="${esc(c.code)}">${icon('star')}${meta.favorite?'Preferito':'Preferiti'}</button></div>
 <div class="glance">${glance.map(([t,ic,l,v,s,cls])=>`<button class="gl ${cls?'t-'+cls:''}" data-ctab="${t}"><span class="gl-ic">${icon(ic)}</span><small>${l}</small><strong>${typeof v==='number'?fmt(v):esc(v)}</strong><em>${s}</em></button>`).join('')}</div>
 <div class="ctabs" role="tablist">${tabs.map(([k,l,ic,n])=>`<button role="tab" aria-selected="${CT.t===k}" class="${CT.t===k?'on':''}" data-ctab="${k}">${icon(ic)}<span>${l}</span>${n?`<em>${fmt(n)}</em>`:''}</button>`).join('')}</div>`;
 let body='';
 if(CT.t==='sum')body=summaryTab(c,fact);
 else if(CT.t==='dev')body=devicesTab(c,off);
 else if(CT.t==='lic')body=licensesTab(c,cur);
 else if(CT.t==='int')body=interventionsTab(c);
 else if(CT.t==='acc')body=accClientSection(c)+(typeof clientExtraSections==='function'?clientExtraSections(c,'riba'):'');
 return`${head}<div class="d-body">${top}<div class="ctab-body" data-tab="${CT.t}">${body}</div></div>`;
}

function summaryTab(c,fact){
 const m=c.meta;
 return`<div class="d-section"><h3>Anagrafica ${c.registry||c.mastro?'':'<span class="badge" data-tip="Completa con una sincronizzazione Zucchetti o un’importazione da MastroSQL">parziale</span>'}${c.mastroOnly?'<span class="badge info" data-tip="Cliente presente solo in MastroSQL">Mastro</span>':''}${c.edit?`<span class="badge acc" data-tip="${esc(c.edit.created?'Creato a mano':'Dati corretti a mano')}${c.edit.updatedBy?' da '+esc(c.edit.updatedBy):''}">${c.edit.created?'creato a mano':'modificato'}</span>`:''}</h3><div class="facts">${fact('Telefono',c.phones.join(' · ')||c.phone,1)}${fact('Email',c.emails.join(' · ')||c.email,1)}${fact('Indirizzo',c.address,1)}${fact('Località',c.city)}${fact('Partita IVA',c.vat,1)}${fact('Codice fiscale',c.taxCode,1)}${c.pec?fact('PEC',c.pec,1):''}${c.contact?fact('Referente',c.contact):''}${c.category?fact('Categoria',c.category):''}${c.installer?fact('Installatore',c.installer):''}</div></div>
 ${typeof clientExtraSections==='function'?clientExtraSections(c,'sites'):''}
 <div class="d-section note-box"><h3>${icon('edit')} Note e promemoria</h3>
  <label class="field">Note per il team<textarea class="textarea" id="m-note" placeholder="Accordi, preferenze, orari, cose da ricordare…">${esc(c.note)}</textarea></label>
  <div class="form-row"><label class="field">Promemoria<select class="select" id="m-state">${['','Da contattare','In attesa','Completato'].map(s=>`<option value="${s}"${(m.followupState||'')===s?' selected':''}>${s||'Nessuno'}</option>`).join('')}</select></label><label class="field">Prossimo contatto<input type="date" class="input" id="m-date" value="${esc(m.nextContact||'')}"></label></div>
  <div class="btn-row" style="margin-top:10px"><button class="btn primary sm" data-save="${esc(c.code)}">${icon('check')}Salva</button><span class="muted" style="font-size:12px;align-self:center">${WEB?'Visibile a tutto il team.':'Salvato solo su questo computer.'}</span></div></div>
 ${c.mastro?`<details class="d-section extra-sec"><summary><h3>Dati MastroSQL <span class="muted">cod. ${esc(c.mastro.code)}</span></h3></summary><div class="facts">${fact('Indirizzo completo',[c.mastro.address,[c.mastro.cap,c.mastro.city,c.mastro.prov&&'('+c.mastro.prov+')'].filter(Boolean).join(' ')].filter(Boolean).join(', '),1)}${fact('Zona',c.mastro.zone)}${fact('Telefoni',c.mastro.phones.join(' · '),1)}${fact('Email',c.mastro.emails.join(' · '),1)}${fact('PEC',c.mastro.pec,1)}${fact('Codice fiscale',c.mastro.taxCode,1)}${Object.entries(c.mastro.extra||{}).map(([k,v])=>fact(esc(k.replace(/_/g,' ').toLowerCase()),v,1)).join('')}</div>
  ${c.mastro.notes&&c.mastro.notes.trim()!=='0'?`<p class="sub" style="margin-top:10px;white-space:pre-line">${esc(c.mastro.notes)}</p>`:''}</details>`:''}`;
}

function devCard(m){
 const v=m.verNext?`<span class="dv ${m.verDays<0?'bad':m.verDays<=60?'due':''}">${icon('calendar')}Verificazione ${rel(m.verDays)}</span>`:'';
 const sub=m.subscribed?`<span class="dv">${icon('sync')}Abbonamento${m.end?' fino al '+dShort(m.end):''}</span>`:'';
 const ge=m.dtr?`<span class="dv ${m.dtr.ge!=null&&m.dtr.ge<=30?'due':''}">${icon('file')}GE ${esc(m.dtr.geNumber||'—')} · ${dShort(parseDate(m.dtr.geDue))}</span>`:'';
 const last=m.dtr?.lastSync?`<span class="dv">${icon('clock')}Ultima chiusura ${dShort(parseDate(m.dtr.lastSync))}</span>`:'';
 return`<div class="dev-card${m.active?'':' off'}" data-device="${esc(m.serial||m.fiscalSerial)}" tabindex="0" role="button">
  <div class="dev-top">${m.brand?brandMark(m.brand,'sm'):''}<div class="grow"><strong>${esc(devName(m))}</strong><small class="mono">${esc(m.serial||'—')}${m.fiscalSerial&&m.fiscalSerial!==m.serial?' · '+esc(m.fiscalSerial):''}</small></div>${m.brand?.portal?portalBadge(m):''}${m.active?'':'<span class="badge">Dismessa</span>'}<span class="row-go">${icon('chevron')}</span></div>
  ${v||sub||ge||last?`<div class="dev-meta">${v}${ge}${last}${sub}</div>`:''}
  ${m.notes?`<p class="dev-note">${esc(m.notes)}</p>`:''}</div>`;
}
function devicesTab(c,off){
 const list=c.machines.filter(m=>CT.off||m.active).slice().sort((a,b)=>(b.active-a.active)||devName(a).localeCompare(devName(b)));
 const groups=DEV_GROUPS.map(([k,l,ic])=>[k,l,ic,list.filter(m=>devCat(m)===k)]).filter(g=>g[3].length);
 const portals=(typeof dtrClientSection==='function'?dtrClientSection(c):'')+(typeof italClientSection==='function'?italClientSection(c):'');
 return`${off?`<label class="switch-row"><input type="checkbox" data-coff ${CT.off?'checked':''}><span class="sw"></span><span>Mostra anche le dismesse <em>${fmt(off)}</em></span></label>`:''}
 ${groups.map(([k,l,ic,ms])=>`<div class="d-section dev-group"><h3>${icon(ic)} ${l} <span>${fmt(ms.filter(m=>m.active).length)}${ms.some(m=>!m.active)?` + ${fmt(ms.filter(m=>!m.active).length)} dismesse`:''}</span></h3><div class="dev-list">${ms.map(devCard).join('')}</div></div>`).join('')
  ||`<div class="d-section">${empty('register','Nessun dispositivo attivo',off?'Ci sono solo dispositivi dismessi: attiva la spunta qui sopra per vederli.':'Importa il parco macchine da MastroSQL.')}</div>`}
 ${portals?`<div class="sec-label">${icon('globe')} Dai portali</div>${portals}`:''}
 ${typeof workClientSection==='function'?workClientSection(c):''}`;
}

function licRow(r){const d=parseDate(r.expiry),n=dayDiff(d),e=D.licEdits?.[r.id];
 return`<div class="lic"><div class="lic-top"><strong>${esc(famLabel(famOfScope(r.scope)))} <span class="mono muted">${esc(r.serial)}</span></strong><span class="row-act">${badge(licenseStatus(r))}${licEditBtn(r)}</span></div>
 <div class="lic-meta"><div>Scadenza<b>${d?dShort(d):'—'}</b>${d?`<span style="color:var(--${n<0?'bad':n<=30?'due':'text-3'})">${rel(n)}</span>`:''}</div><div>Versione<b>${esc(r.version)||'—'}</b></div><div>Rilasciata<b>${esc((r.released||'—').slice(0,10))}</b></div><div>Aggiornata<b>${esc((r.updated||'—').slice(0,10))}</b></div>${r.blocked?`<div>Blocco<b>${esc(r.blocked)}</b></div>`:''}</div>
 ${e&&(e.note||e.expiry)?`<div class="comment">${icon('edit')}<div><p>${e.note?esc(e.note):'Scadenza corretta a mano'}</p><small>${e.expiry?'Scadenza corretta · ':''}${e.updatedBy?esc(e.updatedBy):''}</small></div></div>`:''}</div>`;}
function licensesTab(c,cur){
 const old=c.records.filter(r=>!isCurrent(r));
 return`<div class="d-section"><h3>${icon('key')} Licenze Zucchetti in corso <span>${fmt(cur.length)}</span></h3>${cur.slice().sort((a,b)=>(parseDate(a.expiry)||0)-(parseDate(b.expiry)||0)).map(licRow).join('')||'<p class="sub">Nessuna licenza in corso negli elenchi letti.</p>'}</div>
 ${old.length?`<details class="d-section extra-sec"><summary><h3>Storico licenze <span>${fmt(old.length)}</span></h3></summary>${old.map(licRow).join('')}</details>`:''}
 <div class="d-section"><h3>${icon('bag')} Ordini web <span>${fmt(c.orders.length)}</span></h3>${c.orders.slice(0,20).map(o=>`<div class="list-row" style="cursor:default"><div class="grow"><strong class="mono">${esc(o.number)} <span class="muted">· ${esc(o.serial||'')}</span></strong><small>${o.d?dShort(o.d):esc(o.date)} · ${esc(famLabel(o.family))}</small></div><span class="badge ${orderCls(o.status)}">${esc(o.status)}</span></div>`).join('')||'<p class="sub">Nessun ordine collegato.</p>'}</div>`;
}

// ───────────────────────── interventions: one row, every everyday action one tap away
function intRow(i,showClient){
 const app=i.source==='app',signed=!!i.signedAt,closed=i.status==='Chiuso';const n=i.d?dayDiff(i.d):null;
 const when=i.d?`${dShort(i.d)}${!closed&&n!==null?` · <b class="${n<0?'late':n===0?'today':''}">${n===0?'oggi':rel(n)}</b>`:''}`:'senza data';
 return`<div class="int-row${closed?' done':''}" data-int="${esc(i.id)}" tabindex="0" role="button">
  <div class="int-main"><span class="int-dot ${istCls(i.status)} ${prioCls(i.priority)?'p-'+prioCls(i.priority):''}"></span><div class="grow"><strong>${esc(i.title||'Intervento')}</strong><small>${showClient?esc(i.client||'')+' · ':''}${when}${i.technician?' · '+esc(i.technician):''}${i.source==='mastro'?' · storico Mastro':''}</small></div>${i.priority==='Urgente'||i.priority==='Alta'?`<span class="badge ${prioCls(i.priority)}">${esc(i.priority)}</span>`:''}<span class="badge ${istCls(i.status)}">${signed?'Firmato':esc(i.status)}</span></div>
  ${app?`<div class="int-acts">${signed?`<button data-iq="print" data-id="${esc(i.id)}">${icon('print')}Rapportino</button>${mailOn()?`<button data-iq="mail" data-id="${esc(i.id)}">${icon('mail')}Invia</button>`:''}`:`<button class="go" data-iq="sign" data-id="${esc(i.id)}">${icon('edit')}Firma</button><button data-iq="print" data-id="${esc(i.id)}">${icon('print')}Stampa</button>${closed?'':`<button data-iq="close" data-id="${esc(i.id)}">${icon('check')}Chiudi</button><button data-iq="later" data-id="${esc(i.id)}">${icon('clock')}Posticipa</button>`}`}</div>`:''}</div>`;
}
function interventionsTab(c){
 const app=c.interventions.filter(i=>i.source!=='mastro');const open=app.filter(i=>i.status!=='Chiuso'),done=app.filter(i=>i.status==='Chiuso'),hist=c.interventions.filter(i=>i.source==='mastro');
 return`<button class="btn accent block" data-newint="${esc(c.code)}">${icon('plus')}Nuovo intervento per ${esc(c.name)}</button>
 <div class="d-section"><h3>${icon('tool')} Da fare <span>${fmt(open.length)}</span></h3>${open.map(i=>intRow(i)).join('')||'<p class="sub">Nessun intervento aperto.</p>'}</div>
 ${done.length?`<div class="d-section"><h3>${icon('check')} Chiusi <span>${fmt(done.length)}</span></h3>${done.slice(0,8).map(i=>intRow(i)).join('')}${done.length>8?`<p class="sub">e altri ${done.length-8} nella pagina Interventi.</p>`:''}</div>`:''}
 ${hist.length?`<details class="d-section extra-sec"><summary><h3>Storico MastroSQL <span>${fmt(hist.length)}</span></h3></summary>${hist.slice(0,30).map(i=>intRow(i)).join('')}</details>`:''}`;
}

function pickDate(title,current){return new Promise(res=>{
 const t=new Date();t.setHours(0,0,0,0);const add=n=>{const d=new Date(t);d.setDate(d.getDate()+n);return d;};
 const mon=add(((8-t.getDay())%7)||7);
 const opts=[['Domani',add(1)],['Dopodomani',add(2)],['Lunedì prossimo',mon],['Tra una settimana',add(7)],['Tra due settimane',add(14)],['Tra un mese',add(30)]];
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<div class="modal-box pick-box" role="dialog" aria-label="${esc(title)}"><h3>${esc(title)}</h3><div class="pick-grid">${opts.map(([l,d])=>`<button class="pick" data-d="${isoDay(d)}"><strong>${l}</strong><small>${DF4.format(d)}</small></button>`).join('')}</div>
  <label class="field">Oppure scegli il giorno<input type="date" class="input" id="pick-date" value="${esc(current||isoDay(add(1)))}"></label>
  <div class="btn-row"><button class="btn ghost" data-d="">Annulla</button><button class="btn accent" data-d="input">${icon('check')}Conferma</button></div></div>`;
 document.body.append(m);hideTip();
 const done=v=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);res(v);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();done(null);}};document.addEventListener('keydown',key,true);
 m.addEventListener('click',e=>{if(e.target===m)return done(null);const b=e.target.closest('[data-d]');if(!b)return;const v=b.dataset.d;done(v==='input'?(m.querySelector('#pick-date').value||null):v||null);});
 m.querySelector('.pick')?.focus();
});}
async function saveIntQuick(id,patch,msg){
 const raw=state.interventions.find(x=>x.id===id);if(!raw)return;
 const item={...raw,...patch};
 try{if(WEB)await post('/api/interventions',item);else send('intervention',{item});toast(msg);}catch(e){toast(e.message,'alert');}
}
async function intQuick(action,id){
 const it=D.interventions.find(x=>x.id===id);if(!it)return;
 if(action==='sign')return openSignature({...it,c:it.c});
 if(action==='print')return printReport(id);
 if(action==='mail')return sendReportDialog({...it,c:it.c});
 if(action==='close')return saveIntQuick(id,{status:'Chiuso'},'Intervento chiuso');
 if(action==='later'){const d=await pickDate('Posticipa: '+(it.title||'intervento'),it.date);if(!d)return;
  return saveIntQuick(id,{date:d,status:it.status==='Aperto'?'Pianificato':it.status},`Intervento spostato a ${DF4.format(parseDate(d)||new Date(d))}`);}
}
document.addEventListener('click',e=>{
 const q=e.target.closest?.('[data-iq]');
 if(q){e.stopPropagation();e.preventDefault();intQuick(q.dataset.iq,q.dataset.id);return;}
 const t=e.target.closest?.('#drawer [data-ctab]');
 if(t){e.stopPropagation();e.preventDefault();CT.t=t.dataset.ctab;const d=$('#drawer');openClient(drawerCode,true);
  const bar=d.querySelector('.ctabs');if(bar&&d.scrollTop>bar.offsetTop-8)d.scrollTo({top:bar.offsetTop-8,behavior:reduced?'auto':'smooth'});
  d.querySelector(`.ctabs [data-ctab="${CT.t}"]`)?.focus({preventScroll:true});}
},true);
document.addEventListener('change',e=>{if(e.target.matches?.('#drawer [data-coff]')){CT.off=e.target.checked;openClient(drawerCode,true);}});
// the tab bar sticks right under the drawer header, whatever its real height (font size, buttons, device)
{const base=openClient;openClient=function(...a){base.apply(this,a);const d=$('#drawer'),h=d?.querySelector('.d-head');if(h)d.style.setProperty('--dhead',h.offsetHeight+'px');};}
