import { createServer } from 'node:http';
import { mkaConfig, mkaRequest, mkaSubmission, ballotFromMka } from './mka-appeal.js';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, join } from 'node:path';
import { isActive, publicBallot, readBallots, tally, safeEqual, VoteStore } from './core.js';

const root=resolve(fileURLToPath(new URL('..',import.meta.url)));
const ballotsPath=process.env.BALLOTS_FILE || join(root,'data/ballots.json');
const dataPath=process.env.SUBMISSIONS_FILE || join(root,'data/submissions.json');
const apiKey=process.env.MC_API_KEY;
const secret=process.env.VOTER_HASH_SECRET;
const serverId=process.env.MC_SERVER_ID || 'default';
if (!apiKey || apiKey.length<24 || !secret || secret.length<24) {
  console.error('Set MC_API_KEY and VOTER_HASH_SECRET (min 24 characters each)');
  process.exit(1);
}
const store=new VoteStore(dataPath,secret,()=>readBallots(ballotsPath));
const mka=mkaConfig();
async function loadWithExternal() {
 const ballots=await readBallots(ballotsPath);
 return Promise.all(ballots.map(async b=>{
   if(b.externalProvider!=='mka-appeal')return b;
   if(!mka || !isActive(b))return null;
   try{return ballotFromMka(b,await mkaRequest(mka));}catch(error){console.warn('MKA appeal unavailable:',error.message);return null;}
 })).then(items=>items.filter(Boolean));
}
const headers={
 'X-Content-Type-Options':'nosniff','X-Frame-Options':'DENY','Referrer-Policy':'no-referrer',
 'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'",
 'Cache-Control':'no-store'
};
const staticPaths={'/':['public/index.html','text/html; charset=utf-8'],
 '/style.css':['public/style.css','text/css; charset=utf-8'],
 '/app.js':['public/app.js','text/javascript; charset=utf-8']};
const send=(res,status,payload)=>{
 res.writeHead(status,{...headers,'Content-Type':'application/json; charset=utf-8'});
 res.end(JSON.stringify(payload));
};
const authorized=req=>safeEqual(req.headers.authorization,'Bearer '+apiKey)
 && safeEqual(req.headers['x-minecraft-server'],serverId);
function body(req) {
 return new Promise((resolve,reject)=>{
  let result='',length=0;
  req.on('data',chunk=>{length+=chunk.length;if(length>8192){reject(Object.assign(Error('Payload too large'),{status:413}));req.destroy();return;}result+=chunk;});
  req.on('end',()=>{try{resolve(JSON.parse(result));}catch{reject(Object.assign(Error('Invalid JSON'),{status:400}));}});
  req.on('error',reject);
 });
}
const app=createServer(async(req,res)=>{
 try{
  const path=new URL(req.url,'http://localhost').pathname;
  if(req.method==='GET'&&staticPaths[path]){
   const [file,type]=staticPaths[path];
   res.writeHead(200,{...headers,'Content-Type':type});
   res.end(await readFile(join(root,file)));return;
  }
  if(req.method==='GET'&&path==='/api/v1/health')return send(res,200,{ok:true});
  if(req.method==='GET'&&(path==='/api/v1/ballots'||path==='/api/v1/ballots/active')){
   const ballots=await loadWithExternal();
   return send(res,200,{ballots:(path.endsWith('/active')?ballots.filter(b=>isActive(b)):ballots).map(publicBallot)});
  }
  const match=/^\/api\/v1\/ballots\/([a-z0-9_-]{1,64})\/(results|submissions)$/.exec(path);
  if(match){
   const [,id,action]=match;
   if(req.method==='GET'&&action==='results'){
    const b=(await loadWithExternal()).find(x=>x.id===id);
    if(!b)return send(res,404,{error:'Unknown ballot'});
    if(b.externalProvider==='mka-appeal')return send(res,200,{ballot:publicBallot(b),total:b.total,counts:{},signatures:[]});
    return send(res,200,tally(b,await store.read()));
   }
   if(req.method==='POST'&&action==='submissions'){
    if(!authorized(req))return send(res,401,{error:'Unauthorized Minecraft server'});
    const data=await body(req);
    if(data.serverId!=null && data.serverId!==serverId)return send(res,403,{error:'Unknown server'});
    const ballot=(await readBallots(ballotsPath)).find(x=>x.id===id);
    if(!ballot)return send(res,404,{error:'Unknown ballot'});
    if(!isActive(ballot))return send(res,409,{error:'Ballot closed'});
    if(ballot.externalProvider==='mka-appeal'){
      const submission=mkaSubmission(data);
      return send(res,201,await mkaRequest(mka,'POST',submission));
    }
    return send(res,201,await store.submit({...data,ballotId:id}));
   }
  }
  send(res,404,{error:'Not found'});
 }catch(e){console.error(e.message);send(res,e.status||500,{error:e.status?e.message:'Internal error'});}
});
app.listen(Number(process.env.PORT||3000),'0.0.0.0',()=>console.log('Vote in Minecraft web running'));
