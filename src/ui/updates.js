'use strict';
// ───────────────────────── app updates over the network
// The administrator uploads the Windows zip and the APK once (Impostazioni → Aggiornamenti dell'app);
// every PC working with the server downloads it and installs it on close, every phone is offered the new APK.
let UPD={info:null,host:null,busy:'',loaded:false,serverVersion:''};
function updLoad(force){
 if(!WEB||(UPD.loaded&&!force))return;UPD.loaded=true;
 fetch('api/update/info',{cache:'no-store'}).then(r=>r.ok?r.json():null).then(j=>{UPD.info=j;if(page==='settings')render(false);}).catch(()=>{});
 fetch('api/health',{cache:'no-store'}).then(r=>r.ok?r.json():null).then(j=>{UPD.serverVersion=j?.server||'';if(page==='settings')render(false);}).catch(()=>{});
}
const updSize=n=>n>1048576?(n/1048576).toFixed(1).replace('.',',')+' MB':Math.round(n/1024)+' KB';
function updBuildLine(label,b,kind){
 return`<div class="upd-row"><span class="orb sm">${icon(kind==='android'?'phone':'grid')}</span><div class="grow"><strong>${label}</strong><small class="muted">${b?.version?`Versione <b>${esc(b.version)}</b> · ${updSize(b.size)} · caricata ${esc(relDate(b.uploadedAt))}${b.uploadedBy?' da '+esc(b.uploadedBy):''}`:'Nessuna versione caricata'}</small></div>
  ${isAdmin()&&WEB?`<label class="btn sm ${UPD.busy===kind?'loading':''}">${icon('upload')}${UPD.busy===kind?'Caricamento…':'Carica'}<input type="file" hidden accept="${kind==='android'?'.apk':'.zip'}" data-upd-file="${kind}"></label>`:''}</div>`;
}
function relDate(iso){const d=new Date(iso);if(isNaN(d))return'';const days=Math.round((Date.now()-d)/864e5);return days<=0?'oggi':days===1?'ieri':days<30?`${days} giorni fa`:d.toLocaleDateString('it-IT');}
function updateCard(i){
 updLoad();const h=UPD.host,inf=UPD.info||{};
 const mine=h?.current||'';
 const hostPart=host?`<div class="upd-me ${h?.ready?'ready':''}">
   <div class="grow"><strong>Questo PC</strong><small class="muted">${mine?'Versione '+esc(mine):''}${h?.ready?` · pronta la <b>${esc(h.ready)}</b>`:h?.serverMode===false?' · aggiornamenti in rete solo collegati al server':''}${h?.error?` · <span class="danger-text">${esc(h.error)}</span>`:''}</small></div>
   ${h?.ready?`<button class="btn accent sm" data-host-update="apply">${icon('sync')}Riavvia e aggiorna</button>`:`<button class="btn sm ghost" data-host-update="check">${icon('sync')}Controlla ora</button>`}</div>`:'';
 if(!WEB&&!host)return'';
 return`<div class="card reveal" style="--i:${i}"><h2>Aggiornamenti dell'app</h2><p class="sub">${isAdmin()&&WEB?'Carica qui il file della nuova versione: i PC collegati al server la scaricano da soli e la installano alla chiusura (la postazione di sincronizzazione di notte), i telefoni la propongono all’apertura.':'Le nuove versioni pubblicate dall’amministratore arrivano da sole.'}</p>
  ${hostPart}${WEB?`<div class="upd-list">${updBuildLine('Studio per Windows',inf.windows,'windows')}${updBuildLine('App Android',inf.android,'android')}</div>`:''}
  ${UPD.serverVersion?`<small class="muted">Server versione ${esc(UPD.serverVersion)}</small>`:''}</div>`;
}
async function updUpload(kind,file){
 const m=file.name.match(/(\d+\.\d+\.\d+)/);
 const v=await formPanel({title:kind==='android'?'Nuova versione Android':'Nuova versione Windows',sub:esc(file.name)+' · '+updSize(file.size),tag:'Aggiornamento',
  body:`<div class="form-grid">${fieldHtml('version','Versione','text',m?m[1]:'',1,'required placeholder="2.7.0" autocomplete="off"')}${fieldHtml('notes','Novità (facoltative)','text','',2,'maxlength="300" placeholder="Cosa cambia, in una riga" autocomplete="off"')}</div>`,ok:'Pubblica in rete'});
 if(!v||v.__act)return;
 UPD.busy=kind;render(false);
 try{
  const r=await fetch(`api/update/upload?kind=${kind}&version=${encodeURIComponent(v.version.trim())}&notes=${encodeURIComponent(v.notes||'')}`,{method:'POST',headers:{'X-Requested-With':'topcash','Content-Type':'application/octet-stream'},body:file});
  const j=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(j.error||'Caricamento non riuscito.');
  UPD.info=j;toast(`Versione ${v.version} pubblicata: arriva a tutti i ${kind==='android'?'telefoni':'PC'} collegati.`);
 }catch(e){toast(e.message||'Caricamento non riuscito.');}
 finally{UPD.busy='';render(false);}
}
document.addEventListener('change',e=>{const f=e.target.closest?.('[data-upd-file]');if(f&&f.files?.[0]){updUpload(f.dataset.updFile,f.files[0]);f.value='';}});
document.addEventListener('click',e=>{const b=e.target.closest?.('[data-host-update]');if(!b)return;e.preventDefault();
 if(b.dataset.hostUpdate==='apply')toast('Studio si chiude, si aggiorna e torna subito.');
 host?.postMessage({action:b.dataset.hostUpdate==='apply'?'applyUpdate':'checkUpdate'});});
host?.addEventListener('message',e=>{const v=e.data;if(v?.type!=='update')return;const was=UPD.host?.ready;UPD.host=v;
 if(v.ready&&!was)updBanner();if(page==='settings')render(false);});
function updBanner(){
 const h=UPD.host;if(!h?.ready||document.getElementById('upd-banner'))return;
 const el=document.createElement('div');el.id='upd-banner';el.className='upd-banner';
 el.innerHTML=`<span class="orb sm">${icon('sparkle')}</span><div class="grow"><strong>Versione ${esc(h.ready)} pronta</strong><small>${h.notes?esc(h.notes):'Si installa da sola quando chiudi Studio.'}</small></div><button class="btn accent sm" data-host-update="apply">Riavvia e aggiorna</button><button class="icon-btn sm" aria-label="Più tardi" data-upd-later>${icon('close')}</button>`;
 el.querySelector('[data-upd-later]').onclick=()=>{el.classList.add('out');setTimeout(()=>el.remove(),260);};
 document.body.appendChild(el);
}
