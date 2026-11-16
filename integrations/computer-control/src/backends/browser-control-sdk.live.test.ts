import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  FileToolJournal,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import { localLock } from 'plowshare-client-node/connections';
import { canonicalToolSchema, decodeToolCall } from 'plowshare-client-ts';
import type { ToolCall, ToolResult } from 'plowshare-client-ts';
import { computerDeclarations, createComputerProvider } from '../provider.ts';
import type { ComputerProvider } from '../provider.ts';
import {
  binding,
  client,
  metadata,
  record,
  RelayWire,
} from '../provider.test-support.ts';
import { OperationDeadline, systemClock } from '../deadline.ts';
import { decodeCloseArguments } from '../contracts.ts';
import { createBrowserControlBackend } from './browser-control-backend.ts';
import type { BrowserControlBackendHandle } from './browser-control-backend.ts';
import { awaitBrowserWork } from './browser-control-rpc.ts';

const executablePath = process.env['PLOWSHARE_COMPUTER_BROWSER_EXECUTABLE'];
async function bounded<T>(
  operation: (deadline: OperationDeadline) => Promise<T>,
  milliseconds = 5000,
  parent?: OperationDeadline,
): Promise<T> {
  const deadline = new OperationDeadline(milliseconds, undefined, parent);
  try {
    return await operation(deadline);
  } finally {
    deadline.dispose();
  }
}

