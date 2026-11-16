import type { ElementHandle, JSHandle, Page } from 'playwright';
import {
  BrowserControlError,
  decodeBrowserSnapshot,
} from '../browser-control.ts';
import type {
  BrowserBounds,
  BrowserSnapshot,
  BrowserStateGuard,
} from '../browser-control.ts';
import { computerId } from '../contracts.ts';
import { OperationDeadline } from '../deadline.ts';
import { awaitBrowserWork } from './browser-control-rpc.ts';

interface DomMemory {
  readonly documentId: string;
  readonly ids: WeakMap<Element, unknown>;
  serial: number;
  revision: number;
  focusRevision: number;
  focused: Element | null;
  observer?: MutationObserver;
  resized?: ResizeObserver;
}
interface ScanOptions {
  readonly key: string;
  readonly documentId: string;
  readonly maximumNodes: number;
  readonly maximumVisited: number;
  readonly root: Element | null;
}

/** Fixed trusted inspection program. No model-supplied script, selector or predicate is evaluated. */
function scan(options: ScanOptions) {
  // This is a private browser boundary, not an assertion that page-owned values
  // already satisfy our internal memory contract. Validate every reused field.
  const saved: unknown = Reflect.get(window, options.key);
  const isMemory = (value: unknown): value is DomMemory => {
    if (value === null || typeof value !== 'object') return false;
    const counter = (n: unknown) =>
      typeof n === 'number' && Number.isSafeInteger(n) && n >= 0;
    return (
      'documentId' in value &&
      typeof value.documentId === 'string' &&
      value.documentId.length <= 256 &&
      'ids' in value &&
      value.ids instanceof WeakMap &&
      'serial' in value &&
      counter(value.serial) &&
      'revision' in value &&
      counter(value.revision) &&
      'focusRevision' in value &&
      counter(value.focusRevision) &&
      'focused' in value &&
      (value.focused === null ||
        (value.focused instanceof Element &&
          value.focused.ownerDocument === document)) &&
      'observer' in value &&
      value.observer instanceof MutationObserver &&
      'resized' in value &&
      value.resized instanceof ResizeObserver
    );
  };
  if (saved !== undefined && !isMemory(saved))
    throw new Error('Invalid browser memory');
  let memory = saved;
  if (memory !== undefined && memory.documentId !== options.documentId) {
    // Same-document/history navigation can preserve the Window and its nodes.
    // The host navigation epoch still invalidates every previous observation.
    memory.observer?.disconnect();
    memory.resized?.disconnect();
    memory = undefined;
  }
  if (memory === undefined) {
    memory = {
      documentId: options.documentId,
      ids: new WeakMap(),
      serial: 0,
      revision: 0,
      focusRevision: 0,
      focused: null,
    };
    if (!Reflect.set(window, options.key, memory))
      throw new Error('Browser memory unavailable');
    const observed = memory;
    const observer = new MutationObserver(() => {
      observed.revision++;
    });
    observer.observe(document, {
      subtree: true,
      childList: true,
      attributes: true,
      characterData: true,
    });
    const resized = new ResizeObserver(() => {
      observed.revision++;
    });
    if (document.documentElement !== null)
      resized.observe(document.documentElement);
    if (document.body !== null) resized.observe(document.body);
    memory.observer = observer;
    memory.resized = resized;
  }
  const id = (element: Element): string => {
    let value = memory.ids.get(element);
    if (value === undefined) {
      value = `${memory.documentId}-e${++memory.serial}`;
      memory.ids.set(element, value);
    }
    if (
      typeof value !== 'string' ||
      value.length > 256 ||
      !value.startsWith(`${memory.documentId}-e`)
    )
      throw new Error('Invalid element identity');
    return value;
  };
  const active =
    document.activeElement === document.body ||
    document.activeElement === document.documentElement
      ? null
      : document.activeElement;
  if (memory.focused !== active) {
    memory.focusRevision++;
    memory.focused = active;
  }
  const focusedElementId = active === null ? null : id(active);
  const root = options.root ?? document.body ?? document.documentElement;
  if (root === null || root.ownerDocument !== document || !root.isConnected)
    throw new Error('Stale DOM root');
  const elements: Element[] = [];
  const nodes: {
    elementId: string;
    parentElementId: string | null;
    role: string;
    name: string;
    editable: boolean;
    focused: boolean;
    disabled: boolean;
    bounds: { x: number; y: number; width: number; height: number } | null;
  }[] = [];
  const included = new Map<Element, string>();
  let current: Element | null = root,
    visited = 0,
    truncated = false;
  const boundedText = (element: Element): string => {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let output = '',
      count = 0;
    while (count++ < 32 && output.length < 256) {
      const text = walker.nextNode();
      if (text === null) break;
      let parent = text.parentElement,
        depth = 0,
        visible = true;
      while (parent !== null && depth++ < 32) {
        const style = getComputedStyle(parent);
        if (
          style.display === 'none' ||
          style.visibility === 'hidden' ||
          parent.getAttribute('aria-hidden') === 'true'
        ) {
          visible = false;
          break;
        }
        if (parent === element) break;
        parent = parent.parentElement;
      }
      if (!visible || parent !== element) continue;
      const value = text.textContent ?? '';
      if (value.length > 256 - output.length) truncated = true;
      output += value.slice(0, 256 - output.length);
    }
    if (count > 32 || output.length >= 256) truncated = true;
    return output.trim().slice(0, 256);
  };
  while (current !== null) {
    if (
      visited++ >= options.maximumVisited ||
      nodes.length >= options.maximumNodes
    ) {
      truncated = true;
      break;
    }
    const element: Element = current;
    const style = getComputedStyle(element);
    const tag = element.tagName.toLowerCase();
    const inputType =
      tag === 'input'
        ? (element.getAttribute('type') ?? 'text').toLowerCase()
        : '';
    const rectangle = element.getBoundingClientRect();
    const hidden =
      style.display === 'none' ||
      style.visibility === 'hidden' ||
      element.getAttribute('aria-hidden') === 'true' ||
      inputType === 'hidden';
    if (!hidden && rectangle.width > 0 && rectangle.height > 0) {
      const elementId = id(element);
      let parent = element.parentElement;
      while (parent !== null && !included.has(parent))
        parent = parent.parentElement;
      let role =
        (element.getAttribute('role') ?? '').slice(0, 64).split(/\s/u)[0] ?? '';
      if (role === '')
        role =
          tag === 'button'
            ? 'button'
            : tag === 'a'
              ? 'link'
              : tag === 'input' || tag === 'textarea'
                ? 'textbox'
                : tag === 'select'
                  ? 'combobox'
                  : tag === 'canvas'
                    ? 'canvas'
                    : 'generic';
      const editable =
        inputType !== 'password' &&
        (tag === 'textarea' ||
          (tag === 'input' &&
            ['text', 'email', 'search', 'tel', 'url', 'number', ''].includes(
              inputType,
            )) ||
          (element as HTMLElement).isContentEditable === true);
      const rawName =
        element.getAttribute('aria-label') ??
        element.getAttribute('title') ??
        '';
      if (rawName.length > 256) truncated = true;
      let name = rawName.slice(0, 256);
      if (
        name === '' &&
        tag !== 'input' &&
        tag !== 'textarea' &&
        inputType !== 'password'
      )
        name = boundedText(element);
      if (name === '' && (tag === 'input' || tag === 'textarea')) {
        const labels = (element as HTMLInputElement | HTMLTextAreaElement)
          .labels;
        if (labels !== null && labels.length > 0 && labels[0] !== undefined)
          name = boundedText(labels[0]);
      }
      const bounded = [
        rectangle.x,
        rectangle.y,
        rectangle.width,
        rectangle.height,
      ].every((value) => Number.isFinite(value) && Math.abs(value) <= 32768);
      if (!bounded) truncated = true;
      nodes.push({
        elementId,
        parentElementId: included.get(parent ?? element) ?? null,
        role,
        name,
        editable,
        focused: element === active,
        disabled:
          element.hasAttribute('disabled') ||
          element.getAttribute('aria-disabled') === 'true',
        bounds: bounded
          ? {
              x: rectangle.x,
              y: rectangle.y,
              width: rectangle.width,
              height: rectangle.height,
            }
          : null,
      });
      included.set(element, elementId);
      elements.push(element);
    }
    if (!hidden && element.firstElementChild !== null) {
      current = element.firstElementChild;
      continue;
    }
    current = element;
    while (
      current !== null &&
      current !== root &&
      current.nextElementSibling === null
    )
      current = current.parentElement;
    current =
      current === null || current === root ? null : current.nextElementSibling;
  }
  if (options.root === null && active !== null && !included.has(active))
    truncated = true;
  // Geometry complements mutation/resize/scroll epochs: CSS transforms and
  // font layout can move exact targets without a DOM mutation.
  let geometry = 2166136261;
  for (const node of nodes) {
    const text = `${node.elementId}:${node.bounds?.x}:${node.bounds?.y}:${node.bounds?.width}:${node.bounds?.height}:${node.disabled}`;
    for (let i = 0; i < text.length; i++)
      geometry = Math.imul(geometry ^ text.charCodeAt(i), 16777619);
  }
  return {
    metadata: {
      documentId: memory.documentId,
      layoutGeneration: `${memory.documentId}:${memory.revision}:${innerWidth}:${innerHeight}:${scrollX}:${scrollY}:${geometry >>> 0}`,
      focusGeneration: `${memory.documentId}:${memory.focusRevision}`,
      focusedElementId,
      nodes,
      truncated,
      rootElementId: options.root === null ? null : id(root),
    },
    elements,
  };
}

