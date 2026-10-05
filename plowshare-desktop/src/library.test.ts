import { checkedSender } from './fixture.test-support.ts';
import { fixtures, replyOf } from './replies.test-support.ts';
import { field, list } from './json.test-support.ts';
import { present } from './fixture.test-support.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { LibraryClient } from './library.ts';
import { libraryIdentity, memoryIdentity } from './library-shared.ts';
import { demoState } from './demo.ts';
import { inspectionConversation } from './shared.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { Request as WsRequest } from 'plowshare-client-ts/operations/direct';
const source = fixtures(
  readFileSync(
    new URL(
      '../../test-support/contracts/ws-retrieval-fixtures.json',
      import.meta.url,
    ),
    'utf8',
  ),
);

await test('Help navigation clears project scope and resolves chapters in the reader without eager work', async () => {
  const f = fixture();
  await f.client.open('manual', 'repo');
  assert.equal(f.state.library!.project, null);
  assert.equal(f.state.library!.manual!.chapter, '00-index');
  const sequence = f.state.library!.manual!.sequence;
  await f.client.open('manual', null, undefined, '12-hooks');
  assert.equal(f.state.library!.manual!.chapter, '12-hooks');
  assert.ok(f.state.library!.manual!.sequence > sequence);
  await assert.rejects(
    f.client.open('manual', null, undefined, '../index'),
    /valid manual chapter/,
  );
  assert.equal(f.calls.length, 0);
  await f.client.open('sources');
  assert.equal(f.state.library!.manual, undefined);
});
function fixture() {
  const replies = structuredClone(source);
  replies['proposal.list'][0] = {
    ...present(replies['proposal.list'][0]),
    project: null,
    state: 'pending',
    resolvedAt: null,
    resolvedBy: null,
    resolution: null,
  };
  replies['proposal.resolve'].proposal = {
    ...present(replies['proposal.list'][0]),
    state: 'accepted',
    resolvedAt: '2026-10-02T10:00:00Z',
    resolvedBy: 'fixture',
    resolution: 'accepted',
  };
  const state = demoState();
  state.mode = 'live';
  state.connected = true;
  state.handle = 'fixture';
  state.projects = [{ name: 'repo' }];
  const calls: WsRequest[] = [];
  let reply: (ask: WsRequest) => Outcome | Promise<Outcome> = (ask) => {
    const payload = structuredClone(replyOf(replies, ask.type));
    if (
      payload &&
      typeof payload === 'object' &&
      'query' in payload &&
      'query' in ask.payload
    )
      payload.query = ask.payload.query;
    if (
      payload &&
      typeof payload === 'object' &&
      'question' in payload &&
      'question' in ask.payload
    )
      payload.question = ask.payload.question;
    return { code: 'OK', payload };
  };
  const client = new LibraryClient(
    () => state,
    checkedSender(async (ask) => {
      calls.push(ask);
      return reply(ask);
    }),
    () => {},
  );
  return {
    state,
    client,
    calls,
    replies,
    setReply: (fn: typeof reply) => {
      reply = fn;
    },
  };
}
await test('document semantic search uses the server vector mode while conversation search stays semantic', async () => {
  const f = fixture();
  await f.client.search('documents', 'question', 'semantic');
  assert.equal(field(f.calls.at(-1)!.payload, ['mode']), 'vector');
  assert.equal(f.state.library!.search.value!.mode, 'semantic');
  await f.client.search('conversation', 'question', 'semantic');
  assert.equal(field(f.calls.at(-1)!.payload, ['mode']), 'semantic');
});
await test('explicit WS scope preserves provenance and omits unknown fields; changing scope clears memory/search', async () => {
  const f = fixture();
  f.setReply((a) => ({
    code: 'OK',
    payload:
      a.type === 'memory.read'
        ? { ...f.replies['memory.read'], future: { origin: 'preserve' } }
        : replyOf(f.replies, a.type),
  }));
  await f.client.open('memories', null);
  await f.client.memory('memory_fixture');
  assert.deepEqual(present(f.calls[0]).payload, { project: null });
  assert.equal(f.state.library!.memory.value!.formed.where, 'review');
  assert.equal(field(f.state.library!.memory.value, ['future']), undefined);
  await f.client.open('memories', 'repo');
  assert.deepEqual(f.calls.at(-1)!.payload, { project: 'repo' });
  assert.equal(f.state.library!.memory.value, undefined);
  await assert.rejects(
    f.client.open('memories', 'foreign'),
    /available project/,
  );
  assert.equal(
    f.calls.some((a) => ['agent.run', 'memory.digest'].includes(a.type)),
    false,
  );
});
await test('duplicate and incorrect document page coordinates retain the previous list and filter', async () => {
  const f = fixture(),
    page = f.replies['document.list'];
  page.documents = [present(page.documents[0])];
  page.total = 2;
  page.limit = 50;
  page.offset = 0;
  await f.client.documents('source');
  const initial = structuredClone(f.state.library!.documents.value);
  f.setReply(() => ({ code: 'OK', payload: { ...page, offset: 1 } }));
  await f.client.documents('source', true);
  assert.deepEqual(f.state.library!.documents.value, initial);
  assert.match(f.state.library!.documents.error!, /changed while paging/);
  f.setReply(() => ({ code: 'OK', payload: { ...page, offset: 12 } }));
  await f.client.documents('changed');
  assert.deepEqual(f.state.library!.documents.value, initial);
  assert.equal(f.state.library!.documents.query, 'source');
});
await test('foreign document and chunk replies never become displayed sources', async () => {
  const f = fixture();
  await f.client.documents('');
  const id = present(f.replies['document.list'].documents[0]).documentId;
  f.setReply((a) => ({
    code: 'OK',
    payload:
      a.type === 'document.detail'
        ? { ...f.replies['document.detail'], documentId: 'foreign' }
        : replyOf(f.replies, a.type),
  }));
  await f.client.document(id);
  assert.equal(f.state.library!.document.value, undefined);
  await assert.rejects(f.client.document('foreign'), /displayed/);
  await f.client.search('documents', 'question', 'hybrid');
  const hit = present(f.replies['document.search'].hits[0]);
  f.setReply(() => ({
    code: 'OK',
    payload: {
      ...f.replies['document.chunk'],
      chunkId: hit.chunkId,
      documentId: 'foreign',
    },
  }));
  await f.client.chunk(hit.chunkId);
  assert.equal(f.state.library!.chunk.value, undefined);
  assert.match(f.state.library!.chunk.error!, /different source/);
  await assert.rejects(f.client.memory('foreign'), /displayed/);
});
await test('reset discards late memory reads', async () => {
  const f = fixture();
  let finish!: (reply: Outcome) => void;
  f.setReply((a) =>
    a.type === 'memory.index'
      ? new Promise((r) => {
          finish = r;
        })
      : { code: 'OK', payload: [] },
  );
  const reading = f.client.open('memories');
  await Promise.resolve();
  f.client.reset();
  f.state.library!.memories = {};
  f.state.connected = false;
  finish({ code: 'OK', payload: f.replies['memory.index'] });
  await reading;
  assert.equal(f.state.library!.memories.value, undefined);
});
await test('latest query wins; incomplete navigation stays incomplete and malformed replies preserve it', async () => {
  const f = fixture();
  let finish!: (reply: Outcome) => void;
  f.setReply(
    () =>
      new Promise((r) => {
        finish = r;
      }),
  );
  const old = f.client.search('recall', 'old', 'semantic');
  f.setReply((a) => ({ code: 'OK', payload: replyOf(f.replies, a.type) }));
  await f.client.search('navigate', 'new', 'hybrid');
  finish({ code: 'OK', payload: f.replies['memory.recall'] });
  await old;
  assert.equal(f.state.library!.search.value!.query, 'new');
  assert.equal(
    field(f.state.library!.search.value!.reply, ['complete']),
    false,
  );
  f.setReply(() => ({ code: 'OK', payload: { hits: [] } }));
  await f.client.search('conversation', 'bad', 'hybrid');
  assert.equal(f.state.library!.search.value!.query, 'new');
  assert.match(f.state.library!.search.error!, /incomplete|Unreadable/);
});
await test('semantic paging pins snapshots and refuses paging when none was supplied', async () => {
  const f = fixture(),
    first = f.replies['conversation.search'];
  first.total = 2;
  first.limit = 50;
  first.offset = 0;
  first.hits = first.hits.slice(0, 1);
  first.retrieval = {
    requestedMode: 'semantic',
    effectiveMode: 'semantic',
    totalMeaning: 'candidate_count',
    snapshot: 'pinned',
    truncated: false,
    complete: true,
    fallback: null,
    coverage: null,
    generation: 'g1',
    queryEmbeddingCalls: 1,
    provenance: 'materialized',
  };
  await f.client.search('conversation', 'query', 'semantic');
  f.setReply(() => ({
    code: 'OK',
    payload: {
      ...first,
      offset: 1,
      hits: [
        {
          ...present(first.hits[0]),
          ordinal: present(first.hits[0]).ordinal + 1,
        },
      ],
    },
  }));
  await f.client.search('conversation', 'query', 'semantic', true);
  assert.equal(field(f.calls.at(-1)!.payload, ['snapshot']), 'pinned');
  assert.equal(field(f.calls.at(-1)!.payload, ['offset']), 1);
  assert.equal(list(f.state.library!.search.value!.reply, ['hits']).length, 2);
  f.setReply(() => ({
    code: 'OK',
    payload: {
      ...first,
      offset: 0,
      retrieval: { ...first.retrieval, snapshot: null },
    },
  }));
  await f.client.search('conversation', 'query', 'semantic');
  await f.client.search('conversation', 'query', 'semantic', true);
  assert.match(f.state.library!.search.error!, /snapshot/);
  assert.equal(f.calls.length, 3);
});
await test('search admits only displayed conversations for trajectories without adding them to chat', async () => {
  const f = fixture();
  await f.client.search('conversation', 'query', 'lexical');
  const id = present(f.replies['conversation.search'].hits[0]).conversationId;
  assert.equal(f.client.conversation(id), id);
  assert.equal(inspectionConversation(f.state, id), true);
  assert.equal(
    f.state.conversations.some((r) => r.id === id),
    false,
  );
  assert.throws(() => f.client.conversation('foreign'), /current search/);
  await f.client.open('search', 'repo');
  assert.equal(inspectionConversation(f.state, id), false);
});
await test('stale memory or reset preflight cannot send invalidation', async () => {
  const f = fixture();
  await f.client.open('memories');
  await f.client.memory('memory_fixture');
  const m = f.state.library!.memory.value!;
  f.setReply(() => ({
    code: 'OK',
    payload: { ...m, body: 'changed content' },
  }));
  await assert.rejects(
    f.client.maintain('invalidate', memoryIdentity(m), 'obsolete'),
    /changed elsewhere/,
  );
  assert.equal(
    f.calls.some((a) => a.type === 'memory.invalidate'),
    false,
  );
  let finish!: (reply: Outcome) => void;
  f.setReply(
    () =>
      new Promise((r) => {
        finish = r;
      }),
  );
  const changing = f.client.maintain(
    'invalidate',
    memoryIdentity(m),
    'obsolete',
  );
  f.client.reset();
  finish({ code: 'OK', payload: m });
  await assert.rejects(changing, /connection or scope changed/);
  assert.equal(
    f.calls.some((a) => a.type === 'memory.invalidate'),
    false,
  );
});
await test('confirmed mutations survive failed refresh; ambiguous mutations are sent once', async () => {
  const f = fixture();
  await f.client.open('memories');
  await f.client.memory('memory_fixture');
  const m = f.state.library!.memory.value!;
  f.setReply((a) =>
    a.type === 'memory.index' || a.type === 'proposal.list'
      ? { code: 'BAD_REQUEST', said: 'read failed' }
      : { code: 'OK', payload: replyOf(f.replies, a.type) },
  );
  await f.client.maintain('invalidate', memoryIdentity(m), 'obsolete');
  assert.match(f.state.library!.notice!, /confirmed/);
  assert.match(f.state.library!.memories.error!, /read failed/);
  assert.equal(
    field(f.calls.find((a) => a.type === 'memory.invalidate')!.payload, ['by']),
    'fixture',
  );
  const g = fixture();
  await g.client.open('memories');
  await g.client.memory('memory_fixture');
  g.setReply((a) => {
    if (a.type === 'memory.invalidate') throw Error('connection lost');
    return { code: 'OK', payload: replyOf(g.replies, a.type) };
  });
  await assert.rejects(
    g.client.maintain(
      'invalidate',
      memoryIdentity(g.state.library!.memory.value!),
      'obsolete',
    ),
    /connection lost/,
  );
  assert.match(g.state.library!.error!, /not be replayed/);
  assert.equal(g.calls.filter((a) => a.type === 'memory.invalidate').length, 1);
});
await test('proposal resolution and scope-wide repair serialize duplicate clicks and scope changes', async () => {
  const f = fixture();
  await f.client.open('memories');
  const p = f.state.library!.proposals.value![0];
  await f.client.maintain(
    'resolve',
    libraryIdentity(p),
    'shared knowledge',
    true,
  );
  assert.match(f.state.library!.notice!, /Promoted memory/);
  await assert.rejects(
    f.client.maintain('reembed', 'foreign', ''),
    /current scope/,
  );
  await f.client.maintain('reembed', libraryIdentity({ project: null }), '');
  assert.match(f.state.library!.notice!, /1 repaired; 1 failed/);
  let finish!: (reply: Outcome) => void;
  f.setReply(
    () =>
      new Promise((r) => {
        finish = r;
      }),
  );
  const changing = f.client.maintain(
    'reconsider',
    libraryIdentity({ project: null }),
    '',
  );
  await assert.rejects(
    f.client.maintain('reconsider', libraryIdentity({ project: null }), ''),
    /current memory change/,
  );
  await assert.rejects(f.client.open('memories', 'repo'), /Wait/);
  finish({ code: 'OK', payload: f.replies['proposal.reconsider'] });
  f.setReply((a) => ({ code: 'OK', payload: replyOf(f.replies, a.type) }));
  await changing;
});

