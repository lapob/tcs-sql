// Quick gestures on interventions, by touch (same as the Android app):
//   swipe right → print the report · swipe left → close · long press → sheet with sign, print, close, postpone.
// Mouse and keyboard keep the buttons and the click; vertical scrolling is never blocked (touch-action: pan-y).
(()=>{
 const THRESHOLD=.3,LONG=460;let g=null;
 const allowed=id=>{
  const it=typeof D!=='undefined'&&D?D.interventions.find(x=>x.id===id):null;if(!it)return null;
  // imported from Mastro: the office and administrators may act on it (it becomes the app's own), technicians only look
  const own=it.source==='app'||(it.source==='mastro'&&typeof canEditClients==='function'&&canEditClients());
  return{it,print:own&&!String(id).startsWith('local-'),close:own&&!it.signedAt&&it.status!=='Chiuso'};
 };
 const reset=el=>{el.classList.remove('sw-on','sw-print','sw-close','sw-hit');el.style.transform='';el.style.removeProperty('--sw');};
 document.addEventListener('pointerdown',e=>{
  if(e.pointerType!=='touch'||e.isPrimary===false)return;
  const el=e.target.closest?.('[data-int]');if(!el||e.target.closest('button,a,input,select,textarea,label,[data-iq]'))return;
  const a=allowed(el.dataset.int);if(!a||!(a.print||a.close))return;   // nothing to do on it (e.g. a technician on an imported report): a normal tap
  g={el,a,x:e.clientX,y:e.clientY,dx:0,lock:'',w:el.getBoundingClientRect().width,
   timer:setTimeout(()=>{if(g&&!g.lock){g.lock='press';g.eat=true;navigator.vibrate?.(12);sheet(g.a);}},LONG)};
 },{passive:true});
 document.addEventListener('pointermove',e=>{
  if(!g||e.pointerType!=='touch')return;const dx=e.clientX-g.x,dy=e.clientY-g.y;
  if(!g.lock){
   if(Math.abs(dx)>12&&Math.abs(dx)>Math.abs(dy)*1.4&&(g.a.print||g.a.close)){g.lock='x';clearTimeout(g.timer);g.el.classList.add('sw-on');}
   else if(Math.abs(dy)>10||Math.abs(dx)>12){g.lock='y';clearTimeout(g.timer);}
  }
  if(g.lock!=='x')return;
  // only towards an allowed action; past the end it resists (rubber band)
  let d=dx>0?(g.a.print?dx:0):(g.a.close?dx:0);const max=g.w*.6;if(Math.abs(d)>max)d=Math.sign(d)*(max+(Math.abs(d)-max)*.25);
  g.dx=d;g.el.style.transform=`translate3d(${d}px,0,0)`;g.el.style.setProperty('--sw',Math.min(1,Math.abs(d)/(g.w*THRESHOLD)).toFixed(3));
  g.el.classList.toggle('sw-print',d>0);g.el.classList.toggle('sw-close',d<0);
  const hit=Math.abs(d)>=g.w*THRESHOLD;if(hit!==g.el.classList.contains('sw-hit')){g.el.classList.toggle('sw-hit',hit);if(hit)navigator.vibrate?.(8);}
 },{passive:true});
 const end=e=>{
  if(!g||e.pointerType!=='touch')return;clearTimeout(g.timer);const s=g;g=null;
  if(s.lock==='press'){eat();return;}   // the finger lifted after a long press: its click must not close the sheet just opened
  if(s.lock!=='x')return;s.eat=true;eat(s);
  const hit=Math.abs(s.dx)>=s.w*THRESHOLD,el=s.el,id=el.dataset.int;
  el.classList.add('sw-back');reset(el);setTimeout(()=>el.classList.remove('sw-back'),360);
  if(hit&&typeof intQuick==='function')intQuick(s.dx>0?'print':'close',id);
 };
 document.addEventListener('pointerup',end,{passive:true});
 document.addEventListener('pointercancel',e=>{if(g){clearTimeout(g.timer);const el=g.el;g=null;el.classList.add('sw-back');reset(el);setTimeout(()=>el.classList.remove('sw-back'),360);}},{passive:true});
 // the click that follows a swipe or a long press must not also open the intervention
 let swallow=0;const eat=()=>{swallow=Date.now();};
 document.addEventListener('click',e=>{if(Date.now()-swallow<450&&e.target.closest?.('[data-int],.int-sheet')){e.stopPropagation();e.preventDefault();}},true);
 document.addEventListener('contextmenu',e=>{if(e.target.closest?.('[data-int]')&&matchMedia('(hover:none)').matches)e.preventDefault();});
 // long press: the everyday actions in a bottom sheet (the [data-iq] buttons are handled where the row buttons are)
 function sheet(a){
  eat();document.querySelector('.int-sheet')?.remove();const it=a.it,id=esc(it.id);
  const b=(k,ic,l,cls='')=>`<button class="int-sheet-btn ${cls}" data-iq="${k}" data-id="${id}">${icon(ic)}<span>${l}</span></button>`;
  const m=document.createElement('div');m.className='modal more-sheet int-sheet';
  m.innerHTML=`<div class="sheet-box" role="dialog" aria-label="Azioni rapide"><i class="grip"></i>
   <div class="int-sheet-head"><strong>${esc(it.title||'Intervento')}</strong><small>${esc([it.client,it.technician].filter(Boolean).join(' · '))}</small></div>
   <div class="int-sheet-grid">${a.close?b('sign','edit','Firma','go'):''}${a.print?b('print','print',it.signedAt?'Rapportino':'Stampa'):''}${a.close?b('close','check','Chiudi'):''}${a.close?b('later','clock','Posticipa'):''}${it.signedAt&&typeof mailOn==='function'&&mailOn()?b('mail','mail','Invia'):''}
   <button class="int-sheet-btn" data-open-int="${id}">${icon('arrow')}<span>Apri</span></button></div></div>`;
  document.body.append(m);
  const close=()=>{m.classList.add('out');setTimeout(()=>m.remove(),240);};
  m.addEventListener('click',e=>{if(e.target===m)return close();const o=e.target.closest('[data-open-int]');if(o){close();openIntervention(o.dataset.openInt);return;}if(e.target.closest('[data-iq]'))setTimeout(close,0);});
 }
})();
