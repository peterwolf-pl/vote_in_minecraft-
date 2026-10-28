import { createHmac, timingSafeEqual, randomBytes } from 'node:crypto';
import { readFile, writeFile, rename, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';

const VALID_ID = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const VALID_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VALID_NAME = /^[a-zA-Z0-9_]{3,16}$/;
const error = (status, message) => Object.assign(new Error(message), { status });
export const safeEqual = (a, b) => {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
};
export function validateBallot(b) {
  if (!b || !VALID_ID.test(b.id ?? '')) throw error(500, 'Invalid ballot id');
  if (!['vote','petition'].includes(b.type)) throw error(500, 'Invalid ballot type');
  if (typeof b.title !== 'string' || !b.title.trim() || b.title.length > 180) throw error(500, 'Invalid title');
  if (typeof b.body !== 'string' || b.body.length > 12000) throw error(500, 'Invalid body');
  if (!Array.isArray(b.options)) throw error(500, 'Missing options array');
  if (b.type === 'vote' && (b.options.length < 2 || b.options.length > 8)) throw error(500, 'Vote needs 2-8 options');
  if (b.type === 'petition' && b.options.length !== 0) throw error(500, 'Petition cannot have options');
  const seen = new Set();
  for (const o of b.options) {
    if (!VALID_ID.test(o.id ?? '') || typeof o.label !== 'string' || !o.label.trim() || o.label.length > 120 || seen.has(o.id))
      throw error(500, 'Invalid option');
    seen.add(o.id);
  }
  for (const field of ['startsAt','endsAt'])
    if (b[field] != null && (!/^\d{4}-\d\d-\d\dT/.test(b[field]) || !Number.isFinite(Date.parse(b[field]))))
      throw error(500, 'Invalid date');
  return b;
}
export async function readBallots(path) {
  const { ballots } = JSON.parse(await readFile(path, 'utf8'));
  if (!Array.isArray(ballots)) throw error(500, 'Missing ballots array');
  const ids = new Set();
  for (const b of ballots) {
    validateBallot(b);
    if (ids.has(b.id)) throw error(500, 'Duplicate ballot ID');
    ids.add(b.id);
  }
  return ballots;
}
export const isActive = (b, now=Date.now()) => b.enabled !== false &&
  (!b.startsAt || Date.parse(b.startsAt) <= now) &&
  (!b.endsAt || Date.parse(b.endsAt) > now);
export const publicBallot = b => ({
  id:b.id, type:b.type, title:b.title, body:b.body, options:b.options,
  startsAt:b.startsAt ?? null, endsAt:b.endsAt ?? null,
  publishSignatures:b.publishSignatures === true, active:isActive(b)
});
export const hashVoter = (uuid, secret) => createHmac('sha256', secret).update(uuid.toLowerCase()).digest('hex');
export function tally(b, submissions) {
  const rows = submissions.filter(s => s.ballotId === b.id);
  const counts = Object.fromEntries(b.options.map(o => [o.id, 0]));
  for (const row of rows) if (Object.hasOwn(counts, row.optionId)) counts[row.optionId]++;
  return {ballot:publicBallot(b), total:rows.length, counts,
    signatures:b.type==='petition' && b.publishSignatures === true
      ? rows.filter(s => s.consentToPublishName === true).map(s => ({name:s.playerName, at:s.createdAt}))
      : []};
}
export class VoteStore {
  #queue=Promise.resolve();
  constructor(path, secret, loadBallots) { this.path=path; this.secret=secret; this.loadBallots=loadBallots; }
  async read() {
    try {
      const value = JSON.parse(await readFile(this.path, 'utf8'));
      if (!Array.isArray(value.submissions)) throw Error('Corrupt vote store');
      return value.submissions;
    } catch(e) { if (e.code === 'ENOENT') return []; throw e; }
  }
  submit(input) {
    const job = this.#queue.then(async () => {
      if (!input || typeof input !== 'object' || Array.isArray(input)) throw error(400, 'Invalid JSON');
      const { ballotId, optionId, playerUuid, playerName, consentToPublishName } = input;
      if (!VALID_ID.test(ballotId ?? '') || !VALID_UUID.test(playerUuid ?? '') || !VALID_NAME.test(playerName ?? ''))
        throw error(400, 'Invalid player or ballot');
      const b = (await this.loadBallots()).find(x => x.id === ballotId);
      if (!b) throw error(404, 'Unknown ballot');
      if (!isActive(b)) throw error(409, 'Ballot closed');
      if (b.type === 'vote' && !b.options.some(o => o.id === optionId)) throw error(400,'Invalid option');
      if (b.type === 'petition' && optionId != null) throw error(400,'Petition cannot have options');
      const rows=await this.read();
      const voter=hashVoter(playerUuid,this.secret);
      if (rows.some(s=>s.ballotId===ballotId && s.voter===voter)) throw error(409,'Already submitted');
      const canPublish=b.type==='petition' && b.publishSignatures===true && consentToPublishName===true;
      const row={ballotId,optionId:b.type==='vote'?optionId:null,voter,
        playerName:canPublish?playerName:null,consentToPublishName:canPublish,createdAt:new Date().toISOString()};
      rows.push(row);
      await mkdir(dirname(this.path),{recursive:true});
      const temp=this.path+'.'+process.pid+'.'+randomBytes(4).toString('hex')+'.tmp';
      await writeFile(temp,JSON.stringify({version:1,submissions:rows},null,2)+'\n',{mode:0o600});
      await rename(temp,this.path);
      return {ok:true,ballotId,recordedAt:row.createdAt};
    });
    this.#queue=job.catch(()=>{});
    return job;
  }
}
