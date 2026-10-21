#!/usr/bin/env node
/** Publish the versioned Markdown manual through ordinary authenticated information operations.
 * The local journal retains receipts before effects. Unknown delivery stops the run;
 * only an explicit --resume authorizes resending that same idempotent request. */
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, realpath, rename, rm } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';

const GLOBAL_ACCOUNT = '00000000-0000-0000-0000-000000000004';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const hash = value => createHash('sha256').update(value).digest('hex');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const inside = (root, path) => {
  const part = relative(root, path);
  return part !== '..' && !part.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`) && !isAbsolute(part);
};

/** Resolve inter-chapter links to stable chapter identifiers in the Library, which does not
 * serve repository-relative URLs. Keep external links, anchors and fenced examples intact. */
function readerLinks(text, source, titles) {
  let fence;
  return text.split('\n').map(line => {
    const marker = /^ {0,3}(`{3,}|~{3,})/.exec(line)?.[1];
    if (marker) {
      if (!fence) fence = marker;
      else if (marker[0] === fence[0] && marker.length >= fence.length) fence = undefined;
      return line;
    }
    if (fence) return line;
    return line.replace(/(!?)\[([^\]]+)\]\(([^)]+)\)/g, (match, image, label, target) => {
      if (/^(?:[a-z][a-z0-9+.-]*:|#)/i.test(target)) return match;
      const [path] = target.split('#');
      const chapter = titles.get(resolve(dirname(source), path));
      return chapter ? `[${label}](plowshare-manual:${chapter.id})` : `${label} (source repository reference)`;
    });
  }).join('\n');
}

/** Read only the explicit manifest's bounded Markdown files within this checkout.
 * A dry run needs no SDK, saved login, network request or database connection. */
