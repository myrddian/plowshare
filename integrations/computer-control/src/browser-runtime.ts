import {
  admitToolImage,
  imageToolResult,
  toolTextPart,
} from 'plowshare-client-node';
import type { ToolBinding, ToolCall, ToolResult } from 'plowshare-client-ts';
import type {
  CaptureLimits,
  ComputerOwner,
  SessionReference,
} from './contracts.ts';
import { decodeCapturedImage, decodeTargets } from './contracts.ts';
import type {
  BrowserActionResult,
  BrowserControlBackend,
  BrowserDelivery,
  BrowserLease,
  BrowserObservation,
  BrowserSnapshot,
  BrowserStateGuard,
  BrowserToolName,
  PreparedBrowserAction,
} from './browser-control.ts';
import {
  BrowserControlError,
  decodeBrowserActionResult,
  decodeBrowserCleanupResult,
  decodeBrowserLease,
  decodePreparedBrowserAction,
  decodeBrowserObservation,
  decodeBrowserWindows,
  sameBrowserGuard,
} from './browser-control.ts';
import type { BrowserObserveArguments } from './browser-tool-arguments.ts';
import {
  decodeBrowserFocusArguments,
  decodeBrowserKeyArguments,
  decodeBrowserNavigateArguments,
  decodeBrowserPointerArguments,
  decodeBrowserScrollArguments,
  decodeBrowserSessionArguments,
  decodeBrowserTypeArguments,
} from './browser-tool-arguments.ts';
import {
  computerTextResult,
  validateComputerResult,
} from './computer-results.ts';
import { SessionError } from './sessions.ts';
import type {
  BrowserObservationReference,
  ComputerSession,
  ComputerSessions,
} from './sessions.ts';
import { DeadlineExceeded, OperationDeadline } from './deadline.ts';
import type { Clock } from './deadline.ts';

interface OwnedBrowser {
  readonly owner: ComputerOwner;
  readonly session: ComputerSession;
  readonly backend: BrowserControlBackend;
  lease?: BrowserLease;
  deadline?: OperationDeadline;
  running?: Promise<void>;
  cleanup?: Promise<void>;
  closing: boolean;
}
export interface BrowserRuntimeOptions {
  readonly binding: ToolBinding;
  readonly sessions: ComputerSessions;
  readonly controls: readonly BrowserControlBackend[];
  readonly tools: readonly BrowserToolName[];
  readonly clock: Clock;
  readonly origin: string;
  readonly currentBearer: () => Promise<string>;
  readonly http: typeof fetch;
  readonly captureLimits: CaptureLimits;
  readonly observationReserveMs: number;
  readonly cleanupBudgetMs: number;
  readonly idleCheckMs: number;
}
interface BrowserMetadata {
  readonly version: 'computer-control/1';
  readonly invocationId: string;
  readonly session: ComputerSession;
  readonly actionOutcome: BrowserDelivery['outcome'];
  readonly action: BrowserDelivery | null;
  readonly observationOutcome: 'CAPTURED' | 'FAILED';
  readonly code?: string;
  readonly frame?: BrowserObservationReference['frame'];
  readonly browser?: {
    readonly surfaceId: string;
    readonly readiness: string;
    readonly url?: string | null;
    readonly origin?: string | null;
  };
  readonly snapshot?: BrowserSnapshot | null;
  readonly dialog?: BrowserActionResult['dialog'];
  readonly image?: {
    readonly slot: 0;
    readonly imageId: string;
    readonly mimeType: string;
  };
  readonly truncated?: boolean;
}
function owner(call: ToolCall): ComputerOwner {
  return {
    account: call.account,
    project: call.project,
    provider: call.provider,
    run: call.run,
  };
}
function sameOwner(a: ComputerOwner, b: ComputerOwner): boolean {
  return (
    a.account === b.account &&
    a.project === b.project &&
    a.provider === b.provider &&
    a.run === b.run
  );
}
function code(error: unknown): string {
  return error instanceof BrowserControlError || error instanceof SessionError
    ? error.code
    : error instanceof DeadlineExceeded
      ? 'DEADLINE_EXPIRED'
      : 'INPUT_OR_OBSERVATION_FAILED';
}
function decodeCommand(
  name: Exclude<BrowserToolName, 'windows'>,
  value: unknown,
) {
  switch (name) {
    case 'focus':
      return { kind: name, args: decodeBrowserFocusArguments(value) };
    case 'navigate':
      return { kind: name, args: decodeBrowserNavigateArguments(value) };
    case 'pointer':
      return { kind: name, args: decodeBrowserPointerArguments(value) };
    case 'type':
      return { kind: name, args: decodeBrowserTypeArguments(value) };
    case 'key':
      return { kind: name, args: decodeBrowserKeyArguments(value) };
    case 'scroll':
      return { kind: name, args: decodeBrowserScrollArguments(value) };
  }
}

