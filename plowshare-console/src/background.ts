import { errorMessage } from '../../sdk/typescript/src/binding/values.ts';
/** DOM event/timer APIs cannot await work. Their owner keeps failures visible. */
export function background(work: Promise<unknown> | undefined): void {
  if (!work) return;
  void work.catch((reason: unknown) => {
    const host = document.querySelector('dialog[open]') ?? document.body;
    let alert = host.querySelector<HTMLElement>('[data-async-error]');
    if (!alert) {
      alert = document.createElement('p');
      alert.dataset.asyncError = 'true';
      alert.setAttribute('role', 'alert');
      host.append(alert);
    }
    alert.textContent = errorMessage(reason);
  });
}
