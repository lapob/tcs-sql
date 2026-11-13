'use strict';
/* Portals kept by the server (owner only): MY Zucchetti, DTR-Service, Ital Retail and RCH XDealer run in the server's own
   browser, so no office PC or Windows app is needed. Sign-in happens here, on a live picture of the portal page: a tap is
   a click, what you type goes straight into the portal (the server never stores it). */
var PSV=null,PSV_AT=0,PSV_BUSY=false;
const PSV_ID={z:'zucchetti',dtr:'dtr',ital:'ital',rch:'rch'};
const isOwnerNow=()=>typeof me!=='undefined'&&me?.role==='owner';
const serverHosts=k=>(state.serverPortals||[]).includes(PSV_ID[k]||k);
async function loadPortals(force){
 if(!WEB||!isOwnerNow()||PSV_BUSY)return;
 if(!force&&PSV&&Date.now()-PSV_AT<8000)return;
 PSV_BUSY=true;
 try{const d=await api('/api/portals');const changed=JSON.stringify(d)!==JSON.stringify(PSV);PSV=d;PSV_AT=Date.now();if(changed)render(false);}
 catch{}finally{PSV_BUSY=false;}
}
function psvOf(k){return PSV?.portals?.find(p=>p.id===(PSV_ID[k]||k))||null;}
/* "Aggiorna sul PC": for a portal the office PC reads. The server hands the request to the Windows app at its next check
   (within 20 seconds); while it waits the button says so. With the PC off it says that instead of a button that does nothing. */
