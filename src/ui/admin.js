'use strict';
/* Top Cash Studio — administrator tools: add / edit / delete customers, correct or hide licences, manage users.
   Loaded after app.js. Changes are stored apart from the portal and Mastro copies: a new synchronisation never
   overwrites them, and "Annulla modifiche" brings a customer back to the data from the sources. */

const CE_FIELDS=[['name','Ragione sociale','text',2],['vat','Partita IVA','text'],['taxCode','Codice fiscale','text'],['phone','Telefono','tel'],['email','Email','email'],
 ['pec','PEC','email'],['contact','Referente','text'],['address','Indirizzo','text',2],['city','Località','text'],['category','Categoria','text']];

/* Applies the administrator's changes on top of the derived archive (called after every derive). */
function applyEdits(){
 if(!D)return;const edits=state.clientEdits||{};D.deleted=[];D.edited=0;
 for(const [code,e] of Object.entries(edits)){
  let c=D.byCode.get(code);
  if(!c){if(!e.created)continue;
   c={code,name:e.name||code,vat:'',taxCode:'',city:'',address:'',phone:'',email:'',families:[],records:[],active:[],orders:[],status:'Senza licenze',next:null,meta:state.metadata?.[code]||{},note:state.notes?.[code]||'',registry:false,installer:'',machines:[],interventions:[],phones:[],emails:[],mastro:null,dtr:[],ital:[]};
   D.clients.push(c);D.byCode.set(code,c);}
  c.edit=e;D.edited++;
  for(const [k] of CE_FIELDS)if(e[k])c[k]=e[k];
  if(e.phone)c.phones=[e.phone,...c.phones.filter(p=>p!==e.phone)];
  if(e.email)c.emails=[e.email,...c.emails.filter(p=>p!==e.email)];
  c.search=norm([c.search,c.name,c.vat,c.taxCode,c.city,c.address,c.phone,c.email,c.pec,c.contact,c.category].join(' '));
  if(e.deleted)D.deleted.push(c);
 }
 // merged duplicates: everything of the duplicate shows under the main customer, the duplicate disappears
 D.merged=[];
 for(const [code,e] of Object.entries(edits)){if(!e.mergedInto)continue;const c=D.byCode.get(code),t=D.byCode.get(e.mergedInto);if(!c||!t||c===t||e.deleted)continue;
  for(const k of ['records','active','orders','machines','interventions'])for(const x of c[k]||[]){(t[k]||=[]).push(x);if(x&&typeof x==='object'&&'c' in x)x.c=t;if(x&&'code' in x&&k!=='records')x.code=t.code;if(x&&'client' in x&&(k==='machines'||k==='interventions'))x.client=t.name;}
  t.phones=[...new Set([...(t.phones||[]),...(c.phones||[])])];t.emails=[...new Set([...(t.emails||[]),...(c.emails||[])])];
  if(!t.vat)t.vat=c.vat;if(!t.taxCode)t.taxCode=c.taxCode;if(!t.mastro&&c.mastro)t.mastro=c.mastro;(t.mastroCodes||=[]).push(...(c.mastroCodes||[]));
  t.search=norm(t.search+' '+c.search);(t.mergedFrom||=[]).push(c);D.byCode.set(code,t);D.merged.push(c);}
 // customers without an active machine leave Studio by themselves: restorable from Eliminati, and back on their own
 // as soon as Mastro gives them an active machine. Customers created in Studio and restored ones stay.
 {const out=new Set([...D.deleted,...D.merged]);D.autoGone=0;
  for(const c of D.clients){if(out.has(c)||c.edit?.created||c.edit?.keep||(c.machines||[]).some(m=>m.active))continue;c.autoGone=true;D.deleted.push(c);D.autoGone++;}}
 if(D.deleted.length||D.merged.length){const gone=new Set([...D.deleted,...D.merged]);D.clients=D.clients.filter(c=>!gone.has(c));}
 D.deleted.sort((a,b)=>a.name.localeCompare(b.name,'it'));
 D.clients.sort((a,b)=>a.name.localeCompare(b.name,'it'));
 D.licEdits=state.licenseEdits||{};
}