await test(
  'real browser pixels and one click survive public SDK poll, HTTP admission and duplicate delivery',
  { skip: executablePath === undefined },
  async () => {
    if (executablePath === undefined)
      throw new Error('Explicit browser executable required');
    const overall = new OperationDeadline(30000);
    let directory: string | undefined,
      backend: BrowserControlBackendHandle | undefined,
      provider: ComputerProvider | undefined,
      connection: ReturnType<typeof client> | undefined;
    let effects = 0;
    const admitted: {
      readonly invocation: string;
      readonly sha256: string;
      readonly width: number;
      readonly height: number;
    }[] = [];
    let fixtureFailure: unknown;
    const failures: unknown[] = [];
    let actorsCleanupStarted = false,
      physicallyClosed = true;
    async function cleanupActors(): Promise<void> {
      if (actorsCleanupStarted) return;
      actorsCleanupStarted = true;
      if (provider !== undefined) {
        provider.stop();
        const ownedProvider = provider;
        try {
          await bounded((deadline) => ownedProvider.drain(deadline));
        } catch (error) {
          failures.push(error);
        }
      }
      connection?.close();
      if (backend !== undefined) {
        const ownedBackend = backend;
        try {
          await bounded((deadline) => ownedBackend.close(deadline));
          physicallyClosed = true;
        } catch (error) {
          failures.push(error);
        }
      }
    }
    function step<T>(
      operation: (deadline: OperationDeadline) => Promise<T>,
      milliseconds = 5000,
    ): Promise<T> {
      return bounded(operation, milliseconds, overall);
    }
    let effectResolve = () => {};
    const effectSeen = new Promise<void>((resolve) => {
      effectResolve = resolve;
    });
    async function serve(
      request: IncomingMessage,
      response: ServerResponse,
    ): Promise<void> {
      if (request.method === 'GET' && request.url === '/') {
        response.setHeader('Content-Type', 'text/html');
        response.end(
          "<!doctype html><meta charset=\"utf-8\"><button onclick=\"document.querySelector('output').textContent='Clicked';fetch('/effect',{method:'POST'})\">Click once</button><output>Ready</output>",
        );
        return;
      }
      if (request.method === 'POST' && request.url === '/effect') {
        effects++;
        effectResolve();
        response.end('Recorded');
        return;
      }
      assert.equal(request.method, 'POST');
      assert.equal(request.url, '/v1/images');
      assert.equal(
        request.headers.authorization,
        'Bearer synthetic-fixture-admission',
      );
      const chunks: Uint8Array[] = [];
      let length = 0;
      for await (const chunk of request) {
        const bytes: unknown = chunk;
        assert.ok(bytes instanceof Uint8Array);
        length += bytes.byteLength;
        if (length > 1100000)
          throw new Error('Fixture image upload exceeds bound');
        chunks.push(bytes);
      }
      const contentType = request.headers['content-type'];
      assert.equal(typeof contentType, 'string');
      if (typeof contentType !== 'string')
        throw new Error('Missing fixture image content type');
      const form = await new Response(Buffer.concat(chunks), {
        headers: { 'Content-Type': contentType },
      }).formData();
      const invocation = form.get('invocation'),
        requestId = form.get('requestId'),
        ordinal = form.get('ordinal'),
        file = form.get('file');
      assert.equal(form.get('profile'), 'invocation-image/1');
      assert.equal(ordinal, '0');
      assert.equal(typeof invocation, 'string');
      assert.equal(typeof requestId, 'string');
      assert.ok(file instanceof Blob);
      if (
        typeof invocation !== 'string' ||
        typeof requestId !== 'string' ||
        !(file instanceof Blob)
      )
        throw new Error('Malformed fixture image slot');
      const bytes = new Uint8Array(await file.arrayBuffer());
      assert.ok(bytes.byteLength >= 24);
      assert.deepEqual(
        [...bytes.slice(0, 8)],
        [137, 80, 78, 71, 13, 10, 26, 10],
      );
      const view = new DataView(
        bytes.buffer,
        bytes.byteOffset,
        bytes.byteLength,
      );
      const width = view.getUint32(16),
        height = view.getUint32(20);
      assert.equal(width, 320);
      assert.equal(height, 160);
      const sha256 = createHash('sha256').update(bytes).digest('hex');
      admitted.push({ invocation, sha256, width, height });
      response.setHeader('Content-Type', 'application/json');
      response.end(
        JSON.stringify({
          request: { invocation, requestId, ordinal: 0 },
          project: binding.project,
          owner: 'owner',
          producer: binding.account,
          imageId: `img_${sha256.slice(0, 32)}`,
          mimeType: 'image/png',
          width,
          height,
          byteLength: bytes.byteLength,
          sha256,
          admittedAt: new Date(systemClock.wallNow()).toISOString(),
        }),
      );
    }
    const server = createServer((request, response) => {
      serve(request, response).catch((error: unknown) => {
        fixtureFailure = error;
        response.statusCode = 500;
        response.end('Synthetic fixture refused request');
      });
    });
    server.requestTimeout = 2000;
    try {
      await step((deadline) =>
        awaitBrowserWork(
          new Promise<void>((resolve) =>
            server.listen(0, '127.0.0.1', resolve),
          ),
          deadline,
        ),
      );
      const address = server.address();
      assert.ok(address !== null && typeof address !== 'string');
      const origin = `http://127.0.0.1:${address.port}`;
      directory = await mkdtemp(join(tmpdir(), 'computer-browser-sdk-live-'));
      const ownedDirectory = directory;
      await localLock(
        join(ownedDirectory, 'journal'),
        'sdk-browser-fixture',
        async () => {
          try {
            backend = await step(
              (deadline) =>
                createBrowserControlBackend(
                  {
                    executablePath,
                    headless: true,
                    ownershipDirectory: join(ownedDirectory, 'ownership'),
                    control: {
                      enabled: true,
                      tools: ['pointer'],
                      allowedOrigins: [origin],
                    },
                    targets: [
                      {
                        targetId: 'fixture',
                        label: 'SDK owned fixture',
                        url: origin,
                        viewport: { width: 320, height: 160 },
                      },
                    ],
                  },
                  deadline,
                ),
              10000,
            );
            physicallyClosed = false;
            const ownedBackend = backend;
            const wire = new RelayWire();
            connection = client(wire);
            const budgets = { controlBudgetMs: 2000, observeBudgetMs: 5000 };
            const tools = ['pointer'] as const;
            const identity = createHash('sha256')
              .update(
                toolDeploymentConfig(
                  binding,
                  computerDeclarations('computer_', budgets, tools),
                ),
              )
              .digest('hex');
            const journal = await FileToolJournal.open(
              join(ownedDirectory, 'journal'),
              identity,
            );
            provider = await createComputerProvider({
              connection,
              binding,
              journal,
              backends: [ownedBackend.backend],
              browserControls: [ownedBackend.backend],
              browserTools: tools,
              generation: 'sdk-live-fixture',
              origin,
              currentBearer: async () => 'synthetic-fixture-admission',
              clock: systemClock,
              parentDeadline: () => overall,
              ...budgets,
              browserObservationReserveMs: 1000,
            });
            const ownedProvider = provider;
            function call(tool: string, args: ToolCall['arguments']): ToolCall {
              overall.requireActive();
              const declaration = ownedProvider.declarations.find(
                (declaration) => declaration.name === `computer_${tool}`,
              );
              assert.ok(declaration);
              return decodeToolCall(
                JSON.stringify({
                  schema: 'plowshare-tool/3',
                  resultProfile: 'content/1',
                  invocationId: randomUUID(),
                  project: binding.project,
                  provider: binding.provider,
                  account: 'owner',
                  run: 'sdk-browser-run',
                  call: randomUUID(),
                  tool: declaration.name,
                  deadline: new Date(
                    systemClock.wallNow() +
                      Math.min(15000, overall.remainingMs()),
                  ).toISOString(),
                  arguments: args,
                  ...(declaration.inputSchema === undefined
                    ? {}
                    : {
                        schemaFingerprint: createHash('sha256')
                          .update(canonicalToolSchema(declaration.inputSchema))
                          .digest('hex'),
                      }),
                }),
              );
            }
            async function dispatch(call: ToolCall): Promise<ToolResult> {
              wire.calls.push(call);
              assert.equal(await ownedProvider.provider.poll(), 1);
              const receipt = (await journal.all()).find(
                (receipt) => receipt.invocationId === call.invocationId,
              );
              assert.ok(
                receipt?.result !== null && receipt?.result !== undefined,
              );
              assert.equal(receipt.phase, 'done');
              return receipt.result;
            }
            const opened = await dispatch(
              call('open', { targetId: 'fixture' }),
            );
            assert.equal(opened.state, 'COMPLETED');
            const sessionMetadata = record(metadata(opened)['session']);
            const session = decodeCloseArguments({
              sessionId: sessionMetadata['sessionId'],
              generation: sessionMetadata['generation'],
            });
            const observed = await dispatch(
              call('observe', {
                ...session,
                surfaceId: 'viewport',
                structured: true,
              }),
            );
            assert.equal(observed.state, 'COMPLETED');
            assert.equal(observed.content?.parts[1]?.kind, 'IMAGE');
            const initial = metadata(observed),
              frame = record(initial['frame']),
              snapshot = record(initial['snapshot']);
            assert.equal(typeof frame['frameId'], 'string');
            assert.equal(typeof snapshot['snapshotId'], 'string');
            const nodes = snapshot['nodes'];
            assert.ok(Array.isArray(nodes));
            const button = nodes
              .map(record)
              .find(
                (node) =>
                  node['role'] === 'button' && node['name'] === 'Click once',
              );
            assert.ok(button);
            const snapshotId = snapshot['snapshotId'],
              elementId = button['elementId'];
            assert.ok(
              typeof snapshotId === 'string' && typeof elementId === 'string',
            );
            const click = call('pointer', {
              ...session,
              action: 'click',
              target: { kind: 'element', snapshotId, elementId },
              button: 'left',
              modifiers: [],
            });
            const result = await dispatch(click);
            assert.equal(result.state, 'COMPLETED');
            assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
            assert.equal(metadata(result)['observationOutcome'], 'CAPTURED');
            assert.equal(result.content?.parts[1]?.kind, 'IMAGE');
            assert.equal(result.content?.parts[1]?.use, 'OBSERVATION');
            await step(
              (deadline) => awaitBrowserWork(effectSeen, deadline),
              2000,
            );
            assert.equal(effects, 1);
            assert.equal(admitted.length, 2);
            assert.equal(result.admissions?.[0]?.sha256, admitted[1]?.sha256);
            assert.equal(result.admissions?.[0]?.width, 320);
            assert.equal(result.admissions?.[0]?.height, 160);
            assert.notEqual(admitted[0]?.sha256, admitted[1]?.sha256);
            const duplicate = await dispatch(click);
            assert.deepEqual(duplicate, result);
            assert.equal(effects, 1);
            assert.equal(admitted.length, 2);
            const closed = await dispatch(call('close', { ...session }));
            assert.equal(closed.state, 'COMPLETED');
            assert.equal((await journal.all()).length, 4);
            assert.equal(fixtureFailure, undefined);
            overall.requireActive();
          } finally {
            await cleanupActors();
          }
        },
        overall.signal,
      );
    } catch (error) {
      failures.push(error);
    } finally {
      await cleanupActors();
      if (server.listening) {
        try {
          await bounded((deadline) => {
            const closing = new Promise<void>((resolve, reject) =>
              server.close((error) =>
                error === undefined ? resolve() : reject(error),
              ),
            );
            server.closeAllConnections();
            return awaitBrowserWork(closing, deadline);
          });
        } catch (error) {
          failures.push(error);
        }
      }
      if (directory !== undefined && physicallyClosed) {
        try {
          await rm(directory, { recursive: true, force: true });
        } catch (error) {
          failures.push(error);
        }
      }
      overall.dispose();
    }
    if (failures.length > 0)
      throw new AggregateError(failures, 'SDK browser fixture failed');
  },
);
