import type {
  BrowserAcquireRequest,
  BrowserActionResult,
  BrowserCleanupResult,
  BrowserControl,
  BrowserControlBackend,
  BrowserControlCode,
  BrowserDelivery,
  BrowserDialogEvent,
  BrowserFocusRequest,
  BrowserLease,
  BrowserNavigateRequest,
  BrowserObservation,
  BrowserObserveRequest,
  BrowserPerformRequest,
  BrowserPostObservation,
  BrowserSnapshot,
  BrowserState,
  BrowserToolName,
  BrowserWindows,
} from './browser-control.ts';
import {
  BrowserControlError,
  browserScrollConversion,
  decodeBrowserAcquireRequest,
  decodeBrowserActionResult,
  decodeBrowserFocusRequest,
  decodeBrowserCleanupResult,
  decodeBrowserLease,
  decodeBrowserNavigateRequest,
  decodeBrowserObservation,
  decodeBrowserObserveRequest,
  decodeBrowserPerformRequest,
  decodeBrowserWindows,
  permittedBrowserUrl,
  requireBrowserActionState,
  requireBrowserNavigationState,
} from './browser-control.ts';
import type {
  CaptureRequest,
  CapturedImage,
  ComputerOwner,
  ComputerTarget,
} from './contracts.ts';
import { BackendError } from './contracts.ts';
import type { OperationDeadline } from './deadline.ts';
import type { AffineTransform } from './geometry.ts';
import { image, target } from './contracts.test-support.ts';
import type { FakeClock } from './contracts.test-support.ts';

/** A physical promise gate: caller cancellation does not implicitly resolve it. */
export class BrowserTestGate {
  readonly promise: Promise<void>;
  private resolvePromise: (() => void) | undefined;
  private rejectPromise: ((error: Error) => void) | undefined;
  constructor() {
    this.promise = new Promise<void>((resolve, reject) => {
      this.resolvePromise = resolve;
      this.rejectPromise = reject;
    });
  }
  resolve(): void {
    if (this.resolvePromise === undefined)
      throw new Error('Gate not initialized');
    this.resolvePromise();
  }
  reject(error: Error): void {
    if (this.rejectPromise === undefined)
      throw new Error('Gate not initialized');
    this.rejectPromise(error);
  }
}
export interface FakeBrowserControlOptions {
  readonly tools?: readonly BrowserToolName[];
  readonly targetId?: string;
  readonly surfaceId?: string;
}
const allTools: readonly BrowserToolName[] = [
  'windows',
  'focus',
  'navigate',
  'pointer',
  'type',
  'key',
  'scroll',
];

/**
 * Controlled provider fixture only; actual transport/dedup/admission remains in
 * RelayWire/ToolProvider. Deferred operations deliberately ignore AbortSignal so
 * tests can prove the provider retains physical ownership after caller expiry.
 * Counters record boundary calls; inputs counts dispatched effects before their
 * optional acknowledgement gate, captureCalls counts returned image captures.
 */
