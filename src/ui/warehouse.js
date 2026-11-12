'use strict';
/* Top Cash Studio — Magazzino. Loaded after app.js; uses its helpers and state.
   One warehouse ("sede"): products are entered by hand, loads and unloads are movements,
   the spare parts of an intervention are unloaded by themselves. */

const WH='sede';
const MV_LABEL={carico:'Carico',scarico:'Scarico',trasferimento:'Trasferimento',rettifica:'Rettifica',inventario:'Inventario',intervento:'Intervento'};
const MV_CLS={carico:'ok',scarico:'due',trasferimento:'info',rettifica:'acc',inventario:'acc',intervento:'info'};
const eur=n=>new Intl.NumberFormat('it-IT',{useGrouping:'always',style:'currency',currency:'EUR',maximumFractionDigits:n>=1000?0:2}).format(n||0);
const qf=n=>new Intl.NumberFormat('it-IT',{useGrouping:'always',maximumFractionDigits:3}).format(n||0);
let W=null; // derived warehouse data
let wui={tab:'stock',cat:'',only:'',invCounts:{}};

function deriveWarehouse(){
 const arts=(state.articles||[]).slice().sort((a,b)=>a.name.localeCompare(b.name,'it'));
 const stock=new Map();for(const r of state.stock||[]){const k=r.article.toUpperCase();stock.set(k,(stock.get(k)||0)+r.qty);}
 const levels=new Map();for(const l of state.stockLevels||[])if(l.warehouse===WH)levels.set(l.article.toUpperCase(),l);
 const moves=(state.movements||[]).map(m=>({...m,d:new Date(m.date)}));
 // weighted average cost of loads; catalogue cost when nothing was loaded with a price
 const costAcc=new Map();for(const m of moves)if(m.type==='carico'&&m.unitCost>0){const k=m.article.toUpperCase();const c=costAcc.get(k)||{q:0,v:0};c.q+=m.qty;c.v+=m.qty*m.unitCost;costAcc.set(k,c);}
 const items=arts.map(a=>{const k=a.code.toUpperCase();const total=stock.get(k)||0;
  const c=costAcc.get(k);const cost=c&&c.q>0?c.v/c.q:(a.cost||0);const level=levels.get(k)||null;
  const below=!!level&&total<=level.min;
  const status=total<0?'Da verificare':total<=0?'Esaurito':below?'Sotto scorta':'Disponibile';
  return{...a,key:k,total,cost,value:Math.max(0,total)*cost,level,below,status,search:norm([a.code,a.name,a.barcode,a.category,a.supplier,a.supplierCode].join(' '))};});
 const byCode=new Map(items.map(i=>[i.key,i]));const byBarcode=new Map(items.filter(i=>i.barcode).map(i=>[i.barcode,i]));
 const cats=[...new Set(items.map(i=>i.category).filter(Boolean))].sort((a,b)=>a.localeCompare(b,'it'));
 W={items,byCode,byBarcode,moves,cats,value:items.reduce((s,i)=>s+i.value,0),below:items.filter(i=>i.below&&i.active!==false)};
}
const stCls=s=>({'Disponibile':'ok','Sotto scorta':'due','Esaurito':'bad','Da verificare':'bad'}[s]||'');
function findArticle(text){const t=String(text||'').trim();if(!t)return null;return W.byBarcode.get(t)||W.byCode.get(t.toUpperCase())||W.items.find(i=>i.name===t)||null;}
// write helper: shared server (REST) or local archive (Windows host)
async function wsave(action,data,path,body,okMsg){
 if(WEB){try{const r=await post(path,body);if(okMsg)toast(okMsg);return r;}catch(e){toast(e.message,'alert');throw e;}}
 send(action,data);return null;
}
const canManageWh=()=>!WEB||me?.role!=='tecnico';