/**
 * Provider-owned browser lifecycle on the original SDK call boundary. Backend leases
 * and durable ownership markers remain the physical authority; this map pins their
 * cleanup capability even after a caller expires or the session becomes fenced.
 */
export class BrowserToolRuntime {
  private readonly entries = new Map<string, OwnedBrowser>();
  private readonly enabled: ReadonlySet<BrowserToolName>;
  private readonly controls: ReadonlySet<BrowserControlBackend>;
  private stopped = false;
  private maintenance: (() => void) | undefined;
  constructor(private readonly options: BrowserRuntimeOptions) {
    this.enabled = new Set(options.tools);
    this.controls = new Set(options.controls);
  }
  controlsBackend(value: object): value is BrowserControlBackend {
    return this.options.controls.some((backend) => backend === value);
  }
  hasSession(reference: SessionReference): boolean {
    return this.entries.has(reference.sessionId);
  }
  private entry(call: ToolCall, reference: SessionReference): OwnedBrowser {
    const entry = this.entries.get(reference.sessionId);
    if (
      entry === undefined ||
      entry.session.generation !== reference.generation ||
      !sameOwner(owner(call), entry.owner)
    )
      throw new SessionError('NO_SESSION');
    return entry;
  }
  private assertTool(entry: OwnedBrowser, name: BrowserToolName): void {
    if (
      !this.enabled.has(name) ||
      !entry.backend.browserControl.allowedTools.includes(name)
    )
      throw new BrowserControlError('CONTROL_DISABLED');
  }
  private async current(
    entry: OwnedBrowser,
    deadline: OperationDeadline,
  ): Promise<void> {
    deadline.requireActive();
    if (this.stopped) throw new BrowserControlError('FENCED');
    const targets = decodeTargets(await entry.backend.targets(deadline));
    deadline.requireActive();
    if (
      !targets.some(
        (target) =>
          target.targetId === entry.session.targetId &&
          target.backend === 'browser',
      )
    )
      throw new BrowserControlError('TARGET_GONE');
    this.options.sessions.browserLease(entry.owner, entry.session);
  }
  private failure(
    call: ToolCall,
    error: unknown,
    uncertain: boolean,
    session?: ComputerSession,
  ): ToolResult {
    return computerTextResult(
      call,
      this.options.binding.account,
      {
        version: 'computer-control/1',
        invocationId: call.invocationId,
        ...(session === undefined ? {} : { session }),
        code: code(error),
        actionOutcome: uncertain ? 'UNCERTAIN' : 'NOT_DISPATCHED',
        observationOutcome: 'FAILED',
      },
      uncertain ? 'UNKNOWN' : 'REJECTED',
    );
  }
  private fence(entry: OwnedBrowser): void {
    // A concurrent stop may already have proved quiescence and removed this entry.
    if (!this.entries.has(entry.session.sessionId)) return;
    this.options.sessions.fence(entry.owner, entry.session);
    this.options.sessions.beginClose(entry.owner, entry.session);
    entry.closing = true;
    this.scheduleCleanup(entry);
  }
  private async withEntry<T>(
    entry: OwnedBrowser,
    deadline: OperationDeadline,
    work: () => Promise<T>,
  ): Promise<T> {
    // The underlying promise, not a timed-out SDK waiter, owns busy state and cleanup ordering.
    let resolve = () => {};
    const running = new Promise<void>((done) => {
      resolve = done;
    });
    if (entry.running !== undefined) throw new SessionError('BUSY');
    entry.running = running;
    entry.deadline = deadline;
    try {
      return await this.options.sessions.withSession(
        entry.owner,
        entry.session,
        deadline,
        work,
      );
    } finally {
      delete entry.deadline;
      delete entry.running;
      resolve();
      this.armMaintenance();
    }
  }
  private async cleanup(
    entry: OwnedBrowser,
    deadline: OperationDeadline,
  ): Promise<void> {
    if (entry.cleanup === undefined) {
      const lease = entry.lease;
      if (lease === undefined)
        throw new BrowserControlError('CLEANUP_UNCERTAIN');
      const running = entry.running;
      entry.cleanup = (async () => {
        const result = decodeBrowserCleanupResult(
          await entry.backend.browserControl.release(lease, deadline),
        );
        if (
          result.physicalSettlement !== 'settled' ||
          !result.heldInputsReleased
        )
          throw new BrowserControlError('CLEANUP_UNCERTAIN');
        if (running !== undefined) await running;
        deadline.requireActive();
        this.options.sessions.releaseAfterQuiescence(
          entry.owner,
          entry.session,
        );
        this.entries.delete(entry.session.sessionId);
        this.armMaintenance();
      })();
    }
    await entry.cleanup;
  }
  private scheduleCleanup(entry: OwnedBrowser): void {
    entry.closing = true;
    this.armMaintenance();
    if (entry.cleanup !== undefined || entry.lease === undefined) return;
    const deadline = new OperationDeadline(
      this.options.cleanupBudgetMs,
      this.options.clock,
    );
    // A failed/late release remains memoized and fenced. Never retry removal or cleanup
    // under a fresh identity merely because this waiter expired.
    this.cleanup(entry, deadline).then(
      () => deadline.dispose(),
      () => deadline.dispose(),
    );
  }
  reap(): void {
    for (const pending of this.options.sessions.pendingCleanup()) {
      const entry = this.entries.get(pending.session.sessionId);
      if (entry !== undefined) {
        if (pending.reason === 'idle') entry.deadline?.cancel();
        this.scheduleCleanup(entry);
      }
    }
  }
  private armMaintenance(): void {
    const needed =
      !this.stopped &&
      [...this.entries.values()].some((entry) => !entry.closing);
    if (!needed) {
      this.maintenance?.();
      this.maintenance = undefined;
      return;
    }
    if (this.maintenance !== undefined) return;
    // Local bounded memory maintenance, not a server poll. Exact idle expiry is
    // still checked synchronously before every effect; cleanup scheduling may lag
    // by <=1s plus event-loop delay and retains physical ownership throughout.
    this.maintenance = this.options.clock.schedule(
      this.options.idleCheckMs,
      () => {
        this.maintenance = undefined;
        this.reap();
        this.armMaintenance();
      },
    );
  }
  stop(): void {
    this.stopped = true;
    this.maintenance?.();
    this.maintenance = undefined;
    for (const entry of this.entries.values()) {
      entry.deadline?.cancel();
      this.options.sessions.beginClose(entry.owner, entry.session);
      entry.closing = true;
      this.scheduleCleanup(entry);
    }
  }
  async drain(deadline: OperationDeadline): Promise<void> {
    deadline.requireActive();
    this.reap();
    for (const entry of [...this.entries.values()]) {
      if (!this.stopped && entry.cleanup === undefined) continue;
      deadline.requireActive();
      await this.cleanup(entry, deadline);
    }
    deadline.requireActive();
  }

