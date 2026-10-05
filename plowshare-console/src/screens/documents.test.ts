import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { JobView, StartedJob } from '../repl/wire';
import {
  createDocuments,
  INGEST,
  NO_PROGRESS,
  RESTART_NOTE,
  SIMILARITY_NOTE,
  WHAT_TO_CITE,
} from './documents';
import type { Screen, Transport } from './screen';
import type { DocumentHit, DocumentSearchResponse } from './wire';

let server: {
  jobs: JobView[];
  found: DocumentSearchResponse | null;
  refuse: string | null;
};
let asked: FormData[];
let root: HTMLElement;
let screen: Screen;
let get: ReturnType<typeof vi.fn>;
let post: ReturnType<typeof vi.fn>;

function job(over: Partial<JobView>): JobView {
  return {
    id: 'job_000001',
    agent: INGEST,
    state: 'RUNNING',
    cancelRequested: false,
    conversation: null,
    outcome: null,
    limits: null,
    ...over,
  };
}

function hit(over: Partial<DocumentHit>): DocumentHit {
  return {
    chunkId: 'aaaaaaaa-0000-0000-0000-000000000001',
    text: 'a chunk of the paper, which is what matched',
    similarity: 0.8123,
    paragraphId: 'bbbbbbbb-0000-0000-0000-000000000002',
    paragraphText:
      'the paragraph the chunk came out of, which is the thing to cite',
    paragraphOrdinal: 17,
    documentId: 'cccccccc-0000-0000-0000-000000000003',
    sourceName: 'anchor.txt',
    title: 'On the architecture',
    ...over,
  };
}

function found(over: Partial<DocumentSearchResponse>): DocumentSearchResponse {
  return {
    query: 'what does it argue',
    limit: 10,
    mode: 'hybrid',
    hits: [hit({})],
    searchable: 4200,
    unsearchable: 0,
    ...over,
  };
}

