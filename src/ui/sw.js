'use strict';
/* Top Cash Studio service worker.
   App shell: network-first (code is always fresh when online) with cache fallback.
   /api/session and /api/state: network-first, last good copy kept for offline reading.
   Everything else under /api is never cached.
   "Server down" counts as offline too: when the office PC or the tunnel is off, the proxy in front answers
   502/503/504 or 52x/530 instead of failing the connection, and the saved copy must be used all the same. */
const SHELL='tcs-shell-v68',API='tcs-api';
const SHELL_FILES=['./','index.html','app.js','warehouse.js','accounting.js','sales.js','insights.js','analisi.js','gestures.js','edges.js','portals.js','report.js','dtr.js','ital.js','rch.js','admin.js','updates.js','work.js','registers.js','client.js','styles.css','theme.css','manifest.webmanifest','assets/tcs-app.png','assets/tcs-app@2x.png','assets/tcs-logo.png','assets/favicon-32.png','assets/favicon-64.png','assets/tcs-touch.png','offline.html','offline.js'];
const down=s=>s===502||s===503||s===504||(s>=520&&s<=530);
// code is asked to the server every time (cache:'no-cache' skips a stale copy the browser may keep for hours behind a proxy)
const live=req=>fetch(req,req.mode==='navigate'?undefined:{cache:'no-cache'}).then(res=>{if(down(res.status))throw new Error('server down');return res;});
self.addEventListener('install',e=>{e.waitUntil(caches.open(SHELL).then(c=>c.addAll(SHELL_FILES.map(f=>new Request(f,{cache:'reload'})))).then(()=>self.skipWaiting()));});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==SHELL&&k!==API).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));});
self.addEventListener('fetch',e=>{
 const req=e.request;if(req.method!=='GET')return;const url=new URL(req.url);if(url.origin!==location.origin)return;
 if(url.pathname.startsWith('/api/')){
  if(url.pathname!=='/api/state'&&url.pathname!=='/api/session')return; // live / private endpoints: network only
  e.respondWith(live(req).then(res=>{if(res.status===200){const copy=res.clone();caches.open(API).then(c=>c.put(url.pathname,copy));}return res;})
   .catch(async()=>{const hit=await caches.match(url.pathname,{cacheName:API});if(!hit)return Response.error();
    const h=new Headers(hit.headers);h.set('X-From-Cache','1');h.delete('ETag');return new Response(await hit.blob(),{status:200,headers:h});}));
  return;
 }
 e.respondWith(live(req).then(res=>{if(res.ok){const copy=res.clone();caches.open(SHELL).then(c=>c.put(url.origin+url.pathname,copy));}return res;})
  .catch(async()=>{const hit=await caches.match(req,{cacheName:SHELL,ignoreSearch:true});if(hit)return hit;
   if(req.mode!=='navigate')return Response.error();
   return await caches.match('./',{cacheName:SHELL})||await caches.match('index.html',{cacheName:SHELL})||await caches.match('offline.html',{cacheName:SHELL})||Response.error();}));
});
