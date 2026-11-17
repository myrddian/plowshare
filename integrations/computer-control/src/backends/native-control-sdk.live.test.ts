import assert from 'node:assert/strict';
import { createHash, randomInt, randomUUID } from 'node:crypto';
import { mkdtemp, open } from 'node:fs/promises';
import type { FileHandle } from 'node:fs/promises';
import { constants } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as pause } from 'node:timers/promises';
import test from 'node:test';
import {
  FileToolJournal,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import {
  atomicPrivateWrite,
  privateFile,
  localLock,
} from 'plowshare-client-node/connections';
import { canonicalToolSchema, decodeToolCall } from 'plowshare-client-ts';
import type { ToolCall, ToolResult } from 'plowshare-client-ts';
import { absolutePrivatePath } from '../config.ts';
import { computerDeclarations, createComputerProvider } from '../provider.ts';
import type { ComputerProvider } from '../provider.ts';
import { decodeCloseArguments } from '../contracts.ts';
import {
  decodeNativeSnapshot,
  decodeNativeWindows,
} from '../native-control.ts';
import type { NativeSnapshot } from '../native-control.ts';
import { OperationDeadline } from '../deadline.ts';
import {
  framePoint,
  inverseTransform,
  transformPoint,
  validateTransform,
} from '../geometry.ts';
import { NativeFixture } from '../native-fixture.test-support.ts';
import {
  binding,
  client,
  metadata,
  record,
  RelayWire,
} from '../provider.test-support.ts';
import { createMacOSControlBackend } from './macos-control.ts';
import { awaitBrowserWork } from './browser-control-rpc.ts';
import { parseNativeReplyJson } from './native-control-process.ts';

const configPath = process.env['PLOWSHARE_COMPUTER_NATIVE_ACCEPTANCE_CONFIG'];
const tools = ['windows', 'focus', 'pointer', 'type', 'key', 'scroll'] as const;

/** Only bounded private configuration bytes materialize before any owned app is launched. */
async function readConfiguration(
  path: string,
): Promise<Readonly<Record<string, unknown>>> {
  const deadline = new OperationDeadline(2000);
  let selected: FileHandle | undefined;
  try {
    await awaitBrowserWork(privateFile(path), deadline);
    deadline.requireActive();
    const opening = open(
      path,
      constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK,
    ).then(async (handle) => {
      if (deadline.remainingMs() <= 0 || deadline.signal.aborted) {
        await handle.close();
        throw new Error(
          'Native acceptance configuration opened after its deadline',
        );
      }
      selected = handle;
      return handle;
    });
    const handle = await awaitBrowserWork(opening, deadline);
    const info = await awaitBrowserWork(handle.stat(), deadline);
    assert.ok(info.isFile() && info.size <= 8192);
    const bytes = Buffer.alloc(8193);
    const read = await awaitBrowserWork(
      handle.read(bytes, 0, bytes.length, 0),
      deadline,
    );
    assert.ok(read.bytesRead <= 8192);
    deadline.requireActive();
    return record(parseNativeReplyJson(bytes.subarray(0, read.bytesRead)));
  } finally {
    deadline.dispose();
    if (selected !== undefined) {
      const cleanup = new OperationDeadline(2000);
      try {
        await awaitBrowserWork(selected.close(), cleanup);
      } finally {
        cleanup.dispose();
      }
    }
  }
}

/**
 * Explicit LOCAL_SDK_NATIVE acceptance: real owned GUI applications/helper and
 * public SDK polling/journaling, with a synthetic Relay/admission peer. This
 * establishes neither a configured Plowshare server nor model visual reasoning.
 * It never operates a user's unrelated app or automatically retries an effect.
 */
await test(
  'owned macOS applications receive native input through the shared SDK provider',
  { skip: configPath === undefined, timeout: 150_000 },
  async () => {
    assert.equal(process.platform, 'darwin');
    const file = absolutePrivatePath(configPath);
    const config = await readConfiguration(file);
    assert.deepEqual(Object.keys(config).sort(), [
      'displayId',
      'helperExecutable',
      'leftBundleId',
      'leftFixtureExecutable',
      'rightBundleId',
      'rightFixtureExecutable',
    ]);
    const helperExecutable = absolutePrivatePath(config['helperExecutable']),
      leftExecutable = absolutePrivatePath(config['leftFixtureExecutable']),
      rightExecutable = absolutePrivatePath(config['rightFixtureExecutable']);
    const leftId = config['leftBundleId'],
      rightId = config['rightBundleId'],
      displayId = config['displayId'];
    assert.ok(
      typeof leftId === 'string' &&
        typeof rightId === 'string' &&
        leftId !== rightId &&
        /^[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/u.test(leftId) &&
        /^[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/u.test(rightId),
    );
    assert.ok(
      typeof displayId === 'number' &&
        Number.isSafeInteger(displayId) &&
        displayId > 0 &&
        displayId <= 0xffffffff,
    );
    const fixturePlan = { leftId, rightId, displayId };
    const overall = new OperationDeadline(120_000);
    let directory: string | undefined;
    const evidence: {
      version: 1;
      boundary: 'LOCAL_SDK_NATIVE';
      state: string;
      stages: string[];
      gaps: string[];
      fixtures: {
        instanceId: string;
        processId: number;
        bundleId: string;
        title: string;
      }[];
      images: {
        invocation: string;
        sha256: string;
        width: number;
        height: number;
        file: string;
        byteLength: number;
        requestId: string;
        resultLinked: boolean;
      }[];
      cleanup: string[];
    } = {
      version: 1,
      boundary: 'LOCAL_SDK_NATIVE',
      state: 'RUNNING',
      stages: [],
      gaps: [],
      fixtures: [],
      images: [],
      cleanup: [],
    };
    const failures: unknown[] = [];
    let provider: ComputerProvider | undefined,
      connection: ReturnType<typeof client> | undefined,
      backend:
        Awaited<ReturnType<typeof createMacOSControlBackend>> | undefined;
    const fixtures: NativeFixture[] = [];
    let cleaning = false;
    const pendingSetup = new Set<Promise<void>>();
    const pendingImages = new Set<Promise<void>>();
    async function setup<T>(
      work: (deadline: OperationDeadline) => Promise<T>,
      retain: (value: T) => void,
      disposeLate: (value: T, deadline: OperationDeadline) => Promise<void>,
      budget = 2000,
    ): Promise<T> {
      const deadline = overall.child(budget);
      deadline.requireActive();
      const physical = work(deadline).then(async (value) => {
        // Install ownership before any deadline check can reject the caller.
        retain(value);
        if (
          cleaning ||
          deadline.remainingMs() <= 0 ||
          deadline.signal.aborted
        ) {
          const lateCleanup = new OperationDeadline(2000);
          try {
            await disposeLate(value, lateCleanup);
          } finally {
            lateCleanup.dispose();
          }
          throw new Error(
            'Native acceptance setup completed late; no replacement was started',
          );
        }
        return value;
      });
      const tracked = physical
        .then(
          () => {},
          (error: unknown) => {
            if (cleaning) failures.push(error);
          },
        )
        .finally(() => {
          pendingSetup.delete(tracked);
        });
      pendingSetup.add(tracked);
      try {
        return await awaitBrowserWork(physical, deadline);
      } finally {
        deadline.dispose();
      }
    }

    async function step<T>(
      work: (deadline: OperationDeadline) => Promise<T>,
      budget = 2000,
    ): Promise<T> {
      const deadline = overall.child(budget);
      try {
        return await awaitBrowserWork(work(deadline), deadline);
      } finally {
        deadline.dispose();
      }
    }
    const syntheticAdmission: typeof fetch = async (_url, init) => {
      init?.signal?.throwIfAborted();
      assert.ok(init?.body instanceof FormData);
      const form = init.body,
        image = form.get('file'),
        invocation = form.get('invocation'),
        requestId = form.get('requestId');
      assert.ok(
        image instanceof Blob &&
          typeof invocation === 'string' &&
          typeof requestId === 'string',
      );
      const bytes = new Uint8Array(await image.arrayBuffer());
      init?.signal?.throwIfAborted();
      assert.ok(bytes.length >= 24 && bytes.length <= 1_048_576);
      assert.deepEqual(
        [...bytes.subarray(0, 8)],
        [137, 80, 78, 71, 13, 10, 26, 10],
      );
      const view = new DataView(
          bytes.buffer,
          bytes.byteOffset,
          bytes.byteLength,
        ),
        width = view.getUint32(16),
        height = view.getUint32(20),
        sha256 = createHash('sha256').update(bytes).digest('hex');
      assert.ok(width > 0 && height > 0 && Math.max(width, height) <= 1600);
      assert.ok(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u.test(
          invocation,
        ),
      );
      assert.ok(directory !== undefined && evidence.images.length < 16);
      const imageFile = join(directory, 'admitted-' + invocation + '.png');
      const imageDeadline = overall.child(2000);
      const cancel = () => imageDeadline.cancel();
      init?.signal?.addEventListener('abort', cancel, { once: true });
      const physical = (async () => {
        imageDeadline.requireActive();
        init?.signal?.throwIfAborted();
        const handle = await open(imageFile, 'wx', 0o600);
        try {
          imageDeadline.requireActive();
          await handle.writeFile(bytes);
          await handle.sync();
          imageDeadline.requireActive();
        } finally {
          await handle.close();
        }
      })();
      const tracked = physical
        .then(
          () => {},
          (error: unknown) => {
            if (cleaning) failures.push(error);
          },
        )
        .finally(() => {
          pendingImages.delete(tracked);
        });
      pendingImages.add(tracked);
      try {
        await awaitBrowserWork(physical, imageDeadline);
        imageDeadline.requireActive();
        init?.signal?.throwIfAborted();
        evidence.images.push({
          invocation,
          sha256,
          width,
          height,
          file: imageFile,
          byteLength: bytes.length,
          requestId,
          resultLinked: false,
        });
      } finally {
        init?.signal?.removeEventListener('abort', cancel);
        imageDeadline.dispose();
      }
      return new Response(
        JSON.stringify({
          request: { invocation, requestId, ordinal: 0 },
          project: binding.project,
          owner: 'owner',
          producer: binding.account,
          imageId: 'img_' + sha256.slice(0, 32),
          mimeType: 'image/png',
          width,
          height,
          byteLength: bytes.length,
          sha256,
          admittedAt: new Date().toISOString(),
        }),
      );
    };
    async function exercise(
      activeDirectory: string,
      plan: {
        readonly leftId: string;
        readonly rightId: string;
        readonly displayId: number;
      },
    ): Promise<void> {
      const { leftId, rightId, displayId } = plan;
      try {
        const left = new NativeFixture(leftExecutable, overall);
        fixtures.push(left);
        const right = new NativeFixture(rightExecutable, overall);
        fixtures.push(right);
        const fixtureIdentities = new Map<
          string,
          { instanceId: string; processId: number; title: string }
        >();
        for (const [fixture, slot, id] of [
          [left, 'left', leftId],
          [right, 'right', rightId],
        ] as const) {
          const instanceId = randomUUID();
          const status: Readonly<Record<string, unknown>> = await step(
            (deadline) =>
              fixture.request(
                'configure',
                {
                  instanceId,
                  displayId,
                  slot,
                  marker: randomInt(1_000_000).toString().padStart(6, '0'),
                },
                deadline,
              ),
          );
          assert.equal(status['bundleId'], id);
          assert.equal(status['instanceId'], instanceId);
          const processId = status['processId'];
          assert.equal(processId, fixture.processId);
          assert.ok(
            typeof processId === 'number' &&
              Number.isSafeInteger(processId) &&
              processId > 0,
          );
          const title = 'Controlled native fixture ' + slot + ' ' + instanceId;
          fixtureIdentities.set(id, { instanceId, processId, title });
          evidence.fixtures.push({
            instanceId,
            processId,
            bundleId: id,
            title,
          });
          assert.equal(status['windowVisible'], true);
        }
        evidence.stages.push('owned-applications-configured');
        backend = await setup(
          (deadline) =>
            createMacOSControlBackend(
              {
                helperExecutable,
                ownershipDirectory: join(activeDirectory, 'ownership'),
                targets: [
                  {
                    targetId: 'fixture',
                    label: 'Owned native acceptance display',
                    surfaceId: 'display:' + displayId,
                  },
                ],
                control: {
                  enabled: true,
                  tools,
                  allowedBundleIds: [leftId, rightId],
                },
              },
              deadline,
            ),
          (value) => {
            backend = value;
          },
          (value, deadline) => value.close(deadline),
          10_000,
        );
        const wire = new RelayWire();
        connection = client(wire);
        const budgets = { controlBudgetMs: 2000, observeBudgetMs: 10_000 };
        const identity = createHash('sha256')
          .update(
            toolDeploymentConfig(
              binding,
              computerDeclarations('computer_', budgets, [], tools),
            ),
          )
          .digest('hex');
        // The enclosing public SDK localLock owns this journal through provider
        // drain and every physical setup continuation, including late construction.
        const journal = await setup(
          () =>
            FileToolJournal.open(join(activeDirectory, 'journal'), identity),
          () => {},
          async () => {},
        );
        const initializedBackend = backend;
        const initializedConnection = connection;
        provider = await setup(
          () =>
            createComputerProvider({
              connection: initializedConnection,
              binding,
              journal,
              backends: [initializedBackend.backend],
              nativeControls: [initializedBackend.backend],
              nativeArmingControls: [initializedBackend.backend],
              nativeTools: tools,
              generation: randomUUID(),
              origin: 'https://fixture.invalid',
              currentBearer: async () => 'synthetic-fixture-admission',
              http: syntheticAdmission,
              parentDeadline: () => overall,
              ...budgets,
              nativeObservationReserveMs: 3000,
              maxImagesPerRun: 16,
            }),
          (value) => {
            provider = value;
          },
          async (value, deadline) => {
            value.stop();
            await value.drain(deadline);
          },
        );
        const owned = provider;
        let latest:
          | { snapshot: NativeSnapshot; result: ToolResult; observedAt: number }
          | undefined;
        const observedFrames = new Map<string, number>();
        function retainObservation(result: ToolResult): void {
          const value = metadata(result);
          if (value['snapshot'] !== undefined && value['frame'] !== undefined) {
            const frameId = record(value['frame'])['frameId'];
            assert.ok(typeof frameId === 'string');
            const observedAt = observedFrames.get(frameId) ?? performance.now();
            if (!observedFrames.has(frameId)) {
              assert.ok(observedFrames.size < 16);
              observedFrames.set(frameId, observedAt);
            }
            latest = {
              snapshot: decodeNativeSnapshot(value['snapshot']),
              result,
              observedAt,
            };
          }
        }
        function call(tool: string, args: ToolCall['arguments']): ToolCall {
          overall.requireActive();
          const declaration = owned.declarations.find(
            (row) => row.name === 'computer_' + tool,
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
              run: 'native-owned-fixture',
              call: randomUUID(),
              tool: declaration.name,
              deadline: new Date(
                Date.now() + Math.min(10_000, overall.remainingMs()),
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
        async function dispatch(request: ToolCall): Promise<ToolResult> {
          wire.calls.push(request);
          assert.equal(await step(() => owned.provider.poll(), 10_000), 1);
          const receipt = (await journal.all()).find(
            (row) => row.invocationId === request.invocationId,
          );
          assert.ok(receipt?.result);
          assert.equal(receipt.phase, 'done');
          const result = receipt.result;
          const resultMetadata = metadata(result);
          if (
            resultMetadata['observationOutcome'] === 'CAPTURED' ||
            resultMetadata['frame'] !== undefined ||
            resultMetadata['snapshot'] !== undefined
          ) {
            assert.equal(
              result.admissions?.length,
              1,
              'Captured feedback must carry one admission',
            );
            const imageParts = result.content?.parts.filter(
              (part) => part.kind === 'IMAGE',
            );
            assert.equal(
              imageParts?.length,
              1,
              'Captured feedback must carry one public SDK image part',
            );
            assert.ok(
              imageParts?.[0]?.use === 'OBSERVATION' ||
                imageParts?.[0]?.use === 'BOTH',
            );
          }
          for (const admission of result.admissions ?? []) {
            const retained = evidence.images.find(
              (image) =>
                image.invocation === request.invocationId &&
                image.sha256 === admission.sha256,
            );
            assert.ok(retained);
            assert.equal(admission.request.invocation, request.invocationId);
            assert.equal(admission.request.requestId, retained.requestId);
            assert.equal(admission.request.ordinal, 0);
            assert.equal(admission.project, request.project);
            assert.equal(admission.owner, request.account);
            assert.equal(admission.producer, binding.account);
            assert.equal(admission.mimeType, 'image/png');
            assert.equal(retained.width, admission.width);
            assert.equal(retained.height, admission.height);
            assert.equal(retained.byteLength, admission.byteLength);
            assert.ok(
              result.content?.parts.some(
                (part) =>
                  part.kind === 'IMAGE' &&
                  part.imageId === admission.imageId &&
                  part.ordinal === admission.request.ordinal &&
                  (part.use === 'OBSERVATION' || part.use === 'BOTH'),
              ),
            );
            retained.resultLinked = true;
          }
          if (
            result.state === 'COMPLETED' &&
            [
              'computer_focus',
              'computer_pointer',
              'computer_type',
              'computer_key',
              'computer_scroll',
            ].includes(request.tool)
          ) {
            assert.equal(metadata(result)['actionOutcome'], 'DISPATCHED');
            if (metadata(result)['observationOutcome'] !== 'CAPTURED') {
              evidence.gaps.push(
                'POST_ACTION_OBSERVATION_FAILED:' + request.tool,
              );
              assert.fail(
                'Owned native input completed without its required fresh observation',
              );
            }
          }
          // The provider exposes only freshly admitted metadata. Reuse its actual
          // post-action observation rather than spending another image before every input.
          if (request.tool !== 'computer_windows') latest = undefined;
          retainObservation(result);
          return result;
        }
        const invoke = (tool: string, args: ToolCall['arguments']) =>
          dispatch(call(tool, args));
        const opened = await invoke('open', { targetId: 'fixture' });
        assert.equal(opened.state, 'COMPLETED');
        const openedSession = record(metadata(opened)['session']),
          session = decodeCloseArguments({
            sessionId: openedSession['sessionId'],
            generation: openedSession['generation'],
          });
        const owner = {
          account: 'owner',
          project: binding.project,
          provider: binding.provider,
          run: 'native-owned-fixture',
        };
        const inventory = await invoke('windows', { ...session });
        assert.equal(inventory.state, 'COMPLETED');
        const eligible = decodeNativeWindows(metadata(inventory)['windows']);
        assert.ok(
          eligible.windows.every(
            (window) =>
              window.title === fixtureIdentities.get(window.bundleId)?.title,
          ),
        );
        for (const id of [leftId, rightId])
          assert.equal(
            eligible.windows.filter(
              (window) =>
                window.bundleId === id &&
                window.title === fixtureIdentities.get(id)?.title &&
                window.state === 'available',
            ).length,
            1,
          );
        const arming = owned.nativeArming;
        assert.ok(arming);
        const pending = await step((deadline) =>
          arming.request(
            owner,
            session,
            { requestId: randomUUID(), durationMs: 30_000 },
            deadline,
          ),
        );
        assert.equal(pending.state, 'PENDING');
        console.error(
          'Native acceptance is waiting for the helper local confirmation. Only owned fixture applications will receive test input.',
        );
        const confirmation = overall.child(30_000);
        try {
          for (;;) {
            confirmation.requireActive();
            const deadline = confirmation.child(2000);
            let status;
            try {
              status = await arming.status(owner, session, deadline);
            } finally {
              deadline.dispose();
            }
            if (status.state === 'ARMED' && status.proof.state === 'fresh')
              break;
            assert.equal(
              status.state === 'PENDING' || status.state === 'ARMED',
              true,
              'Local grant expired or revoked; no automatic rearm',
            );
            await pause(200, undefined, { signal: confirmation.signal });
          }
        } finally {
          confirmation.dispose();
        }
        evidence.stages.push('local-grant-verified');
        let expectedApplication: string | undefined;
        async function focus(bundleId: string): Promise<void> {
          const expectedFixture = fixtureIdentities.get(bundleId);
          assert.ok(expectedFixture);
          const listed = await invoke('windows', { ...session });
          assert.equal(listed.state, 'COMPLETED');
          const windows = decodeNativeWindows(metadata(listed)['windows']),
            window = windows.windows.find(
              (row) =>
                row.bundleId === bundleId &&
                row.title === expectedFixture.title &&
                row.state === 'available',
            );
          assert.ok(window);
          assert.ok(
            windows.windows.every(
              (row) =>
                (row.bundleId === leftId || row.bundleId === rightId) &&
                row.title === fixtureIdentities.get(row.bundleId)?.title,
            ),
          );
          const focused = await invoke('focus', {
            ...session,
            windowsId: windows.windowsId,
            surfaceId: window.surfaceId,
          });
          assert.equal(focused.state, 'COMPLETED');
          expectedApplication = window.applicationId;
        }
        async function observe(): Promise<{
          snapshot: NativeSnapshot;
          result: ToolResult;
        }> {
          const result = await invoke('observe', {
            ...session,
            surfaceId: 'display:' + String(displayId),
          });
          assert.equal(result.state, 'COMPLETED');
          return {
            snapshot: decodeNativeSnapshot(metadata(result)['snapshot']),
            result,
          };
        }
        async function target(name: string, editable = false) {
          const observed =
              latest !== undefined &&
              performance.now() - latest.observedAt <= 4000
                ? latest
                : await observe(),
            node = observed.snapshot.nodes.find(
              (row) => row.name === name && (!editable || row.editable),
            );
          assert.ok(node);
          assert.equal(
            observed.snapshot.guard.applicationId,
            expectedApplication,
          );
          return {
            kind: 'element',
            snapshotId: observed.snapshot.snapshotId,
            elementId: node.elementId,
          };
        }
        await focus(leftId);
        const click = call('pointer', {
          ...session,
          action: 'click',
          target: await target('Toggle pixels'),
        });
        assert.equal((await dispatch(click)).state, 'COMPLETED');
        let state = await step((deadline) =>
          left.request('status', {}, deadline),
        );
        assert.equal(record(state['counts'])['buttonActivated'], 1);
        assert.equal(state['toggled'], true);
        await dispatch(click); // Duplicate delivery of the exact invocation is a receipt read.
        state = await step((deadline) => left.request('status', {}, deadline));
        assert.equal(record(state['counts'])['buttonActivated'], 1);
        evidence.stages.push('native-click-and-sdk-deduplication');
        assert.equal(
          (
            await invoke('pointer', {
              ...session,
              action: 'click',
              target: await target('Unicode input', true),
            })
          ).state,
          'COMPLETED',
        );
        const text = 'Native café 🌱\n第二行';
        assert.equal(
          (
            await invoke('type', {
              ...session,
              target: await target('Unicode input', true),
              mode: 'insert',
              text,
            })
          ).state,
          'COMPLETED',
        );
        state = await step((deadline) => left.request('status', {}, deadline));
        assert.equal(state['text'], text);
        evidence.stages.push('unicode-responder-state');
        function counter(
          value: Readonly<Record<string, unknown>>,
          name: string,
        ): number {
          const item = record(value['counts'])[name];
          assert.ok(
            typeof item === 'number' &&
              Number.isSafeInteger(item) &&
              item >= 0 &&
              item <= 1_000_000,
          );
          return item;
        }
        const beforeKey = counter(state, 'keyDown');
        const keyTarget = await target('Unicode input', true);
        assert.ok(latest);
        const keyFrame = record(metadata(latest.result)['frame'])['frameId'];
        assert.ok(typeof keyFrame === 'string');
        assert.equal(
          (
            await invoke('key', {
              ...session,
              frameId: keyFrame,
              expectedFocus: keyTarget,
              key: 'ArrowLeft',
            })
          ).state,
          'COMPLETED',
        );
        state = await step((deadline) => left.request('status', {}, deadline));
        assert.ok(counter(state, 'keyDown') > beforeKey);
        assert.equal(state['text'], text);
        evidence.stages.push('native-key-responder-counter');

        const beforeScroll = counter(state, 'scroll');
        assert.equal(
          (
            await invoke('scroll', {
              ...session,
              target: await target('Scroll sample'),
              dx: 0,
              dy: 64,
              units: 'pixels',
            })
          ).state,
          'COMPLETED',
        );
        state = await step((deadline) => left.request('status', {}, deadline));
        assert.ok(counter(state, 'scroll') > beforeScroll);
        assert.ok(
          typeof state['scrollY'] === 'number' && state['scrollY'] !== 0,
        );
        evidence.stages.push('native-scroll-responder-counter');

        const canvasObservation =
          latest !== undefined && performance.now() - latest.observedAt <= 4000
            ? latest
            : await observe();
        const canvas = canvasObservation.snapshot.nodes.find(
          (node) => node.name === 'Drag canvas',
        );
        assert.ok(canvas?.bounds);
        const canvasBounds = canvas.bounds;
        const frame = record(metadata(canvasObservation.result)['frame']);
        const dragFrameId = frame['frameId'];
        assert.ok(typeof dragFrameId === 'string');
        const transform = validateTransform(frame['transform']);
        const inverse = inverseTransform(transform);
        const width = frame['width'],
          height = frame['height'];
        assert.ok(typeof width === 'number' && typeof height === 'number');
        const points = [0.25, 0.5, 0.75].map((portion, index) => {
          // AX bounds are Quartz points. Convert through the returned affine
          // transform, then validate the rounded image pixel maps inside that same canvas.
          const selected = transformPoint(inverse, {
            x: canvasBounds.x + canvasBounds.width * portion,
            y: canvasBounds.y + canvasBounds.height * 0.5,
          });
          const x = Math.round(selected.x),
            y = Math.round(selected.y);
          const desktop = framePoint(transform, width, height, x, y);
          assert.ok(
            desktop.x > canvasBounds.x &&
              desktop.x < canvasBounds.x + canvasBounds.width &&
              desktop.y > canvasBounds.y &&
              desktop.y < canvasBounds.y + canvasBounds.height,
          );
          return { x, y, atMs: index * 100 };
        });
        const beforeDrag = counter(state, 'dragCompleted');
        assert.equal(
          (
            await invoke('pointer', {
              ...session,
              action: 'drag',
              frameId: dragFrameId,
              points,
            })
          ).state,
          'COMPLETED',
        );
        state = await step((deadline) => left.request('status', {}, deadline));
        assert.equal(counter(state, 'dragCompleted'), beforeDrag + 1);
        evidence.stages.push('affine-native-drag-responder-counter');

        for (const [name, secure] of [
          ['Secure test input', true],
          ['Disabled test input', false],
        ] as const) {
          const before = await step((deadline) =>
            left.request('status', {}, deadline),
          );
          const refused = await invoke('type', {
            ...session,
            target: await target(name),
            mode: 'insert',
            text: 'must-not-appear',
          });
          assert.equal(refused.state, 'REJECTED');
          assert.equal(metadata(refused)['actionOutcome'], 'NOT_DISPATCHED');
          assert.equal(
            metadata(refused)['code'],
            secure ? 'SENSITIVE_TARGET' : 'NOT_EDITABLE',
          );
          const after = await step((deadline) =>
            left.request('status', {}, deadline),
          );
          assert.equal(counter(after, 'keyDown'), counter(before, 'keyDown'));
          assert.equal(
            counter(after, 'textChanged'),
            counter(before, 'textChanged'),
          );
        }
        evidence.stages.push('secure-disabled-ax-input-refused');

        await focus(rightId);
        assert.equal(
          (await step((deadline) => right.request('status', {}, deadline)))[
            'applicationActive'
          ],
          true,
        );
        await focus(leftId);
        async function exactOwnedControl(name: string, gapCode: string) {
          const view =
            latest !== undefined &&
            performance.now() - latest.observedAt <= 4000
              ? latest
              : await observe();
          assert.equal(view.snapshot.guard.applicationId, expectedApplication);
          const node = view.snapshot.nodes.find((item) => item.name === name);
          if (node === undefined) {
            evidence.gaps.push(gapCode);
            throw new Error(
              'Owned fixture AX hierarchy is unsupported: ' + gapCode,
            );
          }
          return {
            kind: 'element',
            snapshotId: view.snapshot.snapshotId,
            elementId: node.elementId,
          };
        }
        assert.equal(
          (
            await invoke('pointer', {
              ...session,
              action: 'click',
              target: await target('Open menu'),
            })
          ).state,
          'COMPLETED',
        );
        const menuResult = await invoke('pointer', {
          ...session,
          action: 'click',
          target: await exactOwnedControl(
            'Record fixture selection',
            'OWNED_MENU_ITEM_NOT_EXPOSED',
          ),
        });
        if (menuResult.state !== 'COMPLETED')
          evidence.gaps.push('OWNED_MENU_GUARD_REFUSED');
        assert.equal(menuResult.state, 'COMPLETED');
        state = await step((deadline) => left.request('status', {}, deadline));
        assert.equal(counter(state, 'menuSelected'), 1);
        evidence.stages.push('owned-native-menu-semantic-state');

        assert.equal(
          (
            await invoke('pointer', {
              ...session,
              action: 'click',
              target: await target('Open dialog'),
            })
          ).state,
          'COMPLETED',
        );
        state = await step((deadline) => left.request('status', {}, deadline));
        assert.equal(counter(state, 'dialogOpened'), 1);
        assert.equal(state['dialogOpen'], true);
        const dialogResult = await invoke('pointer', {
          ...session,
          action: 'click',
          target: await exactOwnedControl(
            'Record fixture confirmation',
            'OWNED_DIALOG_BUTTON_NOT_EXPOSED',
          ),
        });
        if (dialogResult.state !== 'COMPLETED')
          evidence.gaps.push('OWNED_DIALOG_GUARD_REFUSED');
        assert.equal(dialogResult.state, 'COMPLETED');
        state = await step((deadline) => left.request('status', {}, deadline));
        assert.equal(counter(state, 'dialogClosed'), 1);
        assert.equal(state['dialogOpen'], false);
        evidence.stages.push('owned-native-dialog-semantic-state');

        const old =
          latest !== undefined && performance.now() - latest.observedAt <= 4000
            ? latest
            : await observe();
        const oldFrame = record(metadata(old.result)['frame']);
        const frameId = oldFrame['frameId'];
        assert.ok(typeof frameId === 'string');
        const stillOwned = old.snapshot.nodes.find(
          (node) => node.name === 'Toggle pixels',
        );
        assert.ok(stillOwned?.bounds);
        const oldTransform = validateTransform(oldFrame['transform']);
        const stalePoint = transformPoint(inverseTransform(oldTransform), {
          x: stillOwned.bounds.x + stillOwned.bounds.width / 2,
          y: stillOwned.bounds.y + stillOwned.bounds.height / 2,
        });
        const staleX = Math.round(stalePoint.x),
          staleY = Math.round(stalePoint.y);
        const oldWidth = oldFrame['width'],
          oldHeight = oldFrame['height'];
        assert.ok(
          typeof oldWidth === 'number' && typeof oldHeight === 'number',
        );
        const safelyOwned = framePoint(
          oldTransform,
          oldWidth,
          oldHeight,
          staleX,
          staleY,
        );
        assert.ok(
          safelyOwned.x > stillOwned.bounds.x &&
            safelyOwned.x < stillOwned.bounds.x + stillOwned.bounds.width &&
            safelyOwned.y > stillOwned.bounds.y &&
            safelyOwned.y < stillOwned.bounds.y + stillOwned.bounds.height,
        );
        const beforeRevocation = await step((deadline) =>
          left.request('status', {}, deadline),
        );
        const revoked = await step((deadline) =>
          arming.revoke(owner, session, deadline),
        );
        assert.equal(revoked.state, 'REVOKED');
        const stale = await invoke('pointer', {
          ...session,
          action: 'click',
          target: { kind: 'point', frameId, x: staleX, y: staleY },
        });
        assert.equal(stale.state, 'REJECTED');
        const afterRevocation = await step((deadline) =>
          left.request('status', {}, deadline),
        );
        assert.equal(
          counter(afterRevocation, 'mouseDown'),
          counter(beforeRevocation, 'mouseDown'),
        );
        assert.equal(
          counter(afterRevocation, 'buttonActivated'),
          counter(beforeRevocation, 'buttonActivated'),
        );
        evidence.stages.push(
          'cross-application-focus-and-revoked-reference-refusal',
        );
        assert.ok(
          evidence.images.length >= 4 &&
            evidence.images.some((image) => image.resultLinked) &&
            new Set(evidence.images.map((row) => row.sha256)).size > 1,
        );
        evidence.state = 'CHECKS_PASSED';
      } catch (error) {
        evidence.state = 'FAILED';
        failures.push(error);
      } finally {
        cleaning = true;
        overall.cancel();
        overall.dispose();
        const cleanup = new OperationDeadline(10_000);
        provider?.stop();
        try {
          // Original setup promises remain owned across caller timeout. Late
          // providers/handles are retained and explicitly disposed, never polled.
          await awaitBrowserWork(
            Promise.all([...pendingSetup, ...pendingImages]),
            cleanup,
          );
        } catch (error) {
          failures.push(error);
        }
        provider?.stop();
        try {
          await provider?.drain(cleanup);
          evidence.cleanup.push('provider-drained');
        } catch (error) {
          failures.push(error);
        }
        try {
          await backend?.close(cleanup);
          evidence.cleanup.push('helper-closed');
        } catch (error) {
          failures.push(error);
        }
        connection?.close();
        for (const fixture of fixtures)
          try {
            await fixture.close(cleanup);
            evidence.cleanup.push('fixture-closed');
          } catch (error) {
            failures.push(error);
          }
        cleanup.dispose();
        if (failures.length > 0) evidence.state = 'FAILED';
        const evidenceFile =
          directory === undefined
            ? undefined
            : join(directory, 'evidence.json');
        const checkpoint = new OperationDeadline(2000);
        try {
          // Provisional CHECKS_PASSED bytes never replace a successful test/CLI exit.
          // There is exactly one owned write; timeout never launches a replacement.
          if (evidenceFile !== undefined)
            await awaitBrowserWork(
              atomicPrivateWrite(evidenceFile, evidence),
              checkpoint,
            );
        } finally {
          checkpoint.dispose();
        }
        if (evidenceFile !== undefined)
          console.error(
            'Native acceptance evidence retained at: ' + evidenceFile,
          );
      }
      if (failures.length > 0)
        throw new AggregateError(
          failures,
          'Native acceptance failed; inspect retained evidence and fences',
        );
    }
    const lifecycle = new OperationDeadline(140_000);
    let exerciseStarted = false;
    try {
      const activeDirectory = await setup(
        () => mkdtemp(join(tmpdir(), 'computer-native-sdk-')),
        (value) => {
          directory = value;
        },
        async () => {},
      );
      const admission = overall.child(2000);
      let admitted: (() => void) | undefined;
      let refused: ((error: unknown) => void) | undefined;
      const acquired = new Promise<void>((resolve, reject) => {
        admitted = resolve;
        refused = reject;
      });
      const physical = localLock(
        join(activeDirectory, 'journal'),
        'native-sdk-acceptance',
        async () => {
          admission.requireActive();
          exerciseStarted = true;
          admitted?.();
          try {
            await exercise(activeDirectory, fixturePlan);
          } finally {
            // A timed-out construction still owns its journal reservation. This
            // is a retained physical-settlement continuation, never replacement work.
            await Promise.all([...pendingSetup, ...pendingImages]);
          }
        },
        admission.signal,
      );
      const settled = physical.then(
        () => ({ ok: true as const }),
        (error: unknown) => {
          refused?.(error);
          return { ok: false as const, error };
        },
      );
      try {
        await awaitBrowserWork(acquired, admission);
      } finally {
        admission.dispose();
      }
      const outcome = await awaitBrowserWork(settled, lifecycle);
      if (!outcome.ok) throw outcome.error;
    } catch (error) {
      if (!exerciseStarted) {
        cleaning = true;
        overall.cancel();
        overall.dispose();
      }
      throw error;
    } finally {
      lifecycle.dispose();
    }
  },
);
