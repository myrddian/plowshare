interface PaneOptions {
  container: HTMLElement;
  pane: HTMLElement;
  other: HTMLElement;
  key: string;
  label: string;
  property: string;
  side?: 'left' | 'right';
  minimum?: number;
  maximum?: () => number;
}

/** Resize existing grid tracks without adding a track or interrupting pane scrolling. */
export function installPaneResize(options: PaneOptions) {
  const { container, pane, other, property } = options;
  const direction = options.side === 'right' ? -1 : 1;
  const storage = `plowshare.desktop.pane.${options.key}.v1`;
  const handle = document.createElement('div');
  handle.className = 'pane-resizer';
  handle.dataset.pane = options.key;
  handle.tabIndex = 0;
  handle.setAttribute('role', 'separator');
  handle.setAttribute('aria-orientation', 'vertical');
  handle.setAttribute('aria-label', options.label);
  if (!pane.id) pane.id = `${options.key}-pane`;
  handle.setAttribute('aria-controls', pane.id);
  handle.title = `${options.label} · Drag or use arrow keys · Double-click to reset`;
  container.classList.add('resizable-panes');
  container.append(handle);
  let preferred: number | undefined;
  try {
    const stored = localStorage.getItem(storage);
    const value = stored === null ? NaN : Number(stored);
    if (Number.isFinite(value) && value > 0) preferred = value;
  } catch {
    /* Resizing remains available without local storage. */
  }
  const limits = () => {
    const maximum = Math.max(
      0,
      options.maximum?.() ?? container.clientWidth - 330,
    );
    return { minimum: Math.min(options.minimum ?? 220, maximum), maximum };
  };
  const constrain = (width: number) => {
    const { minimum, maximum } = limits();
    return Math.round(Math.max(minimum, Math.min(maximum, width)));
  };
  function refresh() {
    // Retain the preferred width when a smaller window temporarily constrains it.
    if (preferred !== undefined)
      container.style.setProperty(property, `${constrain(preferred)}px`);
    const bounds = pane.getBoundingClientRect();
    const parent = container.getBoundingClientRect();
    handle.hidden =
      !bounds.width ||
      !bounds.height ||
      getComputedStyle(pane).display === 'none' ||
      getComputedStyle(other).display === 'none';
    const edge = direction === 1 ? bounds.right : bounds.left;
    handle.style.left = `${edge - parent.left - container.clientLeft}px`;
    handle.style.top = `${bounds.top - parent.top - container.clientTop}px`;
    handle.style.height = `${bounds.height}px`;
    const { minimum, maximum } = limits();
    handle.setAttribute('aria-valuemin', String(Math.round(minimum)));
    handle.setAttribute('aria-valuemax', String(Math.round(maximum)));
    handle.setAttribute('aria-valuenow', String(Math.round(bounds.width)));
    handle.setAttribute('aria-valuetext', `${Math.round(bounds.width)} pixels`);
  }
  function save() {
    try {
      if (preferred === undefined) localStorage.removeItem(storage);
      else localStorage.setItem(storage, String(preferred));
    } catch {
      /* Keep the selected width for this window. */
    }
  }
  function resize(width: number) {
    preferred = constrain(width);
    refresh();
  }
  let drag: { pointer: number; x: number; width: number } | undefined;
  handle.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    event.preventDefault();
    handle.focus();
    drag = {
      pointer: event.pointerId,
      x: event.clientX,
      width: pane.getBoundingClientRect().width,
    };
    handle.setPointerCapture(event.pointerId);
    document.body.classList.add('resizing-panels');
    handle.dataset.resizing = 'true';
  });
  handle.addEventListener('pointermove', (event) => {
    if (drag?.pointer === event.pointerId)
      resize(drag.width + direction * (event.clientX - drag.x));
  });
  function finish(event: PointerEvent) {
    if (drag?.pointer !== event.pointerId) return;
    drag = undefined;
    document.body.classList.remove('resizing-panels');
    delete handle.dataset.resizing;
    save();
  }
  handle.addEventListener('pointerup', finish);
  handle.addEventListener('pointercancel', finish);
  handle.addEventListener('lostpointercapture', finish);
  handle.addEventListener('keydown', (event) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const { minimum, maximum } = limits();
    resize(
      event.key === 'Home'
        ? minimum
        : event.key === 'End'
          ? maximum
          : pane.getBoundingClientRect().width +
            direction *
              (event.key === 'ArrowRight' ? 1 : -1) *
              (event.shiftKey ? 32 : 16),
    );
    save();
  });
  handle.addEventListener('dblclick', () => {
    preferred = undefined;
    container.style.removeProperty(property);
    save();
    refresh();
  });
  const observer = new ResizeObserver(refresh);
  observer.observe(container);
  observer.observe(pane);
  observer.observe(other);
  window.addEventListener('resize', refresh);
  // Visibility changes need an update even if a hidden track keeps its old geometry.
  const mutations = new MutationObserver(refresh);
  mutations.observe(container, {
    attributes: true,
    attributeFilter: ['class', 'hidden'],
  });
  mutations.observe(pane, {
    attributes: true,
    attributeFilter: ['class', 'hidden'],
  });
  refresh();
  return {
    refresh,
    destroy() {
      observer.disconnect();
      mutations.disconnect();
      window.removeEventListener('resize', refresh);
      if (drag) document.body.classList.remove('resizing-panels');
      handle.remove();
    },
  };
}