await test('a foreign search question cannot replace the current findings', async () => {
  const f = fixture();
  await f.client.search('documents', 'known', 'lexical');
  f.setReply(() => ({
    code: 'OK',
    payload: { ...f.replies['document.search'], query: 'foreign' },
  }));
  await f.client.search('documents', 'new', 'lexical');
  assert.equal(f.state.library!.search.value!.query, 'known');
  assert.match(f.state.library!.search.error!, /different question/);
});

await test('a replacement semantic snapshot and foreign citations cannot replace displayed evidence', async () => {
  const f = fixture(),
    page = f.replies['conversation.search'];
  page.total = 2;
  page.hits = page.hits.slice(0, 1);
  page.retrieval = {
    requestedMode: 'semantic',
    effectiveMode: 'semantic',
    totalMeaning: 'candidate_count',
    snapshot: 'first',
    truncated: false,
    complete: true,
    fallback: null,
    coverage: null,
    generation: 'g1',
    queryEmbeddingCalls: 1,
    provenance: 'materialized',
  };
  await f.client.search('conversation', 'question', 'semantic');
  f.setReply(() => ({
    code: 'OK',
    payload: {
      ...page,
      offset: 1,
      hits: [{ ...page.hits[0], ordinal: 2 }],
      retrieval: { ...page.retrieval, snapshot: 'replacement' },
    },
  }));
  await f.client.search('conversation', 'question', 'semantic', true);
  assert.equal(list(f.state.library!.search.value!.reply, ['hits']).length, 1);
  assert.match(f.state.library!.search.error!, /snapshot/);
  f.setReply((a) => ({ code: 'OK', payload: replyOf(f.replies, a.type) }));
  await f.client.documents('');
  await f.client.document(
    present(f.replies['document.list'].documents[0]).documentId,
  );
  f.setReply(() => ({
    code: 'OK',
    payload: { scope: 'all', limit: 1, citations: [] },
  }));
  await f.client.citations();
  assert.equal(f.state.library!.citations.value, undefined);
  assert.match(f.state.library!.citations.error!, /different source/);
});

