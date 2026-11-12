'use strict';
// ───────────────────────── organised work: inventory of PCs and systems, guided renewals, the day's route,
// data check (duplicates, VAT, unlinked devices, missing contacts) and the single sheet of every register / serial.
let WK={inv:'all',invQ:'',ren:'open',renQ:'',route:undefined,kind:'',open:new Set(),check:'dup',more:{}};
const AS_KINDS=['PC','Server','Stampante','Router / firewall','NAS','Tablet','Licenza software','Altro'];
const AS_ICON={PC:'grid',Server:'box',Stampante:'print','Router / firewall':'sync',NAS:'folder',Tablet:'phone','Licenza software':'key',Altro:'tool'};
const REN_STATES=[['Da contattare','info'],['Contattato','acc'],['Da sollecitare','due'],['Rinnovato','ok'],['Perso','bad']];
const isoDay=d=>d?`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`:'';
function sendWork(action,data,url){
 if(!WEB){if(!host)return toast('Funzione disponibile nell’app Windows.','alert'),false;host.postMessage({action,...data});return true;}
 return post(url,data).then(r=>r||true).catch(e=>{toast(e.message,'alert');return false;});
}
function moreBtn(key,shown,total){return total>shown?`<div class="btn-row" style="justify-content:center;margin-top:12px"><button class="btn sm ghost" data-w-more="${key}">Mostra altri ${fmt(Math.min(60,total-shown))} di ${fmt(total-shown)}</button></div>`:'';}
const limit=(key,n=60)=>n+(WK.more[key]||0);

// ── derive: assets on customers, warranty / expiry dates
function deriveWork(){
 if(!D)return;
 for(const c of D.clients)c.assets=[];
 D.assets=(state.assets||[]).map(a=>{const c=D.byCode.get(a.clientCode)||null;const war=parseDate(a.warrantyTo),exp=parseDate(a.expiry);
  const x={...a,c,client:c?.name||'Cliente '+a.clientCode,war,warDays:dayDiff(war),exp,expDays:dayDiff(exp)};if(c)c.assets.push(x);return x;});
 for(const c of D.clients)if(c.assets.length)c.search+=' '+norm(c.assets.map(a=>[a.name,a.brand,a.serial,a.ip].join(' ')).join(' '));
 D.assetsDue=D.assets.filter(a=>a.active&&((a.warDays!==null&&a.warDays<=60)||(a.expDays!==null&&a.expDays<=60)));
}
// agenda kinds added to the shared deadlines list (Scadenze, Da fare oggi, calendar)
function workAgenda(add){
 for(const a of D.assets||[]){if(!a.active)continue;const at=a.code||a.clientCode,attr=a.c?`data-client="${esc(a.c.code)}"`:'';
  if(a.war)add('war',a.war,a.warDays,a.client,`${a.kind} ${a.name}${a.serial?' · '+a.serial:''}`,attr,a.id);
  if(a.exp)add('aexp',a.exp,a.expDays,a.client,`${a.kind} ${a.name}`,attr,a.id);}
}