export async function loadManual(root = ROOT) {
  root = await realpath(root);
  const manifest = JSON.parse(await readFile(join(root, 'docs/manual/manifest.json'), 'utf8'));
  if (!object(manifest) || manifest.formatVersion !== 1 || typeof manifest.edition !== 'string'
      || !/^[a-z0-9.-]{1,32}$/.test(manifest.edition) || !Array.isArray(manifest.chapters)
      || manifest.chapters.length < 1 || manifest.chapters.length > 64) {
    throw new Error('Invalid manual manifest. Expected version 1 and 1–64 chapters.');
  }
  const ids = new Set(), paths = new Set(), titles = new Map(), sources = [];
  for (const chapter of manifest.chapters) {
    if (!object(chapter) || typeof chapter.id !== 'string' || !/^[a-z0-9-]{1,48}$/.test(chapter.id)
        || typeof chapter.title !== 'string' || !chapter.title.trim() || chapter.title.length > 128
        || /[\r\n\0]/.test(chapter.title) || typeof chapter.path !== 'string'
        || isAbsolute(chapter.path) || !chapter.path.endsWith('.md') || ids.has(chapter.id)) {
      throw new Error('Invalid or duplicate manual chapter.');
    }
    const source = await realpath(resolve(root, chapter.path));
    if (!inside(root, source) || paths.has(source)) throw new Error('Manual files must be unique and inside the checkout.');
    const bytes = await readFile(source);
    if (bytes.length < 1 || bytes.length > 256 * 1024) throw new Error(`Chapter ${chapter.id} must be 1–256 KiB.`);
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (!/^#\s+\S/m.test(text)) throw new Error(`Chapter ${chapter.id} has no Markdown title.`);
    ids.add(chapter.id); paths.add(source); titles.set(source, chapter);
    sources.push({ ...chapter, source, text });
  }
  const chapters = sources.map(({ source, text, ...chapter }) => {
    const content = readerLinks(text, source, titles)
      + `\n\n---\nManual edition: ${manifest.edition}. Chapter: ${chapter.id}. Source: ${chapter.path}.\n`;
    if (Buffer.byteLength(content, 'utf8') > 256 * 1024) throw new Error(`Installed chapter ${chapter.id} exceeds 256 KiB.`);
    return { ...chapter, text: content, hash: hash(content),
      name: `Plowshare manual / ${chapter.id}.md` };
  });
  return { edition: manifest.edition, chapters };
}

/** The journal deliberately excludes passwords, tokens and chapter contents. */
export function newJournal(server, account, destination = 'personal') {
  return { version: 1, server, account, destination, operations: {}, chapters: {} };
}

function validateJournal(journal, server, account, destination = 'personal') {
  if (!['personal', GLOBAL_ACCOUNT].includes(destination)) throw new Error('Unsupported manual destination.');
  if (!object(journal) || (journal.destination ?? 'personal') !== destination || journal.version !== 1 || journal.server !== server || journal.account !== account
      || !object(journal.operations) || !object(journal.chapters)) {
    throw new Error('Manual journal is invalid or belongs to another server/account.');
  }
  if (journal.review && (!object(journal.review) || !UUID.test(journal.review.id)
      || !/^[a-f0-9]{64}$/.test(journal.review.fingerprint) || !['pending', 'done'].includes(journal.review.state)))
    throw new Error('Manual journal contains an invalid publication review.');
  for (const row of Object.values(journal.operations)) {
    if (!object(row) || !UUID.test(row.requestId) || !['pending', 'done'].includes(row.state)
        || typeof row.fingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(row.fingerprint)) {
      throw new Error('Manual journal contains an invalid operation receipt.');
    }
  }
  for (const row of Object.values(journal.chapters)) {
    if (!object(row) || !UUID.test(row.revision) || typeof row.hash !== 'string' || !/^[a-f0-9]{64}$/.test(row.hash)) {
      throw new Error('Manual journal contains an invalid chapter receipt.');
    }
  }
}

const admission = value => {
  if (!object(value) || !UUID.test(value.revision)) throw new Error('Upload returned no valid revision. Delivery is uncertain.');
  return { revision: value.revision };
};
const changed = value => {
  if (!object(value) || value.changed !== true) throw new Error('Mutation returned no confirmed change. Delivery is uncertain.');
  return { changed: true };
};
const excluded = value => {
  if (!object(value) || value.availability !== 'excluded') throw new Error('Exclusion was not confirmed. Delivery is uncertain.');
  return { availability: 'excluded' };
};

/** client/sharedClient implement the existing InformationClient.call contract.
 * persist must durably save the journal before returning. No mutation is retried
 * within this invocation, and a saved pending receipt needs explicit resume. */
export async function publishManual(plan, client, {
  journal, persist, sharedClient, share = false, republish = false, resume = false, signal, destination = 'personal',
  readinessMs = 300_000, pollMs = 1000, now = Date.now,
  sleep = ms => delay(ms, undefined, { signal }), progress = () => {},
}) {
  validateJournal(journal, journal.server, journal.account, destination);
  const generallyAvailable = share || destination === GLOBAL_ACCOUNT;
  if (generallyAvailable && !sharedClient) {
    throw new Error('Global/shared publication needs a global_read reader for verification.');
  }
  // A pending share can only be recovered with explicit sharing still selected.
  // Otherwise this run could report success while leaving that effect unresolved.
  if (republish && !generallyAvailable) throw new Error('--republish requires --share or --global.');
  const steps = ['upload', 'chapter-tags', 'exclude', ...(share && !republish ? ['share'] : [])];
  // A review is one durable batch. Resume never allocates a new sharing request
  // for a chapter already confirmed earlier in the interrupted batch.
  const reviewedShares = new Map();
  let newReview;
  if (journal.review?.state === 'pending' && !republish)
    throw new Error('A publication review is incomplete; inspect the journal and use --republish --resume.');
  if (republish) {
    const fingerprint = hash(JSON.stringify(plan.chapters.map(chapter => [chapter.id, chapter.hash])));
    let review = journal.review;
    if (review?.state === 'pending') {
      if (!resume || review.fingerprint !== fingerprint)
        throw new Error('A publication review has uncertain delivery; use --republish --resume with the same manual contents.');
    } else {
      review = { id: randomUUID(), fingerprint, state: 'pending' };
      newReview = review;
    }
    for (const chapter of plan.chapters)
      reviewedShares.set(chapter.id, `${chapter.id}:${chapter.hash}:reviewed-share:${review.id}`);
  }
  const active = new Set(plan.chapters.flatMap(chapter =>
    steps.map(step => `${chapter.id}:${chapter.hash}:${step}`)));
  for (const key of reviewedShares.values()) active.add(key);
  for (const [key, row] of Object.entries(journal.operations)) {
    if (row.state === 'pending' && (!resume || !active.has(key))) {
      throw new Error('A manual operation has uncertain delivery. Inspect the journal; use --resume with the same manual contents.');
    }
  }

  if (newReview) {
    journal.review = newReview;
    await persist(journal);
  }

  async function mutate(key, operation, payload, validate) {
    signal?.throwIfAborted();
    const fingerprint = hash(JSON.stringify(destination === 'personal' ? [operation, payload] : [destination, operation, payload]));
    let row = journal.operations[key];
    if (row && row.fingerprint !== fingerprint) throw new Error('Receipt payload changed; restore the exact manual edition before resuming.');
    if (row?.state === 'done') return validate(row.result);
    if (!row) {
      row = { requestId: randomUUID(), fingerprint, state: 'pending' };
      journal.operations[key] = row;
      await persist(journal);
    }
    const result = validate(await client.call(operation, { ...payload, requestId: row.requestId }));
    row.result = result; row.state = 'done';
    await persist(journal);
    return result;
  }

  for (const chapter of plan.chapters) {
    signal?.throwIfAborted();
    const key = `${chapter.id}:${chapter.hash}`;
    const previous = journal.chapters[chapter.id];
    const { revision } = await mutate(`${key}:upload`, 'upload', { name: chapter.name, text: chapter.text }, admission);
    await mutate(`${key}:chapter-tags`, 'tags', {
      revision, tags: ['plowshare-manual', `manual-edition-${plan.edition}`, `manual-chapter-${chapter.id}`],
    }, changed);
    const deadline = now() + readinessMs;
    for (;;) {
      signal?.throwIfAborted();
      const status = await client.call('status', { revision });
      if (!object(status) || status.id !== revision || !Number.isSafeInteger(status.generation)
          || !Array.isArray(status.steps) || status.steps.some(row => !object(row))) {
        throw new Error(`Invalid processing status for chapter ${chapter.id}; revision ${revision}.`);
      }
      if (status.availability !== 'active' || status.excluded === true) {
        throw new Error(`Chapter ${chapter.id} is unavailable/excluded; inspect revision ${revision}.`);
      }
      if (!Array.isArray(status.tags) || !status.tags.includes('plowshare-manual') || !status.tags.includes(`manual-chapter-${chapter.id}`)) {
        throw new Error(`Chapter ${chapter.id} is missing its manual tag; inspect revision ${revision}.`);
      }
      const required = (destination === 'personal' ? ['extract', 'derive'] : ['extract', 'derive', 'embed', 'summarise', 'summary_embed']).map(stage => status.steps.find(row => row.stage === stage && row.generation === status.generation));
      if (required.some(row => row && ['failed', 'blocked', 'cancelled', 'skipped'].includes(row.state))) {
        throw new Error(`Chapter ${chapter.id} could not become readable; inspect revision ${revision}.`);
      }
      if (required.every(row => row?.state === 'ready')) break;
      if (now() >= deadline) throw new Error(`Chapter ${chapter.id} is still processing; inspect revision ${revision}, then rerun the installer.`);
      await sleep(Math.min(pollMs, Math.max(1, deadline - now())));
    }
    // Check the real reader rather than treating an admission/processing receipt as text.
    const window = await client.call('read', { revision, offset: 0, limit: 1 });
    if (!object(window) || window.revision !== revision || typeof window.text !== 'string' || !window.text) throw new Error(`Chapter ${chapter.id} has no readable text.`);
    if (republish) {
      await mutate(reviewedShares.get(chapter.id), 'share', { revision }, changed);
    } else if (share) {
      await mutate(`${key}:share`, 'share', { revision }, changed);
    }
    async function verifyDiscovery(allowPrevious) {
      const found = await sharedClient.call('list', {
        kind: 'source', filter: { tags: ['plowshare-manual', `manual-chapter-${chapter.id}`] }, offset: 0, limit: 2,
      });
      const allowed = new Set([revision, ...(allowPrevious && previous ? [previous.revision] : [])]);
      if (!Array.isArray(found) || found.length < 1 || !found.some(row => row.id === revision)
          || found.some(row => !allowed.has(row.id) || row.source_name !== chapter.name)
          || (!allowPrevious && found.length !== 1))
        throw new Error('Help must resolve exactly one current chapter from its name and tags.');
    }
    // Custody and old receipts are not publication evidence. Before replacing an
    // edition, admit only its known predecessor as a temporary second lookup match.
    try {
      if (generallyAvailable) {
        const shared = await sharedClient.call('read', { revision, offset: 0, limit: 1 });
        if (!object(shared) || shared.revision !== revision || typeof shared.text !== 'string' || !shared.text)
          throw new Error('Retained chapter text is unavailable.');
        await verifyDiscovery(true);
      }
      // Preserve the old evidence; remove it from discovery only once the new
      // revision is readable. Exclusion changes discovery, requiring a fresh lookup.
      if (previous && previous.revision !== revision) {
        await mutate(`${key}:exclude`, 'exclude', { revision: previous.revision }, excluded);
        if (generallyAvailable) await verifyDiscovery(false);
      }
    } catch (cause) {
      if (!generallyAvailable) throw cause;
      throw new Error(`Chapter ${chapter.id} could not be verified in global_read. Historical sharing receipts do not prove current publication. Review its audience and reference policy; use --republish with --share or --global to explicitly publish this revision again.`, { cause });
    }
    journal.chapters[chapter.id] = { hash: chapter.hash, revision };
    await persist(journal);
    progress({ id: chapter.id, title: chapter.title, revision, shared: generallyAvailable });
  }
  if (republish) { journal.review.state = 'done'; await persist(journal); }
  return plan.chapters.map(chapter => ({ id: chapter.id, ...journal.chapters[chapter.id] }));
}

const HELP = `Install the Markdown Plowshare manual into the existing information Library.
Usage: node scripts/install-manual.mjs [--dry-run] [--url ORIGIN] [--share] [--global] [--republish] [--resume]
       [--state /absolute/private/journal.json] [--readiness-ms N] [--timeout-ms N]

--dry-run       validate and list chapters offline, without authentication or writes
--global        install into Global and verify global_read access (human scoped ADMIN required)
--share         explicitly publish chapters to authenticated server accounts
--republish     explicitly reapply publication after ACL/audience review; requires --share or --global
--resume        resend an uncertain idempotent request with its saved UUID
--state         private receipt journal outside the checkout (default: config/manual/)
--readiness-ms  per-chapter extraction/derivation deadline (default 300000)
--timeout-ms    each WS request deadline (default 30000)

Sign in with bin/plowshare-cli login first. Live installation needs
./gradlew :plowshare-client-node:nodeBuild. Normal server document processing may
use configured models and allowances. No SQL, new HTTP route or agent run is used.`;

export function options(args, env = process.env) {
  const opts = { url: env.PLOWSHARE_URL, share: false,
    resume: false, republish: false, dryRun: false, readinessMs: 300_000, timeoutMs: 30_000 };
  for (let i = 0; i < args.length; i++) {
    const flag = args[i];
    if (flag === '--help' || flag === '-h') opts.help = true;
    else if (flag === '--share') opts.share = true;
    else if (flag === '--global') opts.global = true;
    else if (flag === '--resume') opts.resume = true;
    else if (flag === '--republish') opts.republish = true;
    else if (flag === '--dry-run') opts.dryRun = true;
    else if (['--url', '--state', '--readiness-ms', '--timeout-ms'].includes(flag)) {
      const value = args[++i];
      if (!value || value.startsWith('--')) throw new Error(`${flag} needs a value.`);
      if (flag === '--url') opts.url = value;
      else if (flag === '--state') opts.state = value;
      else {
        const n = Number(value);
        if (!/^\d+$/.test(value) || !Number.isSafeInteger(n) || n < 1 || n > 3_600_000) throw new Error(`${flag} needs 1–3600000 milliseconds.`);
        opts[flag === '--readiness-ms' ? 'readinessMs' : 'timeoutMs'] = n;
      }
    } else throw new Error(`Unknown option: ${flag}`);
  }
  if (opts.republish && !opts.share && !opts.global) throw new Error('--republish requires --share or --global.');
  if (opts.state && !isAbsolute(opts.state)) throw new Error('--state must be an absolute private path.');
  // Offline preview does not need a server. Live work never guesses a local origin.
  if (!opts.url) {
    if (!opts.help && !opts.dryRun) throw new Error('Set --url or PLOWSHARE_URL to the server origin before installing.');
  } else {
    const url = new URL(opts.url);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
        || url.pathname !== '/' || url.search || url.hash) throw new Error('Use an HTTP(S) server origin without credentials or a path.');
    opts.url = url.origin;
  }
  return opts;
}

