import assert from 'node:assert/strict';
import test from 'node:test';
import type { InformationFacetCounts } from 'plowshare-client-ts/operations/information-replies';
import {
  InformationCollectionReads,
  type FacetProgress,
} from './information-collection.ts';

function deferred<T>() {
  let resolve: ((value: T) => void) | undefined;
  let reject: ((reason: Error) => void) | undefined;
  const promise = new Promise<T>((accept, refuse) => {
    resolve = accept;
    reject = refuse;
  });
  return {
    promise,
    resolve(value: T) {
      if (!resolve) throw new Error('Deferred promise was not initialized');
      resolve(value);
    },
    reject(reason: Error) {
      if (!reject) throw new Error('Deferred promise was not initialized');
      reject(reason);
    },
  };
}
const facets: InformationFacetCounts = {
  total: 1,
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
};
const settled = () => new Promise<void>((resolve) => setImmediate(resolve));
function fixture() {
  const reads = new InformationCollectionReads();
  let context = 'account/server/personal';
  let active = true;
  const rendered: string[][] = [];
  const aggregates: InformationFacetCounts[] = [];
  const progress: FacetProgress[] = [];
  const requested: string[] = [];
  const load = (
    list: Promise<string[]>,
    aggregate: Promise<InformationFacetCounts>,
  ) => {
    const key = context;
    return reads.load({
      facetKey: key,
      current: () => active && key === context,
      facetsCurrent: () => active && key === context,
      list: () => {
        requested.push('list');
        return list;
      },
      facets: () => {
        requested.push('facets');
        return aggregate;
      },
      showList: async (value) => {
        rendered.push(value);
      },
      showFacets: (value) => {
        aggregates.push(value);
      },
      facetProgress: (value) => {
        progress.push(value);
      },
    });
  };
  return {
    reads,
    rendered,
    aggregates,
    progress,
    requested,
    load,
    select(value: string) {
      context = value;
    },
    logout() {
      active = false;
      reads.invalidate();
    },
  };
}

await test('list publishes before pending facets and keeps first position on the shared transport', async () => {
  const f = fixture(),
    list = deferred<string[]>(),
    aggregate = deferred<InformationFacetCounts>();
  const loading = f.load(list.promise, aggregate.promise);
  assert.deepEqual(f.requested, ['list', 'facets']);
  list.resolve(['manual']);
  await loading;
  assert.deepEqual(f.rendered, [['manual']]);
  assert.deepEqual(f.aggregates, []);
  assert.deepEqual(f.progress, [{ state: 'loading' }]);
  aggregate.resolve(facets);
  await settled();
  assert.deepEqual(f.aggregates, [facets]);
  assert.deepEqual(f.progress.at(-1), { state: 'ready' });
});
await test('facet rejection is owned independently and leaves an already rendered list intact', async () => {
  const f = fixture(),
    aggregate = deferred<InformationFacetCounts>();
  await f.load(Promise.resolve(['retained report']), aggregate.promise);
  const reason = new Error('facet service refused');
  aggregate.reject(reason);
  await settled();
  assert.deepEqual(f.rendered, [['retained report']]);
  assert.deepEqual(f.progress.at(-1), { state: 'failed', reason });
  assert.deepEqual(f.requested, ['list', 'facets']);
});
await test('an early facet rejection does not reject or prevent the pending list', async () => {
  const f = fixture(),
    list = deferred<string[]>(),
    aggregate = deferred<InformationFacetCounts>();
  const loading = f.load(list.promise, aggregate.promise);
  aggregate.reject(new Error('aggregate unavailable'));
  await settled();
  list.resolve(['documents']);
  await loading;
  assert.deepEqual(f.rendered, [['documents']]);
  assert.equal(f.progress.at(-1)?.state, 'failed');
});
await test('identical pending facet reads coalesce across list refreshes and explicit retry is allowed', async () => {
  const f = fixture(),
    aggregate = deferred<InformationFacetCounts>();
  await f.load(Promise.resolve(['one']), aggregate.promise);
  await f.load(Promise.resolve(['two']), aggregate.promise);
  assert.deepEqual(f.requested, ['list', 'facets', 'list']);
  aggregate.reject(new Error('retryable read failure'));
  await settled();
  await f.load(Promise.resolve(['three']), Promise.resolve(facets));
  await settled();
  assert.deepEqual(f.requested, ['list', 'facets', 'list', 'list', 'facets']);
  assert.deepEqual(f.rendered, [['one'], ['two'], ['three']]);
});
await test('old scope, principal or connection results cannot overwrite the current selection', async () => {
  for (const next of [
    'account/server/project:other',
    'other/server/personal',
    'account/other-server/personal',
  ]) {
    const f = fixture(),
      oldList = deferred<string[]>(),
      oldFacet = deferred<InformationFacetCounts>();
    const old = f.load(oldList.promise, oldFacet.promise);
    f.select(next);
    await f.load(Promise.resolve(['current']), Promise.resolve(facets));
    await settled();
    oldList.resolve(['stale']);
    oldFacet.resolve({ ...facets, total: 99 });
    await old;
    await settled();
    assert.deepEqual(f.rendered, [['current']]);
    assert.deepEqual(f.aggregates, [facets]);
    assert.deepEqual(f.progress.at(-1), { state: 'ready' });
  }
});
await test('close/logout invalidates both late success and late rejection without rendering', async () => {
  const f = fixture(),
    list = deferred<string[]>(),
    aggregate = deferred<InformationFacetCounts>();
  const loading = f.load(list.promise, aggregate.promise);
  f.logout();
  list.resolve(['private']);
  aggregate.reject(new Error('disconnected'));
  await loading;
  await settled();
  assert.deepEqual(f.rendered, []);
  assert.deepEqual(f.aggregates, []);
  assert.deepEqual(f.progress, [{ state: 'loading' }]);
});
