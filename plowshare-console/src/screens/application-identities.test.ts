import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { applicationIdentityControls } from './application-identities.ts';
const requestId = '11111111-1111-1111-1111-111111111111',
  tokenId = '22222222-2222-2222-2222-222222222222';
const credential = 'pss_' + '1'.repeat(48);
const receipt = {
  project: 'app',
  requestId,
  tokenId,
  principal: '@service/' + tokenId,
  expiresAt: '2026-11-01T00:00:00Z',
};
function fixture(lost = false, admin = true) {
  let retained = receipt;
  const ask = vi.fn(async (type: string, payload?: unknown) => {
    if (type === 'admin.status')
      return {
        code: 'OK',
        payload: { handle: 'operator', serverAdmin: admin },
      };
    if (type === 'application.token.issue') {
      if (
        typeof payload === 'object' &&
        payload !== null &&
        'requestId' in payload &&
        typeof payload.requestId === 'string'
      )
        retained = { ...receipt, requestId: payload.requestId };
      if (lost) throw new Error('lost reply');
      return { code: 'OK', payload: { receipt: retained, credential } };
    }
    if (type === 'application.token.receipt')
      return { code: 'OK', payload: retained };
    throw new Error('Unexpected command');
  });
  const controls = applicationIdentityControls(
    () => ({
      ask,
      close() {},
      status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
    }),
    'app',
  );
  document.body.append(controls.element);
  controls.element.open = true;
  const click = (text: string) =>
    Array.from(controls.element.querySelectorAll('button'))
      .find((button) => button.textContent === text)
      ?.click();
  return { controls, ask, click };
}
beforeEach(() => {
  const data = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => data.set(key, value),
    removeItem: (key: string) => data.delete(key),
    clear: () => data.clear(),
    key: (index: number) => Array.from(data.keys())[index] ?? null,
    get length() {
      return data.size;
    },
  });
});
afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
  document.body.replaceChildren();
});
describe('Application credential controls', () => {
  it('shows a secret once and clears it on dismissal and destruction without caching it', async () => {
    const f = fixture();
    f.click('Issue or rotate credential');
    const input = f.controls.element.querySelector<HTMLInputElement>(
      '[data-input="New service credential"]',
    )!;
    await vi.waitFor(() => expect(input.value).toBe(credential));
    expect(JSON.stringify(localStorage)).not.toContain(credential);
    f.click('Dismiss credential');
    expect(input.value).toBe('');
    f.controls.destroy();
    expect(input.value).toBe('');
  });
  it('recovers an unknown issuance from its receipt without another issue and fences non-admins', async () => {
    const f = fixture(true);
    f.click('Issue or rotate credential');
    await vi.waitFor(() =>
      expect(f.controls.element.textContent).toContain('lost reply'),
    );
    f.click('Read pending receipt');
    await vi.waitFor(() =>
      expect(f.controls.element.textContent).toContain('Request confirmed'),
    );
    expect(
      f.ask.mock.calls.filter((call) => call[0] === 'application.token.issue'),
    ).toHaveLength(1);
    const denied = fixture(false, false);
    denied.click('Issue or rotate credential');
    await vi.waitFor(() =>
      expect(denied.controls.element.textContent).toContain('administrator'),
    );
    expect(
      denied.ask.mock.calls.some(
        (call) => call[0] === 'application.token.issue',
      ),
    ).toBe(false);
  });
});
