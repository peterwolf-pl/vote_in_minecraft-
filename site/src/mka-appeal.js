/** Server-side MKA appeal integration. Never expose MKA credentials to Minecraft clients. */
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function mkaConfig(env = process.env) {
  const root = (env.MKA_APPEAL_API_BASE_URL || '').replace(/\/+$/, '');
  const apiKey = env.MKA_APPEAL_API_KEY || '';
  const serverId = env.MKA_APPEAL_SERVER_ID || '';
  if (!root || apiKey.length < 32 || !serverId || !root.startsWith('https://')) return null;
  const url = new URL(root);
  if (!url.hostname || url.username || url.password || url.search || url.hash || url.pathname !== '/') return null;
  return { url: root + '/api/minecraft/signatures', apiKey, serverId };
}

const fail = (status, message) => Object.assign(new Error(message), { status });

export async function mkaRequest(config, method = 'GET', payload = null, fetchImpl = fetch) {
  if (!config) throw fail(503, 'MKA appeal integration is not configured');
  const opts = {
    method,
    redirect: 'manual',
    signal: AbortSignal.timeout(6000),
    headers: { 'Accept': 'application/json' },
  };
  if (method === 'POST') {
    opts.headers['Authorization'] = 'Bearer ' + config.apiKey;
    opts.headers['X-Minecraft-Server'] = config.serverId;
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(payload);
  }
  let response;
  try { response = await fetchImpl(config.url, opts); }
  catch { throw fail(502, 'MKA signature API is unavailable'); }
  if (method === 'POST') {
    if (response.status === 201) return {ok: true, source: 'mkal.pl/apel'};
    if (response.status === 409) throw fail(409, 'Already signed or the appeal changed');
    if (response.status === 401) throw fail(502, 'MKA signature API authentication failed');
    if (response.status === 400) throw fail(400, 'Invalid signature or missing consent');
    throw fail(502, 'MKA signature API error ' + response.status);
  }
  if (response.status !== 200) throw fail(502, 'MKA appeal unavailable');
  let data;
  try { data = await response.json(); }
  catch { throw fail(502, 'Invalid MKA appeal response'); }
  if (!Number.isSafeInteger(data.appealId) || data.appealId < 1 ||
      typeof data.title !== 'string' || !data.title.trim() ||
      typeof data.body !== 'string' || !Number.isSafeInteger(data.total) || data.total < 0 ||
      data.active !== true) throw fail(502, 'Invalid MKA appeal metadata');
  return data;
}

export function ballotFromMka(template, remote) {
  return {
    ...template, title: remote.title.slice(0, 180),
    body: ((remote.lead || '') + '\n\n' + remote.body + '\n\nPełna treść: mkal.pl/apel').slice(0, 12000),
    appealId: remote.appealId,
    total: remote.total,
    publishSignatures: true,
  };
}

export function mkaSubmission(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) ||
      !uuidPattern.test(input.playerUuid || '') ||
      !Number.isSafeInteger(input.appealId) || input.appealId < 1 ||
      input.optionId != null || input.consentToPublishName !== true ||
      input.consentPrivacy !== true) throw fail(400, 'Invalid or unconsented Minecraft appeal signature');
  return {
    playerUuid: input.playerUuid.toLowerCase(),
    appealId: input.appealId,
    consentPrivacy: true,
    consentPublicDisplay: true,
  };
}