/* Sends an administrator change to the server (shared archive) or to the Windows app (local archive). */
async function adminSend(action,data){
 if(!WEB){if(!host)return toast('Funzione disponibile nell’app Windows.','alert');host.postMessage({action,...data});return true;}
 const url={clientSave:'/api/clients/save',clientDelete:'/api/clients/delete',clientReset:'/api/clients/reset',licenseSave:'/api/licenses/save',brandsSave:'/api/settings/brands'}[action];
 try{const r=await post(url,data);return r||true;}catch(e){toast(e.message,'alert');return false;}
}

/* A form in a panel: resolves with the values, or null when closed. */
function formPanel({title,sub='',tag='',body,ok='Salva',extra=''}){return new Promise(res=>{
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<form class="modal-box wide form-panel" role="dialog" aria-modal="true" aria-label="${esc(title)}" novalidate>
  <div class="dtr-card-head"><div>${tag?`<span class="tag">${tag}</span>`:''}<h3>${esc(title)}</h3>${sub?`<p>${sub}</p>`:''}</div><button type="button" class="icon-btn" data-x aria-label="Chiudi">${icon('close')}</button></div>
  ${body}<div class="btn-row">${extra}<span class="grow"></span><button type="button" class="btn ghost" data-x>Annulla</button><button class="btn accent" type="submit">${icon('check')}${esc(ok)}</button></div></form>`;
 document.body.append(m);const prev=document.activeElement;const f=m.querySelector('form');
 const close=v=>{document.removeEventListener('keydown',key,true);m.classList.add('out');setTimeout(()=>m.remove(),200);prev?.focus?.({preventScroll:true});res(v);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();close(null);}};document.addEventListener('keydown',key,true);
 m.addEventListener('click',e=>{if(e.target===m||e.target.closest('[data-x]'))close(null);const b=e.target.closest('[data-panel-act]');if(b)close({__act:b.dataset.panelAct});});
 f.addEventListener('submit',e=>{e.preventDefault();const bad=[...f.querySelectorAll('[required]')].find(x=>!x.value.trim());if(bad){bad.classList.add('shake');bad.focus();setTimeout(()=>bad.classList.remove('shake'),500);return;}close(Object.fromEntries(new FormData(f)));});
 setTimeout(()=>f.querySelector('input:not([type=hidden]),textarea,select')?.focus({preventScroll:true}),60);
});}
const fieldHtml=(name,label,type,value,span,attrs='')=>`<label class="field${span===2?' span-2':''}">${label}<input class="input" name="${name}" type="${type==='tel'?'tel':type==='email'?'email':type}" value="${esc(value||'')}" ${attrs}></label>`;

async function openClientEditor(code){
 const c=code?D.byCode.get(code)||D.deleted?.find(x=>x.code===code):null;const e=c?.edit||{};
 const src=c?[c.registry?'Zucchetti':'',c.mastro?'MastroSQL':'',c.records.length?'licenze':''].filter(Boolean).join(', '):'';
 const body=`${c&&!e.created?`<div class="note">${icon('sparkle')}<span>I dati arrivano da ${esc(src||'i portali')}. Quello che cambi qui ha la precedenza e <strong>resta anche dopo le sincronizzazioni</strong>; un campo lasciato vuoto torna al valore di origine.</span></div>`:''}
  <div class="form-grid">${CE_FIELDS.map(([k,l,t,span])=>fieldHtml(k,l,t,e[k]||(c?.edit?'':'')||'',span,`${k==='name'&&(!c||e.created)?'required':''} placeholder="${esc(c&&!e.created?(c[k]||''):'')}" autocomplete="off"`)).join('')}</div>`;
 const extra=c&&isAdmin()?`${e&&Object.keys(e).length&&!e.created?`<button type="button" class="btn ghost" data-panel-act="reset">${icon('sync')}Annulla modifiche</button>`:''}<button type="button" class="btn ghost danger-text" data-panel-act="delete">${icon('close')}Elimina</button>`:'';
 const v=await formPanel({title:c?c.name:'Nuovo cliente',tag:c?'Modifica cliente':'Anagrafica',sub:c?`Codice ${esc(c.code)}`:'Il cliente compare subito in Studio, anche senza licenze o macchine. Potrai collegarlo a Mastro e ai portali con la partita IVA.',body,ok:c?'Salva modifiche':'Crea cliente',extra});
 if(!v)return;
 if(v.__act==='delete')return deleteClient(c.code);
 if(v.__act==='reset'){if(await ask({title:'Annullare le modifiche?',text:`${esc(c.name)} torna ai dati dei portali e di Mastro.`,ok:'Annulla modifiche'})){await adminSend('clientReset',{code:c.code});if(WEB)toast('Modifiche annullate');}return;}
 const edit={};for(const [k] of CE_FIELDS)edit[k]=(v[k]||'').trim();
 const r=await adminSend('clientSave',{code:c?.code||'',edit});
 if(r&&WEB){toast(c?'Cliente aggiornato':'Cliente creato');if(!c&&r.code)pendingOpen=r.code;}
}
let pendingOpen='';
async function deleteClient(code){
 const c=D.byCode.get(code);if(!c)return;
 const links=[c.records.length&&`${c.records.length} licenze`,c.machines.length&&`${c.machines.length} macchine`,c.interventions.length&&`${c.interventions.length} interventi`].filter(Boolean).join(', ');
 if(!await ask({title:`Eliminare ${c.name}?`,text:`Il cliente sparisce da Studio per tutto il team${links?` (con i collegamenti a ${links})`:''}. I portali e Mastro non vengono toccati e puoi <strong>ripristinarlo</strong> quando vuoi da Clienti → Eliminati.`,ok:'Elimina',danger:true}))return;
 if(await adminSend('clientDelete',{code,deleted:true})){if(typeof closeDrawer==='function')closeDrawer();if(WEB)toast('Cliente eliminato · ripristinabile da Clienti → Eliminati');}
}
document.addEventListener('input',e=>{if(e.target.matches?.('[data-del-q]')){ui.delQ=e.target.value;const at=e.target.selectionStart;render();const f=document.querySelector('[data-del-q]');if(f){f.focus();f.setSelectionRange(at,at);}}});
async function restoreClient(code){if(await adminSend('clientDelete',{code,deleted:false})&&WEB)toast('Cliente ripristinato');}

async function openLicenseEditor(id){
 const r=state.records.find(x=>x.id===id);if(!r)return;const e=D.licEdits?.[id]||{};const d=parseDate(r.expiry);
 const iso=d?`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`:'';
 const body=`<div class="facts">${[['Cliente',r.client],['Matricola',r.serial],['Prodotto',famLabel(famOfScope(r.scope))],['Versione',r.version||'—']].map(([l,v])=>`<div class="fact"><small>${l}</small><span>${esc(v)}</span></div>`).join('')}</div>
  <div class="form-grid">${fieldHtml('expiry','Scadenza','date',e.expiry||iso)}<label class="field">Stato<select class="select" name="hidden"><option value="">Visibile</option><option value="1"${e.hidden?' selected':''}>Togli dall’elenco</option></select></label>
  <label class="field span-2">Nota<textarea class="textarea" name="note" placeholder="Es. rinnovata a mano il…, licenza spostata su un’altra cassa…" maxlength="500">${esc(e.note||'')}</textarea></label></div>
  <p class="sub">La correzione vale finché non la togli: le sincronizzazioni con Zucchetti non la sovrascrivono.${e.updatedBy?` Ultima modifica: ${esc(e.updatedBy)}, ${esc(dTime(e.updatedAt))}.`:''}</p>`;
 const extra=Object.keys(e).length?`<button type="button" class="btn ghost" data-panel-act="reset">${icon('sync')}Torna ai dati del portale</button>`:'';
 const v=await formPanel({title:r.serial,tag:'Modifica licenza',sub:esc(r.client),body,ok:'Salva',extra});if(!v)return;
 const edit=v.__act==='reset'?{expiry:'',note:'',hidden:false}:{expiry:v.expiry===iso&&!e.expiry?'':v.expiry,note:(v.note||'').trim(),hidden:v.hidden==='1'};
 if(await adminSend('licenseSave',{id,edit})&&WEB)toast(edit.hidden?'Licenza tolta dall’elenco':'Licenza aggiornata');
}

/* Users: name, delete (others), own profile from any device */
async function editUserName(u){
 const v=await formPanel({title:u.name,tag:'Utente',sub:esc(u.username),body:`<div class="form-grid">${fieldHtml('name','Nome e cognome','text',u.name,2,'required maxlength="80"')}</div>`,ok:'Salva'});if(!v)return;
 try{await post(u.username===me?.username?'/api/me':'/api/users/'+encodeURIComponent(u.username),{name:v.name.trim()});toast('Nome aggiornato');if(u.username===me?.username)me.name=v.name.trim();usersData=null;render(false);}catch(e){toast(e.message,'alert');}
}
async function deleteUser(u){
 if(!await ask({title:`Eliminare ${u.name}?`,text:'L’utente non potrà più accedere e le sue sessioni si chiudono subito. Interventi, note e rapportini che ha scritto restano.',ok:'Elimina utente',danger:true}))return;
 try{await post('/api/users/'+encodeURIComponent(u.username)+'/delete');toast('Utente eliminato');usersData=null;render(false);}catch(e){toast(e.message,'alert');}
}

document.addEventListener('click',e=>{
 const t=e.target.closest?.('[data-edit-client],[data-new-client],[data-del-client],[data-restore-client],[data-edit-lic],[data-user-name],[data-user-del]');if(!t)return;
 e.stopPropagation();e.preventDefault();
 const ds=t.dataset;
 if(!(('editClient' in ds||'newClient' in ds)?canEditClients():isAdmin()))return toast('Solo un amministratore può farlo.','alert');
 if('newClient' in ds)return openClientEditor(null);
 if(ds.editClient)return openClientEditor(ds.editClient);
 if(ds.delClient)return deleteClient(ds.delClient);
 if(ds.restoreClient)return restoreClient(ds.restoreClient);
 if(ds.editLic)return openLicenseEditor(ds.editLic);
 const u=usersData?.users.find(x=>x.username===(ds.userName||ds.userDel));if(!u)return;
 if(ds.userName)return editUserName(u);
 return deleteUser(u);
},true);

/* Brands of cash register set aside for now (EDIT): their customers and machines leave every list; nothing is deleted. */
function brandsCard(i){
 if(!isAdmin())return'';const r=state.brandRule||{brands:['EDIT'],clients:0,machines:0,names:[]};const b=r.brands||[];
 const none=!(state.machines||[]).length&&!r.machines;
 return`<div class="card reveal" style="--i:${i}"><h2>Marche messe da parte</h2><p class="sub">I registratori di queste marche non compaiono, e nemmeno i clienti Mastro i cui registratori <strong>in uso</strong> sono tutti di queste marche (con il loro storico). Chi è passato a un’altra marca resta. Nessun dato viene cancellato: togli la marca e torna tutto.</p>
  <div class="btn-row" style="margin-top:12px">${b.length?b.map(x=>`<span class="badge acc">${esc(x)}</span>`).join(''):'<span class="muted">Nessuna marca esclusa</span>'}</div>
  <p class="sub" style="margin-top:10px">${none?'Si applica appena importi i dati da MastroSQL.':r.clients||r.machines?`Tolti ora: <strong>${fmt(r.clients)}</strong> clienti e <strong>${fmt(r.machines)}</strong> registratori.`:'Nessun registratore di queste marche nei dati di Mastro.'}</p>
  ${(r.names||[]).length?`<details class="brand-names"><summary>Vedi i clienti tolti</summary><div>${r.names.map(n=>`<span>${esc(n)}</span>`).join('')}</div></details>`:''}
  <div class="btn-row" style="margin-top:12px"><button class="btn sm" data-brands-edit>${icon('edit')}Modifica marche</button></div></div>`;
}
document.addEventListener('click',async e=>{if(!e.target.closest?.('[data-brands-edit]'))return;e.preventDefault();
 const cur=(state.brandRule?.brands||['EDIT']).join(', ');
 const v=await formPanel({title:'Marche messe da parte',tag:'Registratori',sub:'Scrivi le marche separate da una virgola. Lascia vuoto per vedere tutto.',body:`<div class="form-grid">${fieldHtml('brands','Marche','text',cur,2,'placeholder="EDIT" autocomplete="off" maxlength="300"')}</div>`,ok:'Salva'});
 if(!v||v.__act)return;
 const brands=String(v.brands||'').split(/[,;]/).map(x=>x.trim().toUpperCase()).filter(Boolean);
 if(await adminSend('brandsSave',{brands})&&WEB)toast(brands.length?'Marche escluse: '+brands.join(', '):'Ora si vedono tutte le marche.');
});
