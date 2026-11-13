'use strict';
/* Soft edges: every scrollable strip or panel fades out on the sides that still hide content,
   so a list never ends with a hard cut. The fade grows with the distance still to scroll (0 → 28px)
   and disappears at the very start / end. Classes: fx-x (horizontal overflow), fx-y (vertical overflow);
   the CSS turns them into a mask (strips, inner lists) or a sticky progressive-blur veil (boxes with a background). */
(()=>{
 const SEL='.seg,.dtr-seg,.ctabs,.table-wrap,.ins-tiles,.ana-weeks,.sal-flow,.sidebar nav,.cmd-results,.gap-list,.ac-list,.brand-names div,.sheet-box,.modal-box,.drawer';
 const BOX='.sheet-box,.modal-box,.drawer',F=28,seen=new WeakSet(),dirty=new Set();
 const px=v=>Math.round(Math.min(F,Math.max(0,v)))+'px',op=v=>(Math.min(F,Math.max(0,v))/F).toFixed(2);
 /* Blur veils: the mask only fades; on top of it every scroller that is not a box gets two thin layers over its two
    edges whose backdrop blur softens what slides under them (strongest at the edge). They live in the scroller's
    positioned ancestor (absolutely placed: no room taken, no clicks caught) and follow its size and the fade. */
 const veils=new Map(),VW=26,stuck=new Set();
 const dropVeils=el=>{const v=veils.get(el);if(v){v.a.remove();v.b.remove();v.l?.remove();v.r?.remove();veils.delete(el);}};
 /* 2.10.12 with a mouse a sideways strip must be reachable: arrows on the sides that still hide something (a click moves
    most of a width, smoothly), the wheel scrolls it sideways, and it can be dragged. Touch and trackpads already swipe. */
 const FINE=matchMedia('(hover:hover) and (pointer:fine)');
 const glide=new WeakMap();
 // smooth sideways scroll toward a target that keeps growing while the wheel turns (no jumps between notches)
 const glideTo=(el,dx)=>{
  // tiles that snap one by one: let the browser move to the next one in that direction (small steps would snap back)
  if(getComputedStyle(el).scrollSnapType.indexOf('x')===0||/x|both/.test(getComputedStyle(el).scrollSnapType)){el.scrollBy({left:Math.sign(dx)*Math.max(Math.abs(dx),el.clientWidth*.6),behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});return;}
  const max=el.scrollWidth-el.clientWidth;let g=glide.get(el);
  if(!g){g={to:el.scrollLeft,raf:0};glide.set(el,g);}
  g.to=Math.max(0,Math.min(max,(g.raf?g.to:el.scrollLeft)+dx));
  if(matchMedia('(prefers-reduced-motion: reduce)').matches){el.scrollLeft=g.to;return;}
  if(!g.raf){const step=()=>{const d=g.to-el.scrollLeft;if(Math.abs(d)<1){el.scrollLeft=g.to;g.raf=0;return;}el.scrollLeft+=d*.22;g.raf=requestAnimationFrame(step);};g.raf=requestAnimationFrame(step);}};
 const STRIP=160; // a strip of tabs or chips (not a tall table): the wheel and the drag move it sideways
 const sideways=el=>{const c=getComputedStyle(el);return /(auto|scroll)/.test(c.overflowX)&&el.scrollWidth-el.clientWidth>2;};
 const stripAt=t=>{for(let e=t;e&&e!==document.body;e=e.parentElement){if(e.nodeType===1&&sideways(e))return e.clientHeight<=STRIP?e:null;}return null;};
 const placeVeils=(el,ox,oy)=>{
  const opr=el.offsetParent;
  const sticky=getComputedStyle(el).position==='sticky';
  const ok=(ox||oy)&&opr&&opr!==document.body&&!el.matches(BOX)&&(!sticky||(ox&&!oy))&&getComputedStyle(opr).position!=='static';
  if(!ok){stuck.delete(el);return dropVeils(el);}
  if(sticky)stuck.add(el);else stuck.delete(el);
  const axis=ox?'x':'y';let v=veils.get(el);
  if(v&&(v.axis!==axis||v.host!==opr||!v.a.isConnected)){dropVeils(el);v=null;}
  if(!v){const mk=c=>{const d=document.createElement('i');d.className='fx-veil '+c;d.setAttribute('aria-hidden','true');return d;};
   v={axis,host:opr,a:mk(axis==='x'?'fx-veil-l':'fx-veil-t'),b:mk(axis==='x'?'fx-veil-r':'fx-veil-b')};opr.append(v.a,v.b);
   if(axis==='x'){const arrow=(c,dir,label)=>{const b=document.createElement('button');b.type='button';b.className='fx-arrow '+c;b.tabIndex=-1;b.setAttribute('aria-label',label);
     b.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="'+(dir<0?'M15 5l-7 7 7 7':'M9 5l7 7-7 7')+'"/></svg>';
     b.addEventListener('click',e=>{e.preventDefault();e.stopPropagation();glideTo(el,dir*Math.max(120,el.clientWidth*.7));});
     b.addEventListener('pointerdown',e=>e.stopPropagation());return b;};
    v.l=arrow('fx-arrow-l',-1,'Scorri a sinistra');v.r=arrow('fx-arrow-r',1,'Scorri a destra');opr.append(v.l,v.r);}
   veils.set(el,v);}
  // a sticky strip is not where offsetTop says once it sticks: measure where it really is inside the host
  let L=el.offsetLeft,T=el.offsetTop;const W=el.offsetWidth,H=el.offsetHeight,s=el.style;
  if(sticky){const r=el.getBoundingClientRect(),o=opr.getBoundingClientRect();L=Math.round(r.left-o.left-opr.clientLeft+opr.scrollLeft);T=Math.round(r.top-o.top-opr.clientTop+opr.scrollTop);}
  const put=(d,x,y,w,h,o)=>{const css=`left:${x}px;top:${y}px;width:${w}px;height:${h}px;opacity:${o};visibility:${+o>0?'visible':'hidden'}`;if(d.style.cssText!==css)d.style.cssText=css;};
  if(axis==='x'){const fl=s.getPropertyValue('--fo-l')||'0',fr=s.getPropertyValue('--fo-r')||'0';put(v.a,L,T,VW,H,sticky?'0':fl);put(v.b,L+W-VW,T,VW,H,sticky?'0':fr);
   if(v.l){// arrows: in the middle of a strip; on a tall table, in the middle of the part on screen (they follow the page)
    let cy=T+H/2;if(H>STRIP){const r=el.getBoundingClientRect(),top=Math.max(r.top,60),bot=Math.min(r.bottom,innerHeight-20);cy=bot-top>60?T+(top+bot)/2-r.top:-999;}
    const AW=30,ay=Math.round(cy-AW/2),show=o=>FINE.matches&&cy>-999&&+o>.05?'1':'0';
    const pa=(b,x,o)=>{const css=`left:${x}px;top:${ay}px;opacity:${show(o)};visibility:${show(o)==='1'?'visible':'hidden'}`;if(b.style.cssText!==css)b.style.cssText=css;};
    pa(v.l,L+4,fl);pa(v.r,L+W-AW-4,fr);}}
  else{put(v.a,L,T,W,VW,s.getPropertyValue('--fo-t')||'0');put(v.b,L,T+H-VW,W,VW,s.getPropertyValue('--fo-b')||'0');}
 };
 const upd=el=>{
  if(!el.isConnected)return dropVeils(el);
  const s=el.style,w=el.scrollWidth-el.clientWidth,h=el.scrollHeight-el.clientHeight;
  const ox=w>8,oy=h>8; // a few pixels of overflow (rounding, focus rings) is not worth a fade
  el.classList.toggle('fx-x',ox);el.classList.toggle('fx-y',oy);
  // the blur veils are positioned inside the scroller: give it a containing block when it has none
  if((ox||oy)&&!el.classList.contains('fx-rel')&&!el.matches(BOX)&&getComputedStyle(el).position==='static')el.classList.add('fx-rel');
  const set=(k,v)=>{if(s.getPropertyValue(k)!==v)s.setProperty(k,v);};
  if(ox){const l=Math.abs(el.scrollLeft);set('--fe-l',px(l));set('--fe-r',px(w-l));set('--fo-l',op(l));set('--fo-r',op(w-l));set('--fe-mx',w+'px');}
  if(oy){const t=el.scrollTop;s.setProperty('--fe-t',px(t));s.setProperty('--fe-b',px(h-t));s.setProperty('--fo-t',op(t));s.setProperty('--fo-b',op(h-t));set('--fe-my',h+'px');
   // sticky veils stop at the padding edge: pull them out to the real top / bottom of the box
   if(el.matches(BOX)){const c=getComputedStyle(el),pt='-'+c.paddingTop,pb='-'+c.paddingBottom,mx=h+'px';
    if(s.getPropertyValue('--pad-t')!==pt)s.setProperty('--pad-t',pt);if(s.getPropertyValue('--pad-b')!==pb)s.setProperty('--pad-b',pb);if(s.getPropertyValue('--fe-max')!==mx)s.setProperty('--fe-max',mx);}}
  placeVeils(el,ox,oy);
 };
 let raf=0;
 const flush=()=>{raf=0;const a=[...dirty];dirty.clear();a.forEach(upd);};
 const queue=el=>{dirty.add(el);if(!raf)raf=requestAnimationFrame(flush);};
 const ro='ResizeObserver' in window?new ResizeObserver(es=>es.forEach(e=>queue(e.target))):null;
 const scan=()=>{document.querySelectorAll(SEL).forEach(el=>{if(!seen.has(el)){seen.add(el);ro&&ro.observe(el);}queue(el);});veils.forEach((_,el)=>queue(el));};
 // any other scroller joins the first time it moves (lists added later, a page's own panels): same edges everywhere
 // (only transparent ones: a mask on a panel with its own background would fade the panel itself)
 const scroller=el=>{const c=getComputedStyle(el);return /(auto|scroll)/.test(c.overflowX+c.overflowY)&&c.backgroundImage==='none'&&/rgba\(.*,\s*0\)|transparent/.test(c.backgroundColor)&&!el.closest('.auth');};
 // the page itself (main): the frosted veils under the top bar and above the bottom edge grow with the distance
 // still to scroll on that side (0 at the very top / end: nothing covers the first or last line)
 let pageRaf=0;
 const page=()=>{pageRaf=0;const m=document.getElementById('main'),sh=m&&m.closest('.shell');if(!sh)return;
  const t=m.scrollTop,b=m.scrollHeight-m.clientHeight-t,f=v=>(Math.min(40,Math.max(0,v))/40).toFixed(2),s=sh.style;
  const bar=document.querySelector('.sidebar'),r=bar&&getComputedStyle(bar).position==='fixed'?bar.getBoundingClientRect():null;
  const bh=r&&r.top>innerHeight/2?Math.round(innerHeight-r.top)+'px':'0px';if(s.getPropertyValue('--bar-h')!==bh)s.setProperty('--bar-h',bh);
  if(s.getPropertyValue('--page-t')!==f(t))s.setProperty('--page-t',f(t));if(s.getPropertyValue('--page-b')!==f(b))s.setProperty('--page-b',f(b));};
 const pageLater=()=>{if(!pageRaf)pageRaf=requestAnimationFrame(page);};
 document.addEventListener('scroll',e=>{if(e.target&&e.target.id==='main'){pageLater();veils.forEach((v,el)=>{if(v.l&&el.clientHeight>STRIP)queue(el);});}},{capture:true,passive:true});
 // mouse wheel over a sideways strip: it scrolls the strip (smoothly) until its end, then the page goes on as usual
 addEventListener('wheel',e=>{if(e.ctrlKey||!FINE.matches)return;const dy=Math.abs(e.deltaY)>Math.abs(e.deltaX)?e.deltaY:0;if(!dy)return;
  const el=stripAt(e.target);if(!el)return;const max=el.scrollWidth-el.clientWidth,g=glide.get(el),at=g&&g.raf?g.to:el.scrollLeft;
  if((dy<0&&at<=0)||(dy>0&&at>=max-1))return;
  e.preventDefault();glideTo(el,dy*(e.deltaMode===1?32:e.deltaMode===2?el.clientWidth:1));},{passive:false});
 // drag with the mouse: past a few pixels it is a drag (the click that follows is swallowed), below it stays a click
 let drag=null;
 addEventListener('pointerdown',e=>{if(e.pointerType!=='mouse'||e.button!==0||e.target.closest('input,select,textarea,[contenteditable]'))return;
  const el=stripAt(e.target);if(el)drag={el,x:e.clientX,left:el.scrollLeft,moved:false,id:e.pointerId};},true);
 addEventListener('pointermove',e=>{if(!drag||e.pointerId!==drag.id)return;const dx=e.clientX-drag.x;
  if(!drag.moved&&Math.abs(dx)<6)return;if(!drag.moved){drag.moved=true;drag.el.classList.add('fx-drag');drag.snap=getComputedStyle(drag.el).scrollSnapType!=='none';if(drag.snap)drag.el.style.scrollSnapType='none';const g=glide.get(drag.el);if(g&&g.raf){cancelAnimationFrame(g.raf);g.raf=0;}}
  drag.el.scrollLeft=drag.left-dx;drag.dx=dx;},true);
 const endDrag=e=>{if(!drag||(e&&e.pointerId!==drag.id))return;const d=drag;drag=null;d.el.classList.remove('fx-drag');
  // tiles that snap: a decided drag goes on to the next tile in that direction, a short one settles back
  if(d.snap){d.el.style.scrollSnapType='';if(Math.abs(d.dx||0)>40)d.el.scrollBy({left:-Math.sign(d.dx),behavior:'smooth'});}
  if(d.moved){const stop=ev=>{ev.preventDefault();ev.stopImmediatePropagation();};addEventListener('click',stop,{capture:true,once:true});setTimeout(()=>removeEventListener('click',stop,true),0);}};
 addEventListener('pointerup',endDrag,true);addEventListener('pointercancel',endDrag,true);
 // keyboard: a tab reached with Tab or the arrows comes fully into view
 document.addEventListener('focusin',e=>{const el=stripAt(e.target);if(!el||e.target===el)return;const a=e.target.getBoundingClientRect(),b=el.getBoundingClientRect();
  if(a.left<b.left+28)glideTo(el,a.left-b.left-36);else if(a.right>b.right-28)glideTo(el,a.right-b.right+36);});
 // a sticky strip moves inside the box that scrolls: its arrows follow it
 document.addEventListener('scroll',e=>{const t=e.target;if(t&&t.nodeType===1)stuck.forEach(el=>{if(t.contains(el))queue(el);});},{capture:true,passive:true});
 // any other sideways strip gets its arrows the first time the mouse goes over it
 document.addEventListener('pointerover',e=>{if(e.pointerType!=='mouse')return;const el=stripAt(e.target);if(el&&!seen.has(el)){seen.add(el);ro&&ro.observe(el);queue(el);}},{passive:true});
 document.addEventListener('scroll',e=>{const t=e.target;if(!t||t.nodeType!==1||t.tagName==='MAIN')return;
  if(!seen.has(t)){if(!scroller(t))return;seen.add(t);ro&&ro.observe(t);}queue(t);},{capture:true,passive:true});
 let scanRaf=0;
 const later=()=>{pageLater();if(!scanRaf)scanRaf=requestAnimationFrame(()=>{scanRaf=0;scan();});};
 new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true});
 addEventListener('resize',later,{passive:true});
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',scan);else scan();
})();
