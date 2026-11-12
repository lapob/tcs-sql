'use strict';
/* Top Cash Studio — UI layer. Data arrives from the Windows host (WebView2) as a single "state" message. */

// ───────────────────────── helpers
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const icon=n=>`<svg aria-hidden="true"><use href="#i-${n}"/></svg>`;
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const nf=new Intl.NumberFormat('it-IT');const DF0=new Intl.DateTimeFormat('it-IT',{day:'2-digit',month:'short',year:'numeric',hour:'2-digit',minute:'2-digit'}),DF1=new Intl.DateTimeFormat('it-IT',{day:'numeric',month:'short',year:'numeric'}),DF2=new Intl.DateTimeFormat('it-IT',{month:'long',year:'numeric'}),DF3=new Intl.DateTimeFormat('it-IT',{month:'short'}),DF4=new Intl.DateTimeFormat('it-IT',{weekday:'long',day:'numeric',month:'long'}),DF5=new Intl.DateTimeFormat('it-IT',{weekday:'short'}); // formatters are costly to build: create once
const fmt=n=>nf.format(n);
const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
const store={get(k,d){try{const v=localStorage.getItem('tc.'+k);return v===null?d:JSON.parse(v);}catch{return d;}},set(k,v){try{localStorage.setItem('tc.'+k,JSON.stringify(v));}catch{}}};
let today=new Date();today.setHours(0,0,0,0);
// Dates are parsed thousands of times per render: memoize (the portal repeats the same strings).
const _pd=new Map();
function parseDate(v){if(!v)return null;let d=_pd.get(v);if(d!==undefined)return d;let m=String(v).match(/^(\d{2})[-/](\d{2})[-/](\d{4})(?:\s+(\d{2}):(\d{2}))?/);d=m?new Date(+m[3],+m[2]-1,+m[1],+(m[4]||0),+(m[5]||0)):(m=String(v).match(/^(\d{4})-(\d{2})-(\d{2})/))?new Date(+m[1],+m[2]-1,+m[3]):null;if(_pd.size>20000)_pd.clear();_pd.set(v,d);return d;}
const dayDiff=d=>d?Math.round((new Date(d.getFullYear(),d.getMonth(),d.getDate())-today)/86400000):null;
const days=v=>dayDiff(parseDate(v));
const dShort=d=>d?DF1.format(d):'—';
const dTime=v=>v?DF0.format(new Date(v)):'—';
const rel=n=>n===null?'':n===0?'oggi':n===1?'domani':n===-1?'ieri':n>0?`tra ${n} giorni`:`${-n} giorni fa`;
const norm=s=>String(s||'').toLocaleLowerCase('it').normalize('NFD').replace(/[̀-ͯ]/g,'');
const normName=s=>norm(s).replace(/[^a-z0-9]/g,'');
const initials=s=>(s||'').replace(/[^\p{L}\p{N} ]/gu,'').split(/\s+/).filter(w=>w.length>1&&!/^(srl|snc|sas|spa|di|e|il|la|lo|s)$/i.test(w)).slice(0,2).map(x=>x[0]).join('').toUpperCase()||'TC';
const hue=s=>{let h=0;for(const c of String(s))h=(h*31+c.charCodeAt(0))%360;return`hsl(${h} 62% 48%)`;};
const markEl=(name,code)=>`<span class="mark" style="--h:${hue(code||name)}">${esc(initials(name))}</span>`;
const hl=(text,q)=>{const t=esc(text);if(!q)return t;const i=norm(text).indexOf(norm(q));return i<0?t:esc(text.slice(0,i))+'<mark>'+esc(text.slice(i,i+q.length))+'</mark>'+esc(text.slice(i+q.length));};

const FAMILY={ILCONTO:'Il Conto',ZMENU:'ZMenu',ILCONTOAN:'Il Conto noleggio',POSSISTEMI:'Pos Sistemi',SELLBY:'Sellby',TILBY:'Tilby'};
const famOfScope=s=>s.replace(/-(attive|scadute)$/,'');
const famLabel=f=>FAMILY[f]||f;

// ───────────────────────── state
let state={records:[],clients:[],orders:[],mastroClients:[],machines:[],interventions:[],warehouses:[],articles:[],stockLevels:[],stock:[],movements:[],invoices:[],dues:[],balances:[],dueNotes:{},sources:[],events:[],notes:{},metadata:{},sourceFolder:'',dataFolder:'',portalConnected:false,syncRunning:false,syncProgress:''};
let D=null; // derived data
let page=store.get('page','overview'),ui={q:'',status:'',product:'',city:'',tab:'current',p:1,sort:{key:'name',dir:1},ostatus:'',ofam:'',taskTab:'open',clientView:'all',itab:'open',mtab:'active',tech:''};
let drawerCode=null,firstState=true,searchTimer=0;

function licenseStatus(r){if(r._st)return r._st;if((r.blocked||'').toUpperCase()==='SI')return'Bloccata';const d=days(r.expiry);if(d!==null&&licRenewed(r))return'Rinnovata';return d===null?'Da verificare':d<0?'Scaduta':d<=30?'In scadenza':'Attiva';}
// "Già rinnovata": the same outcome as the Rinnovi page (key = licence + expiry day), so a new expiry read from the
// portal starts clean by itself, and the mark is shared by the website, the Windows app and the phones.
function licRenewKey(r){const d=parseDate(r.expiry);return d?`lic|${r.id}|${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`:'';}
function renewBy(r){const m=state.renewals?.[licRenewKey(r)];return m?`Segnata come rinnovata${m.by?' da '+m.by:''}${m.at?' il '+dShort(new Date(m.at)):''}`:'';}
// one tap: marked (or unmarked) at once on screen, saved in the background; if the save fails the screen goes back
document.addEventListener('click',async e=>{const b=e.target.closest?.('[data-lic-renew]');if(!b)return;e.preventDefault();e.stopPropagation();
 const r=state.records.find(x=>x.id===b.dataset.licRenew);const key=r&&licRenewKey(r);if(!key||b.disabled)return;
 const was=state.renewals?.[key]||null,on=was?.status==='Rinnovato';
 const back=on?([...(was.steps||[])].reverse().find(s=>s.status&&s.status!=='Rinnovato')?.status||''):'Rinnovato';
 const now=new Date().toISOString(),by=me?.name||'';
 (state.renewals||={})[key]={...(was||{}),status:back,nextContact:on?(was?.nextContact||''):'',at:now,by,steps:[...(was?.steps||[]),{at:now,by,status:back,note:''}]};
 b.disabled=true;derive();render(false);if(drawerCode&&!$('#drawer').hidden)openClient(drawerCode,true);
 if(!on)document.querySelectorAll(`[data-lic-renew="${CSS.escape(r.id)}"]`).forEach(x=>x.classList.add('just')); // one pop, only on what was tapped
 const ok=await sendWork('renewalSave',{key,status:back,note:was?.note||'',nextContact:state.renewals[key].nextContact},'/api/renewals/save');
 if(!ok){if(was)state.renewals[key]=was;else delete state.renewals[key];derive();render(false);if(drawerCode&&!$('#drawer').hidden)openClient(drawerCode,true);return;}
 toast(on?`${r.client}: di nuovo da rinnovare`:`${r.client}: licenza segnata come rinnovata`,on?'repeat':'check');
},true);
function licRenewed(r){const k=licRenewKey(r);return!!k&&state.renewals?.[k]?.status==='Rinnovato';}
const licRenewBtn=(r,big)=>{const d=days(r.expiry);if(d===null||(d>60&&!licRenewed(r)))return'';const on=licRenewed(r);
 return big?`<button type="button" class="btn sm ${on?'ghost':'renew-go'}" data-lic-renew="${esc(r.id)}">${icon(on?'repeat':'check')}${on?'Annulla “rinnovata”':'Già rinnovata'}</button>`
  :`<button type="button" class="icon-btn sm renew-btn${on?' on':''}" data-lic-renew="${esc(r.id)}" data-tip="${on?'Segnata come rinnovata: tocca per annullare':'Segna come già rinnovata'}" aria-label="${on?'Annulla rinnovata':'Già rinnovata'}" aria-pressed="${on}">${icon('check')}</button>`;};
const isCurrent=r=>!r.scope.endsWith('-scadute');
const STATUS_CLS={'Rinnovata':'ok renewed','Attiva':'ok','In scadenza':'due','Scaduta':'bad','Bloccata':'bad','Da verificare':'','Senza licenze':''};
const STATUS_TIP={'Rinnovata':'Segnata come già rinnovata: si aggiorna da sola quando il portale riporta la nuova scadenza','Attiva':'Licenza negli elenchi correnti con scadenza oltre 30 giorni','In scadenza':'Scade entro i prossimi 30 giorni: momento giusto per contattare il cliente','Scaduta':'Data di scadenza già passata','Bloccata':'Il portale segnala la generazione bloccata','Da verificare':'Scadenza non indicata dal portale','Senza licenze':'Cliente presente in anagrafica ma senza matricole negli elenchi letti'};
const badge=s=>`<span class="badge ${STATUS_CLS[s]??''}" data-tip="${esc(STATUS_TIP[s]||'')}">${esc(s)}</span>`;
function orderCls(s){s=norm(s);return /annull|non approv|disdett/.test(s)?'bad':/allegati|sospes|da completare/.test(s)?'due':/^registrato$/.test(s)?'ok':/approvazione|registrazione|inviare|approvato/.test(s)?'info':'';}

function derive(){
 const now=new Date();now.setHours(0,0,0,0);if(+now!==+today)today=now;
 for(const r of state.records){r._st=undefined;r._st=licenseStatus(r);}
 const reg=new Map((state.clients||[]).map(c=>[c.code,c]));const by=new Map();
 for(const r of state.records){if(!by.has(r.clientCode))by.set(r.clientCode,[]);by.get(r.clientCode).push(r);}
 const codes=new Set([...reg.keys(),...by.keys()]);const list=[];const byCode=new Map();const serialTo=new Map();const nameTo=new Map();
 for(const code of codes){
  const g=reg.get(code)||{};const recs=(by.get(code)||[]).slice().sort((a,b)=>(parseDate(a.expiry)||0)-(parseDate(b.expiry)||0));
  const active=recs.filter(isCurrent);const meta=state.metadata?.[code]||{};
  const families=[...new Set([...(g.families||[]),...recs.map(r=>famOfScope(r.scope))])];
  const pool=active.length?active:recs;const st=pool.map(r=>{const s=licenseStatus(r);return s==='Rinnovata'?'Attiva':s;});
  const status=!recs.length?'Senza licenze':st.includes('Bloccata')?'Bloccata':st.includes('In scadenza')?'In scadenza':st.includes('Attiva')?'Attiva':st.every(s=>s==='Scaduta')?'Scaduta':'Da verificare';
  const next=active.map(r=>parseDate(r.expiry)).filter(d=>d&&d>=today).sort((a,b)=>a-b)[0]||null;
  const c={code,name:g.name||recs[0]?.client||code,vat:recs.find(r=>r.vat)?.vat||'',taxCode:g.taxCode||'',city:g.city||'',address:g.address||'',phone:(g.phone||'').trim(),email:g.email||'',families,records:recs,active,orders:[],status,next,meta,note:state.notes?.[code]||'',registry:!!g.code,installer:recs.find(r=>r.installer)?.installer||''};
  c.machines=[];c.interventions=[];c.phones=c.phone?[c.phone]:[];c.emails=c.email?[c.email]:[];c.mastro=null;
  list.push(c);byCode.set(code,c);for(const r of recs)serialTo.set(r.serial,code);nameTo.set(normName(c.name),code);
 }
 const orders=(state.orders||[]).map(o=>{const code=serialTo.get(o.serial)||nameTo.get(normName(o.client))||null;const x={...o,clientCode:code,d:parseDate(o.date)};if(code)byCode.get(code).orders.push(x);return x;}).sort((a,b)=>(b.d||0)-(a.d||0));
 // ── MastroSQL master data: linked to the Zucchetti customer by VAT number / tax code, else by name; otherwise a new customer
 const idKey=s=>String(s||'').toUpperCase().replace(/^IT/,'').replace(/[^0-9A-Z]/g,'');
 const byVat=new Map();for(const c of list){for(const k of[c.vat,c.taxCode].map(idKey))if(k.length>=11&&!byVat.has(k))byVat.set(k,c);}
 const mastroTo=new Map();
 for(const m of state.mastroClients||[]){
  let c=[m.vat,m.taxCode].map(idKey).filter(k=>k.length>=11).map(k=>byVat.get(k)).find(Boolean)||byCode.get(nameTo.get(normName(m.name)));
  if(!c){const code='M'+m.code;c={code,name:m.name,vat:m.vat,taxCode:m.taxCode,city:'',address:'',phone:'',email:'',families:[],records:[],active:[],orders:[],status:'Senza licenze',next:null,meta:state.metadata?.[code]||{},note:state.notes?.[code]||'',registry:false,installer:'',machines:[],interventions:[],phones:[],emails:[],mastroOnly:true};list.push(c);byCode.set(code,c);}
  // a customer registered twice in Mastro (same VAT / tax code / name) stays ONE customer: the first record is the main one
  if(!c.mastro)c.mastro=m;(c.mastroCodes||=[]).push(m.code);mastroTo.set(m.code,c);
  for(const k of[m.vat,m.taxCode].map(idKey))if(k.length>=11&&!byVat.has(k))byVat.set(k,c);if(!nameTo.has(normName(m.name)))nameTo.set(normName(m.name),c.code);
  if(!c.vat)c.vat=m.vat;if(!c.taxCode)c.taxCode=m.taxCode;if(!c.city)c.city=m.city;if(!c.address)c.address=m.address;
  c.phones=[...new Set([...c.phones,...m.phones])];c.emails=[...new Set([...c.emails,...m.emails])];
  if(!c.phone)c.phone=c.phones[0]||'';if(!c.email)c.email=c.emails[0]||'';
 }
 const allM=state.machines||[];const keptM=allM.filter(m=>!rtDropped(m.model));
 const machines=keptM.map(m=>{if(/^[0\s.-]*$/.test(m.fiscalSerial||''))m={...m,fiscalSerial:''};if(/^[0\s.-]*$/.test(m.serial||''))m={...m,serial:''};const c=mastroTo.get(m.clientCode);const ver=parseDate(m.verification);
  const verNext=ver?new Date(ver.getFullYear()+2,ver.getMonth(),ver.getDate()):null; // periodic verification of fiscal devices: every 2 years
  const flag=state.machineFlags?.[m.id]?.state||''; // decided in Studio: "dismessa" (gone) or "attiva" (still there)
  const x={...m,c,client:c?.name||('Cliente Mastro '+m.clientCode),code:c?.code||null,ver,verNext,verDays:dayDiff(verNext),end:parseDate(m.contractEnd),endDays:dayDiff(parseDate(m.contractEnd)),mastroActive:m.active,active:m.active&&flag!=='dismessa',flag};
  // verification overdue for over a year: probably no longer at the customer, out of the round and the deadlines until someone decides
  x.stale=x.active&&flag!=='attiva'&&x.verDays!==null&&x.verDays<-365;if(c)c.machines.push(x);return x;});
 const interventions=(state.interventions||[]).map(i=>{const c=i.source==='mastro'?mastroTo.get(i.clientCode):byCode.get(i.clientCode);
  const x={...i,c,code:c?.code||null,client:c?.name||i.clientName||'—',d:parseDate(i.date)};if(c)c.interventions.push(x);return x;}).sort((a,b)=>(b.d||0)-(a.d||0));
 for(const c of list){c.search=norm([c.name,c.code,c.vat,c.taxCode,c.city,c.address,...c.phones,...c.emails,c.mastro?.pec,c.mastro?.code,...c.records.map(r=>r.serial),...c.machines.map(m=>m.serial+' '+m.fiscalSerial)].join(' '));}
 list.sort((a,b)=>a.name.localeCompare(b.name,'it'));
 const current=state.records.filter(isCurrent);
 D={clients:list,byCode,orders,current,machines,dropped:allM.length-keptM.length,interventions,history:state.records.filter(r=>!isCurrent(r)),
  due30:current.filter(r=>{const d=days(r.expiry);return d!==null&&d>=0&&d<=30&&r._st!=='Rinnovata';}),
  overdue:current.filter(r=>{const d=days(r.expiry);return d!==null&&d<0&&r._st!=='Rinnovata';}),
  tasks:list.filter(c=>c.meta.nextContact||c.meta.followupState&&c.meta.followupState!=='Completato'),
  orders30:orders.filter(o=>o.d&&dayDiff(o.d)>=-30),
  openInterventions:interventions.filter(i=>i.status!=='Chiuso'),
  verDue:machines.filter(m=>m.active&&!m.stale&&m.verDays!==null&&m.verDays<=60),
  stale:machines.filter(m=>m.stale),
  contractDue:machines.filter(m=>m.subscribed&&m.endDays!==null&&m.endDays<=60)};
 if(typeof deriveWarehouse==='function')deriveWarehouse();
 if(typeof deriveAccounting==='function')deriveAccounting();
}

// ───────────────────────── bridge
// Two ways to run: inside the Windows app with its local archive (https://topcash.local), or served by the shared
// Top Cash server (any browser, phone, or the Windows app pointed at the server). "host" = Windows app around the page.
const host=window.chrome?.webview;
const WEB=location.hostname!=='topcash.local'&&!window.__PREVIEW_STATE;
let me=null,hostInfo=null,usersData=null,etag='',offline=false;
document.documentElement.dataset.mode=WEB?'web':'local';
if(WEB&&!host)document.documentElement.dataset.browser='1'; // the plain website (not inside the Windows app): the download page lives here
async function api(path,opt={}){
 const headers={'X-Requested-With':'topcash',...(opt.body&&!(opt.body instanceof FormData)?{'Content-Type':'application/json'}:{}),...(opt.headers||{})};
 const r=await fetch(path,{credentials:'same-origin',...opt,headers});
 if(r.status===401&&!/\/api\/(login|password|session)/.test(path)){showAuth('login');throw new Error('Sessione scaduta: accedi di nuovo.');}
 const ct=r.headers.get('content-type')||'';const data=ct.includes('json')?await r.json():await r.text();
 if(r.status===403&&data?.device){showDevice(data.device,data.code);throw new Error(data.error);}
 if(!r.ok)throw new Error(data?.error||(typeof data==='string'&&data)||('Errore '+r.status));return data;}
const post=(p,b)=>api(p,{method:'POST',body:JSON.stringify(b||{})});
function download(url){const a=document.createElement('a');a.href=url;a.download='';document.body.append(a);a.click();a.remove();toast('Download avviato','download');}
function pickAndUpload(){const i=document.createElement('input');i.type='file';i.accept='.csv,.json,.xlsx';i.onchange=async()=>{const f=i.files[0];if(!f)return;const fd=new FormData();fd.append('file',f);try{const r=await api('/api/import',{method:'POST',body:fd});toast(r.message||'Importazione completata');}catch(e){toast(e.message,'alert');}};i.click();}
const HOST_ACTIONS=['portal','syncPortal','dtr','dtrSync','ital','italSync','rch','rchSync','autostart','external','setSyncToken','disconnectServer','connectServer','hostInfo','setExternal'];
function send(action,data={}){
 if(!WEB){if(host)host.postMessage({action,...data});else toast('Funzione disponibile nell’app Windows (TopCash.Licenze.exe).','alert');return;}
 if(host&&HOST_ACTIONS.includes(action))return host.postMessage({action,...data});
 const fail=e=>toast(e.message,'alert');
 switch(action){
  case'note':post('/api/note',{code:data.code,note:data.note}).then(()=>toast('Nota salvata e condivisa con il team')).catch(fail);break;
  case'meta':post('/api/meta',{code:data.code,meta:data.meta}).catch(fail);break;
  case'exportClients':download('/api/export/clients.csv');break;
  case'export':download('/api/export/licenses.csv');break;
  case'calendar':download('/api/export/calendar.ics');break;
  case'exportStock':download('/api/export/stock.csv');break;
  case'exportReorder':download('/api/export/reorder.csv');break;
  case'exportInvoices':download('/api/export/invoices.csv');break;
  case'exportDues':download('/api/export/dues.csv');break;
  case'backup':download('/api/backup');break;
  case'import':pickAndUpload();break;
  case'external':if(data.url.startsWith('https://'))window.open(data.url,'_blank','noopener');else location.href=data.url;break;
  case'rch':case'rchSync':toast('RCH XDealer si aggiorna dalla postazione dell’ufficio: sessione controllata ogni 15 minuti, dati ogni 2 ore.','sync');break;
  case'ital':case'italSync':toast('Ital Retail si aggiorna dalla postazione dell’ufficio: sessione controllata ogni 15 minuti, dati ogni 2 ore.','sync');break;
  case'dtr':case'dtrSync':toast('DTR-Service si aggiorna dalla postazione dell’ufficio: sessione controllata ogni 15 minuti, dati ogni 2 ore.','sync');break;
  case'syncPortal':case'portal':toast('La lettura da Zucchetti parte dalla postazione dell’ufficio, in automatico ogni 6 ore.','sync');break;
  default:toast('Funzione non disponibile da qui.','alert');
 }
}
function external(url){send('external',{url});}
function toast(msg,ic='check'){const box=$('#toasts');[...box.children].find(x=>x.dataset.msg===msg)?.remove();while(box.children.length>=3)box.firstElementChild.remove(); // no duplicates, max 3
 const t=document.createElement('div');t.className='toast'+(ic==='alert'?' warn':'');t.dataset.msg=msg;t.innerHTML=icon(ic)+`<span>${esc(msg)}</span>`;box.append(t);setTimeout(()=>{t.classList.add('out');setTimeout(()=>t.remove(),320);},4200);}
// ── floating tooltip: one element on <body>, positioned above (or below when no room) and clamped to the viewport,
// so it is never clipped by cards, scroll areas or the table header.
const tipBox=document.createElement('div');tipBox.className='tip';tipBox.setAttribute('role','tooltip');document.body.append(tipBox);
let tipEl=null,tipTimer=0;
function showTip(el){if(!el.isConnected||!el.dataset.tip)return;tipEl=el;tipBox.textContent=el.dataset.tip;tipBox.classList.add('on');
 const r=el.getBoundingClientRect(),w=tipBox.offsetWidth,h=tipBox.offsetHeight;let top=r.top-h-8,below=top<8;if(below)top=r.bottom+8;
 const left=Math.min(Math.max(8,r.left+r.width/2-w/2),innerWidth-w-8);tipBox.style.left=left+'px';tipBox.style.top=top+'px';tipBox.dataset.below=below;}
