import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {VoteStore,validateBallot,tally,hashVoter} from '../src/core.js';

let dir,store;
const vote={id:'v1',type:'vote',title:'Test',body:'A question',options:[{id:'yes',label:'Yes'},{id:'no',label:'No'}]};
const petition={id:'p1',type:'petition',title:'Please sign',body:'An appeal',options:[],publishSignatures:true};
const uuids=['d0b2ad4c-6a0a-4f8a-82cf-febcbb0cc4e9','d0b2ad4c-6a0a-4f8a-82cf-febcbb0cc4ea'];
before(async()=>{
 dir=await mkdtemp(join(tmpdir(),'votemc-'));
 store=new VoteStore(join(dir,'submissions.json'),'test-secret',async()=>[vote,petition]);
});
after(async()=>{if(dir)await rm(dir,{recursive:true,force:true});});
test('valid and invalid ballots',()=>{
 assert.equal(validateBallot(vote).id,'v1');
 assert.throws(()=>validateBallot({...vote,options:[{id:'yes',label:'A'},{id:'yes',label:'B'}]}));
});
test('duplicate prevention with concurrent votes',async()=>{
 const form={ballotId:'v1',playerUuid:uuids[0],playerName:'Builder123',optionId:'yes'};
 const result=await Promise.allSettled([store.submit(form),store.submit(form)]);
 assert.equal(result.filter(x=>x.status==='fulfilled').length,1);
 assert.equal(result.filter(x=>x.status==='rejected'&&x.reason.status===409).length,1);
 assert.equal(tally(vote,await store.read()).counts.yes,1);
});
test('invalid choice rejected',async()=>{
 await assert.rejects(store.submit({ballotId:'v1',playerUuid:uuids[1],playerName:'Steve42',optionId:'nope'}),{status:400});
});
test('public nick requires consent',async()=>{
 await store.submit({ballotId:'p1',playerUuid:uuids[0],playerName:'Builder123',consentToPublishName:false});
 await store.submit({ballotId:'p1',playerUuid:uuids[1],playerName:'AlexCool',consentToPublishName:true});
 const out=tally(petition,await store.read());
 assert.equal(out.total,2);
 assert.deepEqual(out.signatures.map(s=>s.name),['AlexCool']);
 assert.notEqual(hashVoter(uuids[0],'a'),hashVoter(uuids[0],'b'));
});