export class FakeBrowserControlBackend implements BrowserControlBackend {
  readonly browserControl: BrowserControl;
  readonly targetId: string;
  readonly surfaceId: string;
  acquireCalls = 0;
  releaseCalls = 0;
  observeCalls = 0;
  windowsCalls = 0;
  captureCalls = 0;
  bareCaptureCalls = 0;
  inputs = 0;
  readonly acquired: BrowserAcquireRequest[] = [];
  readonly performed: BrowserPerformRequest[] = [];
  readonly focused: BrowserFocusRequest[] = [];
  readonly navigated: BrowserNavigateRequest[] = [];
  readonly observed: BrowserObserveRequest[] = [];
  delivery: BrowserDelivery | undefined;
  postObservationFailure: BrowserControlCode | undefined;
  observationFailure: Error | undefined;
  inputFailure: Error | undefined;
  releaseFailure: Error | undefined;
  releaseResult: BrowserCleanupResult = {
    physicalSettlement: 'settled',
    heldInputsReleased: true,
  };
  dialogEvent: BrowserDialogEvent | null = null;
  /** Controlled browser coordinates are CSS pixels; custom faults must opt in explicitly. */
  imageTransform: AffineTransform = Object.freeze({
    a: 1,
    b: 0,
    c: 0,
    d: 1,
    e: 0,
    f: 0,
  });
  beforeAcquire: (() => void) | undefined;
  beforeInput: (() => void) | undefined;
  afterInput: (() => void) | undefined;
  beforeObserve: (() => void) | undefined;
  beforeRelease: (() => void) | undefined;
  state: BrowserState;
  snapshot: BrowserSnapshot;
  windowsValue: BrowserWindows | undefined;
  private lease: BrowserLease | undefined;
  private owner: ComputerOwner | undefined;
  private stopping = false;
  private fenced = false;
  private sequence = 0;
  private snapshotSequence = 0;
  private windowsSequence = 0;
  private physical: Promise<BrowserActionResult> | undefined;
  private inputGate: BrowserTestGate | undefined;
  private releaseGate: BrowserTestGate | undefined;

