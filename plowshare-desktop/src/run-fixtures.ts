// Synthetic complete server records for desktop orchestration tests.
import type {
  OrchestrationStatus,
  RunView,
} from 'plowshare-client-ts/operations/administrative-replies';
export const runWire = (
  id: string,
  state = 'running',
  extra: Partial<RunView> = {},
): RunView => ({
  id,
  state,
  definition: 'research',
  tier: 'global',
  project: null,
  depth: 0,
  parent: null,
  pendingCap: null,
  result: null,
  failure: null,
  returnsUsed: 0,
  maxReturns: 3,
  nudges: 0,
  restarts: 0,
  callerAgent: 'assistant',
  callerConversation: 'caller',
  conductorConversation: 'conductor',
  waitingFor: null,
  createdAt: '2026-10-01T00:00:00Z',
  endedAt: null,
  stalledSince: null,
  ...extra,
});
export const statusWire = (
  id = 'root',
  state = 'asking',
  extra: Partial<OrchestrationStatus> = {},
): OrchestrationStatus => ({
  orchestration: runWire(id, state),
  todos: [],
  children: [],
  messages: [
    {
      id: 'question-1',
      kind: 'question',
      author: 'conductor',
      text: 'Which sources?',
      createdAt: '2026-10-01T00:00:00Z',
      deliveredAt: null,
      capKind: null,
      structure: null,
    },
  ],
  ...extra,
});
