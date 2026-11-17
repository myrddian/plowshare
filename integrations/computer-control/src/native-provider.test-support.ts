import type { ComputerTarget } from './contracts.ts';
import { BackendError, decodeTargets } from './contracts.ts';
import type { FakeClock } from './contracts.test-support.ts';
import { image } from './contracts.test-support.ts';
import type { OperationDeadline } from './deadline.ts';
import {
  NativeControlError,
  decodeNativeAcquireRequest,
  decodeNativeFocusRequest,
  decodeNativeLease,
  decodeNativeObservation,
  decodeNativePerformRequest,
  decodeNativeStateGuard,
  decodeNativeWindows,
  requireNativeActionState,
  requireReadyNativeState,
} from './native-control.ts';
import type {
  NativeAcquisition,
  NativeActionResult,
  NativeAcquireRequest,
  NativeCleanupResult,
  NativeControl,
  NativeControlBackend,
  NativeDelivery,
  NativeFocusRequest,
  NativeLease,
  NativeObservation,
  NativeObserveRequest,
  NativePerformRequest,
  NativeState,
  NativeToolName,
  NativeWindows,
} from './native-control.ts';

/** Synthetic readiness is test data. This fake provides no OS permission/lock proof. */
export class FakeNativeControlBackend
  implements NativeControlBackend, NativeControl
{
  readonly desktopIsolation = 'native-broker';
  readonly nativeControl: NativeControl = this;
  readonly allowedTools: readonly NativeToolName[];
  readonly targetId: string;
  readonly surfaceId = 'display:1';
  acquireCalls = 0;
  releaseCalls = 0;
  captureCalls = 0;
  bareCaptureCalls = 0;
  inputs = 0;
  hasLease = false;
  availability: NativeState['availability'] = 'ready';
  delivery: NativeDelivery = {
    outcome: 'DISPATCHED',
    physicalSettlement: 'settled',
    mechanism: 'cg-event',
  };
  readonly performed: NativePerformRequest[] = [];
  private held: NativeLease | undefined;
  constructor(
    readonly clock: FakeClock,
    options: {
      readonly tools?: readonly NativeToolName[];
      readonly targetId?: string;
    } = {},
  ) {
    this.allowedTools = options.tools ?? [
      'windows',
      'focus',
      'pointer',
      'type',
      'key',
      'scroll',
    ];
    this.targetId = options.targetId ?? 'native';
  }
  targets(deadline: OperationDeadline): Promise<readonly ComputerTarget[]> {
    deadline.requireActive();
    return Promise.resolve(
      decodeTargets([
        {
          targetId: this.targetId,
          backend: 'macos',
          label: 'Synthetic native desktop',
          surfaces: [{ id: this.surfaceId, label: 'Synthetic display' }],
          capabilities: ['capture', 'structured', 'pointer', 'keyboard'],
        },
      ]),
    );
  }
  capture(): Promise<never> {
    this.bareCaptureCalls++;
    return Promise.reject(new BackendError('CAPTURE_FAILED'));
  }
  acquire(
    value: NativeAcquireRequest,
    deadline: OperationDeadline,
  ): Promise<NativeAcquisition> {
    const request = decodeNativeAcquireRequest(value);
    deadline.requireActive();
    this.acquireCalls++;
    if (request.targetId !== this.targetId)
      return Promise.resolve({ outcome: 'NOT_ACQUIRED', code: 'TARGET_GONE' });
    if (this.hasLease)
      return Promise.resolve({ outcome: 'NOT_ACQUIRED', code: 'BUSY' });
    this.held = decodeNativeLease({
      leaseId: 'native-lease-' + this.acquireCalls,
      targetId: request.targetId,
      ...request.session,
    });
    this.hasLease = true;
    return Promise.resolve({ outcome: 'ACQUIRED', lease: this.held });
  }
  private require(lease: NativeLease): void {
    const checked = decodeNativeLease(lease);
    if (!this.hasLease || JSON.stringify(checked) !== JSON.stringify(this.held))
      throw new NativeControlError('NO_LEASE');
  }
  release(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeCleanupResult> {
    this.require(lease);
    deadline.requireActive();
    this.releaseCalls++;
    this.hasLease = false;
    return Promise.resolve({
      physicalSettlement: 'settled',
      heldInputsReleased: true,
      helperStopped: true,
    });
  }
  state(): NativeState {
    return {
      surfaceId: this.surfaceId,
      applicationId: 'native-app',
      windowId: 'native-window',
      layoutGeneration: 'native-layout-' + this.inputs,
      focusGeneration: 'native-focus',
      displayGeneration: 'native-display',
      interactionGeneration: 'native-interaction',
      availability: this.availability,
      permissions: {
        screenCapture: true,
        accessibility: true,
        eventPosting: true,
        eventListening: true,
      },
      secureInput: 'clear',
      interference: 'clear',
      focusKnown: true,
      focusedElementId: 'native-input',
      display: {
        displayId: this.surfaceId,
        bounds: { x: -300, y: 50, width: 100, height: 80 },
        scaleX: 1,
        scaleY: 1,
        rotation: 0,
      },
      applicationLabel: 'Synthetic fixture',
      bundleId: 'org.example.NativeFixture',
    };
  }
  observation(structured = true): NativeObservation {
    this.captureCalls++;
    const state = this.state();
    const captured = image(this.clock);
    return decodeNativeObservation({
      image: { ...captured, layoutGeneration: state.layoutGeneration },
      state,
      snapshot: structured
        ? {
            snapshotId: 'native-snapshot-' + this.captureCalls,
            rootElementId: null,
            guard: decodeNativeStateGuard({
              surfaceId: state.surfaceId,
              applicationId: state.applicationId,
              windowId: state.windowId,
              layoutGeneration: state.layoutGeneration,
              focusGeneration: state.focusGeneration,
              displayGeneration: state.displayGeneration,
              interactionGeneration: state.interactionGeneration,
            }),
            capturedAt: captured.capturedAt,
            kind: 'ax',
            approximate: true,
            truncated: false,
            nodes: [
              {
                elementId: 'native-input',
                parentElementId: null,
                role: 'AXTextField',
                subrole: null,
                name: 'Synthetic input',
                editable: true,
                focused: true,
                enabled: true,
                secure: false,
                bounds: { x: -300, y: 50, width: 100, height: 80 },
              },
            ],
          }
        : null,
    });
  }
  observe(
    lease: NativeLease,
    request: NativeObserveRequest,
    deadline: OperationDeadline,
  ): Promise<NativeObservation> {
    this.require(lease);
    deadline.requireActive();
    if (request.surfaceId !== this.surfaceId)
      throw new NativeControlError('TARGET_GONE');
    return Promise.resolve(this.observation(request.structured));
  }
  windows(
    lease: NativeLease,
    deadline: OperationDeadline,
  ): Promise<NativeWindows> {
    this.require(lease);
    deadline.requireActive();
    return Promise.resolve(
      decodeNativeWindows({
        windowsId: 'native-windows',
        capturedAt: new Date(this.clock.wall).toISOString(),
        truncated: false,
        windows: [
          {
            surfaceId: 'native-window-surface',
            applicationId: 'native-app',
            windowId: 'native-window',
            captureSurfaceId: this.surfaceId,
            applicationLabel: 'Synthetic fixture',
            bundleId: 'org.example.NativeFixture',
            title: 'Synthetic window',
            bounds: { x: -300, y: 50, width: 100, height: 80 },
            focused: true,
            state: 'available',
          },
        ],
      }),
    );
  }
  focus(
    lease: NativeLease,
    value: NativeFocusRequest,
    deadline: OperationDeadline,
  ): Promise<NativeActionResult> {
    this.require(lease);
    decodeNativeFocusRequest(value);
    deadline.requireActive();
    try {
      requireReadyNativeState(this.state());
    } catch (error) {
      if (error instanceof NativeControlError)
        return Promise.resolve({
          delivery: {
            outcome: 'NOT_DISPATCHED',
            physicalSettlement: 'settled',
            code: error.code,
          },
          observation: { state: 'FAILED', code: error.code },
        });
      throw error;
    }
    this.inputs++;
    return Promise.resolve({
      delivery: {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'focus',
      },
      observation: { state: 'CAPTURED', value: this.observation() },
    });
  }
  perform(
    lease: NativeLease,
    value: NativePerformRequest,
    deadline: OperationDeadline,
  ): Promise<NativeActionResult> {
    this.require(lease);
    const request = decodeNativePerformRequest(value);
    deadline.requireActive();
    this.performed.push(request);
    try {
      requireNativeActionState(this.state(), request.expected, request.action);
    } catch (error) {
      if (error instanceof NativeControlError)
        return Promise.resolve({
          delivery: {
            outcome: 'NOT_DISPATCHED',
            physicalSettlement: 'settled',
            code: error.code,
          },
          observation: { state: 'FAILED', code: error.code },
        });
      throw error;
    }
    this.inputs++;
    return Promise.resolve({
      delivery: this.delivery,
      observation:
        this.delivery.outcome === 'UNCERTAIN'
          ? { state: 'FAILED', code: 'INPUT_FAILED' }
          : { state: 'CAPTURED', value: this.observation() },
    });
  }
}