function row(value: unknown): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new BrowserControlError('OBSERVATION_FAILED');
  return value as Readonly<Record<string, unknown>>;
}
export interface DomScan {
  readonly guard: BrowserStateGuard;
  readonly focusedElementId: string | null;
  readonly snapshot: BrowserSnapshot;
  readonly handles: ReadonlyMap<string, ElementHandle<Element>>;
  dispose(deadline?: OperationDeadline): Promise<void>;
}

/** Convert unknown browser protocol data before it reaches control/session logic. */
export async function inspectBrowserDom(
  page: Page,
  options: Omit<ScanOptions, 'root'>,
  surfaceId: string,
  snapshotId: string,
  deadline: OperationDeadline,
  root: ElementHandle<Element> | null = null,
  cleanupBudgetMs = 2000,
): Promise<DomScan> {
  const result = await awaitBrowserWork(
    page.evaluateHandle(scan, { ...options, root }),
    deadline,
  );
  const owned: JSHandle[] = [result];
  const handles = new Map<string, ElementHandle<Element>>();
  try {
    const metadataHandle = await awaitBrowserWork(
      result.getProperty('metadata'),
      deadline,
    );
    owned.push(metadataHandle);
    const metadata = row(
      await awaitBrowserWork(metadataHandle.jsonValue(), deadline),
    );
    const guard = {
      surfaceId,
      documentId: computerId(metadata['documentId']),
      layoutGeneration: computerId(metadata['layoutGeneration']),
      focusGeneration: computerId(metadata['focusGeneration']),
    };
    const snapshot = decodeBrowserSnapshot({
      snapshotId,
      guard,
      capturedAt: new Date().toISOString(),
      kind: 'dom-semantic',
      approximate: true,
      nodes: metadata['nodes'],
      truncated: metadata['truncated'],
      rootElementId: metadata['rootElementId'],
    });
    const focusedElementId =
      metadata['focusedElementId'] === null
        ? null
        : computerId(metadata['focusedElementId']);
    const elements = await awaitBrowserWork(
      result.getProperty('elements'),
      deadline,
    );
    owned.push(elements);
    const properties = await awaitBrowserWork(
      elements.getProperties(),
      deadline,
    );
    for (const [index, handle] of properties) {
      const n = Number(index),
        node = snapshot.nodes[n];
      const element = handle.asElement();
      if (
        !Number.isSafeInteger(n) ||
        n < 0 ||
        node === undefined ||
        element === null
      ) {
        owned.push(handle);
        throw new BrowserControlError('OBSERVATION_FAILED');
      }
      // getProperties erases the vendor generic. asElement plus the fixed scan
      // program above establish these values are the exact returned Elements.
      handles.set(node.elementId, element as ElementHandle<Element>);
    }
    if (handles.size !== snapshot.nodes.length)
      throw new BrowserControlError('OBSERVATION_FAILED');
    return {
      guard,
      snapshot,
      focusedElementId,
      handles,
      async dispose(deadline) {
        const budget = deadline ?? new OperationDeadline(cleanupBudgetMs);
        try {
          await awaitBrowserWork(
            Promise.all(
              [...owned, ...handles.values()].map((handle) => handle.dispose()),
            ),
            budget,
          );
        } finally {
          if (deadline === undefined) budget.dispose();
        }
      },
    };
  } catch (error) {
    const cleanup = new OperationDeadline(cleanupBudgetMs);
    try {
      await awaitBrowserWork(
        Promise.all(
          [...owned, ...handles.values()].map((handle) => handle.dispose()),
        ),
        cleanup,
      );
    } finally {
      cleanup.dispose();
    }
    throw error;
  }
}