// ───────────────────────── 1. inventory
function inventoryPage(){
 const all=D.assets||[];const q=norm(WK.invQ.trim());
 let list=all.filter(a=>WK.inv==='all'?a.active:WK.inv==='due'?D.assetsDue.includes(a):WK.inv==='off'?!a.active:a.kind===WK.inv&&a.active);
 if(q)list=list.filter(a=>norm([a.client,a.name,a.brand,a.serial,a.ip,a.os,a.location,a.notes].join(' ')).includes(q));
 list.sort((a,b)=>a.client.localeCompare(b.client,'it')||a.name.localeCompare(b.name,'it'));
 const kinds=AS_KINDS.filter(k=>all.some(a=>a.kind===k&&a.active));const n=limit('inv');
 const wl=all.filter(a=>a.active&&a.warDays!==null&&a.warDays>=0&&a.warDays<=60).length,ex=all.filter(a=>a.active&&((a.warDays!==null&&a.warDays<0)||(a.expDays!==null&&a.expDays<0))).length;
 return`${hero('<span class="grad">PC e sistemi</span>.','L’inventario informatico dei clienti: PC, server, stampanti, rete e licenze software, con garanzie e scadenze nell’agenda.',`<button class="btn accent" data-asset-new>${icon('plus')}Aggiungi</button>`)}
 ${explain('inventory','Le garanzie e le scadenze delle licenze software compaiono in <strong>Scadenze</strong> e in <strong>Da fare oggi</strong>. Le password non si salvano qui: tienile nel gestore delle password.')}
 <div class="kpis">${kpi('Elementi',all.filter(a=>a.active).length,'in uso presso i clienti','grid','info','inventory',2)}${kpi('Clienti',new Set(all.filter(a=>a.active).map(a=>a.clientCode)).size,'con almeno un elemento','users','acc','inventory',3)}${kpi('Garanzie',wl,'in scadenza entro 60 giorni','shield','due','inventory',4)}${kpi('Scadute',ex,'garanzie o licenze già scadute','alert','bad','inventory',5)}</div>
 <div class="card reveal" style="--i:6"><div class="card-head"><div><h2>Inventario<span class="count">${fmt(list.length)}</span></h2></div>
  <div class="seg">${[['all','Tutto'],['due','In scadenza'],...kinds.map(k=>[k,k]),['off','Dismessi']].map(([k,l])=>`<button class="${WK.inv===k?'active':''}" data-w-inv="${esc(k)}">${esc(l)}</button>`).join('')}</div></div>
  <label class="search" style="margin:6px 0 12px">${icon('search')}<input data-w-q="invQ" placeholder="Cerca cliente, modello, matricola, IP…" value="${esc(WK.invQ)}" autocomplete="off"></label>
  ${list.length?list.slice(0,n).map(assetRow).join('')+moreBtn('inv',n,list.length):`<div class="empty"><span class="orb">${icon('grid')}</span><h3>${all.length?'Nessun elemento':'Inventario vuoto'}</h3><p>${all.length?'Cambia filtro o ricerca.':'Aggiungi i PC, le stampanti e gli apparati dei tuoi clienti: li ritrovi nella loro scheda.'}</p></div>`}</div>`;
}
function assetBadges(a){
 const b=[];if(!a.active)b.push('<span class="badge">Dismesso</span>');
 if(a.warDays!==null)b.push(`<span class="badge ${a.warDays<0?'bad':a.warDays<=60?'due':'ok'}">Garanzia ${a.warDays<0?'scaduta':rel(a.warDays)}</span>`);
 if(a.expDays!==null)b.push(`<span class="badge ${a.expDays<0?'bad':a.expDays<=60?'due':'info'}">Scade ${rel(a.expDays)}</span>`);
 return b.join(' ');
}
function assetRow(a){
 return`<div class="list-row" data-asset="${esc(a.id)}" tabindex="0"><span class="orb sm as-orb">${icon(AS_ICON[a.kind]||'tool')}</span><div class="grow"><strong>${esc(a.name)} <small class="muted">${esc([a.brand,a.kind].filter(Boolean).join(' · '))}</small></strong><small>${esc(a.client)}${a.serial?' · <span class="mono">'+esc(a.serial)+'</span>':''}${a.ip?' · '+esc(a.ip):''}${a.location?' · '+esc(a.location):''}</small></div><span class="row-badges">${assetBadges(a)}</span></div>`;
}
function workClientSection(c){
 const as=c.assets||[];
 return`<div class="d-section"><h3>PC e sistemi <span>${as.length?as.length+' ':''}<button class="btn sm ghost" data-asset-new="${esc(c.code)}">${icon('plus')}Aggiungi</button></span></h3>${as.map(assetRow).join('')||'<p class="muted">Nessun PC, stampante o apparato registrato.</p>'}</div>`;
}
function clientOptions(){return D.clients.map(c=>`<option value="${esc(c.name)}${c.city?' — '+esc(c.city):''}" data-code="${esc(c.code)}"></option>`).join('');}
function clientFromLabel(v){v=String(v||'').trim();const c=D.clients.find(c=>(c.name+(c.city?' — '+c.city:''))===v)||D.clients.find(c=>c.name===v);return c||null;}
async function openAssetEditor(id,clientCode){
 const a=id?(D.assets||[]).find(x=>x.id===id):null;const c=D.byCode.get(a?.clientCode||clientCode||'');
 const sel=(n,l,opts,v)=>`<label class="field">${l}<select class="select" name="${n}">${opts.map(o=>`<option${o===v?' selected':''}>${esc(o)}</option>`).join('')}</select></label>`;
 const body=`<div class="form-grid">
  <label class="field span-2">Cliente<input class="input" name="client" list="as-clients" required value="${esc(c?c.name+(c.city?' — '+c.city:''):'')}" placeholder="Scrivi per cercare" autocomplete="off"></label><datalist id="as-clients">${clientOptions()}</datalist>
  ${sel('kind','Tipo',AS_KINDS,a?.kind||'PC')}${fieldHtml('name','Nome o modello','text',a?.name,1,'required maxlength="120" autocomplete="off" placeholder="PC cassa, HP ProDesk 400…"')}
  ${fieldHtml('brand','Marca','text',a?.brand,1,'maxlength="60" autocomplete="off"')}${fieldHtml('serial','Matricola / seriale','text',a?.serial,1,'maxlength="80" autocomplete="off"')}
  ${fieldHtml('location','Dove si trova','text',a?.location,1,'maxlength="120" autocomplete="off" placeholder="Cassa 1, ufficio, magazzino…"')}${fieldHtml('ip','Indirizzo IP','text',a?.ip,1,'maxlength="60" autocomplete="off" inputmode="decimal"')}
  ${fieldHtml('os','Sistema / versione','text',a?.os,1,'maxlength="80" autocomplete="off" placeholder="Windows 11 Pro, firmware 2.1…"')}${fieldHtml('purchased','Acquisto','date',a?.purchased)}
  ${fieldHtml('warrantyTo','Garanzia fino al','date',a?.warrantyTo)}${fieldHtml('expiry','Scadenza (licenza, contratto)','date',a?.expiry)}
  <label class="field span-2">Note<textarea class="input" name="notes" rows="3" maxlength="1000">${esc(a?.notes||'')}</textarea></label>
  <label class="field">Stato<select class="select" name="active"><option value="1">In uso</option><option value="0"${a&&!a.active?' selected':''}>Dismesso</option></select></label></div>`;
 const v=await formPanel({title:a?a.name:'Nuovo elemento',tag:'PC e sistemi',sub:a?esc(a.client):'Le password non si salvano qui.',body,ok:'Salva',extra:a&&canDelete()?`<button type="button" class="btn ghost danger-text" data-act="delete">Elimina</button>`:''});
 if(!v)return;
 if(v.__act==='delete'){if(confirm('Togliere «'+a.name+'» dall’inventario?'))sendWork('assetDelete',{id:a.id},'/api/assets/delete');return;}
 const cl=clientFromLabel(v.client);if(!cl){toast('Scegli il cliente dall’elenco.','alert');return openAssetEditor(id,clientCode);}
 const asset={id:a?.id||'',clientCode:cl.code,kind:v.kind,name:v.name,brand:v.brand,serial:v.serial,location:v.location,ip:v.ip,os:v.os,purchased:v.purchased,warrantyTo:v.warrantyTo,expiry:v.expiry,notes:v.notes,active:v.active!=='0'};
 if(await sendWork('assetSave',{asset},'/api/assets/save')&&WEB)toast('Inventario aggiornato.');
}
const canDelete=()=>!WEB||['admin','office'].includes(me?.role);