await test('memory invalidation ignores use counts changed by its own preflight read', async () => {
  const f = fixture();
  await f.client.open('memories');
  await f.client.memory('memory_fixture');
  const m = f.state.library!.memory.value!;
  f.setReply((a) => ({
    code: 'OK',
    payload:
      a.type === 'memory.read'
        ? { ...m, uses: m.uses + 1, lastUsed: '2026-10-02T11:00:00Z' }
        : replyOf(f.replies, a.type),
  }));
  await f.client.maintain('invalidate', memoryIdentity(m), 'obsolete');
  assert.equal(f.calls.filter((a) => a.type === 'memory.invalidate').length, 1);
  assert.match(f.state.library!.notice!, /confirmed/);
});

await test('unsettled successful mutation replies remain uncertain and are not replayed', async () => {
  const f = fixture();
  await f.client.open('memories');
  await f.client.memory('memory_fixture');
  f.setReply((a) => ({
    code: 'OK',
    payload:
      a.type === 'memory.invalidate'
        ? f.replies['memory.read']
        : replyOf(f.replies, a.type),
  }));
  await assert.rejects(
    f.client.maintain(
      'invalidate',
      memoryIdentity(f.state.library!.memory.value!),
      'obsolete',
    ),
    /not confirmed/,
  );
  assert.equal(f.calls.filter((a) => a.type === 'memory.invalidate').length, 1);
  assert.match(f.state.library!.error!, /not be replayed/);
  assert.equal(f.state.library!.notice, undefined);
});

