import { describe, it, expect } from 'vitest';
import { decodeRequest } from './schema.ts';
import { parseCommand } from './commands.ts';
import { request, resultOf } from './direct.ts';
import { checkedTransport } from './transport.ts';
const document = {
  project: 'app',
  path: 'a.txt',
  text: '',
  revision: 'a'.repeat(64),
  writable: true,
};
describe('Application server file contracts', () => {
  it('exposes shared CLI/TUI commands and allows an empty text file', () => {
    expect(parseCommand('application files', 'app')).toMatchObject({
      kind: 'request',
      request: { type: 'application.files', payload: { project: 'app' } },
    });
    expect(() => decodeRequest('application.file.save', document)).toThrow();
    expect(
      request('application.file.save', {
        project: 'app',
        path: 'a.txt',
        text: '',
        revision: document.revision,
      }).payload.text,
    ).toBe('');
  });
  it('rejects traversal, coercion, binary and unbounded inputs', () => {
    for (const path of [
      '../secret',
      '/root',
      '.git/config',
      'a\\b',
      'a//b',
      'x\0',
    ])
      expect(() =>
        decodeRequest('application.file.read', { project: 'app', path }),
      ).toThrow();
    for (const path of [null, 1])
      expect(() =>
        decodeRequest('application.files', { project: 'app', path }),
      ).toThrow();
    for (const text of ['\0', '\ud800', 'x'.repeat(262145)])
      expect(() =>
        decodeRequest('application.file.save', {
          project: 'app',
          path: 'a.txt',
          text,
          revision: document.revision,
        }),
      ).toThrow();
  });
  it('refuses crossed source replies and malformed revisions, preserving uncertain save without replay', async () => {
    const asked = request('application.file.read', {
      project: 'app',
      path: 'a.txt',
    });
    for (const bad of [
      { ...document, project: 'other' },
      { ...document, path: 'other.txt' },
      { ...document, revision: 'old' },
    ])
      expect(resultOf(asked, { code: 'OK', payload: bad }).kind).toBe(
        'invalid-response',
      );
    let calls = 0;
    const transport = checkedTransport({
      ask: async () => {
        calls++;
        return { code: 'OK', payload: { ...document, project: 'other' } };
      },
    });
    await expect(
      transport.ask('application.file.save', {
        project: 'app',
        path: 'a.txt',
        text: '',
        revision: document.revision,
      }),
    ).rejects.toThrow('completion is unknown');
    expect(calls).toBe(1);
  });
});