  constructor(
    readonly clock: FakeClock,
    options: FakeBrowserControlOptions = {},
  ) {
    this.targetId = options.targetId ?? target.targetId;
    this.surfaceId = options.surfaceId ?? 'viewport';
    this.state = this.freshState();
    this.snapshot = this.freshSnapshot();
    this.browserControl = {
      allowedTools: Object.freeze([...(options.tools ?? allTools)]),
      acquire: (request, deadline) => this.acquire(request, deadline),
      release: (lease, deadline) => this.release(lease, deadline),
      windows: (lease, deadline) => this.windows(lease, deadline),
      observe: (lease, request, deadline) =>
        this.observe(lease, request, deadline),
      focus: (lease, request, deadline) => this.focus(lease, request, deadline),
      navigate: (lease, request, deadline) =>
        this.navigate(lease, request, deadline),
      perform: (lease, request, deadline) =>
        this.perform(lease, request, deadline),
    };
  }
  get hasLease(): boolean {
    return this.lease !== undefined;
  }
  get leaseOwner(): ComputerOwner | undefined {
    return this.owner;
  }
  get inputActive(): boolean {
    return this.physical !== undefined;
  }
  deferInput(): BrowserTestGate {
    return (this.inputGate = new BrowserTestGate());
  }
  deferRelease(): BrowserTestGate {
    return (this.releaseGate = new BrowserTestGate());
  }
  targets(deadline: OperationDeadline): Promise<readonly ComputerTarget[]> {
    deadline.requireActive();
    return Promise.resolve([
      {
        ...target,
        targetId: this.targetId,
        backend: 'browser',
        surfaces: [{ id: this.surfaceId, label: 'Controlled viewport' }],
        capabilities: ['capture', 'structured', 'pointer', 'keyboard'],
      },
    ]);
  }
  capture(
    _request: CaptureRequest,
    deadline: OperationDeadline,
  ): Promise<CapturedImage> {
    deadline.requireActive();
    this.bareCaptureCalls++;
    return Promise.reject(new BackendError('CAPTURE_FAILED'));
  }
  private async acquire(
    value: BrowserAcquireRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserLease> {
    this.acquireCalls++;
    deadline.requireActive();
    this.beforeAcquire?.();
    deadline.requireActive();
    const request = decodeBrowserAcquireRequest(value);
    if (request.targetId !== this.targetId)
      throw new BrowserControlError('TARGET_GONE');
    if (this.lease !== undefined) throw new BrowserControlError('BUSY');
    this.sequence++;
    this.windowsValue = undefined;
    this.state = this.freshState();
    this.snapshot = this.freshSnapshot();
    this.lease = decodeBrowserLease({
      leaseId: 'lease-' + this.sequence,
      targetId: this.targetId,
      ...request.session,
    });
    this.owner = request.owner;
    this.acquired.push(request);
    this.stopping = false;
    this.fenced = false;
    return this.lease;
  }
  private async release(
    value: BrowserLease,
    deadline: OperationDeadline,
  ): Promise<BrowserCleanupResult> {
    this.releaseCalls++;
    deadline.requireActive();
    this.assertLease(value, true);
    this.stopping = true;
    this.beforeRelease?.();
    if (this.physical !== undefined)
      await this.physical.then(
        () => undefined,
        () => undefined,
      );
    if (this.releaseGate !== undefined) await this.releaseGate.promise;
    deadline.requireActive();
    if (this.releaseFailure !== undefined) {
      this.fenced = true;
      throw this.releaseFailure;
    }
    const result = decodeBrowserCleanupResult(this.releaseResult);
    if (result.physicalSettlement === 'settled' && result.heldInputsReleased) {
      this.lease = undefined;
      this.owner = undefined;
      this.stopping = false;
      this.fenced = false;
    } else this.fenced = true;
    return result;
  }
  private async windows(
    lease: BrowserLease,
    deadline: OperationDeadline,
  ): Promise<BrowserWindows> {
    this.windowsCalls++;
    deadline.requireActive();
    this.assertLease(lease);
    this.requireTool('windows');
    const tabs = this.windowsValue?.tabs ?? [
      { state: this.state, title: 'Controlled fixture' },
    ];
    this.windowsValue = decodeBrowserWindows({
      windowsId: 'windows-' + ++this.windowsSequence,
      capturedAt: new Date(this.clock.wallNow()).toISOString(),
      tabs: tabs.map((tab) =>
        tab.state.surfaceId === this.state.surfaceId
          ? { ...tab, state: this.state }
          : tab,
      ),
      truncated: this.windowsValue?.truncated ?? false,
    });
    return this.windowsValue;
  }

  private async observe(
    lease: BrowserLease,
    value: BrowserObserveRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserObservation> {
    this.observeCalls++;
    deadline.requireActive();
    this.assertLease(lease);
    const request = decodeBrowserObserveRequest(value);
    this.observed.push(request);
    if (
      request.surfaceId !== this.state.surfaceId &&
      request.surfaceId !== this.surfaceId
    )
      throw new BrowserControlError('TARGET_GONE');
    if (
      request.root !== undefined &&
      (request.root.snapshotId !== this.snapshot.snapshotId ||
        !this.snapshot.nodes.some(
          (node) => node.elementId === request.root?.elementId,
        ))
    )
      throw new BrowserControlError('STALE_OBSERVATION');
    const observed = await this.captureObservation(
      deadline,
      request.structured,
    );
    if (request.root === undefined || observed.snapshot === null)
      return observed;
    const root = observed.snapshot.nodes.find(
      (node) => node.elementId === request.root?.elementId,
    );
    if (root === undefined) throw new BrowserControlError('STALE_OBSERVATION');
    return decodeBrowserObservation({
      ...observed,
      snapshot: {
        ...observed.snapshot,
        rootElementId: root.elementId,
        nodes: [{ ...root, parentElementId: null }],
      },
    });
  }
  private focus(
    lease: BrowserLease,
    value: BrowserFocusRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    const request = decodeBrowserFocusRequest(value);
    this.focused.push(request);
    let selected: BrowserState | undefined;
    return this.input(
      lease,
      deadline,
      {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'focus',
      },
      () => {
        this.requireTool('focus');
        const tab = this.windowsValue?.tabs.find(
          (row) => row.state.surfaceId === request.surfaceId,
        );
        if (
          this.windowsValue?.windowsId !== request.windowsId ||
          tab === undefined ||
          tab.state.readiness === 'closed'
        )
          throw new BrowserControlError('STALE_OBSERVATION');
        selected = tab.state;
      },
      () => {
        if (selected === undefined)
          throw new Error('Missing validated focus target');
        this.state = selected;
      },
    );
  }

  private navigate(
    lease: BrowserLease,
    value: BrowserNavigateRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    const request = decodeBrowserNavigateRequest(value);
    this.navigated.push(request);
    let destination: BrowserState | undefined;
    return this.input(
      lease,
      deadline,
      {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'goto',
      },
      () => {
        const state =
          this.state.surfaceId === request.expected.surfaceId
            ? this.state
            : this.windowsValue?.tabs.find(
                (row) => row.state.surfaceId === request.expected.surfaceId,
              )?.state;
        if (state === undefined) throw new BrowserControlError('TARGET_GONE');
        this.requireTool('navigate');
        requireBrowserNavigationState(state, request.expected);
        const url = permittedBrowserUrl(
          {
            enabled: true,
            tools: this.browserControl.allowedTools,
            allowedOrigins: ['https://fixture.invalid'],
          },
          request.url,
        );
        destination = {
          ...state,
          readiness: 'ready',
          guardInstalled: true,
          url,
          origin: new URL(url).origin,
          requestedUrl: null,
        };
      },
      () => {
        if (destination === undefined)
          throw new Error('Missing validated navigation target');
        this.state = destination;
      },
    );
  }

  private perform(
    lease: BrowserLease,
    value: BrowserPerformRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    const request = decodeBrowserPerformRequest(value);
    this.performed.push(request);
    const action = request.action;
    const delivery: BrowserDelivery =
      action.kind === 'scroll'
        ? {
            outcome: 'DISPATCHED',
            physicalSettlement: 'settled',
            mechanism: 'wheel',
            scroll: browserScrollConversion(action, 16),
          }
        : {
            outcome: 'DISPATCHED',
            physicalSettlement: 'settled',
            mechanism:
              action.kind === 'type'
                ? action.mode === 'insert'
                  ? 'insertText'
                  : 'fill'
                : action.kind === 'key'
                  ? 'keyboard'
                  : 'mouse',
          };
    return this.input(lease, deadline, delivery, () => {
      requireBrowserActionState(this.state, request.expected, action);
      const element =
        action.kind === 'type'
          ? action.target
          : action.kind === 'key'
            ? action.expectedFocus
            : 'target' in action && action.target.kind === 'element'
              ? action.target
              : undefined;
      if (
        element != null &&
        (element.snapshotId !== this.snapshot.snapshotId ||
          !this.snapshot.nodes.some(
            (node) => node.elementId === element.elementId,
          ))
      )
        throw new BrowserControlError('STALE_OBSERVATION');
      if (
        action.kind === 'type' &&
        !this.snapshot.nodes.some(
          (node) =>
            node.elementId === action.target.elementId &&
            node.editable &&
            !node.disabled,
        )
      )
        throw new BrowserControlError('NOT_EDITABLE');
    });
  }
  private async input(
    lease: BrowserLease,
    deadline: OperationDeadline,
    normal: BrowserDelivery,
    validate: () => void,
    dispatched: () => void = () => {},
  ): Promise<BrowserActionResult> {
    deadline.requireActive();
    this.assertLease(lease);
    const physical = Promise.resolve().then(async () => {
      this.beforeInput?.();
      deadline.requireActive();
      try {
        validate();
      } catch (error) {
        if (!(error instanceof BrowserControlError)) throw error;
        return decodeBrowserActionResult({
          delivery: {
            outcome: 'NOT_DISPATCHED',
            physicalSettlement: 'settled',
            code: error.code,
          },
          observation: { state: 'FAILED', code: error.code },
          dialog: null,
        });
      }
      const delivery = this.delivery ?? normal;
      if (delivery.outcome === 'NOT_DISPATCHED')
        return decodeBrowserActionResult({
          delivery,
          observation: { state: 'FAILED', code: delivery.code },
          dialog: null,
        });
      this.inputs++;
      dispatched();
      if (this.inputGate !== undefined) await this.inputGate.promise;
      this.afterInput?.();
      if (this.inputFailure !== undefined) {
        this.fenced = true;
        throw this.inputFailure;
      }
      deadline.requireActive();
      if (delivery.outcome === 'UNCERTAIN') {
        this.fenced = true;
        return decodeBrowserActionResult({
          delivery,
          observation: { state: 'FAILED', code: delivery.code },
          dialog: this.dialogEvent,
        });
      }
      this.state = {
        ...this.state,
        layoutGeneration: 'layout-after-' + this.inputs,
      };
      let observation: BrowserPostObservation;
      if (this.postObservationFailure !== undefined)
        observation = { state: 'FAILED', code: this.postObservationFailure };
      else {
        try {
          observation = {
            state: 'CAPTURED',
            value: await this.captureObservation(deadline, true),
          };
        } catch (error) {
          observation = {
            state: 'FAILED',
            code:
              error instanceof BrowserControlError
                ? error.code
                : 'OBSERVATION_FAILED',
          };
        }
      }
      return decodeBrowserActionResult({
        delivery,
        observation,
        dialog: this.dialogEvent,
      });
    });
    this.physical = physical;
    try {
      return await physical;
    } finally {
      if (this.physical === physical) this.physical = undefined;
    }
  }
  private async captureObservation(
    deadline: OperationDeadline,
    structured: boolean,
  ): Promise<BrowserObservation> {
    this.beforeObserve?.();
    deadline.requireActive();
    if (this.observationFailure !== undefined) throw this.observationFailure;
    this.snapshot = this.freshSnapshot();
    const observed = decodeBrowserObservation({
      image: {
        ...image(this.clock),
        transform: this.imageTransform,
        layoutGeneration: this.state.layoutGeneration,
      },
      state: this.state,
      snapshot: structured ? this.snapshot : null,
    });
    this.captureCalls++;
    return observed;
  }
  private requireTool(tool: BrowserToolName): void {
    if (!this.browserControl.allowedTools.includes(tool))
      throw new BrowserControlError('CONTROL_DISABLED');
  }
  private assertLease(value: BrowserLease, cleanup = false): void {
    const lease = decodeBrowserLease(value),
      owned = this.lease;
    if (
      owned === undefined ||
      lease.leaseId !== owned.leaseId ||
      lease.targetId !== owned.targetId ||
      lease.sessionId !== owned.sessionId ||
      lease.generation !== owned.generation
    )
      throw new BrowserControlError('NO_LEASE');
    if (!cleanup && (this.stopping || this.fenced))
      throw new BrowserControlError('FENCED');
    if (!cleanup && this.physical !== undefined)
      throw new BrowserControlError('BUSY');
  }
  private freshState(): BrowserState {
    return {
      surfaceId: this.surfaceId,
      documentId: 'document-' + this.sequence,
      layoutGeneration: 'layout-' + this.sequence,
      focusGeneration: 'focus-' + this.sequence,
      readiness: 'ready',
      guardInstalled: true,
      url: 'https://fixture.invalid/',
      origin: 'https://fixture.invalid',
      requestedUrl: null,
      focusedElementId: 'element-' + this.sequence,
    };
  }
  private freshSnapshot(): BrowserSnapshot {
    return {
      snapshotId: 'snapshot-' + ++this.snapshotSequence,
      rootElementId: null,
      guard: {
        surfaceId: this.state.surfaceId,
        documentId: this.state.documentId,
        layoutGeneration: this.state.layoutGeneration,
        focusGeneration: this.state.focusGeneration,
      },
      capturedAt: new Date(this.clock.wallNow()).toISOString(),
      kind: 'dom-semantic',
      approximate: true,
      truncated: false,
      nodes:
        this.state.focusedElementId === null
          ? []
          : [
              {
                elementId: this.state.focusedElementId,
                parentElementId: null,
                role: 'textbox',
                name: 'Controlled field',
                editable: true,
                focused: true,
                disabled: false,
                bounds: { x: 10, y: 10, width: 40, height: 20 },
              },
            ],
    };
  }
}