  async open(
    call: ToolCall,
    targetId: string,
    backend: BrowserControlBackend,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    let entry: OwnedBrowser | undefined,
      attempted = false;
    try {
      if (this.stopped || !this.controls.has(backend))
        throw new BrowserControlError('CONTROL_DISABLED');
      const session = this.options.sessions.openControlled(
        owner(call),
        targetId,
      );
      entry = { session, owner: owner(call), backend, closing: false };
      this.entries.set(session.sessionId, entry);
      this.armMaintenance();
      const held = entry;
      const result = computerTextResult(call, this.options.binding.account, {
        version: 'computer-control/1',
        session,
        controlled: true,
        surfaces: [{ id: 'viewport', label: 'Initial browser viewport' }],
      });
      await this.withEntry(held, deadline, async () => {
        deadline.requireActive();
        attempted = true;
        const lease = decodeBrowserLease(
          await backend.browserControl.acquire(
            {
              owner: held.owner,
              session: {
                sessionId: session.sessionId,
                generation: session.generation,
              },
              targetId,
            },
            deadline,
          ),
        );
        if (
          lease.sessionId !== session.sessionId ||
          lease.generation !== session.generation ||
          lease.targetId !== targetId
        )
          throw new BrowserControlError('CLEANUP_UNCERTAIN');
        held.lease = lease;
        this.options.sessions.bindBrowserLease(held.owner, session, lease);
        deadline.requireActive();
      });
      return result;
    } catch (error) {
      if (entry !== undefined && attempted) this.fence(entry);
      else if (entry !== undefined) {
        this.options.sessions.beginClose(entry.owner, entry.session);
        this.options.sessions.releaseAfterQuiescence(
          entry.owner,
          entry.session,
        );
        this.entries.delete(entry.session.sessionId);
        this.armMaintenance();
      }
      return this.failure(call, error, attempted, entry?.session);
    }
  }
  async close(
    call: ToolCall,
    reference: SessionReference,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    let entry: OwnedBrowser | undefined;
    try {
      entry = this.entry(call, reference);
      this.options.sessions.beginClose(entry.owner, entry.session);
      entry.closing = true;
      this.armMaintenance();
      entry.deadline?.cancel();
      await this.cleanup(entry, deadline);
      deadline.requireActive();
      return computerTextResult(call, this.options.binding.account, {
        version: 'computer-control/1',
        closed: true,
      });
    } catch (error) {
      return this.failure(call, error, entry !== undefined, entry?.session);
    }
  }
  private async bearer(deadline: OperationDeadline): Promise<string> {
    const bearer = await this.options.currentBearer();
    deadline.requireActive();
    if (!bearer.trim() || /[\r\n]/u.test(bearer))
      throw new BrowserControlError('CONTROL_DISABLED');
    return bearer;
  }
  private minimal(
    call: ToolCall,
    entry: OwnedBrowser,
    delivery: BrowserDelivery | null,
    failureCode = 'OBSERVATION_FAILED',
    dialog: BrowserActionResult['dialog'] = null,
  ): ToolResult {
    return computerTextResult(
      call,
      this.options.binding.account,
      {
        version: 'computer-control/1',
        invocationId: call.invocationId,
        session: entry.session,
        actionOutcome: delivery?.outcome ?? 'NOT_DISPATCHED',
        action: delivery,
        dialog,
        observationOutcome: 'FAILED',
        code: failureCode,
      },
      delivery?.outcome === 'UNCERTAIN'
        ? 'UNKNOWN'
        : delivery?.outcome === 'NOT_DISPATCHED'
          ? 'REJECTED'
          : 'COMPLETED',
    );
  }
  private async publishObservation(
    call: ToolCall,
    entry: OwnedBrowser,
    value: BrowserObservation,
    bearer: string,
    deadline: OperationDeadline,
    delivery: BrowserDelivery | null,
    dialog: BrowserActionResult['dialog'] = null,
  ): Promise<ToolResult> {
    const observation = decodeBrowserObservation(value),
      image = decodeCapturedImage(
        observation.image,
        this.options.captureLimits,
      );
    const boundedHttp: typeof fetch = (input, init) => {
      deadline.requireActive();
      return this.options.http(input, {
        ...init,
        signal:
          init?.signal == null
            ? deadline.signal
            : AbortSignal.any([deadline.signal, init.signal]),
      });
    };
    const admitted = await admitToolImage(
      this.options.origin,
      bearer,
      call,
      0,
      image.bytes,
      'OBSERVATION',
      boundedHttp,
    );
    deadline.requireActive();
    if (
      admitted.receipt.width !== image.width ||
      admitted.receipt.height !== image.height ||
      admitted.receipt.mimeType !== image.mimeType ||
      admitted.receipt.producer !== this.options.binding.account
    )
      throw new BrowserControlError('OBSERVATION_FAILED');
    let snapshot = observation.snapshot;
    let exposeLocation = true;
    // Prepare bounded visible metadata before committing refs. Only emitted nodes
    // become actionable; omitted backend handles never gain provider membership.
    const preview = {
      frameId: 'x'.repeat(256),
      surfaceId: observation.state.surfaceId,
      width: image.width,
      height: image.height,
      transform: image.transform,
      capturedAt: image.capturedAt,
      layoutGeneration: image.layoutGeneration,
    };
    const build = (
      frame: BrowserObservationReference['frame'],
    ): BrowserMetadata => ({
      version: 'computer-control/1',
      invocationId: call.invocationId,
      session: entry.session,
      actionOutcome: delivery?.outcome ?? 'NOT_DISPATCHED',
      action: delivery,
      observationOutcome: 'CAPTURED',
      frame,
      snapshot,
      dialog,
      browser: {
        surfaceId: observation.state.surfaceId,
        readiness: observation.state.readiness,
        ...(exposeLocation
          ? { url: observation.state.url, origin: observation.state.origin }
          : {}),
      },
      image: {
        slot: 0,
        imageId: admitted.receipt.imageId,
        mimeType: image.mimeType,
      },
      truncated: !exposeLocation || snapshot?.truncated === true,
    });
    const encode = (metadata: BrowserMetadata) => {
      const text = JSON.stringify(metadata);
      if (text.length > 12000)
        throw new BrowserControlError('OBSERVATION_FAILED');
      return validateComputerResult(
        call,
        imageToolResult(
          call,
          [toolTextPart(text), admitted.part],
          [admitted.receipt],
        ),
        this.options.binding.account,
      );
    };
    for (;;) {
      try {
        encode(build(preview));
        break;
      } catch (error) {
        if (snapshot !== null && snapshot.nodes.length > 0) {
          const root = snapshot.rootElementId;
          const parents = new Set(
            snapshot.nodes.map((node) => node.parentElementId),
          );
          const removable = snapshot.nodes.findLastIndex(
            (node) => node.elementId !== root && !parents.has(node.elementId),
          );
          if (removable < 0) {
            if (exposeLocation) {
              exposeLocation = false;
              continue;
            }
            throw error;
          }
          snapshot = {
            ...snapshot,
            nodes: snapshot.nodes.filter((_node, index) => index !== removable),
            truncated: true,
          };
          continue;
        }
        if (exposeLocation) {
          exposeLocation = false;
          continue;
        }
        throw error;
      }
    }
    deadline.requireActive();
    const recorded = this.options.sessions.recordBrowserObservation(
      entry.owner,
      entry.session,
      { ...observation, image, snapshot },
    );
    return encode(build(recorded.frame));
  }
  async observe(
    call: ToolCall,
    args: BrowserObserveArguments,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    let started = false,
      captureSettled = false,
      entry: OwnedBrowser | undefined;
    try {
      entry = this.entry(call, args);
      const held = entry;
      return await this.withEntry(held, deadline, async () => {
        await this.current(held, deadline);
        const bearer = await this.bearer(deadline);
        if (args.root !== undefined)
          this.options.sessions.prepareBrowserTarget(
            held.owner,
            held.session,
            args.root,
          );
        this.options.sessions.reserveImage(held.owner);
        started = true;
        const observed = await held.backend.browserControl.observe(
          this.options.sessions.browserLease(held.owner, held.session),
          {
            surfaceId: args.surfaceId,
            structured: args.structured,
            ...(args.root === undefined ? {} : { root: args.root }),
          },
          deadline,
        );
        captureSettled = true;
        if (args.structured && observed.snapshot === null)
          throw new BrowserControlError('OBSERVATION_FAILED');
        return this.publishObservation(
          call,
          held,
          observed,
          bearer,
          deadline,
          null,
        );
      });
    } catch (error) {
      if (entry !== undefined && started && !captureSettled) this.fence(entry);
      return this.failure(call, error, started, entry?.session);
    }
  }