await test('document reader pages saved text and rejects foreign or malformed text ranges', async () => {
  const f = fixture();
  await f.client.documents('');
  const id = present(f.replies['document.list'].documents[0]).documentId;
  const original = 'A'.repeat(8192) + 'Second page';
  f.setReply((ask) => {
    if (ask.type !== 'information.read')
      return { code: 'OK', payload: replyOf(f.replies, ask.type) };
    const offset = ask.payload.offset ?? 0;
    const value = original.slice(offset, offset + 8192);
    return {
      code: 'OK',
      payload: {
        revision: id,
        start: offset,
        end: offset + value.length,
        total: original.length,
        text: value,
      },
    };
  });
  await f.client.document(id);
  assert.equal(f.state.library!.reader!.value!.text.length, 8192);
  assert.deepEqual(field(f.calls.at(-1)!.payload, ['scope']), {
    kind: 'personal',
    includeShared: true,
  });
  await f.client.sourceText(id, 8192);
  assert.equal(f.state.library!.reader!.value!.text, 'Second page');
  const previous = structuredClone(f.state.library!.reader!.value);
  f.setReply(() => ({
    code: 'OK',
    payload: { ...previous, revision: 'bbbbbbbb-0000-0000-0000-000000000002' },
  }));
  await f.client.sourceText(id, 0);
  assert.deepEqual(f.state.library!.reader!.value, previous);
  assert.match(f.state.library!.reader!.error!, /Unreadable/);
  f.setReply(() => ({
    code: 'OK',
    payload: { ...previous, start: 0, end: 100 },
  }));
  await f.client.sourceText(id, 0);
  assert.deepEqual(f.state.library!.reader!.value, previous);
  assert.match(f.state.library!.reader!.error!, /Unreadable/);
  await assert.rejects(f.client.sourceText('foreign', 0), /displayed document/);
  await assert.rejects(f.client.sourceText(id, 500), /next or previous/);
});

