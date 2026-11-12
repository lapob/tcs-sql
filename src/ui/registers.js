'use strict';
// ───────────────────────── Registers: brand / model / category of every machine, portal coverage, Mastro fiscal detail.
// Mastro keeps the model as free text ("DRTCASH+", "DTR DRTCASH", "EPSON FP-81 II RT"): brands are recognised here,
// then each register of a brand that has a portal (DTR-Service, Ital Retail) is checked against that portal's list.
// Edit (E.D.IT.), Ditron, Custom and their models are no longer followed: they are left out of the whole app (Mastro keeps them).
// A model written with "RCH" stays: RCH sold some of the same names.
const RT_DROP=/DITRON|\bCUSTOM\b|\bQ3X|\bX ?KUBE|\bKUBE\b|SWEDA|\bE\.?D\.?IT\b|\bEDIT\b|\bZERO ?(5|7|55|8|TOP|\+)|\bZERO5|\bZERO7|\bZERO55|^SPOT\b|SPOT ZERO|SMARTY|\bNINA\b|\bFLY\b|GLOBE|QUADR|FLEXY|\bZIP\b|SPEEDY|MIKROPOS|^DREAM\b|SEVENT?\b|BUFFETTI|^POINT$|^VELVET|^MASTER\b|\bFLEA\b|PULSAR|NESSO|SUPREMA|^ONE$|EDIT ONE/;
const rtDropped=model=>{const s=String(model||'').toUpperCase().trim();return RT_DROP.test(s)&&!/\bRCH\b/.test(s);};
// sections: the brands with a portal (DTR, Ital Retail) and Epson on their own, every other register together
const RT_BRANDS=[
 {k:'dtr',l:'DTR',portal:'dtr',pl:'DTR-Service',hue:212,re:/\bDTR\b|\bDRT|DRT ?CASH|DRT ?PALM|DRT ?FRONT|^D(CASH|FLEX|PALM)\b/},
 {k:'ital',l:'Ital Retail',portal:'ital',pl:'Ital Retail',hue:152,re:/ITAL ?RETAIL|\bNICE\b|\bSPICE\b|ITAL ?START|^NEXT\b/},
 {k:'rch',l:'RCH',portal:'rch',pl:'RCH XDealer',hue:4,re:/\bRCH\b|PRINT!|\bONDA\b|WALL ?E\b|\bAPE ?\d|\bASSO\b|LDP-?\d/},
 {k:'epson',l:'Epson',hue:228,re:/EPSON|\bFP[- ]?\d/},
 {k:'axon',l:'Axon Micrelec',hue:270,re:/AXON|\bDADO\b|WY ?CASH|\bAURA\b|MICRELEC|HYDRA/},
 {k:'olivetti',l:'Olivetti',hue:340,re:/OLIVETTI|NETTUN/},
 {k:'sarema',l:'Sarema',hue:300,re:/SAREMA/},
 {k:'cashmatic',l:'Cashmatic',cat:'cash',hue:96,re:/CASHMATIC/},
 {k:'soft',l:'Software e licenze',cat:'soft',hue:250,re:/LICENZA|\bAPP\b|APP\.|SOFTWARE|RISTOQUICK|POSBY/},
 {k:'printer',l:'Stampanti',cat:'printer',hue:200,re:/^STAMP|STAMPANTE/},
 {k:'scale',l:'Bilance',cat:'scale',hue:120,re:/BILANCIA/},
];
const RT_OTHER={k:'other',l:'Registratori di cassa',hue:220};
const RT_OWN=new Set(['dtr','ital','rch','epson']);
const secOrd=k=>{const i=['dtr','ital','rch','epson','other'].indexOf(k);return i<0?9:i;};
const RT_CATS={rt:'Registratori di cassa',cash:'Casse automatiche',soft:'Software e licenze',printer:'Stampanti',scale:'Bilance'};
let RG=null;
function rtBrand(model){const s=String(model||'').toUpperCase();return RT_BRANDS.find(b=>b.re.test(s))||RT_OTHER;}
// "DTR DRTCASH" and "DRTCASH" are the same model: the brand words are dropped, spacing and punctuation normalised
function rtModel(model,b){let s=String(model||'').toUpperCase().replace(/[^A-Z0-9+!]+/g,' ').trim();
 if(b.k==='dtr')s=s.replace(/^DTR\s+/,'');else if(b.k==='axon')s=s.replace(/^(AXON|MICRELEC)\s+/,'');else if(b.k==='ital')s=s.replace(/^ITAL ?RETAIL\s+/,'');else if(b.k!=='other'&&b.k!=='soft'&&b.k!=='printer'&&b.k!=='scale')s=s.replace(new RegExp('^'+b.l.toUpperCase()+'\\s*'),'').replace(/^RCH\s*-?\s*/,'');
 return s.replace(/\s+/g,' ').trim()||String(model||'').trim()||'Senza modello';}