// ───────────────────────── 2. guided renewals
const REN_KINDS=new Set(['lic','con','rtu','isub','aexp','war','ver','cert','icert']);
function renewalItems(){
 const out=agendaItems(-90,120).filter(x=>REN_KINDS.has(x.k));const marks=state.renewals||{};
 for(const x of out){x.key=x.key||`${x.k}|${x.ref||x.sub}|${isoDay(x.d)}`;x.mark=marks[x.key]||null;x.status=x.mark?.status||'';x.c=renClient(x);}
 return out;
}
function renClient(x){const m=/data-client="([^"]+)"/.exec(x.attr||'');if(m)return D.byCode.get(m[1].replace(/&quot;/g,'"'))||null;
 const s=/data-(dtr|ital)="([^"]+)"/.exec(x.attr||'');if(s){const list=s[1]==='dtr'?(DT?.regs||[]):(IT?.devs||[]);return list.find(r=>r.serial===s[2])?.c||null;}return null;}
function renewalsPage(){
 const all=renewalItems();const q=norm(WK.renQ.trim());
 const open=x=>!['Rinnovato','Perso'].includes(x.status);
 let list=all.filter(x=>WK.ren==='open'?open(x):WK.ren==='late'?open(x)&&x.n<0:WK.ren==='recall'?x.mark?.nextContact&&days(x.mark.nextContact)<=0&&open(x):WK.ren==='done'?x.status==='Rinnovato':WK.ren==='lost'?x.status==='Perso':true);
 if(q)list=list.filter(x=>norm(x.title+' '+x.sub).includes(q));
 const n=limit('ren');const recall=all.filter(x=>x.mark?.nextContact&&days(x.mark.nextContact)<=0&&open(x)).length;
 const done=all.filter(x=>x.status==='Rinnovato').length,lost=all.filter(x=>x.status==='Perso').length,closed=done+lost;
 return`${hero('<span class="grad">Rinnovi</span>.','Ogni scadenza dei prossimi 4 mesi (e quelle passate da poco) con il suo stato: chi hai contattato, chi va sollecitato, chi ha rinnovato.','')}
 <div class="kpis">${kpi('Da gestire',all.filter(open).length,'scadenze senza esito','task','info','renewals',1)}${kpi('Scadute senza esito',all.filter(x=>open(x)&&x.n<0).length,'da sollecitare subito','alert','bad','renewals',2)}${kpi('Da richiamare oggi',recall,'richiami fissati','phone','due','renewals',3)}${kpi('Rinnovati',done,closed?`${Math.round(done*100/closed)}% di quelli chiusi`:'nessun esito ancora','check','ok','renewals',4)}</div>
 <div class="card reveal" style="--i:5"><div class="card-head"><div><h2>Scadenze<span class="count">${fmt(list.length)}</span></h2></div>
  <div class="seg">${[['open','Da gestire'],['late','Scadute'],['recall','Da richiamare'],['done','Rinnovati'],['lost','Persi'],['all','Tutte']].map(([k,l])=>`<button class="${WK.ren===k?'active':''}" data-w-ren="${k}">${l}</button>`).join('')}</div></div>
  <label class="search" style="margin:6px 0 12px">${icon('search')}<input data-w-q="renQ" placeholder="Cerca cliente o matricola…" value="${esc(WK.renQ)}" autocomplete="off"></label>
  ${list.slice(0,n).map(renRow).join('')||`<div class="empty"><span class="orb">${icon('check')}</span><h3>Niente da fare qui</h3><p>Nessuna scadenza in questo elenco.</p></div>`}${moreBtn('ren',n,list.length)}</div>`;
}
function renRow(x){
 const [label,cls]=AG_KIND[x.k]||['Scadenza',''];const st=REN_STATES.find(s=>s[0]===x.status);const nc=x.mark?.nextContact;
 return`<div class="list-row ren-row" data-ren="${esc(x.key)}" tabindex="0"><span class="date-chip ${x.n<0?'bad':x.n<=30?'due':''}"><b>${x.d.getDate()}</b><span>${DF3.format(x.d)}</span></span>
  <div class="grow"><strong>${esc(x.title)}</strong><small><span class="badge ${cls}">${label}</span> ${esc(x.sub)} · ${rel(x.n)}${x.mark?.note?` · <i>${esc(x.mark.note)}</i>`:''}</small></div>
  <span class="row-badges">${nc?`<span class="badge ${days(nc)<=0?'due':''}">Richiamo ${dShort(parseDate(nc))}</span>`:''}${st?`<span class="badge ${st[1]}">${esc(st[0])}</span>`:'<span class="badge">Nessun esito</span>'}</span></div>`;
}
const renMail=(x)=>{const c=x.c;const to=c?.email||c?.emails?.[0]||'';const [label]=AG_KIND[x.k]||['Scadenza'];
 const subj=`${label} in scadenza il ${dShort(x.d)}`;
 const body=`Buongiorno,\n\nle ricordiamo che il ${dShort(x.d)} scade: ${label.toLowerCase()} (${x.sub}).\nPer rinnovare senza interruzioni può rispondere a questa email o chiamarci: ci pensiamo noi.\n\nCordiali saluti\nTop Cash Service S.r.l.`;
 return`mailto:${encodeURIComponent(to)}?subject=${encodeURIComponent(subj)}&body=${encodeURIComponent(body)}`;};
