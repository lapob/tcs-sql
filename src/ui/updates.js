'use strict';
// ───────────────────────── app updates over the network
// A release (or the administrator's upload) reaches every PC and phone: each one shows the new version and updates with one tap.
let UPD={info:null,host:null,busy:'',loaded:false,serverVersion:''};
function updLoad(force){
 if(!WEB||(UPD.loaded&&!force))return;UPD.loaded=true;
 fetch('api/update/info',{cache:'no-store'}).then(r=>r.ok?r.json():null).then(j=>{UPD.info=j;if(page==='settings'||page==='apps')render(false);}).catch(()=>{});
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
 const hostPart=host?`<div class="upd-me ${h?.available||h?.ready?'ready':''}">
   <div class="grow"><strong>Questo PC</strong><small class="muted">${mine?'Versione '+esc(mine):''}${h?.available?` · disponibile la <b>${esc(h.available)}</b>`:h?.ready?` · pronta la <b>${esc(h.ready)}</b>`:' · è l’ultima versione'}${h?.error?` · <span class="danger-text">${esc(h.error)}</span>`:''}</small></div>
   ${h?.available||h?.ready?`<button class="btn accent sm" data-host-update="apply" ${h?.progress!=null?'disabled':''}>${icon('sync')}${h?.progress!=null?`Scarico ${Math.round(h.progress*100)}%`:'Aggiorna e riavvia'}</button>`:`<button class="btn sm ghost" data-host-update="check">${icon('sync')}Controlla ora</button>`}</div>`:'';
 if(!WEB&&!host)return'';
 return`<div class="card reveal" style="--i:${i}"><h2>Aggiornamenti dell'app</h2><p class="sub">${isAdmin()&&WEB?'Carica qui il file della nuova versione: ogni PC e ogni telefono la mostra e si aggiorna con un tocco (la postazione di sincronizzazione da sola, di notte).':'Quando esce una nuova versione compare qui e in basso a sinistra: un tocco la scarica e riavvia Studio.'}</p>
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
 if(b.disabled||b.getAttribute('aria-disabled')==='true')return;
 if(b.dataset.hostUpdate==='apply'){const h=UPD.host||{};UPD.host={...h,progress:0,error:''};updPaint();}
 host?.postMessage({action:b.dataset.hostUpdate==='apply'?'applyUpdate':'checkUpdate'});});
document.addEventListener('click',e=>{if(!e.target.closest?.('#side-update'))return;e.preventDefault();try{sessionStorage.removeItem('upd-later');}catch{}updPaint(true);});
host?.addEventListener('message',e=>{const v=e.data;if(v?.type!=='update')return;const was=UPD.host?.progress;UPD.host=v;updPaint();
 if(page==='settings'&&!(was!=null&&v.progress!=null))render(false);});
// ───────────────────────── the update, always in sight while it matters
// A soft card bottom right (once per version, "Più tardi" puts it away) and a quiet entry at the foot of the sidebar that
// stays until the update is done. The button turns into the progress: Aggiorna → Scarico 42% → Riavvio.
const updWant=h=>h&&(h.available||h.ready)?(h.available||h.ready):'';
function updPaint(force){
 const h=UPD.host||{},v=updWant(h),p=h.progress;
 // sidebar entry
 let side=document.getElementById('side-update');
 if(!side){const foot=document.querySelector('.sidebar-foot');if(foot){side=document.createElement('button');side.type='button';side.id='side-update';side.className='side-update';side.hidden=true;foot.before(side);}}
 if(side){
  const html=v?`<span class="su-dot" aria-hidden="true"></span><span class="su-txt"><strong>${p!=null?(p>=1?'Riavvio…':`Aggiornamento ${Math.round(p*100)}%`):'Nuova versione '+esc(v)}</strong><small>${p!=null?'Studio torna tra un attimo':'Tocca per aggiornare'}</small></span>${p!=null?`<span class="su-bar"><i style="transform:scaleX(${Math.max(.02,p)})"></i></span>`:''}`:'';
  if(side.dataset.html!==html){side.dataset.html=html;side.innerHTML=html;}
  if(!!v===side.hidden){if(v){side.hidden=false;side.classList.remove('out');void side.offsetWidth;side.classList.add('in');}else{side.classList.add('out');setTimeout(()=>{if(!updWant(UPD.host))side.hidden=true;},260);}}
 }
 // floating card
 let el=document.getElementById('upd-banner');
 let later='';try{later=sessionStorage.getItem('upd-later')||'';}catch{}
 const show=v&&(force||p!=null||later!==v);
 if(!show){if(el&&!el.classList.contains('out')){el.classList.add('out');setTimeout(()=>el.remove(),260);}return;}
 if(!el||el.classList.contains('out')){el?.remove();el=document.createElement('div');el.id='upd-banner';el.className='upd-banner';el.setAttribute('role','status');
  el.innerHTML=`<span class="orb sm upd-orb">${icon('sparkle')}</span><div class="grow"><strong></strong><small></small><span class="upd-err danger-text" hidden></span></div><button class="btn accent sm upd-go" data-host-update="apply"></button><button class="icon-btn sm" aria-label="Più tardi" data-tip="Più tardi" data-upd-later>${icon('close')}</button><span class="upd-line" aria-hidden="true"><i></i></span>`;
  el.querySelector('[data-upd-later]').onclick=()=>{try{sessionStorage.setItem('upd-later',v);}catch{}el.classList.add('out');setTimeout(()=>el.remove(),260);};
  document.body.appendChild(el);}
 const busy=p!=null,done=busy&&p>=1;
 el.classList.toggle('busy',busy);el.classList.toggle('done',done);
 el.querySelector('strong').textContent=done?'Riavvio di Studio…':busy?`Scarico la versione ${v}`:`Nuova versione ${v}`;
 el.querySelector('small').textContent=busy?(done?'Si installa e torna subito, con tutto come prima':`${Math.round(p*100)}%${h.size?' di '+updSize(h.size):''} · non serve fare altro`):(h.notes||`Ora hai la ${h.current||''}${h.size?' · '+updSize(h.size):''}`);
 const err=el.querySelector('.upd-err');err.hidden=!h.error;err.textContent=h.error||'';
 const go=el.querySelector('.upd-go');go.disabled=busy;go.innerHTML=done?`<span class="spinner" aria-hidden="true"></span>Riavvio`:busy?`<span class="spinner" aria-hidden="true"></span>${Math.round(p*100)}%`:`${icon('download')}${h.error?'Riprova':'Aggiorna'}`;
 el.querySelector('[data-upd-later]').hidden=busy;
 el.querySelector('.upd-line i').style.transform=`scaleX(${busy?Math.max(.02,p):0})`;
}
// ───────────────────────── "Scarica le app"
// Website: Android + Windows + install as app (iPhone/browser), the device in use first.
// Inside the Windows app: only the Android app (Studio is already installed there).
// Files: the server's update copies; the Windows app without a server falls back to the official GitHub release.
const RELEASE='https://github.com/lapob/tcs-sql/releases/latest/download/';
let installPrompt=null;
addEventListener('beforeinstallprompt',e=>{e.preventDefault();installPrompt=e;if(page==='apps')render(false);authGetApp();});
addEventListener('appinstalled',()=>{installPrompt=null;toast('Top Cash Studio installato','check');if(page==='apps')render(false);});
function appsPage(){
 updLoad();const inf=UPD.info||{},ua=navigator.userAgent;
 const ios=/iPhone|iPad/i.test(ua)||(navigator.maxTouchPoints>1&&/Macintosh/.test(ua));
 const mine=/Android/i.test(ua)?'android':ios?'web':/Windows/i.test(ua)?'windows':'web';
 const standalone=matchMedia('(display-mode: standalone)').matches||navigator.standalone;
 const meta=b=>b?.version?`<div class="app-meta"><span>Versione <b>${esc(b.version)}</b></span><span>${updSize(b.size)}</span><span>${esc(relDate(b.uploadedAt))}</span></div>`:'<div class="app-meta"><span>Non ancora pubblicata</span></div>';
 const steps=l=>`<ol class="app-steps">${l.map(s=>`<li>${s}</li>`).join('')}</ol>`;
 const cards={
  android:{ic:'android',tone:'t-ok',title:'Android',sub:'App nativa per telefoni e tablet: veloce, con scanner, firma e uso offline.',b:inf.android,
   btn:inf.android?.version?`<a class="btn accent app-dl" href="api/update/android" download>${icon('download')}Scarica per Android</a>`:'',
   steps:steps(['Scarica il file e aprilo dalle notifiche','Se richiesto, consenti l’installazione da questa fonte','Accedi con il tuo utente: gli aggiornamenti arrivano da soli'])},
  windows:{ic:'windows',tone:'t-info',title:'Windows',sub:'Studio per PC Windows 10 e 11, collegato al server dell’ufficio, con notifiche e sincronizzazione.',b:inf.windows,
   btn:inf.windows?.version?`<a class="btn accent app-dl" href="api/update/windows" download>${icon('download')}Scarica per Windows</a>`:'',
   steps:steps(['Scarica ed estrai la cartella (tasto destro → Estrai tutto)','Avvia <b>TopCash.Licenze.exe</b>','Si aggiorna da solo quando esce una nuova versione'])},
  web:{ic:'globe',tone:'t-due',title:'iPhone, iPad e browser',sub:'Nessun download: Top Cash Studio si installa come app direttamente dal browser.',b:null,
   btn:standalone?`<span class="badge ok">Già installata su questo dispositivo</span>`:installPrompt?`<button class="btn accent app-dl" data-install-pwa>${icon('plus')}Installa come app</button>`:'',
   steps:steps(ios?['Tocca <b>Condividi</b> in Safari','Scegli <b>Aggiungi a schermata Home</b>','Apri l’icona Top Cash dalla Home']:['Apri il menu del browser (⋮ o …)','Scegli <b>Installa app</b> o <b>Aggiungi a schermata Home</b>','Apri Top Cash come una normale app'])}
 };
 if(!WEB){cards.android.b=null;cards.android.btn=`<a class="btn accent app-dl" href="${RELEASE}TopCash-Studio.apk" target="_blank" rel="noopener">${icon('download')}Scarica per Android</a>`;}
 const order=host?['android']:[mine,...['android','windows','web'].filter(k=>k!==mine)];
 return`<section class="apps-hero reveal" style="--i:0"><div class="apps-hero-glow" aria-hidden="true"></div>
   <span class="apps-hero-ic">${icon('download')}</span>
   <div><h1>${host?'App per il telefono':'Scarica le app'}</h1><p>${host?'Porta Top Cash Studio sul telefono Android: stesso utente, stessi dati, sempre aggiornata da sola.':'Top Cash Studio su ogni dispositivo, sempre aggiornato. Accedi con lo stesso utente ovunque: i dati sono quelli del server dell’ufficio.'}</p></div></section>
  <div class="apps-grid${host?' single':''}">${order.map((k,i)=>{const c=cards[k];return`<article class="card app-card reveal${k===mine?' mine':''}" style="--i:${i+1}">
   <div class="app-top"><span class="app-ic ${c.tone}">${icon(c.ic)}</span>${k===mine&&!host?'<span class="badge acc">Consigliata per questo dispositivo</span>':''}</div>
   <h2>${c.title}</h2><p class="sub">${c.sub}</p>${k==='web'?'':meta(c.b)}
   <div class="app-cta">${c.btn}</div>${c.steps}</article>`;}).join('')}</div>
  <p class="muted apps-foot">${icon('shield')}Gli stessi file della Release ufficiale. Fuori ufficio le app usano la connessione cifrata.</p>`;
}
// ───────────────────────── "Scarica l'app" under the sign-in form: the right app for the device in use
// (Android phone → the APK, Windows PC → Studio for Windows, iPhone/iPad → add to Home, other browsers → install as app).
// The sign-in page only opens to a device the server already lets in (office network, or approved for outside the
// office), so outside the office the button appears only after the administrator's approval.
function authGetApp(){
 const slot=document.getElementById('auth-get');if(!slot||host)return;
 const ua=navigator.userAgent,ios=/iPhone|iPad|iPod/i.test(ua)||(navigator.maxTouchPoints>1&&/Macintosh/.test(ua));
 const kind=/Android/i.test(ua)?'android':ios?'ios':/Windows NT/i.test(ua)?'windows':'web';
 const standalone=matchMedia('(display-mode: standalone)').matches||navigator.standalone;
 const draw=html=>{if(slot.dataset.html===html)return;slot.dataset.html=html;slot.innerHTML=html;slot.hidden=!html;};
 const row=(ic,tone,title,sub,go,attrs)=>{const tag=attrs.startsWith('href')?'a':'button';return`<${tag}${tag==='button'?' type="button"':''} class="auth-get" ${attrs}><span class="ag-ic ${tone}">${icon(ic)}</span><span class="ag-txt"><strong>${title}</strong><small>${sub}</small></span><span class="ag-go">${icon(go)}</span></${tag}>`;};
 if(kind==='ios'){
  if(standalone)return draw('');
  return draw(`<div class="ag-wrap">${row('plus','t-due','Aggiungi Top Cash alla Home','Si apre a tutto schermo, come un’app','chevron','data-get-ios aria-expanded="false"')}<div class="ag-steps"><div><ol><li>Tocca <b>Condividi</b> nella barra di Safari</li><li>Scegli <b>Aggiungi alla schermata Home</b></li><li>Apri l’icona <b>Top Cash</b> dalla Home</li></ol></div></div></div>`);
 }
 if(kind==='web'){
  if(standalone||!installPrompt)return draw('');
  return draw(row('download','t-info','Installa Top Cash come app','Finestra sua, icona nel menu, sempre aggiornata','plus','data-install-pwa'));
 }
 const show=()=>{const b=UPD.info?.[kind];if(!b?.version)return draw('');
  draw(kind==='android'
   ?row('android','t-ok','Scarica l’app per Android',`Versione ${esc(b.version)} · ${updSize(b.size)}`,'download','href="api/update/android" download data-get-app="android"')
   :row('windows','t-info','Scarica Studio per Windows',`Versione ${esc(b.version)} · ${updSize(b.size)} · Windows 10 e 11`,'download','href="api/update/windows" download data-get-app="windows"'));};
 if(UPD.info)return show();
 fetch('api/update/info',{cache:'no-store'}).then(r=>r.ok?r.json():null).then(j=>{if(j){UPD.info=j;show();}}).catch(()=>{});
}
document.addEventListener('click',e=>{
 const ios=e.target.closest?.('[data-get-ios]');
 if(ios){const w=ios.parentElement,open=!w.classList.contains('open');w.classList.toggle('open',open);ios.setAttribute('aria-expanded',open);return;}
 const a=e.target.closest?.('[data-get-app]');if(!a)return;
 a.classList.remove('sent');void a.offsetWidth;a.classList.add('sent');
 toast(a.dataset.getApp==='android'?'Download avviato: aprilo dalle notifiche e consenti l’installazione':'Download avviato: estrai la cartella e avvia TopCash.Licenze.exe','download');
});
document.addEventListener('click',async e=>{const b=e.target.closest?.('[data-install-pwa]');if(!b||!installPrompt)return;e.preventDefault();
 installPrompt.prompt();const r=await installPrompt.userChoice.catch(()=>null);installPrompt=null;if(r?.outcome!=='accepted')render(false);});