function pcSyncBtn(id){
 const st=PSV?.station;
 if((PSV?.asked||[]).includes(id))return`<button class="btn ghost sm" disabled>${icon('sync')}Chiesto al PC…</button>`;
 if(st&&!st.online)return`<button class="btn ghost sm" disabled data-tip="Il PC dell’ufficio è spento o l’app è chiusa">${icon('sync')}PC spento</button>`;
 return`<button class="btn ghost sm" data-pc-sync="${id}">${icon('sync')}Aggiorna sul PC</button>`;
}
// moving to the server only makes sense where the server's browser can read the portal (Ital Retail and RCH stop it with Cloudflare)
const PSV_CAN_HOST=['zucchetti','dtr'];
/* state text and buttons for one row of "Portali e accessi" when the owner looks at it */
function psvRow(p){
 const s=psvOf(p.k);if(!s)return null;
 if(!s.enabled)return{where:'pc',state:p.state,text:p.text,btn:pcSyncBtn(s.id)+(PSV_CAN_HOST.includes(s.id)?`<button class="btn ghost sm icon-only" data-psv-on="${s.id}" aria-label="Sposta sul server" data-tip="Sposta sul server: lo legge anche a PC spento">${icon('cloud')}</button>`:'')};
 // the portal's Cloudflare robot check stops the server's browser: the office station keeps reading it, its state is shown
 if(s.blocked)return{where:'pc',blocked:true,state:p.state,text:p.text,
  btn:pcSyncBtn(s.id)+`<button class="btn ghost sm icon-only" data-psv-menu="${s.id}" aria-label="Altre azioni" data-tip="Altre azioni">${icon('more')}</button>`};
 const state=s.running?'ok':s.connected?'ok':'bad';
 const text=s.running?(s.status||'Lettura in corso…'):s.connected?`Sul server · ${s.lastSync?'aggiornato '+dTime(s.lastSync):'prima lettura in arrivo'} · ${s.every}`:(s.error||'Sul server · serve il tuo accesso');
 const btn=s.connected?`<button class="btn ghost sm" data-psv-sync="${s.id}"${s.running?' disabled':''}>${icon('sync')}${s.running?'In lettura':'Aggiorna ora'}</button><button class="btn ghost sm icon-only" data-psv-menu="${s.id}" aria-label="Altre azioni" data-tip="Altre azioni">${icon('more')}</button>`
  :`<button class="btn accent sm" data-psv-open="${s.id}">${icon('external')}Accedi</button><button class="btn ghost sm icon-only" data-psv-menu="${s.id}" aria-label="Altre azioni" data-tip="Altre azioni">${icon('more')}</button>`;
 return{where:'server',state,text,btn};
}
document.addEventListener('click',async e=>{
 const on=e.target.closest('[data-psv-on]'),sy=e.target.closest('[data-psv-sync]'),op=e.target.closest('[data-psv-open]'),mn=e.target.closest('[data-psv-menu]'),pc=e.target.closest('[data-pc-sync]');
 if(!on&&!sy&&!op&&!mn&&!pc)return;
 e.preventDefault();e.stopPropagation();
 const fail=x=>toast(x.message,'alert');
 if(pc){pc.disabled=true;
  try{const r=await api(`/api/portals/${pc.dataset.pcSync}/station-sync`,{method:'POST'});if(PSV)PSV={...PSV,asked:r.asked||[]};
   toast('Richiesta inviata al PC dell’ufficio: la lettura parte entro 20 secondi','sync');render(false);
   // follow it: the button comes back when the PC has taken the request, the time of the last reading changes when it is done
   [25e3,60e3,120e3].forEach(t=>setTimeout(()=>loadPortals(true),t));}
  catch(x){pc.disabled=false;fail(x);}return;}
 if(on){const p=psvOf(on.dataset.psvOn);
  if(!await ask({title:`Spostare ${p.name} sul server?`,text:'Il server apre il portale con un browser tutto suo e lo legge da solo, anche con il PC dell’ufficio spento o l’app disinstallata. Serve un tuo accesso, una volta sola: la password la scrivi tu nel portale e non viene salvata.',ok:'Sposta sul server'}))return;
  try{PSV=await api(`/api/portals/${p.id}/enabled`,{method:'POST',body:JSON.stringify({enabled:true})}).then(v=>({...PSV,portals:v}));render(false);openPortalView(p.id);}catch(x){fail(x);}return;}
 if(sy){try{await api(`/api/portals/${sy.dataset.psvSync}/sync`,{method:'POST'});toast('Lettura avviata sul server','sync');setTimeout(()=>loadPortals(true),1500);}catch(x){fail(x);}return;}
 if(op){openPortalView(op.dataset.psvOpen);return;}
 if(mn){const p=psvOf(mn.dataset.psvMenu);if(!p)return;
  const pick=await psvMenu(p);
  if(pick==='open')openPortalView(p.id);
  else if(pick==='stop'){try{await api(`/api/portals/${p.id}/stop`,{method:'POST'});toast('Lettura interrotta: dati precedenti conservati');}catch(x){fail(x);}}
  else if(pick==='off'){if(await ask({title:`Togliere ${p.name} dal server?`,text:'Il server smette di leggerlo; l’accesso resta salvato e puoi rimetterlo quando vuoi. Senza server lo legge solo la postazione dell’ufficio, se è accesa.',ok:'Togli dal server'})){try{await api(`/api/portals/${p.id}/enabled`,{method:'POST',body:JSON.stringify({enabled:false})});loadPortals(true);}catch(x){fail(x);}}}
  else if(pick==='forget'){if(await ask({title:`Scollegare ${p.name}?`,text:'Il server cancella la sessione salvata del portale: per leggerlo di nuovo dovrai accedere un’altra volta. I dati già letti restano.',ok:'Scollega',danger:true})){try{await api(`/api/portals/${p.id}/forget`,{method:'POST'});toast(`${p.name} scollegato`);loadPortals(true);}catch(x){fail(x);}}}
  return;}
},true);
function psvMenu(p){return new Promise(res=>{
 const m=document.createElement('div');m.className='modal';
 const item=(k,ic,t,s,danger)=>`<button class="list-row psv-item${danger?' danger':''}" data-k="${k}">${icon(ic)}<div class="grow"><strong>${t}</strong><small>${s}</small></div></button>`;
 m.innerHTML=`<div class="modal-box" role="dialog" aria-modal="true" aria-label="${esc(p.name)}"><h3>${esc(p.name)}</h3><p class="sub">${esc(p.status||'')}</p>
  <div class="psv-items">${p.blocked?'':item('open','external','Apri il portale','Guarda la pagina o rifai l’accesso')}${p.running&&!p.blocked?item('stop','close','Interrompi la lettura','I dati già salvati restano'):''}${item('off','cloud','Togli dal server','L’accesso resta salvato')}${item('forget','logout','Scollega','Cancella la sessione: servirà un nuovo accesso',true)}</div>
  <div class="btn-row"><button class="btn ghost" data-k="">Chiudi</button></div></div>`;
 document.body.append(m);
 const close=v=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);res(v);};
 const key=e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();close('');}};document.addEventListener('keydown',key,true);
 m.addEventListener('click',e=>{const b=e.target.closest('[data-k]');if(b)close(b.dataset.k);else if(e.target===m)close('');});
});}

