'use strict';
/* Top Cash Studio — intervention report ("rapportino") signed by the customer.
   Signature pad: pointer events (mouse, pen, finger), smoothed strokes, exported as PNG. */

function reportPdf(id){
 if(WEB)return download('/api/interventions/'+encodeURIComponent(id)+'/report.pdf');
 send('reportPdf',{id});
}
async function unsignReport(it){
 if(!await ask({title:'Annullare la firma?',text:`Il rapportino n. <strong>${esc(it.reportNo)}</strong> torna modificabile e la firma di ${esc(it.signer)} viene eliminata. L’operazione resta nella cronologia; il numero del rapportino non cambia.`,ok:'Annulla firma',danger:true}))return;
 if(WEB){try{await post('/api/interventions/'+encodeURIComponent(it.id)+'/unsign');toast('Firma annullata');}catch(e){toast(e.message,'alert');}}else send('unsignIntervention',{id:it.id});
}

function openSignature(it){
 const c=it.c;const parts=(it.parts||[]).filter(p=>p.article);
 const m=document.createElement('div');m.className='modal';
 m.innerHTML=`<form class="modal-box wide sign-box" aria-label="Firma del rapportino">
  <h3>Rapportino di intervento</h3>
  <div class="sign-sum"><div><small>Cliente</small><strong>${esc(c?.name||it.clientName||'—')}</strong></div><div><small>Intervento</small><strong>${esc(it.title)}</strong></div>
   <div><small>Tecnico</small><strong>${esc(it.technician||'—')}</strong></div><div><small>Ricambi</small><strong>${parts.length?parts.map(p=>esc((typeof W!=='undefined'&&W?.byCode.get(p.article.toUpperCase())?.name)||p.article)+' ×'+p.qty).join(', '):'nessuno'}</strong></div></div>
  ${it.work?`<p class="sub" style="white-space:pre-line;max-height:90px;overflow:auto">${esc(it.work)}</p>`:''}
  <div class="form-row"><label class="field">Ore lavorate<input class="input mono" name="hours" inputmode="decimal" value="${esc(String(it.hours||'').replace('.',','))}" placeholder="es. 1,5"></label>
   <label class="field">Nome e cognome di chi firma<input class="input" name="signer" required maxlength="80" autocomplete="off" placeholder="Il cliente o un suo incaricato"></label></div>
  <div class="field">Firma del cliente<div class="sign-pad"><canvas aria-label="Riquadro firma"></canvas><span class="sign-hint">Firma qui con il dito, la penna o il mouse</span><button type="button" class="btn sm ghost sign-clear">${icon('close')}Cancella</button></div></div>
  <label class="check"><input type="checkbox" name="close" checked> Segna l’intervento come <strong>chiuso</strong></label>
  <p class="muted" style="font-size:12px">Dopo la firma il rapportino non è più modificabile (l’ufficio può annullare la firma). Il PDF numerato si scarica subito dopo.</p>
  <div class="btn-row"><button type="button" class="btn ghost" data-sm="0">Annulla</button><button class="btn accent" type="submit">${icon('check')}Conferma firma</button></div></form>`;
 document.body.append(m);
 const canvas=m.querySelector('canvas'),pad=m.querySelector('.sign-pad'),ctx=canvas.getContext('2d');let strokes=0,drawing=false,last=null,mid=null;
 const fit=()=>{const r=pad.getBoundingClientRect(),dpr=Math.min(2,devicePixelRatio||1);canvas.width=r.width*dpr;canvas.height=r.height*dpr;canvas.style.width=r.width+'px';canvas.style.height=r.height+'px';
  ctx.setTransform(dpr,0,0,dpr,0,0);ctx.lineCap='round';ctx.lineJoin='round';ctx.strokeStyle='#101840';strokes=0;pad.classList.remove('signed');};
 fit();requestAnimationFrame(()=>{if(!strokes)fit();}); // size immediately (works even before the first frame)
 const pos=e=>{const r=canvas.getBoundingClientRect();return{x:e.clientX-r.left,y:e.clientY-r.top,p:e.pressure||0.5};};
 canvas.addEventListener('pointerdown',e=>{e.preventDefault();if(!canvas.width)fit();canvas.setPointerCapture(e.pointerId);drawing=true;last=pos(e);mid=last;pad.classList.add('signed');
  ctx.beginPath();ctx.arc(last.x,last.y,1.3,0,7);ctx.fillStyle='#101840';ctx.fill();});
 canvas.addEventListener('pointermove',e=>{if(!drawing)return;e.preventDefault();
  for(const ev of (e.getCoalescedEvents?.()||[e])){const p=pos(ev);const nm={x:(last.x+p.x)/2,y:(last.y+p.y)/2};
   // quadratic smoothing through midpoints; pen pressure modulates the width
   ctx.lineWidth=e.pointerType==='pen'?1.4+p.p*2.6:2.6;ctx.beginPath();ctx.moveTo(mid.x,mid.y);ctx.quadraticCurveTo(last.x,last.y,nm.x,nm.y);ctx.stroke();last=p;mid=nm;}});
 const end=()=>{if(drawing){drawing=false;strokes++;}};canvas.addEventListener('pointerup',end);canvas.addEventListener('pointercancel',end);
 m.querySelector('.sign-clear').addEventListener('click',()=>{ctx.clearRect(0,0,canvas.width,canvas.height);strokes=0;pad.classList.remove('signed');});
 const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),220);document.removeEventListener('keydown',key,true);removeEventListener('resize',fit);};
 const key=e=>{if(e.key==='Escape'){e.stopPropagation();close();}};document.addEventListener('keydown',key,true);addEventListener('resize',fit);
 m.addEventListener('click',e=>{if(e.target===m||e.target.closest('[data-sm="0"]'))close();});
 m.querySelector('form').addEventListener('submit',async e=>{e.preventDefault();const f=new FormData(e.target);
  if(!strokes)return toast('Fai firmare il cliente nel riquadro','alert');
  const signer=String(f.get('signer')).trim();if(signer.length<2)return toast('Scrivi il nome di chi firma','alert');
  // export on white at a compact size (keeps the archive small)
  const out=document.createElement('canvas');const scale=Math.min(1,700/canvas.width);out.width=canvas.width*scale;out.height=canvas.height*scale;
  const o=out.getContext('2d');o.fillStyle='#fff';o.fillRect(0,0,out.width,out.height);o.drawImage(canvas,0,0,out.width,out.height);
  const payload={signer,hours:String(f.get('hours')||'').replace(',','.'),signature:out.toDataURL('image/png'),close:f.get('close')==='on'};
  const btn=e.target.querySelector('[type=submit]');btn.disabled=true;
  if(WEB){try{const r=await post('/api/interventions/'+encodeURIComponent(it.id)+'/sign',payload);toast(`Rapportino n. ${r.reportNo} firmato`);close();
    if(await ask({title:`Rapportino n. ${r.reportNo} firmato`,text:'Vuoi scaricare subito il PDF da inviare al cliente?',ok:'Scarica PDF'}))reportPdf(it.id);}
   catch(x){toast(x.message,'alert');btn.disabled=false;}}
  else{send('signIntervention',{id:it.id,...payload});close();}});
 setTimeout(()=>m.querySelector('[name=signer]').focus(),60);
}
// local (Windows) archive: offer the PDF right after the host confirms the signature
if(window.chrome?.webview)window.chrome.webview.addEventListener('message',async e=>{const v=e.data;if(v?.type!=='signed')return;
 if(await ask({title:`Rapportino n. ${v.reportNo} firmato`,text:'Vuoi salvare subito il PDF da inviare al cliente?',ok:'Salva PDF'}))reportPdf(v.id);});
