'use strict';
/* Top Cash Studio service worker.
   App shell: network-first (code is always fresh when online) with cache fallback.
   /api/session and /api/state: network-first, last good copy kept for offline reading.
   Everything else under /api is never cached. */
const SHELL='tcs-shell-v13',API='tcs-api';
const SHELL_FILES=['./','index.html','app.js','warehouse.js','accounting.js','report.js','dtr.js','styles.css','theme.css','manifest.webmanifest','assets/tcs-app.png','assets/tcs-app@2x.png','assets/tcs-logo.png','assets/favicon-32.png','assets/favicon-64.png','assets/tcs-touch.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(SHELL).then(c=>c.addAll(SHELL_FILES)).then(()=>self.skipWaiting()));});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==SHELL&&k!==API).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));});
self.addEventListener('fetch',e=>{
 const req=e.request;if(req.method!=='GET')return;const url=new URL(req.url);if(url.origin!==location.origin)return;
 if(url.pathname.startsWith('/api/')){
  if(url.pathname!=='/api/state'&&url.pathname!=='/api/session')return; // live / private endpoints: network only
  e.respondWith(fetch(req).then(res=>{if(res.status===200){const copy=res.clone();caches.open(API).then(c=>c.put(url.pathname,copy));}return res;})
   .catch(async()=>{const hit=await caches.match(url.pathname,{cacheName:API});if(!hit)return Response.error();
    const h=new Headers(hit.headers);h.set('X-From-Cache','1');h.delete('ETag');return new Response(await hit.blob(),{status:200,headers:h});}));
  return;
 }
 e.respondWith(fetch(req).then(res=>{if(res.ok){const copy=res.clone();caches.open(SHELL).then(c=>c.put(req,copy));}return res;}).catch(()=>caches.match(req).then(r=>r||caches.match('index.html'))));
});