  async windows(
    call: ToolCall,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    try {
      const args = decodeBrowserSessionArguments(call.arguments),
        entry = this.entry(call, args);
      this.assertTool(entry, 'windows');
      return await this.withEntry(entry, deadline, async () => {
        await this.current(entry, deadline);
        let windows = decodeBrowserWindows(
          await entry.backend.browserControl.windows(
            this.options.sessions.browserLease(entry.owner, entry.session),
            deadline,
          ),
        );
        for (;;) {
          try {
            const result = computerTextResult(
              call,
              this.options.binding.account,
              {
                version: 'computer-control/1',
                session: entry.session,
                windows,
              },
            );
            this.options.sessions.recordBrowserWindows(
              entry.owner,
              entry.session,
              windows,
            );
            return result;
          } catch (error) {
            if (windows.tabs.length === 0) throw error;
            windows = {
              ...windows,
              tabs: windows.tabs.slice(0, -1),
              truncated: true,
            };
          }
        }
      });
    } catch (error) {
      return this.failure(call, error, false);
    }
  }

  async action(
    call: ToolCall,
    name: Exclude<BrowserToolName, 'windows'>,
    deadline: OperationDeadline,
  ): Promise<ToolResult> {
    let entry: OwnedBrowser | undefined,
      attempted = false;
    let outcome: BrowserActionResult | undefined;
    try {
      const sessions = this.options.sessions;
      let expected: BrowserStateGuard | undefined;
      let action: PreparedBrowserAction | undefined;
      let dialogDecision: Parameters<
        BrowserControlBackend['browserControl']['perform']
      >[1]['dialogDecision'];
      let navigation: { readonly url: string } | undefined;
      let focus:
        { readonly windowsId: string; readonly surfaceId: string } | undefined;
      // Decode once at the boundary, before resolving or acting on any target.
      const command = decodeCommand(name, call.arguments);
      entry = this.entry(call, command.args);
      const held = entry;
      this.assertTool(held, name);
      return await this.withEntry(held, deadline, async () => {
        await this.current(held, deadline);
        // Credential lookup may consume the frame lifetime. Resolve the exact
        // references afterwards, with no intervening await before dispatch.
        const bearer = await this.bearer(deadline);
        const prepare = (
          target: Parameters<ComputerSessions['prepareBrowserTarget']>[2],
        ) => sessions.prepareBrowserTarget(held.owner, held.session, target);
        if (command.kind === 'focus') {
          focus = {
            windowsId: command.args.windowsId,
            surfaceId: command.args.surfaceId,
          };
          sessions.resolveBrowserWindow(
            held.owner,
            held.session,
            focus.windowsId,
            focus.surfaceId,
          );
        } else if (command.kind === 'navigate') {
          const args = command.args,
            observed = args.observation;
          navigation = args;
          dialogDecision = args.dialogDecision;
          if (observed.kind === 'frame')
            expected = sessions.resolveBrowserFrame(
              held.owner,
              held.session,
              observed.frameId,
            );
          else {
            const state = sessions.resolveBrowserWindow(
              held.owner,
              held.session,
              observed.windowsId,
              observed.surfaceId,
            );
            expected = {
              surfaceId: state.surfaceId,
              documentId: state.documentId,
              layoutGeneration: state.layoutGeneration,
              focusGeneration: state.focusGeneration,
            };
          }
        } else if (command.kind === 'pointer') {
          const args = command.args,
            gesture = args.action;
          dialogDecision = args.dialogDecision;
          if (gesture.kind === 'drag') {
            expected = sessions.resolveBrowserFrame(
              held.owner,
              held.session,
              gesture.frameId,
            );
            const points = gesture.points
              .map((point) => {
                const prepared = prepare({
                  kind: 'point',
                  frameId: gesture.frameId,
                  x: point.x,
                  y: point.y,
                });
                if (
                  !sameBrowserGuard(
                    expected ?? prepared.expected,
                    prepared.expected,
                  ) ||
                  prepared.target.kind !== 'point'
                )
                  throw new BrowserControlError('STALE_OBSERVATION');
                return { ...prepared.target, atMs: point.atMs };
              })
              .map(({ x, y, atMs }) => ({ x, y, atMs }));
            action = {
              kind: 'drag',
              points,
              button: gesture.button,
              modifiers: gesture.modifiers,
            };
          } else {
            const prepared = prepare(gesture.target);
            expected = prepared.expected;
            action =
              gesture.kind === 'move'
                ? { kind: 'move', target: prepared.target }
                : { ...gesture, target: prepared.target };
          }
        } else if (command.kind === 'type') {
          const args = command.args,
            prepared = prepare(args.action.target);
          expected = prepared.expected;
          action = args.action;
          dialogDecision = args.dialogDecision;
        } else if (command.kind === 'key') {
          const args = command.args;
          expected = sessions.resolveBrowserFrame(
            held.owner,
            held.session,
            args.frameId,
          );
          if (
            args.action.expectedFocus !== null &&
            !sameBrowserGuard(
              expected,
              prepare(args.action.expectedFocus).expected,
            )
          )
            throw new BrowserControlError('STALE_OBSERVATION');
          action = args.action;
          dialogDecision = args.dialogDecision;
        } else if (command.kind === 'scroll') {
          const args = command.args,
            prepared = prepare(args.target);
          expected = prepared.expected;
          action = {
            kind: 'scroll',
            target: prepared.target,
            dx: args.dx,
            dy: args.dy,
            units: args.units,
          };
          dialogDecision = args.dialogDecision;
        }
        if (dialogDecision?.promptText !== undefined)
          this.assertTool(held, 'type');
        if (action !== undefined) action = decodePreparedBrowserAction(action);
        // Reserve a known-valid minimal result and image occurrence BEFORE any input.
        this.minimal(call, held, {
          outcome: 'DISPATCHED',
          physicalSettlement: 'settled',
          mechanism: 'mouse',
        });
        this.minimal(call, held, {
          outcome: 'UNCERTAIN',
          physicalSettlement: 'uncertain',
          code: 'INPUT_FAILED',
        });
        // The text-only fallback must retain a handled modal even when its
        // screenshot/upload fails. Reserve worst-case JSON escaping in advance.
        this.minimal(
          call,
          held,
          {
            outcome: 'PARTIAL',
            physicalSettlement: 'settled',
            delivered: {
              kind: 'type',
              insertedUtf16Units: 4096,
              cleared: true,
            },
            code: 'OBSERVATION_FAILED',
          },
          'X'.repeat(64),
          {
            type: 'beforeunload',
            decision: 'dismiss',
            message: '\u0001'.repeat(1024),
            messageTruncated: true,
          },
        );
        deadline.requireActive();
        if (deadline.remainingMs() <= this.options.observationReserveMs)
          throw new DeadlineExceeded();
        sessions.reserveImage(held.owner);
        const lease = sessions.browserLease(held.owner, held.session);
        sessions.invalidateBrowserObservations(held.owner, held.session);
        attempted = true;
        const control = held.backend.browserControl;
        let raw: BrowserActionResult;
        if (focus !== undefined)
          raw = await control.focus(
            lease,
            {
              ...focus,
              observationReserveMs: this.options.observationReserveMs,
            },
            deadline,
          );
        else if (navigation !== undefined && expected !== undefined)
          raw = await control.navigate(
            lease,
            {
              expected,
              url: navigation.url,
              observationReserveMs: this.options.observationReserveMs,
              ...(dialogDecision === undefined ? {} : { dialogDecision }),
            },
            deadline,
          );
        else if (action !== undefined && expected !== undefined)
          raw = await control.perform(
            lease,
            {
              expected,
              action,
              observationReserveMs: this.options.observationReserveMs,
              ...(dialogDecision === undefined ? {} : { dialogDecision }),
            },
            deadline,
          );
        else throw new BrowserControlError('INVALID_ARGUMENT');
        const result = decodeBrowserActionResult(raw);
        outcome = result;
        if (result.delivery.outcome === 'UNCERTAIN') {
          this.fence(held);
          return this.minimal(
            call,
            held,
            result.delivery,
            result.delivery.code,
            result.dialog,
          );
        }
        if (result.delivery.outcome === 'NOT_DISPATCHED')
          return this.minimal(
            call,
            held,
            result.delivery,
            result.delivery.code,
            result.dialog,
          );
        if (result.observation.state === 'FAILED')
          return this.minimal(
            call,
            held,
            result.delivery,
            result.observation.code,
            result.dialog,
          );
        try {
          return await this.publishObservation(
            call,
            held,
            result.observation.value,
            bearer,
            deadline,
            result.delivery,
            result.dialog,
          );
        } catch (error) {
          // Established input is never retyped/recaptured after binary admission or
          // optional metadata failure. The original admission slot remains uncertain.
          deadline.requireActive();
          return this.minimal(
            call,
            held,
            result.delivery,
            code(error),
            result.dialog,
          );
        }
      });
    } catch (error) {
      if (entry !== undefined && attempted) this.fence(entry);
      if (
        entry !== undefined &&
        outcome?.delivery.outcome === 'UNCERTAIN' &&
        deadline.remainingMs() > 0
      )
        return this.minimal(
          call,
          entry,
          outcome.delivery,
          outcome.delivery.code,
          outcome.dialog,
        );
      return this.failure(call, error, attempted, entry?.session);
    }
  }
}
