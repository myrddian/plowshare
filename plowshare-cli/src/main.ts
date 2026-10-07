#!/usr/bin/env node
import { promptFileStore } from 'plowshare-client-node/filestores';
import { promptLogin, promptNewPassword, promptSetup } from './login.js';
import { run } from './run.js';

const interrupt = new AbortController();
const stop = (): void => interrupt.abort();
process.once('SIGINT', stop);
process.once('SIGTERM', stop);
process.exitCode = await run(process.argv.slice(2), {
  env: process.env,
  login: promptLogin,
  fileStoreSetup: promptFileStore,
  setup: promptSetup,
  newPassword: promptNewPassword,
  stdout: (text) => {
    process.stdout.write(text);
  },
  stderr: (text) => {
    process.stderr.write(text);
  },
  signal: interrupt.signal,
  stdin: async (signal) => {
    signal.throwIfAborted();
    const stopped = (): void => {
      process.stdin.destroy(new Error('input interrupted'));
    };
    signal.addEventListener('abort', stopped, { once: true });
    try {
      const chunks: Buffer[] = [];
      for await (const chunk of process.stdin)
        chunks.push(Buffer.from(chunk as Uint8Array));
      return Buffer.concat(chunks).toString('utf8');
    } finally {
      signal.removeEventListener('abort', stopped);
    }
  },
});
process.removeListener('SIGINT', stop);
process.removeListener('SIGTERM', stop);
