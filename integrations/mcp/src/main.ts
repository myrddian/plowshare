import { readFile } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { connectPlowshare } from 'plowshare-client-node';
import { decodeConfig } from './config.ts';
import { HttpMcpSessions } from './session.ts';
import { runIntegration } from './runner.ts';

async function main(): Promise<void> {
  const path = process.argv[2];
  if (process.argv.length !== 3 || path === undefined || !isAbsolute(path))
    throw new Error(
      'Usage: node build/mcp/main.js /absolute/path/mcp-config.json',
    );
  const bytes = await readFile(path);
  if (bytes.byteLength > 65536)
    throw new Error('MCP configuration exceeds bound');
  const raw: unknown = JSON.parse(bytes.toString('utf8'));
  const config = decodeConfig(raw);
  const token = process.env[config.tokenEnvironment];
  if (!token || token.length > 16384)
    throw new Error('Configured executor token is missing or invalid');
  const controller = new AbortController();
  const stop = () => controller.abort();
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  const client = await connectPlowshare({
    origin: config.origin,
    token,
    timeoutMs: config.requestTimeoutMs,
  });
  try {
    await runIntegration(
      client,
      config,
      new HttpMcpSessions(config.clientInfo),
      controller.signal,
    );
  } finally {
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
    client.close();
  }
}
main().catch(() => {
  console.error(
    'MCP integration stopped. Check configuration, permissions and retained private recovery state.',
  );
  process.exitCode = 1;
});
