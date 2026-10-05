// Operational provider registration and runtime configuration have no WS contracts.
// Use the administrator's saved CLI session; never replay an uncertain mutation.
import { Credentials } from '../../sdk/node/build/credentials.js';
import { readFile } from 'node:fs/promises';
const base = process.env.PLOWSHARE_URL;
if (!base) throw new Error('Supply PLOWSHARE_URL for search registration.');
const provider = process.env.PLOWSHARE_SEARCH_PROVIDER_URL;
if (!provider) throw new Error('Supply PLOWSHARE_SEARCH_PROVIDER_URL for search registration.');
const door = { base, fetch: (url, init) => fetch(url, { ...init, redirect: 'error', signal: AbortSignal.timeout(20_000) }) };
let phase = 'credential loading';
let status;
try {
  const store = new Credentials(base);
  // Operator verification can select its existing credential file without a
  // password login or putting the token in command arguments or shell output.
  const tokenFile = process.env.PLOWSHARE_TOKEN_FILE;
  const access = tokenFile
    ? (await readFile(tokenFile, 'utf8')).split(/\r?\n/, 1)[0].trim()
    : process.env.PLOWSHARE_TOKEN || (await store.renew(door)).access;
  if (!access || /\s/.test(access)) throw new Error('Invalid registration credential.');
  phase = 'provider registration';
  const response = await door.fetch(`${store.origin}/v1/search/providers`, {
    method: 'POST', headers: { Authorization: `Bearer ${access}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ baseUrl: provider }),
  });
  status = response.status;
  if (!response.ok) throw new Error(`Registration was refused (HTTP ${response.status}).`);
  status = undefined;
  if (process.argv.includes('--enable-ladder')) {
    phase = 'search ladder configuration';
    const headers = { Authorization: `Bearer ${access}` };
    const listing = await door.fetch(`${store.origin}/v1/config`, { headers });
    if (!listing.ok) throw new Error('Could not read the current search ladder.');
    const settings = await listing.json();
    if (!Array.isArray(settings)) throw new Error('Unexpected configuration response.');
    const setting = settings.find(row => row.key === 'plowshare.search.ladder');
    if (!setting || typeof setting.value !== 'string') throw new Error('Search ladder is unavailable.');
    const current = setting.value;
    if (!current.split(',').map(value => value.trim()).includes('searxng')) {
      const changed = await door.fetch(`${store.origin}/v1/config/plowshare.search.ladder`, {
        method: 'PUT', headers: { ...headers, 'Content-Type': 'text/plain' },
        body: current ? `${current},searxng` : 'searxng',
      });
      if (!changed.ok) throw new Error('Could not enable the search ladder.');
    }
    console.log('Registered the SearXNG adapter and enabled its search rung.');
  } else {
    console.log('Registered the SearXNG adapter. Set PLOWSHARE_SEARCH_LADDER=searxng in the deployment environment.');
  }
} catch {
  console.error(`Search registration failed during ${phase}${status ? ` (HTTP ${status})` : ''}. Select an existing operator credential or run plowshare-cli setup/login for this server as an administrator, then retry.`);
  process.exitCode = 1;
}