export interface BrowserElementCheck {
  readonly connected: boolean;
  readonly frame: boolean;
  readonly editable: boolean;
  readonly sensitive: boolean;
  readonly focused: boolean;
  readonly hit: boolean;
  readonly bounds: BrowserBounds | null;
  readonly borderLeft: number;
  readonly borderTop: number;
}
/** DOM protocol values are validated before they participate in action coordinates. */
export function decodeBrowserElementCheck(value: unknown): BrowserElementCheck {
  const fields = [
    'connected',
    'frame',
    'editable',
    'sensitive',
    'focused',
    'hit',
    'bounds',
    'borderLeft',
    'borderTop',
  ];
  const result = row(value);
  if (Object.keys(result).some((key) => !fields.includes(key)))
    throw new BrowserControlError('OBSERVATION_FAILED');
  const boolean = (name: string): boolean => {
    const value = result[name];
    if (typeof value !== 'boolean')
      throw new BrowserControlError('OBSERVATION_FAILED');
    return value;
  };
  const number = (value: unknown, minimum: number): number => {
    if (
      typeof value !== 'number' ||
      !Number.isFinite(value) ||
      value < minimum ||
      value > 32768
    )
      throw new BrowserControlError('OBSERVATION_FAILED');
    return value;
  };
  let bounds: BrowserBounds | null = null;
  if (result['bounds'] !== null) {
    const rectangle = row(result['bounds']);
    if (
      Object.keys(rectangle).some(
        (key) => !['x', 'y', 'width', 'height'].includes(key),
      )
    )
      throw new BrowserControlError('OBSERVATION_FAILED');
    bounds = Object.freeze({
      x: number(rectangle['x'], -32768),
      y: number(rectangle['y'], -32768),
      width: number(rectangle['width'], 0),
      height: number(rectangle['height'], 0),
    });
  }
  const connected = boolean('connected');
  if (connected !== (bounds !== null))
    throw new BrowserControlError('OBSERVATION_FAILED');
  return Object.freeze({
    connected,
    frame: boolean('frame'),
    editable: boolean('editable'),
    sensitive: boolean('sensitive'),
    focused: boolean('focused'),
    hit: boolean('hit'),
    bounds,
    borderLeft: number(result['borderLeft'], 0),
    borderTop: number(result['borderTop'], 0),
  });
}

