import { it, expect } from 'vitest';
import { applicationFiles } from './application-files';
import type { EventStream, FrameOutcome } from '../events';
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
it('browses server text, retains a draft and reconciles a lost save without replay', async () => {
  const calls: string[] = [];
  let fail = false;
  const source = {
    project: 'app',
    path: 'a.txt',
    text: 'old',
    revision: 'a'.repeat(64),
    writable: true,
  };
  const stream: EventStream = {
    close() {},
    status: () => ({ state: 'open', attempt: 0, retryInMs: null }),
    async ask(type): Promise<FrameOutcome> {
      calls.push(type);
      if (type === 'application.file.save') {
        fail = true;
        throw new Error('socket lost');
      }
      return {
        code: 'OK',
        payload:
          type === 'application.files'
            ? {
                project: 'app',
                path: '',
                entries: [{ path: 'a.txt', name: 'a.txt', directory: false }],
                more: false,
              }
            : source,
      };
    },
  };
  const view = applicationFiles('app', () => stream);
  document.body.replaceChildren(view);
  const click = async (label: string) => {
    const target = [...view.querySelectorAll('button')].find(
      (row) => row.textContent === label,
    )!;
    target.click();
    await tick();
  };
  await click('Files');
  await click('a.txt');
  const editor = view.querySelector('textarea')!;
  editor.value = 'draft';
  editor.dispatchEvent(new Event('input'));
  await click('Save file');
  expect(fail).toBe(true);
  expect(view.querySelector('[role="alert"]')?.textContent).toContain(
    'will not be replayed',
  );
  expect(
    [...view.querySelectorAll('button')].find(
      (row) => row.textContent === 'Save file',
    )!.disabled,
  ).toBe(true);
  await click('Read current file');
  expect(editor.value).toBe('draft');
  expect(calls.filter((type) => type === 'application.file.save')).toHaveLength(
    1,
  );
});
it('shows a readonly server file and a disconnected error without HTTP fallback', async () => {
  const source = {
    project: 'app',
    path: 'a.txt',
    text: 'old',
    revision: 'a'.repeat(64),
    writable: false,
  };
  let connected = true;
  const stream: EventStream = {
    close() {},
    status: () => ({
      state: connected ? 'open' : 'closed',
      attempt: 0,
      retryInMs: null,
    }),
    async ask(type) {
      return {
        code: 'OK',
        payload:
          type === 'application.files'
            ? { project: 'app', path: '', entries: [], more: false }
            : source,
      };
    },
  };
  const view = applicationFiles('app', () => stream);
  document.body.replaceChildren(view);
  view.querySelector<HTMLButtonElement>('button')!.click();
  await tick();
  view.querySelector('input')!.value = 'a.txt';
  [...view.querySelectorAll('button')]
    .find((row) => row.textContent === 'Read file')!
    .click();
  await tick();
  expect(view.querySelector('textarea')!.readOnly).toBe(true);
  connected = false;
  [...view.querySelectorAll('button')]
    .find((row) => row.textContent === 'Read current file')!
    .click();
  await tick();
  expect(view.querySelector('[role="alert"]')?.textContent).toContain(
    'Connect to the server',
  );
});
