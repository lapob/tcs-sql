'use strict';
/* Soft edges: every scrollable strip or panel fades out on the sides that still hide content,
   so a list never ends with a hard cut. The fade grows with the distance still to scroll (0 → 28px)
   and disappears at the very start / end. Classes: fx-x (horizontal overflow), fx-y (vertical overflow);
   the CSS turns them into a mask (strips, inner lists) or a sticky progressive-blur veil (boxes with a background). */
(()=>{
 const SEL='.seg,.dtr-seg,.ctabs,.table-wrap,.ins-tiles,.ana-weeks,.sal-flow,.sidebar nav,.cmd-results,.gap-list,.brand-names div,.sheet-box,.modal-box,.drawer';
 const BOX='.sheet-box,.modal-box,.drawer',F=28,seen=new WeakSet(),dirty=new Set();
 const px=v=>Math.round(Math.min(F,Math.max(0,v)))+'px',op=v=>(Math.min(F,Math.max(0,v))/F).toFixed(2);
 const upd=el=>{
  if(!el.isConnected)return;
  const s=el.style,w=el.scrollWidth-el.clientWidth,h=el.scrollHeight-el.clientHeight;
  const ox=w>8,oy=h>8; // a few pixels of overflow (rounding, focus rings) is not worth a fade
  el.classList.toggle('fx-x',ox);el.classList.toggle('fx-y',oy);
  if(ox){const l=Math.abs(el.scrollLeft);s.setProperty('--fe-l',px(l));s.setProperty('--fe-r',px(w-l));}
  if(oy){const t=el.scrollTop;s.setProperty('--fe-t',px(t));s.setProperty('--fe-b',px(h-t));s.setProperty('--fo-t',op(t));s.setProperty('--fo-b',op(h-t));
   // sticky veils stop at the padding edge: pull them out to the real top / bottom of the box
   if(el.matches(BOX)){const c=getComputedStyle(el),pt='-'+c.paddingTop,pb='-'+c.paddingBottom,mx=h+'px';
    if(s.getPropertyValue('--pad-t')!==pt)s.setProperty('--pad-t',pt);if(s.getPropertyValue('--pad-b')!==pb)s.setProperty('--pad-b',pb);if(s.getPropertyValue('--fe-max')!==mx)s.setProperty('--fe-max',mx);}}
 };
 let raf=0;
 const flush=()=>{raf=0;const a=[...dirty];dirty.clear();a.forEach(upd);};
 const queue=el=>{dirty.add(el);if(!raf)raf=requestAnimationFrame(flush);};
 const ro='ResizeObserver' in window?new ResizeObserver(es=>es.forEach(e=>queue(e.target))):null;
 const scan=()=>{document.querySelectorAll(SEL).forEach(el=>{if(!seen.has(el)){seen.add(el);ro&&ro.observe(el);}queue(el);});};
 document.addEventListener('scroll',e=>{const t=e.target;if(t&&t.nodeType===1&&seen.has(t))queue(t);},{capture:true,passive:true});
 let scanRaf=0;
 const later=()=>{if(!scanRaf)scanRaf=requestAnimationFrame(()=>{scanRaf=0;scan();});};
 new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true});
 addEventListener('resize',later,{passive:true});
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',scan);else scan();
})();
