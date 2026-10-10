import { describe, expect, it, vi } from 'vitest';
import type { CommandEntry } from '../../../sdk/typescript/src/operations/conversation-replies.ts';
import { mountCommands } from './commands';
const skill: CommandEntry = {
  command: '/skill:review',
  aliases: [],
  kind: 'skill',
  name: 'review',
  description: '<script>Review</script>',
  argumentHint: 'Describe work',
  executor: 'reviewer',
  mode: null,
  tier: 'PROJECT',
  hash: 'checked',
};
function fixture() {
  const form = document.createElement('form');
  const root = document.createElement('div');
  const draft = document.createElement('textarea');
  form.append(root, draft);
  const controls = mountCommands(root, draft);
  const submit = vi.fn();
  draft.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') submit();
  });
  controls.update([skill], true);
  return { root, draft, controls, submit };
}
describe('browser command preparation', () => {
  it('completes a prefix with the DIRECT default without submitting and allows context overrides', () => {
    const { root, draft, submit } = fixture();
    draft.value = '/skill:r';
    draft.dispatchEvent(new Event('input'));
    expect(root.querySelectorAll('[role=option]')).toHaveLength(1);
    expect(root.querySelector('[role=option]')?.textContent).toContain(
      'Describe work',
    );
    draft.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(submit).not.toHaveBeenCalled();
    expect(draft.value).toBe('/skill:review --mode=DIRECT ');
    expect(root.querySelector('select')?.value).toBe('DIRECT');
    expect(root.textContent).toContain('DIRECT (default)');
    const mode = root.querySelector('select')!;
    mode.value = 'NEW';
    root.querySelector<HTMLButtonElement>('.command-card button')!.click();
    expect(draft.value).toBe('/skill:review --mode=NEW ');
    expect(submit).not.toHaveBeenCalled();
    expect(root.querySelector('script')).toBeNull();
  });
  it('keeps arguments editable, replaces context once, and only offers the selected catalog', () => {
    const { root, draft, controls, submit } = fixture();
    draft.value = '/skill:review --mode=DIRECT Review\nthis change';
    root.querySelector('select')!.value = 'SUMMARISED';
    root.querySelector<HTMLButtonElement>('.command-card button')!.click();
    expect(draft.value).toBe(
      '/skill:review --mode=SUMMARISED Review\nthis change',
    );
    expect(submit).not.toHaveBeenCalled();
    draft.value = '/';
    draft.dispatchEvent(new Event('input'));
    controls.update([], true);
    expect(root.querySelectorAll('[role=option]')).toHaveLength(0);
    expect(root.textContent).toContain('No commands are available');
    controls.update(undefined, false);
    expect(root.textContent).toContain('discovery is unavailable');
  });
  it('bounds completion/catalog rendering and disables preparation while the composer is closed', () => {
    const { root, draft, controls } = fixture();
    const commands = Array.from({ length: 60 }, (_, index): CommandEntry => ({
      ...skill,
      command: '/skill:r' + index,
      mode: 'NEW',
    }));
    controls.update(commands, true);
    draft.value = '/';
    draft.dispatchEvent(new Event('input'));
    expect(root.querySelectorAll('[role=option]')).toHaveLength(20);
    expect(root.querySelectorAll('.command-card')).toHaveLength(40);
    controls.setEnabled(false);
    expect(root.querySelectorAll('[role=option]')).toHaveLength(0);
    expect(
      root.querySelector<HTMLButtonElement>('.command-card button')!.disabled,
    ).toBe(true);
    root.querySelector<HTMLInputElement>('input')!.value = 'r59';
    root.querySelector('input')!.dispatchEvent(new Event('input'));
    expect(root.querySelectorAll('.command-card')).toHaveLength(1);
  });
});