async function openRenewal(key){
 const x=renewalItems().find(r=>r.key===key);if(!x)return;const c=x.c;const [label]=AG_KIND[x.k]||['Scadenza'];
 const steps=(x.mark?.steps||[]).slice().reverse();const tel=c?.phone||c?.phones?.[0]||'';
 const body=`<div class="ren-actions">${tel?`<a class="btn sm" href="tel:${esc(tel.replace(/\s/g,''))}">${icon('phone')}${esc(tel)}</a>`:''}<a class="btn sm" href="${esc(renMail(x))}">${icon('mail')}Email di promemoria</a>${c?`<button type="button" class="btn sm ghost" data-act="client">${icon('users')}Scheda cliente</button>`:''}</div>
  <div class="form-grid"><label class="field span-2">Esito<div class="seg ren-seg">${REN_STATES.map(([s])=>`<label><input type="radio" name="status" value="${esc(s)}"${(x.status||'Da contattare')===s?' checked':''}><span>${esc(s)}</span></label>`).join('')}</div></label>
  ${fieldHtml('nextContact','Richiama il','date',x.mark?.nextContact||'')}${fieldHtml('note','Nota','text',x.mark?.note||'',1,'maxlength="300" autocomplete="off" placeholder="Es. vuole il preventivo, richiamare dopo le 15"')}</div>
  ${steps.length?`<div class="d-section"><h3>Storia <span>${steps.length}</span></h3>${steps.map(s=>`<div class="dtr-ev"><time>${esc(dTime(s.at))}</time><span><b>${esc(s.status||'Nota')}</b>${s.note?' · '+esc(s.note):''} <span class="muted">· ${esc(s.by)}</span></span></div>`).join('')}</div>`:''}`;
 const v=await formPanel({title:x.title,tag:label,sub:`${esc(x.sub)} · scade il ${dShort(x.d)} (${rel(x.n)})`,body,ok:'Salva esito'});
 if(!v)return;if(v.__act==='client'){if(c)openClient(c.code);return;}
 if(await sendWork('renewalSave',{key,status:v.status||'',note:v.note||'',nextContact:v.nextContact||''},'/api/renewals/save')&&WEB)toast('Esito salvato.');
}

