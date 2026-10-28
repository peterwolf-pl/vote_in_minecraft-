const root=document.querySelector('#ballots');
const updated=document.querySelector('#last-update');
const el=(tag,cls,value)=>{
 const node=document.createElement(tag);
 if(cls)node.className=cls;
 if(value!==undefined)node.textContent=String(value);
 return node;
};
async function get(path){
 const response=await fetch(path,{cache:'no-store'});
 if(!response.ok)throw Error('HTTP '+response.status);
 return response.json();
}
function draw(result){
 const {ballot,counts,total,signatures}=result;
 const card=el('article','ballot');
 const top=el('div','top');
 top.append(el('span','type-label',ballot.type==='vote'?'GŁOSOWANIE':'APEL / PODPISY'),
  el('span','pill'+(ballot.active?'':' closed'),ballot.active?'AKTYWNE':'ZAMKNIĘTE'));
 card.append(top,el('h3','',ballot.title),el('p','body',ballot.body));
 const block=el('div','results');
 if(ballot.type==='vote')for(const option of ballot.options){
  const count=counts[option.id]??0,percent=total?Math.round(count*1000/total)/10:0;
  const row=el('div','result-row'),head=el('div','row-head'),track=el('div','track'),fill=el('div','fill');
  head.append(el('span','',option.label),el('span','',percent+'% · '+count));
  fill.style.width=percent+'%';track.append(fill);row.append(head,track);block.append(row);
 }
 const summary=el('div','summary');
 summary.append(el('strong','count',total),el('span','',ballot.type==='vote'?'ODDANYCH GŁOSÓW':'PODPISÓW'));
 block.append(summary);
 if(ballot.type==='petition'&&ballot.publishSignatures){
  const list=el('div','signatures');
  for(const s of signatures.slice(-100).reverse())list.append(el('span','signature',s.name));
  if(!signatures.length)list.append(el('span','empty','Brak publicznych podpisów'));
  block.append(list);
 }
 card.append(block);return card;
}
async function refresh(){
 try{
  const {ballots}=await get('/api/v1/ballots');
  const results=await Promise.all(ballots.map(b=>get('/api/v1/ballots/'+encodeURIComponent(b.id)+'/results')));
  root.replaceChildren(...results.map(draw));
  if(!results.length)root.replaceChildren(el('p','empty','Brak głosowań. Dodaj je do pliku ballots.json.'));
  updated.textContent='Ostatnia aktualizacja: '+new Date().toLocaleTimeString('pl-PL');
 }catch{
  if(!root.querySelector('.ballot'))root.replaceChildren(el('p','empty','Nie można pobrać wyników.'));
  updated.textContent='Błąd połączenia. Ponawiam...';
 }
}
refresh();setInterval(refresh,15000);
