import { icon } from './icons.ts';

export const copyButton = (label: string, kind: 'answer' | 'code' | 'section' | 'record') =>
  `<button class="copy-button icon-button" type="button" data-copy="${kind}" aria-label="${label}" title="${label}">${icon('copy')}</button>`;

/** Explicit clipboard writes only; neither frontend reads the clipboard. */
export function installCopyControls() {
  const feedback = document.createElement('span');
  feedback.className = 'sr-only'; feedback.setAttribute('role', 'status');
  document.body.append(feedback);
  document.addEventListener('click', async event => {
    const button = (event.target as HTMLElement).closest<HTMLButtonElement>('[data-copy]');
    if (!button || button.disabled) return;
    event.preventDefault();
    const text = button.dataset.copy === 'answer' ? button.closest<HTMLElement>('[data-copy-source]')?.dataset.copySource
      : button.dataset.copy === 'code' ? button.closest('.markdown-code')?.querySelector('pre code')?.textContent
      : button.dataset.copy === 'section' ? button.closest('.trajectory-text-section')?.querySelector('pre')?.textContent
      : button.closest('.trajectory-raw')?.querySelector('pre')?.textContent;
    if (text == null) return;
    const label = button.getAttribute('aria-label') || 'Copy text';
    button.disabled = true;
    try {
      await window.plowshare.request({ action: 'copy-text', text });
      button.innerHTML = icon('check'); button.dataset.copied = 'true';
      button.setAttribute('aria-label', 'Copied'); button.title = 'Copied'; feedback.textContent = 'Copied to clipboard.';
    } catch {
      button.innerHTML = icon('alert'); button.dataset.copied = 'false';
      button.setAttribute('aria-label', 'Copy failed'); button.title = 'Copy failed. Try again.';
      feedback.textContent = 'Could not copy this text.';
    } finally {
      button.disabled = false;
      setTimeout(() => {
        if (!button.isConnected) return;
        button.innerHTML = icon('copy'); delete button.dataset.copied;
        button.setAttribute('aria-label', label); button.title = label;
      }, 2000);
    }
  });
}
