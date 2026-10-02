import { basename } from 'node:path';
import { getAsset } from 'node:sea';

const aliases = { 'plowshare-cli': 'cli', 'plowshare-mcp': 'mcp', 'plowshare-talk': 'talk' };
const args = process.argv.slice(2);
let mode = aliases[basename(process.argv0)] ?? 'cli';
if (!aliases[basename(process.argv0)] && ['cli', 'mcp', 'talk'].includes(args[0])) mode = args.shift();
// Existing entry points read argv directly. Only consume the native mode selector.
process.argv.splice(2, process.argv.length - 2, ...args);
if (args.length === 1 && args[0] === '--version') {
  const provenance = JSON.parse(getAsset('build-info', 'utf8'));
  process.stdout.write(`Plowshare ${__PLOWSHARE_VERSION__} (${provenance.commit.slice(0, 8)}${provenance.trackedChanges ? '+dirty' : ''}; ${process.platform}-${process.arch}; bundled Node ${process.versions.node})\n`);
} else if (args.length === 1 && args[0] === '--licenses') {
  process.stdout.write(getAsset('licenses', 'utf8'));
} else if (mode === 'mcp') {
  await import('../plowshare-mcp/src/main.ts');
} else if (mode === 'talk') {
  const { talk } = await import('./distribution-talk.mjs');
  await talk(args);
} else {
  if ((args.includes('--help') || args.includes('-h')) && !args.includes('--json')) {
    process.stdout.write('plowshare [CLI command]\nplowshare talk [terminal options]\nplowshare mcp [stdio options]\nplowshare --version | --licenses\n\n');
  }
  await import('../plowshare-cli/src/main.ts');
}
