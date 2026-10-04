// Operational provider registration and runtime configuration have no WS contracts.
// Use the administrator's saved CLI session; never replay an uncertain mutation.
import { Credentials } from '../../plowshare-client-node/build/credentials.js';
const base = process.env.PLOWSHARE_URL ?? 'http://127.0.0.1:8091';
const door = { base, fetch: (url, init) => fetch(url, { ...init, redirect: 'error', signal: AbortSignal.timeout(20_000) }) };
try {
  const store = new Credentials(base);
  const access = process.env.PLOWSHARE_TOKEN || (await store.renew(door)).access;
  const response = await door.fetch(`${store.origin}/v1/search/providers`, {
    method: 'POST', headers: { Authorization: `Bearer ${access}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ baseUrl: process.env.PLOWSHARE_SEARCH_PROVIDER_URL || 'http://searxng-provider:8086' }),
  });
  if (!response.ok) throw new Error(`Registration was refused (HTTP ${response.status}).`);
  if (process.argv.includes('--enable-ladder')) {
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
  console.error('Search registration failed. Run plowshare-cli setup or login for this server as an administrator, then retry.');
  process.exitCode = 1;
}
