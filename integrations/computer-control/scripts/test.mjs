import { readdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = new URL('../build/computer/', import.meta.url);
const rawBudget = process.env.PLOWSHARE_COMPUTER_TEST_TIMEOUT_MS ?? '60000';
const budget = Number(rawBudget);
if (
  !/^\d+$/.test(rawBudget) ||
  !Number.isSafeInteger(budget) ||
  budget < 1 ||
  budget > 300000
)
  throw new Error('Test budget must be 1..300000 milliseconds');
const deadline = performance.now() + budget;
const tests = (await readdir(root, { recursive: true }))
  .filter((file) => file.endsWith('.test.js'))
  .sort()
  .map((file) => fileURLToPath(new URL(file, root)));
if (tests.length === 0) throw new Error('No compiled computer-control tests');
const remaining = Math.ceil(deadline - performance.now());
if (remaining <= 0)
  throw new Error('Test discovery exhausted the shared deadline');
const grouped = process.platform !== 'win32';
const child = spawn(
  process.execPath,
  ['--test', `--test-timeout=${remaining}`, ...tests],
  {
    stdio: 'inherit',
    detached: grouped,
  },
);
let expired = false;
let drain;
const timer = setTimeout(() => {
  expired = true;
  console.error(
    'Computer-control test deadline expired; terminating owned test work.',
  );
  if (grouped && child.pid !== undefined) {
    try {
      process.kill(-child.pid, 'SIGKILL');
    } catch (error) {
      if (error.code !== 'ESRCH')
        console.error('Test process-group termination failed.');
    }
  } else child.kill('SIGKILL');
  drain = setTimeout(() => {
    console.error(
      `Test process ${child.pid ?? 'unknown'} did not drain; inspect it before reuse.`,
    );
    process.exit(1);
  }, 5000);
}, remaining);
child.once('error', () => {
  clearTimeout(timer);
  clearTimeout(drain);
  process.exitCode = 1;
});
child.once('close', (code) => {
  clearTimeout(timer);
  clearTimeout(drain);
  process.exitCode = expired ? 1 : (code ?? 1);
});
