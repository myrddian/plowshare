import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { createConfig } from './config';
import type { Screen, Transport } from './screen';
import type { Setting } from './wire';

let server: { settings: Setting[] };
let root: HTMLElement;
let screen: Screen;
let get: ReturnType<typeof vi.fn>;
let put: ReturnType<typeof vi.fn>;

function setting(over: Partial<Setting>): Setting {
  return {
    key: 'plowshare.documents.ingest-budget',
    value: '40',
    updatedAt: null,
    updatedBy: null,
    pinned: false,
    ...over,
  };
}

function transport(): Transport {
  get = vi.fn(async (path: string): Promise<unknown> => {
    if (path === '/v1/config') {
      return server.settings;
    }
    throw new ApiError(`${path} answered 404`, 404);
  });
  return { get, post: vi.fn(), put: vi.fn() };
}

/** The one call this screen makes that is not JSON: a raw string body. */
function writer(): (path: string, value: string) => Promise<Setting> {
  put = vi.fn(async (path: string, value: string): Promise<Setting> => {
    const key = path.replace('/v1/config/', '');
    const written = setting({
      key,
      value,
      updatedAt: '2026-09-07T11:00:00Z',
      updatedBy: 'operator',
    });
    server.settings = server.settings.map((one) =>
      one.key === key ? written : one,
    );
    return written;
  });
  return put;
}

function row(key: string): HTMLElement {
  return root.querySelector(`[data-key="${key}"]`) as HTMLElement;
}

beforeEach(() => {
  server = {
    settings: [
      setting({ updatedAt: '2026-09-07T10:00:00Z', updatedBy: 'operator' }),
    ],
  };
  root = document.createElement('main');
  document.body.replaceChildren(root);
  screen = createConfig({ root, transport: transport(), write: writer() });
});

describe('the live configuration, and the two things it has to admit', () => {
  it('lists every live key with who last set it', async () => {
    server.settings = [
      setting({
        updatedAt: '2026-09-07T10:00:00Z',
        updatedBy: 'operator',
        pinned: false,
      }),
    ];
    await screen.load();
    expect(row('plowshare.documents.ingest-budget')).not.toBeNull();
    expect(root.textContent).toContain('operator');
  });

  it('says a pinned key is overwritten at the next boot', async () => {
    // Writing one works now and is reverted on restart: an operator's
    // environment variable wins on boot precedence. A screen that offered
    // the edit and stayed quiet about that would be lying by omission.
    server.settings = [
      setting({ updatedAt: null, updatedBy: null, pinned: true }),
    ];
    await screen.load();
    expect(
      row('plowshare.documents.ingest-budget').querySelector('[data-pinned]')
        ?.textContent,
    ).toContain('next boot');
  });

  it('does not claim a boot overwrite for a key nobody pinned', async () => {
    server.settings = [
      setting({ updatedAt: null, updatedBy: null, pinned: false }),
    ];
    await screen.load();
    expect(
      row('plowshare.documents.ingest-budget').querySelector('[data-pinned]'),
    ).toBeNull();
  });

  it('says so when only one key is live, rather than looking empty', async () => {
    server.settings = [
      setting({ updatedAt: null, updatedBy: null, pinned: false }),
    ];
    await screen.load();
    expect(root.querySelector('[data-note]')).not.toBeNull();
  });

  it('renders "not recorded" for a key nobody has written, never a blank', async () => {
    server.settings = [
      setting({ updatedAt: null, updatedBy: null, pinned: false }),
    ];
    await screen.load();
    expect(root.textContent).toContain('not recorded');
  });

  it('writes a value as a raw body, not as JSON', async () => {
    await screen.load();
    const field = row('plowshare.documents.ingest-budget').querySelector(
      'input',
    ) as HTMLInputElement;
    field.value = '80';
    const save = row('plowshare.documents.ingest-budget').querySelector(
      'button.save',
    ) as HTMLButtonElement;
    save.click();
    await vi.waitFor(() => expect(put).toHaveBeenCalled());
    expect(put.mock.calls.at(-1)?.[0]).toBe(
      '/v1/config/plowshare.documents.ingest-budget',
    );
    expect(put.mock.calls.at(-1)?.[1]).toBe('80');
  });

  it('re-reads the listing after a write lands, rather than trusting its own guess', async () => {
    await screen.load();
    const field = row('plowshare.documents.ingest-budget').querySelector(
      'input',
    ) as HTMLInputElement;
    field.value = '80';
    const save = row('plowshare.documents.ingest-budget').querySelector(
      'button.save',
    ) as HTMLButtonElement;
    save.click();
    await vi.waitFor(() => expect(put).toHaveBeenCalled());
    await vi.waitFor(() => expect(get).toHaveBeenCalledTimes(2));
  });

  // This drives the injected `write` seam, not the real one -- and that is
  // deliberate rather than a shortcut. The real `write()` goes through
  // `refused()`, which reads the body, so `RuntimeConfigController.set`'s own
  // refusal (naming the live keys) is what its `ApiError` carries; what that
  // function does with a real `Response` is pinned in `api.test.ts`, against
  // a real `fetch` rather than this fake transport. What this test covers is
  // the other half, and is this screen's own: whatever message a refusal's
  // `ApiError` carries is shown on the page verbatim, through
  // `trouble`/`problemText`, with nothing invented and nothing paraphrased.
  // The message below is written to look like nothing this screen could have
  // made up itself, so a passing assertion means the text travelled rather
  // than matching by coincidence.
  it('renders a refused write’s own message verbatim, rather than inventing one', async () => {
    server.settings = [setting({})];
    put.mockRejectedValue(
      new ApiError('xyzzy-refusal-793, verbatim from the rejection', 400),
    );
    await screen.load();
    const field = row('plowshare.documents.ingest-budget').querySelector(
      'input',
    ) as HTMLInputElement;
    field.value = '80';
    const save = row('plowshare.documents.ingest-budget').querySelector(
      'button.save',
    ) as HTMLButtonElement;
    save.click();
    await vi.waitFor(() =>
      expect(root.querySelector('[data-trouble]')).not.toBeNull(),
    );
    expect(root.textContent).toContain(
      'xyzzy-refusal-793, verbatim from the rejection',
    );
  });
});
