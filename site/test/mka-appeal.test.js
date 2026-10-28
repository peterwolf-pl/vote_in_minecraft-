import test from 'node:test';
import assert from 'node:assert/strict';
import { mkaConfig, mkaRequest, mkaSubmission, ballotFromMka } from '../src/mka-appeal.js';

const id='d0b2ad4c-6a0a-4f8a-82cf-febcbb0cc4e9';
const config={url:'https://mkal.pl/api/minecraft/signatures',apiKey:'a'.repeat(48),serverId:'default'};
test('MKA config is disabled without secure server credentials',()=>{
  assert.equal(mkaConfig({}),null);
  assert.equal(mkaConfig({MKA_APPEAL_API_BASE_URL:'http://mkal.pl',MKA_APPEAL_API_KEY:'a'.repeat(48),MKA_APPEAL_SERVER_ID:'default'}),null);
  assert.equal(mkaConfig({MKA_APPEAL_API_BASE_URL:'https://mkal.pl',MKA_APPEAL_API_KEY:'a'.repeat(48),MKA_APPEAL_SERVER_ID:'default'}).url,config.url);
});
test('MKA signatures never include name, city, or email supplied by player',()=>{
  const signed=mkaSubmission({playerUuid:id.toUpperCase(),appealId:1,consentPrivacy:true,consentToPublishName:true,playerName:'Steve',city:'fake',email:'x@x.com'});
  assert.deepEqual(signed,{playerUuid:id,appealId:1,consentPrivacy:true,consentPublicDisplay:true});
  assert.throws(()=>mkaSubmission({playerUuid:id,appealId:1,consentPrivacy:false,consentToPublishName:true}),{status:400});
  assert.throws(()=>mkaSubmission({playerUuid:id,appealId:1,consentPrivacy:true,consentToPublishName:false}),{status:400});
});
test('GET current MKA appeal yields live metadata and counter',async()=>{
  const data={appealId:9,title:'Apel o MKA',lead:'Prosimy o wsparcie',body:'Treść apelu',total:489,active:true};
  const get=async()=>({status:200,json:async()=>data});
  const remote=await mkaRequest(config,'GET',null,get);
  assert.equal(remote.total,489);
  const ballot=ballotFromMka({id:'mka-appeal',type:'petition'},remote);
  assert.equal(ballot.appealId,9);assert.match(ballot.body,/Treść apelu/);
});
test('POST sends only UUID and consent to authorized MKA backend',async()=>{
  let actual;
  const fetcher=async(url,opts)=>{actual={url,opts};return{status:201};};
  assert.deepEqual(await mkaRequest(config,'POST',mkaSubmission({playerUuid:id,appealId:9,consentPrivacy:true,consentToPublishName:true}),fetcher),{ok:true,source:'mkal.pl/apel'});
  assert.equal(actual.opts.headers.Authorization,'Bearer '+config.apiKey);
  assert.equal(actual.opts.headers['X-Minecraft-Server'],'default');
  assert.deepEqual(JSON.parse(actual.opts.body),{playerUuid:id,appealId:9,consentPrivacy:true,consentPublicDisplay:true});
});
test('MKA duplicate response remains a conflict',async()=>{
  await assert.rejects(mkaRequest(config,'POST',{},async()=>({status:409})),{status:409});
});
