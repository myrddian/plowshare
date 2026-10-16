// Trust only the public fixture certificate in the spawned test process. Never disable TLS.
import { readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = new URL('../', import.meta.url);
const tests = readdirSync(new URL('build/mcp/', root))
  .filter((name) => name.endsWith('.test.js'))
  .sort()
  .map((name) => fileURLToPath(new URL('build/mcp/' + name, root)));
if (!tests.length) throw new Error('No compiled MCP tests');
const result = spawnSync(process.execPath, ['--test', ...tests], {
  stdio: 'inherit',
  env: {
    ...process.env,
    NODE_EXTRA_CA_CERTS: fileURLToPath(
      new URL('src/fixtures/mcp-test-cert.pem', root),
    ),
  },
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
