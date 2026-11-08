import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  FileBrowserSession,
  type FileBrowserDocument,
  type FileBrowserListing,
  type FileBrowserPort,
} from './file-browser.ts';

function listing(
  path: string,
  entries: FileBrowserListing['entries'],
  more = false,
): FileBrowserListing {
  return { path, entries, more };
}

function document(
  path: string,
  text: string,
  writable = true,
  revision = `rev:${text}`,
): FileBrowserDocument {
  return { path, text, revision, writable };
}

function memoryPort(
  root: Map<string, { text: string; writable?: boolean } | 'dir'>,
): FileBrowserPort & { lists: string[]; reads: string[]; saves: string[] } {
  const lists: string[] = [];
  const reads: string[] = [];
  const saves: string[] = [];
  return {
    lists,
    reads,
    saves,
    async list(path) {
      lists.push(path);
      const prefix = path ? `${path}/` : '';
      const names = new Map<
        string,
        { name: string; path: string; directory: boolean }
      >();
      for (const key of root.keys()) {
        if (path === key && root.get(key) === 'dir') continue;
        if (!key.startsWith(prefix) && key !== path) continue;
        if (key === path) continue;
        const rest = key.startsWith(prefix) ? key.slice(prefix.length) : key;
        if (!rest || (path && !key.startsWith(prefix))) continue;
        const slash = rest.indexOf('/');
        if (slash < 0) {
          names.set(rest, {
            name: rest,
            path: prefix + rest,
            directory: root.get(prefix + rest) === 'dir',
          });
        } else {
          const name = rest.slice(0, slash);
          if (!names.has(name))
            names.set(name, {
              name,
              path: prefix + name,
              directory: true,
            });
        }
      }
      return listing(path, [...names.values()]);
    },
    async read(path) {
      reads.push(path);
      const row = root.get(path);
      if (!row || row === 'dir') throw new Error(`Missing file ${path}`);
      return document(path, row.text, row.writable !== false);
    },
    async save(path, text, revision) {
      saves.push(path);
      const row = root.get(path);
      if (!row || row === 'dir') throw new Error(`Missing file ${path}`);
      if (revision !== `rev:${row.text}`)
        throw new Error('The file changed or write access is unavailable');
      row.text = text;
      return document(path, text, row.writable !== false);
    },
  };
}

await test('expand caches directory listings per session', async () => {
  const port = memoryPort(
    new Map([
      ['agents', 'dir'],
      ['agents/bot.json', { text: '{}' }],
      ['notes.md', { text: 'hello' }],
    ]),
  );
  const session = new FileBrowserSession(port);
  await session.expand('');
  assert.equal(port.lists.length, 1);
  assert.deepEqual(
    session.listing('')?.entries.map((row) => row.name).sort(),
    ['agents', 'notes.md'],
  );
  await session.expand('agents');
  assert.equal(session.listing('agents')?.entries[0]?.path, 'agents/bot.json');
  await session.expand('');
  assert.equal(
    port.lists.length,
    3,
    're-expand lists again instead of trusting a stale cache forever',
  );
});

await test('open loads a document and tracks drafts independently of another session', async () => {
  const shared = new Map<string, { text: string; writable?: boolean } | 'dir'>([
    ['notes.md', { text: 'shared' }],
  ]);
  const leftPort = memoryPort(shared);
  const rightPort = memoryPort(
    new Map([['other.md', { text: 'right only', writable: false }]]),
  );
  const left = new FileBrowserSession(leftPort);
  const right = new FileBrowserSession(rightPort);
  await left.open('notes.md');
  await right.open('other.md');
  assert.equal(left.document?.text, 'shared');
  assert.equal(right.document?.text, 'right only');
  assert.equal(right.document?.writable, false);
  left.setDraft('changed on left');
  assert.equal(left.draftText, 'changed on left');
  assert.equal(right.draftText, 'right only');
  assert.equal(right.dirty, false);
  assert.equal(left.dirty, true);
});

await test('save writes through the port and clears dirty when revision matches', async () => {
  const port = memoryPort(new Map([['notes.md', { text: 'before' }]]));
  const session = new FileBrowserSession(port);
  await session.open('notes.md');
  session.setDraft('after');
  await session.save();
  assert.deepEqual(port.saves, ['notes.md']);
  assert.equal(session.document?.text, 'after');
  assert.equal(session.dirty, false);
  assert.equal(session.document?.revision, 'rev:after');
});

await test('collapse forgets a directory without clearing sibling state', async () => {
  const session = new FileBrowserSession(
    memoryPort(
      new Map([
        ['a', 'dir'],
        ['b', 'dir'],
        ['a/one.md', { text: '1' }],
        ['b/two.md', { text: '2' }],
      ]),
    ),
  );
  await session.expand('');
  await session.expand('a');
  await session.expand('b');
  session.collapse('a');
  assert.equal(session.listing('a'), undefined);
  assert.ok(session.listing('b'));
  assert.ok(session.expanded.has(''));
  assert.ok(!session.expanded.has('a'));
});

await test('reset clears listings, expansion, and drafts for a new root', async () => {
  const session = new FileBrowserSession(
    memoryPort(new Map([['notes.md', { text: 'x' }]])),
  );
  await session.expand('');
  await session.open('notes.md');
  session.setDraft('draft');
  session.reset();
  assert.equal(session.listing(''), undefined);
  assert.equal(session.document, undefined);
  assert.equal(session.draftText, undefined);
  assert.equal(session.expanded.size, 0);
});

await test('scoped drafts survive a root switch and restore when the scope returns', async () => {
  const port = memoryPort(new Map([['notes.md', { text: 'base' }]]));
  const session = new FileBrowserSession(port);
  session.setScope('source');
  await session.open('notes.md');
  session.setDraft('source draft');
  session.setScope('outputs');
  assert.equal(session.document, undefined);
  await session.open('notes.md');
  assert.equal(session.draftText, 'base');
  session.setDraft('output draft');
  session.setScope('source');
  await session.open('notes.md');
  assert.equal(session.draftText, 'source draft');
});
