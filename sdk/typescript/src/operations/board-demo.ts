import type { SwarmActivity } from './swarm.ts';
import type { EntryPageView } from './conversation-replies.ts';
import type {
  BoardInspection,
  Topic,
  SeatView,
  BoardMessage,
} from './board.ts';
export function demoBoard(): BoardInspection {
  const root: Topic = {
    id: 'demo-board',
    project: 'Plowshare',
    parent: null,
    root: 'demo-board',
    depth: 0,
    title: 'How should device sync work?',
    label: 'NEED INFO',
    account: 'demo',
    openerKind: 'bot',
    opener: 'farnsworth',
    originConversation: 'demo-desktop',
    state: 'open',
    resolution: null,
    potTotal: 120,
    potSpent: 49,
    reserve: 12,
    quietNotifiedAt: null,
    openedAt: '2026-10-01T01:20:00Z',
    closedAt: null,
  };
  const child: Topic = {
    ...root,
    id: 'demo-child',
    parent: root.id,
    root: root.id,
    depth: 1,
    title: 'Compare conflict strategies',
    label: 'RESEARCH',
    openerKind: 'member',
    opener: 'researcher',
    originConversation: null,
    potTotal: null,
    potSpent: null,
    reserve: null,
  };
  const seats: SeatView[] = ['researcher', 'spec_writer', 'critic'].map(
    (occupant, i) => ({
      seat: {
        topic: root.id,
        occupant,
        conversation: `demo-swarm-${occupant}`,
        passed: i === 2,
        failedEnding: null,
        silentWakes: 0,
        seenThrough: null,
        alertsUsed: 0,
      },
      state: ['running', 'ready', 'passed'][i]!,
      job: i === 0 ? 'demo-job' : null,
      reason: null,
      position: i === 1 ? 2 : null,
      waitedMillis: i === 1 ? 12000 : null,
      overdue: false,
    }),
  );
  const messages: BoardMessage[] = [
    {
      id: 'demo-message-1',
      topic: root.id,
      replyTo: null,
      authorKind: 'opener',
      author: 'farnsworth',
      conversation: 'demo-desktop',
      entry: 1,
      kind: 'post',
      title: null,
      body: 'We need a clear design for **device sync**. Research conflicts, propose a spec, and challenge the assumptions.',
      alert: false,
      mentions: [],
      postedAt: root.openedAt,
    },
    {
      id: 'demo-message-2',
      topic: root.id,
      replyTo: 'demo-message-1',
      authorKind: 'member',
      author: 'researcher',
      conversation: 'demo-desktop',
      entry: null,
      kind: 'document',
      title: 'Initial findings',
      body: '### Two approaches\n\n| Strategy | Tradeoff |\n| --- | --- |\n| Last write wins | Simple, but can discard edits |\n| Merge operations | Preserves edits, needs stable identities |\n\nThe evidence favours testing concurrent offline edits before choosing.',
      alert: false,
      mentions: ['critic'],
      postedAt: '2026-10-01T01:22:00Z',
    },
    {
      id: 'demo-message-3',
      topic: root.id,
      replyTo: 'demo-message-2',
      authorKind: 'member',
      author: 'researcher',
      conversation: 'demo-desktop',
      entry: null,
      kind: 'request',
      title: child.title,
      body: 'Compare the conflict strategies in a focused child topic.',
      alert: true,
      mentions: [],
      postedAt: '2026-10-01T01:24:00Z',
    },
  ];
  const topics = [
    { topic: root, messages: 3, documents: 1 },
    { topic: child, messages: 1, documents: 0 },
  ];
  const activity = Object.fromEntries(
    seats.map((s) => [
      s.seat.conversation,
      {
        value: {
          through: 3,
          entries: [
            {
              ordinal: 1,
              turnOrdinal: 1,
              kind: 'utterance',
              state: 'stands' as const,
              text: 'Research device sync conflicts.',
            },
            {
              ordinal: 2,
              turnOrdinal: 1,
              kind: 'answer',
              state: 'stands' as const,
              calls: [
                {
                  id: 'sample-call',
                  name:
                    s.seat.occupant === 'researcher' ? 'search' : 'board_post',
                  arguments: '{}',
                  length: 2,
                  cut: false,
                  salient:
                    s.seat.occupant === 'researcher'
                      ? 'offline conflict strategies'
                      : 'design findings',
                },
              ],
            },
            {
              ordinal: 3,
              turnOrdinal: 1,
              kind: 'tool_result',
              state: 'stands' as const,
              toolCallId: 'sample-call',
              outcome: 'ok',
              text: 'Sample evidence collected.',
            },
          ],
        },
      },
    ]),
  );
  return {
    activity,
    topics: { value: topics, more: false },
    details: {
      [root.id]: {
        value: {
          topic: root,
          root,
          seats,
          messages,
          decisions: [
            {
              request: 'demo-message-3',
              approved: true,
              reason: 'Investigate independently',
              child: child.id,
            },
          ],
        },
      },
      [child.id]: {
        value: {
          topic: child,
          root,
          seats: [],
          messages: [
            {
              ...messages[0]!,
              id: 'demo-child-message',
              topic: child.id,
              replyTo: null,
              body: 'Compare last-write-wins and merge operations for concurrent offline edits.',
            },
          ],
          decisions: [],
        },
      },
    },
    swarm: {
      value: {
        pools: [{ pool: 'spark', used: 1, slots: 3 }],
        ready: [
          {
            topic: root.id,
            member: 'spec_writer',
            specifier: 'reasoning',
            position: 2,
            waitedMillis: 12000,
            overdue: false,
          },
        ],
        topics,
        seats,
        more: false,
      },
    },
  };
}

/** Preview data uses the same complete DTO as a server trajectory response. */
export function demoActivityPage(
  activity: SwarmActivity | undefined,
): EntryPageView {
  const entries = (activity?.entries ?? []).map((e) => ({
    ordinal: e.ordinal,
    turnOrdinal: e.turnOrdinal,
    kind: e.kind,
    excerpt: e.text ?? null,
    length: e.length ?? e.text?.length ?? 0,
    cut: e.cut ?? false,
    ejectedAt: e.ejectedAt ?? null,
    supersededBy: e.supersededBy ?? null,
    toolCallId: e.toolCallId ?? null,
    toolCalls: (e.calls ?? []).map((call) => ({
      ...call,
      salient: call.salient ?? null,
      opened: call.opened
        ? { conversation: call.opened.conversation, agent: call.opened.agent }
        : null,
    })),
    handle: e.handle ?? null,
    recordedAt: e.recordedAt ?? null,
    tookMillis: e.tookMillis ?? null,
    dispatch: e.dispatch ?? null,
    wireModel: e.wireModel ?? null,
    completion: e.completion ?? null,
    speaker: e.speaker ?? null,
    speakerName: e.speakerName ?? null,
    outcome: e.outcome ?? null,
  }));
  return {
    entries,
    total: entries.length,
    offset: 0,
    limit: 24,
    through: activity?.through ?? 0,
    oldest: entries[0]?.ordinal ?? null,
    more: false,
  };
}