/** Readiness/hit-test for an exact captured node; never re-resolve a name or locator. */
export async function checkBrowserElement(
  handle: ElementHandle<Element>,
  point: { readonly x: number; readonly y: number } | null,
  deadline: OperationDeadline,
): Promise<BrowserElementCheck> {
  deadline.requireActive();
  const value: unknown = await awaitBrowserWork(
    handle.evaluate((element, point) => {
      if (!element.isConnected || element.ownerDocument !== document)
        return {
          connected: false,
          frame: false,
          editable: false,
          sensitive: false,
          focused: false,
          hit: false,
          bounds: null,
          borderLeft: 0,
          borderTop: 0,
        };
      const tag = element.tagName.toLowerCase(),
        type = (element.getAttribute('type') ?? 'text').toLowerCase();
      const bounds = element.getBoundingClientRect();
      const x = point?.x ?? bounds.x + bounds.width / 2,
        y = point?.y ?? bounds.y + bounds.height / 2;
      const hit = document.elementFromPoint(x, y);
      const sensitive = (node: Element | null): boolean => {
        if (node === null) return false;
        if (
          node.closest(
            'input[type="password" i],input[type="file" i],a[download]',
          ) !== null
        )
          return true;
        const label = node.closest('label');
        const control =
          label instanceof HTMLLabelElement ? label.control : null;
        return (
          control instanceof HTMLInputElement &&
          ['password', 'file'].includes(control.type)
        );
      };
      return {
        connected: true,
        frame:
          element.closest('iframe,frame') !== null ||
          hit?.closest('iframe,frame') != null,
        editable:
          !element.hasAttribute('readonly') &&
          !element.hasAttribute('disabled') &&
          ((tag === 'input' &&
            ['text', 'email', 'search', 'tel', 'url', 'number'].includes(
              type,
            )) ||
            tag === 'textarea' ||
            (element as HTMLElement).isContentEditable === true),
        sensitive: sensitive(element) || sensitive(hit),
        focused: document.activeElement === element,
        hit: hit !== null && (hit === element || element.contains(hit)),
        bounds: {
          x: bounds.x,
          y: bounds.y,
          width: bounds.width,
          height: bounds.height,
        },
        borderLeft: Number.parseFloat(
          getComputedStyle(element).borderLeftWidth,
        ),
        borderTop: Number.parseFloat(getComputedStyle(element).borderTopWidth),
      };
    }, point),
    deadline,
  );
  return decodeBrowserElementCheck(value);
}
