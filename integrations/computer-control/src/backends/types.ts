import type { ComputerBackend } from '../contracts.ts';
import type { OperationDeadline } from '../deadline.ts';

/** Backend resources belong to the embedding host, not to the model-facing tool invocation. */
export interface BackendHandle {
  readonly backend: ComputerBackend;
  close(deadline: OperationDeadline): Promise<void>;
}