/** Atomic, private journal writes; pending state survives losing the process/reply. */
async function saveJournal(path, journal) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  try {
    const file = await open(temporary, 'wx', 0o600);
    try { await file.writeFile(JSON.stringify(journal, null, 2) + '\n'); await file.sync(); }
    finally { await file.close(); }
    await rename(temporary, path);
  } finally { await rm(temporary, { force: true }); }
}

export async function main(args = process.argv.slice(2), env = process.env) {
  const opts = options(args, env);
  if (opts.help) { process.stdout.write(HELP + '\n'); return; }
  const plan = await loadManual();
  if (opts.dryRun) {
    for (const chapter of plan.chapters) process.stdout.write(`${chapter.id}\t${chapter.title}\t${Buffer.byteLength(chapter.text)} bytes\n`);
    process.stdout.write(`${plan.chapters.length} chapters validated; no requests or local journal writes.\n`);
    return;
  }
  let authenticateConfigured, credentialDirectory, InformationClient;
  try {
    ({ authenticateConfigured } = await import(pathToFileURL(join(ROOT, 'sdk/node/build/session.js'))));
    ({ credentialDirectory } = await import(pathToFileURL(join(ROOT, 'sdk/node/build/credentials.js'))));
    ({ InformationClient } = await import(pathToFileURL(join(ROOT, 'sdk/typescript/build/operations/information.js'))));
  } catch { throw new Error('Build the SDK first: ./gradlew :plowshare-client-node:nodeBuild'); }
  const abort = new AbortController();
  const interrupt = () => abort.abort(new Error('Manual installation interrupted; inspect saved receipts before resuming.'));
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  let session, lock;
  try {
    const authTimeout = setTimeout(() => abort.abort(new Error('Manual authentication timed out.')), opts.timeoutMs);
    try { session = await authenticateConfigured(opts.url, env, abort.signal, text => process.stderr.write(text + '\n')); }
    finally { clearTimeout(authTimeout); }
    const destination = opts.global ? GLOBAL_ACCOUNT : 'personal';
    const scope = opts.global ? { kind: 'account', account: GLOBAL_ACCOUNT } : { kind: 'personal', includeGlobal: true };
    const filename = hash(JSON.stringify(opts.global ? [opts.url, session.handle, destination] : [opts.url, session.handle])) + '.json';
    const state = opts.state ?? join(dirname(credentialDirectory(env)), 'manual', filename);
    if (inside(ROOT, resolve(state))) throw new Error('Keep the private manual journal outside the source checkout.');
    await mkdir(dirname(state), { recursive: true, mode: 0o700 });
    if (inside(await realpath(ROOT), await realpath(dirname(state)))) throw new Error('The journal directory resolves inside the checkout.');
    const wantedLock = `${state}.lock`;
    try { await mkdir(wantedLock, { mode: 0o700 }); lock = wantedLock; }
    catch (error) {
      if (error.code === 'EEXIST') throw new Error('Manual journal is busy or has an interrupted lock. Confirm no installer is running before removing its .lock directory.');
      throw error;
    }
    let journal;
    try { journal = JSON.parse(await readFile(state, 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw new Error('Manual journal is unreadable; preserve it and inspect before resuming.'); }
    journal ??= newJournal(opts.url, session.handle, destination);
    validateJournal(journal, opts.url, session.handle, destination);
    // A request deadline closes the session; it never falls back to HTTP or resends.
    const transport = { ask: async (type, payload) => {
      const timeout = setTimeout(() => abort.abort(new Error('Manual request timed out; delivery may be uncertain.')), opts.timeoutMs);
      try { return await session.ask(type, payload); } finally { clearTimeout(timeout); }
    } };
    await publishManual(plan, new InformationClient(transport, scope), {
      journal, destination, persist: value => saveJournal(state, value),
      sharedClient: new InformationClient(transport, { kind: 'global_read' }),
      share: opts.share, republish: opts.republish, resume: opts.resume, readinessMs: opts.readinessMs, signal: abort.signal,
      progress: row => process.stdout.write(`${row.id}\t${row.revision}\t${row.shared ? 'shared' : 'readable (sharing not requested)'}\n`),
    });
    process.stdout.write(opts.global || opts.share
      ? `Installed ${plan.chapters.length} generally available chapters. Open Help to read the manual.\n`
      : `Installed ${plan.chapters.length} Personal chapters. Find them in Library document sources; Help requires a Global/shared installation.\n`);
  } finally {
    session?.close();
    if (lock) await rm(lock, { recursive: true, force: true });
    process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { process.stderr.write(`Manual installation stopped: ${error.message}\n`); process.exitCode = 1; });
}
