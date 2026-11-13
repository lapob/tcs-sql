// offline page: checks quietly in the background and opens Studio only once the server answers again
// (no reload every few seconds while the office PC is off)
var t=document.getElementById('t'),wait=5000,timer=0;
function check(){
 clearTimeout(timer);
 fetch('/api/health',{cache:'no-store'}).then(function(r){
  if(r.ok){t.textContent='Server di nuovo raggiungibile: apro Studio…';location.replace('/');return;}
  throw 0;
 }).catch(function(){
  wait=Math.min(wait*1.5,60000);
  t.textContent='Controllo da solo in sottofondo: si apre appena il server risponde.';
  timer=setTimeout(check,wait);
 });
}
t.textContent='Controllo da solo in sottofondo: si apre appena il server risponde.';
timer=setTimeout(check,wait);
addEventListener('online',check);
document.addEventListener('visibilitychange',function(){if(document.visibilityState==='visible')check();});
