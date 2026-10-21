import { demoState } from '../demo.ts';
import type { DesktopApi, DesktopState } from '../shared.ts';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import { installInformation } from './information.ts';

// Deferred text replies exercise the installed reader, including stale responses
// after a hint, rather than merely testing its refresh scheduler in isolation.
const state: DesktopState = {
  ...demoState(),
  mode: 'live',
  connected: true,
  base: 'https://fixture.invalid',
  handle: 'fixture',
  informationRevision: 0,
  informationConnection: 0,
};
const host = document.querySelector<HTMLElement>('#reader');
if (!host) throw new Error('Missing reader fixture host');
const text =
  '# Retained reader\n\n' +
  'A complete paragraph.\n\n'.repeat(1600) +
  'Final retained paragraph.';
const revision = 'aaaaaaaa-0000-0000-0000-000000000001';
const requests: string[] = [];
const pending: (() => void)[] = [];
let listener: ((snapshot: DesktopState) => void) | undefined;
let manual = false;
let held = true;
let refused = false;
let generation = 1;
let failRead = false;
let panel: ReturnType<typeof installInformation> | undefined;
const row = () => ({
  id: revision,
  kind: manual ? 'source' : 'report',
  source_name: manual ? 'Plowshare manual / 00-index.md' : 'Retained report',
  title: manual ? 'Manual chapter' : 'Research report',
  availability: 'active',
  ordinal: 1,
  tags: manual ? ['plowshare-manual', 'manual-chapter-00-index'] : [],
  autoTag: [],
});
const api: DesktopApi = {
  async request(request) {
    if (request.action !== 'information')
      throw new Error('Unexpected fixture request');
    requests.push(request.operation);
    if (refused) throw new Error('Selection permission revoked');
    switch (request.operation) {
      case 'list':
        return { state, information: decodeReply('information.list', [row()]) };
      case 'status':
        return {
          state,
          information: decodeReply('information.status', {
            ...row(),
            generation,
            can_manage: false,
            inputs: [],
            citations: [],
            steps: ['extract', 'derive'].map((stage) => ({
              stage,
              state: 'ready',
              attempt: 1,
              generation,
              compatible: true,
            })),
          }),
        };
      case 'read': {
        if (failRead) {
          failRead = false;
          throw new Error('Temporary text read failure');
        }
        const start = request.payload.offset ?? 0;
        const end = Math.min(
          text.length,
          start + (request.payload.limit ?? 32768),
        );
        const information = decodeReply('information.read', {
          revision,
          start,
          end,
          total: text.length,
          text: text.slice(start, end),
        });
        if (held) await new Promise<void>((resolve) => pending.push(resolve));
        return { state, information };
      }
      default:
        throw new Error('Optional fixture capability unavailable');
    }
  },
  subscribe(next) {
    listener = next;
    return () => {
      listener = undefined;
    };
  },
};
Object.assign(window, {
  informationReaderFixture: {
    requests,
    open(kind: 'manual' | 'report') {
      manual = kind === 'manual';
      panel = installInformation(
        api,
        () => state,
        () => '',
        host,
        {
          kind: manual ? 'source' : 'report',
          ...(manual ? { manual: true } : {}),
        },
      );
      panel.open(manual ? undefined : revision);
    },
    hint() {
      state.informationRevision = (state.informationRevision ?? 0) + 1;
      listener?.(state);
    },
    release() {
      held = false;
      for (const resolve of pending.splice(0)) resolve();
    },
    hold() {
      held = true;
      generation++;
    },
    failNextRead() {
      generation++;
      failRead = true;
    },
    revoke() {
      refused = true;
      this.hint();
    },
    pendingCount() {
      return pending.length;
    },
    refresh() {
      panel?.refresh();
    },
    logout() {
      state.connected = false;
      listener?.(state);
    },
    destroy() {
      panel?.destroy();
    },
  },
});