function warehousePage(){
 if(!W)deriveWarehouse();
 const canManage=canManageWh();
 const tabs=[['stock','Giacenze'],['moves','Movimenti'],['reorder',`Riordino${W.below.length?' · '+W.below.length:''}`],...(canManage?[['inventory','Inventario']]:[])];
 if(!tabs.some(([k])=>k===wui.tab))wui.tab='stock';
 const body={stock:stockTab,moves:movesTab,reorder:reorderTab,inventory:inventoryTab}[wui.tab];
 const m30=W.moves.filter(m=>(Date.now()-m.d)/864e5<=30).length;
 return`${hero('Il <span class="grad">magazzino</span>.','Ricambi e consumabili: giacenze, carichi e scarichi, scorte minime e riordino.',
  `<button class="btn" data-wmove="scarico">${icon('upload')}Scarico</button><button class="btn accent" data-wmove="carico">${icon('download')}Carico</button>`)}
 ${explain('warehouse','Inserisci i prodotti con <strong>Nuovo articolo</strong>, registra i <strong>carichi</strong> quando arriva la merce: i ricambi usati negli <strong>interventi</strong> si scaricano da soli. Imposta la <strong>scorta minima</strong> di un articolo per vederlo nel riordino. Con un lettore di codici a barre basta scansionare nel campo di ricerca.')}
 <div class="kpis">${kpi('Articoli',W.items.filter(i=>i.active!==false).length,`${fmt(W.items.filter(i=>i.total>0).length)} disponibili`,'bag','','warehouse',2)}
  <div class="card kpi t-ok reveal" style="--i:3"><div class="kpi-top"><span>Valore magazzino</span><span class="kpi-icon">${icon('key')}</span></div><div class="kpi-value" style="font-size:28px">${eur(W.value)}</div><div class="kpi-note">a costo medio di carico</div></div>
  ${kpi('Sotto scorta',W.below.length,W.below.length?'da riordinare':'scorte in ordine','alert',W.below.length?'t-due':'t-ok','warehouse',4)}
  ${kpi('Movimenti · 30 gg',m30,'carichi, scarichi e ricambi','sync','t-info','warehouse',5)}</div>
 <div class="toolbar reveal" style="--i:6"><div class="seg">${tabs.map(([k,l])=>`<button class="${wui.tab===k?'active':''}" data-wtab="${k}">${l}</button>`).join('')}</div>
  <span style="flex:1"></span>${canManage?`<button class="btn sm need-export" data-wnew="article">${icon('plus')}Nuovo articolo</button>`:''}</div>
 ${body(canManage)}`;
}