/* ── live view: the portal page as a picture, refreshed while open; taps, scrolls and typing go to the server's browser */
function openPortalView(id){
 const p=psvOf(id)||{id,name:id};
 const m=document.createElement('div');m.className='modal pv-modal';
 m.innerHTML=`<div class="modal-box wide pv-box" role="dialog" aria-modal="true" aria-label="Accedi a ${esc(p.name)}">
  <div class="pv-head"><div class="grow"><h3>${esc(p.name)}</h3><small class="pv-url" id="pv-url">Apertura del portale…</small></div><button class="btn ghost sm icon-only" data-pv="close" aria-label="Chiudi">${icon('close')}</button></div>
  <div class="pv-screen" id="pv-screen" tabindex="0" aria-label="Pagina del portale: tocca per cliccare"><img id="pv-img" alt="" draggable="false"><div class="pv-wait" id="pv-wait"><span class="spinner"></span></div></div>
  <div class="pv-type"><input id="pv-input" class="input" type="password" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="Tocca un campo nella pagina, poi scrivi qui" aria-label="Testo da scrivere nel portale">
   <button class="btn ghost sm icon-only" data-pv="eye" aria-label="Mostra o nascondi il testo" data-tip="Mostra il testo">${icon('eye')}</button></div>
  <div class="pv-keys"><button class="btn ghost sm" data-pv-key="Backspace">⌫ Cancella</button><button class="btn ghost sm" data-pv-key="Tab">Tab</button><button class="btn ghost sm" data-pv-key="Enter">Invio</button><span class="grow"></span><button class="btn ghost sm" data-pv="back">${icon('back')}Indietro</button><button class="btn ghost sm" data-pv="reload">${icon('sync')}Ricarica</button></div>
  <p class="sub pv-note">${icon('lock')} Quello che scrivi va direttamente nel portale: il server non salva password né codici.</p>
  <div class="btn-row"><button class="btn accent" data-pv="done">${icon('check')}Ho fatto l’accesso</button></div></div>`;
 document.body.append(m);
 const img=m.querySelector('#pv-img'),scr=m.querySelector('#pv-screen'),inp=m.querySelector('#pv-input'),url=m.querySelector('#pv-url'),wait=m.querySelector('#pv-wait');
 let open=true,fast=0,last='',objUrl='',timer=0;
 // on a phone the portal is drawn at phone size by the server: its mobile page, readable and easy to tap
 const phone=innerWidth<760,vw=phone?Math.round(Math.min(430,innerWidth-32)):0,vh=phone?Math.round(Math.max(520,Math.min(900,innerHeight*.92-250))):0;
 if(phone){scr.style.aspectRatio=vw+'/'+vh;scr.style.width='100%';}
 const viewUrl=`/api/portals/${id}/view`+(phone?`?w=${vw}&h=${vh}`:'');
 const send=b=>api(`/api/portals/${id}/input`,{method:'POST',body:JSON.stringify(b)}).catch(x=>toast(x.message,'alert'));
 const nudge=()=>{fast=Date.now()+2500;clearTimeout(timer);timer=setTimeout(pull,120);};
 async function pull(){
  if(!open)return;
  try{
   const r=await fetch(viewUrl,{credentials:'same-origin',headers:{'X-Requested-With':'topcash'},cache:'no-store'});
   if(r.ok){const b=await r.blob();const u=URL.createObjectURL(b);img.src=u;if(objUrl)URL.revokeObjectURL(objUrl);objUrl=u;wait.hidden=true;url.textContent=r.headers.get('X-Portal-Url')||'';}
   else{const d=await r.json().catch(()=>({}));url.textContent=d.error||'Portale non disponibile';}
  }catch{url.textContent='Server non raggiungibile';}
  if(open)timer=setTimeout(pull,Date.now()<fast?250:900);
 }
 pull();
 const rel=e=>{const r=img.getBoundingClientRect();return{x:(e.clientX-r.left)/r.width,y:(e.clientY-r.top)/r.height};};
 let down=null;
 scr.addEventListener('pointerdown',e=>{down={y:e.clientY,x:e.clientX,t:Date.now(),...rel(e)};});
 scr.addEventListener('pointerup',async e=>{
  if(!down)return;const dy=e.clientY-down.y,dx=e.clientX-down.x;const d=down;down=null;
  if(Math.abs(dy)>14&&Math.abs(dy)>Math.abs(dx)){const r=img.getBoundingClientRect();await send({kind:'scroll',x:d.x,y:d.y,dy:-dy*(860/r.height)});nudge();return;}
  await send({kind:'click',x:d.x,y:d.y});last='';inp.value='';nudge();
  // after a tap the keyboard is ready for the field just chosen (on phones it opens by itself)
  inp.focus({preventScroll:true});
 });
 scr.addEventListener('wheel',e=>{e.preventDefault();const p=rel(e);send({kind:'scroll',x:p.x,y:p.y,dy:e.deltaY});nudge();},{passive:false});
 // typing: what is added or deleted in the field is replayed in the portal, so phone keyboards and autocorrect work too
 inp.addEventListener('input',async()=>{
  const v=inp.value;
  if(v.startsWith(last)){const add=v.slice(last.length);if(add)await send({kind:'text',text:add});}
  else if(last.startsWith(v)){for(let i=0;i<last.length-v.length;i++)await send({kind:'key',text:'Backspace'});}
  else{for(let i=0;i<last.length;i++)await send({kind:'key',text:'Backspace'});if(v)await send({kind:'text',text:v});}
  last=v;nudge();
 });
 inp.addEventListener('keydown',async e=>{
  if(e.key==='Enter'){e.preventDefault();await send({kind:'key',text:'Enter'});inp.value='';last='';nudge();}
  else if(e.key==='Tab'){e.preventDefault();await send({kind:'key',text:'Tab'});inp.value='';last='';nudge();}
  else if(e.key==='Escape'){e.preventDefault();e.stopPropagation();close();}
  else if(e.key==='Backspace'&&!inp.value){e.preventDefault();await send({kind:'key',text:'Backspace'});nudge();}
 });
 const close=async(done)=>{if(!open)return;open=false;clearTimeout(timer);if(objUrl)URL.revokeObjectURL(objUrl);document.removeEventListener('keydown',esc_,true);m.classList.add('out');setTimeout(()=>m.remove(),220);
  if(done){await send({kind:'done'});toast('Controllo l’accesso: se è riuscito la lettura parte da sola','sync');}
  setTimeout(()=>loadPortals(true),1500);};
 const esc_=e=>{if(e.key==='Escape'&&document.activeElement!==inp){e.preventDefault();e.stopPropagation();close();}};
 document.addEventListener('keydown',esc_,true);
 m.addEventListener('click',async e=>{
  const k=e.target.closest('[data-pv-key]');if(k){await send({kind:'key',text:k.dataset.pvKey});if(k.dataset.pvKey!=='Backspace'){inp.value='';last='';}else{inp.value=inp.value.slice(0,-1);last=inp.value;}nudge();return;}
  const b=e.target.closest('[data-pv]');if(!b){if(e.target===m)close();return;}
  switch(b.dataset.pv){
   case'close':close();break;
   case'done':close(true);break;
   case'back':await send({kind:'back'});nudge();break;
   case'reload':await send({kind:'reload'});nudge();break;
   case'eye':inp.type=inp.type==='password'?'text':'password';b.innerHTML=icon(inp.type==='password'?'eye':'eye-off');inp.focus();break;
  }
 });
}

