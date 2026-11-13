namespace TopCash.Server;
/// The portal reading scripts, copied unchanged from the Windows app (src/*Window.cs) so the server reads every
/// portal exactly the same way. Generated: keep them identical to the app when a portal changes its pages.
static class PortalJs
{
 public const string DtrList="""
 (()=>{const t=document.querySelector('table');const login=location.pathname.startsWith('/auth');if(!t)return JSON.stringify({Rows:[],Next:false,Total:0,Page_:0,Login:login,Columns:false});
  const head=[...t.querySelectorAll('thead th')].map(x=>x.innerText.trim().toUpperCase());
  const cols=['MATRICOLA','TAGS','CLIENTE','FIRMWARE','GE','ULTIMA COMUNICAZIONE'].every(h=>head.includes(h));
  const ix=h=>head.indexOf(h);const txt=c=>(c?.innerText||'').trim();
  const rows=[...t.querySelectorAll('tbody tr')].filter(r=>r.cells.length>=head.length&&head.length>0).map(r=>{const c=r.cells;
   const s=c[ix('MATRICOLA')];const serial=(s.querySelector('h6')?.innerText||s.innerText).trim();
   const tags=[...c[ix('TAGS')].querySelectorAll('span.label')].map(x=>x.innerText.trim()).filter(Boolean).join(' ');
   const lines=txt(c[ix('CLIENTE')]).split('\n').map(x=>x.trim()).filter(Boolean);
   const ge=txt(c[ix('GE')]),z=txt(c[ix('ULTIMA COMUNICAZIONE')]);
   return [serial,tags,lines[0]||'',(lines[1]||'').replace(/^\(|\)$/g,''),txt(c[ix('FIRMWARE')]),
    (ge.match(/GE\s*(\d+)/)||[])[1]||'',(ge.match(/(\d{2}\/\d{2}\/\d{4})/)||[])[1]||'',
    (z.match(/Z\s*(\d+)/)||[])[1]||'',(z.match(/(\d{2}\/\d{2}\/\d{4} \d{2}:\d{2})/)||[])[1]||''];});
  const next=[...document.querySelectorAll('button')].find(b=>b.querySelector('i.ki-bold-arrow-next'));
  const tot=(document.body.innerText.match(/Totale:\s*([\d.]+)/)||[])[1];const pg=(document.body.innerText.match(/Pagina\s*(\d+)/)||[])[1];
  return JSON.stringify({Rows:rows,Next:!!next&&!next.disabled,Total:tot?+tot.replace(/\./g,''):0,Page_:pg?+pg:0,Login:login,Columns:cols});})()
 """;
 public const string DtrDetail="""
 ((serial)=>{const d=document.querySelector('[role=dialog]');if(!d)return null;const L=d.innerText.split(String.fromCharCode(10)).map(s=>s.trim()).filter(Boolean);
  if(!L.includes(serial)||!L.includes('Ultimi interventi')||!L.includes('Scadenza cert.'))return null;
  const val=l=>{const i=L.indexOf(l);return i>=0?(L[i+1]||''):'';};const ci=L.indexOf('Scadenza cert.');
  const cert=/^\d{2}\/\d{2}\/\d{4}$/.test(L[ci+1]||'')?L[ci+1]:'';const li=L.indexOf('LICENZE',ci);const blk=L.slice(ci+(cert?2:1),li>ci?li:ci+7).filter(x=>!/^Attenzione/i.test(x));const warn=L.filter(x=>/^Attenzione/i.test(x)).join(' ');
  const vi=blk.findIndex(x=>/^(IT)?\d{11}$|^[A-Z0-9]{16}$/i.test(x));const vat=vi>=0?blk[vi]:'';const hi=vi>=0?vi-1:blk.length-1;const holder=hi>=0?blk[hi]:'';const address=blk.slice(0,Math.max(0,hi)).join(' ').replace(/^[,\s]+/,'').trim();
  const ev=(a,b)=>{const i=L.indexOf(a),j=b?L.indexOf(b,i+1):-1;if(i<0)return[];const seg=L.slice(i+1,j>i?j:undefined);const out=[];
   for(let k=0;k<seg.length;k++){if(/^\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}(:\d{2})?$/.test(seg[k])){out.push({At:seg[k],Text:seg[k+1]||''});k++;}}return out;};
  const lic=[...d.querySelectorAll('table tbody tr')].map(tr=>[...tr.cells].map(c=>c.innerText.trim())).filter(c=>c.length>=6&&c.some(Boolean)).map(c=>{const [f,t]=(c[1]||'').split(' - ');return{Client:c[0],From:f||'',To:t||'',Status:c[2],Name:c[3],Type:c[4],AutoRenew:c[5]};});
  const ge=val('GE');
  return JSON.stringify({Serial:serial,Holder:holder,Vat:vat,CertDue:cert,LastVp:val('Ultima VP'),GeInit:(ge.match(/iniz\.\s*(\d{2}\/\d{2}\/\d{4})/)||[])[1]||'',
   Licenses:lic,Errors:ev('Ultimi errori','Ultimi interventi'),Works:ev('Ultimi interventi','Chiudi'),Extensions:'',Warning:warn,Address:address});})
 """;
 public const string ItalList="""
 ((need,dev)=>{const ok=location.hostname==='oneplus.italretail.it';const t=document.querySelector('table');const tot=(document.body.innerText.match(/Totale elementi:\s*([\d.]+)/)||[])[1];
  if(!ok||!t||tot===undefined)return JSON.stringify({Rows:[],Total:-1,Login:!ok,Columns:false});
  const head=[...t.querySelectorAll('thead th')].map(x=>x.innerText.trim().split(String.fromCharCode(10))[0].trim().toUpperCase());const ix=h=>head.findIndex(x=>x.startsWith(h));
  const cols=need.every(h=>ix(h)>=0);const txt=c=>(c?.innerText||'').trim().replace(/\s+/g,' ');
  const rows=!cols?[]:[...t.querySelectorAll('tbody tr')].filter(r=>r.cells.length>=head.length&&txt(r.cells[ix(need[0])]).length>0).map(r=>{const v=need.map(h=>txt(r.cells[ix(h)]));
   if(dev){const a=r.cells[ix(need[0])].querySelector('a');v.splice(1,0,((a?.getAttribute('href')||'').match(/devices\/(\d+)/)||[])[1]||'');}return v;});
  return JSON.stringify({Rows:rows,Total:+tot.replace(/\./g,''),Login:false,Columns:cols});})
 """;
 public const string ItalDetail="""
 ((serial)=>{if(location.hostname!=='oneplus.italretail.it')return 'LOGIN';const T=(document.querySelector('main')||document.body).innerText;const L=T.split(String.fromCharCode(10)).map(s=>s.trim()).filter(Boolean);
  if(!L.includes('Matricola: '+serial)||!L.includes('Cronologia aggiornamenti'))return null;
  const val=l=>{const x=L.find(s=>s.startsWith(l+':'));return x?x.slice(l.length+1).trim():'';};const num=s=>{const m=String(s).match(/\d+/);return m?+m[0]:0;};
  const tab=h=>[...document.querySelectorAll('table')].find(t=>[...t.querySelectorAll('thead th')].some(x=>x.innerText.trim().startsWith(h)));
  const rows=t=>t?[...t.querySelectorAll('tbody tr')].map(r=>[...r.cells].map(c=>c.innerText.trim().replace(/\s+/g,' '))):[];const dt=/^\d{2}-\d{2}-\d{4}/;
  const free=val('Spazio libero DGFE');
  return JSON.stringify({Serial:serial,SubFrom:val('Data inizio'),SubTo:val('Data fine'),AutoRenew:T.includes('Disattiva rinnovo automatico'),Firmware:val('Versione firmware'),Closures:num(val('Chiusure N°')),DgfeNumber:num(val('DGFE Numero')),DgfeSize:val('Dimensione del DGFE'),DgfeFree:free?num(free):-1,RtState:val('Stato RT'),Activated:val('Data di attivazione'),Registered:val('Data del censimento'),CertFrom:val('Data creazione del certificato'),CertDue:val('Data scadenza del certificato'),DataAt:(T.match(/dati aggiornati il\s*(\d{2}-\d{2}-\d{4}( \d{2}:\d{2}(:\d{2})?)?)/)||[])[1]||'',Hw:val('Versione HW'),Wifi:val('WiFi firmware version'),Seasonal:val('Stagionale'),Clock:val('Impostazione orario'),
   Updates:rows(tab('Data aggiornamento')).filter(c=>c.length>=3&&dt.test(c[0])).map(c=>({At:c[0],From:c[1],To:c[2].split(' ')[0]})),
   Dgfe:rows(tab('Matricola')).filter(c=>c.length>=5&&dt.test(c[3])).map(c=>({Duration:c[1].replace(/^I\s+/,''),Number:c[2],At:c[3],Status:c[4]}))});})
 """;
 public const string RchApi="""
 (async(path,params)=>{
  if(location.hostname!=='xdealer.rch.it')return{login:true};
  try{
   if(window.axios&&window.axios.get)return{data:(await window.axios.get(path,{params})).data};
   const r=await fetch(path+'?'+new URLSearchParams(params),{credentials:'include',headers:{Accept:'application/json','X-Requested-With':'XMLHttpRequest'}});
   if([401,403,419].includes(r.status))return{login:true};if(!r.ok)return{error:'HTTP '+r.status};return{data:await r.json()};
  }catch(e){const s=e&&e.response&&e.response.status;if([401,403,419].includes(s))return{login:true};return{error:String(s?'HTTP '+s:(e&&e.message)||e)};}
 })
 """;
 public const string RchMap="""
 ((d)=>{const S=v=>v==null?'':typeof v==='object'?String(v.name??v.description??v.label??v.code??v.value??''):String(v);
  const N=v=>{const n=parseInt(v,10);return isNaN(n)?0:n;};
  return(d.data||[]).map(e=>{const dd=(e.dealer_devices||e['dealer-devices']||e.dealerDevices||[])[0]||{};const c=e.customer||dd.customer||{};const g=e.diagnostic_data||{};
   const live=!e.is_replaced_by;const up=live&&(e.has_update||e.purchased_version)?(S(e.has_update?e.available_version:e.purchased_version)||'sì'):live&&e.has_fiscal_updates&&e.available_version_fiscal_update?S(e.available_version_fiscal_update):'';
   const free=g.dgfe_perc_free;const ser=S(e.serial_number).trim();
   return[ser,JSON.stringify({Serial:ser,PortalId:S(e.id),Part:S(e.part_number),Type:S(e.device_type??e.type),State:S(e.state??e.status??e.device_state),
    Client:S(c.name),Vat:S(c.vat_number),City:S(c.city),Province:S(c.province),Phone:S(c.phone),Email:S(c.email),Note:S(e.notes??dd.notes),Labels:(e.labels||dd.labels||[]).map(S).filter(Boolean).join(', '),
    Contract:!!e.has_maintenance_contract,Update:up,Firmware:S(e.version??e.firmware_version??e.current_version??e.fw_version),
    DgfeFree:free==null||free===''||isNaN(+free)?-1:Math.round(+free),DgfeNumber:N(g.dgfe_number),LastVerification:S(g.last_verification_date),ZDate:S(g.z_report_date),ZNumber:N(g.z_report_number),
    CertFrom:S(g.cert_start_date),CertDue:S(g.cert_expire_date),Activated:S(g.act_date),Registered:S(g.device_reg_date)})];}).filter(r=>r[0]);})
 """;
 public const string ZucSortHeader="""(()=>{const t=__GRID__;const h=[...t.querySelectorAll('[id$="_title_cell"]')].find(h=>h.textContent.trim().toUpperCase()==='MATRICOLA');if(h)h.click();})()""";
 public const string ZucSortAsc="""(()=>{const li=[...document.querySelectorAll('li.grid_orderby_li.asc')].find(li=>li.textContent.trim()==='Crescente');if(li){li.click();return true;}return false;})()""";
 public const string ZucNext="""(()=>{const t=__GRID__;const a=t?.querySelector('a[title="Pagina successiva"]:not([class*="_dis"])');if(a){a.click();return true;}return false;})()""";
 public const string ZucGrid="""
   (()=>{const t=__GRID__;if(!t)return null;const h=t.querySelector('tr[id$="_titles_row"]');if(!h)return null;const clean=c=>(c.innerText||'').replace(/[-]/g,'').replace(/\s+/g,' ').trim();return {Headers:[...h.children].map(c=>(c.querySelector('[id$="_title_cell"]')?.textContent||c.innerText||'').replace(/[-]/g,'').replace(/\s+/g,' ').trim()),Rows:[...t.querySelectorAll('tr')].filter(r=>/_row\d+$/.test(r.id)).map(r=>[...r.children].map(c=>clean(c)||c.querySelector('img')?.getAttribute('alt')||'')),Next:!!t.querySelector('a[title="Pagina successiva"]:not([class*="_dis"])'),NoData:/Nessun dato/i.test(t.innerText)};})()
   """;
}
