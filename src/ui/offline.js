// offline page: retries by itself every 20 seconds
var n=20,t=document.getElementById('t');
setInterval(function(){n--;if(n<=0){location.reload();return;}t.textContent='Riprovo da solo tra '+n+' second'+(n===1?'o':'i')+'.';},1000);
