import { errorMessage } from 'plowshare-client-ts/binding/values';

/** Browser event dispatch does not await callbacks. Own every rejected promise
 * and leave a visible diagnostic in the active page/dialog. */
export function ownedEvent<A extends unknown[]>(
  handler: (...args: A) => Promise<unknown>,
): (...args: A) => void {
  return (...args) => {
    void handler(...args).catch((reason: unknown) => {
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
  };
}

/** Timers and callbacks use the same visible error owner as DOM event handlers. */
export function background(work: Promise<unknown> | undefined): void {
  if (work) ownedEvent(() => work)();
}
