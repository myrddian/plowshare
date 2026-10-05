import type {
  ConflictRow,
  UnionStatus,
} from 'plowshare-client-ts/operations/union';
import type { ConflictPreview } from 'plowshare-client-node/sync/conflicts';
export interface SyncState {
  project: string;
  status?: UnionStatus;
  conflicts: readonly ConflictRow[];
  loading?: boolean;
  busy?: boolean;
  error?: string;
  notice?: string;
  standing?: string | undefined;
  uncertain?: boolean;
  preview?: ConflictPreview;
}
export const syncIdentity = (state: SyncState) =>
  JSON.stringify([state.project, state.status]);
export const previewIdentity = (preview: ConflictPreview) =>
  JSON.stringify([preview.row, preview.localHash]);