// ───────────────────────── 3. the day's route
// rank orders the work: 0 urgent (urgent job, overdue verification), 1 soon (high priority, verification within 30 days,
// journal almost full), 2 normal job, 3 register reporting errors (often solved remotely, so they come last)
function routeStops(){
 const stops=[];const add=(c,kind,cls,text,attr,tech,rank,key)=>{stops.push({c,kind,cls,text,attr,rank,key,tech:tech||'',city:(c?.city||c?.mastro?.city||'').trim()||'Senza località',cap:(c?.mastro?.cap||'').trim(),zone:c?.mastro?.zone||''});};
 for(const i of D.openInterventions){const r=i.priority==='Urgente'?0:i.priority==='Alta'?1:2;add(i.c,'Intervento',['bad','due','info'][r],i.title||'Intervento',`data-int="${esc(i.id)}"`,i.technician,r,'i'+i.id);}
 for(const m of D.machines)if(m.active&&!m.stale&&m.verDays!==null&&m.verDays<=30)add(m.c,'Verificazione',m.verDays<0?'bad':'due',`${m.model||'Macchina'} · ${m.serial} · ${rel(m.verDays)}`,m.code?`data-client="${esc(m.code)}"`:'','',m.verDays<0?0:1,'m'+m.id);
 if(typeof IT!=='undefined'&&IT)for(const r of IT.low||[])add(r.c,'DGFE quasi pieno','due',`${r.serial} · ${r.d?.dgfeFree}% libero`,`data-ital="${esc(r.serial)}"`,'',1,'it'+r.serial);
 if(typeof RC!=='undefined'&&RC)for(const r of RC.low||[])add(r.c,'DGFE quasi pieno','due',`${r.serial} · ${r.dgfeFree}% libero`,`data-rch="${esc(r.serial)}"`,'',1,'rc'+r.serial);
 if(typeof DT!=='undefined'&&DT)for(const r of DT.errors||[])add(r.c,'Errore DTR','bad',`${r.serial} · ${r.model||'cassa'}`,`data-dtr="${esc(r.serial)}"`,'',3,'d'+r.serial);
 return stops;
}
// a technician opens the round on his own stops (his name contains the technician's name, e.g. "Marco Chiti" → "Chiti")
function routeMine(techs){if(me?.role!=='tecnico')return'';const n=norm(me.name||'').trim();if(n.length<3)return'';return techs.find(t=>norm(t).trim().length>=3&&(n.includes(norm(t).trim())||norm(t).includes(n)))||'';}
function routePage(){
 const all=routeStops();const techs=[...new Set(all.map(s=>s.tech).filter(Boolean))].sort();
 if(WK.route===undefined)WK.route=routeMine(techs);if(WK.route&&!techs.includes(WK.route))WK.route='';
 const byTech=all.filter(s=>!WK.route||s.tech===WK.route);
 // what kind of stop: the counts help decide where to go first
 const KINDS=[['Intervento','Interventi'],['Verificazione','Verificazioni'],['DGFE quasi pieno','DGFE pieni'],['Errore DTR','Errori cassa']].map(([k,l])=>[k,l,byTech.filter(s=>s.kind===k).length]).filter(x=>x[2]);
 if(WK.kind&&!KINDS.some(x=>x[0]===WK.kind))WK.kind='';
 const list=byTech.filter(s=>!WK.kind||s.kind===WK.kind);const SHOW=6;
 // a big city is split by postcode so each group is a real round; urgent places first, "Senza località" last
 const per={};for(const s of list)per[s.city]=(per[s.city]||0)+1;
 const groups=new Map();for(const s of list){const k=per[s.city]>20&&s.cap&&s.city!=='Senza località'?s.city+' '+s.cap:s.city;if(!groups.has(k))groups.set(k,[]);groups.get(k).push(s);}
 // stops already done today go to the bottom of their place; a place with everything done goes after the others
 for(const s of list)s.done=routeIsDone(s);
 for(const ss of groups.values())ss.sort((a,b)=>a.done-b.done||a.rank-b.rank||(a.c?.name||'').localeCompare(b.c?.name||'','it'));
 const hot=ss=>ss.filter(s=>s.rank<=1&&!s.done).length;const allDone=ss=>ss.every(s=>s.done);
 const doneN=list.filter(s=>s.done).length;
 const ordered=[...groups.entries()].sort((a,b)=>(a[0]==='Senza località')-(b[0]==='Senza località')||allDone(a[1])-allDone(b[1])||a[1][0].rank-b[1][0].rank||hot(b[1])-hot(a[1])||b[1].length-a[1].length||a[0].localeCompare(b[0],'it'));

 const addr=c=>[c?.address||c?.mastro?.address,c?.mastro?.cap,c?.city||c?.mastro?.city,c?.mastro?.prov].filter(Boolean).join(', ');
 const maps=ss=>{const places=[...new Set(ss.map(s=>addr(s.c)).filter(Boolean))].slice(0,10);if(!places.length)return'';const dest=places.pop();
  return`https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=${encodeURIComponent(dest)}${places.length?'&waypoints='+encodeURIComponent(places.join('|')):''}`;};
 return`${hero('<span class="grad">Giro di oggi</span>.','Interventi aperti, verificazioni entro 30 giorni, casse in errore e giornali quasi pieni, raggruppati per località: un tocco apre il percorso sul telefono, il cerchio segna la tappa come fatta.','')}
 ${list.length?`<div class="route-progress reveal" style="--i:1"><div class="rp-bar"><i style="width:${Math.round(doneN/list.length*100)}%"></i></div><span><b>${doneN}</b> di ${list.length} ${list.length===1?'tappa fatta':'tappe fatte'} oggi</span></div>`:''}
 <div class="toolbar route-tools reveal" style="--i:1">${KINDS.length>1?`<div class="seg"><button class="${!WK.kind?'active':''}" data-w-kind="">Tutto<span class="count">${byTech.length}</span></button>${KINDS.map(([k,l,n])=>`<button class="${WK.kind===k?'active':''}" data-w-kind="${esc(k)}">${l}<span class="count">${n}</span></button>`).join('')}</div>`:''}
  ${techs.length?`<div class="seg"><button class="${!WK.route?'active':''}" data-w-route="">Tutti i tecnici</button>${techs.map(t=>`<button class="${WK.route===t?'active':''}" data-w-route="${esc(t)}">${esc(t)}</button>`).join('')}</div>`:''}</div>
 ${ordered.length?`<div class="route-grid">${ordered.slice(0,12+(WK.more.route||0)).map(([city,ss],gi)=>{const url=maps(ss);return`<div class="card reveal route-card" style="--i:${Math.min(gi+2,9)}"><div class="card-head"><div><h2>${esc(city)}<span class="count">${ss.length}</span></h2><p>${esc([...new Set(ss.map(s=>s.zone).filter(z=>z&&!/^\d+$/.test(z)))].join(', ')||(ss.length===1?'1 tappa':ss.length+' tappe'))}${ss.some(s=>s.rank===0&&!s.done)?` · <span style="color:var(--bad);font-weight:600">${ss.filter(s=>s.rank===0&&!s.done).length===1?'1 urgente':ss.filter(s=>s.rank===0&&!s.done).length+' urgenti'}</span>`:''}${ss.some(s=>s.done)?` · <span style="color:var(--ok);font-weight:600">${allDone(ss)?'tutto fatto':ss.filter(s=>s.done).length+' fatte'}</span>`:''}</p></div>${url?`<a class="btn sm accent" href="${esc(url)}" target="_blank" rel="noopener">${icon('pin')}Percorso</a>`:''}</div>
  ${(WK.open.has(city)?ss:ss.slice(0,SHOW)).map(s=>`<div class="list-row route-stop${s.done?' done':''}" ${s.attr} tabindex="0"><button type="button" class="route-check${s.done?' on':''}" data-route-done="${esc(s.key)}" aria-pressed="${s.done}" aria-label="${s.done?'Segna di nuovo da fare':'Segna come fatta'}" data-tip="${s.done?esc('Fatta'+(state.routeDone[s.key]?.by?' da '+state.routeDone[s.key].by:'')+' · tocca per rifarla'):'Segna come fatta'}">${icon('check')}</button><span class="badge ${s.cls}">${esc(s.kind)}</span><div class="grow"><strong>${esc(s.c?.name||'Cliente da abbinare')}</strong><small>${esc(s.text)}${addr(s.c)?' · '+esc(addr(s.c)):''}${s.tech?' · '+esc(s.tech):''}</small></div>${s.c?.phone?`<a class="icon-btn sm" href="tel:${esc(s.c.phone.replace(/\s/g,''))}" aria-label="Chiama ${esc(s.c.name)}" data-stop>${icon('phone')}</a>`:''}</div>`).join('')}${ss.length>SHOW?`<button class="btn sm ghost route-more" data-w-city="${esc(city)}">${WK.open.has(city)?'Mostra meno':`Mostra altre ${ss.length-SHOW}`}</button>`:''}</div>`;}).join('')}</div>${ordered.length>12+(WK.more.route||0)?`<button class="btn route-more-cities" data-w-more="route">${icon('chevron')}Mostra altre ${ordered.length-12-(WK.more.route||0)} località</button>`:''}`
 :`<div class="card reveal empty"><span class="orb">${icon('check')}</span><h3>Giornata libera</h3><p>Nessun intervento aperto né verificazione imminente.</p></div>`}`;
}

