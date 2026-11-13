'use strict';
/* Soft edges: every scrollable strip or panel fades out on the sides that still hide content,
   so a list never ends with a hard cut. The fade grows with the distance still to scroll (0 → 28px)
   and disappears at the very start / end. Classes: fx-x (horizontal overflow), fx-y (vertical overflow);
   the CSS turns them into a mask (strips, inner lists) or a sticky progressive-blur veil (boxes with a background). */
(()=>{
 const SEL='.seg,.dtr-seg,.ctabs,.table-wrap,.ins-tiles,.ana-weeks,.sal-flow,.sidebar nav,.cmd-results,.gap-list,.brand-names div,.sheet-box,.modal-box,.drawer';
 const BOX='.sheet-box,.modal-box,.drawer',F=28,seen=new WeakSet(),dirty=new Set();
 const px=v=>Math.round(Math.min(F,Math.max(0,v)))+'px',op=v=>(Math.min(F,Math.max(0,v))/F).toFixed(2);
 /* Blur veils: the mask only fades; on top of it every scroller that is not a box gets two thin layers over its two
    edges whose backdrop blur softens what slides under them (strongest at the edge). They live in the scroller's
    positioned ancestor (absolutely placed: no room taken, no clicks caught) and follow its size and the fade. */
 const veils=new Map(),VW=26;
 const dropVeils=el=>{const v=veils.get(el);if(v){v.a.remove();v.b.remove();veils.delete(el);}};
 const placeVeils=(el,ox,oy)=>{
  const opr=el.offsetParent;
  const ok=(ox||oy)&&opr&&opr!==document.body&&!el.matches(BOX)&&getComputedStyle(el).position!=='sticky'&&getComputedStyle(opr).position!=='static';
  if(!ok)return dropVeils(el);
  const axis=ox?'x':'y';let v=veils.get(el);
  if(v&&(v.axis!==axis||v.host!==opr||!v.a.isConnected)){dropVeils(el);v=null;}
  if(!v){const mk=c=>{const d=document.createElement('i');d.className='fx-veil '+c;d.setAttribute('aria-hidden','true');return d;};
   v={axis,host:opr,a:mk(axis==='x'?'fx-veil-l':'fx-veil-t'),b:mk(axis==='x'?'fx-veil-r':'fx-veil-b')};opr.append(v.a,v.b);veils.set(el,v);}
  const L=el.offsetLeft,T=el.offsetTop,W=el.offsetWidth,H=el.offsetHeight,s=el.style;
  const put=(d,x,y,w,h,o)=>{const css=`left:${x}px;top:${y}px;width:${w}px;height:${h}px;opacity:${o};visibility:${+o>0?'visible':'hidden'}`;if(d.style.cssText!==css)d.style.cssText=css;};
  if(axis==='x'){put(v.a,L,T,VW,H,s.getPropertyValue('--fo-l')||'0');put(v.b,L+W-VW,T,VW,H,s.getPropertyValue('--fo-r')||'0');}
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
 document.addEventListener('scroll',e=>{if(e.target&&e.target.id==='main')pageLater();},{capture:true,passive:true});
 document.addEventListener('scroll',e=>{const t=e.target;if(!t||t.nodeType!==1||t.tagName==='MAIN')return;
  if(!seen.has(t)){if(!scroller(t))return;seen.add(t);ro&&ro.observe(t);}queue(t);},{capture:true,passive:true});
 let scanRaf=0;
 const later=()=>{pageLater();if(!scanRaf)scanRaf=requestAnimationFrame(()=>{scanRaf=0;scan();});};
 new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true});
 addEventListener('resize',later,{passive:true});
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',scan);else scan();
})();