await test('document reader discards delayed text after connection reset', async () => {
  const f = fixture();
  await f.client.documents('');
  const id = present(f.replies['document.list'].documents[0]).documentId;
  let finish!: (reply: Outcome) => void;
  f.setReply((ask) =>
    ask.type === 'information.read'
      ? new Promise((resolve) => {
          finish = resolve;
        })
      : { code: 'OK', payload: replyOf(f.replies, ask.type) },
  );
  const reading = f.client.document(id);
  await new Promise((resolve) => setImmediate(resolve));
  f.client.reset();
  finish({
    code: 'OK',
    payload: { revision: id, start: 0, end: 4, total: 4, text: 'Late' },
  });
  await reading;
  assert.equal(f.state.library!.reader!.value, undefined);
  assert.equal(f.state.library!.reader!.loading, false);
});

await test('retained report handoff keeps project scope and can reopen the same revision', async () => {
  const f = fixture(),
    id = '00000000-0000-0000-0000-000000000099';
  await f.client.open('sources', null, id);
  assert.equal(f.state.library!.report!.revision, id);
  const sequence = f.state.library!.report!.sequence;
  await f.client.open('sources', null, id);
  assert.ok(f.state.library!.report!.sequence > sequence);
  await assert.rejects(
    f.client.open('sources', null, 'foreign'),
    /retained report/,
  );
  assert.equal(f.state.library!.report!.revision, id);
  await f.client.open('sources');
  assert.equal(f.state.library!.report, undefined);
});
