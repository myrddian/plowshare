import assert from 'node:assert/strict';
import test from 'node:test';
import {
  InformationRefresh,
  type InformationRefreshClock,
} from './information-refresh.ts';
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

class RefreshClock implements InformationRefreshClock {
  now = 0;
  readonly tasks: { at: number; action: () => void; cancelled: boolean }[] = [];
  schedule(delay: number, action: () => void) {
    const task = { at: this.now + delay, action, cancelled: false };
    this.tasks.push(task);
    return () => {
      task.cancelled = true;
    };
  }
  advance(milliseconds: number) {
    const end = this.now + milliseconds;
    for (;;) {
      const next = this.tasks.find((task) => !task.cancelled && task.at <= end);
      if (!next) break;
      this.now = next.at;
      next.cancelled = true;
      next.action();
    }
    this.now = end;
  }
}
function refreshFixture() {
  const clock = new RefreshClock();
  const invalidations: boolean[] = [];
  let reads = 0;
  const owner = new InformationRefresh(
    clock,
    (changed) => {
      invalidations.push(changed);
    },
    () => {
      reads++;
    },
  );
  return { clock, invalidations, owner, reads: () => reads };
}
await test('unchanged information does no four-second reads and reconciles at a bounded interval', () => {
  const f = refreshFixture();
  f.owner.observe('server/account/personal/socket1', 0);
  f.clock.advance(0);
  assert.equal(f.reads(), 1);
  for (let tick = 0; tick < 14; tick++) {
    f.owner.observe('server/account/personal/socket1', 0);
    f.clock.advance(4000);
  }
  assert.equal(f.reads(), 1);
  f.clock.advance(4000);
  assert.equal(f.reads(), 2);
  assert.deepEqual(f.invalidations, [true]);
});
await test('hints hide immediately and bursts coalesce without postponing the first deadline', () => {
  const f = refreshFixture();
  f.owner.observe('selection', 0);
  f.clock.advance(0);
  f.owner.readStarted();
  f.owner.observe('selection', 1);
  f.clock.advance(100);
  f.owner.observe('selection', 2);
  f.clock.advance(149);
  assert.equal(f.reads(), 1);
  assert.deepEqual(f.invalidations, [true, false]);
  f.clock.advance(1);
  assert.equal(f.reads(), 2);
  f.owner.observe('selection', 2);
  f.clock.advance(250);
  assert.equal(f.reads(), 2);
});

await test('a new hint during a read still invalidates its response and schedules recovery', () => {
  const f = refreshFixture();
  f.owner.observe('selection', 0);
  f.clock.advance(0);
  f.owner.readStarted();
  f.owner.observe('selection', 1);
  f.clock.advance(250);
  f.owner.readStarted();
  f.owner.observe('selection', 2);
  assert.deepEqual(f.invalidations, [true, false, false]);
  f.clock.advance(250);
  assert.equal(f.reads(), 3);
});
await test('scope/account/server/socket switches fence already queued callbacks and recover immediately', () => {
  for (const identity of [
    'other-scope',
    'other-account',
    'other-server',
    'same-account/new-socket',
  ]) {
    const f = refreshFixture();
    f.owner.observe('initial', 0);
    const stale = f.clock.tasks.slice();
    f.owner.observe(identity, 0);
    for (const task of stale) task.action();
    assert.equal(f.reads(), 0);
    f.clock.advance(0);
    assert.equal(f.reads(), 1);
    assert.deepEqual(f.invalidations, [true, true]);
  }
});
await test('logout stops all timers; reconnect requires fresh reads even with unchanged hint counter', () => {
  const f = refreshFixture();
  f.owner.observe('selection', 0);
  f.clock.advance(0);
  f.owner.observe('selection', 1);
  const stale = f.clock.tasks.slice();
  f.owner.observe(undefined, 1);
  for (const task of stale) task.action();
  f.clock.advance(120_000);
  assert.equal(f.reads(), 1);
  f.owner.observe('selection', 1);
  f.clock.advance(0);
  assert.equal(f.reads(), 2);
});
await test('manual recovery consumes pending hint and destroy fences cancelled callback execution', () => {
  const f = refreshFixture();
  f.owner.observe('selection', 0);
  const initial = f.clock.tasks[0];
  assert.ok(initial);
  f.owner.refreshNow();
  initial.action();
  assert.equal(f.reads(), 1);
  f.owner.observe('selection', 1);
  const stale = f.clock.tasks.slice();
  f.owner.stop();
  for (const task of stale) task.action();
  f.clock.advance(120_000);
  assert.equal(f.reads(), 1);
});

await test('progress coalesces without hiding or cancelling reads; authority upgrades its timer', () => {
  const f = refreshFixture();
  f.owner.observe('selection', 0, 0);
  f.clock.advance(0);
  f.owner.readStarted();
  for (let hint = 1; hint <= 100; hint++) f.owner.observe('selection', 0, hint);
  assert.deepEqual(
    f.invalidations,
    [true],
    'Progress must not hide content or retire in-flight reads',
  );
  f.clock.advance(999);
  assert.equal(f.reads(), 1);
  f.clock.advance(1);
  assert.equal(
    f.reads(),
    2,
    'Continuous progress must neither starve nor multiply refreshes',
  );
  f.owner.readStarted();
  f.owner.observe('selection', 0, 101);
  const stale = f.clock.tasks.filter(
    (task) => !task.cancelled && task.at < 60_000,
  );
  f.owner.observe('selection', 1, 101);
  assert.deepEqual(
    f.invalidations,
    [true, false],
    'Authority/content still fences immediately',
  );
  f.clock.advance(250);
  assert.equal(
    f.reads(),
    3,
    'A full invalidation takes priority over progress',
  );
  for (const task of stale) task.action();
  assert.equal(
    f.reads(),
    3,
    'Cancelled soft work cannot resurrect after authority invalidation',
  );
  f.owner.observe('selection', 1, 102);
  f.owner.observe(undefined, 1, 102);
  f.clock.advance(120_000);
  assert.equal(f.reads(), 3, 'Logout fences progress timers too');
});
