import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type { Operation } from 'plowshare-client-ts/operations/direct';
import type { Payloads, Reply } from 'plowshare-client-ts';
import type { ProviderConnection } from './provider.ts';
import type { IntegrationConfig } from './config.ts';
import { runIntegration } from './runner.ts';
import {
  Credentials,
  Sessions,
  registration,
  options,
  id,
} from './provider.test-support.ts';
import { object } from './codec.ts';

await test('runner registers with a retained UUID, publishes, stops intake and withdraws on shutdown', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'mcp-runner-'));
  const controller = new AbortController();
  const credentials = new Credentials();
  const sessions = new Sessions();
  const seen: string[] = [];
  const config: IntegrationConfig = {
    origin: 'https://example.invalid',
    tokenEnvironment: 'TOKEN',
    journalDirectory: directory,
    pollMs: 50,
    workers: [{ binding: id, generation: 1, options }],
  };
  let currentRegistration = registration;
  const connection: ProviderConnection = {
    async request<K extends Operation>(
      operation: K,
      payload: Payloads[K],
    ): Promise<Reply<K>> {
      seen.push(operation);
      const request = object(payload);
      let response: unknown;
      if (operation === 'tool.scope.worker.status')
        response = { binding: id, registration: null };
      else if (operation === 'tool.scope.worker.register') {
        assert.equal(typeof request.requestId, 'string');
        if (typeof request.requestId !== 'string')
          throw new Error('Missing registration identity');
        currentRegistration = {
          ...registration,
          registration: request.requestId,
        };
        credentials.credential = {
          ...credentials.credential,
          registration: request.requestId,
        };
        response = currentRegistration;
      } else if (operation === 'tool.scope.worker.credential')
        response = await credentials.release(null);
      else if (operation === 'tool.scope.worker.publish') {
        const publication = object(request.publication);
        response = {
          project: publication.project,
          topic: publication.topic,
          requestId: publication.requestId,
          position: '1',
          publishedAt: '2026-01-01T00:00:00Z',
        };
      } else if (operation === 'tool.scope.worker.consume') {
        const consumption = object(request.consumption);
        controller.abort();
        response = {
          project: consumption.project,
          topic: consumption.topic,
          group: consumption.group,
          consumerId: consumption.consumerId,
          status: 'EMPTY',
          batchId: null,
          fence: null,
          through: '0',
          expiresAt: null,
          expiredThrough: null,
          events: [],
        };
      } else throw new Error('Unexpected runner operation');
      return {
        kind: 'success',
        outcome: { code: 'OK', payload: decodeReply(operation, response) },
      };
    },
  };
  try {
    await runIntegration(connection, config, sessions, controller.signal);
    assert.deepEqual(seen, [
      'tool.scope.worker.status',
      'tool.scope.worker.register',
      'tool.scope.worker.credential',
      'tool.scope.worker.publish',
      'tool.scope.worker.consume',
      'tool.scope.worker.publish',
    ]);
    assert.ok(sessions.closed);
    assert.equal(sessions.calls, 0);
    const files = await readdir(join(directory, id));
    assert.equal(files.includes('worker.lock'), false);
    assert.ok(files.some((file) => file.endsWith('.deployment.json')));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
