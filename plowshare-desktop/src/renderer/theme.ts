/** Desktop appearance is a local, origin-wide preference, independent of server authority. */
type DesktopTheme = 'classic' | 'print' | 'print-light' | 'print-dark';
const storageKey = 'plowshare.desktop.theme.v1';

function readTheme(value: unknown): DesktopTheme {
  if (
    value === 'classic' ||
    value === 'print' ||
    value === 'print-light' ||
    value === 'print-dark'
  )
    return value;
  throw new Error('Unknown Desktop theme.');
}

let current: DesktopTheme = 'classic';
let notice = '';
try {
  current = readTheme(localStorage.getItem(storageKey) ?? 'classic');
} catch {
  notice = 'Could not read the saved theme. Choose a theme to save it again.';
}

/** Runs before body paint; every workspace page loads the same script and origin. */
function applyTheme(choice: DesktopTheme) {
  current = choice;
  document.documentElement.dataset.theme =
    choice === 'classic' ? 'classic' : 'print';
  document.documentElement.dataset.printMode =
    choice === 'print-light'
      ? 'light'
      : choice === 'print-dark'
        ? 'dark'
        : 'system';
  const select = document.querySelector<HTMLSelectElement>('#appearance-theme');
  if (select) select.value = choice;
  const status = document.querySelector<HTMLElement>('#appearance-status');
  if (status) {
    status.textContent = notice;
    status.hidden = notice.length === 0;
  }
}
applyTheme(current);

document.addEventListener('DOMContentLoaded', () => {
  applyTheme(current);
  document
    .querySelector<HTMLSelectElement>('#appearance-theme')
    ?.addEventListener('change', (event) => {
      if (!(event.currentTarget instanceof HTMLSelectElement)) return;
      const choice = readTheme(event.currentTarget.value);
      notice = '';
      try {
        localStorage.setItem(storageKey, choice);
      } catch {
        notice =
          'Theme changed for this view, but could not be saved. Try again.';
      }
      applyTheme(choice);
    });
});

// Inspection pages share plowshare://app. Storage events update already-open
// pages; newly opened pages read the saved choice before painting. A malformed
// update retains the current look and reports the problem instead of applying it.
window.addEventListener('storage', (event) => {
  if (event.storageArea !== localStorage || event.key !== storageKey) return;
  try {
    const choice = readTheme(event.newValue ?? 'classic');
    notice = '';
    applyTheme(choice);
  } catch {
    notice =
      'Another view saved an unknown theme. Choose a theme to repair it.';
    applyTheme(current);
  }
});