function stockTab(canManage){
 const q=norm(ui.q);let list=W.items;
 if(q)list=list.filter(i=>i.search.includes(q));
 if(wui.cat)list=list.filter(i=>i.category===wui.cat);
 if(wui.only==='below')list=list.filter(i=>i.below);else if(wui.only==='zero')list=list.filter(i=>i.total<=0);else if(wui.only==='avail')list=list.filter(i=>i.total>0);
 if(!['name','qty','value','code'].includes(ui.sort.key))ui.sort={key:'name',dir:1};
 list=sortBy(list,{name:i=>i.name,code:i=>i.code,qty:i=>i.total,value:i=>i.value});
 const rows=pageOf(list,16);
 return`<div class="card reveal" style="--i:7"><div class="card-head"><div><h2>Giacenze<span class="count">${fmt(list.length)}</span></h2><p>Tocca un articolo per i dettagli, la scorta minima e i movimenti.</p></div></div>
 <div class="toolbar">${search('Cerca codice, descrizione, codice a barre…')}
  ${W.cats.length?`<select class="select" id="f-wcat"><option value="">Tutte le categorie</option>${W.cats.map(c=>`<option${wui.cat===c?' selected':''}>${esc(c)}</option>`).join('')}</select>`:''}
  <select class="select" id="f-wonly"><option value="">Tutti</option><option value="avail"${wui.only==='avail'?' selected':''}>Disponibili</option><option value="below"${wui.only==='below'?' selected':''}>Sotto scorta</option><option value="zero"${wui.only==='zero'?' selected':''}>Esauriti</option></select></div>
 <div class="table-wrap"><table class="t-stock"><thead><tr>${th('Articolo','name')}<th>Categoria</th>${th('Quantità','qty')}${th('Valore','value')}<th>Stato</th><th></th></tr></thead><tbody>
 ${rows.map((i,k)=>`<tr class="reveal" style="--i:${k}" data-art="${esc(i.code)}" tabindex="0"><td><div class="who"><span class="mark" style="--h:${hue(i.category||i.code)}">${icon(i.serialized?'register':'bag')}</span><div><strong>${hl(i.name,ui.q)}</strong><small class="mono">${hl(i.code,ui.q)}${i.barcode?' · '+hl(i.barcode,ui.q):''}</small></div></div></td>
  <td>${i.category?`<span class="chip">${esc(i.category)}</span>`:'<span class="muted">—</span>'}</td>
  <td class="mono"><strong>${qf(i.total)}</strong> <span class="muted">${esc(i.unit)}</span>${i.level?`<br><small class="muted">min ${qf(i.level.min)}</small>`:''}</td><td class="mono">${eur(i.value)}</td><td><span class="badge ${stCls(i.status)}">${esc(i.status)}</span></td><td><span class="row-go">${icon('chevron')}</span></td></tr>`).join('')
  ||`<tr><td colspan="6">${empty('bag',W.items.length?'Nessun articolo trovato':'Magazzino vuoto',W.items.length?'Modifica la ricerca o i filtri.':canManage?'Inserisci il primo prodotto con Nuovo articolo.':'L’ufficio non ha ancora inserito prodotti.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,16)}</div>`;
}

function movesTab(){
 const q=norm(ui.q);let list=W.moves;
 if(q)list=list.filter(m=>norm([m.article,W.byCode.get(m.article.toUpperCase())?.name,m.notes,m.user,m.serial].join(' ')).includes(q));
 const rows=pageOf(list,18);
 // signed quantity: what entered the warehouse is +, what left is −
 const sign=m=>m.to&&!m.from?1:m.from&&!m.to?-1:0;
 return`<div class="card reveal" style="--i:7"><div class="card-head"><div><h2>Movimenti<span class="count">${fmt(list.length)}</span></h2><p>Registro completo: chi ha caricato o scaricato cosa, e quando.</p></div></div>
 <div class="toolbar">${search('Cerca articolo, nota, matricola, utente…')}</div>
 <div class="table-wrap"><table><thead><tr><th>Data</th><th>Tipo</th><th>Articolo</th><th>Quantità</th><th>Note</th><th>Utente</th></tr></thead><tbody>
 ${rows.map((m,k)=>{const a=W.byCode.get(m.article.toUpperCase());const s=sign(m);return`<tr class="reveal" style="--i:${k}" ${m.interventionId?`data-int="${esc(m.interventionId)}"`:`data-art="${esc(m.article)}"`} tabindex="0"><td>${dTime(m.date)}</td><td><span class="badge ${MV_CLS[m.type]||''}">${MV_LABEL[m.type]||m.type}</span></td>
  <td><strong style="font-weight:560">${esc(a?.name||m.article)}</strong><br><small class="muted mono">${esc(m.article)}${m.serial?' · matr. '+esc(m.serial):''}</small></td><td class="mono" style="color:var(--${s>0?'ok':s<0?'due':'text'})">${s>0?'+':s<0?'−':''}${qf(m.qty)} ${esc(a?.unit||'')}</td>
  <td>${esc(m.notes||'')}${m.clientCode&&D.byCode.get(m.clientCode)?'<br><small class="muted">'+esc(D.byCode.get(m.clientCode).name)+'</small>':''}</td><td>${esc(m.user)}</td></tr>`;}).join('')
  ||`<tr><td colspan="6">${empty('sync','Nessun movimento','I carichi, gli scarichi e i ricambi degli interventi compariranno qui.')}</td></tr>`}
 </tbody></table></div>${pager(list.length,18)}</div>`;
}

function reorderTab(canManage){
 const lines=W.below.map(i=>({i,q:i.total,l:i.level,order:Math.max(i.level.max>0?i.level.max:i.level.min*2,i.level.min)-i.total}));
 const bySup=new Map();for(const r of lines){const s=r.i.supplier||'Senza fornitore';if(!bySup.has(s))bySup.set(s,[]);bySup.get(s).push(r);}
 return`<div class="card reveal" style="--i:7"><div class="card-head"><div><h2>Proposta di riordino<span class="count">${fmt(lines.length)}</span></h2><p>Articoli arrivati alla scorta minima, con la quantità per tornare alla massima. Raggruppati per fornitore.</p></div>
  ${canManage&&lines.length?`<button class="btn sm need-export" data-action="exportReorder">${icon('download')}Esporta CSV per gli ordini</button>`:''}</div>
 ${[...bySup].map(([s,rs])=>`<div class="month"><div class="month-head"><h3>${esc(s)}</h3><span>${rs.length} righe</span></div>${rs.map(r=>`<div class="list-row" data-art="${esc(r.i.code)}"><div class="grow"><strong>${esc(r.i.name)}</strong><small class="mono">${esc(r.i.code)}${r.i.supplierCode?' · cod. forn. '+esc(r.i.supplierCode):''} · ci sono ${qf(r.q)}, minimo ${qf(r.l.min)}</small></div><strong class="mono">+${qf(r.order)} ${esc(r.i.unit)}</strong></div>`).join('')}</div>`).join('')
  ||empty('check','Scorte in ordine','Nessun articolo è sotto la scorta minima. Imposta i minimi dalla scheda di ogni articolo.')}</div>`;
}

function inventoryTab(canManage){
 if(!canManage)return'';
 const q=norm(ui.q);
 let list=W.items.filter(i=>i.active!==false&&(i.total!==0||i.level||wui.invCounts[i.code]!==undefined||q));
 if(q)list=list.filter(i=>i.search.includes(q));
 const diffs=Object.entries(wui.invCounts).filter(([c,v])=>v!==''&&+v!==(W.byCode.get(c.toUpperCase())?.total||0));
 return`<div class="card reveal" style="--i:7"><div class="card-head"><div><h2>Inventario fisico</h2><p>Conta la merce e scrivi le quantità reali: vengono registrate solo le differenze, come movimenti di inventario.</p></div>
  <button class="btn accent sm" data-winv="save"${diffs.length?'':' disabled'}>${icon('check')}Registra ${diffs.length} differenze</button></div>
 <div class="toolbar">${search('Scansiona o cerca un articolo da contare…')}</div>
 <div class="table-wrap"><table><thead><tr><th>Articolo</th><th>A sistema</th><th>Contati</th><th>Differenza</th></tr></thead><tbody>
 ${list.slice(0,300).map(i=>{const sys=i.total;const v=wui.invCounts[i.code];const d=v===undefined||v===''?null:+v-sys;return`<tr><td><strong style="font-weight:560">${esc(i.name)}</strong><br><small class="mono muted">${esc(i.code)}${i.barcode?' · '+esc(i.barcode):''}</small></td><td class="mono">${qf(sys)} ${esc(i.unit)}</td>
  <td><input class="input mono inv-count" style="width:110px" inputmode="decimal" data-inv="${esc(i.code)}" value="${v??''}" placeholder="${qf(sys)}"></td><td class="mono" style="color:var(--${d===null||d===0?'text-3':d<0?'bad':'ok'})">${d===null?'—':(d>0?'+':'')+qf(d)}</td></tr>`;}).join('')
  ||`<tr><td colspan="4">${empty('search','Niente da contare','Cerca o scansiona un articolo per aggiungerlo al conteggio.')}</td></tr>`}
 </tbody></table></div></div>`;
}

// ── article sheet
function openArticle(code){
 const i=W.byCode.get(String(code).toUpperCase());if(!i)return toast('Articolo non trovato','alert');
 const canManage=canManageWh();const recent=W.moves.filter(m=>m.article.toUpperCase()===i.key).slice(0,12);const l=i.level;
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<div class="modal-box wide" role="dialog" aria-label="${esc(i.name)}">
  <div style="display:flex;gap:14px;align-items:center"><span class="mark" style="--h:${hue(i.category||i.code)};width:48px;height:48px">${icon(i.serialized?'register':'bag')}</span><div style="flex:1;min-width:0"><h3>${esc(i.name)}</h3><p class="mono">${esc(i.code)}${i.barcode?' · '+esc(i.barcode):''}</p></div><span class="badge ${stCls(i.status)}">${esc(i.status)}</span></div>
  <div class="facts"><div class="fact"><small>Giacenza</small><span style="font-weight:650;font-variant-numeric:tabular-nums;color:var(--${i.total<0?'bad':i.below?'due':'text'})">${qf(i.total)} ${esc(i.unit)}</span></div><div class="fact"><small>Categoria</small><span>${esc(i.category||'—')}</span></div>
   <div class="fact"><small>Costo medio</small><span>${eur(i.cost)}</span></div><div class="fact"><small>Prezzo di vendita</small><span>${eur(i.price)} <span class="muted">IVA ${qf(i.vat)}%</span></span></div>
   <div class="fact"><small>Fornitore</small><span>${esc(i.supplier||'—')}${i.supplierCode?' · '+esc(i.supplierCode):''}</span></div></div>
  ${canManage?`<div class="stat-line need-export" style="align-items:center"><span>Scorta minima e massima</span><span style="display:flex;gap:6px;align-items:center;font-size:12px;color:var(--text-3)">min <input class="input mono" style="width:70px;height:32px" data-lvl="min" value="${l?qf(l.min):''}"> max <input class="input mono" style="width:70px;height:32px" data-lvl="max" value="${l?qf(l.max):''}"></span></div>`:''}
  ${i.serialized?`<p class="sub">Articolo gestito <strong>a matricola</strong>: indica il numero di serie in ogni movimento.</p>`:''}
  <details class="extra"${recent.length?'':' hidden'}><summary>Ultimi movimenti (${recent.length})</summary>${recent.map(x=>`<div class="stat-line"><span>${dShort(x.d)} · <span class="badge ${MV_CLS[x.type]}">${MV_LABEL[x.type]}</span>${x.serial?' · '+esc(x.serial):''}</span><span class="mono">${x.to&&!x.from?'+':x.from&&!x.to?'−':''}${qf(x.qty)}</span></div>`).join('')}</details>
  <div class="btn-row">${canManage?`<button class="btn ghost need-export" data-am="edit" style="margin-right:auto">Modifica articolo</button>`:''}<button class="btn ghost" data-am="close">Chiudi</button><button class="btn" data-am="scarico">Scarico</button><button class="btn accent" data-am="carico">Carico</button></div></div>`;
 document.body.append(m);
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();close();}};document.addEventListener('keydown',key,true);
 m.addEventListener('click',e=>{if(e.target===m)return close();const b=e.target.closest('[data-am]');if(!b)return;const a=b.dataset.am;close();if(a==='edit')openArticleEditor(i);else if(a!=='close')openMovement(a,i.code);});
 // min/max saved when leaving the field
 m.addEventListener('change',e=>{if(!e.target.closest('[data-lvl]'))return;const v=t=>+(m.querySelector(`[data-lvl="${t}"]`).value.replace(',','.')||0);
  const level={article:i.code,warehouse:WH,min:v('min'),max:v('max')};wsave('levelSave',{item:level},'/api/warehouse/levels',level,'Scorta aggiornata').catch(()=>{});});
}

// ── movement form (load / unload / correction)
function openMovement(type='carico',articleCode=''){
 if(!W)deriveWarehouse();
 const types=['carico','scarico',...(canManageWh()?['rettifica']:[])];if(!types.includes(type))type='carico';
 const a0=articleCode?W.byCode.get(articleCode.toUpperCase()):null;
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<form class="modal-box wide" id="mv-form" aria-label="Movimento di magazzino"><h3>Movimento di magazzino</h3>
  <div class="seg" id="mv-type">${types.map(t=>`<button type="button" data-t="${t}" class="${t===type?'active':''}">${MV_LABEL[t]}</button>`).join('')}</div>
  <label class="field">Articolo<input class="input" id="mv-art" list="mv-arts" value="${esc(a0?a0.name:'')}" placeholder="Scansiona il codice a barre o cerca…" required autocomplete="off"></label>
  <datalist id="mv-arts">${W.items.filter(i=>i.active!==false).slice(0,4000).map(i=>`<option value="${esc(i.name)}">${esc(i.code)}${i.barcode?' · '+esc(i.barcode):''}</option>`).join('')}</datalist>
  <p class="sub" id="mv-info"></p>
  <div class="form-row"><label class="field">Quantità<input class="input mono" id="mv-qty" inputmode="decimal" value="1" required></label><label class="field" id="mv-sign-l" hidden>Rettifica<select class="select" id="mv-sign"><option value="+">Aggiungi (+)</option><option value="-">Togli (−)</option></select></label><label class="field" id="mv-cost-l">Costo unitario €<input class="input mono" id="mv-cost" inputmode="decimal" placeholder="opzionale"></label></div>
  <label class="field" id="mv-serial-l" hidden>Matricola / numero di serie<input class="input mono" id="mv-serial" autocomplete="off"></label>
  <label class="field">Note<input class="input" id="mv-notes" maxlength="300" placeholder="es. DDT n. 123 del fornitore, cliente, motivo…"></label>
  <div class="btn-row"><button type="button" class="btn ghost" data-m="0">Annulla</button><button class="btn accent" type="submit">${icon('check')}Registra</button></div></form>`;
 document.body.append(m);
 const $m=s=>m.querySelector(s);let cur=type;
 const art=()=>findArticle($m('#mv-art').value);
 const info=()=>{const a=art();$m('#mv-serial-l').hidden=!a?.serialized;$m('#mv-info').innerHTML=a?`<span class="mono">${esc(a.code)}</span> · in magazzino: <strong>${qf(a.total)} ${esc(a.unit)}</strong>`:($m('#mv-art').value?'<span style="color:var(--due)">Articolo non trovato: scegli dall’elenco o scansiona il codice.</span>':'');};
 const layout=()=>{m.querySelectorAll('#mv-type button').forEach(b=>b.classList.toggle('active',b.dataset.t===cur));$m('#mv-sign-l').hidden=cur!=='rettifica';$m('#mv-cost-l').hidden=cur!=='carico';info();};
 m.querySelector('#mv-type').addEventListener('click',e=>{const b=e.target.closest('[data-t]');if(b){cur=b.dataset.t;layout();}});
 $m('#mv-art').addEventListener('input',info);
 // barcode scanners type the code and press Enter: resolve it instead of submitting the form
 $m('#mv-art').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();const a=art();if(a){$m('#mv-art').value=a.name;info();$m('#mv-qty').select();}else toast('Codice non trovato','alert');}});
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();close();}};document.addEventListener('keydown',key,true);
 m.addEventListener('click',e=>{if(e.target===m||e.target.closest('[data-m="0"]'))close();});
 $m('#mv-form').addEventListener('submit',async e=>{e.preventDefault();const a=art();if(!a)return toast('Scegli un articolo valido','alert');
  const qty=+$m('#mv-qty').value.replace(',','.');if(!(qty>0))return toast('Quantità non valida','alert');
  const into=cur==='carico'||cur==='rettifica'&&$m('#mv-sign').value==='+';
  const mv={id:'',date:'',type:cur,article:a.code,qty,from:into?'':WH,to:into?WH:'',unitCost:cur==='carico'?+($m('#mv-cost').value||'0').replace(',','.')||0:0,serial:$m('#mv-serial').value.trim(),clientCode:'',interventionId:'',notes:$m('#mv-notes').value.trim(),user:'',created:''};
  try{await wsave('movements',{movements:[mv]},'/api/warehouse/movements',{movements:[mv]},'Movimento registrato');close();}catch{}});
 layout();setTimeout(()=>(a0?$m('#mv-qty'):$m('#mv-art')).focus(),40);
}