function transport(): Transport {
  get = vi.fn(async (path: string): Promise<unknown> => {
    if (path === '/v1/jobs') {
      return server.jobs;
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  post = vi.fn(async (path: string): Promise<unknown> => {
    if (path === '/v1/documents/search') {
      if (server.refuse !== null) {
        throw new ApiError(server.refuse, 400);
      }
      return server.found;
    }
    if (/^\/v1\/jobs\/[^/]+\/cancel$/.test(path)) {
      server.jobs = server.jobs.map((one) => ({
        ...one,
        cancelRequested: true,
      }));
      return server.jobs[0];
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  return { get, post } as unknown as Transport;
}

async function sendUp(form: FormData): Promise<StartedJob> {
  asked.push(form);
  server.jobs = [...server.jobs, job({ id: 'job_000002' })];
  return { id: 'job_000002', agent: INGEST };
}

function textUnder(selector: string, within: ParentNode = root): string {
  return within.querySelector(selector)?.textContent ?? '';
}

/** Every ingest drawn, by job id, in the order it was drawn. */
function ingests(): string[] {
  return [...root.querySelectorAll('[data-ingest]')].map(
    (node) => node.getAttribute('data-ingest') ?? '',
  );
}

/** Put a file on the control the way a browser would, which jsdom will not do. */
function choose(name: string): void {
  const control = root.querySelector('[data-file]') as HTMLInputElement;
  Object.defineProperty(control, 'files', {
    configurable: true,
    value: [new File(['the body of a paper'], name, { type: 'text/plain' })],
  });
  control.dispatchEvent(new Event('change'));
}

async function search(question: string): Promise<void> {
  const box = root.querySelector('[data-input="question"]') as HTMLInputElement;
  box.value = question;
  (root.querySelector('.ask') as HTMLButtonElement).click();
  await vi.waitFor(() => expect(post).toHaveBeenCalled());
}

beforeEach(() => {
  server = { jobs: [], found: found({}), refuse: null };
  asked = [];
  root = document.createElement('main');
  document.body.replaceChildren(root);
  screen = createDocuments({
    root,
    transport: transport(),
    send: sendUp,
    pollMs: null,
  });
});

describe('an ingest is a long job, and the screen says what running is worth', () => {
  it('sends the file as multipart, with the name a person chose', async () => {
    await screen.load();
    choose('anchor.txt');
    const naming = root.querySelector(
      '[data-input="name"]',
    ) as HTMLInputElement;
    naming.value = 'the anchor paper';
    (root.querySelector('.ingest') as HTMLButtonElement).click();

    await vi.waitFor(() => expect(asked).toHaveLength(1));
    const form = asked[0] as FormData;
    expect((form.get('file') as File).name).toBe('anchor.txt');
    expect(form.get('name')).toBe('the anchor paper');
  });

  it('leaves the name out when nobody chose one, rather than sending a blank', async () => {
    // The server refuses a blank `name` with a sentence about why: a blank
    // is a caller who meant to name the document, and falling back to the
    // filename would file it somewhere they did not choose. An omitted
    // field is what asks for the filename, so this omits it.
    await screen.load();
    choose('anchor.txt');
    (root.querySelector('.ingest') as HTMLButtonElement).click();

    await vi.waitFor(() => expect(asked).toHaveLength(1));
    expect((asked[0] as FormData).has('name')).toBe(false);
  });

  it('refuses to send with no file, and says so without asking the server', async () => {
    await screen.load();
    (root.querySelector('.ingest') as HTMLButtonElement).click();

    expect(textUnder('[data-trouble]')).toContain('file');
    expect(asked).toHaveLength(0);
  });

  it('draws the started ingest as running, from the listing and not from the answer', async () => {
    await screen.load();
    choose('anchor.txt');
    (root.querySelector('.ingest') as HTMLButtonElement).click();

    await vi.waitFor(() => expect(ingests()).toEqual(['job_000002']));
    expect(
      get.mock.calls.filter((call) => call[0] === '/v1/jobs').length,
    ).toBeGreaterThan(1);
  });

  it('states that there is no fraction, and why there could not be one', async () => {
    await screen.load();

    expect(textUnder('[data-no-progress]')).toBe(NO_PROGRESS);
    // The door DocumentController submits through takes no session and puts
    // no limits on the handle, so nothing is published and nothing counts.
    expect(NO_PROGRESS).toContain('no session');
    expect(NO_PROGRESS).toContain('limits');
    expect(NO_PROGRESS).toContain('26 minutes');
  });

  it('says the handle does not survive a restart, and does not pretend otherwise', async () => {
    await screen.load();

    expect(textUnder('[data-restart-note]')).toBe(RESTART_NOTE);
    expect(RESTART_NOTE).toContain('job_000001');
  });

  it('shows only ingests, and says how many runs of other kinds it left out', async () => {
    server.jobs = [job({}), job({ id: 'job_000003', agent: 'code_reviewer' })];
    await screen.load();

    expect(ingests()).toEqual(['job_000001']);
    expect(textUnder('[data-window]')).toContain('code_reviewer');
  });

  it('draws the outcome when one arrives, and never invents one before', async () => {
    server.jobs = [job({})];
    await screen.load();
    expect(textUnder('[data-running]')).toContain('as of the last time');

    server.jobs = [
      job({
        state: 'DONE',
        outcome: {
          ending: 'ANSWERED',
          answered: true,
          resumable: false,
          text: "stored 'anchor.txt': 214 paragraphs, 380 chunks.",
          steps: 221,
          modelCalls: 221,
          detail: '',
        },
      }),
    ];
    (root.querySelector('.reload') as HTMLButtonElement).click();

    await vi.waitFor(() =>
      expect(textUnder('[data-outcome]')).toContain('214 paragraphs'),
    );
    expect(textUnder('[data-outcome]')).toContain('221');
  });

  it('says what a cancel costs, because it is not a rollback', async () => {
    server.jobs = [job({})];
    await screen.load();
    const stop = root.querySelector('[data-cancel]') as HTMLButtonElement;

    // On the control, because it is what the person is about to do: a
    // cancel keeps everything already written and stops the rest.
    expect(stop.title).toContain('already written stays');
    expect(stop.title).toContain('Not a rollback');
    stop.click();
    await vi.waitFor(() =>
      expect(post).toHaveBeenCalledWith('/v1/jobs/job_000001/cancel'),
    );
  });
});

describe('search: what matched, and what to cite', () => {
  it('asks with the question, the limit and the mode, in a body', async () => {
    await screen.load();
    await search('what does it argue');

    expect(post).toHaveBeenCalledWith(
      '/v1/documents/search',
      expect.objectContaining({ query: 'what does it argue', mode: 'hybrid' }),
    );
  });

  it('refuses a blank question the way the server would, and does not ask', async () => {
    await screen.load();
    (root.querySelector('.ask') as HTMLButtonElement).click();

    expect(post).not.toHaveBeenCalled();
    expect(textUnder('[data-trouble]')).toContain('question');
  });

  it('marks the paragraph as the citation and the chunk as not one', async () => {
    await screen.load();
    await search('what does it argue');

    await vi.waitFor(() =>
      expect(root.querySelector('[data-hit]')).not.toBeNull(),
    );
    const one = root.querySelector('[data-hit]') as HTMLElement;
    expect(one.getAttribute('data-cite')).toBe(
      'bbbbbbbb-0000-0000-0000-000000000002',
    );
    expect(textUnder('[data-citation-note]')).toBe(WHAT_TO_CITE);
    expect(WHAT_TO_CITE).toContain('chunk');
    expect(WHAT_TO_CITE).toContain('re-ingest');
  });

  it('says the ordinal is a position and not an identity', async () => {
    await screen.load();
    await search('what does it argue');

    await vi.waitFor(() => expect(textUnder('[data-ordinal]')).toContain('17'));
    expect(textUnder('[data-ordinal]')).toContain('today');
  });

  it('reports the mode that was used and the limit that was used', async () => {
    server.found = found({ mode: 'lexical', limit: 10 });
    await screen.load();
    await search('what does it argue');

    await vi.waitFor(() =>
      expect(textUnder('[data-mode]')).toContain('lexical'),
    );
    expect(textUnder('[data-mode]')).toContain('10');
  });

  it('says similarity is a fact about a hit and not the order under hybrid', async () => {
    await screen.load();
    await search('what does it argue');

    await vi.waitFor(() =>
      expect(root.querySelector('[data-hit]')).not.toBeNull(),
    );
    expect(textUnder('[data-similarity-note]')).toBe(SIMILARITY_NOTE);
    expect(SIMILARITY_NOTE).toContain('fused');
    expect(SIMILARITY_NOTE).not.toContain('probability of');
  });

  it('tells an empty corpus from an empty answer, using the two counts', async () => {
    server.found = found({ hits: [], searchable: 0, unsearchable: 0 });
    await screen.load();
    await search('what does it argue');

    // Scoped to the answer: the ingest half draws its own empty statement,
    // and the two are different facts about two different things.
    await vi.waitFor(() =>
      expect(root.querySelector('[data-answer] [data-empty]')).not.toBeNull(),
    );
    expect(textUnder('[data-answer] [data-empty]')).toContain(
      'nothing in it a question can reach',
    );
  });

  it('says how much of the corpus the question could not reach', async () => {
    server.found = found({ hits: [], searchable: 100, unsearchable: 40 });
    await screen.load();
    await search('what does it argue');

    await vi.waitFor(() => expect(textUnder('[data-reach]')).toContain('40'));
    expect(textUnder('[data-reach]')).toContain('no vector');
  });

  it('draws the server’s refusal rather than an empty result', async () => {
    server.refuse = '/v1/documents/search answered 400';
    await screen.load();
    await search('what does it argue');

    await vi.waitFor(() =>
      expect(textUnder('[data-trouble]')).toContain('400'),
    );
    expect(root.querySelector('[data-hit]')).toBeNull();
  });
});
