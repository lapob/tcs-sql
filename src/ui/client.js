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
 const brands=[...act.filter(x=>devCat(x)==='rt').reduce((m,x)=>m.set(x.brand?.l||'Altro',(m.get(x.brand?.l||'Altro')||0)+1),new Map())].map(([b,n])=>`${fmt(n)} ${b}`);
 const tone=n=>n==null?'':n<0?'bad':n<=30?'due':'ok';
 // every sentence follows the numbers: singular, plural, none, expired or upcoming
 const rtN=act.filter(x=>devCat(x)==='rt').length;
 const devSub=act.length?(brands.length?brands.join(' · ')+(act.length>rtN?' · '+plur(act.length-rtN,'altro','altri'):''):plur(act.length,'attivo','attivi'))+(off?` · ${plur(off,'dismesso','dismessi')}`:''):off?`${plur(off,'dismesso','dismessi')}, nessuno attivo`:'nessun dispositivo';
 const ld=cur.map(r=>dayDiff(parseDate(r.expiry))).filter(n=>n!==null&&!Number.isNaN(n));const exp=ld.filter(n=>n<0),next=ld.filter(n=>n>=0).sort((a,b)=>a-b)[0];
 const licSub=!cur.length?'nessuna in corso':cur.length===1?(ld.length?daysPhrase(ld[0],'scade','scaduta'):'senza scadenza'):
  exp.length===cur.length?'tutte scadute':exp.length?`${plur(exp.length,'scaduta','scadute')}${next!==undefined?', '+daysPhrase(next,'la prossima scade','')+'':''}`:next!==undefined?daysPhrase(next,'la prima scade',''):'senza scadenza';
 const licTone=exp.length?'bad':next!==undefined?tone(next):'';
 const urgent=open.filter(i=>i.priority==='Urgente'||i.priority==='Alta');
 const intSub=!open.length?'nessuno da fare':open.length===1?esc(open[0].title):urgent.length?`${plur(urgent.length,'urgente','urgenti')} · ${esc(urgent[0].title)}`:`il primo: ${esc(open[0].title)}`;
 const vers=act.filter(x=>x.verNext);
 const verSub=ver?daysPhrase(ver.verDays,'prevista','scaduta')+(vers.length>1?` · ${plur(vers.length,'cassa','casse')}`:''):'nessuna prevista';
 const glance=[
  ['dev','register',act.length===1?'Dispositivo attivo':'Dispositivi attivi',act.length,devSub,''],
  ['lic','key',cur.length===1?'Licenza Zucchetti':'Licenze Zucchetti',cur.length,licSub,licTone],
  ['int','tool',open.length===1?'Intervento aperto':'Interventi aperti',open.length,intSub,urgent.length?'bad':open.length?'due':'ok'],
  ['dev','calendar',vers.length>1?'Prossima verificazione':'Verificazione',ver?dShort(ver.verNext):'—',verSub,ver?tone(ver.verDays):''],
 ];
 // who follows the customer, under which conditions, and the money (only for who sees the accounting)
 const strip=[
  c.tech?`<span class="cs-item" data-tip="Dal${c.techFrom==='macchine'?'le macchine':c.techFrom==='anagrafica Mastro'?'l’anagrafica Mastro':'l’ultimo intervento'}${c.techs?.length>1?' · altri: '+esc(c.techs.filter(t=>t!==c.tech).join(', ')):''}">${icon('tool')}Tecnico <b>${esc(c.tech)}</b></span>`:`<span class="cs-item muted">${icon('tool')}Nessun tecnico assegnato</span>`,
  ...(c.kinds||[]).map(g=>svcChip(g)),
  c.pay?`<button class="cs-pay t-${c.pay.state}" data-ctab="acc">${icon('euro')}${esc(payPhrase(c.pay))}</button>`:'',
 ].filter(Boolean).join('');
 const head=`<div class="d-head"><span class="tag">Scheda cliente</span><div class="btn-row">${canEditClients()?`<button class="btn sm ghost" data-edit-client="${esc(c.code)}">${icon('edit')}Modifica</button>`:''}<button class="icon-btn" data-close aria-label="Chiudi">${icon('close')}</button></div></div>`;
 const top=`<div class="d-profile">${markEl(c.name,c.code)}<div><h2>${esc(c.name)}</h2><p>${[c.city,c.code&&'cod. '+esc(c.code)].filter(Boolean).join(' · ')} ${badge(c.status)}${c.dormant?' <span class="badge" data-tip="Nessun dispositivo attivo, licenza, tecnico, pagamento o attività recente">archiviato</span>':''}</p></div></div>
 <div class="cstrip">${strip}</div>
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
 const svc=m.svc?svcChip({k:m.svc.k,n:1,until:m.svc.until,labels:new Set(m.svc.label?[m.svc.label]:[])}):'';
 const tech=m.technician?`<span class="dv">${icon('users')}${esc(m.technician)}</span>`:'';
 return`<div class="dev-card${m.active?'':' off'}" data-device="${esc(m.serial||m.fiscalSerial)}" tabindex="0" role="button">
  <div class="dev-top">${m.brand?brandMark(m.brand,'sm'):''}<div class="grow"><strong>${esc(devName(m))}</strong><small class="mono">${esc(m.serial||'—')}${m.fiscalSerial&&m.fiscalSerial!==m.serial?' · '+esc(m.fiscalSerial):''}</small></div>${m.brand?.portal?portalBadge(m):''}${m.active?'':'<span class="badge">Dismessa</span>'}<span class="row-go">${icon('chevron')}</span></div>
  ${svc||tech||v||sub||ge||last?`<div class="dev-meta">${svc}${tech}${v}${ge}${last}${m.svc?'':sub}</div>`:''}
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