// ── article editor
function openArticleEditor(a){
 const isNew=!a;a=a||{code:'',name:'',barcode:'',category:'',unit:'PZ',cost:0,price:0,vat:22,supplier:'',supplierCode:'',serialized:false,active:true,notes:'',source:'app',updated:''};
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<form class="modal-box wide" id="ar-form"><h3>${isNew?'Nuovo articolo':'Modifica articolo'}</h3>
  <label class="field">Descrizione<input class="input" name="name" value="${esc(a.name)}" required maxlength="200" placeholder="es. Rotolo termico 57x40"></label>
  <div class="form-row"><label class="field">Codice<input class="input mono" name="code" value="${esc(a.code)}" ${isNew?'':'readonly'} required maxlength="40" placeholder="proposto dalla descrizione"><small class="field-err" id="ar-taken" hidden>Codice già usato da un altro articolo</small></label><label class="field">Codice a barre (EAN)<input class="input mono" name="barcode" value="${esc(a.barcode)}" placeholder="scansiona…" maxlength="60"></label></div>
  <div class="form-row"><label class="field">Categoria<input class="input" name="category" list="ar-cats" value="${esc(a.category)}"></label><label class="field">Unità di misura<input class="input" name="unit" value="${esc(a.unit)}" maxlength="10"></label></div>
  <datalist id="ar-cats">${W.cats.map(c=>`<option value="${esc(c)}">`).join('')}</datalist>
  <div class="form-row"><label class="field">Costo €<input class="input mono" name="cost" inputmode="decimal" value="${a.cost||''}"></label><label class="field">Prezzo di vendita €<input class="input mono" name="price" inputmode="decimal" value="${a.price||''}"></label></div>
  <div class="form-row"><label class="field">Fornitore<input class="input" name="supplier" value="${esc(a.supplier)}"></label><label class="field">Codice del fornitore<input class="input mono" name="supplierCode" value="${esc(a.supplierCode)}"></label></div>
  <div class="form-row"><label class="field">IVA %<input class="input mono" name="vat" inputmode="decimal" value="${a.vat}"></label><label class="check" style="align-self:end;padding-bottom:10px"><input type="checkbox" name="serialized" ${a.serialized?'checked':''}> Gestito a matricola (numero di serie)</label></div>
  <label class="check"><input type="checkbox" name="active" ${a.active!==false?'checked':''}> Articolo attivo</label>
  <div class="btn-row"><button type="button" class="btn ghost" data-m="0">Annulla</button><button class="btn accent" type="submit">${icon('check')}Salva</button></div></form>`;
 document.body.append(m);
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),220);};
 m.addEventListener('click',e=>{if(e.target===m||e.target.closest('[data-m="0"]'))close();});
 // new article: the code follows the description until it is typed by hand; a code already in use is said at once
 if(isNew){const fm=m.querySelector('form'),cf=fm.code,nf=fm.name;let auto=true;const taken=()=>{const t=!!cf.value.trim()&&!!W.byCode?.get?.(cf.value.trim().toUpperCase());m.querySelector('#ar-taken').hidden=!t;cf.setCustomValidity(t?'Codice già usato':'');};
  const make=v=>{const w=v.toUpperCase().normalize('NFD').replace(/[̀-ͯ]/g,'').split(/[^A-Z0-9]+/).filter(Boolean);return w.map(x=>/\d/.test(x)?x:x.slice(0,w.length<=2?6:3)).join('').slice(0,16);};
  nf.addEventListener('input',()=>{if(auto){cf.value=make(nf.value);taken();}});cf.addEventListener('input',()=>{auto=!cf.value;cf.value=cf.value.toUpperCase();taken();});
  setTimeout(()=>nf.focus(),60);}
 m.querySelector('form').addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(e.target);const n=k=>+String(f.get(k)||'0').replace(',','.')||0;
  const item={...a,code:String(f.get('code')).trim(),name:String(f.get('name')).trim(),barcode:String(f.get('barcode')||'').trim(),category:String(f.get('category')||'').trim(),unit:String(f.get('unit')||'PZ').trim(),cost:n('cost'),price:n('price'),vat:n('vat'),supplier:String(f.get('supplier')||''),supplierCode:String(f.get('supplierCode')||''),serialized:f.get('serialized')==='on',active:f.get('active')==='on',source:'app'};
  try{await wsave('articleSave',{item},'/api/warehouse/articles',item,'Articolo salvato');close();}catch{}});
 // Enter in the barcode field (scanner) must not submit
 m.querySelector('[name=barcode]').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();m.querySelector('[name=name]').focus();}});
 setTimeout(()=>m.querySelector(isNew?'[name=code]':'[name=name]').focus(),40);
}

// ── intervention editor: spare parts used (unloaded from the warehouse when the intervention is saved)
function partsSection(parts,ro){
 if(!W)deriveWarehouse();
 if(!W.items.length&&!(parts||[]).length)return'';
 const row=p=>`<div class="part-row"><input class="input" data-p="article" list="mv-arts-p" value="${esc(W.byCode.get((p.article||'').toUpperCase())?.name||p.article||'')}" placeholder="Ricambio (scansiona o cerca)" ${ro?'disabled':''}>
  <input class="input mono" data-p="qty" inputmode="decimal" value="${p.qty??1}" style="width:72px" ${ro?'disabled':''}>
  <input class="input mono" data-p="serial" value="${esc(p.serial||'')}" placeholder="matricola" style="width:120px" ${ro?'disabled':''}>${ro?'':'<button type="button" class="icon-btn" data-prm aria-label="Togli">'+icon('close')+'</button>'}</div>`;
 return`<div class="field"><span class="field-head">Ricambi usati<small class="muted">Scaricati in automatico dal magazzino</small></span>
  <div id="parts">${(parts||[]).map(row).join('')}</div>${ro?'':`<button type="button" class="btn sm ghost" data-padd style="justify-self:start">${icon('plus')}Aggiungi ricambio</button>`}
  <datalist id="mv-arts-p">${W.items.filter(i=>i.active!==false).slice(0,4000).map(i=>`<option value="${esc(i.name)}">${esc(i.code)}</option>`).join('')}</datalist><template id="part-tpl">${row({qty:1})}</template></div>`;
}
function readParts(root){
 return[...root.querySelectorAll('.part-row')].map(r=>{const v=k=>r.querySelector(`[data-p="${k}"]`).value.trim();const a=findArticle(v('article'));
  return{article:a?.code||v('article'),qty:+v('qty').replace(',','.')||0,warehouse:WH,serial:v('serial'),_ok:!!a};}).filter(p=>p.article);
}

// ── wiring
document.addEventListener('click',e=>{
 const t=e.target.closest('[data-wtab],[data-wmove],[data-wnew],[data-art],[data-winv],[data-padd],[data-prm]');if(!t)return;const ds=t.dataset;
 if(ds.wtab){wui.tab=ds.wtab;ui.p=1;ui.q='';return typeof morph==='function'?morph():render(false);}
 if(ds.wmove)return openMovement(ds.wmove);
 if(ds.wnew==='article')return openArticleEditor(null);
 if(ds.art)return openArticle(ds.art);
 if(ds.padd!==undefined){const box=t.closest('.field').querySelector('#parts');box.insertAdjacentHTML('beforeend',t.closest('.field').querySelector('#part-tpl').innerHTML);box.lastElementChild.querySelector('input').focus();return;}
 if(ds.prm!==undefined)return t.closest('.part-row').remove();
 if(ds.winv==='save'){const counted={};for(const[c,v]of Object.entries(wui.invCounts))if(v!=='')counted[c]=+String(v).replace(',','.');
  ask({title:'Registrare l’inventario?',text:'Vengono registrate le differenze tra le quantità contate e quelle a sistema.',ok:'Registra'}).then(async ok=>{if(!ok)return;
   try{await wsave('inventory',{warehouse:WH,counted},'/api/warehouse/inventory',{warehouse:WH,counted},'Inventario registrato');wui.invCounts={};render(false);}catch{}});}
});
document.addEventListener('input',e=>{const inp=e.target.closest('[data-inv]');if(!inp)return;wui.invCounts[inp.dataset.inv]=inp.value;
 const row=inp.closest('tr');const sys=W.byCode.get(inp.dataset.inv.toUpperCase())?.total||0;const d=inp.value===''?null:+inp.value.replace(',','.')-sys;const cell=row.lastElementChild;cell.textContent=d===null?'—':(d>0?'+':'')+qf(d);cell.style.color=`var(--${d===null||d===0?'text-3':d<0?'bad':'ok'})`;
 const n=Object.entries(wui.invCounts).filter(([c,v])=>v!==''&&+String(v).replace(',','.')!==(W.byCode.get(c.toUpperCase())?.total||0)).length;const b=$('[data-winv="save"]');if(b){b.disabled=!n;b.lastChild.textContent=`Registra ${n} differenze`;}});
document.addEventListener('change',e=>{const id=e.target.id;
 if(id==='f-wcat'){wui.cat=e.target.value;ui.p=1;render(false);}else if(id==='f-wonly'){wui.only=e.target.value;ui.p=1;render(false);}});
// scanner on the stock / inventory search: an exact barcode opens the article (or focuses its count)
document.addEventListener('keydown',e=>{if(e.key!=='Enter'||e.target.id!=='q'||page!=='warehouse')return;const a=findArticle(e.target.value);if(!a)return;e.preventDefault();
 if(wui.tab==='inventory'){ui.q='';if(wui.invCounts[a.code]===undefined)wui.invCounts[a.code]='0';render(false);setTimeout(()=>{const inp=document.querySelector(`[data-inv="${CSS.escape(a.code)}"]`);if(inp){inp.value=String((+inp.value||0)+1);inp.dispatchEvent(new Event('input',{bubbles:true}));inp.select();}},30);}
 else{e.target.value='';ui.q='';openArticle(a.code);}});