function hideTip(){clearTimeout(tipTimer);tipEl=null;tipBox.classList.remove('on');}
document.addEventListener('pointerover',e=>{if(e.pointerType==='touch')return;const el=e.target.closest?.('[data-tip]');if(el===tipEl)return;hideTip();if(el?.dataset.tip)tipTimer=setTimeout(()=>showTip(el),320);});
['scroll','pointerdown','keydown','wheel'].forEach(ev=>addEventListener(ev,hideTip,{capture:true,passive:true}));
// ── in-app confirmation dialog (replaces the browser's grey confirm boxes)
let lastAskAlign=false;
function ask({title,text='',ok='Conferma',danger=false,html='',cancel=true}){return new Promise(res=>{
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<div class="modal-box" role="alertdialog" aria-modal="true" aria-label="${esc(title)}"><h3>${esc(title)}</h3>${text?`<p>${text}</p>`:''}${html}<div class="btn-row">${cancel?'<button class="btn ghost" data-m="0">Annulla</button>':''}<button class="btn ${danger?'danger':'accent'}" data-m="1">${esc(ok)}</button></div></div>`;
 document.body.append(m);const prev=document.activeElement;
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();close(false);}if(e.key==='Tab'){const f=[...m.querySelectorAll('button')];const i=f.indexOf(document.activeElement);e.preventDefault();f[(i+(e.shiftKey?-1:1)+f.length)%f.length].focus();}};
 const close=v=>{lastAskAlign=!!m.querySelector('#mastro-align')?.checked;document.removeEventListener('keydown',key,true);m.classList.add('out');setTimeout(()=>m.remove(),220);prev?.focus?.({preventScroll:true});res(v);};
 document.addEventListener('keydown',key,true);m.addEventListener('click',e=>{const b=e.target.closest('[data-m]');if(b)close(b.dataset.m==='1');else if(e.target===m)close(false);});
 setTimeout(()=>m.querySelector('[data-m="1"]').focus(),30);});}
async function copy(text){try{await navigator.clipboard.writeText(text);toast('Copiato negli appunti');}catch{toast('Copia non riuscita','alert');}}

// ───────────────────────── shared UI pieces
const PAGES={accRec:'Da incassare',accInv:'Fatture',accPay:'Da pagare',accCli:'Situazione clienti',overview:'Panoramica',clients:'Clienti',interventions:'Interventi',route:'Giro di oggi',renewals:'Rinnovi',inventory:'PC e sistemi',check:'Controllo dati',machines:'Registratori di cassa',dtr:'Portale DTR',ital:'Portale Ital Retail',rch:'Portale RCH',warehouse:'Magazzino',accounting:'Contabilità',licenses:'Licenze',expiry:'Scadenze',orders:'Ordini',tasks:'Attività',sync:'Sincronizzazione',settings:'Impostazioni',team:'Utenti',apps:'Scarica le app'};
function hero(title,text,actions=''){return`<section class="hero reveal"><div><h1>${title}</h1><p>${text}</p></div><div class="btn-row">${actions}</div></section>`;}
function explain(id,html){if(store.get('hide.'+id,false))return'';return`<div class="explain reveal" style="--i:1"><span class="orb">${icon('sparkle')}</span><div>${html}</div><button class="x" data-hide-explain="${id}" aria-label="Nascondi spiegazione" data-tip="Nascondi questa spiegazione">${icon('close')}</button></div>`;}
function kpi(label,value,note,ic,tone,go,i,tip){return`<div class="card kpi ${tone} reveal" style="--i:${i}" data-go="${go}" tabindex="0" ${tip?`data-tip="${esc(tip)}"`:''}><div class="kpi-top"><span>${label}</span><span class="kpi-icon">${icon(ic)}</span></div><div class="kpi-value" data-count="${value}">${fmt(value)}</div><div class="kpi-note">${note}</div></div>`;}
function pager(total,size){const pages=Math.max(1,Math.ceil(total/size));ui.p=Math.min(Math.max(1,ui.p),pages);const from=total?(ui.p-1)*size+1:0;return`<div class="pager"><span>${fmt(from)}–${fmt(Math.min(ui.p*size,total))} di ${fmt(total)}</span><span class="pg"><button class="prev" data-step="-1" aria-label="Pagina precedente"${ui.p<=1?' disabled':''}>${icon('chevron')}</button><span>${ui.p} / ${pages}</span><button data-step="1" aria-label="Pagina successiva"${ui.p>=pages?' disabled':''}>${icon('chevron')}</button></span></div>`;}
function th(label,key,tip){const on=ui.sort.key===key;return`<th${key?` class="sortable" data-sort="${key}"`:''}>${label}${on?`<span class="arrow">${ui.sort.dir>0?'↑':'↓'}</span>`:''}${tip?`<span class="help" data-tip="${esc(tip)}">?</span>`:''}</th>`;}
// Stable sort: equal keys (incl. two "no date" = Infinity) compare as 0 instead of NaN; empty values always last.
function sortBy(list,getters){const g=getters[ui.sort.key];if(!g)return list;return list.slice().sort((a,b)=>{const x=g(a),y=g(b);if(x===y)return 0;
 const ex=x===''||x==null||x===Infinity,ey=y===''||y==null||y===Infinity;if(ex!==ey)return ex?1:-1;
 return(typeof x==='string'?x.localeCompare(y,'it'):x-y)*ui.sort.dir;});}
// Clamp the current page *before* slicing, so a narrower filter never shows an empty page.
function pageOf(list,size){const pages=Math.max(1,Math.ceil(list.length/size));ui.p=Math.min(Math.max(1,ui.p),pages);return list.slice((ui.p-1)*size,ui.p*size);}
function search(placeholder){return`<label class="search">${icon('search')}<input id="q" value="${esc(ui.q)}" placeholder="${placeholder}" autocomplete="off"></label>`;}
function sel(id,label,options,value){return`<select class="select" id="${id}" aria-label="${label}"><option value="">${label}</option>${options.map(o=>`<option${o===value?' selected':''} value="${esc(o)}">${esc(famLabel(o))}</option>`).join('')}</select>`;}
const telOk=p=>(p||'').replace(/\D/g,'').length>=6;
function contacts(c){return`<div class="contact-icons"><button class="ci ${telOk(c.phone)?'on':'off'}" aria-label="Chiama" ${telOk(c.phone)?`data-ext="tel:${esc(c.phone.replace(/[^\d+]/g,''))}" data-tip="Chiama ${esc(c.phone)}"`:'disabled'}>${icon('phone')}</button><button class="ci ${c.email?'on':'off'}" aria-label="Email" ${c.email?`data-ext="mailto:${esc(c.email)}" data-tip="Scrivi a ${esc(c.email)}"`:'disabled'}>${icon('mail')}</button><button class="ci ${c.address?'on':'off'}" aria-label="Mappa" ${c.address?`data-ext="${mapsUrl(c)}" data-tip="Apri in Google Maps"`:'disabled'}>${icon('pin')}</button></div>`;}
const mapsUrl=c=>'https://www.google.com/maps/search/?api=1&query='+encodeURIComponent([c.address,c.city].filter(Boolean).join(', '));
const empty=(ic,title,text)=>`<div class="empty">${icon(ic)}<strong>${title}</strong><span>${text}</span></div>`;
function lastCapture(){const d=state.sources.map(s=>s.capturedAt).concat((state.clients||[]).slice(0,1).map(c=>c.capturedAt)).filter(Boolean).sort().at(-1);return d?dTime(d):'mai';}

// ───────────────────────── pages
// ── One agenda for everything that expires: Zucchetti licences, periodic checks and service contracts (Mastro),
// electronic journal / certificate / RT-Utility licence (DTR). from..to are day offsets from today.
const AG_KIND={lic:['Licenza Zucchetti','info'],ver:['Verificazione','acc'],con:['Abbonamento','acc'],ge:['Giornale elettronico','due'],cert:['Certificato RT','due'],rtu:['Licenza RT-Utility','info'],isub:['Abbonamento Ital Retail','acc'],icert:['Certificato RT','due'],rver:['Verificazione','acc'],rcert:['Certificato RT','due'],war:['Garanzia','info'],aexp:['Scadenza software','acc']};
function agendaItems(from,to){
 const out=[];const add=(k,d,n,title,sub,attr,ref)=>{if(d&&n!==null&&n>=from&&n<=to)out.push({k,d,n,title,sub,attr,ref});};
 for(const r of D.current){const d=parseDate(r.expiry);add('lic',d,dayDiff(d),r.client,`${famLabel(famOfScope(r.scope))} · ${r.serial}`,`data-client="${esc(r.clientCode)}"`,r.id);}
 for(const m of D.machines){if(m.active&&!m.stale)add('ver',m.verNext,m.verDays,m.client,`${m.model||'Macchina'} · ${m.serial}`,m.code?`data-client="${esc(m.code)}"`:'',m.id);
  if(m.subscribed)add('con',m.end,m.endDays,m.client,`${m.model||'Macchina'} · ${m.serial}`,m.code?`data-client="${esc(m.code)}"`:'',m.id);}
 if(typeof workAgenda==='function')workAgenda(add);
 if(typeof DT!=='undefined'&&DT)for(const r of DT.regs){const nm=r.c?.name||r.client,a=`data-dtr="${esc(r.serial)}"`;
  add('ge',parseDate(r.geDue),r.ge,nm,`GE ${r.geNumber||''} · ${r.serial}`,a);
  if(r.d?.certDue)add('cert',parseDate(r.d.certDue),r.cert,nm,r.serial,a);
  if(r.lic?.to)add('rtu',parseDate(r.lic.to),r.licDays,nm,r.serial,a);}
 if(typeof IT!=='undefined'&&IT)for(const r of IT.devs){const nm=r.c?.name||r.label||r.serial,a=`data-ital="${esc(r.serial)}"`;
  if(r.d?.subTo&&!r.d.autoRenew)add('isub',parseDate(r.d.subTo),r.sub,nm,`${r.type||'Cassa'} · ${r.serial}`,a);
  if(r.d?.certDue)add('icert',parseDate(r.d.certDue),r.cert,nm,`Ital Retail · ${r.serial}`,a);}
 if(typeof RC!=='undefined'&&RC)for(const r of RC.live){const nm=r.c?.name||r.label||r.serial,a=`data-rch="${esc(r.serial)}"`;
  if(r.verNext&&!r.m)add('rver',r.verNext,r.ver,nm,`RCH ${r.type||''} · ${r.serial}`,a);
  if(r.certDue)add('rcert',parseDate(r.certDue),r.cert,nm,`RCH · ${r.serial}`,a);}
 return out.sort((a,b)=>a.d-b.d);
}
function agendaRow(x){const[label,cls]=AG_KIND[x.k];return`<div class="list-row" ${x.attr}><span class="date-chip ${x.n<0?'bad':x.n<=30?'due':''}"><b>${x.d.getDate()}</b><span>${DF3.format(x.d)}</span></span><div class="grow"><strong>${esc(x.title)}</strong><small><span class="badge ${cls}">${label}</span> ${esc(x.sub)}</small></div>${x.k==='lic'&&state.renewals?.[`lic|${x.ref}|${isoDay(x.d)}`]?.status==='Rinnovato'?'<span class="badge ok renewed">Rinnovata</span>':`<span class="in-days" style="color:var(--${x.n<0?'bad':x.n<=30?'due':'text-3'})">${rel(x.n)}</span>`}</div>`;}
// "Da fare oggi": only the lines that need attention, each one opens the right page
function todayCard(){
 const late=D.tasks.filter(c=>c.meta.followupState!=='Completato'&&c.meta.nextContact&&days(c.meta.nextContact)<=0).length;
 const week=agendaItems(-3650,7);const dt=typeof DT!=='undefined'&&DT?DT:null;const it=typeof IT!=='undefined'&&IT?IT:null;const rc=typeof RC!=='undefined'&&RC?RC:null;
 const st=state.dtrStatus||{};const stale=WEB&&st.lastSync&&st.checkedAt&&(Date.now()-new Date(st.checkedAt))>3*3600e3;
 const rows=[
  [stale,'alert','bad','La postazione di sincronizzazione non si vede da '+(stale?Math.round((Date.now()-new Date(st.checkedAt))/3600e3):0)+' ore','Zucchetti, DTR, Ital Retail e RCH non si aggiornano: controlla che il PC dell’ufficio sia acceso con Studio aperto.','sync'],
  [D.openInterventions.length,'tool','info',`${plur(D.openInterventions.length,'intervento aperto','interventi aperti')}`,'Da completare o chiudere','interventions'],
  [late,'task','due',`${fmt(late)} promemoria da fare`,'Contatti fissati per oggi o già passati','tasks'],
  [week.length,'calendar','due',`${plur(week.length,'scadenza entro 7 giorni','scadenze entro 7 giorni')}`,'Licenze, verificazioni, abbonamenti, GE e certificati (comprese quelle già passate)','expiry'],
  [dt?.errors.length,'alert','bad',`${plur(dt?.errors.length||0,'cassa con errori','casse con errori')}`,'Errori o avvisi inviati a DTR negli ultimi 7 giorni','dtr','errors'],
  [dt?.silent.length,'clock','bad',`${plur(dt?.silent.length||0,'cassa silenziosa','casse silenziose')}`,'Nessuna comunicazione da oltre 7 giorni','dtr','silent'],
  [it?.update.length,'download','due',`${plur(it?.update.length||0,'cassa Ital Retail da aggiornare','casse Ital Retail da aggiornare')}`,'Nuovo firmware disponibile sul portale','ital','update'],
  [it?.low.length,'box','bad',`${fmt(it?.low.length||0)} giornali elettronici quasi pieni`,'Casse Ital Retail con poco spazio nel DGFE','ital','dgfe'],
  [rc?.low.length,'box','bad',`${fmt(rc?.low.length||0)} giornali elettronici RCH quasi pieni`,'Registratori RCH con poco spazio nel DGFE','rch','dgfe'],
  [rc?.silent.length,'clock','due',`${plur(rc?.silent.length||0,'registratore RCH senza chiusure','registratori RCH senza chiusure')}`,'Nessuna chiusura giornaliera inviata da oltre 10 giorni','rch','silent'],
  [typeof A!=='undefined'&&A?A.overdue.length:0,'euro','due',`${plur(typeof A!=='undefined'&&A?A.overdue.length:0,'scadenza contabile passata','scadenze contabili passate')}`,'Incassi e pagamenti da sollecitare','accRec'],
  [isAdmin()&&typeof W!=='undefined'&&W?W.below.length:0,'box','due',`${plur(typeof W!=='undefined'&&W?W.below.length:0,'articolo sotto scorta','articoli sotto scorta')}`,'Da riordinare','warehouse'],
 ].filter(r=>r[0]);
 return`<div class="card reveal today" style="--i:5"><div class="card-head"><div><h2>Da fare oggi</h2><p>${rows.length?'Solo quello che richiede attenzione.':'Niente in sospeso: tutto in ordine.'}</p></div></div>
  ${rows.map(([,ic,tone,title,sub,pg,tab])=>`<div class="list-row" data-go="${pg}"${tab?` data-go-${pg==='ital'?'itab':pg==='rch'?'rtab':'dtab'}="${tab}"`:''} tabindex="0"><span class="today-ic t-${tone}">${icon(ic)}</span><div class="grow"><strong>${title}</strong><small>${sub}</small></div><span class="row-go">${icon('chevron')}</span></div>`).join('')||empty('check','Tutto fatto','Nessuna scadenza, errore o promemoria in sospeso.')}</div>`;
}
function overview(){
 const h=new Date().getHours();const greet=h<13?'Buongiorno':h<18?'Buon pomeriggio':'Buonasera';
 const cs=D.clients.filter(c=>!c.dormant),withMail=cs.filter(c=>c.email).length,withPhone=cs.filter(c=>telOk(c.phone)||c.phones?.some(telOk)).length;
 const upcoming=D.current.filter(r=>{const d=days(r.expiry);return d!==null&&d>=-7&&d<=60;}).sort((a,b)=>parseDate(a.expiry)-parseDate(b.expiry)).slice(0,7);
 const months=[...Array(12)].map((_,i)=>{const d=new Date(today.getFullYear(),today.getMonth()-11+i,1);return{d,n:D.orders.filter(o=>o.d&&o.d.getFullYear()===d.getFullYear()&&o.d.getMonth()===d.getMonth()).length};});const maxM=Math.max(1,...months.map(m=>m.n));
 const fams=Object.keys(FAMILY).map(f=>({f,n:D.current.filter(r=>famOfScope(r.scope)===f).length})).filter(x=>x.n);const maxF=Math.max(1,...fams.map(x=>x.n));
 const agenda=D.tasks.filter(c=>c.meta.nextContact&&c.meta.followupState!=='Completato').sort((a,b)=>a.meta.nextContact.localeCompare(b.meta.nextContact)).slice(0,5);
 const needSync=!(state.clients||[]).length;
 return`${hero(`${greet}, <span class="grad">${esc(WEB&&me?.name?me.name.split(/\s+/)[0]:'Top Cash')}</span>.`,`Tutti i tuoi clienti, licenze e ordini Zucchetti in un unico posto · ${DF4.format(new Date())}`,`<button class="btn" data-action="exportClients">${icon('download')}Esporta anagrafica</button>`)}
 ${explain('overview','<strong>Questa è la tua plancia.</strong> In alto i numeri chiave (cliccali per aprire la sezione), poi le scadenze imminenti, l’andamento degli ordini e lo stato delle licenze. Usa <strong>Ctrl K</strong> in qualsiasi momento per trovare un cliente, una matricola o un ordine.')}
 <div class="kpis">
  ${kpi(cs.length===1?'Cliente attivo':'Clienti attivi',cs.length,`${fmt(withPhone)} con telefono${D.dormant?' · '+plur(D.dormant,'archiviato','archiviati'):' · '+fmt(withMail)+' con email'}`,'users','','clients',2,'Clienti distinti da anagrafica e licenze Zucchetti')}
  ${kpi('Licenze attive',D.current.length,`${fmt(D.history.length)} nello storico scadute`,'key','t-ok','licenses',3,'Licenze presenti negli elenchi correnti del portale')}
  ${kpi('In scadenza · 30 gg',D.due30.length,D.overdue.length?`${fmt(D.overdue.length)} già scadute da gestire`:'Nessuna scaduta in sospeso','clock','t-due','expiry',4,'Licenze che scadono nei prossimi 30 giorni')}
  ${kpi('Interventi aperti',D.openInterventions.length,D.verDue.length?`${plur(D.verDue.length,'verificazione entro 60 gg','verificazioni entro 60 gg')}`:`${plur(D.orders30.length,'ordine Zucchetti in 30 gg','ordini Zucchetti in 30 gg')}`,'tool','t-info','interventions',5,'Interventi tecnici non ancora chiusi')}
 </div>
 <div class="layout-2"><div class="stack">
  ${todayCard()}
  ${typeof accessCard==='function'&&(!WEB||isAdmin())?accessCard(true):''}
  ${WEB&&(me?.role==='admin'||me?.role==='owner')&&state.features?.backupError?`<div class="banner reveal" style="--i:5"><span class="orb">${icon('alert')}</span><div class="grow"><h3>Copia esterna dei backup non riuscita</h3><p>${esc(state.features.backupError)} Collega il disco: il server riprova da solo ogni ora.</p></div><button class="btn" data-go="settings">Impostazioni ${icon('arrow')}</button></div>`:''}
  ${needSync?`<div class="banner reveal" style="--i:6"><span class="orb">${icon('sync')}</span><div class="grow"><h3>Completa le anagrafiche dei clienti</h3><p>Avvia una sincronizzazione: verranno letti indirizzi, telefoni, email, codici fiscali e ordini da MY Zucchetti.</p></div><button class="btn" data-go="sync">Come si fa ${icon('arrow')}</button></div>`:`<div class="banner reveal" style="--i:6"><span class="orb">${icon('shield')}</span><div class="grow"><h3>Archivio allineato con Zucchetti</h3><p>Ultima lettura dal portale: ${esc(lastCapture())}. ${WEB?'Tutto il team vede gli stessi dati, in tempo reale.':'I dati restano sul tuo computer.'}</p></div><button class="btn" data-go="sync">Dettagli ${icon('arrow')}</button></div>`}
  <div class="card reveal" style="--i:7"><div class="card-head"><div><h2>Prossime scadenze</h2><p>Licenze che scadono nei prossimi 60 giorni (e quelle appena scadute).</p></div><button class="btn ghost sm" data-go="expiry">Scadenziario ${icon('arrow')}</button></div>
   ${upcoming.map(r=>{const d=parseDate(r.expiry),n=dayDiff(d);return`<div class="list-row" data-client="${esc(r.clientCode)}"><span class="date-chip ${n<0?'bad':n<=30?'due':''}"><b>${d.getDate()}</b><span>${DF3.format(d)}</span></span><div class="grow"><strong>${esc(r.client)}</strong><small>${esc(famLabel(famOfScope(r.scope)))} · <span class="mono">${esc(r.serial)}</span></small></div><span class="in-days" style="color:var(--${n<0?'bad':n<=30?'due':'text-3'})">${rel(n)}</span></div>`;}).join('')||empty('calendar','Nessuna scadenza imminente','Nei prossimi 60 giorni non scade nessuna licenza.')}
  </div>
  <div class="card reveal" style="--i:8"><div class="card-head"><div><h2>Ordini negli ultimi 12 mesi</h2><p>Ordini web registrati sul portale, per mese.</p></div><button class="btn ghost sm" data-go="orders">Tutti gli ordini ${icon('arrow')}</button></div>
   ${D.orders.length?`<div class="bars">${months.map((m,i)=>`<div class="bar"><i style="height:${Math.max(3,m.n/maxM*100)}%;--i:${i}" data-v="${m.n} ordini"></i><span>${DF3.format(m.d)}</span></div>`).join('')}</div>`:empty('bag','Nessun ordine in archivio','Gli ordini arrivano con la prossima sincronizzazione.')}
  </div>
 </div><div class="stack">
  ${donut()}
  <div class="card reveal" style="--i:8"><h2>Licenze per prodotto</h2><p class="sub">Elenchi correnti per famiglia Zucchetti.</p><div style="margin-top:12px">${fams.map((x,i)=>`<div class="hbar"><span>${esc(famLabel(x.f))}</span><span class="track"><span class="fill" style="width:${x.n/maxF*100}%;--i:${i};display:block"></span></span><strong>${fmt(x.n)}</strong></div>`).join('')||'<p class="sub">Nessuna licenza.</p>'}</div></div>
  <div class="card reveal" style="--i:9"><div class="card-head"><div><h2>Agenda contatti</h2><p>I promemoria che hai fissato.</p></div><button class="btn ghost sm" data-go="tasks" aria-label="Apri le attività" data-tip="Tutte le attività">${icon('arrow')}</button></div>${agenda.map(c=>{const n=days(c.meta.nextContact);return`<div class="list-row" data-client="${esc(c.code)}">${markEl(c.name,c.code)}<div class="grow"><strong>${esc(c.name)}</strong><small>${esc(c.meta.followupState||'Da contattare')}</small></div><span class="in-days" style="color:var(--${n<0?'bad':n===0?'due':'text-3'})">${rel(n)}</span></div>`;}).join('')||`<p class="sub">Apri un cliente e imposta “Prossimo contatto” per ritrovarlo qui.</p>`}</div>
 </div></div>`;
}
function donut(){const groups=[['Attiva','var(--ok)'],['In scadenza','var(--due)'],['Scaduta','var(--bad)'],['Bloccata','#9f1239'],['Da verificare','var(--text-3)']].map(([n,c])=>({n,c,v:D.current.filter(r=>licenseStatus(r)===n).length}));const total=D.current.length||1;const C=2*Math.PI*52;let off=0;
 return`<div class="card reveal" style="--i:7"><h2>Stato delle licenze</h2><p class="sub">Calcolato da data di scadenza e blocco del portale.</p><div class="donut-wrap"><svg class="donut" viewBox="0 0 130 130"><circle class="track" cx="65" cy="65" r="52"/>${groups.filter(g=>g.v).map(g=>{const len=g.v/total*C;const gap=groups.filter(x=>x.v).length>1?3:0;const cap=13,round=len>cap+gap+2;/* round caps stick out half a stroke on each side: shorten the dash so segments never overlap; tiny ones get square ends */const s=`<circle cx="65" cy="65" r="52" stroke="${g.c}"${round?'':' style="stroke-linecap:butt"'} stroke-dasharray="${round?(len-gap-cap).toFixed(2):Math.max(.5,len-gap).toFixed(2)} ${C}" stroke-dashoffset="${(-(off+(round?cap/2:0)+gap/2)).toFixed(2)}"/>`;off+=len;return s;}).join('')}</svg><div class="donut-center"><strong data-count="${D.current.length}">${fmt(D.current.length)}</strong><span>licenze correnti</span></div></div>${groups.filter(g=>g.v).map(g=>`<div class="legend" data-tip="${esc(STATUS_TIP[g.n]||'')}"><span><i style="background:${g.c}"></i>${g.n}</span><strong>${fmt(g.v)}</strong></div>`).join('')}</div>`;}

function clientsPage(){
 let list=D.clients;const q=norm(ui.q);
 if(q)list=list.filter(c=>c.search.includes(q));
 else if(ui.clientView==='archived')list=list.filter(c=>c.dormant);else list=list.filter(c=>!c.dormant);
 if(ui.ctech)list=list.filter(c=>c.tech===ui.ctech);
 if(ui.status)list=list.filter(c=>c.status===ui.status);
 if(ui.product)list=list.filter(c=>c.families.includes(ui.product));
 if(ui.city)list=list.filter(c=>c.city===ui.city);
 if(ui.clientView==='fav')list=list.filter(c=>c.meta.favorite);
 if(ui.clientView==='nocontact')list=list.filter(c=>!c.email&&!telOk(c.phone));
 list=sortBy(list,{name:c=>c.name,city:c=>c.city||'~',lic:c=>c.active.length,next:c=>c.next?+c.next:Infinity});
 const size=14;const cities=[...new Set(D.clients.map(c=>c.city).filter(Boolean))].sort((a,b)=>a.localeCompare(b,'it'));
 const rows=pageOf(list,size);
 if(ui.clientView==='deleted')return deletedClientsPage();
 return`${hero('I tuoi <span class="grad">clienti</span>.','Anagrafica completa dal portale Zucchetti, unita a licenze, ordini e alle tue note.',`${canEditClients()?`<button class="btn accent" data-new-client>${icon('plus')}Nuovo cliente</button>`:''}<button class="btn" data-action="exportClients">${icon('download')}Esporta CSV</button>`)}
 ${explain('clients','<strong>Clicca su un cliente</strong> per aprire la sua scheda: contatti, licenze, ordini, promemoria e note. Le icone nella colonna Contatti ti permettono di <strong>chiamare, scrivere o aprire la mappa</strong> con un clic. Clicca sull’intestazione di una colonna per ordinarla.')}
 <div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Anagrafica<span class="count">${fmt(list.length)}</span></h2><p>${fmt(D.clients.filter(c=>c.registry).length)} schede dal portale Zucchetti · ${fmt(D.clients.filter(c=>c.mastro).length)} da MastroSQL · ${fmt(D.clients.filter(c=>c.meta.favorite).length)} preferiti</p></div>
  <div class="seg">${[['all','Attivi'],['fav','Preferiti'],['nocontact','Senza contatti'],...(D.dormant?[['archived',`Archiviati · ${fmt(D.dormant)}`]]:[]),...(isAdmin()&&D.deleted?.length?[['deleted',`Eliminati · ${D.deleted.length}`]]:[])].map(([k,l])=>`<button class="${ui.clientView===k?'active':''}" data-cview="${k}">${l}</button>`).join('')}</div></div>
  <div class="toolbar">${search('Cerca per nome, P.IVA, città, telefono, email, matricola…')}${sel('f-status','Tutti gli stati',['Attiva','In scadenza','Scaduta','Bloccata','Da verificare','Senza licenze'],ui.status)}${sel('f-product','Tutti i prodotti',Object.keys(FAMILY),ui.product)}${sel('f-city','Tutte le località',cities,ui.city)}${D.techList?.length?sel('f-ctech','Tutti i tecnici',D.techList,ui.ctech):''}</div>
  <div class="table-wrap"><table><thead><tr>${th('Cliente','name')}${th('Località','city')}<th>Contatti</th><th>Prodotti</th>${th('Licenze','lic','Licenze negli elenchi correnti')}${th('Prossima scadenza','next')}<th>Stato</th><th></th></tr></thead><tbody>
  ${rows.map((c,i)=>`<tr class="reveal" style="--i:${i}" data-client="${esc(c.code)}" tabindex="0"><td><div class="who">${markEl(c.name,c.code)}<div><strong>${hl(c.name,ui.q)}${c.meta.favorite?`<svg class="fav"><use href="#i-star"/></svg>`:''}</strong><small>Cod. ${esc(c.code.replace(/^0+/,''))}${c.vat?` · P.IVA ${esc(c.vat)}`:''}</small></div></div></td><td>${c.city?esc(c.city):'<span class="muted">—</span>'}${c.tech?`<br><small class="muted">${icon('tool')} ${esc(c.tech)}</small>`:''}</td><td>${contacts(c)}</td><td>${(c.kinds||[]).map(g=>svcChip(g,1)).join('')}${c.families.map(f=>`<span class="chip">${esc(famLabel(f))}</span>`).join('')}</td><td class="num">${c.active.length}${c.records.length>c.active.length?`<span class="muted"> +${c.records.length-c.active.length}</span>`:''}</td><td>${c.next?`${dShort(c.next)}<br><small class="muted">${rel(dayDiff(c.next))}</small>`:'<span class="muted">—</span>'}</td><td>${badge(c.status)}</td><td><span class="row-go">${icon('chevron')}</span></td></tr>`).join('')||`<tr><td colspan="8">${empty('search','Nessun cliente trovato','Prova con un altro nome, una partita IVA o una matricola.')}</td></tr>`}
  </tbody></table></div>${pager(list.length,size)}</div>`;
}

// ── team: interventions go to a person of the team, who finds them in "I miei" (and gets a notice)
const TEAM_ROLE={tecnico:'Tecnici',ufficio:'Amministrazione',admin:'Amministratori',owner:'Amministratori'};
function techOptions(sel){
 const team=(state.team||[]),names=new Set(team.map(u=>norm(u.name)));
 const groups={};for(const u of team){(groups[TEAM_ROLE[u.role]||'Team']||=[]).push(u.name);}
 const order=['Tecnici','Amministrazione','Amministratori','Team'].filter(g=>groups[g]);
 const old=sel&&!names.has(norm(sel))?`<optgroup label="Altro"><option selected>${esc(sel)}</option></optgroup>`:'';
 return`<option value="">Nessuno</option>${order.map(g=>`<optgroup label="${g}">${groups[g].sort((a,b)=>a.localeCompare(b,'it')).map(n=>`<option${norm(n)===norm(sel)?' selected':''}>${esc(n)}</option>`).join('')}</optgroup>`).join('')}${old}`;
}
const mineInt=i=>me&&i.source==='app'&&norm(i.technician)===norm(me.name);
/** new state: interventions newly assigned to me by someone else → notice (and system notification when allowed) */
function noticeAssigned(prev,next){
 if(!me||!prev)return;const was=new Map(prev.map(i=>[i.id,i]));
 const fresh=next.filter(i=>mineInt(i)&&i.status!=='Chiuso'&&norm(i.updatedBy||i.createdBy||'')!==norm(me.name)&&!mineInt(was.get(i.id)||{}));
 if(!fresh.length)return;
 const t=fresh.length===1?`Nuovo intervento assegnato a te: ${fresh[0].clientName||fresh[0].client||''}${fresh[0].title?' · '+fresh[0].title:''}`:`${fresh.length} nuovi interventi assegnati a te`;
 toast(t,'info');
 try{if('Notification' in window&&Notification.permission==='granted'&&document.hidden)new Notification('Top Cash Studio',{body:t,tag:'int-'+fresh[0].id});}catch{}
}

function deletedClientsPage(){
 const all=D.deleted||[],q=norm(ui.delQ||''),found=q?all.filter(c=>norm(c.name+' '+c.code+' '+(c.vat||'')+' '+(c.city||'')).includes(q)):all,list=found.slice(0,200);
 return`${hero('Clienti <span class="grad">eliminati</span>.',`Nascosti da Studio per tutto il team: i portali e Mastro non sono stati toccati.${D.autoGone?` ${plur(D.autoGone,'cliente è uscito','clienti sono usciti')} da soli perché senza macchine attive: tornano appena Mastro gliene assegna una.`:''} Ripristinali quando vuoi.`,`<button class="btn" data-cview="all">${icon('arrow')}Torna ai clienti</button>`)}
 <div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Eliminati<span class="count">${fmt(all.length)}</span></h2>${found.length>list.length?`<p>Ne mostro ${fmt(list.length)} su ${fmt(found.length)}: cerca per nome, codice, P.IVA o città.</p>`:''}</div><label class="search" style="max-width:280px">${icon('search')}<input data-del-q placeholder="Cerca tra gli eliminati" value="${esc(ui.delQ||'')}" aria-label="Cerca tra gli eliminati"></label></div>
 ${list.map(c=>`<div class="list-row">${markEl(c.name,c.code)}<div class="grow"><strong>${esc(c.name)}</strong><small>Cod. ${esc(c.code.replace(/^0+/,''))}${c.vat?' · P.IVA '+esc(c.vat):''}${c.autoGone?' · senza macchine attive':c.edit?.updatedBy?` · eliminato da ${esc(c.edit.updatedBy)} ${esc(dTime(c.edit.updatedAt))}`:''}</small></div><button class="btn sm" data-restore-client="${esc(c.code)}">${icon('sync')}Ripristina</button></div>`).join('')||empty('check','Nessun cliente eliminato','')}</div>`;
}
const licEditBtn=r=>isAdmin()?`<button class="icon-btn sm" data-edit-lic="${esc(r.id)}" data-tip="Modifica licenza" aria-label="Modifica licenza">${icon('edit')}</button>`:'';
const licNote=r=>{const e=D.licEdits?.[r.id];return e?`<span class="badge info" data-tip="${esc([e.note,e.updatedBy&&('Modificata da '+e.updatedBy)].filter(Boolean).join(' · '))}">${e.expiry?'scadenza corretta':'nota'}</span>`:'';};
function licTable(list,size=14,title='Registro licenze',sub='Dati originali dell’ultima lettura dal portale.'){
 const q=norm(ui.q);if(q)list=list.filter(r=>norm([r.client,r.clientCode,r.vat,r.serial,r.product].join(' ')).includes(q));
 if(ui.status)list=list.filter(r=>licenseStatus(r)===ui.status);if(ui.product)list=list.filter(r=>famOfScope(r.scope)===ui.product);
 if(ui.sort.key==='name')ui.sort={key:'exp',dir:1};
 list=sortBy(list,{exp:r=>parseDate(r.expiry)?+parseDate(r.expiry):Infinity,client:r=>r.client,serial:r=>r.serial,ver:r=>r.version});
 const rows=pageOf(list,size);
 return`<div class="card reveal" style="--i:3"><div class="card-head"><div><h2>${title}<span class="count">${fmt(list.length)}</span></h2><p>${sub}</p></div></div>
 <div class="toolbar">${search('Cerca cliente, matricola, P.IVA…')}${sel('f-status','Tutti gli stati',['Attiva','In scadenza','Scaduta','Bloccata','Da verificare'],ui.status)}${sel('f-product','Tutti i prodotti',Object.keys(FAMILY),ui.product)}</div>
 <div class="table-wrap"><table><thead><tr>${th('Cliente','client')}${th('Matricola','serial')}<th>Prodotto</th>${th('Versione','ver')}${th('Scadenza','exp')}<th>Stato</th><th></th></tr></thead><tbody>
 ${rows.map((r,i)=>{const d=parseDate(r.expiry),n=dayDiff(d);return`<tr class="reveal" style="--i:${i}" data-client="${esc(r.clientCode)}" tabindex="0"><td><div class="who">${markEl(r.client,r.clientCode)}<div><strong>${hl(r.client,ui.q)}</strong><small>Cod. ${esc(r.clientCode.replace(/^0+/,''))}</small></div></div></td><td class="mono">${hl(r.serial,ui.q)}</td><td><span class="chip">${esc(famLabel(famOfScope(r.scope)))}</span></td><td class="mono">${esc(r.version)||'—'}</td><td>${d?`${dShort(d)}<br><small style="color:var(--${n<0?'bad':n<=30?'due':'text-3'})">${rel(n)}</small>`:'<span class="muted">Non indicata</span>'}</td><td>${badge(licenseStatus(r))} ${licNote(r)}</td><td><div class="row-act">${licRenewBtn(r)}${licEditBtn(r)}<span class="row-go">${icon('chevron')}</span></div></td></tr>`;}).join('')||`<tr><td colspan="7">${empty('key','Nessuna licenza','Modifica la ricerca o i filtri.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,size)}</div>`;
}
function licensesPage(){
 const src=ui.tab==='all'?state.records:ui.tab==='history'?D.history:D.current;
 return`${hero('Le tue <span class="grad">licenze</span>.','Tutte le matricole Zucchetti, con versione, scadenza e stato calcolato.',`<button class="btn" data-action="export">${icon('download')}Esporta CSV</button>`)}
 ${explain('licenses','<strong>“Correnti”</strong> sono le licenze negli elenchi attivi del portale; lo <strong>storico</strong> contiene quelle già scadute da meno di 6 mesi. Non compaiono le licenze segnate <strong>non valide</strong> sul portale (quadrato rosso) né quelle scadute da oltre 6 mesi. Lo stato è calcolato dalla data di scadenza: <span class="badge due">In scadenza</span> significa entro 30 giorni. Clicca una riga per aprire il cliente.')}
 <div class="toolbar reveal" style="--i:2"><div class="seg">${[['current',`Correnti · ${fmt(D.current.length)}`],['history',`Storico · ${fmt(D.history.length)}`],['all','Tutte']].map(([k,l])=>`<button class="${ui.tab===k?'active':''}" data-tab="${k}">${l}</button>`).join('')}</div></div>
 ${cleanupNote()}${licTable(src)}`;
}
function cleanupNote(){const k=state.licenseCleanup;if(!k||!(k.red+k.old+k.hidden))return'';
 return`<div class="note reveal" style="--i:2">${icon('sparkle')}<span>Elenco ripulito: ${[k.red&&`<strong>${fmt(k.red)}</strong> non valide sul portale`,k.old&&`<strong>${fmt(k.old)}</strong> scadute da oltre 6 mesi`,k.hidden&&`<strong>${fmt(k.hidden)}</strong> tolte a mano`].filter(Boolean).join(' · ')}. ${isAdmin()?'Per correggere una licenza usa la matita accanto alla riga.':''}</span></div>`;}
function expiryPage(){
 const win=D.current.filter(r=>{const d=days(r.expiry);return d!==null&&d>=0&&d<=120;}).sort((a,b)=>parseDate(a.expiry)-parseDate(b.expiry));
 const months=new Map();for(const r of win){const d=parseDate(r.expiry);const k=d.getFullYear()+'-'+d.getMonth();if(!months.has(k))months.set(k,{d,items:[]});months.get(k).items.push(r);}
 const soon=D.current.filter(r=>{const d=days(r.expiry);return d>30&&d<=90;}).length;
 const ak=ui.akind||'';const ag=agendaItems(-60,ui.adays||30).filter(x=>!ak||x.k===ak);const agKinds=[...new Set(agendaItems(-60,ui.adays||30).map(x=>x.k))];
 return`${hero('Gioca d’<span class="grad">anticipo</span>.','Tutte le scadenze in un’unica agenda: licenze Zucchetti, verificazioni, abbonamenti, giornali elettronici, certificati e licenze RT-Utility.',`<button class="btn" data-action="calendar">${icon('calendar')}Aggiungi al calendario</button>`)}
 ${explain('expiry','Qui vedi <strong>quando scadono le licenze</strong>. “Aggiungi al calendario” crea un file .ics con le scadenze dei prossimi 90 giorni da aprire in Outlook o Google Calendar. <strong>Nessun rinnovo parte in automatico</strong>: decidi sempre tu.')}
 <div class="kpis" style="grid-template-columns:repeat(3,1fr)">
  ${kpi('Entro 30 giorni',D.due30.length,'Da contattare subito','clock','t-due','expiry',2)}
  ${kpi('Tra 31 e 90 giorni',soon,'Il prossimo trimestre','calendar','t-info','expiry',3)}
  ${kpi('Già scadute',D.overdue.length,'Ancora negli elenchi correnti','alert','t-bad','expiry',4)}
 </div>
 <div class="card reveal" style="--i:5;margin-bottom:16px"><div class="card-head"><div><h2>Agenda<span class="count">${fmt(ag.length)}</span></h2><p>Tutto ciò che scade, in ordine di data (comprese le scadenze passate da meno di 60 giorni).</p></div><div class="seg">${[[7,'7 giorni'],[30,'30 giorni'],[90,'90 giorni']].map(([n,l])=>`<button class="${(ui.adays||30)===n?'active':''}" data-adays="${n}">${l}</button>`).join('')}</div></div>
  <div class="toolbar"><div class="dtr-tags"><button class="dtr-tag${ak?'':' on'}" data-akind="">Tutte</button>${agKinds.map(k=>`<button class="dtr-tag${ak===k?' on':''}" data-akind="${k}">${AG_KIND[k][0]}</button>`).join('')}</div></div>
  ${ag.slice(0,(ui.amore||60)).map(agendaRow).join('')||empty('calendar','Nessuna scadenza nel periodo','Allarga il periodo o cambia tipo.')}
  ${ag.length>(ui.amore||60)?`<div class="btn-row" style="justify-content:center;margin-top:12px"><button class="btn ghost" data-amore>Mostra altre ${fmt(Math.min(60,ag.length-(ui.amore||60)))}</button></div>`:''}
 </div>
 <div class="layout-2"><div class="card reveal" style="--i:5"><div class="card-head"><div><h2>Licenze Zucchetti per mese</h2><p>Prossimi 120 giorni.</p></div></div>
  ${[...months.values()].map(m=>`<div class="month"><div class="month-head"><h3>${DF2.format(m.d)}</h3><span>${m.items.length} licenze</span></div>${m.items.map(r=>{const d=parseDate(r.expiry),n=dayDiff(d);return`<div class="list-row" data-client="${esc(r.clientCode)}"><span class="date-chip ${n<=30?'due':''}"><b>${d.getDate()}</b><span>${DF5.format(d)}</span></span><div class="grow"><strong>${esc(r.client)}</strong><small>${esc(famLabel(famOfScope(r.scope)))} · <span class="mono">${esc(r.serial)}</span></small></div>${licRenewed(r)?'<span class="badge ok renewed">Rinnovata</span>':`<span class="in-days" style="color:var(--${n<=30?'due':'text-3'})">${rel(n)}</span>`}${licRenewBtn(r)}${D.byCode.get(r.clientCode)?.meta.nextContact?`<span class="badge acc" data-tip="Promemoria già fissato">${icon('check')}</span>`:''}</div>`;}).join('')}</div>`).join('')||empty('calendar','Nessuna scadenza in arrivo','Nei prossimi 120 giorni non scade nessuna licenza.')}
 </div>
 <div class="card reveal" style="--i:6"><h2>Scadute da gestire</h2><p class="sub">Licenze con data passata ancora negli elenchi correnti.</p><div style="margin-top:10px">${D.overdue.sort((a,b)=>parseDate(b.expiry)-parseDate(a.expiry)).slice(0,30).map(r=>`<div class="list-row" data-client="${esc(r.clientCode)}"><div class="grow"><strong>${esc(r.client)}</strong><small><span class="mono">${esc(r.serial)}</span> · ${esc(r.expiry)}</small></div><span class="in-days" style="color:var(--bad)">${rel(days(r.expiry))}</span>${licRenewBtn(r)}</div>`).join('')||'<p class="sub">Nessuna. Ottimo lavoro!</p>'}</div></div></div>
 ${D.machines.length?`<div class="card reveal" style="--i:7;margin-top:16px"><div class="card-head"><div><h2>Verificazioni e abbonamenti</h2><p>Registratori con verificazione periodica (stimata) o abbonamento in scadenza entro 60 giorni.</p></div><button class="btn ghost sm" data-go="machines">Parco macchine ${icon('arrow')}</button></div>
  ${[...D.verDue.map(m=>({m,kind:'Verificazione',d:m.verNext,n:m.verDays})),...D.contractDue.map(m=>({m,kind:'Fine abbonamento',d:m.end,n:m.endDays}))].sort((a,b)=>a.d-b.d).slice(0,40).map(x=>`<div class="list-row" ${x.m.code?`data-client="${esc(x.m.code)}"`:''}><span class="date-chip ${x.n<0?'bad':'due'}"><b>${x.d.getDate()}</b><span>${DF3.format(x.d)}</span></span><div class="grow"><strong>${esc(x.m.client)}</strong><small>${x.kind} · ${esc(x.m.model||'')} <span class="mono">${esc(x.m.serial)}</span></small></div><span class="in-days" style="color:var(--${x.n<0?'bad':'due'})">${rel(x.n)}</span></div>`).join('')||'<p class="sub">Nessuna scadenza nei prossimi 60 giorni.</p>'}</div>`:''}`;
}
function ordersPage(){
 let list=D.orders;const q=norm(ui.q);
 if(q)list=list.filter(o=>norm([o.client,o.number,o.serial,o.status].join(' ')).includes(q));
 if(ui.ostatus)list=list.filter(o=>o.status===ui.ostatus);if(ui.ofam)list=list.filter(o=>o.family===ui.ofam);
 if(!['date','client','num'].includes(ui.sort.key))ui.sort={key:'date',dir:-1};
 list=sortBy(list,{date:o=>o.d?+o.d:0,client:o=>o.client,num:o=>o.number});
 const size=16,rows=pageOf(list,size);const statuses=[...new Set(D.orders.map(o=>o.status))].sort();
 const reg=D.orders.filter(o=>/^registrato$/i.test(o.status)).length;const open=D.orders.filter(o=>['info','due'].includes(orderCls(o.status))).length;
 return`${hero('Gli <span class="grad">ordini</span> web.','Lo storico degli ordini inviati a Zucchetti per ogni famiglia di prodotti, con lo stato aggiornato.','')}
 ${explain('orders','Gli ordini sono letti dal portale <strong>in sola lettura</strong>: da qui non si crea né si modifica nulla. Ogni ordine viene collegato al cliente tramite la matricola; clicca una riga per aprire la scheda del cliente.')}
 <div class="kpis" style="grid-template-columns:repeat(3,1fr)">${kpi('Ordini in archivio',D.orders.length,'Tutte le famiglie di prodotto','bag','','orders',2)}${kpi('Registrati',reg,'Completati sul portale','check','t-ok','orders',3)}${kpi('In lavorazione',open,'In approvazione, da inviare o con allegati mancanti','clock','t-due','orders',4)}</div>
 <div class="card reveal" style="--i:5"><div class="card-head"><div><h2>Elenco ordini<span class="count">${fmt(list.length)}</span></h2><p>Dal più recente.</p></div></div>
 <div class="toolbar">${search('Cerca cliente, numero ordine, matricola…')}<select class="select" id="f-ostatus"><option value="">Tutti gli stati</option>${statuses.map(s=>`<option${s===ui.ostatus?' selected':''}>${esc(s)}</option>`).join('')}</select>${sel('f-ofam','Tutti i prodotti',Object.keys(FAMILY),ui.ofam)}</div>
 <div class="table-wrap"><table class="t-orders"><thead><tr>${th('Data','date')}${th('N. ordine','num')}<th>Matricola</th>${th('Cliente','client')}<th>Prodotto</th><th>Stato</th><th></th></tr></thead><tbody>
 ${rows.map((o,i)=>`<tr class="reveal" style="--i:${i}" ${o.clientCode?`data-client="${esc(o.clientCode)}" tabindex="0"`:''}><td>${o.d?dShort(o.d):esc(o.date)}<br><small class="muted">${o.d?rel(dayDiff(o.d)):''}</small></td><td class="mono">${hl(o.number,ui.q)}</td><td class="mono">${hl(o.serial||'—',ui.q)}</td><td><div class="who">${markEl(o.client,o.clientCode||o.client)}<div><strong>${hl(o.client,ui.q)}</strong></div></div></td><td><span class="chip">${esc(famLabel(o.family))}</span></td><td><span class="badge ${orderCls(o.status)}">${esc(o.status)}</span></td><td>${o.clientCode?`<span class="row-go">${icon('chevron')}</span>`:''}</td></tr>`).join('')||`<tr><td colspan="7">${empty('bag','Nessun ordine',D.orders.length?'Modifica la ricerca o i filtri.':'Avvia una sincronizzazione per importare gli ordini dal portale.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,size)}</div>`;
}
function tasksPage(){
 const all=D.clients.filter(c=>c.meta.nextContact||c.meta.followupState);
 let list=all;if(ui.taskTab==='open')list=all.filter(c=>c.meta.followupState!=='Completato');if(ui.taskTab==='late')list=all.filter(c=>c.meta.followupState!=='Completato'&&c.meta.nextContact&&days(c.meta.nextContact)<=0);if(ui.taskTab==='done')list=all.filter(c=>c.meta.followupState==='Completato');
 list=list.slice().sort((a,b)=>(a.meta.nextContact||'9').localeCompare(b.meta.nextContact||'9'));
 const suggest=D.clients.filter(c=>c.status==='In scadenza'&&!c.meta.nextContact).slice(0,8);
 return`${hero('Le tue <span class="grad">attività</span>.','Promemoria e richiami ai clienti, così nessun rinnovo resta indietro.','')}
 ${explain('tasks','Apri un cliente e nella sezione <strong>Promemoria</strong> imposta stato e data del prossimo contatto: comparirà qui e nella Panoramica. A destra trovi i <strong>clienti suggeriti</strong>: hanno licenze in scadenza e nessun promemoria.')}
 <div class="layout-2"><div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Promemoria<span class="count">${fmt(list.length)}</span></h2></div><div class="seg">${[['open','Da fare'],['late','Oggi e in ritardo'],['done','Completati'],['all','Tutti']].map(([k,l])=>`<button class="${ui.taskTab===k?'active':''}" data-ttab="${k}">${l}</button>`).join('')}</div></div>
  ${list.map(c=>{const n=c.meta.nextContact?days(c.meta.nextContact):null;return`<div class="list-row" data-client="${esc(c.code)}">${markEl(c.name,c.code)}<div class="grow"><strong>${esc(c.name)}</strong><small>${c.meta.nextContact?dShort(parseDate(c.meta.nextContact)):'Senza data'}${c.note?' · '+esc(c.note.slice(0,80)):''}</small></div><span class="badge ${c.meta.followupState==='Completato'?'ok':c.meta.followupState==='In attesa'?'info':'due'}">${esc(c.meta.followupState||'Da contattare')}</span><span class="in-days" style="min-width:90px;text-align:right;color:var(--${n!==null&&n<0?'bad':n===0?'due':'text-3'})">${rel(n)}</span></div>`;}).join('')||empty('task','Nessun promemoria qui','Apri un cliente e imposta un prossimo contatto.')}
 </div><div class="card reveal" style="--i:3"><h2>Suggeriti</h2><p class="sub">Licenze in scadenza, nessun promemoria.</p><div style="margin-top:10px">${suggest.map(c=>`<div class="list-row" data-client="${esc(c.code)}">${markEl(c.name,c.code)}<div class="grow"><strong>${esc(c.name)}</strong><small>Scade ${rel(dayDiff(c.next))}</small></div>${icon('plus')}</div>`).join('')||'<p class="sub">Tutto pianificato.</p>'}</div></div></div>`;
}
function syncPage(){
 const famRows=Object.keys(FAMILY).map(f=>{const a=state.sources.find(s=>s.scope===f+'-attive'),s=state.sources.find(s=>s.scope===f+'-scadute');const cl=(state.clients||[]).filter(c=>c.families.includes(f)).length,or=(state.orders||[]).filter(o=>o.family===f).length;return`<div class="source-row"><div><strong>${esc(famLabel(f))}</strong><small>${plur(a?.rowCount||0,'attiva','attive')} · ${fmt(s?.rowCount||0)} scadute · ${fmt(cl)} anagrafiche · ${plur(or,'ordine','ordini')}</small></div>${a?.complete?'<span class="badge ok">Letto</span>':'<span class="badge">Da leggere</span>'}</div>`;}).join('');
 const step1=state.portalConnected,step2=(state.clients||[]).length>0;
 return`${hero('<span class="grad">Sincronizzazione</span> Zucchetti.','Come arrivano i dati, cosa è stato letto e la cronologia di ogni aggiornamento.','')}
 ${explain('sync','La sincronizzazione apre MY Zucchetti in una finestra protetta: <strong>accedi tu</strong> con le tue credenziali (l’app non le salva). Poi l’app legge licenze, anagrafiche e ordini di tutte le famiglie, <strong>solo in lettura</strong>, e si ripete da sola ogni 6 ore finché la finestra resta aperta.')}
 ${typeof accessCard==='function'?accessCard(false):''}
 <div class="layout-half"><div class="stack">
  <div class="card reveal" style="--i:2"><div class="conn"><span class="zlogo">Z</span><div><h2>MY Zucchetti Group</h2><p class="sub">${state.syncRunning?`<span class="badge acc"><span class="spinner"></span><span id="sync-live">${esc(state.syncProgress||'Sincronizzazione in corso')}</span></span>`:state.portalConnected?'<span class="badge ok">Sessione attiva</span>':'<span class="badge">Non collegato</span>'}</p></div></div>
   ${WEB&&!hostInfo?.syncStation?`<ol class="steps"><li class="done">Questo dispositivo è collegato all’<strong>archivio condiviso</strong> e riceve ogni aggiornamento in tempo reale.</li><li class="${step2?'done':''}">La <strong>postazione dell’ufficio</strong> legge MY Zucchetti e invia i dati al server, in automatico ogni 6 ore.</li><li>Se servono dati più recenti, chiedi a chi gestisce la postazione di premere <strong>Sincronizza tutto</strong>.</li></ol>`:
   `<ol class="steps"><li class="${step1?'done':''}">Apri il collegamento e <strong>accedi</strong> con le credenziali Zucchetti.</li><li class="${step2?'done':''}">Premi <strong>Sincronizza tutto</strong>: in pochi minuti vengono letti 24 elenchi${WEB?' e inviati al server condiviso':''}.</li><li>Lascia aperta la finestra: l’archivio si aggiorna da solo ogni 6 ore.</li></ol>
   <div class="btn-row" style="margin-top:18px"><button class="btn" data-action="portal">${icon('external')}Apri collegamento</button><button class="btn accent" data-action="syncPortal"${state.syncRunning?' disabled':''}>${icon('sync')}Sincronizza tutto</button></div>`}</div>
  <div class="card reveal" style="--i:3"><h2>Copertura del portale</h2><p class="sub">Per ogni famiglia: licenze, anagrafiche e ordini letti.</p><div style="margin-top:8px">${famRows}</div></div>
 </div><div class="stack">
  <div class="card reveal" style="--i:4"><div class="card-head"><div><h2>Cronologia</h2><p>Ogni lettura e importazione lascia una traccia.</p></div><button class="btn ghost sm" data-action="scan">${icon('sync')}Controlla cartella</button></div>${state.events.slice(0,18).map(e=>`<div class="event"><span class="dot ${e.kind==='error'?'err':''}">${icon(e.kind==='error'?'alert':e.kind==='backup'?'shield':'check')}</span><div><div>${esc(e.message)}</div><small>${esc(dTime(e.time))}</small></div></div>`).join('')||'<p class="sub">Nessuna attività registrata.</p>'}</div>
  <div class="card reveal need-export" style="--i:5"><h2>Importazione da file</h2>${WEB?`<p class="sub">Carica un’esportazione Zucchetti (JSON, CSV o XLSX): viene aggiunta all’archivio condiviso. Gli stessi file non vengono importati due volte.</p><div class="btn-row" style="margin-top:12px"><button class="btn sm" data-action="import">${icon('upload')}Importa un file</button></div>`:`<p class="sub">Ogni minuto, con l’app aperta, vengono importati i nuovi file JSON, CSV o XLSX esportati da Zucchetti in questa cartella.</p><div class="path">${esc(state.sourceFolder)}</div><div class="btn-row"><button class="btn sm" data-action="folder">${icon('folder')}Scegli cartella</button><button class="btn sm" data-action="import">${icon('upload')}Importa un file</button></div>`}</div>
 </div></div>`;
}
function settingsPage(){const t=store.get('theme','auto');
 return`${hero('<span class="grad">Impostazioni</span>.','Aspetto, archivio, backup ed esportazioni.','')}
 <div class="layout-half"><div class="stack">
  <div class="card reveal" style="--i:1"><h2>Aspetto</h2><p class="sub">Scegli il tema. “Automatico” segue Windows.</p><div class="theme-pick" style="margin-top:14px">${[['light','Chiaro','l'],['dark','Scuro','d'],['auto','Automatico','a']].map(([k,l,c])=>`<button class="${t===k?'active':''}" data-theme-set="${k}"><i class="${c}"></i>${l}</button>`).join('')}</div>
   <div class="btn-row" style="margin-top:14px"><button class="btn sm ghost" data-action-local="resetTips">${icon('sparkle')}Mostra di nuovo le spiegazioni</button></div></div>
  ${host?`<div class="card reveal" style="--i:2"><h2>Avvio automatico</h2><p class="sub">Fai partire Studio da solo quando accendi il PC, ridotto a icona: sulla <strong>postazione di sincronizzazione</strong> tiene Zucchetti, DTR-Service e Ital Retail sempre aggiornati anche dopo un riavvio.</p>
   <div class="seg" style="margin-top:14px"><button class="${hostInfo?.autostart?'active':''}" data-autostart="1">Avvia con Windows</button><button class="${hostInfo?.autostart?'':'active'}" data-autostart="0">Non avviare</button></div></div>`:''}
  <div class="card reveal" style="--i:2"><h2>Prestazioni</h2><p class="sub">“Automatico” misura la velocità del PC all’avvio e sceglie da solo. La modalità leggera toglie sfocature ed effetti di sfondo per la massima fluidità anche sui computer meno potenti. Ora attiva: <strong>${document.documentElement.dataset.perf==='lite'?'leggera':'effetti completi'}</strong>.</p>
   <div class="seg" style="margin-top:14px">${[['auto','Automatico'],['lite','Massima fluidità'],['full','Effetti completi']].map(([k,l])=>`<button class="${store.get('perf','auto')===k?'active':''}" data-perf-set="${k}">${l}</button>`).join('')}</div></div>
  <div class="card reveal" style="--i:2">${WEB?`<h2>Archivio condiviso</h2><p class="sub">Server <strong>${esc(location.host)}</strong>: tutti vedono gli stessi dati, aggiornati in tempo reale. ${offline?'<span class="badge due">offline · ultimi dati salvati</span>':'<span class="badge ok">collegato</span>'}</p>`:`<h2>Database locale</h2><p class="sub">SQLite sul tuo computer: clienti, licenze, ordini, note e promemoria. Niente viene pubblicato online.</p><div class="path">${esc(state.dataFolder)}</div>`}
   <div class="stat-line"><span>Clienti</span><strong>${fmt(D.clients.length)}</strong></div><div class="stat-line"><span>Schede anagrafiche</span><strong>${fmt((state.clients||[]).length)}</strong></div><div class="stat-line"><span>Licenze</span><strong>${fmt(state.records.length)}</strong></div><div class="stat-line"><span>Ordini</span><strong>${fmt((state.orders||[]).length)}</strong></div><div class="stat-line"><span>Ultima lettura</span><strong>${esc(lastCapture())}</strong></div>
   <div class="btn-row" style="margin-top:14px"><button class="btn sm" data-action="dataFolder">${icon('folder')}Apri cartella dati</button></div></div>
 </div><div class="stack">
  ${accountCard(3)}${lanCard(3)}${typeof updateCard==='function'?updateCard(3):''}${serverCard(3)}${mailCard(3)}${weeklyCard(3)}${backupCard(3)}${mastroCard(3)}${typeof brandsCard==='function'?brandsCard(3):''}
  <div class="card reveal need-export" style="--i:3"><h2>Backup ed esportazioni</h2><p class="sub">Crea una copia completa prima di spostare o archiviare i dati. Il backup contiene dati dei clienti: conservalo in un posto sicuro.</p><div class="btn-row" style="margin-top:14px"><button class="btn accent sm" data-action="backup">${icon('shield')}Backup database</button><button class="btn sm" data-action="exportClients">${icon('users')}Anagrafica CSV</button><button class="btn sm" data-action="export">${icon('key')}Licenze CSV</button><button class="btn sm" data-action="calendar">${icon('calendar')}Scadenze .ics</button></div></div>
  <div class="card reveal" style="--i:4"><h2>Scorciatoie</h2><div style="margin-top:10px"><div class="stat-line"><span>Ricerca rapida ovunque</span><span><kbd>Ctrl</kbd> <kbd>K</kbd></span></div><div class="stat-line"><span>Vai alle sezioni</span><span><kbd>Alt</kbd> <kbd>1</kbd>…<kbd>8</kbd></span></div><div class="stat-line"><span>Chiudi scheda o ricerca</span><kbd>Esc</kbd></div></div></div>
  <div class="card reveal" style="--i:5"><div style="display:flex;gap:16px;align-items:center"><img src="assets/tcs-app@2x.png" alt="" class="auth-logo" style="margin:0"><div><h2>Top Cash Studio</h2><p class="sub">Top Cash Service S.r.l. · Versione ${esc(UPD?.host?.current||UPD?.serverVersion||'2.7')} · Windows · .NET 10 · WebView2 · SQLite</p></div></div></div>
 </div></div>`;}

// ───────────────────────── gestionale: interventions, machines, Mastro import
const ISTATES=['Aperto','Pianificato','In corso','In attesa','Chiuso'],PRIOS=['Bassa','Normale','Alta','Urgente'];
const istCls=s=>({'Aperto':'info','Pianificato':'acc','In corso':'due','In attesa':'','Chiuso':'ok'}[s]??'');
// priority colours, the same as the Android app: Urgente red, Alta yellow, Bassa green, Normale neutral
const prioCls=p=>({'Urgente':'bad','Alta':'due','Bassa':'ok'}[p]??'');
function interventionsPage(){
 let list=D.interventions;const q=norm(ui.q);
 if(ui.itab==='open')list=list.filter(i=>i.status!=='Chiuso');else if(ui.itab==='mine')list=list.filter(i=>i.status!=='Chiuso'&&mineInt(i));else if(ui.itab==='today')list=list.filter(i=>i.status!=='Chiuso'&&i.d&&dayDiff(i.d)<=0);else if(ui.itab==='closed')list=list.filter(i=>i.status==='Chiuso'&&i.source==='app');else if(ui.itab==='mastro')list=list.filter(i=>i.source==='mastro');
 if(q)list=list.filter(i=>norm([i.client,i.title,i.work,i.technician,i.machine].join(' ')).includes(q));
 if(ui.tech)list=list.filter(i=>i.technician===ui.tech);
 if(!['date','client','prio'].includes(ui.sort.key))ui.sort={key:'date',dir:-1};
 list=sortBy(list,{date:i=>i.d?+i.d:0,client:i=>i.client,prio:i=>PRIOS.indexOf(i.priority)});
 const rows=pageOf(list,16);const techs=[...new Set(D.interventions.map(i=>i.technician).filter(Boolean))].sort();
 const urgent=D.openInterventions.filter(i=>i.priority==='Urgente'||i.priority==='Alta').length;const today_=D.openInterventions.filter(i=>i.d&&dayDiff(i.d)===0).length;
 return`${hero('Gli <span class="grad">interventi</span>.','Assistenza tecnica: apri, assegna e chiudi gli interventi presso i clienti. Lo storico di Mastro resta consultabile.',`<button class="btn accent" data-action-local="newIntervention">${icon('plus')}Nuovo intervento</button>`)}
 ${explain('interventions','Crea un intervento con <strong>Nuovo intervento</strong> o dalla scheda di un cliente. Assegna un tecnico, una priorità e aggiornane lo stato fino a <span class="badge ok">Chiuso</span>. ' +(WEB?'Ogni modifica è visibile subito a tutto il team.':'')+' Gli interventi importati da Mastro sono in sola lettura.')}
 <div class="kpis" style="grid-template-columns:repeat(3,1fr)">${kpi('Aperti',D.openInterventions.length,'Da completare','tool','t-info','interventions',2)}${kpi('Urgenti o alta priorità',urgent,'Da fare per primi','alert','t-bad','interventions',3)}${kpi('Pianificati per oggi',today_,DF4.format(new Date()),'calendar','t-due','interventions',4)}</div>
 <div class="card reveal" style="--i:5"><div class="card-head"><div><h2>Elenco interventi<span class="count">${fmt(list.length)}</span></h2></div><div class="seg">${[['open','Da fare'],...(WEB&&me?[['mine','I miei']]:[]),['today','Oggi e in ritardo'],['closed','Chiusi'],['mastro','Storico Mastro'],['all','Tutti']].map(([k,l])=>`<button class="${ui.itab===k?'active':''}" data-itab="${k}">${l}</button>`).join('')}</div></div>
 <div class="toolbar">${search('Cerca cliente, lavoro, tecnico…')}<select class="select" id="f-tech"><option value="">Tutti i tecnici</option>${techs.map(t=>`<option${t===ui.tech?' selected':''}>${esc(t)}</option>`).join('')}</select></div>
 <div class="table-wrap"><table class="t-int"><thead><tr>${th('Data','date')}${th('Cliente','client')}<th>Intervento</th><th>Tecnico</th>${th('Priorità','prio')}<th>Stato</th><th></th></tr></thead><tbody>
 ${rows.map((i,k)=>`<tr class="reveal" style="--i:${k}" data-int="${esc(i.id)}" tabindex="0"><td>${i.d?dShort(i.d):'<span class="muted">—</span>'}<br><small class="muted">${i.d?rel(dayDiff(i.d)):''}</small></td><td><div class="who">${markEl(i.client,i.code||i.client)}<div><strong>${hl(i.client,ui.q)}</strong><small>${i.source==='mastro'?'Storico Mastro':esc(i.createdBy?'da '+i.createdBy:'')}</small></div></div></td><td><strong style="font-weight:560">${hl(i.title,ui.q)}</strong>${i.machine?`<br><small class="muted">${esc(machineLabel(i))}</small>`:''}</td><td>${i.technician?esc(i.technician):'<span class="muted">—</span>'}</td><td><span class="badge ${prioCls(i.priority)}">${esc(i.priority)}</span></td><td><span class="badge ${istCls(i.status)}">${i.signedAt?'Firmato':esc(i.status)}</span></td><td class="t-acts">${i.source==='app'?(i.signedAt?`<button class="icon-btn sm" data-iq="print" data-id="${esc(i.id)}" data-tip="Rapportino firmato" aria-label="Rapportino">${icon('print')}</button>`:`<button class="icon-btn sm" data-iq="sign" data-id="${esc(i.id)}" data-tip="Firma del cliente" aria-label="Firma">${icon('edit')}</button><button class="icon-btn sm" data-iq="print" data-id="${esc(i.id)}" data-tip="Stampa da portare dal cliente" aria-label="Stampa">${icon('print')}</button>${i.status==='Chiuso'?'':`<button class="icon-btn sm" data-iq="close" data-id="${esc(i.id)}" data-tip="Chiudi" aria-label="Chiudi">${icon('check')}</button><button class="icon-btn sm" data-iq="later" data-id="${esc(i.id)}" data-tip="Posticipa" aria-label="Posticipa">${icon('clock')}</button>`}`):''}<span class="row-go">${icon('chevron')}</span></td></tr>`).join('')||`<tr><td colspan="7">${empty('tool','Nessun intervento',ui.itab==='open'?'Non ci sono interventi da fare. Ottimo!':'Modifica i filtri o crea un nuovo intervento.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}
function machineLabel(i){const c=i.c;const m=c?.machines.find(m=>m.id===i.machine||m.id==='m'+i.machine||m.serial===i.machine);return m?`${m.model||'Macchina'} · ${m.serial}`:(i.machine?'Macchina '+i.machine:'');}
// Intervention editor (modal form). History from Mastro opens read-only.
function openIntervention(id,clientCode){remember('i',id);
 const it=id?D.interventions.find(i=>i.id===id):null;const signed=!!it?.signedAt;const ro=it?.source==='mastro'||signed;
 const c=it?.c||D.byCode.get(clientCode)||null;
 const n=new Date(),todayIso=`${n.getFullYear()}-${String(n.getMonth()+1).padStart(2,'0')}-${String(n.getDate()).padStart(2,'0')}`; // local date, not UTC
 const v=it||{id:'',clientCode:c?.code||'',date:todayIso,title:'',work:'',status:'Aperto',priority:'Normale',technician:WEB&&me?me.name:'',machine:'',total:''};
 const m=document.createElement('div');m.className='modal';
 const opts=(arr,sel)=>arr.map(x=>`<option${x===sel?' selected':''}>${esc(x)}</option>`).join('');
 const machOpts=cc=>{const cl=D.byCode.get(cc);return`<option value="">Nessuna macchina</option>`+(cl?.machines||[]).map(x=>`<option value="${esc(x.id)}"${x.id===v.machine?' selected':''}>${esc((x.model||'Macchina')+' · '+x.serial)}</option>`).join('');};
 m.innerHTML=`<form class="modal-box wide" id="int-form" aria-label="Intervento">
  <h3>${it?.source==='mastro'?'Intervento dallo storico Mastro':signed?'Rapportino n. '+esc(it.reportNo):it?'Modifica intervento':'Nuovo intervento'}</h3>
  ${signed?`<div class="sign-banner">${icon('check')}<div><strong>Firmato da ${esc(it.signer)}</strong><small>${dTime(it.signedAt)} · rapportino n. ${esc(it.reportNo)} · non più modificabile</small></div></div>`:''}
  ${signed&&it.sentAt?`<div class="sign-sent">${icon('mail')}<span>PDF inviato a <strong>${esc(it.sentTo)}</strong> il ${dTime(it.sentAt)}</span></div>`:''}
  <label class="field">Cliente<input class="input" id="int-client" list="int-clients" value="${esc(c?c.name:'')}" placeholder="Scrivi per cercare un cliente…" ${ro?'disabled':''} required autocomplete="off"></label>
  <datalist id="int-clients">${D.clients.slice(0,3000).map(x=>`<option value="${esc(x.name)}">${esc([x.city,x.code.replace(/^0+/,'')].filter(Boolean).join(' · '))}</option>`).join('')}</datalist>
  <div class="form-row"><label class="field">Data<input class="input" type="date" name="date" value="${esc(v.date||'')}" ${ro?'disabled':''}></label><label class="field">Assegna a<select class="select" name="technician" ${ro?'disabled':''}>${techOptions(v.technician||'')}</select></label></div>
  <label class="field">Titolo<input class="input" name="title" value="${esc(v.title)}" maxlength="200" required placeholder="es. Sostituzione stampante, verificazione periodica…" ${ro?'disabled':''}></label>
  <div class="form-row"><label class="field">Stato<select class="select" name="status" ${ro?'disabled':''}>${opts(ISTATES,v.status)}</select></label><div class="field">Priorità<div class="prio-seg" role="radiogroup" aria-label="Priorità">${PRIOS.map(p=>`<label class="p-${prioCls(p)||'n'}"><input type="radio" name="priority" value="${p}" ${(PRIOS.includes(v.priority)?v.priority:'Normale')===p?'checked':''} ${ro?'disabled':''}><span>${p}</span></label>`).join('')}</div></div></div>
  <label class="field">Macchina<select class="select" name="machine" id="int-machine" ${ro?'disabled':''}>${machOpts(v.clientCode)}</select></label>
  ${typeof partsSection==='function'?partsSection(it?.parts||[],ro,v.technician):''}
  <label class="field">Lavoro svolto / note<textarea class="textarea" name="work" maxlength="20000" ${ro?'disabled':''} placeholder="Descrizione del problema e del lavoro eseguito…">${esc(v.work||'')}</textarea></label>
  ${photoSection(it)}
  ${it?`<p class="muted" style="font-size:12px">${it.source==='mastro'?'Importato da MastroSQL'+(it.total&&it.total!=='0'?' · totale documento '+esc(it.total)+' €':''):`Creato ${it.created?dTime(it.created):''} ${it.createdBy?'da '+esc(it.createdBy):''}${it.updated&&it.updated!==it.created?` · modificato ${dTime(it.updated)} ${it.updatedBy?'da '+esc(it.updatedBy):''}`:''}`}</p>`:''}
  <div class="btn-row">${it&&it.source==='app'?(signed?`<button type="button" class="btn ghost need-export" data-m="unsign">Annulla firma</button><button type="button" class="btn" data-m="pdf">${icon('download')}PDF</button><button type="button" class="btn" data-m="print">${icon('print')}Stampa</button>${mailOn()?`<button type="button" class="btn accent" data-m="mail">${icon('mail')}Invia al cliente</button>`:''}`:`<button type="button" class="btn" data-m="print">${icon('print')}Stampa</button><button type="button" class="btn" data-m="later">${icon('clock')}Posticipa</button><button type="button" class="btn" data-m="sign">${icon('check')}Firma e rapportino</button>`):''}${it&&!ro?`<button type="button" class="btn ghost need-export" data-m="del" style="margin-right:auto;color:var(--bad)">Elimina</button>`:''}${c?`<button type="button" class="btn ghost" data-m="client">Apri cliente</button>`:''}<button type="button" class="btn ghost" data-m="0">${ro?'Chiudi':'Annulla'}</button>${ro?'':`<button class="btn accent" type="submit">${icon('check')}Salva</button>`}</div></form>`;
 document.body.append(m);hideTip();bindPhotos(m,it);
 const form=m.querySelector('form');const clientInput=m.querySelector('#int-client');
 const resolve=()=>{const n=clientInput.value.trim();return D.clients.find(x=>x.name===n)||D.clients.find(x=>norm(x.name)===norm(n))||null;};
 clientInput.addEventListener('change',()=>{const cl=resolve();m.querySelector('#int-machine').innerHTML=machOpts(cl?.code);});
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();close();}};document.addEventListener('keydown',key,true);
 m.addEventListener('click',async e=>{const b=e.target.closest('[data-m]');if(e.target===m)return close();if(!b)return;
  if(b.dataset.m==='0')close();
  if(b.dataset.m==='client'){close();openClient(c.code);}
  if(b.dataset.m==='pdf'){reportPdf(it.id);}
  if(b.dataset.m==='print'){printReport(it.id);}
  if(b.dataset.m==='later'){close();intQuick('later',it.id);}
  if(b.dataset.m==='mail'){close();sendReportDialog({...it,c});}
  if(b.dataset.m==='unsign'){close();unsignReport(it);}
  if(b.dataset.m==='sign'){close();openSignature({...it,c:it.c});}
  if(b.dataset.m==='del'){if(await ask({title:'Eliminare l’intervento?',text:'L’operazione non si può annullare.',ok:'Elimina',danger:true})){close();if(WEB)post('/api/interventions/'+encodeURIComponent(it.id)+'/delete').then(()=>toast('Intervento eliminato')).catch(x=>toast(x.message,'alert'));else send('interventionDelete',{id:it.id});}}});
 form.addEventListener('submit',e=>{e.preventDefault();if(ro)return;const cl=resolve();if(!cl){toast('Scegli un cliente dall’elenco','alert');clientInput.focus();return;}
  const f=Object.fromEntries(new FormData(form));
  const parts=typeof readParts==='function'?readParts(form):[];const bad=parts.find(p=>!p._ok||!(p.qty>0));if(bad){toast(`Ricambio non valido: ${bad.article}`,'alert');return;}
  const item={id:v.id||'',clientCode:cl.code,clientName:cl.name,date:f.date||'',machine:f.machine||'',title:f.title.trim(),work:f.work||'',status:f.status,technician:(f.technician||'').trim(),priority:f.priority,total:v.total||'',source:'app',created:'',createdBy:'',updated:'',updatedBy:'',parts:parts.map(({_ok,...p})=>p)};
  if(WEB)post('/api/interventions',item).then(()=>{toast('Intervento salvato e condiviso');close();}).catch(x=>toast(x.message,'alert'));else{send('intervention',{item});close();}});
 setTimeout(()=>(c?m.querySelector('[name=title]'):clientInput)?.focus(),40);
}
// Mastro import: the Windows app on a PC with Mastro reads (SELECT only) and shows a preview; nothing is saved before confirmation.
let mastroState=null;
function mastroCard(i){
 if(!host)return WEB?`<div class="card reveal need-export" style="--i:${i}"><h2>Importa da MastroSQL</h2><p class="sub">L’importazione si avvia dall’app Windows Top Cash Studio su un PC dove MastroSQL è installato (collegato a questo server).</p></div>`:'';
 const s=mastroState;
 return`<div class="card reveal need-export" style="--i:${i}"><h2>Importa da MastroSQL</h2><p class="sub">Legge <strong>in sola lettura</strong> clienti, contatti, parco macchine, verificazioni, abbonamenti e storico interventi dal database di Mastro configurato su questo PC. Mastro non viene modificato. Prima di salvare vedi un’anteprima.</p>
 ${s?.stage==='reading'?`<p class="sub" style="margin-top:12px"><span class="badge acc"><span class="spinner"></span>Lettura da ${esc(s.source)}…</span></p>`:''}
 ${s?.ok===false?`<p class="sub" style="margin-top:12px;color:var(--bad)">${esc(s.error)}</p>`:''}
 <div class="btn-row" style="margin-top:14px"><button class="btn accent sm" data-action-local="mastroRead"${s?.stage==='reading'?' disabled':''}>${icon('download')}Leggi da MastroSQL</button></div>
 ${(state.mastroClients||[]).length?`<p class="sub" style="margin-top:10px">In archivio: ${plur(state.mastroClients.length,'cliente Mastro','clienti Mastro')} · ${plur((state.machines||[]).length,'macchina','macchine')} · ${plur((state.interventions||[]).filter(x=>x.source==='mastro').length,'intervento storico','interventi storici')}.</p>`:''}</div>`;
}
async function mastroPreview(v){
 const p=v.preview;
 const sample=p.sample.map(c=>`<div class="stat-line"><span>${esc(c.name)}<br><small class="muted">${esc([c.city,c.vat].filter(Boolean).join(' · '))}</small></span><span class="muted" style="text-align:right">${esc(c.phones[0]||'')}<br>${esc(c.emails[0]||'')}</span></div>`).join('');
 const ok=await ask({title:'Anteprima importazione MastroSQL',text:`Origine: <strong>${esc(v.source)}</strong>. I dati Mastro già presenti verranno sostituiti con questa lettura; interventi creati nell’app, note e promemoria restano invariati.`,
  html:`<div class="stat-line"><span>Clienti</span><strong>${fmt(p.clients)}</strong></div><div class="stat-line"><span>con telefono / con email</span><strong>${fmt(p.withPhone)} / ${fmt(p.withEmail)}</strong></div><div class="stat-line"><span>Macchine</span><strong>${fmt(p.machines)}</strong></div><div class="stat-line"><span>Interventi storici</span><strong>${fmt(p.interventions)}</strong></div><div class="stat-line"><span>Articoli di magazzino</span><strong>${fmt(p.articles||0)}</strong></div><div class="stat-line"><span>Fatture / note di credito</span><strong>${fmt(p.invoices||0)}</strong></div><div class="stat-line"><span>Scadenze (incassi e pagamenti)</span><strong>${fmt(p.dues||0)}</strong></div>
   ${p.stockRows?`<label class="check" style="margin-top:8px"><input type="checkbox" id="mastro-align"> Allinea anche le giacenze a quelle di Mastro (${fmt(p.stockRows)} righe, ${fmt(p.warehouses)} magazzini)</label><p class="muted" style="font-size:12px">Senza spunta viene importato solo il catalogo: le quantità restano quelle registrate in Top Cash Studio. L’allineamento registra delle rettifiche, quindi resta tutto tracciato.</p>`:''}
   ${p.warnings.map(w=>`<p class="muted" style="font-size:12px">⚠ ${esc(w)}</p>`).join('')}<details style="font-size:12.5px"><summary style="cursor:pointer;color:var(--accent)">Esempio dei primi clienti</summary>${sample}</details>`,ok:`Importa ${plur(p.clients,'cliente','clienti')}`});
 const align=!!lastAskAlign;
 if(!ok){toast('Importazione annullata: nessun dato modificato');return;}
 if(WEB){try{const r=await post('/api/mastro/import',{...v.batch,alignStock:align});toast(r.message);}catch(x){toast(x.message,'alert');}}else send('mastroImport',{align});
}

// ───────────────────────── team & shared-archive settings
let newToken='',newPassword='';
const genPassword=()=>{const a='ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';const r=crypto.getRandomValues(new Uint32Array(14));return[...r].map(x=>a[x%a.length]).join('').replace(/(.{4})(?=.)/g,'$1-');};
function teamPage(){
 if(!usersData){api('/api/users').then(d=>{usersData=d;if(page==='team')render(false);}).catch(e=>toast(e.message,'alert'));return hero('<span class="grad">Utenti</span> e accessi.','Caricamento…','');}
 const st=usersData.stationLastContact?new Date(usersData.stationLastContact):null;const stOk=st&&(Date.now()-st)<7*3600e3;
 return`${hero('<span class="grad">Utenti</span> e accessi.','Chi può usare Top Cash Studio, con quale ruolo, e la postazione che sincronizza Zucchetti.','')}
 ${rolesCard()}
 <div class="layout-2"><div class="card reveal" style="--i:2"><div class="card-head"><div><h2>Utenti<span class="count">${usersData.users.length}</span></h2><p>${usersData.connected} dispositivi collegati in questo momento.</p></div></div>
  <div class="table-wrap"><table><thead><tr><th>Utente</th><th>Ruolo</th><th>Ultimo accesso</th><th>Stato</th><th></th></tr></thead><tbody>
  ${usersData.users.map(u=>`<tr><td><div class="who">${markEl(u.name,u.username)}<div><strong>${esc(u.name)}${u.username===me.username?' <span class="badge acc">tu</span>':''}</strong><small>${esc(u.username)}</small></div></div></td>
   <td>${roleCell(u)}</td>
   <td>${u.lastLogin?dTime(u.lastLogin):'<span class="muted">mai</span>'}</td><td>${u.disabled?'<span class="badge bad">Disattivato</span>':'<span class="badge ok">Attivo</span>'}</td>
   <td><div class="btn-row"><button class="icon-btn sm" data-user-name="${esc(u.username)}" data-tip="Cambia nome" aria-label="Cambia nome">${icon('edit')}</button>${u.username===me.username?'<button class="btn sm ghost" data-go="settings">La tua password</button>':`<button class="btn sm ghost" data-user-del="${esc(u.username)}" data-tip="Elimina utente" aria-label="Elimina utente">${icon('close')}</button><button class="btn sm ghost" data-action-local="resetPwd" data-u="${esc(u.username)}">Nuova password</button><button class="btn sm ghost" data-action-local="toggleUser" data-u="${esc(u.username)}" data-d="${u.disabled?0:1}">${u.disabled?'Riattiva':'Disattiva'}</button>`}</div></td></tr>`).join('')}
  </tbody></table></div></div>
  <div class="stack"><form class="card reveal" style="--i:3" id="new-user"><h2>Nuovo utente</h2><p class="sub">Comunica nome utente e password al collega: potrà cambiarla dalle Impostazioni.</p>
   <div style="display:grid;gap:10px;margin-top:14px"><label class="field">Nome e cognome<input class="input" name="name" required maxlength="80"></label><label class="field">Nome utente<input class="input" name="username" required autocapitalize="none" spellcheck="false" placeholder="es. mario.rossi"></label>
   <div class="field">Ruolo<div class="role-choose" role="radiogroup" aria-label="Ruolo">${ROLE_DEF.slice().reverse().filter(([k])=>k!=='owner'||me.role==='owner').map(([k,ic,l,t])=>`<label class="role-opt r-${k}"><input type="radio" name="role" value="${k}"${k==='tecnico'?' checked':''}><span class="role-ic">${icon(ic)}</span><span class="grow"><strong>${l}</strong><small>${t}</small></span></label>`).join('')}</div></div>
   <label class="field">Password iniziale<div style="display:flex;gap:6px"><input class="input mono" name="password" required minlength="10" value="${esc(newPassword||(newPassword=genPassword()))}"><button type="button" class="btn sm" data-action-local="genPwd" data-tip="Genera un’altra password">${icon('sync')}</button><button type="button" class="btn sm" data-copy="${esc(newPassword)}" data-tip="Copia">${icon('copy')}</button></div></label>
   <button class="btn accent" type="submit">${icon('plus')}Crea utente</button></div></form>
  <div class="card reveal" style="--i:4"><h2>Postazione di sincronizzazione</h2><p class="sub">Il PC d’ufficio che legge MY Zucchetti e invia i dati al server. Usa una chiave dedicata, mai la password di un utente.</p>
   <div class="stat-line"><span>Chiave</span><strong>${usersData.syncTokenCreated?'attiva dal '+dShort(new Date(usersData.syncTokenCreated)):'non creata'}</strong></div>
   <div class="stat-line"><span>Ultimo contatto</span><strong>${st?dTime(st):'mai'} ${st?(stOk?'<span class="badge ok">ok</span>':'<span class="badge due">da controllare</span>'):''}</strong></div>
   ${newToken?`<div class="path" style="user-select:all">${esc(newToken)}</div><p class="sub">Copiala ora: non verrà più mostrata.</p><div class="btn-row"><button class="btn sm" data-copy="${esc(newToken)}">${icon('copy')}Copia chiave</button>${host?`<button class="btn sm accent" data-action-local="useToken">${icon('check')}Usa questo PC come postazione</button>`:''}</div>`:
   `<div class="btn-row" style="margin-top:12px"><button class="btn sm accent" data-action-local="genToken">${icon('key')}${usersData.syncTokenCreated?'Genera nuova chiave':'Crea chiave'}</button>${usersData.syncTokenCreated?`<button class="btn sm ghost" data-action-local="revokeToken">Revoca</button>`:''}</div>`}
   ${host&&hostInfo?.syncStation?'<p class="sub" style="margin-top:10px"><span class="badge ok">Questo PC è la postazione di sincronizzazione</span></p>':''}
  </div></div></div>
 <div style="margin-top:16px">${devicesCard()}</div><div style="margin-top:16px">${securityCard()}</div>`;
}
// the four roles at a glance: what each one sees and can do
function rolesCard(){
 const R=[['owner','star','Proprietario','Poteri illimitati: tutto quello che fa un amministratore, più la gestione degli altri amministratori e dei proprietari. Nessuno può modificarlo o bloccarlo.'],
  ['admin','shield','Amministratore','Tutto: dati, contabilità, magazzino, utenti, dispositivi, backup e impostazioni del server.'],
  ['ufficio','euro','Amministrazione','Vede e lavora su tutto, contabilità compresa, ed esporta. Non gestisce utenti e impostazioni.'],
  ['tecnico','tool','Tecnico','Vede tutto tranne la contabilità (fatture, scadenze, saldi e RiBa non arrivano proprio sul suo dispositivo). Interventi, firme e magazzino del furgone.']];
 return`<div class="roles-grid reveal" style="--i:1">${R.map(([k,ic,l,t])=>`<div class="role-card r-${k}${(me.role===k)?' on':''}"><span class="role-ic">${icon(ic)}</span><div><strong>${l}${me.role===k?' <span class="badge acc">tu</span>':''}</strong><small>${t}</small></div></div>`).join('')}</div>
  <p class="muted" style="font-size:12.5px;margin:-4px 0 14px">Se cambi ruolo, disattivi un utente o reimposti la password, le sue sessioni aperte vengono chiuse subito.</p>`;
}
function securityCard(){const list=(usersData.security||[]).slice(0,25);
 const ic=m=>/sospeso|bloccat/i.test(m)?'alert':/fuori ufficio/i.test(m)?'external':'shield';
 return`<div class="card reveal" style="--i:4"><div class="card-head"><div><h2>Accessi e sicurezza</h2><p>Accessi da fuori ufficio, tentativi sospesi e modifiche a utenti e dispositivi. Visibile solo agli amministratori.</p></div></div>
  ${list.map(e=>`<div class="event"><span class="dot ${ic(e.message)==='alert'?'err':''}">${icon(ic(e.message))}</span><div><div>${esc(e.message)}</div><small>${esc(dTime(e.time))}</small></div></div>`).join('')||'<p class="sub">Nessun evento registrato.</p>'}</div>`;
}
function devicesCard(){const list=usersData.devices||[];const pend=list.filter(d=>d.status==='pending').length;
 const badge=d=>d.status==='approved'?'<span class="badge ok">Attivo</span>':d.status==='pending'?'<span class="badge due">In attesa</span>':'<span class="badge bad">Bloccato</span>';
 return`<div class="card reveal devices-card" style="--i:3"><div class="card-head"><div><h2>Dispositivi${pend?`<span class="count due">${pend} in attesa</span>`:`<span class="count">${list.length}</span>`}</h2>
  <p>Ogni PC, telefono o browser che accede compare qui, con l’ultimo utente e l’ultimo accesso. In ufficio basta nome utente e password; da fuori ufficio un dispositivo nuovo entra solo dopo che lo <strong>autorizzi</strong> (controlla che il codice sia quello che ti comunica il collega). <strong>Blocca</strong> chiude subito l’accesso a un dispositivo perso o di un ex collaboratore.</p></div></div>
  ${list.length?`<div class="table-wrap"><table><thead><tr><th>Codice</th><th>Dispositivo</th><th>Ultimo utente</th><th>Ultimo accesso</th><th>Stato</th><th></th></tr></thead><tbody>
  ${list.map(d=>`<tr><td><span class="mono" style="font-weight:650">${esc(d.id)}</span>${d.id===usersData.thisDevice?' <span class="badge acc">questo</span>':''}</td><td><strong>${esc(d.name)}</strong><small class="muted" style="display:block">${esc(d.platform||'')}${d.remote?' · da fuori ufficio':''}</small></td>
   <td>${esc(d.user||'—')}</td><td>${d.lastSeen?dTime(d.lastSeen):'—'}<small class="muted" style="display:block">${esc(d.lastIp||'')}</small></td><td>${badge(d)}</td>
   <td><div class="btn-row">${d.status!=='approved'?`<button class="btn sm accent" data-dev="${esc(d.id)}" data-dev-act="approve">${icon('check')}${d.status==='pending'?'Autorizza':'Sblocca'}</button>`:''}${d.status!=='revoked'?`<button class="btn sm ghost" data-dev="${esc(d.id)}" data-dev-act="revoke">Blocca</button>`:''}<button class="btn sm ghost" data-dev="${esc(d.id)}" data-dev-act="delete" data-tip="Elimina dall’elenco" aria-label="Elimina">${icon('close')}</button></div></td></tr>`).join('')}
  </tbody></table></div>`:empty('shield','Nessun dispositivo','Compariranno qui al primo accesso di ogni PC, telefono o browser.')}</div>`;}
function accountCard(i){if(!WEB||!me)return'';return`<form class="card reveal" style="--i:${i}" id="pwd-form"><h2>Il tuo account</h2><p class="sub">${esc(me.name)} · ${esc(me.username)} · ${esc(me.roleLabel)}</p>
 <div style="display:grid;gap:10px;margin-top:14px"><label class="field">Password attuale<input class="input" type="password" name="current" required autocomplete="current-password"></label><label class="field">Nuova password (min. 10 caratteri)<input class="input" type="password" name="password" required minlength="10" autocomplete="new-password"></label>
 <div class="btn-row"><button class="btn sm primary" type="submit">Cambia password</button><button class="btn sm ghost" type="button" data-action-local="logout">Esci</button></div></div></form>`;}
// ── e-mail for signed reports (administrators, shared server). The password is write-only: the server never sends it back.
let mailCfg=null,backupInfo=null;
function mailCard(i){if(!WEB||!(me?.role==='admin'||me?.role==='owner'))return'';
 if(!mailCfg){api('/api/settings/mail').then(d=>{mailCfg=d;if(page==='settings')render(false);}).catch(()=>{});return`<div class="card reveal" style="--i:${i}"><h2>Email rapportini</h2><p class="sub">Caricamento…</p></div>`;}
 const m=mailCfg;const sec=[['starttls','STARTTLS (porta 587)'],['ssl','SSL/TLS (porta 465)'],['none','Nessuna (sconsigliato)']];
 return`<form class="card reveal" style="--i:${i}" id="mail-form" autocomplete="off"><h2>Email rapportini ${m.ready?'<span class="badge ok">attivo</span>':'<span class="badge">non attivo</span>'}</h2>
  <p class="sub">Dati della casella da cui partono i rapportini firmati (es. Aruba: <span class="mono">smtps.aruba.it</span>, porta 465, SSL). La password viene cifrata sul server e non è più visibile.</p>
  <div class="form-row"><label class="field">Server SMTP<input class="input mono" name="host" value="${esc(m.host)}" placeholder="smtps.aruba.it" maxlength="120"></label><label class="field" style="max-width:110px">Porta<input class="input mono" name="port" type="number" min="1" max="65535" value="${m.port||587}"></label></div>
  <label class="field">Sicurezza<select class="select" name="security">${sec.map(([k,l])=>`<option value="${k}"${m.security===k?' selected':''}>${l}</option>`).join('')}</select></label>
  <div class="form-row"><label class="field">Utente<input class="input" name="user" value="${esc(m.user)}" placeholder="assistenza@topcash.it" maxlength="200"></label><label class="field">Password<input class="input" name="password" type="password" autocomplete="new-password" placeholder="${m.hasPassword?'•••••••• (salvata: lascia vuoto per non cambiarla)':'Password della casella'}" maxlength="300"></label></div>
  <div class="form-row"><label class="field">Mittente<input class="input" name="from" value="${esc(m.from)}" placeholder="assistenza@topcash.it" maxlength="200"></label><label class="field">Nome mittente<input class="input" name="fromName" value="${esc(m.fromName)}" maxlength="80"></label></div>
  <label class="field">Copia per l’ufficio (Ccn, facoltativa)<input class="input" name="bcc" value="${esc(m.bcc)}" placeholder="ufficio@topcash.it" maxlength="400"></label>
  <label class="check"><input type="checkbox" name="enabled" ${m.enabled?'checked':''}> Attiva l’invio dei rapportini ai clienti</label>
  ${m.hasPassword?`<label class="check"><input type="checkbox" name="clearPassword"> Cancella la password salvata</label>`:''}
  <div class="btn-row" style="margin-top:12px"><button type="button" class="btn sm ghost" data-mail-test>${icon('mail')}Email di prova</button><button class="btn sm accent" type="submit">${icon('check')}Salva</button></div></form>`;}
function backupCard(i){if(!WEB||!(me?.role==='admin'||me?.role==='owner'))return'';
 if(!backupInfo){api('/api/settings/backup').then(d=>{backupInfo=d;if(page==='settings')render(false);}).catch(()=>{});return`<div class="card reveal" style="--i:${i}"><h2>Backup automatici</h2><p class="sub">Caricamento…</p></div>`;}
 const b=backupInfo,st=b.status||{};
 return`<form class="card reveal" style="--i:${i}" id="backup-form"><h2>Backup automatici ${st.error?'<span class="badge due">copia esterna non riuscita</span>':b.config.external?'<span class="badge ok">attivi</span>':''}</h2>
  <p class="sub">Ogni giorno il server salva una copia verificata del database in <span class="mono">${esc(b.local)}</span> (ultime ${b.config.keepLocal}) e la copia anche nella cartella esterna (ultime ${b.config.keepExternal}). Se il disco non è collegato riprova ogni ora.</p>
  <label class="field">Cartella esterna (disco USB o NAS)<input class="input mono" name="external" value="${esc(b.config.external)}" placeholder="H:\\TcsSqlOfflineBackups" maxlength="240"></label>
  <div class="stat-line"><span>Ultimo backup</span><strong>${st.lastLocal?dTime(st.lastLocal):'—'}</strong></div>
  <div class="stat-line"><span>Ultima copia esterna</span><strong>${st.lastExternal?dTime(st.lastExternal):'—'}</strong></div>
  ${st.error?`<p class="sub" style="color:var(--bad)">${esc(st.error)}</p>`:''}
  <div class="btn-row" style="margin-top:12px"><button type="button" class="btn sm ghost" data-backup-now>${icon('shield')}Backup adesso</button><button class="btn sm accent" type="submit">${icon('check')}Salva cartella</button></div></form>`;}
document.addEventListener('submit',async e=>{
 if(e.target.id==='mail-form'){e.preventDefault();const f=new FormData(e.target),b=e.target.querySelector('[type=submit]');b.disabled=true;
  try{mailCfg=await post('/api/settings/mail',{host:f.get('host'),port:+f.get('port')||587,security:f.get('security'),user:f.get('user'),password:f.get('password')||'',clearPassword:f.get('clearPassword')==='on',from:f.get('from'),fromName:f.get('fromName'),bcc:f.get('bcc'),enabled:f.get('enabled')==='on'});
   toast(mailCfg.ready?'Email rapportini attiva':'Impostazioni email salvate');render(false);}catch(x){toast(x.message,'alert');b.disabled=false;}}
 if(e.target.id==='backup-form'){e.preventDefault();const b=e.target.querySelector('[type=submit]');b.disabled=true;b.classList.add('busy');
  try{backupInfo=await post('/api/settings/backup',{external:new FormData(e.target).get('external')});toast(backupInfo.status.error?'Cartella salvata, ma la copia non è riuscita':'Cartella salvata e backup copiato',backupInfo.status.error?'alert':'shield');render(false);}catch(x){toast(x.message,'alert');b.disabled=false;b.classList.remove('busy');}}
});
document.addEventListener('click',async e=>{
 const t=e.target.closest('[data-mail-test]');
 if(t){const to=(me?.email||mailCfg?.from||'');const v=prompt('Indirizzo a cui inviare l’email di prova (salva prima le impostazioni):',to);if(!v)return;t.disabled=true;t.classList.add('busy');
  try{await post('/api/settings/mail/test',{to:v.trim()});toast('Email di prova inviata a '+v.trim(),'mail');}catch(x){toast(x.message,'alert');}t.disabled=false;t.classList.remove('busy');return;}
 const bn=e.target.closest('[data-backup-now]');
 if(bn){bn.disabled=true;bn.classList.add('busy');try{backupInfo=await post('/api/backup/now');toast(backupInfo.status.error?'Backup creato sul server, copia esterna non riuscita':'Backup creato e copiato',backupInfo.status.error?'alert':'shield');render(false);}catch(x){toast(x.message,'alert');bn.disabled=false;bn.classList.remove('busy');}}
});
// "Accesso dalla rete": the addresses the other PCs, tablets and phones of the office type in the browser (login required)
let LAN=null,PUBLIC='';
// the server tells logged-in apps its public address (Cloudflare tunnel): the Windows app stores it once, so outside the office it switches by itself
function learnPublic(){if(!WEB)return;api('/api/session').then(s=>{PUBLIC=s.publicUrl||'';offerPublic();}).catch(()=>{});}
function offerPublic(){if(host&&PUBLIC&&hostInfo&&!hostInfo.serverExternal&&!offerPublic.done){offerPublic.done=1;host.postMessage({action:'setExternal',url:PUBLIC,auto:true});}}
function lanCard(i){
 if(!WEB)return'';
 if(LAN===null){LAN=false;fetch('api/lan',{headers:{'X-Requested-With':'topcash'},credentials:'same-origin'}).then(r=>r.ok?r.json():null).then(j=>{LAN=j||false;if(page==='settings')render(false);}).catch(()=>{});}
 const pub=LAN&&LAN.publicUrl;
 if(!isAdmin())return pub?`<div class="card reveal" style="--i:${i}"><h2>Accesso da fuori ufficio</h2><p class="sub">Apri questo indirizzo da qualsiasi PC o telefono e accedi con il tuo utente. Sul telefono puoi scegliere «Aggiungi a schermata Home».</p>
  <div class="lan-list"><div class="lan-row"><code>${esc(pub)}</code><span class="badge acc">pubblico</span><button class="btn sm ghost" data-copy="${esc(pub)}">${icon('copy')}Copia</button></div></div></div>`:'';
 const urls=(LAN&&LAN.urls&&LAN.urls.length?LAN.urls:[location.origin]);
 return`<div class="card reveal" style="--i:${i}"><h2>Accesso dalla rete</h2><p class="sub">Dagli altri PC, tablet e telefoni dell’ufficio apri uno di questi indirizzi nel browser e accedi con il tuo utente. Sul telefono puoi poi scegliere «Aggiungi a schermata Home».</p>
  <div class="lan-list">${urls.map((u,k)=>`<div class="lan-row"><code>${esc(u)}</code>${k===0?'<span class="badge ok">consigliato</span>':''}<button class="btn sm ghost" data-copy="${esc(u)}">${icon('copy')}Copia</button></div>`).join('')}</div>
  ${pub?`<p class="sub" style="margin-top:14px">Da fuori ufficio (connessione cifrata, dispositivi autorizzati e nome utente e password):</p><div class="lan-list"><div class="lan-row"><code>${esc(pub)}</code><span class="badge acc">pubblico</span><button class="btn sm ghost" data-copy="${esc(pub)}">${icon('copy')}Copia</button></div></div>`:''}
  <p class="muted" style="font-size:12px;margin-top:8px">Perché l’indirizzo non cambi, nel router riserva l’IP a questo PC (prenotazione DHCP).${pub?'':' Fuori ufficio usa l’indirizzo esterno o la VPN.'}</p></div>`;
}
function serverCard(i){
 if(!WEB&&host)return`<div class="card reveal" style="--i:${i}"><h2>Archivio condiviso</h2><p class="sub">Collega questo PC al server Top Cash per lavorare tutti sugli stessi dati, da PC, tablet e telefono. L’archivio locale resta sul PC e puoi tornarci quando vuoi.</p>
  <div style="display:flex;gap:8px;margin-top:14px"><input class="input" id="server-url" placeholder="es. 192.168.1.20:8470 oppure topcash.azienda.it" value="${esc(hostInfo?.serverUrl||'')}"><button class="btn accent" style="flex:none" data-action-local="connect">${icon('external')}Collega</button></div></div>`;
 if(WEB&&host)return`<div class="card reveal" style="--i:${i}"><h2>Questo PC</h2><p class="sub">Collegato al server <strong>${esc(location.host)}</strong>.${hostInfo?.syncStation?' È la <strong>postazione di sincronizzazione</strong>: tienilo acceso con Top Cash Studio aperto.':''}</p>
  <div class="btn-row" style="margin-top:12px">${hostInfo?.syncStation?`<button class="btn sm" data-action="portal">${icon('external')}Apri collegamento Zucchetti</button><button class="btn sm ghost" data-action-local="unsetStation">Non usare più come postazione</button>`:''}<button class="btn sm ghost" data-action-local="disconnect">Torna all’archivio locale</button></div>
  <label class="field" style="margin-top:16px">Indirizzo esterno (fuori ufficio)<div style="display:flex;gap:8px"><input class="input" id="server-ext" placeholder="es. topcash.tuodominio.it" value="${esc((hostInfo?.serverExternal||'').replace(/^https:\/\//,'').replace(/\/$/,''))}"><button class="btn sm" data-action-local="saveExternal">${icon('check')}Salva</button></div></label>
  <p class="muted" style="font-size:12px;margin-top:6px">In ufficio usa la rete locale; fuori ufficio passa da solo all’indirizzo esterno. ${hostInfo?.activeServer&&hostInfo.activeServer===hostInfo.serverExternal?'<span class="badge acc">ora collegato da fuori</span>':''}</p></div>`;
 if(WEB)return`<div class="card reveal" style="--i:${i}"><h2>Installa l’app</h2><p class="sub">Su telefono e tablet: apri il menu del browser e scegli <strong>“Aggiungi a schermata Home”</strong> (iPhone: icona Condividi). Su PC con Edge o Chrome: icona <strong>Installa</strong> nella barra dell’indirizzo. Si apre come un’app, a schermo intero, e si aggiorna da sola.</p></div>`;
 return'';
}
async function localAction(name,el){
 const fail=e=>toast(e.message,'alert');
 switch(name){
  case'newIntervention':openIntervention(null,null);break;
  case'mastroRead':mastroState={stage:'reading',source:'MastroSQL'};render(false);host.postMessage({action:'mastroRead'});break;
  case'resetTips':Object.keys(PAGES).forEach(k=>store.set('hide.'+k,false));toast('Le spiegazioni sono di nuovo visibili');render(false);break;
  case'connect':{const url=$('#server-url').value.trim();if(!url)return toast('Scrivi l’indirizzo del server','alert');send('connectServer',{url});toast('Collegamento in corso…','sync');break;}
  case'saveExternal':host.postMessage({action:'setExternal',url:$('#server-ext').value.trim()});break;
  case'disconnect':if(await ask({title:'Tornare all’archivio locale?',text:'Questo PC userà di nuovo il suo database locale. Il server condiviso e i dati dei colleghi non vengono toccati.',ok:'Torna all’archivio locale'}))host.postMessage({action:'disconnectServer'});break;
  case'unsetStation':host.postMessage({action:'setSyncToken',token:''});break;
  case'genPwd':newPassword=genPassword();render(false);break;
  case'genToken':if(usersData.syncTokenCreated&&!await ask({title:'Generare una nuova chiave?',text:'La chiave attuale smetterà subito di funzionare: dovrai attivare di nuovo la postazione di sincronizzazione.',ok:'Genera nuova chiave',danger:true}))return;try{newToken=(await post('/api/sync/token')).token;usersData=null;render(false);}catch(e){fail(e);}break;
  case'useToken':host.postMessage({action:'setSyncToken',token:newToken});newToken='';usersData=null;render(false);break;
  case'revokeToken':if(!await ask({title:'Revocare la chiave?',text:'La postazione smetterà di inviare dati da Zucchetti finché non ne crei una nuova.',ok:'Revoca',danger:true}))return;try{await post('/api/sync/token/revoke');usersData=null;render(false);toast('Chiave revocata');}catch(e){fail(e);}break;
  case'toggleUser':if(el.dataset.d==='1'&&!await ask({title:`Disattivare ${el.dataset.u}?`,text:'Non potrà più accedere e le sue sessioni aperte vengono chiuse subito. Potrai riattivarlo quando vuoi.',ok:'Disattiva',danger:true}))return;try{await post('/api/users/'+encodeURIComponent(el.dataset.u),{disabled:el.dataset.d==='1'});usersData=null;render(false);toast(el.dataset.d==='1'?'Utente disattivato':'Utente riattivato');}catch(e){fail(e);}break;
  case'resetPwd':{const p=genPassword();if(!await ask({title:`Nuova password per ${el.dataset.u}`,text:'Comunicala al collega: le sue sessioni aperte verranno chiuse e potrà cambiarla dalle Impostazioni.',html:`<div class="path mono" style="user-select:all;font-size:15px;text-align:center">${esc(p)}</div>`,ok:'Imposta e copia'}))return;try{await post('/api/users/'+encodeURIComponent(el.dataset.u),{password:p});await copy(p);toast('Password reimpostata e copiata negli appunti');}catch(e){fail(e);}break;}
 }
}
document.addEventListener('submit',async e=>{
 if(e.target.id==='new-user'){e.preventDefault();const f=Object.fromEntries(new FormData(e.target));try{await post('/api/users',f);toast(`Utente ${f.name} creato`);newPassword='';usersData=null;render(false);}catch(x){toast(x.message,'alert');}}
 if(e.target.id==='pwd-form'){e.preventDefault();const f=Object.fromEntries(new FormData(e.target));try{const r=await post('/api/password',f);toast(r.message);setTimeout(()=>location.reload(),1500);}catch(x){toast(x.message,'alert');}}
});
// ── role picker: a styled menu instead of the system drop-down. Your own row and the owners (for non-owners) are locked
// here and on the server, which is the check that counts.
const ROLE_DEF=[['owner','star','Proprietario','Tutto, compresa la gestione degli amministratori'],['admin','shield','Amministratore','Dati, utenti, dispositivi e impostazioni'],
 ['ufficio','euro','Amministrazione','Tutto il lavoro e la contabilità, senza utenti'],['tecnico','tool','Tecnico','Clienti e interventi, senza contabilità']];
const roleOf=k=>ROLE_DEF.find(r=>r[0]===k)||ROLE_DEF[3];
function roleCell(u){const r=roleOf(u.role);
 const locked=u.username===me.username?'Il tuo ruolo lo può cambiare solo un altro amministratore':u.role==='owner'&&me.role!=='owner'?'Solo un proprietario può modificare un proprietario':'';
 if(locked)return`<span class="role-pill r-${u.role}" data-tip="${locked}">${icon(r[1])}${r[2]}<svg class="lock" aria-hidden="true"><use href="#i-key"/></svg></span>`;
 return`<button type="button" class="role-pick r-${u.role}" data-role-pick="${esc(u.username)}" aria-haspopup="listbox" aria-expanded="false">${icon(r[1])}<span>${r[2]}</span><svg class="chev" aria-hidden="true"><use href="#i-chevron"/></svg></button>`;}
function closeRoleMenu(){const m=document.querySelector('.role-menu');if(!m)return;document.querySelector('[data-role-pick][aria-expanded="true"]')?.setAttribute('aria-expanded','false');m.classList.add('out');setTimeout(()=>m.remove(),160);}
function openRoleMenu(btn,tries=0){
 closeRoleMenu();
 // a button half out of view is brought into view first (scrolling closes the menu, so it opens once the scroll is done)
 {const r=btn.getBoundingClientRect();if(tries<2&&(r.top<70||r.bottom>innerHeight-20)){openRoleMenu.quiet=Date.now()+500;btn.scrollIntoView({block:'center',behavior:'smooth'});setTimeout(()=>openRoleMenu(btn,tries+1),360);return;}}const u=usersData?.users.find(x=>x.username===btn.dataset.rolePick);if(!u)return;
 const opts=ROLE_DEF.filter(([k])=>k!=='owner'||me.role==='owner');
 const m=document.createElement('div');m.className='role-menu';m.setAttribute('role','listbox');m.setAttribute('aria-label','Ruolo di '+u.name);
 m.innerHTML=opts.map(([k,ic,l,t])=>`<button type="button" role="option" class="role-opt r-${k}${k===u.role?' on':''}" aria-selected="${k===u.role}" data-role-set="${k}"><span class="role-ic">${icon(ic)}</span><span class="grow"><strong>${l}</strong><small>${t}</small></span>${k===u.role?icon('check'):''}</button>`).join('');
 document.body.append(m);
 const r=btn.getBoundingClientRect(),h=m.offsetHeight,w=m.offsetWidth;const below=innerHeight-r.bottom>h+12;
 m.style.left=Math.max(8,Math.min(r.left,innerWidth-w-8))+'px';m.style.top=(below?r.bottom+6:Math.max(8,r.top-h-6))+'px';m.classList.toggle('up',!below);
 btn.setAttribute('aria-expanded','true');(m.querySelector('.on')||m.firstElementChild)?.focus({preventScroll:true});
 m.addEventListener('keydown',e=>{const o=[...m.querySelectorAll('.role-opt')];const i=o.indexOf(document.activeElement);
  if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();o[(i+(e.key==='ArrowDown'?1:o.length-1))%o.length].focus();}
  else if(e.key==='Escape'||e.key==='Tab'){e.preventDefault();closeRoleMenu();btn.focus();}});
 m.addEventListener('click',async e=>{const b=e.target.closest('[data-role-set]');if(!b)return;const k=b.dataset.roleSet;closeRoleMenu();if(k===u.role)return;
  const to=roleOf(k),from=roleOf(u.role);
  // more power always asks first; less power is immediate (and ends that person's sessions on the server)
  if((k==='owner'||k==='admin')&&!(await ask({title:`${u.name} diventa ${to[2]}?`,text:k==='owner'?'Avrà poteri illimitati, compresa la gestione degli altri amministratori. Solo un proprietario potrà toglierglieli.':'Potrà gestire utenti, dispositivi e impostazioni del server.',ok:`Sì, rendi ${to[2]}`})))return;
  btn.classList.add('loading');
  try{await post('/api/users/'+encodeURIComponent(u.username),{role:k});toast(`${u.name}: ${from[2]} → ${to[2]}. Le sue sessioni aperte sono state chiuse.`,'check');}
  catch(x){toast(x.message,'alert');}
  usersData=null;render(false);});
}
document.addEventListener('click',e=>{const b=e.target.closest?.('[data-role-pick]');if(b){e.preventDefault();b.getAttribute('aria-expanded')==='true'?closeRoleMenu():openRoleMenu(b);return;}
 if(!e.target.closest?.('.role-menu'))closeRoleMenu();});
addEventListener('resize',closeRoleMenu);document.addEventListener('scroll',()=>{if(Date.now()>(openRoleMenu.quiet||0))closeRoleMenu();},true);

// ───────────────────────── drawer (client card)
function openClient(code,keep){const c=D.byCode.get(code);if(!c)return;if(!keep)remember('c',code);drawerCode=code;const d=$('#drawer');const wasOpen=!d.hidden;$('#drawer-backdrop').hidden=false;d.hidden=false;if(!keep&&!wasOpen){d.classList.remove('out');}d.classList.toggle('keep',!!keep);
 const m=c.meta;const fact=(label,value,copyable)=>`<div class="fact"><small>${label}</small><span>${value?esc(value):'<span class="muted">—</span>'}${value&&copyable?`<button class="copy" data-copy="${esc(value)}" aria-label="Copia">${icon('copy')}</button>`:''}</span></div>`;
 const scroll=d.scrollTop;
 d.innerHTML=clientCard(c,fact);
 if(keep)d.scrollTop=scroll;else d.querySelector('[data-close]').focus({preventScroll:true});
}
function closeDrawer(){const d=$('#drawer');if(d.hidden)return;d.classList.add('out');$('#drawer-backdrop').classList.add('out');setTimeout(()=>{d.hidden=true;d.classList.remove('out');$('#drawer-backdrop').hidden=true;$('#drawer-backdrop').classList.remove('out');},280);drawerCode=null;}
function saveMeta(code,patch){const old=state.metadata[code]||{favorite:false,nextContact:'',followupState:''};const meta={favorite:!!old.favorite,nextContact:old.nextContact||'',followupState:old.followupState||'',...patch};state.metadata[code]=meta;derive();if(typeof applyEdits==='function')applyEdits();if(typeof deriveDtr==='function')deriveDtr();if(typeof deriveItal==='function')deriveItal();if(typeof deriveRch==='function')deriveRch();if(typeof deriveRegisters==='function')deriveRegisters();if(typeof deriveWork==='function')deriveWork();send('meta',{code,meta});render(false);openClient(code,true);}

// ───────────────────────── command palette
// ── two areas, like MastroSQL (Mastro / Contabilità): Interventi = the daily work, Contabilità = Mastro's accounting.
// Each area has its own navigation and remembers its last page; technicians only have Interventi.
const ACC_PAGES=['accounting','accRec','accInv','accPay','accCli'];
const areaOf=p=>ACC_PAGES.includes(p)?'acc':'ops';
// system pages (Utenti, Sync, Impostazioni, App) belong to both areas: opening them keeps the area you are in
const SYS_PAGES=['team','sync','settings','apps'];const curArea=()=>document.documentElement.dataset.area||areaOf(page);
const AREA_TABS={ops:['overview','clients','interventions','warehouse'],acc:['accounting','accRec','accInv','accPay']};
let MAIN_TABS=AREA_TABS.ops;
function setArea(a){const root=document.documentElement;if(root.dataset.area===a)return;const was=root.dataset.area;root.dataset.area=a;MAIN_TABS=AREA_TABS[a];
 $$('.nav-item[data-page]').forEach(b=>b.classList.toggle('tab-main',MAIN_TABS.includes(b.dataset.page)));
 $$('[data-area-go]').forEach(b=>{b.setAttribute('aria-selected',String(b.dataset.areaGo===a));});
 // the area's navigation slides in from the side of the chosen tab (one movement, no flash)
 const nav=$('#nav');if(was&&nav?.animate&&!reduced)nav.animate([{opacity:0,transform:`translateX(${a==='acc'?12:-12}px)`},{opacity:1,transform:'none'}],{duration:260,easing:'cubic-bezier(.2,.8,.2,1)'});}
/** the sections of the current area in menu order (what Alt+1…9 opens) */
function areaPages(){const a=curArea();return[...new Set($$('#nav .nav-item[data-page]').filter(b=>[a,'both'].includes(b.dataset.area||'ops')&&!(b.classList.contains('need-admin')&&!isAdmin())&&!(b.classList.contains('need-acc')&&!canAccounting())&&!(b.dataset.page==='team'&&!(WEB&&(me?.role==='admin'||me?.role==='owner')))&&PAGES[b.dataset.page]).map(b=>b.dataset.page))];}
function goArea(a){if(a==='acc'&&!canAccounting())return;if(curArea()===a&&!SYS_PAGES.includes(page))return;let p=store.get('page.'+a);if(!PAGES[p]||areaOf(p)!==a||SYS_PAGES.includes(p))p=a==='acc'?'accounting':'overview';go(p);}
document.addEventListener('click',e=>{const b=e.target.closest('[data-area-go]');if(b)goArea(b.dataset.areaGo);});
let cmdSel=0,cmdItems=[];
// recently opened clients / interventions (per device) → first thing in the quick search
function remember(k,id){if(!id)return;const r=store.get('recent',[]).filter(x=>!(x.k===k&&x.id===id));r.unshift({k,id});store.set('recent',r.slice(0,6));}
function recentItems(){return store.get('recent',[]).map(({k,id})=>{if(k==='c'){const c=D.byCode.get(id);return c&&{g:'Aperti di recente',t:c.name,s:[c.city,c.phone].filter(Boolean).join(' · '),mark:c,run:()=>openClient(c.code)};}
 const i=D.interventions.find(x=>x.id===id);return i&&{g:'Aperti di recente',t:i.title||'Intervento',s:(i.c?.name||i.clientName||'')+' · '+i.status,ic:'tool',run:()=>openIntervention(i.id)};}).filter(Boolean);}
const isAdmin=()=>!WEB||(me?.role==='admin'||me?.role==='owner');
// every role can create and edit customers; deleting, restoring and merging stay with administrators
const canEditClients=()=>!WEB||!!me;
const canAccounting=()=>!WEB||(!!me&&me.role!=='tecnico');
// warehouse management: administrators only
const HAS_W=()=>typeof W!=='undefined'&&W&&isAdmin();
// quick actions shared by the palette, the "Nuovo" menu and the keyboard shortcuts
function quickActions(){const a=[{t:'Nuovo intervento',s:'N',ic:'tool',run:()=>openIntervention(null)}];
 if(HAS_W())a.push({t:'Carico merce in magazzino',s:'M',ic:'box',run:()=>openMovement('carico')},{t:'Trasferisci sul furgone',s:'',ic:'arrow',run:()=>openMovement('trasferimento')},{t:'Scarica un ricambio',s:'',ic:'upload',run:()=>openMovement('scarico')});
 return a;}
function openCmd(){$('#cmd').hidden=false;const i=$('#cmd-q');i.value=ui.q||'';cmdRender();setTimeout(()=>{i.focus();i.select();},10);}
function closeCmd(){$('#cmd').hidden=true;}
function cmdRender(){const q=norm($('#cmd-q').value.trim());const out=[];
 const AP=areaPages();Object.entries(PAGES).forEach(([k,l])=>{const i=AP.indexOf(k);if(k==='team'&&!(WEB&&(me?.role==='admin'||me?.role==='owner')))return;if((k==='warehouse'||k==='check')&&!isAdmin())return;if(ACC_PAGES.includes(k)&&!canAccounting())return;if(!q||norm(l).includes(q))out.push({g:'Sezioni',t:l,s:i>=0&&i<9?'Alt + '+(i+1):(areaOf(k)==='acc'?'Contabilità':'Interventi'),ic:{overview:'home',clients:'users',licenses:'key',expiry:'calendar',orders:'bag',tasks:'task',sync:'sync',settings:'settings',team:'shield',interventions:'tool',machines:'register',warehouse:'box',accounting:'euro',route:'pin',renewals:'phone',inventory:'grid',check:'check',dtr:'shield',ital:'register'}[k],run:()=>go(k)});});
 if(q){D.clients.filter(c=>c.search.includes(q)).slice(0,8).forEach(c=>out.push({g:'Clienti',t:c.name,s:[c.city,c.phone,c.email].filter(Boolean).join(' · ')||'Cod. '+c.code,mark:c,run:()=>openClient(c.code)}));
  state.records.filter(r=>norm(r.serial).includes(q)).slice(0,5).forEach(r=>out.push({g:'Matricole',t:r.serial,s:r.client+' · scade '+(r.expiry||'—'),ic:'key',run:()=>openClient(r.clientCode)}));
  if(HAS_W())W.items.filter(i=>i.search.includes(q)).slice(0,5).forEach(i=>out.push({g:'Magazzino',t:i.name,s:i.code+(i.barcode?' · '+i.barcode:'')+' · giacenza '+qf(i.total)+' '+i.unit,ic:'box',run:()=>openArticle(i.code)}));
  D.orders.filter(o=>norm(o.number).includes(q)).slice(0,4).forEach(o=>out.push({g:'Ordini',t:'Ordine '+o.number,s:o.client+' · '+o.status,ic:'bag',run:()=>o.clientCode?openClient(o.clientCode):go('orders')}));
  out.push({g:'Azioni',t:`Mostra tutti i risultati per “${$('#cmd-q').value.trim()}”`,s:'Elenco clienti filtrato',ic:'search',run:()=>{ui.q=$('#cmd-q').value.trim();go('clients',true);}});}
 else{out.unshift(...recentItems(),...quickActions().map(x=>({...x,g:'Azioni rapide'})));
  (WEB?[]:[['Sincronizza ora con Zucchetti','sync',()=>send('syncPortal')],['Esporta anagrafica clienti','download',()=>send('exportClients')],['Crea backup del database','shield',()=>send('backup')]]).concat([['Cambia tema chiaro/scuro','moon',toggleTheme],['Scorciatoie da tastiera','sparkle',showKeys]]).forEach(([t,ic,run])=>out.push({g:'Altro',t,s:'',ic,run}));}
 cmdItems=out;cmdSel=0;let g='';$('#cmd-results').innerHTML=out.map((o,i)=>{const h=o.g!==g?`<div class="cmd-group">${o.g}</div>`:'';g=o.g;return h+`<button class="cmd-item${i===0?' sel':''}" data-cmd="${i}">${o.mark?markEl(o.mark.name,o.mark.code):`<span class="ico">${icon(o.ic)}</span>`}<span class="grow"><strong>${hl(o.t,$('#cmd-q').value.trim())}</strong>${o.s?`<small>${esc(o.s)}</small>`:''}</span>${icon('chevron')}</button>`;}).join('')||`<div class="empty">${icon('search')}<strong>Nessun risultato</strong></div>`;}
function cmdMove(d){if(!cmdItems.length)return;cmdSel=(cmdSel+d+cmdItems.length)%cmdItems.length;$$('.cmd-item').forEach((b,i)=>b.classList.toggle('sel',i===cmdSel));$$('.cmd-item')[cmdSel]?.scrollIntoView({block:'nearest'});}
function cmdRun(i){const it=cmdItems[i];if(!it)return;closeCmd();it.run();}

// ───────────────────────── render & navigation
function render(animate){
 if(!D)return;const main=$('#main');const scroll=main.scrollTop;const focus=document.activeElement?.id;const caret=document.activeElement?.selectionStart;
 if(page==='team'&&!(WEB&&(me?.role==='admin'||me?.role==='owner')))page='overview';
 if(page==='warehouse'&&!isAdmin())page='overview';
 if(ACC_PAGES.includes(page)&&!canAccounting())page='overview';
 const html={overview,clients:clientsPage,licenses:licensesPage,expiry:expiryPage,orders:ordersPage,tasks:tasksPage,sync:syncPage,settings:settingsPage,team:teamPage,apps:typeof appsPage==='function'?appsPage:overview,interventions:interventionsPage,accRec:()=>accRegister('receivables'),accInv:()=>accRegister('invoices'),accPay:()=>accRegister('payables'),accCli:()=>accRegister('clients'),machines:typeof machinesPage==='function'?machinesPage:overview,route:typeof routePage==='function'?routePage:overview,renewals:typeof renewalsPage==='function'?renewalsPage:overview,inventory:typeof inventoryPage==='function'?inventoryPage:overview,check:typeof checkPage==='function'?checkPage:overview,dtr:typeof dtrPage==='function'?dtrPage:overview,ital:typeof italPage==='function'?italPage:overview,rch:typeof rchPage==='function'?rchPage:overview,warehouse:typeof warehousePage==='function'?warehousePage:overview,accounting:typeof accountingPage==='function'?accountingPage:overview}[page]||overview;
 document.body.classList.toggle('anim',!!animate&&!reduced);
 // live update on the same page: remember the numbers shown, to roll only the ones that change
 const before=!animate&&lastRendered===page?$$('#main [data-count]').map(el=>+el.dataset.count):null;
 const badges=$$('.nav-item .badge, .nav-item [id^="nav-"]').map(b=>[b,b.textContent]);
 {const ar=SYS_PAGES.includes(page)?curArea():areaOf(page);setArea(ar);if(!SYS_PAGES.includes(page))store.set('page.'+ar,page);}
 main.innerHTML=`<div class="page">${html()}</div>`;lastRendered=page;
 $('#crumb').textContent=PAGES[page];
 $$('.nav-item').forEach(b=>b.classList.toggle('active',b.dataset.page===page));$('#nav-more')?.classList.toggle('active',!MAIN_TABS.includes(page));moveIndicator();
 if(typeof renewalItems==='function'){const set=(id,v)=>{const e=document.getElementById(id);if(e)e.textContent=v?fmt(v):'';};try{set('nav-renewals',renewalItems().filter(x=>x.n<=30&&!['Rinnovato','Perso'].includes(x.status)).length) /* only what needs a call within 30 days, not the whole 4-month list */;set('nav-route',(()=>{const st=routeStops(),t=routeMine([...new Set(st.map(s=>s.tech).filter(Boolean))]);return st.filter(s=>s.rank===0&&!routeIsDone(s)&&(!t||s.tech===t||!s.tech)).length;})()) /* only the urgent stops, not the whole list */;set('nav-inventory',(D.assetsDue||[]).length);}catch(e){console.warn(e);}}
 $('#nav-clients').textContent=fmt(D.clients.length);$('#nav-licenses').textContent=fmt(D.current.length);$('#nav-orders').textContent=D.orders.length?fmt(D.orders.length):'';$('#nav-interventions').textContent=D.openInterventions.length||'';$('#nav-machines').textContent=D.machines.length?fmt(D.machines.length):'';if(typeof DT!=='undefined'&&DT&&$('#nav-dtr'))$('#nav-dtr').textContent=DT.status?.error?'!':DT.silent.length?fmt(DT.silent.length):'';if(typeof IT!=='undefined'&&IT&&$('#nav-ital'))$('#nav-ital').textContent=IT.status?.error?'!':IT.update.length?fmt(IT.update.length):'';if(typeof RC!=='undefined'&&RC&&$('#nav-rch'))$('#nav-rch').textContent=RC.status?.error?'!':RC.low.length?fmt(RC.low.length):'';if(typeof W!=='undefined'&&W)$('#nav-warehouse').textContent=W.below.length||'';if(typeof A!=='undefined'&&A){$('#nav-accounting').textContent=A.overdue.length?fmt(A.overdue.length):'';const p7=A.payOpen.filter(x=>x.days!==null&&x.days<=7).length;$('#nav-accpay').textContent=p7?fmt(p7):'';}
 $('#nav-due').textContent=agendaItems(0,30).length||'';const late=D.tasks.filter(c=>c.meta.followupState!=='Completato'&&c.meta.nextContact&&days(c.meta.nextContact)<=0).length;$('#nav-tasks').textContent=late||'';
 $('#alerts-dot').classList.toggle('on',D.due30.length>0);$('#nav-more-dot').textContent=($('#nav-due').textContent||$('#nav-tasks').textContent||$('#nav-team')?.textContent)?'•':'';checkDevices();
 $('#nav-sync-dot').className='sync-dot '+(state.syncRunning?'run':state.portalConnected?'on':'');
 $('#sync-pill').hidden=!state.syncRunning;$('#sync-pill-text').textContent=state.syncProgress||'Sincronizzazione…';
 sideStatus();
 if(before)liveChanges(before);
 if(!reduced)for(const[b,t]of badges)if(b.textContent&&b.textContent!==t){b.classList.remove('pop');void b.offsetWidth;b.classList.add('pop');}
 if(animate){main.scrollTop=0;countUp();}else{main.scrollTop=scroll;if(focus){const el=document.getElementById(focus);if(el){el.focus({preventScroll:true});try{el.setSelectionRange(caret,caret);}catch{}}}}
 bind();
}
function go(p,keepQuery,fromHistory){if(!PAGES[p])return;const change=()=>{page=p;store.set('page',p);ui.p=1;ui.status='';ui.product='';ui.city='';ui.ostatus='';ui.ofam='';ui.tech='';if(!keepQuery)ui.q='';ui.sort={key:p==='orders'?'date':p==='clients'?'name':'exp',dir:p==='orders'?-1:1};render(true);};
 if(p!==page){if(!fromHistory)navHistory.push(p);transition(change,fromHistory?'back':'fwd');}else change();}
// Phones (every browser): the new page rises into place inside the scrolling area, under the glass bars, card by card.
// Nothing is snapshotted, so nothing can ever be drawn over the bottom bar (Chrome, Safari, Firefox, Samsung Internet).
const PHONE=matchMedia('(max-width:760px)');
// One layer moves, once: forward = rises in, back = slides in from the left. Cards never travel over one another.
function pageIn(dir){const main=$('#main');if(!main?.animate)return;
 main.getAnimations().forEach(a=>a.cancel());
 main.animate(dir==='back'?[{opacity:0,transform:'translateX(-18px)'},{opacity:1,transform:'none'}]:[{opacity:0,transform:'translateY(10px)'},{opacity:1,transform:'none'}],
  {duration:dir==='back'?260:300,easing:'cubic-bezier(.2,.8,.2,1)'});}
// View Transitions with graceful fallback: a new navigation skips the running one, and aborted transitions are silent.
let vt=null;
function transition(fn,dir='fwd'){const pre=()=>{const c=transition.cleanup;transition.cleanup=null;c?.();};
 if(!document.startViewTransition||reduced||document.hidden||PHONE.matches){pre();fn();if(!reduced&&!document.hidden)pageIn(dir);return;}
 // clip the page snapshot to the area the bars leave free (top glass bar, bottom tab bar on phones)
 {const m=$('#main'),tb=$('.topbar'),sb=$('.sidebar');if(m){const r=m.getBoundingClientRect(),t=tb?.getBoundingClientRect(),s=sb?.getBoundingClientRect();const root=document.documentElement.style;
  root.setProperty('--vt-top',Math.max(0,(t?t.bottom:r.top)-r.top)+'px');root.setProperty('--vt-bottom',(s&&s.width>=innerWidth-2&&s.top<r.bottom?Math.max(0,r.bottom-s.top):0)+'px');}}
 document.documentElement.dataset.nav=dir;clearTimeout(transition.t);transition.t=setTimeout(()=>{delete document.documentElement.dataset.nav;},900); // never left behind
 let done=false;const once=()=>{if(!done){done=true;pre();fn();document.body.classList.remove('anim');}};setTimeout(once,350); // the page cross-fade is the only motion: no card-by-card entrance on top of it (it made sections wobble and pop in late) // never block navigation if the window is not painting
 vt?.skipTransition();const t=vt=document.startViewTransition(once);for(const p of[t.ready,t.finished,t.updateCallbackDone])p.catch(()=>{});t.finished.finally(()=>{if(vt===t){vt=null;delete document.documentElement.dataset.nav;}});}
// ── pointer spotlight on cards (theme.css): one passive listener, at most one style write per frame
{let lit=null,px=0,py=0,raf=0;
 const paint=()=>{raf=0;if(!lit)return;const r=lit.getBoundingClientRect();lit.style.setProperty('--mx',(px-r.left)+'px');lit.style.setProperty('--my',(py-r.top)+'px');};
 document.addEventListener('pointermove',e=>{if(e.pointerType!=='mouse')return;const c=e.target.closest?.('.card');if(c!==lit){lit?.classList.remove('lit');lit=c;c?.classList.add('lit');}px=e.clientX;py=e.clientY;if(lit&&!raf)raf=requestAnimationFrame(paint);},{passive:true});
 document.addEventListener('pointerleave',()=>{lit?.classList.remove('lit');lit=null;});}
function moveIndicator(){const a=$('.nav-item.active'),ind=$('.nav-indicator');if(!a){ind.style.opacity=0;return;}ind.style.opacity=1;ind.style.transform=`translateY(${a.offsetTop}px)`;ind.style.height=a.offsetHeight+'px';const n=a.closest('nav');if(n&&(a.offsetTop<n.scrollTop||a.offsetTop+a.offsetHeight>n.scrollTop+n.clientHeight))a.scrollIntoView({block:'nearest',behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'auto':'smooth'});}
let lastRendered='';
// ── Back button / back gesture (browser, installed app on the phone): it first closes the open window or client panel,
// then goes back through the pages visited — it no longer leaves the app by mistake.
// Every history entry is {tcs,page,ov}: ov = how many windows were open. Windows closed from the UI remove their entry.
const navHistory=(()=>{
 let ignore=0,queued=null,raf=0;
 const overlays=()=>[...document.querySelectorAll('body > .modal:not(.out)')].length+(($('#drawer')&&!$('#drawer').hidden&&!$('#drawer').classList.contains('out'))?1:0);
 const cur=()=>history.state?.tcs?history.state:{tcs:1,page,ov:0};
 const reconcile=()=>{raf=0;if(ignore)return;const n=overlays(),s=cur();
  if(n>s.ov)history.pushState({tcs:1,page,ov:n},'');
  else if(n<s.ov){ignore++;history.back();}};
 const schedule=()=>{if(!raf)raf=setTimeout(reconcile,40);}; // a timer, not rAF: it must run also when the window is in background
 const closeTop=()=>{const m=[...document.querySelectorAll('body > .modal:not(.out)')].pop();
  if(m){m.dispatchEvent(new MouseEvent('click',{bubbles:true}));return true;}
  if(!$('#drawer').hidden){closeDrawer();return true;}return false;};
 addEventListener('popstate',e=>{
  if(ignore){ignore--;if(!ignore&&queued){history.pushState(queued,'');queued=null;}schedule();return;}
  const s=e.state?.tcs?e.state:{tcs:1,page:'overview',ov:0};
  if(overlays()>s.ov){closeTop();schedule();return;}
  if(s.page!==page){go(s.page,false,true);return;}
 });
 new MutationObserver(schedule).observe(document.body,{childList:true});
 queueMicrotask(()=>{const d=$('#drawer');if(d)new MutationObserver(schedule).observe(d,{attributes:true,attributeFilter:['hidden','class']});});
 history.replaceState({tcs:1,page,ov:0},'');
 return{push(p){const st={tcs:1,page:p,ov:0};
   if(ignore){queued=st;return;}                          // a "close window" step is still travelling: push right after it
   if(cur().ov>0)history.replaceState(st,'');else history.pushState(st,'');}}; // leaving a page with a window open: reuse its entry
})();
// Numbers that changed with a live update roll from the old to the new value; their card glows once.
function liveChanges(before){if(reduced)return;const now=$$('#main [data-count]');if(now.length!==before.length)return;
 now.forEach((el,i)=>{const from=before[i],to=+el.dataset.count;if(from===to||!isFinite(from)||!isFinite(to))return;
  const t0=performance.now(),dur=700;el.classList.add(to>from?'tick-up':'tick-down');
  const step=t=>{const k=Math.min(1,(t-t0)/dur),e=1-Math.pow(1-k,4);el.textContent=fmt(Math.round(from+(to-from)*e));if(k<1)requestAnimationFrame(step);else setTimeout(()=>el.classList.remove('tick-up','tick-down'),600);};requestAnimationFrame(step);
  const card=el.closest('.card');if(card){card.classList.remove('live');void card.offsetWidth;card.classList.add('live');setTimeout(()=>card.classList.remove('live'),1400);}});}
function countUp(){if(reduced)return;$$('[data-count]').forEach(el=>{const to=+el.dataset.count;if(!to)return;const t0=performance.now(),dur=600;const step=t=>{const k=Math.min(1,(t-t0)/dur),e=1-Math.pow(1-k,4);el.textContent=fmt(Math.round(to*e));if(k<1)requestAnimationFrame(step);};requestAnimationFrame(step);});}
function bind(){
 const q=$('#q');if(q)q.addEventListener('input',e=>{ui.q=e.target.value;ui.p=1;clearTimeout(searchTimer);searchTimer=setTimeout(()=>requestAnimationFrame(()=>render(false)),90);});
 const on=(id,key)=>{const el=$('#'+id);if(el)el.addEventListener('change',e=>{ui[key]=e.target.value;ui.p=1;render(false);});};
 on('f-status','status');on('f-product','product');on('f-city','city');on('f-ctech','ctech');on('f-ostatus','ostatus');on('f-ofam','ofam');on('f-tech','tech');
}

// ───────────────────────── theme
function applyTheme(t){const r=document.documentElement;const dark=t==='dark'||(t==='auto'&&matchMedia('(prefers-color-scheme: dark)').matches);r.dataset.theme=dark?'dark':'light';}
function setTheme(t){store.set('theme',t);transition(()=>{applyTheme(t);if(page==='settings')render(false);});}
function toggleTheme(){setTheme(document.documentElement.dataset.theme==='dark'?'light':'dark');}
applyTheme(store.get('theme','auto'));matchMedia('(prefers-color-scheme: dark)').addEventListener('change',()=>applyTheme(store.get('theme','auto')));

// ───────────────────────── events
document.addEventListener('click',e=>{
 const t=e.target.closest('button,[data-go],[data-client],[data-cmd],[data-int]');if(!t)return;const ds=t.dataset;
 if(ds.cmd!==undefined)return cmdRun(+ds.cmd);
 if(ds.page)return go(ds.page);
 if(ds.ext){e.stopPropagation();return external(ds.ext);}
 if(ds.copy){e.stopPropagation();return copy(ds.copy);}
 if(ds.hideExplain){store.set('hide.'+ds.hideExplain,true);const box=t.closest('.explain');box.style.transition='opacity .25s, transform .3s';box.style.opacity=0;box.style.transform='translateY(-6px)';setTimeout(()=>render(false),260);return;}
 if(ds.action)return send(ds.action);
 if(ds.actionLocal==='reload')return location.reload();
 if(ds.actionLocal==='logout')return logout();
 if(ds.actionLocal)return localAction(ds.actionLocal,t);
 if(ds.themeSet)return setTheme(ds.themeSet);
 if(ds.perfSet)return setPerf(ds.perfSet);
 if(ds.autostart)return send('autostart',{on:ds.autostart==='1'});
 if(ds.adays){ui.adays=+ds.adays;ui.amore=60;return render(false);}
 if('akind' in ds){ui.akind=ds.akind;ui.amore=60;return render(false);}
 if('amore' in ds){ui.amore=(ui.amore||60)+60;return render(false);}
 if(ds.go){if(ds.goDtab)ui.dtab=ds.goDtab;if(ds.goItab)ui.itab=ds.goItab;if(ds.goRtab)ui.rtab=ds.goRtab;return go(ds.go);}
 if(t.hasAttribute('data-close'))return closeDrawer();
 if(ds.fav){const m=state.metadata[ds.fav]||{};saveMeta(ds.fav,{favorite:!m.favorite});toast(!m.favorite?'Aggiunto ai preferiti':'Rimosso dai preferiti','star');return;}
 if(ds.save){const code=ds.save;state.notes[code]=$('#m-note').value;send('note',{code,note:$('#m-note').value});saveMeta(code,{followupState:$('#m-state').value,nextContact:$('#m-date').value});return;}
 if(ds.step){ui.p+=+ds.step;render(false);$('#main').scrollTo({top:0,behavior:reduced?'auto':'smooth'});return;}
 if(ds.tab){ui.tab=ds.tab;ui.p=1;return render(false);}
 if(ds.ttab){ui.taskTab=ds.ttab;return render(false);}
 if(ds.itab){ui.itab=ds.itab;ui.p=1;return render(false);}
 if(ds.mtab){ui.mtab=ds.mtab;ui.p=1;return render(false);}
 if(ds.devAct){(async()=>{const d=(usersData.devices||[]).find(x=>x.id===ds.dev);
  if(ds.devAct!=='approve'&&!await ask({title:ds.devAct==='revoke'?'Bloccare il dispositivo?':'Eliminare il dispositivo?',text:`${esc(d?.name||ds.dev)} (${esc(ds.dev)}) ${ds.devAct==='delete'?'sparisce dall’elenco; se riprova da fuori ufficio dovrà essere autorizzato di nuovo':'non potrà più accedere da fuori ufficio finché non lo sblocchi'}.`,ok:ds.devAct==='revoke'?'Blocca':'Elimina',danger:true}))return;
  try{await post('/api/devices/'+encodeURIComponent(ds.dev)+'/'+ds.devAct);toast(ds.devAct==='approve'?'Dispositivo autorizzato':ds.devAct==='revoke'?'Dispositivo bloccato':'Dispositivo eliminato','shield');usersData=await api('/api/users');render(false);pendAt=0;checkDevices();}catch(x){toast(x.message,'alert');}})();return;}
 if(ds.int)return openIntervention(ds.int);
 if(ds.newint)return openIntervention(null,ds.newint);
 if(ds.cview){ui.clientView=ds.cview;ui.p=1;return render(false);}
 if(ds.sort){ui.sort=ui.sort.key===ds.sort?{key:ds.sort,dir:-ui.sort.dir}:{key:ds.sort,dir:1};return render(false);}
 if(ds.client)return openClient(ds.client);
});
document.addEventListener('keydown',e=>{
 if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();return $('#cmd').hidden?openCmd():closeCmd();}
 if(!$('#cmd').hidden){if(e.key==='Escape')closeCmd();else if(e.key==='ArrowDown'){e.preventDefault();cmdMove(1);}else if(e.key==='ArrowUp'){e.preventDefault();cmdMove(-1);}else if(e.key==='Enter'){e.preventDefault();cmdRun(cmdSel);}return;}
 // Ctrl+Invio salva il modulo aperto (intervento, movimento, articolo…)
 if((e.ctrlKey||e.metaKey)&&e.key==='Enter'){const f=document.querySelector('.modal:not(.out) form');if(f){e.preventDefault();f.requestSubmit();return;}}
 const typing=e.target.closest?.('input,textarea,select,[contenteditable]');
 if(!typing&&!e.ctrlKey&&!e.metaKey&&!e.altKey&&!document.querySelector('.modal')){
  const k=e.key.toLowerCase();
  if(k==='n'){e.preventDefault();return quickActions()[0].run();}
  if(k==='m'&&HAS_W()){e.preventDefault();return openMovement('carico');}
  if(e.key==='/'){e.preventDefault();const q=$('#q');return q?(q.focus(),q.select()):openCmd();}
  if(e.key==='?'){e.preventDefault();return showKeys();}
  if(k==='a'&&canAccounting()){e.preventDefault();return goArea(curArea()==='acc'?'ops':'acc');}
 }
 if(e.key==='Escape')return closeDrawer();
 if(e.altKey&&/^[1-9]$/.test(e.key)){e.preventDefault();const p=areaPages()[+e.key-1];return p&&go(p);}
 if(e.key==='Enter'&&e.target.matches('[data-client],[data-go],[data-int]'))e.target.click();
});
function showKeys(){const K=(k,t)=>`<div class="key-row"><span>${t}</span><span>${k.split('+').map(x=>`<kbd>${x}</kbd>`).join('')}</span></div>`;
 ask({title:'Scorciatoie da tastiera',html:`<div class="keys">${K('Ctrl+K','Ricerca rapida ovunque')}${K('/','Cerca nella pagina')}${K('N','Nuovo intervento')}${HAS_W()?K('M','Carico merce'):''}${K('Alt+1…9','Vai a una sezione dell’area')}${canAccounting()?K('A','Passa da Interventi a Contabilità'):''}${K('Ctrl+Invio','Salva il modulo aperto')}${K('Esc','Chiudi')}${K('?','Questo elenco')}</div>`,ok:'Ho capito',cancel:false});}
// "Nuovo" in the top bar: the most frequent actions one click away
{const b=$('#quick-new'),menu=$('#quick-menu');
 b.addEventListener('click',()=>{menu.innerHTML=quickActions().map((a,i)=>`<button class="qm-item" data-qm="${i}" role="menuitem"><span class="ico">${icon(a.ic)}</span><span class="grow">${a.t}</span>${a.s?`<kbd>${a.s}</kbd>`:''}</button>`).join('');
  const r=b.getBoundingClientRect();menu.style.top=r.bottom+8+'px';menu.style.right=Math.max(12,innerWidth-r.right)+'px';menu.togglePopover();});
 menu.addEventListener('click',e=>{const x=e.target.closest('[data-qm]');if(!x)return;menu.hidePopover();quickActions()[+x.dataset.qm]?.run();});}
$('#cmd-q').addEventListener('input',cmdRender);
$('#cmd').addEventListener('click',e=>{if(e.target.id==='cmd')closeCmd();});
$('#cmd-open').addEventListener('click',openCmd);
$('#drawer-backdrop').addEventListener('click',closeDrawer);
// phone: 4 main sections in the bottom bar, the rest in the "Altro" sheet
$('#nav-more').addEventListener('click',openMoreSheet);
// the sheet keeps the same sections as the side menu (Lavoro, Registratori, Zucchetti, Ufficio, Sistema)
// short names in the sheet: the section title already says what they are
const MORE_SHORT={machines:'Registratori',dtr:'DTR',ital:'Ital Retail',rch:'RCH',sync:'Sincronizza',apps:'App'};
function moreGroups(items){const groups=[];let cur=null,i=0;
 for(const el of $$('#nav > *')){
  if(el.classList.contains('nav-caption')){cur={title:el.textContent,items:[]};groups.push(cur);continue;}
  if(!items.includes(el))continue;if(!cur){cur={title:'',items:[]};groups.push(cur);}cur.items.push(el);
 }
 return groups.filter(g=>g.items.length).map(g=>`<section class="more-group">${g.title?`<h3>${esc(g.title)}</h3>`:''}<div class="more-grid">${g.items.map(b=>{const em=b.querySelector('em.hot')?.textContent||'';const label=MORE_SHORT[b.dataset.page]||b.querySelector('span').textContent;return`<button class="more-item${b.dataset.page===page?' on':''}" data-more="${b.dataset.page}" style="--i:${i++}">${b.querySelector('svg').outerHTML}<span>${esc(label)}</span>${em?`<em>${esc(em)}</em>`:''}</button>`;}).join('')}</div></section>`).join('');}
function openMoreSheet(){if(document.querySelector('.more-sheet'))return;
 const items=$$('.nav-item[data-page]').filter(b=>!MAIN_TABS.includes(b.dataset.page)&&!(b.dataset.page==='team'&&!(WEB&&(me?.role==='admin'||me?.role==='owner')))&&!(b.dataset.page==='warehouse'&&!isAdmin())&&!(b.dataset.page==='check'&&!isAdmin())&&!(ACC_PAGES.includes(b.dataset.page)&&!canAccounting())&&[curArea(),'both'].includes(b.dataset.area||'ops')&&b.dataset.page!==undefined);
 const m=document.createElement('div');m.className='modal more-sheet';
 m.innerHTML=`<div class="sheet-box" role="dialog" aria-label="Altre sezioni"><i class="grip"></i>${moreGroups(items)}</div>`;
 document.body.append(m);
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),260);};
 m.addEventListener('click',e=>{const b=e.target.closest('[data-more]');
  if(b&&b.dataset.more===page)close();
  else if(b){ // the sheet leaves inside the page change (no frozen glass): removed when the new page is drawn
   const box=m.querySelector('.sheet-box');if(document.startViewTransition&&!reduced&&!PHONE.matches){box.style.viewTransitionName='more-sheet';m.style.viewTransitionName='more-dim';transition.cleanup=()=>m.remove();setTimeout(()=>m.isConnected&&m.remove(),600);go(b.dataset.more);}
   else{close();go(b.dataset.more);}}
  else if(!e.target.closest('.sheet-box'))close();});
 document.addEventListener('keydown',function k(e){if(e.key==='Escape'){close();document.removeEventListener('keydown',k,true);}},true);
 // swipe down to close
 const box=m.querySelector('.sheet-box');let y0=null;box.addEventListener('pointerdown',e=>{y0=e.clientY;});box.addEventListener('pointerup',e=>{if(y0!==null&&e.clientY-y0>60)close();y0=null;});
}
$('#alerts').addEventListener('click',()=>go('expiry'));
$('#theme-toggle').addEventListener('click',toggleTheme);
addEventListener('resize',moveIndicator);
// glass top bar gets its hairline + shadow as soon as the page scrolls under it
// the scrollbar's real width (classic bars on Windows, 0 for overlay bars on phones and Mac): top bar and bottom fade end before it
{const m=$('#main'),sh=$('.shell');if(m&&sh){let last=-1;const sbw=()=>{const w=Math.max(0,m.offsetWidth-m.clientWidth);if(w!==last){last=w;sh.style.setProperty('--sbw',w+'px');}};sbw();if('ResizeObserver' in window)new ResizeObserver(sbw).observe(m);else addEventListener('resize',sbw);}}
{const m=$('#main'),tb=$('.topbar');let raf=0;m.addEventListener('scroll',()=>{if(raf)return;raf=requestAnimationFrame(()=>{raf=0;tb.classList.toggle('scrolled',m.scrollTop>4);});},{passive:true});}
// overlay scrollbars: the thumb shows on the area that is scrolling and fades out shortly after it stops (theme.css)
{const live=new Map();document.addEventListener('scroll',e=>{const el=e.target===document?document.documentElement:e.target;if(!el.classList)return;el.classList.add('is-scrolling');clearTimeout(live.get(el));live.set(el,setTimeout(()=>{el.classList.remove('is-scrolling');live.delete(el);},900));},{capture:true,passive:true});}

// ───────────────────────── performance profile
// "auto" measures the real frame rate at startup and picks the lighter profile on slow or low-memory PCs.
// It also keeps watching while the app is used: data saver, low battery, reduced transparency and real stutters
// switch to the light profile by themselves, and back when the reason goes away.
let perfTemp=false;
function applyPerf(){const pref=store.get('perf','auto');let lite=pref==='lite';
 if(pref==='auto')lite=perfTemp||!!navigator.connection?.saveData||matchMedia('(prefers-reduced-transparency: reduce)').matches||(store.get('perf.detected',null)??((navigator.hardwareConcurrency||8)<=4||(navigator.deviceMemory||8)<=4));
 const v=lite?'lite':'full';if(document.documentElement.dataset.perf!==v)document.documentElement.dataset.perf=v;}
(function watchPerf(){
 try{navigator.connection?.addEventListener?.('change',applyPerf);}catch{}
 try{matchMedia('(prefers-reduced-transparency: reduce)').addEventListener('change',applyPerf);}catch{}
 // battery saver: below 20% and not charging the effects step aside until the device is plugged in
 navigator.getBattery?.().then(b=>{const f=()=>{const t=!b.charging&&b.level<=.2;if(t!==perfTemp){perfTemp=t;applyPerf();}};f();b.addEventListener('levelchange',f);b.addEventListener('chargingchange',f);}).catch(()=>{});
 // real stutters while the person uses the app: many long frames in a short time → light profile, remembered
 let hits=[];const type=PerformanceObserver.supportedEntryTypes?.includes('long-animation-frame')?'long-animation-frame':PerformanceObserver.supportedEntryTypes?.includes('longtask')?'longtask':null;
 if(!type)return;
 try{new PerformanceObserver(l=>{if(store.get('perf','auto')!=='auto'||document.documentElement.dataset.perf==='lite'||document.hidden)return;
  const now=performance.now();for(const e of l.getEntries())if(e.duration>=(type==='longtask'?150:120))hits.push(now);
  hits=hits.filter(t=>now-t<15000);
  if(hits.length>=8){hits=[];store.set('perf.detected',true);applyPerf();}}).observe({type,buffered:false});}catch{}
})();
function probeFps(){if(store.get('perf','auto')!=='auto')return;let n=0,t0=0;const step=t=>{if(!t0)t0=t;if(++n<40)return requestAnimationFrame(step);const ms=(t-t0)/39;// average frame time while the aurora and splash animate
 const lite=ms>22||(navigator.hardwareConcurrency||8)<=2;if(document.visibilityState==='visible'&&ms<200){store.set('perf.detected',lite);applyPerf();}};setTimeout(()=>requestAnimationFrame(step),1200);}
function setPerf(v){store.set('perf',v);if(v==='auto')store.set('perf.detected',null);applyPerf();if(v==='auto')probeFps();render(false);toast(v==='lite'?'Modalità leggera attiva: massima fluidità':v==='full'?'Effetti completi attivi':'Prestazioni in automatico');}
applyPerf();probeFps();
// Pause decorative animations whenever the window is not in front (saves CPU/GPU and battery).
const idle=()=>document.documentElement.classList.toggle('idle',document.hidden||!document.hasFocus());
addEventListener('blur',idle);addEventListener('focus',idle);document.addEventListener('visibilitychange',idle);

function sideStatus(){const el=$('#side-status');if(!el)return;el.textContent=state.syncRunning?'Sincronizzazione…':WEB?(me?.roleLabel||''):state.portalConnected?'Zucchetti collegato':'Archivio locale';}
function progress(v){state.syncRunning=v.running;state.syncProgress=v.text;state.portalConnected=v.connected??state.portalConnected;
 $('#sync-pill').hidden=!v.running;$('#sync-pill-text').textContent=v.text||'Sincronizzazione…';
 $('#nav-sync-dot').className='sync-dot '+(v.running?'run':state.portalConnected?'on':'');
 sideStatus();
 const live=$('#sync-live');if(live)live.textContent=v.text||'';}
function receive(v){
 if(v.type==='toast')return toast(v.message);
 if(v.type==='clientSaved'){if(typeof pendingOpen!=='undefined'&&!D.byCode.has(v.code))pendingOpen=v.code;return;}
 if(v.type==='progress')return progress(v);
 if(v.type==='mastro'){mastroState=v;if(v.stage==='preview'){mastroState=null;mastroPreview(v);}if(page==='settings')render(false);if(v.ok===false)toast(v.error,'alert');return;}
 if(v.type==='host'){hostInfo={...hostInfo,...v};offerPublic();if(v.syncStation)document.documentElement.dataset.station='1';else delete document.documentElement.dataset.station;if(page==='settings'||page==='team'||page==='sync')render(false);return;}
 if(v.type!=='state')return;
 if(v.interventions&&!firstState)noticeAssigned(state.interventions,v.interventions);
 if(firstState&&me?.role==='tecnico'&&ui.itab==='open')ui.itab='mine';
 state={...state,...v,clients:v.clients||[],orders:v.orders||[],mastroClients:v.mastroClients||[],machines:v.machines||[],interventions:v.interventions||[],warehouses:v.warehouses||[],articles:v.articles||[],stockLevels:v.stockLevels||[],stock:v.stock||[],movements:v.movements||[],invoices:v.invoices||[],dues:v.dues||[],balances:v.balances||[],dueNotes:v.dueNotes||{},metadata:v.metadata||{},notes:v.notes||{}};derive();if(typeof applyEdits==='function')applyEdits();if(typeof deriveDtr==='function')deriveDtr();if(typeof deriveItal==='function')deriveItal();if(typeof deriveRch==='function')deriveRch();if(typeof deriveRegisters==='function')deriveRegisters();if(typeof deriveWork==='function')deriveWork();
 render(firstState);firstState=false;if(typeof pendingOpen!=='undefined'&&pendingOpen&&D.byCode.has(pendingOpen)){const pc=pendingOpen;pendingOpen='';openClient(pc);}else if(drawerCode){if(D.byCode.has(drawerCode)&&!D.deleted?.some(x=>x.code===drawerCode))openClient(drawerCode,true);else closeDrawer();}
 $('#splash')?.classList.add('gone');
}
// ───────────────────────── shared server: session, live updates, offline
// owner = administrator with unlimited powers: the page treats them as admin, data-owner unlocks what only they can do
function applyRole(){const r=WEB?(me?.role||'none'):'admin';document.documentElement.dataset.role=r==='owner'?'admin':r;if(r==='owner')document.documentElement.dataset.owner='1';else delete document.documentElement.dataset.owner;
 const foot=$('.company');if(WEB&&me){foot.innerHTML=`<span class="avatar">${esc(initials(me.name))}</span><div><strong>${esc(me.name)}</strong><small id="side-status">${esc(me.roleLabel)}</small></div>`;}}
let loadTimer=0,loading=false,again=false;
function scheduleLoad(){clearTimeout(loadTimer);loadTimer=setTimeout(loadState,250);}
// raw: the last complete archive as received (pristine sections + their fingerprints). With it, only the sections that changed are downloaded.
let raw=null,saveTimer=0,earlyState=null;
const haveHeader=()=>Object.entries(raw.hashes).map(([k,h])=>k+'='+h).join(',');
// the merged copy replaces the saved one used when the server cannot be reached (written when the page is idle)
function saveRaw(){if(!('caches' in window)||!isSecureContext)return;clearTimeout(saveTimer);saveTimer=setTimeout(()=>{const body=JSON.stringify(raw);
 caches.open('tcs-api').then(c=>c.put('/api/state',new Response(body,{headers:{'Content-Type':'application/json'}}))).catch(()=>{});},3000);}
async function loadState(){if(loading){again=true;return;}loading=true;
 try{const partial=!!raw?.hashes;const h={};if(etag)h['If-None-Match']=etag;if(partial)h['X-Have']=haveHeader();
  const early=!partial&&!etag?await earlyState:null;earlyState=null; // started together with the session check
  const r=early&&early.status!==401?early:await fetch(partial?'/api/state/delta':'/api/state',{credentials:'same-origin',cache:'no-store',headers:h});
  if(r.status===401){showAuth('login');return;}
  if(r.status===304){setOffline(false);return;}
  if(partial&&r.status===404){raw=null;again=true;return;} // older server without partial updates
  if(!r.ok)throw new Error('HTTP '+r.status);
  const s=await r.json();etag=r.headers.get('ETag')||'';setOffline(r.headers.get('X-From-Cache')==='1');
  let v;
  if(s.partial){delete s.partial;const fresh=Object.keys(s);raw={...raw,...s};
   // unchanged sections are handed over as copies: the views may decorate the objects they receive, the pristine copy stays clean
   v={...s};for(const k of Object.keys(raw))if(!fresh.includes(k))v[k]=structuredClone(raw[k]);saveRaw();}
  else{raw=s;v=structuredClone(s);}
  // a problem while drawing the data is not a lost connection: report it, keep the "online" state
  try{receive({type:'state',...v,syncRunning:state.syncRunning,syncProgress:state.syncProgress,portalConnected:state.portalConnected});}catch(e){console.error('Errore nella visualizzazione dei dati',e);}
 }catch{setOffline(true);if(firstState)$('#splash')?.classList.add('gone');}
 finally{loading=false;if(again){again=false;scheduleLoad();}}}
let es=null;
function openStream(){es?.close();es=new EventSource('/api/stream');
 es.onmessage=e=>{const m=JSON.parse(e.data);if(m.type==='changed'||m.type==='hello'){if(m.type==='hello')setOffline(false);if(String(m.version)!==(etag.match(/v(\d+)/)||[])[1])scheduleLoad();}else if(m.type==='progress')progress(m);};
 // EventSource reconnects by itself: only report "offline" if it stays down for a few seconds (no flicker on Wi-Fi blips).
 es.onerror=()=>{clearTimeout(offTimer);offTimer=setTimeout(()=>{if(es.readyState!==1)setOffline(true);},4000);};es.onopen=()=>{clearTimeout(offTimer);setOffline(false);scheduleLoad();};}
let offTimer=0;
function setOffline(v){if(offline===v)return;offline=v;document.documentElement.classList.toggle('offline',v);const b=$('#offline-bar');if(b)b.hidden=!v;}
addEventListener('online',()=>{scheduleLoad();if(es?.readyState===2)openStream();});
// Coming back to the app (phone unlocked, tab focused): refresh at once instead of waiting.
document.addEventListener('visibilitychange',()=>{if(WEB&&me&&document.visibilityState==='visible'){scheduleLoad();if(!es||es.readyState===2)openStream();}});

function showAuth(kind,err=''){
 $('#splash')?.classList.add('gone');es?.close();const a=$('#auth');a.hidden=false;
 const setup=kind==='setup';
 a.innerHTML=`<div class="auth-stack"><form class="auth-card" id="auth-form" autocomplete="on">
  <img src="assets/tcs-app@2x.png" alt="Top Cash Service" class="auth-logo">
  <h1>${setup?'Configura Top Cash Studio':'Accedi a Top Cash Studio'}</h1>
  <p>${setup?'Crea l’account <strong>amministratore</strong>. Potrai poi aggiungere i colleghi da <em>Utenti</em>.':'Usa le credenziali che ti ha dato l’amministratore.'}</p>
  ${setup?`<label class="field">Nome e cognome<input class="input" name="name" required maxlength="80" autocomplete="name"></label>`:''}
  <label class="field">Nome utente<input class="input" name="username" required autocomplete="username" autocapitalize="none" spellcheck="false"></label>
  <label class="field">Password<input class="input" name="password" type="password" required autocomplete="${setup?'new-password':'current-password'}" ${setup?'minlength="10"':''}></label>
  ${setup?`<label class="field">Ripeti la password<input class="input" name="password2" type="password" required autocomplete="new-password"></label><p class="muted" style="font-size:12px">Almeno 10 caratteri. Scegli una password che non usi altrove.</p>`:`<label class="check"><input type="checkbox" name="remember" checked> Resta connesso su questo dispositivo</label>`}
  <div class="auth-err" role="alert">${esc(err)}</div>
  <button class="btn accent" type="submit">${setup?'Crea amministratore':'Accedi'} ${icon('arrow')}</button>
 </form><div id="auth-get" hidden></div></div>`;
 if(!setup&&typeof authGetApp==='function')authGetApp();
 setTimeout(()=>a.querySelector('input')?.focus(),50);
 $('#auth-form').addEventListener('submit',async e=>{e.preventDefault();const f=Object.fromEntries(new FormData(e.target));const btn=e.target.querySelector('button');
  if(setup&&f.password!==f.password2){e.target.querySelector('.auth-err').textContent='Le due password non coincidono.';return;}
  btn.disabled=true;try{me=await post(setup?'/api/setup':'/api/login',setup?{name:f.name,username:f.username,password:f.password}:{username:f.username,password:f.password,remember:!!f.remember,deviceName:deviceName(),platform:devicePlatform()});startWeb();}
  catch(x){e.target.querySelector('.auth-err').textContent=x.message;btn.disabled=false;e.target.querySelector('[name=password]').select();}});
}
// ───────────────────────── devices (outside the office: credentials are enough, a blocked device stays out)
function deviceName(){const u=navigator.userAgent;const os=/iPhone/.test(u)?'iPhone':/iPad/.test(u)?'iPad':/Android/.test(u)?'Android':/Mac OS/.test(u)?'Mac':/Windows/.test(u)?'Windows':'Dispositivo';
 const br=host?'App Top Cash':/Edg\//.test(u)?'Edge':/Chrome\//.test(u)?'Chrome':/Firefox\//.test(u)?'Firefox':/Safari\//.test(u)?'Safari':'Browser';return os+' · '+br;}
const devicePlatform=()=>host?'Windows (app)':'Browser';
let devTimer=0;
// administrators: devices asking for access from outside the office (counter on Utenti, a toast when a new one arrives).
// Asked at most every 20 s, with a last check after a burst of changes so a new request is never missed.
let pendLast=-1,pendAt=0,pendTimer=0;
function checkDevices(){if(!WEB||!me||!isAdmin())return;const wait=20000-(Date.now()-pendAt);
 if(wait>0){if(!pendTimer)pendTimer=setTimeout(()=>{pendTimer=0;checkDevices();},wait);return;}
 pendAt=Date.now();api('/api/devices/pending').then(r=>{const n=r?.count||0,el=$('#nav-team');if(el)el.textContent=n||'';
  if(pendLast>=0&&n>pendLast)toast(n===1?'Un dispositivo chiede l’accesso da fuori ufficio: autorizzalo in Utenti → Dispositivi':`${n} dispositivi chiedono l’accesso da fuori ufficio: autorizzali in Utenti → Dispositivi`,'shield');
  pendLast=n;}).catch(()=>{});}
// ───────────────────────── on-screen keyboard (phones and tablets)
// The field being typed in must never hide behind the keyboard: the page knows how much of the screen the keyboard
// covers (--kb, for browsers that do not shrink the page themselves) and brings the focused field to the middle of
// what is left, inside its sheet / dialog / page, once the keyboard has finished opening.
(()=>{const vv=window.visualViewport;if(!vv||!matchMedia('(pointer:coarse)').matches)return;const root=document.documentElement;let raf=0,base=innerHeight;
 const typing=()=>{const a=document.activeElement;return!!a&&(a.isContentEditable||/^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName))&&!/^(checkbox|radio|button|submit|range|color|file)$/.test(a.type||'');};
 const update=()=>{raf=0;if(!typing())base=Math.max(base,innerHeight);
  const kb=Math.max(0,Math.round(innerHeight-vv.height-vv.offsetTop));const open=typing()&&(kb>80||innerHeight<base-120);
  root.style.setProperty('--kb',(kb>80?kb:0)+'px');root.classList.toggle('kb-open',open);};
 const sched=()=>{if(!raf)raf=requestAnimationFrame(update);};
 vv.addEventListener('resize',sched);vv.addEventListener('scroll',sched);addEventListener('resize',sched);
 const reveal=el=>{if(!el?.isConnected||document.activeElement!==el)return;const r=el.getBoundingClientRect(),top=vv.offsetTop+56,bottom=vv.offsetTop+vv.height-16;
  if(r.top<top||r.bottom>bottom)el.scrollIntoView({block:'center',inline:'nearest',behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'instant':'smooth'});};
 document.addEventListener('focusin',e=>{const el=e.target;if(!typing())return;sched();
  // the keyboard animates in for ~250-350 ms: check once it is up, and again if the visible area changes after that
  setTimeout(()=>reveal(el),320);const once=()=>{vv.removeEventListener('resize',once);setTimeout(()=>reveal(el),60);};vv.addEventListener('resize',once);setTimeout(()=>vv.removeEventListener('resize',once),1200);});
 document.addEventListener('focusout',()=>setTimeout(sched,60));
})();

function showDevice(status,code){
 $('#splash')?.classList.add('gone');es?.close();clearTimeout(devTimer);const a=$('#auth');a.hidden=false;const blocked=status==='revoked';
 a.innerHTML=`<div class="auth-card device-card">
  <img src="assets/tcs-app@2x.png" alt="Top Cash Service" class="auth-logo">
  <h1>${blocked?'Dispositivo bloccato':'Autorizza questo dispositivo'}</h1>
  <p>${blocked?'L’amministratore ha bloccato l’accesso da fuori ufficio per questo dispositivo. Se è un errore, comunicagli questo codice: può sbloccarlo da <strong>Utenti → Dispositivi</strong>.':'Per sicurezza, da fuori ufficio Top Cash Studio si apre solo sui dispositivi approvati. Comunica questo codice a un amministratore: lo trova in <strong>Utenti → Dispositivi</strong>. Serve una volta sola per dispositivo.'}</p>
  ${code?`<div class="device-code" aria-label="Codice dispositivo">${esc(code)}</div>`:''}
  ${blocked?'':`<p class="device-wait"><span class="spinner"></span>In attesa dell’approvazione: la pagina si apre da sola.</p>`}
  <button class="btn ghost" type="button" id="dev-retry">${icon('sync')}Controlla ora</button></div>`;
 const check=async()=>{try{const h=await post('/api/device/hello',{name:deviceName(),platform:devicePlatform()});if(h.status==='approved'||!h.remote)return location.reload();if(h.status!==status||h.code!==code)return showDevice(h.status,h.code);}catch{}if(!blocked)devTimer=setTimeout(check,8000);};
 $('#dev-retry').addEventListener('click',()=>{clearTimeout(devTimer);check();});
 if(!blocked)devTimer=setTimeout(check,code?8000:0); // no code yet (a parallel request arrived before the device cookie): ask for it at once
}
function startWeb(){$('#auth').hidden=true;applyRole();etag='';raw=null;loadState();openStream();if(host)host.postMessage({action:'hostInfo'});learnPublic();}
async function logout(){try{await post('/api/logout');}catch{}try{await caches.delete('tcs-api');}catch{}me=null;location.reload();}
async function bootWeb(){
 if('serviceWorker' in navigator&&isSecureContext){
  // sw.js never from the HTTP cache (a proxy may keep it for hours); checked again whenever the page comes back to the front
  const had=!!navigator.serviceWorker.controller;let reloading=false;
  navigator.serviceWorker.register('sw.js',{updateViaCache:'none'}).then(reg=>{document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')reg.update().catch(()=>{});});}).catch(()=>{});
  // a new version took over: reload once, but never under an open form (then at the next return to the page)
  const swap=()=>{if(reloading)return;if(document.querySelector('.modal:not(.out),.drawer:not(.out) form')){document.addEventListener('visibilitychange',function v(){if(document.visibilityState==='visible'){document.removeEventListener('visibilitychange',v);swap();}});return;}reloading=true;location.reload();};
  navigator.serviceWorker.addEventListener('controllerchange',()=>{if(had)swap();});
 }
 // the three first requests leave together: one round trip instead of three (it counts outside the office, through the tunnel)
 earlyState=fetch('/api/state',{credentials:'same-origin',cache:'no-store'}).catch(()=>null);
 const hello=post('/api/device/hello',{name:deviceName(),platform:devicePlatform()}).catch(()=>null),session=api('/api/session');session.catch(()=>{});
 try{const h=await hello;if(h?.remote&&h.status!=='approved'){earlyState=null;return showDevice(h.status,h.code);} // no answer (server down, no network): go on with the saved copy, the offline bar says so
  const s=await session;if(s.setupNeeded)return showAuth('setup');if(!s.user)return showAuth('login');me=s.user;startWeb();}
 catch{$('#splash')?.classList.add('gone');$('#main').innerHTML=`<div class="error-box"><img src="assets/tcs-app@2x.png" alt="Top Cash Service" class="tile"><h2>Server non raggiungibile</h2><p>Il server dell’ufficio non risponde: può essere spento o senza connessione. Controlla anche la tua rete.</p><p class="muted" style="font-size:13px;margin-top:6px">Riprovo da solo ogni 20 secondi.</p><p style="margin-top:16px"><button class="btn accent" data-action-local="reload">Riprova ora</button></p></div>`;setTimeout(()=>location.reload(),20000);}
}

{const h=location.hash.slice(1);if(PAGES[h])page=h;} // PWA shortcuts (#clients, #expiry)
if(WEB){if(host)host.addEventListener('message',e=>receive(e.data));bootWeb();}
else if(host){host.addEventListener('message',e=>receive(e.data));send('ready');send('hostInfo');}
else if(window.__PREVIEW_STATE){setTimeout(()=>receive(window.__PREVIEW_STATE),400);}
else{setTimeout(()=>{$('#splash')?.classList.add('gone');$('#main').innerHTML=`<div class="error-box"><img src="assets/tcs-app@2x.png" alt="Top Cash Service" class="tile"><h2>Apri l’app Windows</h2><p>Avvia <strong>TopCash.Licenze.exe</strong> per consultare il database dei clienti.</p></div>`;},600);}

// ───────────────────────── 2.9.0 photos of an intervention: taken on the phone or added from the PC, shrunk before sending
const photoUrl=id=>'/api/photos/'+encodeURIComponent(id)+'.jpg';
const photosOf=id=>state.photos?.[id]||[];
const canDropPhoto=(p,it)=>!it?.signedAt&&(canAccounting()||norm(p.by)===norm(me?.name||''));
function photoTile(p,it){return`<div class="photo" data-photo="${esc(p.id)}"><button type="button" class="photo-open" data-photo-open="${esc(p.id)}" aria-label="Apri la foto"><img src="${photoUrl(p.id)}" loading="lazy" alt="Foto dell'intervento" decoding="async"></button>${canDropPhoto(p,it)?`<button type="button" class="photo-x" data-photo-del="${esc(p.id)}" aria-label="Togli la foto" data-tip="Togli la foto">${icon('close')}</button>`:''}</div>`;}
function photoSection(it){
 if(!WEB)return'';
 if(!it)return`<div class="field photos-field"><span class="field-head">Foto</span><p class="muted photo-hint">${icon('camera')}Salva l’intervento, poi riaprilo per aggiungere le foto.</p></div>`;
 if(it.source!=='app')return'';
 const ps=photosOf(it.id);const signed=!!it.signedAt;
 if(signed&&!ps.length)return'';
 return`<div class="field photos-field"><span class="field-head"><span>Foto <span class="count" id="photo-n">${ps.length||''}</span></span><small class="muted">${signed?'sono nel PDF del rapportino':'prima e dopo il lavoro: finiscono anche nel PDF'}</small></span>
  <div class="photo-grid" id="int-photos">${ps.map(p=>photoTile(p,it)).join('')}${signed?'':`<label class="photo-add" data-tip="Scatta o scegli foto (anche più di una)"><input type="file" accept="image/*" multiple hidden>${icon('camera')}<span>Aggiungi</span></label>`}</div></div>`;
}
/** Resizes to at most 1600 px on the long side (JPEG 82%): a phone photo of 4 MB becomes ~300 KB. */
async function shrinkPhoto(file){
 let bmp;try{bmp=await createImageBitmap(file,{imageOrientation:'from-image'});}catch{throw new Error('Formato non supportato: usa una foto JPEG o PNG.');}
 const k=Math.min(1,1600/Math.max(bmp.width,bmp.height));const w=Math.round(bmp.width*k),h=Math.round(bmp.height*k);
 const cv=document.createElement('canvas');cv.width=w;cv.height=h;const g=cv.getContext('2d');g.fillStyle='#fff';g.fillRect(0,0,w,h);g.drawImage(bmp,0,0,w,h);bmp.close?.();
 const blob=await new Promise(r=>cv.toBlob(r,'image/jpeg',.82));if(!blob)throw new Error('Foto non leggibile.');
 return await new Promise((res,rej)=>{const fr=new FileReader();fr.onload=()=>res(fr.result);fr.onerror=()=>rej(new Error('Foto non leggibile.'));fr.readAsDataURL(blob);});
}
function bindPhotos(box,it){
 const grid=box.querySelector('#int-photos');if(!grid)return;
 const count=()=>{const n=grid.querySelectorAll('.photo:not(.busy)').length;const e=box.querySelector('#photo-n');if(e)e.textContent=n||'';};
 grid.addEventListener('change',async e=>{const inp=e.target.closest('input[type=file]');if(!inp)return;const files=[...inp.files];inp.value='';if(!files.length)return;
  const add=grid.querySelector('.photo-add');let ok=0;
  for(const f of files){const ph=document.createElement('div');ph.className='photo busy';ph.innerHTML='<span class="spin"></span>';grid.insertBefore(ph,add);
   try{const data=await shrinkPhoto(f);const p=await post('/api/interventions/'+encodeURIComponent(it.id)+'/photos',{data});
    (state.photos||={})[it.id]=[...photosOf(it.id),p];const tmp=document.createElement('div');tmp.innerHTML=photoTile(p,it);ph.replaceWith(tmp.firstElementChild);ok++;}
   catch(x){ph.remove();toast(x.message,'alert');break;}
   count();}
  if(ok)toast(ok===1?'Foto aggiunta':ok+' foto aggiunte','camera');});
 grid.addEventListener('click',async e=>{
  const del=e.target.closest('[data-photo-del]');
  if(del){e.preventDefault();e.stopPropagation();if(!await ask({title:'Togliere la foto?',text:'La foto viene tolta dall’intervento per tutti.',ok:'Togli',danger:true}))return;
   try{await post('/api/photos/'+encodeURIComponent(del.dataset.photoDel)+'/delete');const tile=del.closest('.photo');tile.classList.add('gone');setTimeout(()=>{tile.remove();count();},220);
    if(state.photos?.[it.id])state.photos[it.id]=state.photos[it.id].filter(p=>p.id!==del.dataset.photoDel);toast('Foto tolta','trash');}catch(x){toast(x.message,'alert');}return;}
  const op=e.target.closest('[data-photo-open]');if(op){e.preventDefault();openPhoto(it,op.dataset.photoOpen);}});
}
function openPhoto(it,id){
 const list=photosOf(it.id);let i=Math.max(0,list.findIndex(p=>p.id===id));if(!list.length)return;
 const v=document.createElement('div');v.className='lightbox';v.setAttribute('role','dialog');v.setAttribute('aria-label','Foto');
 const draw=()=>{const p=list[i];v.innerHTML=`<img src="${photoUrl(p.id)}" alt="Foto ${i+1} di ${list.length}"><div class="lb-bar"><span>${i+1} / ${list.length} · ${esc(p.by)}${p.at?' · '+dTime(p.at):''}</span><a class="btn sm" href="${photoUrl(p.id)}" download="foto-${esc(p.id)}.jpg">${icon('download')}Scarica</a><button class="btn sm" data-lb="x">${icon('close')}Chiudi</button></div>${list.length>1?`<button class="lb-nav prev" data-lb="-1" aria-label="Foto precedente">${icon('chevron')}</button><button class="lb-nav next" data-lb="1" aria-label="Foto successiva">${icon('chevron')}</button>`:''}`;};
 const close=()=>{v.classList.add('out');setTimeout(()=>v.remove(),200);document.removeEventListener('keydown',key,true);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();e.preventDefault();close();}else if(e.key==='ArrowRight'){i=(i+1)%list.length;draw();}else if(e.key==='ArrowLeft'){i=(i-1+list.length)%list.length;draw();}};
 v.addEventListener('click',e=>{const b=e.target.closest('[data-lb]');if(e.target===v||b?.dataset.lb==='x')return close();if(b){i=(i+ +b.dataset.lb+list.length)%list.length;draw();}});
 document.addEventListener('keydown',key,true);draw();document.body.append(v);
}
// ───────────────────────── 2.9.0 the round of the day: stops marked as done (shared with the team, kept for the day)
function routeIsDone(s){return!!s.key&&state.routeDone?.[s.key]?.day===isoDay(today);}
document.addEventListener('click',e=>{const b=e.target.closest?.('[data-route-done]');if(!b)return;e.preventDefault();e.stopPropagation();
 const k=b.dataset.routeDone,on=!(state.routeDone?.[k]?.day===isoDay(today));state.routeDone={...(state.routeDone||{})};
 if(on)state.routeDone[k]={day:isoDay(today),at:new Date().toISOString(),by:me?.name||''};else delete state.routeDone[k];
 const row=b.closest('.list-row');if(row){row.classList.toggle('done',on);b.classList.toggle('on',on);b.setAttribute('aria-pressed',on);}
 clearTimeout(routeDoneTimer);routeDoneTimer=setTimeout(()=>render(false),on?420:0); // the tick shows, then the stop moves to the bottom of its place
 if(WEB)post('/api/route/done',{key:k,done:on}).catch(x=>{toast(x.message,'alert');});else send('routeDone',{key:k,done:on});
 toast(on?'Tappa fatta':'Tappa di nuovo da fare',on?'check':'repeat');},true);
var routeDoneTimer;
// ───────────────────────── 2.9.0 weekly summary by e-mail (administrators)
var weeklyCfg=null;
function weeklyCard(i){if(!WEB||!(me?.role==='admin'||me?.role==='owner'))return'';
 if(!weeklyCfg){api('/api/settings/weekly').then(d=>{weeklyCfg=d;if(page==='settings')render(false);}).catch(()=>{});return`<div class="card reveal" style="--i:${i}"><h2>Riepilogo settimanale</h2><p class="sub">Caricamento…</p></div>`;}
 const w=weeklyCfg;
 return`<form class="card reveal" style="--i:${i}" id="weekly-form"><h2>Riepilogo settimanale ${w.enabled?'<span class="badge ok">attivo</span>':'<span class="badge">non attivo</span>'}</h2>
  <p class="sub">Ogni lunedì mattina un’email con interventi aperti (urgenti per primi), chiusi della settimana, verificazioni dei prossimi 30 giorni, macchine da controllare, licenze in scadenza e magazzino sotto scorta. Parte dalla casella di “Email rapportini”.</p>
  <label class="field">Destinatari<input class="input" name="to" value="${esc(w.to||'')}" placeholder="ufficio@topcash.it, titolare@topcash.it" maxlength="400"></label>
  <label class="check"><input type="checkbox" name="enabled" ${w.enabled?'checked':''}> Invia il riepilogo ogni lunedì</label>
  ${w.lastSent?`<div class="stat-line"><span>Ultimo invio</span><strong>${dTime(w.lastSent)}</strong></div>`:''}
  ${w.lastError?`<p class="sub" style="color:var(--bad)">${esc(w.lastError)}</p>`:''}
  ${mailOn()?'':`<p class="sub" style="color:var(--due)">Prima attiva “Email rapportini”: il riepilogo parte da quella casella.</p>`}
  <div class="btn-row" style="margin-top:12px"><button type="button" class="btn sm ghost" data-weekly-now>${icon('mail')}Invia adesso</button><button class="btn sm accent" type="submit">${icon('check')}Salva</button></div></form>`;}
document.addEventListener('submit',async e=>{if(e.target.id!=='weekly-form')return;e.preventDefault();const f=new FormData(e.target),b=e.target.querySelector('[type=submit]');b.disabled=true;
 try{weeklyCfg=await post('/api/settings/weekly',{enabled:f.get('enabled')==='on',to:f.get('to')||''});toast(weeklyCfg.enabled?'Riepilogo settimanale attivo: arriva ogni lunedì':'Riepilogo settimanale salvato','mail');render(false);}catch(x){toast(x.message,'alert');b.disabled=false;}});
document.addEventListener('click',async e=>{const b=e.target.closest?.('[data-weekly-now]');if(!b)return;const to=b.closest('form')?.querySelector('[name=to]')?.value.trim()||'';
 if(!to){toast('Scrivi prima i destinatari','alert');return;}b.disabled=true;b.classList.add('busy');
 try{await post('/api/settings/weekly/send',{enabled:true,to});toast('Riepilogo inviato a '+to,'mail');}catch(x){toast(x.message,'alert');}b.disabled=false;b.classList.remove('busy');});
