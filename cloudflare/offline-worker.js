// Top Cash Studio - pagina di cortesia quando il server dell'ufficio non risponde.
// Cloudflare Worker sulla rotta tcsql.it/* (e www.tcsql.it/*), con "Fail open" attivo.
// Passa ogni richiesta al server (tunnel). Solo se il tunnel o il PC sono spenti (errori 502/503/504, 52x, 530 di Cloudflare)
// risponde lui: una pagina chiara per le persone, un JSON 503 per le app. Non legge e non salva nessun dato.
const DOWN = s => s === 502 || s === 503 || s === 504 || (s >= 520 && s <= 530);

const PAGE = `<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="robots" content="noindex">
<meta name="theme-color" content="#f4f6fb" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0c0d12" media="(prefers-color-scheme: dark)">
<title>Top Cash Studio - server non raggiungibile</title>
<style>
:root{color-scheme:light dark;--bg:#f4f6fb;--card:#fff;--text:#16181d;--muted:#5a6072;--line:#e3e6ee;--accent:#0b63ce;--on:#fff;--red:#d92d20}
@media (prefers-color-scheme:dark){:root{--bg:#0c0d12;--card:#15171e;--text:#f1f2f6;--muted:#9aa1b2;--line:#262a35;--accent:#5aa2ff;--on:#06203f}}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;min-height:100dvh;display:grid;place-items:center;padding:24px;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{width:100%;max-width:440px;background:var(--card);border:1px solid var(--line);border-radius:22px;padding:32px 28px;text-align:center;box-shadow:0 12px 32px rgba(8,37,75,.08)}
.mark{width:64px;height:64px;margin:0 auto 18px;border-radius:18px;display:grid;place-items:center;background:linear-gradient(135deg,var(--red),var(--accent));color:#fff}
h1{font-size:22px;line-height:1.25;margin:0 0 8px;text-wrap:balance}
p{margin:0 0 10px;color:var(--muted)}
ul{text-align:left;color:var(--muted);padding-left:20px;margin:14px 0 20px}
li{margin:4px 0}
a.btn{display:inline-block;padding:12px 22px;border-radius:12px;background:var(--accent);color:var(--on);font-weight:600;text-decoration:none}
a.btn:focus-visible{outline:3px solid var(--accent);outline-offset:3px}
small{display:block;margin-top:16px;color:var(--muted)}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--red);margin-right:6px;animation:p 1.6s ease-in-out infinite}
@keyframes p{50%{opacity:.25}}
@media (prefers-reduced-motion:reduce){.dot{animation:none}}
</style>
</head>
<body>
<main>
<div class="mark" aria-hidden="true"><svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 2l20 20"/><path d="M8.5 16.4a5 5 0 0 1 7 0"/><path d="M5 12.9a10 10 0 0 1 5.2-2.7"/><path d="M19 12.9a10 10 0 0 0-2-1.5"/><path d="M2 8.8a15 15 0 0 1 4.2-2.6"/><path d="M22 8.8a15 15 0 0 0-11.3-3.8"/><path d="M12 20h.01"/></svg></div>
<h1>Il server dell'ufficio non risponde</h1>
<p><span class="dot"></span>Top Cash Studio al momento non &egrave; raggiungibile da fuori ufficio.</p>
<ul>
<li>Il PC server pu&ograve; essere spento, in riavvio o senza Internet.</li>
<li>I dati sono al sicuro in ufficio: non si perde nulla.</li>
<li>Se sei in ufficio, usa l'indirizzo della rete locale.</li>
</ul>
<a class="btn" href="">Riprova</a>
<small id="t">Riprovo da solo tra 20 secondi.</small>
</main>
<script>
var n=20,t=document.getElementById('t');
setInterval(function(){n--;if(n<=0){location.reload();return;}t.textContent='Riprovo da solo tra '+n+' second'+(n===1?'o':'i')+'.';},1000);
</script>
</body>
</html>`;

const HEADERS = {
  'Cache-Control': 'no-store',
  'Retry-After': '20',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'X-TopCash-Offline': '1'
};

function offline(request) {
  const path = new URL(request.url).pathname;
  const wantsPage = request.method === 'GET' && !path.startsWith('/api/') && (request.headers.get('Accept') || '').includes('text/html');
  if (wantsPage) {
    return new Response(PAGE, { status: 503, headers: { ...HEADERS, 'Content-Type': 'text/html; charset=utf-8',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'" } });
  }
  return new Response(JSON.stringify({ error: "Il server dell'ufficio non risponde. Riprova tra poco.", offline: true }),
    { status: 503, headers: { ...HEADERS, 'Content-Type': 'application/json; charset=utf-8' } });
}

export default {
  async fetch(request) {
    let response;
    try { response = await fetch(request); }
    catch { return offline(request); }
    return DOWN(response.status) ? offline(request) : response;
  }
};
