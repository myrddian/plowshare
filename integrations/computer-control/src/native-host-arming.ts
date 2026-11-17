import type { ComputerOwner, SessionReference } from './contracts.ts';
import type { OperationDeadline } from './deadline.ts';
import type { NativeArmRequest, NativeArmStatus } from './native-arming.ts';

/** Host-local capability. Never register these methods as Relay/model tools. */
export interface NativeHostArming {
  /** Immutable bounded owner/session inventory; no private helper proof material. */
  sessions(): readonly NativeHostSession[];
  request(
    owner: ComputerOwner,
    session: SessionReference,
    request: NativeArmRequest,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus>;
  status(
    owner: ComputerOwner,
    session: SessionReference,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus>;
  /** Bypasses normal input's busy lane; invalidates all prior action references first. */
  revoke(
    owner: ComputerOwner,
    session: SessionReference,
    deadline: OperationDeadline,
  ): Promise<NativeArmStatus>;
}
export interface NativeHostSession {
  readonly owner: ComputerOwner;
  readonly session: SessionReference;
  readonly targetId: string;
  readonly closing: boolean;
}
