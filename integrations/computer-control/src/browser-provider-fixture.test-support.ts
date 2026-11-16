import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { toolDeploymentConfig } from 'plowshare-client-node/tools';
import { canonicalToolSchema, decodeToolCall } from 'plowshare-client-ts';
import type { ToolCall, ToolResult } from 'plowshare-client-ts';
import type { BrowserToolName } from './browser-control.ts';
import { FakeClock } from './contracts.test-support.ts';
import { FakeBrowserControlBackend } from './browser-provider.test-support.ts';
import { createComputerProvider, computerDeclarations } from './provider.ts';
import { decodeCloseArguments } from './contracts.ts';
import type { ComputerBackend } from './contracts.ts';
import { OperationDeadline } from './deadline.ts';
import {
  admissions,
  binding,
  call,
  client,
  MemoryJournal,
  metadata,
  record,
  RelayWire,
} from './provider.test-support.ts';

const allTools: readonly BrowserToolName[] = [
  'windows',
  'focus',
  'navigate',
  'pointer',
  'type',
  'key',
  'scroll',
];
export async function browserProviderFixture(
  options: {
    readonly http?: typeof fetch;
    readonly currentBearer?: () => Promise<string>;
    readonly maxImagesPerRun?: number;
    readonly backend?: FakeBrowserControlBackend;
    readonly clock?: FakeClock;
    readonly tools?: readonly BrowserToolName[];
    readonly additionalBackends?: readonly ComputerBackend[];
    readonly idleMs?: number;
  } = {},
) {
  const clock = options.clock ?? new FakeClock(),
    tools = options.tools ?? allTools,
    backend =
      options.backend ?? new FakeBrowserControlBackend(clock, { tools }),
    wire = new RelayWire(),
    connection = client(wire);
  const identity = createHash('sha256')
    .update(
      toolDeploymentConfig(
        binding,
        computerDeclarations(
          'computer_',
          { controlBudgetMs: 2000, observeBudgetMs: 10_000 },
          tools,
        ),
      ),
    )
    .digest('hex');
  const journal = new MemoryJournal(identity);
  const provider = await createComputerProvider({
    connection,
    binding,
    journal,
    backends: [backend, ...(options.additionalBackends ?? [])],
    browserControls: [backend],
    browserTools: tools,
    generation: 'controlled-generation',
    origin: 'https://fixture.invalid',
    currentBearer: options.currentBearer ?? (async () => 'fixture-bearer'),
    http: options.http ?? admissions(),
    clock,
    ...(options.idleMs === undefined ? {} : { idleMs: options.idleMs }),
    ...(options.maxImagesPerRun === undefined
      ? {}
      : { maxImagesPerRun: options.maxImagesPerRun }),
  });
  function request(
    operation: string,
    args: ToolCall['arguments'] = {},
  ): ToolCall {
    const declaration = provider.declarations.find(
      (value) => value.name === 'computer_' + operation,
    );
    assert.ok(declaration);
    return decodeToolCall(
      JSON.stringify({
        ...call(clock, operation, args),
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
  async function dispatch(request: ToolCall): Promise<ToolResult> {
    wire.calls.push(request);
    assert.equal(await provider.provider.poll(), 1);
    const result = journal.rows.get(request.invocationId)?.result;
    assert.ok(result);
    return result;
  }
  const invoke = (operation: string, args: ToolCall['arguments'] = {}) =>
    dispatch(request(operation, args));
  async function open() {
    const result = await invoke('open', { targetId: backend.targetId });
    assert.equal(result.state, 'COMPLETED');
    const value = record(metadata(result)['session']);
    return decodeCloseArguments({
      sessionId: value['sessionId'],
      generation: value['generation'],
    });
  }
  async function observe(session: ReturnType<typeof decodeCloseArguments>) {
    const result = await invoke('observe', {
      ...session,
      surfaceId: backend.surfaceId,
    });
    assert.equal(result.state, 'COMPLETED');
    const value = metadata(result),
      frame = record(value['frame']),
      snapshot = record(value['snapshot']);
    const frameId = frame['frameId'],
      snapshotId = snapshot['snapshotId'];
    assert.ok(typeof frameId === 'string');
    assert.ok(typeof snapshotId === 'string');
    const nodes = snapshot['nodes'];
    assert.ok(Array.isArray(nodes));
    const editable = nodes
      .map(record)
      .find((node) => node['editable'] === true);
    assert.ok(editable);
    const elementId = editable['elementId'];
    assert.ok(typeof elementId === 'string');
    return {
      result,
      frameId,
      target: {
        kind: 'element',
        snapshotId,
        elementId,
      },
    };
  }
  async function cleanup() {
    provider.stop();
    const deadline = new OperationDeadline(2000, clock);
    try {
      await provider.drain(deadline);
    } finally {
      deadline.dispose();
      connection.close();
    }
  }
  return {
    clock,
    tools,
    backend,
    wire,
    connection,
    journal,
    provider,
    request,
    dispatch,
    invoke,
    open,
    observe,
    cleanup,
  };
}