/* 2.10.12 morning read: every day at the chosen hour the server asks the office PC to read the portals that are on it
   (only those it has read before). The owner switches it on or off and picks the hour; it tries for four hours if the PC is late. */
function pcMorningHtml(){const r=PSV?.pcDaily;if(!r)return'';const ids=PSV.pcIds||[];
 const hh=h=>String(h).padStart(2,'0')+':00',now=new Date(),todayIso=now.getFullYear()+'-'+String(now.getMonth()+1).padStart(2,'0')+'-'+String(now.getDate()).padStart(2,'0');
 const hours=[...Array(21).keys()].map(h=>`<option value="${h}"${h===r.hour?' selected':''}>${hh(h)}</option>`).join('');
 const next=!r.enabled?'Disattivata: i portali sul PC si leggono solo con “Aggiorna sul PC” o dall’app Windows':!ids.length?'Nessun portale da leggere sul PC: sono tutti sul server o mai collegati'
  :(r.lastDay===todayIso||now.getHours()>r.hour+3)?`Prossima: domani alle ${hh(r.hour)}`:now.getHours()<r.hour?`Prossima: oggi alle ${hh(r.hour)}`:'Prossima: entro un minuto';
 const last=r.lastRun?` · ultima ${dTime(r.lastRun)}${r.lastResult?': '+r.lastResult:''}`:'';
 return`<div class="pc-morning"><label class="check"><input type="checkbox" data-pcd-on${r.enabled?' checked':''}> <span><b>Leggi ogni mattina</b> alle</span></label><select class="select" data-pcd-hour aria-label="Ora della lettura del mattino"${r.enabled?'':' disabled'}>${hours}</select><small>${esc(next+last)}</small></div>`;}
document.addEventListener('change',async e=>{const t=e.target;if(!t.matches?.('[data-pcd-on],[data-pcd-hour]'))return;
 const box=t.closest('.pc-morning');const on=box.querySelector('[data-pcd-on]').checked,hour=+box.querySelector('[data-pcd-hour]').value;
 box.querySelectorAll('input,select').forEach(x=>x.disabled=true);
 try{const r=await api('/api/portals/pc-daily',{method:'POST',body:JSON.stringify({enabled:on,hour})});if(PSV)PSV={...PSV,pcDaily:r};
  toast(r.enabled?`Ogni mattina alle ${String(r.hour).padStart(2,'0')}:00 il PC dell’ufficio legge i suoi portali`:'Lettura del mattino disattivata','sync');}
 catch(x){toast(x.message,'alert');}render(false);});