// ───────────────────────── 4. data check
function dataIssues(){
 const nk=s=>normName(s||'').replace(/\b(srl|snc|sas|spa|srls|di|e|ditta|the)\b/g,'').replace(/\s+/g,' ').trim();
 const idk=s=>String(s||'').toUpperCase().replace(/^IT/,'').replace(/[^0-9A-Z]/g,'');
 const by=new Map();for(const c of D.clients){for(const k of ['n:'+nk(c.name),...[c.vat,c.taxCode].map(idk).filter(v=>v.length>=11).map(v=>'v:'+v)]){if(k==='n:')continue;if(!by.has(k))by.set(k,new Set());by.get(k).add(c);}}
 const seen=new Set();const dups=[];
 for(const [k,set] of by){if(set.size<2)continue;const arr=[...set];const id=arr.map(c=>c.code).sort().join('|');if(seen.has(id))continue;seen.add(id);dups.push({why:k.startsWith('v:')?'Stessa P.IVA / codice fiscale':'Stesso nome',list:arr});}
 const vat=D.clients.filter(c=>c.mastro&&c.records.length&&idk(c.mastro.vat).length>=11&&c.records.some(r=>idk(r.vat).length>=11&&idk(r.vat)!==idk(c.mastro.vat)));
 const devs=[...(typeof DT!=='undefined'&&DT?DT.regs.filter(r=>!r.c).map(r=>({src:'DTR',serial:r.serial,label:r.client||'',attr:`data-dtr="${esc(r.serial)}"`})):[]),
  ...(typeof IT!=='undefined'&&IT?IT.devs.filter(r=>!r.c).map(r=>({src:'Ital Retail',serial:r.serial,label:r.label||'',attr:`data-ital="${esc(r.serial)}"`})):[]),
  ...(typeof RC!=='undefined'&&RC?RC.devs.filter(r=>!r.c&&!r.off).map(r=>({src:'RCH',serial:r.serial,label:r.label||'',attr:`data-rch="${esc(r.serial)}"`})):[])];
 const noContact=D.clients.filter(c=>(c.active.length||c.machines.some(m=>m.active))&&!c.phones.length&&!c.emails.length&&!c.phone&&!c.email);
 return{dups,vat,devs,noContact};
}
function checkPage(){
 if(!isAdmin())return`${hero('Controllo dati','Riservato all’amministratore.','')}`;
 const x=dataIssues();const n=limit('chk');
 const tabs=[['dup','Doppioni',x.dups.length],['vat','P.IVA diverse',x.vat.length],['dev','Casse senza cliente',x.devs.length],['contact','Senza contatti',x.noContact.length]];
 let body='';
 if(WK.check==='dup')body=x.dups.slice(0,n).map(g=>`<div class="chk-group"><small class="muted">${esc(g.why)}</small>${g.list.map(c=>`<div class="list-row" data-client="${esc(c.code)}"><div class="grow"><strong>${esc(c.name)}</strong><small>${esc([c.city,c.vat,c.mastro?'Mastro '+c.mastro.code:'',c.records.length?c.records.length+' licenze':'',c.machines.length?c.machines.length+' macchine':''].filter(Boolean).join(' · '))}</small></div></div>`).join('')}<div class="btn-row"><button class="btn sm" data-merge="${esc(g.list.map(c=>c.code).join('|'))}">${icon('users')}Unisci…</button></div></div>`).join('')+moreBtn('chk',n,x.dups.length);
 if(WK.check==='vat')body=x.vat.slice(0,n).map(c=>`<div class="list-row" data-edit-client="${esc(c.code)}"><div class="grow"><strong>${esc(c.name)}</strong><small>Mastro <span class="mono">${esc(c.mastro.vat)}</span> · Zucchetti <span class="mono">${esc([...new Set(c.records.map(r=>r.vat).filter(Boolean))].join(', '))}</span></small></div><span class="badge due">Correggi</span></div>`).join('')+moreBtn('chk',n,x.vat.length);
 if(WK.check==='dev')body=x.devs.slice(0,n).map(d=>`<div class="list-row"><span class="badge info">${esc(d.src)}</span><div class="grow" ${d.attr}><strong class="mono">${esc(d.serial)}</strong><small>${esc(d.label||'Nessun nome sul portale')}</small></div><button class="btn sm" data-link-dev="${esc(d.serial)}" data-link-label="${esc(d.label)}">${icon('users')}Abbina</button></div>`).join('')+moreBtn('chk',n,x.devs.length);
 if(WK.check==='contact')body=x.noContact.slice(0,n).map(c=>`<div class="list-row" data-edit-client="${esc(c.code)}"><div class="grow"><strong>${esc(c.name)}</strong><small>${esc([c.city,c.active.length?c.active.length+' licenze attive':'',c.machines.filter(m=>m.active).length?c.machines.filter(m=>m.active).length+' macchine attive':''].filter(Boolean).join(' · '))}</small></div><span class="badge">Completa</span></div>`).join('')+moreBtn('chk',n,x.noContact.length);
 return`${hero('<span class="grad">Controllo dati</span>.','Doppioni da unire, P.IVA che non coincidono tra i portali e Mastro, casse senza cliente e clienti senza contatti: ognuno con il suo pulsante per sistemarlo.','')}
 <div class="kpis">${tabs.map(([k,l,v],i)=>`<div class="card kpi ${v?'due':'ok'} reveal" style="--i:${i+1}" data-w-check="${k}" tabindex="0"><div class="kpi-top"><span>${l}</span><span class="kpi-icon">${icon(v?'alert':'check')}</span></div><div class="kpi-value">${fmt(v)}</div><div class="kpi-note">${v?'da controllare':'tutto a posto'}</div></div>`).join('')}</div>
 <div class="card reveal" style="--i:5"><div class="card-head"><div><h2>${tabs.find(t=>t[0]===WK.check)[1]}</h2></div><div class="seg">${tabs.map(([k,l,v])=>`<button class="${WK.check===k?'active':''}" data-w-check="${k}">${l} <small>${fmt(v)}</small></button>`).join('')}</div></div>
 ${body||`<div class="empty"><span class="orb">${icon('check')}</span><h3>Tutto a posto</h3><p>Niente da sistemare in questo elenco.</p></div>`}
 ${WK.check==='dup'&&D.merged?.length?`<p class="sub" style="margin-top:12px">${plur(D.merged.length,'cliente già unito','clienti già uniti')}: per separarli apri il cliente principale → <em>Separa</em>.</p>`:''}</div>`;
}
async function mergeDialog(codes){
 const list=codes.map(c=>D.byCode.get(c)).filter(Boolean);if(list.length<2)return;
 const score=c=>c.records.length*3+c.machines.length*2+(c.mastro?2:0)+c.phones.length;const main=[...list].sort((a,b)=>score(b)-score(a))[0];
 const body=`<p class="sub">Il cliente principale tiene nome e dati; gli altri spariscono e tutte le loro licenze, macchine, casse e interventi passano sotto di lui. Si può separare in ogni momento.</p>
  <div class="form-grid"><label class="field span-2">Cliente principale<select class="select" name="main">${list.map(c=>`<option value="${esc(c.code)}"${c===main?' selected':''}>${esc(c.name)}${c.city?' — '+esc(c.city):''} (${esc(c.code)})</option>`).join('')}</select></label></div>`;
 const v=await formPanel({title:'Unisci clienti',tag:'Controllo dati',sub:`${list.length} schede`,body,ok:'Unisci'});if(!v||v.__act)return;
 for(const c of list)if(c.code!==v.main)await sendWork('clientMerge',{code:c.code,into:v.main},'/api/clients/merge');
 if(WEB)toast('Clienti uniti.');
}
async function linkDialog(serial,label){
 const guess=label?D.clients.find(c=>normName(c.name).includes(normName(label))||normName(label).includes(normName(c.name))):null;
 const body=`<div class="form-grid"><label class="field span-2">Cliente<input class="input" name="client" list="lk-clients" required value="${esc(guess?guess.name+(guess.city?' — '+guess.city:''):'')}" placeholder="Scrivi per cercare" autocomplete="off"></label><datalist id="lk-clients">${clientOptions()}</datalist></div>`;
 const v=await formPanel({title:'Abbina la cassa',tag:'Matricola '+esc(serial),sub:label?'Sul portale: '+esc(label):'Il portale non indica il cliente.',body,ok:'Abbina'});if(!v||v.__act)return;
 const c=clientFromLabel(v.client);if(!c)return toast('Scegli il cliente dall’elenco.','alert');
 if(await sendWork('linkSave',{serial,clientCode:c.code},'/api/links/save')&&WEB)toast('Cassa abbinata a '+c.name+'.');
}