// ───────────────────────── words that follow the numbers: "1 licenza scaduta", "3 licenze", "nessuna"
const plur=(n,one,many,none)=>n===0&&none!==undefined?none:`${fmt(n)} ${n===1?one:many}`;
const daysPhrase=(n,future,past)=>n===null?'':n===0?`${future} oggi`:n===1?`${future} domani`:n===-1?`${past} ieri`:n>0?`${future} tra ${fmt(n)} giorni`:`${past} ${fmt(-n)} giorni fa`;
const listIt=a=>a.length<2?(a[0]||''):a.slice(0,-1).join(', ')+' e '+a[a.length-1];
const euro=n=>'€ '+Number(n||0).toLocaleString('it-IT',{minimumFractionDigits:2,maximumFractionDigits:2});

// ───────────────────────── service conditions from Mastro (lis_macc.SERVIZIO → servizi), technician, payments
const SVC=[
 {k:'rent',l:'Noleggio',ic:'sync',tone:'info',re:/NOLEGG/i},
 {k:'sub',l:'Abbonamento',ic:'check',tone:'ok',re:/ABBON|CANONE/i},
 {k:'warr',l:'Garanzia',ic:'shield',tone:'acc',re:/GARANZ/i},
 {k:'prepaid',l:'Ore prepagate',ic:'clock',tone:'acc',re:/PREPAGAT/i},
 {k:'install',l:'Installazione',ic:'tool',tone:'',re:/INSTALLAZ/i},
 {k:'call',l:'A chiamata',ic:'phone',tone:'due',re:/PAGAMENTO|CHIAMATA/i},
];
const SVC_BY=Object.fromEntries(SVC.map(s=>[s.k,s]));
// what applies to one machine: an open warranty wins, then the service written in Mastro, then the subscription flag
function svcOf(m){
 const w=m.warrantyEnd?parseDate(m.warrantyEnd):null;if(w&&dayDiff(w)>=0)return{k:'warr',until:w};
 const s=SVC.find(x=>x.re.test(m.service||''));
 const end=parseDate(m.contractUntil||'')||m.end||null;
 if(s&&s.k!=='warr')return{k:s.k,label:m.service,until:s.k==='call'?null:end};
 if(m.subscribed)return{k:'sub',label:m.service||'Abbonamento',until:end};
 if(s?.k==='warr')return{k:'call',label:'Garanzia scaduta',until:null,expired:w};
 return m.service?{k:'call',label:m.service,until:null}:null;
}
function deriveCommercial(){
 if(!D)return;const accOk=canAccounting();
 if(accOk&&typeof deriveAccounting==='function'&&typeof A!=='undefined'&&!A)try{deriveAccounting();}catch{}
 const yearsAgo=n=>{const d=new Date();d.setFullYear(d.getFullYear()-n);return d;};
 const recent=yearsAgo(3);
 for(const c of D.clients){
  const act=c.machines.filter(m=>m.active);
  // conditions of the active machines, grouped: "Noleggio (2) · Abbonamento fino al 31 dic"
  const kinds=new Map();
  for(const m of act){const s=svcOf(m);m.svc=s;if(!s)continue;const g=kinds.get(s.k)||{k:s.k,n:0,until:null,labels:new Set()};g.n++;if(s.until&&(!g.until||s.until>g.until))g.until=s.until;if(s.label)g.labels.add(s.label);kinds.set(s.k,g);}
  c.kinds=[...kinds.values()].sort((a,b)=>SVC.findIndex(x=>x.k===a.k)-SVC.findIndex(x=>x.k===b.k));
  // technician: the one on most active machines, else the customer's referent in Mastro, else the latest intervention
  const votes=new Map();for(const m of act)if(m.technician)votes.set(m.technician,(votes.get(m.technician)||0)+1);
  const top=[...votes].sort((a,b)=>b[1]-a[1])[0];
  const lastInt=c.interventions.find(i=>i.technician&&!/^\d+$/.test(i.technician));
  c.tech=top?.[0]||c.mastro?.agent||lastInt?.technician||'';
  c.techFrom=top?'macchine':c.mastro?.agent?'anagrafica Mastro':lastInt?'ultimo intervento':'';
  c.techs=[...votes.keys()];
  // payments (only for who sees the accounting)
  c.pay=null;
  if(accOk&&typeof A!=='undefined'&&A){const p=A.perClient.get(c.code);const unpaid=(c.riba||[]).filter(r=>r.unpaid);const ribaOpen=unpaid.reduce((s,r)=>s+(r.amount||0),0);
   if(p?.dues?.length||unpaid.length){const open=(p?.open||0),overdue=(p?.overdue||0)+ribaOpen;const next=(p?.dues||[]).filter(x=>x.open&&x.d).sort((a,b)=>a.d-b.d)[0];
    const closed=(p?.dues||[]).filter(x=>!x.open&&x.d).sort((a,b)=>b.d-a.d)[0];
    c.pay={state:overdue>0.005?'late':open>0.005?'due':'ok',open:open+ribaOpen,overdue,next,last:closed,unpaid:unpaid.length};}}
  // nothing at all to work on: no active device, licence, technician, payment or recent activity → archived (automatic and reversible)
  const recentInt=c.interventions.some(i=>i.source==='app'?i.status!=='Chiuso'||(i.d&&i.d>=recent):i.d&&i.d>=recent);
  const recentOrd=c.orders.some(o=>o.d&&o.d>=yearsAgo(2));
  c.dormant=!act.length&&!c.active.length&&!c.tech&&!recentInt&&!recentOrd&&!(c.pay&&c.pay.open>0.005)&&!c.edit?.created&&!c.meta.favorite&&!c.note&&!(c.dtr?.length)&&!(c.ital?.length)&&!(c.assets?.length);
  if(c.tech||c.kinds.length)c.search+=' '+norm([c.tech,...c.kinds.map(k=>SVC_BY[k.k].l)].join(' '));
 }
 D.dormant=D.clients.filter(c=>c.dormant).length;
 D.techList=[...new Set(D.clients.map(c=>c.tech).filter(Boolean))].sort((a,b)=>a.localeCompare(b,'it'));
}
{const base=deriveRegisters;deriveRegisters=function(...a){base.apply(this,a);try{deriveCommercial();}catch(e){console.error(e);}};}

const svcChip=(g,short)=>{const s=SVC_BY[g.k];const lab=g.k==='sub'&&g.labels.size===1?[...g.labels][0]:s.l;
 return`<span class="svc t-${s.tone}" data-tip="${esc([...g.labels].join(' · ')||s.l)}">${icon(s.ic)}${esc(lab)}${g.n>1?` <em>${fmt(g.n)}</em>`:''}${!short&&g.until?` · ${g.k==='warr'?'fino al':'scade il'} ${dShort(g.until)}`:''}</span>`;};
// one sentence about the money, with the right words for the case
function payPhrase(p){
 if(!p)return'';
 if(p.state==='late')return`Deve pagare ${euro(p.open)}${p.overdue>=p.open-0.005?', tutto scaduto':`, di cui ${euro(p.overdue)} scaduti`}${p.unpaid?` (${plur(p.unpaid,'RiBa insoluta','RiBa insolute')})`:''}`;
 if(p.state==='due')return`Da pagare ${euro(p.open)}${p.next?` · ${daysPhrase(dayDiff(p.next.d),'scade','scaduta')}`:''}`;
 return`In regola${p.last?` · ultimo pagamento ${dShort(p.last.d)}`:''}`;
}