function deriveRegisters(){
 if(!D){RG=null;return;}
 const rt=new Map((state.rt||[]).map(r=>[r.machineId,r]));
 // a portal never synchronised says nothing about its registers: they are not "missing", the list is just not there yet
 const synced={dtr:(state.dtr||[]).length>0,ital:(state.ital||[]).length>0,rch:(state.rch||[]).length>0};
 const brands=new Map();
 for(const m of D.machines){
  const b0=rtBrand(m.model);const b=b0.cat||RT_OWN.has(b0.k)?b0:RT_OTHER;m.maker=b0.cat||b0.k==='other'?'':b0.l;
  m.brand=b;m.cat=b.cat||'rt';m.family=(m.maker&&b===RT_OTHER?m.maker+' ':'')+rtModel(m.model,b0);m.rt=rt.get(m.id)||null;
  m.fw=m.rt?.fields?.['Firmware']||m.dtr?.firmware||m.ital?.d?.firmware||m.rch?.firmware||'';
  m.portal=b.portal?(!synced[b.portal]?'nosync':m[b.portal]?'in':'out'):'none';
  if(!brands.has(b.k))brands.set(b.k,{...b,cat:b.cat||'rt',synced:b.portal?synced[b.portal]:true,all:0,active:0,in:0,out:0,models:new Map()});
  const g=brands.get(b.k);g.all++;if(m.active){g.active++;if(m.portal==='in')g.in++;else if(m.portal==='out')g.out++;const f=g.models.get(m.family)||{name:m.family,n:0};f.n++;g.models.set(m.family,f);}
 }
 const list=[...brands.values()].map(g=>({...g,models:[...g.models.values()].sort((a,b)=>b.n-a.n)})).sort((a,b)=>(a.cat==='rt'?0:1)-(b.cat==='rt'?0:1)||secOrd(a.k)-secOrd(b.k)||b.active-a.active);
 const sites=new Map();for(const s of state.sites||[]){if(!sites.has(s.clientCode))sites.set(s.clientCode,[]);sites.get(s.clientCode).push(s);}
 const riba=new Map();for(const r of state.riba||[]){if(!riba.has(r.clientCode))riba.set(r.clientCode,[]);riba.get(r.clientCode).push(r);}
 for(const c of D.clients){const ks=c.mastroCodes||[];c.sites=ks.flatMap(k=>sites.get(k)||[]);c.riba=ks.flatMap(k=>riba.get(k)||[]);}
 RG={brands:list,missing:D.machines.filter(m=>m.active&&m.portal==='out'),verifications:(state.rt||[]).reduce((n,r)=>n+(r.verificationCount||0),0)};
}
const brandMark=(b,size='')=>!b?'':`<span class="bmark ${size}" style="--h:${b.hue}">${esc(b.l.split(/\s+/).filter((w,i,a)=>a.length<2||w.length>2).map(w=>w[0]).join('').toUpperCase().slice(0,2))}</span>`;
function portalBadge(m){
 if(m.portal==='nosync')return`<span class="pbadge" data-tip="${esc(m.brand.pl)} non è ancora stato sincronizzato">${icon('clock')}${esc(m.brand.pl)} da sincronizzare</span>`;
 if(m.portal==='in')return`<span class="pbadge in" data-tip="Presente su ${esc(m.brand.pl)}">${icon('check')}${esc(m.brand.pl)}</span>`;
 if(m.portal==='out')return m.active?`<span class="pbadge out" data-tip="Registratore ${esc(m.brand.l)} attivo in Mastro ma non trovato su ${esc(m.brand.pl)}">${icon('alert')}Non su ${esc(m.brand.pl)}</span>`:`<span class="pbadge">Non su ${esc(m.brand.pl)}</span>`;
 return'<span class="muted" data-tip="Per questo marchio non c’è un portale collegato">—</span>';
}
function brandGrid(){
 const rt=RG.brands.filter(b=>b.cat==='rt'&&b.active),other=RG.brands.filter(b=>b.cat!=='rt'&&b.active);
 const card=(b,i)=>{const cov=b.portal&&b.synced&&b.active?Math.round(b.in/b.active*100):null;
  return`<button class="brand-card reveal${ui.mbrand===b.k?' on':''}" style="--i:${i};--h:${b.hue}" data-mbrand="${b.k}" aria-pressed="${ui.mbrand===b.k}">
   <div class="brand-top">${brandMark(b)}<div class="grow"><strong>${esc(b.l)}</strong><small>${plur(b.active,'attiva','attive')} · ${plur(b.models.length,'modello','modelli')}</small></div>${b.portal?`<span class="brand-portal">${esc(b.pl)}</span>`:''}</div>
   ${b.portal&&!b.synced?`<small class="cov-txt">${icon('clock')} ${esc(b.pl)} non ancora sincronizzato: collegalo dall’app Windows per il confronto</small>`:cov!==null?`<div class="cov"><i style="width:${cov}%"></i></div><small class="cov-txt"><b>${fmt(b.in)}</b> nel portale${b.out?` · <span class="warn">${fmt(b.out)} non iscritte</span>`:' · tutte iscritte'}</small>`:`<small class="cov-txt muted">${b.models.slice(0,3).map(x=>esc(x.name)).join(' · ')}</small>`}</button>`;};
 return`<div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Sezioni</h2><p>DTR, Ital Retail e RCH (che hanno un portale) ed Epson hanno la loro sezione; tutti gli altri sono in Registratori di cassa. Per i marchi con un portale controllo se ogni cassa attiva è iscritta al portale.${D.dropped?` <span class="muted">${plur(D.dropped,'macchina Edit, Ditron o Custom esclusa','macchine Edit, Ditron o Custom escluse')}.</span>`:''}</p></div>${ui.mbrand?`<button class="btn sm ghost" data-mbrand="">${icon('close')}Tutti i marchi</button>`:''}</div>
  <div class="brand-grid">${rt.map(card).join('')}</div>
  ${other.length?`<div class="cat-row">${other.map((b,i)=>`<button class="chip${ui.mbrand===b.k?' on':''}" data-mbrand="${b.k}">${brandMark(b,'xs')}${esc(RT_CATS[b.cat]||b.l)}<em>${fmt(b.active)}</em></button>`).join('')}</div>`:''}</div>`;
}
function machinesPage(){
 if(!ui.mtab)ui.mtab='active';
 // the archive can be rebuilt by paths that do not classify (local edits, saves): classify here when needed
 if(!RG||D.machines.some(m=>!m.brand))deriveRegisters();
 const brand=ui.mbrand?RG?.brands.find(b=>b.k===ui.mbrand):null;
 let list=D.machines;const q=norm(ui.q);
 if(brand)list=list.filter(m=>m.brand.k===brand.k);
 if(brand&&ui.mmodel)list=list.filter(m=>m.family===ui.mmodel);
 if(ui.mtab==='stale'){if(!ui.mstale)ui.mstale='todo';list=ui.mstale==='off'?list.filter(m=>m.flag==='dismessa'):ui.mstale==='on'?list.filter(m=>m.flag==='attiva'):list.filter(m=>m.stale);}
 else if(ui.mtab==='active')list=list.filter(m=>m.active);else if(ui.mtab==='sub')list=list.filter(m=>m.subscribed);else if(ui.mtab==='ver')list=list.filter(m=>m.active&&!m.stale&&m.verDays!==null&&m.verDays<=60);else if(ui.mtab==='end')list=list.filter(m=>m.subscribed&&m.endDays!==null&&m.endDays<=60);else if(ui.mtab==='gap')list=list.filter(m=>m.active&&m.portal==='out');
 if(q)list=list.filter(m=>norm([m.client,m.model,m.serial,m.fiscalSerial,m.logo,m.type,m.brand?.l,m.fw].join(' ')).includes(q));
 if(!['client','ver','end','model'].includes(ui.sort.key))ui.sort={key:'ver',dir:1};
 list=sortBy(list,{client:m=>m.client,model:m=>(m.brand?.l||'')+' '+m.family,ver:m=>m.verNext?+m.verNext:Infinity,end:m=>m.end?+m.end:Infinity});
 const rows=pageOf(list,16);const dueCls=n=>n===null?'':n<0?'bad':n<=60?'due':'';
 const stale=ui.mtab==='stale';if(stale)[...MSEL].forEach(id=>{if(!list.some(m=>m.id===id))MSEL.delete(id);});
 const act=D.machines.filter(m=>m.active).length;
 return`${hero('Tutti i <span class="grad">registratori di cassa</span>.','Registratori e dispositivi presso i clienti, divisi per marchio e modello, con presenza nei portali, verificazioni e abbonamenti.','')}
 ${D.machines.length?'':`<div class="banner reveal" style="--i:1"><span class="orb">${icon('register')}</span><div class="grow"><h3>Importa il parco macchine da MastroSQL</h3><p>Da Impostazioni → Importa da MastroSQL, su un PC dove Mastro è installato.</p></div><button class="btn" data-go="settings">Vai alle impostazioni ${icon('arrow')}</button></div>`}
 <div class="kpis" style="grid-template-columns:repeat(4,1fr)">${kpi('Macchine attive',act,`${fmt(D.machines.length)} in archivio`,'register','','machines',1)}${kpi('Non iscritte ai portali',RG?.missing.length||0,'Attive in Mastro, assenti da DTR-Service / Ital Retail','alert',RG?.missing.length?'t-due':'','machines',2)}${kpi('Verificazioni entro 60 gg',D.verDue.length,RG?.verifications?`${plur(RG.verifications,'verifica nello storico','verifiche nello storico')}`:'Comprese quelle già scadute','shield','t-due','machines',3)}${kpi('Abbonamenti in scadenza',D.contractDue.length,`${fmt(D.machines.filter(m=>m.subscribed).length)} in abbonamento`,'calendar','t-info','machines',4)}</div>
 ${D.stale.length&&!stale?`<div class="banner reveal stale-banner" style="--i:1"><span class="orb">${icon('alert')}</span><div class="grow"><h3>${plur(D.stale.length,'macchina da controllare','macchine da controllare')}</h3><p>Verificazione scaduta da oltre un anno: probabilmente non sono più dal cliente. Sono fuori dal Giro e dalle Scadenze finché non decidi.</p></div><button class="btn" data-mtab="stale">Controlla ${icon('arrow')}</button></div>`:''}
 ${RG&&D.machines.length&&!stale?brandGrid():''}
 <div class="card reveal" style="--i:5"><div class="card-head"><div><h2>${brand?brandMark(brand,'sm')+' '+esc(brand.l):'Macchine'}<span class="count">${fmt(list.length)}</span></h2>${brand?.portal?`<p>${fmt(brand.in)} su ${esc(brand.pl)} · ${plur(brand.out,'attiva non iscritta','attive non iscritte')}</p>`:''}</div>
  <div class="seg">${[['active','Attive'],['gap','Non iscritte'],['ver','Verifiche'],['end','Abbonamenti in scadenza'],['sub','In abbonamento'],['stale','Da controllare'],['all','Tutte']].filter(([k])=>k!=='stale'||D.stale.length||D.machines.some(m=>m.flag)).map(([k,l])=>`<button class="${ui.mtab===k?'active':''}" data-mtab="${k}">${l}${k==='gap'&&RG?.missing.length?` <em class="seg-n">${fmt(RG.missing.length)}</em>`:''}${k==='stale'&&D.stale.length?` <em class="seg-n">${fmt(D.stale.length)}</em>`:''}</button>`).join('')}</div></div>
 ${stale?staleBar(list):''}
 ${brand&&brand.models.length>1?`<div class="chips model-chips">${brand.models.slice(0,24).map(x=>`<button class="chip${ui.mmodel===x.name?' on':''}" data-mmodel="${esc(x.name)}">${esc(x.name)}<em>${fmt(x.n)}</em></button>`).join('')}</div>`:''}
 <div class="toolbar">${search('Cerca cliente, marchio, modello, matricola, firmware…')}</div>
 <div class="table-wrap"><table${stale?' class="sel-table"':''}><thead><tr>${stale?`<th class="sel"><input type="checkbox" data-msel-all aria-label="Seleziona tutte" ${list.length&&list.every(m=>MSEL.has(m.id))?'checked':''}${canFlag()?'':' disabled'}></th>`:''}${th('Cliente','client')}${th('Modello','model')}<th>Matricola</th><th>Portale</th>${th('Prossima verificazione','ver')}${th('Fine abbonamento','end')}<th>Stato</th></tr></thead><tbody>
 ${rows.map((m,k)=>`<tr class="reveal${stale&&MSEL.has(m.id)?' picked':''}" style="--i:${k}" ${m.code&&!stale?`data-client="${esc(m.code)}" tabindex="0"`:''}>${stale?`<td class="sel"><input type="checkbox" data-msel="${esc(m.id)}" aria-label="Seleziona ${esc(m.client)}" ${MSEL.has(m.id)?'checked':''}${canFlag()?'':' disabled'}></td>`:''}<td><div class="who">${markEl(m.client,m.code||m.client)}<div><strong>${stale&&m.code?`<button type="button" class="link-btn" data-client="${esc(m.code)}">${hl(m.client,ui.q)}</button>`:hl(m.client,ui.q)}</strong><small>${esc(m.c?.city||'')}</small></div></div></td>
  <td><div class="model-cell">${brandMark(m.brand,'xs')}<div><strong>${hl(m.family,ui.q)}</strong><small class="muted">${esc(m.brand.l)}${m.fw?' · fw '+esc(m.fw):''}</small></div></div></td>
  <td class="mono">${m.serial?`<button class="link-btn mono" data-device="${esc(m.serial)}" data-tip="Scheda unica: Mastro, portali, verifiche, licenze e storia">${hl(m.serial,ui.q)}</button>`:'—'}${m.fiscalSerial&&m.fiscalSerial!=='0'?`<br><small class="muted">${hl(m.fiscalSerial,ui.q)}</small>`:''}</td>
  <td>${portalBadge(m)}</td>
  <td>${m.verNext?`${dShort(m.verNext)}<br><small style="color:var(--${dueCls(m.verDays)==='bad'?'bad':dueCls(m.verDays)==='due'?'due':'text-3'})">${rel(m.verDays)}</small>`:'<span class="muted">—</span>'}</td>
  <td>${m.subscribed?(m.end?`${dShort(m.end)}<br><small style="color:var(--${dueCls(m.endDays)==='bad'?'bad':dueCls(m.endDays)==='due'?'due':'text-3'})">${rel(m.endDays)}</small>`:'In abbonamento'):'<span class="muted">Nessuno</span>'}</td>
  <td>${m.flag==='dismessa'?`<span class="badge" data-tip="${esc('Segnata come dismessa in Studio'+(state.machineFlags[m.id]?.by?' da '+state.machineFlags[m.id].by:''))}">Dismessa in Studio</span>`:m.stale?'<span class="badge due">Da controllare</span>':m.active?`<span class="badge ok"${m.flag==='attiva'?` data-tip="${esc('Confermata attiva in Studio'+(state.machineFlags[m.id]?.by?' da '+state.machineFlags[m.id].by:''))}"`:''}>Attiva</span>`:'<span class="badge">Dismessa</span>'}</td></tr>`).join('')||`<tr><td colspan="${stale?8:7}">${empty('register','Nessuna macchina',D.machines.length?'Modifica i filtri.':'Importa il parco macchine da MastroSQL.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}
// inside the DTR and Ital Retail sections: the active registers of that brand that Mastro has but the portal does not
function portalGapCard(portal){
 if(!RG)return'';const b=RT_BRANDS.find(x=>x.portal===portal);const list=RG.missing.filter(m=>m.brand.k===b.k);const g=RG.brands.find(x=>x.k===b.k);
 if(!g)return'';
 if(!g.synced)return`<div class="card reveal gap-card" style="--i:9"><div class="card-head"><div><h2>${brandMark(b,'sm')} Casse ${esc(b.l)} in Mastro<span class="count">${fmt(g.active)}</span></h2><p>Quando ${esc(b.pl)} sarà sincronizzato, qui vedrai quali di queste casse mancano sul portale.</p></div></div></div>`;
 return`<div class="card reveal gap-card" style="--i:9"><div class="card-head"><div><h2>${brandMark(b,'sm')} Casse ${esc(b.l)} in Mastro<span class="count">${fmt(g.active)}</span></h2><p>${list.length?`<b>${fmt(list.length)}</b> attive in Mastro non sono iscritte a ${esc(b.pl)}: di solito il servizio non è stato attivato per quella cassa (a volte la matricola in Mastro è scritta diversa).`:`Tutte le ${plur(g.active,'cassa attiva è presente su','casse attive sono presenti su')} ${esc(b.pl)}.`}</p></div>${list.length?`<button class="btn sm ghost" data-go="machines" data-mbrand-go="${b.k}">Vedi in Macchine ${icon('arrow')}</button>`:''}</div>
  <div class="cov big"><i style="width:${g.active?Math.round(g.in/g.active*100):0}%"></i></div>
  ${list.length?`<div class="gap-list">${list.slice(0,40).map(m=>`<button class="gap-row" data-device="${esc(m.serial||m.fiscalSerial||'')}"><span class="mono">${esc(m.serial||'—')}</span><span class="grow"><strong>${esc(m.client)}</strong><small>${esc(m.family)}${m.c?.city?' · '+esc(m.c.city):''}</small></span>${icon('chevron')}</button>`).join('')}</div>${list.length>40?`<p class="muted" style="font-size:12.5px;margin-top:8px">e altre ${fmt(list.length-40)}: elenco completo in Macchine → Non iscritte.</p>`:''}`:''}</div>`;
}
// fiscal detail and verification history in the device sheet
function rtSection(m){
 const r=m?.rt;if(!r)return'';const f=r.fields||{};const keys=Object.keys(f);
 const groups=[['Fiscale',['Telematico','Attivazione RT','Disattivazione RT','Dismissione RT','Dati fiscali RT','Trasmissione attiva dal','Stato (QR)','Censimento','Censimento QR del','Lotteria scontrini','Tracciato XML 7.0','Trasmissioni 12 mesi','RT automatico']],
  ['Firmware e giornale',['Firmware','Data firmware','Firmware secondario','Firmware disponibile','Firmware modello','Giornale elettronico (DGFE) dal','DGFE: giorni','Ultima chiusura Z','Azzeramenti','Ultimo azzeramento']],
  ['Verificazione e contratto',['Anno verificazione','Importo verificazione','Verificazione periodica (QR)','Tipo verificazione (QR)','Laboratorio (P.IVA)','Fine garanzia','Fine contratto','Mesi abbonamento','Costo','Mese fatturazione']]];
 const used=new Set(groups.flatMap(g=>g[1]));const rest=keys.filter(k=>!used.has(k));if(rest.length)groups.push(['Altro',rest]);
 const val=v=>/^\d{4}-\d{2}-\d{2}$/.test(v)?dShort(parseDate(v)):esc(v);
 return`${groups.map(([t,ks])=>{const row=ks.filter(k=>f[k]);return row.length?`<div class="d-section"><h3>${t} <span>Mastro</span></h3><div class="facts">${row.map(k=>`<div class="fact"><small>${esc(k)}</small><span>${val(f[k])}</span></div>`).join('')}</div></div>`:'';}).join('')}
  ${r.verificationCount?`<div class="d-section"><h3>Verifiche periodiche <span>${fmt(r.verificationCount)}</span></h3><div class="ver-list">${r.verifications.map(v=>`<div class="ver-row"><time>${v.date?dShort(parseDate(v.date)):'—'}</time><div class="grow"><strong>${esc(v.type||'Verifica')}${v.outcome?` · <span class="${/neg/i.test(v.outcome)?'danger-text':''}">${esc(v.outcome)}</span>`:''}</strong><small>${[v.technician&&'Tecnico '+v.technician,v.seal&&'Sigillo '+v.seal,v.report&&'Rapporto n. '+v.report,v.amount&&'€ '+v.amount].filter(Boolean).map(esc).join(' · ')}</small>${v.notes?`<small class="muted">${esc(v.notes)}</small>`:''}</div></div>`).join('')}</div>${r.verificationCount>r.verifications.length?`<p class="muted" style="font-size:12px;margin-top:6px">Ultime ${r.verifications.length} di ${fmt(r.verificationCount)}.</p>`:''}</div>`:''}`;
}
// shops and RiBa in the client drawer
function clientExtraSections(c,part){if(c&&c.sites===undefined)deriveRegisters();
 if(!c)return'';let h='';
 if(part!=='riba'&&c.sites?.length)h+=`<div class="d-section"><h3>Sedi e punti vendita <span>${c.sites.length}</span></h3>${c.sites.map(s=>`<div class="site-row${s.closed?' closed':''}">${icon('pin')}<div class="grow"><strong>${esc(s.name||'Sede')}${s.closed?' <span class="badge">chiusa</span>':''}</strong><small>${esc([s.address,[s.cap,s.city].filter(Boolean).join(' '),s.prov&&'('+s.prov+')'].filter(Boolean).join(', '))}</small>${s.phone||s.email||s.hours?`<small class="muted">${esc([s.phone,s.email,s.hours&&'Orario '+s.hours].filter(Boolean).join(' · '))}</small>`:''}</div>${s.address?`<a class="icon-btn sm" target="_blank" rel="noopener" href="https://www.google.com/maps/search/?api=1&query=${encodeURIComponent([s.address,s.cap,s.city,s.prov].filter(Boolean).join(' '))}" aria-label="Apri in Maps">${icon('external')}</a>`:''}</div>`).join('')}</div>`;
 if(part!=='sites'&&c.riba?.length&&canAccounting()){const open=c.riba.filter(r=>!r.paid||r.unpaid).sort((a,b)=>(a.due||'').localeCompare(b.due||''));const uns=c.riba.filter(r=>r.unpaid);
  if(open.length)h+=`<div class="d-section"><h3>RiBa ed effetti <span>${open.length}</span></h3>${uns.length?`<p class="danger-text" style="font-size:13px;margin:0 0 8px">${uns.length} insolut${uns.length===1?'o':'i'} · € ${fmtMoney(uns.reduce((s,r)=>s+r.amount,0))}</p>`:''}${open.slice(0,12).map(r=>`<div class="ver-row"><time>${r.due?dShort(parseDate(r.due)):'—'}</time><div class="grow"><strong>€ ${fmtMoney(r.amount)}${r.unpaid?' · <span class="danger-text">insoluto</span>':''}</strong><small>${esc([r.doc&&'Doc. '+r.doc,r.bank].filter(Boolean).join(' · '))}</small></div></div>`).join('')}</div>`;}
 return h;
}
const fmtMoney=n=>Number(n||0).toLocaleString('it-IT',{minimumFractionDigits:2,maximumFractionDigits:2});
// the DTR and Ital Retail sections gain the coverage card at the bottom
if(typeof dtrPage==='function'){const base=dtrPage;dtrPage=function(){return base()+portalGapCard('dtr');};}
if(typeof italPage==='function'){const base=italPage;italPage=function(){return base()+portalGapCard('ital');};}
if(typeof rchPage==='function'){const base=rchPage;rchPage=function(){return base()+portalGapCard('rch');};}
document.addEventListener('click',e=>{
 const t=e.target.closest?.('[data-mbrand],[data-mmodel],[data-mbrand-go]');if(!t)return;
 if('mbrandGo' in t.dataset){ui.mbrand=t.dataset.mbrandGo;ui.mtab='gap';ui.mmodel='';ui.p=1;return;}
 e.preventDefault();e.stopPropagation();
 if('mbrand' in t.dataset){ui.mbrand=ui.mbrand===t.dataset.mbrand?'':t.dataset.mbrand;ui.mmodel='';ui.p=1;}
 else{ui.mmodel=ui.mmodel===t.dataset.mmodel?'':t.dataset.mmodel;ui.p=1;}
 render(false);
},true);

// ───────────────────────── Portals and sign-ins: one place that says which access is missing and reopens it in one click.
// Sign-ins happen on the office sync station (Windows app); elsewhere the card only says what is missing.
function portalAccess(){
 const ago=s=>s?Math.floor((Date.now()-new Date(s))/864e5):null;
 const zLast=(state.sources||[]).map(s=>s.capturedAt).filter(Boolean).sort().pop()||'';
 const z={k:'z',name:'MY Zucchetti',mark:'Z',hue:4,act:'portal',sub:'Licenze, anagrafiche e ordini'};
 const zd=ago(zLast);z.state=!zLast?'off':zd>3?'bad':'ok';
 z.text=!zLast?'Mai sincronizzato':zd>3?`Ultima lettura ${plur(zd,'giorno','giorni')} fa: serve un nuovo accesso`:`Aggiornato ${dTime(zLast)}`;
 const portal=(k,name,st,act,hue,sub)=>{const s=st||{};return{k,name,mark:name.split(/[\s-]+/).filter(w=>w.length>2).map(w=>w[0]).join('').slice(0,2).toUpperCase(),hue,act,sub,
  state:s.error?'bad':!s.lastSync?'off':'ok',text:s.error?String(s.error):!s.lastSync?'Mai collegato':`Aggiornato ${dTime(s.lastSync)}`};};
 return[z,portal('dtr','DTR-Service',state.dtrStatus,'dtr',212,'Portale DTR'),portal('ital','Ital Retail',state.italStatus,'ital',152,'Portale Ital Retail'),portal('rch','RCH XDealer',state.rchStatus,'rch',4,'Portale RCH')];
}
function accessCard(onlyMissing){
 const list=portalAccess();const missing=list.filter(p=>p.state!=='ok');
 if(onlyMissing&&!missing.filter(p=>p.state!=='todo').length)return'';
 const head=missing.length?`${plur(missing.length,'accesso da rifare','accessi da rifare')}`:'Tutti i portali sono collegati';
 const btn=p=>p.state==='ok'?(WEB?'':`<button class="btn ghost sm" data-action="${p.act}">${icon('external')}Apri</button>`):!p.act?'':WEB?'<small class="muted">dalla postazione dell’ufficio</small>':`<button class="btn accent sm" data-action="${p.act}">${icon('external')}Accedi di nuovo</button>`;
 return`<div class="card reveal access-card" style="--i:1"><div class="card-head"><div><h2>Portali e accessi</h2><p>${head}. ${WEB?'Gli accessi si rifanno dall’app Windows della postazione di sincronizzazione.':'Accedi tu nella finestra del portale: Studio non salva le password e poi legge i dati in sola lettura.'}</p></div></div>
 <div class="access-list">${list.map(p=>`<div class="access-row s-${p.state}"><span class="bmark" style="--h:${p.hue}">${esc(p.mark)}</span><div class="grow"><strong>${esc(p.name)} <span class="access-dot" aria-hidden="true"></span></strong><small>${esc(p.sub)} · ${esc(p.text)}</small></div>${btn(p)}</div>`).join('')}</div></div>`;
}

// ───────────────────────── 2.9.0 machines to check: verification overdue for over a year → "dismessa" or "ancora attiva"
var MSEL=new Set();
const canFlag=()=>canAccounting(); // administrators and office (the server allows the same)
function staleBar(list){
 const views=[['todo','Da decidere',D.stale.length],['off','Segnate dismesse',D.machines.filter(m=>m.flag==='dismessa').length],['on','Confermate attive',D.machines.filter(m=>m.flag==='attiva').length]];
 const n=MSEL.size,todo=ui.mstale==='todo';
 return`<div class="stale-tools"><div class="chips">${views.map(([k,l,c])=>`<button class="chip${ui.mstale===k?' on':''}" data-mstale="${k}">${l}<em>${fmt(c)}</em></button>`).join('')}</div>
  <p class="sub">${todo?'La verificazione periodica è scaduta da più di un anno: di solito la cassa è stata sostituita o il cliente ha chiuso, ma in Mastro risulta ancora attiva. Decidi qui: <b>Dismesse</b> le toglie da tutti gli elenchi (Mastro non viene toccato), <b>Ancora attive</b> le rimette nel Giro e nelle Scadenze.':ui.mstale==='off'?'Macchine tolte dagli elenchi in Studio. Se un cliente resta senza macchine attive esce anche lui dall’elenco clienti (si ripristina da Clienti eliminati).':'Macchine confermate presso il cliente nonostante la verificazione molto scaduta: restano nel Giro e nelle Scadenze.'}</p>
  ${canFlag()?`<div class="bulk-bar${n?' on':''}"><span id="msel-n">${n?`<b>${fmt(n)}</b> ${n===1?'selezionata':'selezionate'}`:'Seleziona le macchine nella tabella'}</span><div class="btn-row">${todo?`<button class="btn sm" data-mflag="attiva"${n?'':' disabled'}>${icon('check')}Ancora attive</button><button class="btn sm danger" data-mflag="dismessa"${n?'':' disabled'}>${icon('close')}Dismesse</button>`:`<button class="btn sm" data-mflag=""${n?'':' disabled'}>${icon('repeat')}Rimetti da controllare</button>`}</div></div>`:'<p class="sub muted">Solo amministrazione e amministratori possono decidere.</p>'}</div>`;
}
function mselRefresh(){const n=MSEL.size;const bar=document.querySelector('.bulk-bar');if(!bar)return;bar.classList.toggle('on',n>0);
 const lab=bar.querySelector('#msel-n');if(lab)lab.innerHTML=n?`<b>${fmt(n)}</b> ${n===1?'selezionata':'selezionate'}`:'Seleziona le macchine nella tabella';bar.querySelectorAll('[data-mflag]').forEach(b=>b.disabled=!n);
 document.querySelectorAll('[data-msel]').forEach(c=>c.closest('tr')?.classList.toggle('picked',c.checked));}
function staleList(){return ui.mstale==='off'?D.machines.filter(m=>m.flag==='dismessa'):ui.mstale==='on'?D.machines.filter(m=>m.flag==='attiva'):D.stale;}
document.addEventListener('change',e=>{const c=e.target;
 if(c.matches?.('[data-msel]')){c.checked?MSEL.add(c.dataset.msel):MSEL.delete(c.dataset.msel);const all=document.querySelector('[data-msel-all]');if(all)all.checked=staleList().every(m=>MSEL.has(m.id));return mselRefresh();}
 if(c.matches?.('[data-msel-all]')){const l=staleList();l.forEach(m=>c.checked?MSEL.add(m.id):MSEL.delete(m.id));document.querySelectorAll('[data-msel]').forEach(x=>x.checked=c.checked);return mselRefresh();}});
document.addEventListener('click',async e=>{const t=e.target.closest?.('[data-mstale],[data-mflag]');if(!t)return;e.preventDefault();e.stopPropagation();
 if('mstale' in t.dataset){ui.mstale=t.dataset.mstale;MSEL.clear();ui.p=1;return render(false);}
 const st=t.dataset.mflag,ids=[...MSEL];if(!ids.length)return;
 if(st==='dismessa'){const lost=new Set(ids.map(id=>D.machines.find(m=>m.id===id)?.c).filter(c=>c&&c.machines.every(m=>!m.active||ids.includes(m.id))));
  if(!await ask({title:`Segnare ${ids.length===1?'la macchina':ids.length+' macchine'} come dismess${ids.length===1?'a':'e'}?`,text:`${ids.length===1?'Esce':'Escono'} da Giro, Scadenze ed elenchi. Mastro non viene modificato e puoi tornare indietro da “Segnate dismesse”.${lost.size?` ${lost.size===1?'Un cliente resta':lost.size+' clienti restano'} senza macchine attive ed esc${lost.size===1?'e':'ono'} dall’elenco clienti.`:''}`,ok:ids.length===1?'Segna dismessa':'Segna dismesse',danger:true}))return;}
 t.disabled=true;
 const apply=()=>{state.machineFlags={...(state.machineFlags||{})};for(const id of ids){if(st)state.machineFlags[id]={state:st,at:new Date().toISOString(),by:me?.name||''};else delete state.machineFlags[id];}};
 try{if(WEB)await post('/api/machines/flag',{ids,state:st});else send('machineFlag',{ids,state:st});apply();MSEL.clear();derive();if(typeof applyEdits==='function')applyEdits();if(typeof deriveRegisters==='function')deriveRegisters();
  toast(st==='dismessa'?`${plur(ids.length,'macchina segnata','macchine segnate')} come dismess${ids.length===1?'a':'e'}`:st==='attiva'?`${plur(ids.length,'macchina confermata','macchine confermate')} attiv${ids.length===1?'a':'e'}`:`${plur(ids.length,'macchina','macchine')} di nuovo da controllare`,'check');render(false);}
 catch(x){toast(x.message,'alert');t.disabled=false;}},true);