// ───────────────────────── 5. single sheet of a register / serial: Mastro machine + DTR + Ital Retail + licences + interventions
function openDevice(serial){
 const k=serKey(serial);if(!k)return;const tail=s=>{const x=serKey(s);return x&&(x===k||(x.length>=8&&k.length>=8&&(x.endsWith(k.slice(-8))&&k.endsWith(x.slice(-8)))));};
 const m=D.machines.find(x=>tail(x.serial)||tail(x.fiscalSerial));const dt=(typeof DT!=='undefined'&&DT?DT.regs:[]).find(r=>tail(r.serial));const it=(typeof IT!=='undefined'&&IT?IT.devs:[]).find(r=>tail(r.serial));
 const lic=state.records.filter(r=>tail(r.serial));const c=m?.c||dt?.c||it?.c||(lic[0]&&D.byCode.get(lic[0].clientCode))||null;
 const ints=D.interventions.filter(i=>(m&&i.machine&&('m'+i.machine===m.id||serKey(i.machine)===serKey(m.serial)))||(c&&i.c===c&&k.length>=6&&serKey(i.title+' '+(i.work||'')).includes(k)));
 const ev=[];const push=(d,t,s,cls='')=>{if(d)ev.push({d,t,s,cls});};
 if(m){push(parseDate(m.firstInstall||m.installed),'Installazione',m.model||'');push(m.ver,'Ultima verificazione','');if(m.verNext)push(m.verNext,'Prossima verificazione',rel(m.verDays),m.verDays<0?'bad':m.verDays<=60?'due':'');if(m.end)push(m.end,'Fine abbonamento',m.amount&&m.amount!=='0'?'€ '+m.amount:'',m.endDays<0?'bad':'');}
 for(const i of ints)push(i.d,'Intervento',`${i.title}${i.technician?' · '+i.technician:''}`,i.status!=='Chiuso'?'info':'');
 if(it){const d=it.d||{};for(const u of d.updates||[])push(parseDate(u.at),'Aggiornamento firmware',`dalla ${u.from} alla ${u.to}`);if(d.subTo)push(parseDate(d.subTo),'Fine abbonamento Ital Retail',d.autoRenew?'rinnovo automatico':'');if(d.certDue)push(parseDate(d.certDue),'Scadenza certificato RT','');for(const o of it.orders||[])push(parseDate(o.date),'Ordine Ital Retail',`${o.kind} · ${o.amount}`);}
 if(dt){if(dt.geDue)push(parseDate(dt.geDue),'Scadenza giornale elettronico','');if(dt.d?.certDue)push(parseDate(dt.d.certDue),'Scadenza certificato RT','');if(dt.lic?.to)push(parseDate(dt.lic.to),'Fine licenza RT-Utility','');}
 for(const r of lic)push(parseDate(r.expiry),'Scadenza licenza '+famLabel(famOfScope(r.scope)),r.product||'');
 ev.sort((a,b)=>b.d-a.d);
 const src=[m&&'Mastro',dt&&'DTR-Service',it&&'Ital Retail',lic.length&&'Zucchetti'].filter(Boolean);
 const fact=(l,v)=>`<div class="fact"><small>${l}</small><span>${v||'<span class="muted">—</span>'}</span></div>`;
 const box=document.createElement('div');box.className='modal';
 box.innerHTML=`<div class="modal-box wide dtr-card" role="dialog" aria-label="Scheda ${esc(serial)}"><div class="dtr-card-head"><div><span class="tag">Scheda unica · ${esc(src.join(' + ')||'nessuna fonte')}</span><h3 class="mono">${esc(m?.serial||dt?.serial||it?.serial||serial)}</h3><p>${esc(c?.name||'Cliente non abbinato')}</p></div><button class="icon-btn" data-x aria-label="Chiudi">${icon('close')}</button></div>
  <div class="facts">${fact('Modello',m?.brand?`${brandMark(m.brand,'xs')} ${esc(m.brand.l)} · ${esc(m.family)}`:esc(m?.model||dt?.model||it?.type||''))}${m?.brand?.portal?fact('Portale',portalBadge(m)):''}${fact('Matricola fiscale',esc(m?.fiscalSerial&&m.fiscalSerial!=='0'?m.fiscalSerial:''))}${fact('Stato in Mastro',m?(m.active?'Attiva':'Dismessa')+(m.subscribed?' · in abbonamento':''):'')}${fact('Prossima verificazione',m?.verNext?`${dShort(m.verNext)} · ${rel(m.verDays)}`:'')}
  ${dt?fact('DTR-Service',`${esc(dt.d?.state||dt.status||'collegata')}${dt.geDue?' · GE '+dShort(parseDate(dt.geDue)):''}`):''}${it?fact('Ital Retail',`${it.noSub?'senza abbonamento':'abbonamento attivo'}${it.d?.firmware?' · fw '+esc(it.d.firmware):''}`):''}${lic.length?fact('Licenze Zucchetti',lic.map(r=>esc(famLabel(famOfScope(r.scope)))+' '+(parseDate(r.expiry)?dShort(parseDate(r.expiry)):'')).join(', ')):''}${m?.notes?fact('Note Mastro',`<span style="white-space:pre-line">${esc(m.notes)}</span>`):''}</div>
  ${m&&typeof rtSection==='function'?rtSection(m):''}
  <div class="d-section"><h3>Storia <span>${ev.length}</span></h3>${ev.slice(0,80).map(e=>`<div class="dtr-ev"><time>${dShort(e.d)}</time><span${e.cls?` style="color:var(--${e.cls})"`:''}><b>${esc(e.t)}</b>${e.s?' · '+esc(e.s):''}</span></div>`).join('')||'<p class="muted">Nessun evento.</p>'}</div>
  <div class="btn-row">${c?`<button class="btn" data-open-client="${esc(c.code)}">${icon('users')}Apri cliente</button><button class="btn accent" data-newint="${esc(c.code)}">${icon('plus')}Nuovo intervento</button>`:''}${dt?`<button class="btn ghost" data-dtr="${esc(dt.serial)}">DTR</button>`:''}${it?`<button class="btn ghost" data-ital="${esc(it.serial)}">Ital Retail</button>`:''}</div></div>`;
 document.body.append(box);
 const close=()=>{box.classList.add('out');setTimeout(()=>box.remove(),220);document.removeEventListener('keydown',key,true);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();close();}};document.addEventListener('keydown',key,true);
 box.addEventListener('click',e=>{if(e.target===box||e.target.closest('[data-x]'))return close();const oc=e.target.closest('[data-open-client]');if(oc){close();openClient(oc.dataset.openClient);return;}if(e.target.closest('[data-newint],[data-dtr],[data-ital]'))close();});
 box.querySelector('[data-x]').focus({preventScroll:true});
}

