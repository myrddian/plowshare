import { demoState } from '../demo.ts';
import type { DesktopApi } from '../shared.ts';
import type { InformationFacetCounts } from 'plowshare-client-ts/operations/information-replies';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import { installInformation } from './information.ts';

const state = {
  ...demoState(),
  mode: 'live' as const,
  connected: true,
  base: 'https://fixture.invalid',
  handle: 'fixture',
};
const pending: {
  resolve: (value: InformationFacetCounts) => void;
  reject: (reason: Error) => void;
}[] = [];
const requests: string[] = [];
const api: DesktopApi = {
  async request(request) {
    if (request.action !== 'information')
      throw new Error('Unexpected fixture action');
    requests.push(request.operation);
    if (request.operation === 'list') {
      const collection =
        request.scope.kind === 'project'
          ? request.scope.project
          : request.scope.kind;
      return {
        state,
        information: decodeReply('information.list', [
          {
            id: 'aaaaaaaa-0000-0000-0000-000000000001',
            kind: 'report',
            source_name: `${state.handle}/${collection}`,
            report_status: 'draft',
            availability: 'active',
            ordinal: 1,
            tags: [],
            autoTag: [],
          },
        ]),
      };
    }
    if (request.operation === 'facets') {
      const information = await new Promise<InformationFacetCounts>(
        (resolve, reject) => {
          pending.push({ resolve, reject });
        },
      );
      return { state, information };
    }
    throw new Error('Optional fixture capability unavailable');
  },
  subscribe() {
    return () => undefined;
  },
};
const host = document.querySelector<HTMLElement>('#collection');
if (!host) throw new Error('Fixture host missing');
const panel = installInformation(
  api,
  () => state,
  () => '',
  host,
  { kind: 'report' },
);
const facetValue = (total: number): InformationFacetCounts => ({
  total,
  facets: {
    kind: [],
    tags: [],
    autoTag: [],
    tagGroup: [],
    author: [],
    documentAuthor: [],
    when: [],
    subtype: [],
  },
  hasMore: {
    kind: false,
    tags: false,
    autoTag: false,
    tagGroup: false,
    author: false,
    documentAuthor: false,
    when: false,
    subtype: false,
  },
  tagGraph: { edges: [], hasMore: false },
});
Object.assign(window, {
  informationCollectionFixture: {
    requests,
    open() {
      panel.open();
      host.querySelector<HTMLDetailsElement>('[data-faceting]')!.open = true;
    },
    refresh() {
      panel.refresh();
    },
    resolve(index: number, total = 1) {
      const request = pending[index];
      if (!request) throw new Error('Missing deferred facets');
      request.resolve(facetValue(total));
    },
    reject(index: number) {
      const request = pending[index];
      if (!request) throw new Error('Missing deferred facets');
      request.reject(new Error('Facet fixture failure'));
    },
    logout() {
      state.connected = false;
      state.handle = '';
      panel.close();
    },
    destroy() {
      panel.destroy();
    },
  },
});
