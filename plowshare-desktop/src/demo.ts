import { demoBoard } from './board-demo.ts';
import { contextKey, emptyActivity } from './shared.ts';
import type { Agent, DesktopState, Entry } from './shared.ts';
const entry = (
  ordinal: number,
  kind: string,
  text: string,
  extra = {},
): Entry => ({
  ordinal,
  turnOrdinal: 1,
  kind,
  state: 'stands',
  text,
  ...extra,
});
export function demoState(): DesktopState {
  const agent: Agent = {
    name: 'plowshare',
    bot: true,
    served: true,
    preferred: true,
    description: 'General conversation · demo agent',
    tools: ['document_search', 'memory_navigate'],
    withheld: [],
    calls: [],
    scopes: [],
    orchestrations: [],
  };
  return {
    mode: 'demo',
    connected: false,
    connection: 'Offline demo',
    base: '',
    handle: '',
    board: demoBoard(),
    files: { status: 'off' },
    projects: [{ name: 'Research' }, { name: 'Plowshare' }],
    agents: { '': [agent], Research: [agent], Plowshare: [agent] },
    conversations: [
      { id: 'demo-research', project: 'Research', title: 'The peaceful atom' },
      {
        id: 'demo-desktop',
        project: 'Plowshare',
        title: 'A workspace for everything',
      },
      { id: 'demo-global', title: 'Start with a question' },
    ],
    history: {
      'demo-research': {
        more: false,
        entries: [
          entry(
            1,
            'utterance',
            'Help me explore the ideas behind Project Plowshare. What should I read first?',
            { speaker: 'person' },
          ),
          entry(
            2,
            'answer',
            'Finding a starting point in the source library.',
            {
              asked: 1,
              calls: [
                {
                  id: 'demo-call',
                  name: 'document_search',
                  arguments: '{"query":"Project Plowshare history"}',
                  length: 36,
                  cut: false,
                },
              ],
            },
          ),
          entry(
            3,
            'tool_result',
            'Sample library references: program overview, research notes, historical context. These are demonstration data, not a retrieved result.',
            { toolCallId: 'demo-call', outcome: 'ok', tookMillis: 120 },
          ),
          entry(
            4,
            'answer',
            'Start with the original **ambitions**, then follow the experiments and the questions they left behind.\n\n### Three reading threads\n\n- **The scientific proposal:** what was considered possible?\n- **The practical experiments:** what did the evidence show?\n- **The public consequences:** who lived with the results?\n\n| Thread | Guiding question |\n| --- | --- |\n| Proposal | What was considered possible? |\n| Evidence | What did the experiments show? |\n| Consequences | Who lived with the results? |\n\n```text\nProposal → Experiment → Evidence\n                         ↓\n                    Consequences\n```\n\n> This is a sample conversation for trying the desktop. Connect your Plowshare server to work with real sources and agents.',
          ),
        ],
      },
      'demo-desktop': {
        more: false,
        entries: [
          entry(
            1,
            'utterance',
            'A desktop should make room for research, writing, coding and everyday work.',
            { speaker: 'person' },
          ),
          entry(
            2,
            'answer',
            'Keep the conversation close, with the useful material beside it.\n\nSwitch between independent conversations, inspect the tools when you need to, and keep the interface quiet while work runs. Your draft stays with its conversation.\n\nTry floating or docking this chat, open the trajectory window, or start two demo conversations at once.',
          ),
        ],
      },
      'demo-global': { more: false, entries: [] },
    },
    jobs: [],
    approvals: [],
    activity: {
      ...emptyActivity(),
      inbox: {
        loaded: true,
        unread: 1,
        read: ['demo-read-notice'],
        items: [
          {
            id: 'demo-notice',
            kind: 'notice',
            arrivedAt: '2026-10-01T10:00:00Z',
            answer:
              'Your library is ready.\n\nThis is a **sample inbox notice**. Read receipts in the demo stay on this computer.',
          },
          {
            id: 'demo-read-notice',
            kind: 'notice',
            arrivedAt: '2026-09-30T10:00:00Z',
            readAt: '2026-09-30T10:05:00Z',
            answer:
              'An earlier sample notice.\n\nRead items remain in your mailbox.',
          },
        ],
      },
      runs: {
        loaded: true,
        items: [
          {
            id: 'demo-run',
            definition: 'deep_research',
            tier: 'project',
            project: 'Research',
            state: 'finished',
            depth: 0,
            createdAt: '2026-10-01T09:00:00Z',
            result: 'A sample research summary. No background work is running.',
          },
        ],
      },
      details: {
        'demo-run': {
          value: {
            run: {
              id: 'demo-run',
              definition: 'deep_research',
              tier: 'project',
              project: 'Research',
              state: 'finished',
              depth: 0,
              createdAt: '2026-10-01T09:00:00Z',
              result:
                'A sample research summary. No background work is running.',
            },
            stages: [
              {
                text: 'Explore the source library',
                status: 'done',
                summary: 'Sample references collected.',
              },
            ],
            messages: [],
            children: [],
          },
        },
      },
    },
    contexts: {
      [contextKey('demo-research', 'plowshare')]: {
        status: 'ready',
        sent: 15240,
        limit: 131072,
        model: 'Sample model',
        sample: true,
      },
    },
  };
}
export function demoAnswer(task: string): string {
  return `This is a local demonstration response. No model or server has been called.\n\nYour message was: “${task}”\n\nYou can switch conversations while this sample streams, keep a separate draft in each, inspect execution events, or cancel the demo run. Use “Connect server” for a real Plowshare conversation.`;
}