// ── events (capture: run before the generic handlers)
document.addEventListener('click',e=>{
 const t=e.target.closest?.('[data-w-inv],[data-w-ren],[data-w-route],[data-w-kind],[data-w-city],[data-w-check],[data-w-more],[data-asset],[data-asset-new],[data-ren],[data-merge],[data-link-dev],[data-device]');if(!t)return;
 if(e.target.closest('a[href],[data-stop]')&&!t.matches('a'))return;
 const ds=t.dataset;e.preventDefault();e.stopPropagation();
 if('wInv' in ds){WK.inv=ds.wInv;WK.more.inv=0;return render(false);}
 if('wRen' in ds){WK.ren=ds.wRen;WK.more.ren=0;return render(false);}
 if('wRoute' in ds){WK.route=ds.wRoute;WK.more.route=0;return render(false);}
 if('wKind' in ds){WK.kind=ds.wKind;WK.more.route=0;return render(false);}
 if('wCity' in ds){const c=ds.wCity,was=WK.open.has(c);was?WK.open.delete(c):WK.open.add(c);const y=t.closest('.route-card')?.getBoundingClientRect().top;render(false);
  // keep the card where it was (closing a long list must not throw you elsewhere)
  const card=[...document.querySelectorAll('[data-w-city]')].find(b=>b.dataset.wCity===c)?.closest('.route-card');if(card&&y!==undefined)$('#main').scrollBy({top:card.getBoundingClientRect().top-y,behavior:'instant'});return;}
 if('wCheck' in ds){WK.check=ds.wCheck;WK.more.chk=0;return render(false);}
 if('wMore' in ds){WK.more[ds.wMore]=(WK.more[ds.wMore]||0)+(ds.wMore==='route'?12:60);return render(false);}
 if('assetNew' in ds)return openAssetEditor(null,ds.assetNew||'');
 if('asset' in ds)return openAssetEditor(ds.asset);
 if('ren' in ds)return openRenewal(ds.ren);
 if('merge' in ds)return mergeDialog(ds.merge.split('|'));
 if('linkDev' in ds)return linkDialog(ds.linkDev,ds.linkLabel||'');
 if('device' in ds)return openDevice(ds.device);
},true);
let wTimer;document.addEventListener('input',e=>{const f=e.target.closest?.('[data-w-q]');if(!f)return;WK[f.dataset.wQ]=f.value;WK.more={};clearTimeout(wTimer);
 wTimer=setTimeout(()=>{const pos=f.selectionStart;render(false);const n=document.querySelector(`[data-w-q="${f.dataset.wQ}"]`);if(n){n.focus({preventScroll:true});try{n.setSelectionRange(pos,pos);}catch{}}},160);});
