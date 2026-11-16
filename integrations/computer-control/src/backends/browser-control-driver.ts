import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright';
import type {
  Browser,
  BrowserContext,
  CDPSession,
  Dialog,
  ElementHandle,
  Page,
} from 'playwright';
import {
  BrowserControlError,
  browserScrollConversion,
  decodeBrowserObservation,
  permittedBrowserUrl,
  requireBrowserActionState,
  requireBrowserNavigationState,
  sameBrowserGuard,
} from '../browser-control.ts';
import type {
  AnticipatedDialog,
  BrowserActionResult,
  BrowserCleanupResult,
  BrowserControlCode,
  BrowserDelivery,
  BrowserDialogEvent,
  BrowserFocusRequest,
  BrowserNavigateRequest,
  BrowserObservation,
  BrowserObserveRequest,
  BrowserPerformRequest,
  BrowserState,
  BrowserStateGuard,
  BrowserWindows,
  PreparedBrowserAction,
  PreparedBrowserTarget,
  BrowserDialogType,
} from '../browser-control.ts';
import { BackendError } from '../contracts.ts';
import { OperationDeadline } from '../deadline.ts';
import type {
  BrowserControlBackendConfig,
  BrowserControlDriver,
  ControlledBrowserContext,
} from './browser-control-backend.ts';
import type { BrowserTargetConfig } from './browser.ts';
import { awaitBrowserWork, browserTimeout } from './browser-control-rpc.ts';
import { setTimeout as wait } from 'node:timers/promises';
import { checkBrowserElement, inspectBrowserDom } from './browser-dom.ts';
import type { DomScan } from './browser-dom.ts';

interface Tab {
  readonly surfaceId: string;
  readonly page: Page;
  documentId: string;
  readiness: 'ready' | 'quarantined' | 'closed';
  requestedUrl: string | null;
  guardInstalled: boolean;
  guardWork?: Promise<void>;
  explicitNavigation: boolean;
  snapshot?: DomScan;
}
interface DialogScope {
  readonly anticipated: AnticipatedDialog | undefined;
  consumed: boolean;
  event: BrowserDialogEvent | null;
  failure: BrowserControlCode | undefined;
  readonly work: Set<Promise<void>>;
  cancel?: () => void;
}

function failure(
  error: unknown,
  deadline: OperationDeadline,
): BrowserControlCode {
  return error instanceof BrowserControlError
    ? error.code
    : deadline.signal.aborted
      ? 'DEADLINE_EXPIRED'
      : 'INPUT_FAILED';
}
function refused(code: BrowserControlCode): BrowserActionResult {
  return {
    delivery: {
      outcome: 'NOT_DISPATCHED',
      physicalSettlement: 'settled',
      code,
    },
    observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
    dialog: null,
  };
}
function stateGuard(state: BrowserState): BrowserStateGuard {
  return {
    surfaceId: state.surfaceId,
    documentId: state.documentId,
    layoutGeneration: state.layoutGeneration,
    focusGeneration: state.focusGeneration,
  };
}

/** One independently leased isolated context. Unknown input enters close, never retry. */
class PlaywrightControlledContext implements ControlledBrowserContext {
  private readonly tabs = new Map<string, Tab>();
  private readonly pages = new Map<Page, Tab>();
  private readonly pending = new Set<Promise<unknown>>();
  private readonly eventWork = new Set<Promise<void>>();
  private readonly heldKeys = new Map<Page, Set<string>>();
  private readonly heldButtons = new Map<
    Page,
    Set<'left' | 'middle' | 'right'>
  >();
  private readonly domKey = `__plowshareComputer_${randomUUID()}`;
  private activeSurface = 'viewport';
  private windowsId: string | null = null;
  private creatingInitial = true;
  private readonly popupRequests: string[] = [];
  private fatal: BrowserControlCode | undefined;
  private dialogScope: DialogScope | undefined;
  private closing = false;
  private closed = false;
  private closeWork: Promise<BrowserCleanupResult> | undefined;

  constructor(
    private readonly context: BrowserContext,
    private readonly config: BrowserControlBackendConfig,
  ) {}

  private own<T>(work: Promise<T>): Promise<T> {
    this.pending.add(work);
    work.then(
      () => this.pending.delete(work),
      () => this.pending.delete(work),
    );
    return work;
  }
  private rpc<T>(
    start: () => Promise<T>,
    deadline: OperationDeadline,
  ): Promise<T> {
    deadline.requireActive();
    return awaitBrowserWork(this.own(start()), deadline);
  }
  private event(work: Promise<void>): void {
    const budget = new OperationDeadline(this.config.cleanupBudgetMs ?? 2000);
    const observed = awaitBrowserWork(this.own(work), budget);
    this.eventWork.add(observed);
    if (this.eventWork.size > 256) this.failStop('FENCED');
    observed.then(
      () => {
        this.eventWork.delete(observed);
        budget.dispose();
      },
      () => {
        this.eventWork.delete(observed);
        budget.dispose();
        this.failStop('FENCED');
      },
    );
  }
  private failStop(code: BrowserControlCode): void {
    this.fatal ??= code;
    if (this.dialogScope !== undefined) {
      this.dialogScope.cancel?.();
      return;
    }
    if (this.closing || this.closed) return;
    const cleanup = new OperationDeadline(this.config.cleanupBudgetMs ?? 2000);
    this.close(cleanup).then(
      () => cleanup.dispose(),
      () => {
        cleanup.dispose();
        this.fatal = 'CLEANUP_UNCERTAIN';
      },
    );
  }
  private ready(): void {
    if (this.closed || this.closing)
      throw new BrowserControlError('TARGET_GONE');
    if (this.fatal !== undefined) throw new BrowserControlError(this.fatal);
  }
  private tab(surfaceId: string): Tab {
    const tab = this.tabs.get(surfaceId);
    if (tab === undefined || tab.page.isClosed())
      throw new BrowserControlError('TARGET_GONE');
    return tab;
  }
  private register(
    page: Page,
    surfaceId: string,
    requestedUrl: string | null,
  ): Tab {
    if (this.closing || this.closed)
      throw new BrowserControlError('TARGET_GONE');
    if (this.tabs.size >= (this.config.maxTabs ?? 8)) {
      this.fatal = 'FENCED';
      this.event(page.close().then(() => {}));
      throw new BrowserControlError('FENCED');
    }
    const tab: Tab = {
      page,
      surfaceId,
      documentId: randomUUID(),
      readiness: 'quarantined',
      requestedUrl,
      guardInstalled: false,
      explicitNavigation: false,
    };
    this.tabs.set(surfaceId, tab);
    this.pages.set(page, tab);
    if (surfaceId !== 'viewport') this.activeSurface = surfaceId;
    this.windowsId = null;
    page.on('dialog', (dialog) => this.onDialog(dialog));
    page.on('close', () => {
      tab.readiness = 'closed';
      this.windowsId = null;
    });
    page.on('download', (download) => {
      this.failStop('INPUT_FAILED');
      this.event(download.cancel());
    });
    page.on('filechooser', () => {
      this.failStop('SENSITIVE_TARGET');
    });
    page.on('framenavigated', (frame) => {
      if (frame !== page.mainFrame()) return;
      tab.documentId = randomUUID();
      this.windowsId = null;
      try {
        permittedBrowserUrl(this.config.control, page.url());
        if (
          tab.guardInstalled &&
          (tab.readiness === 'ready' || tab.explicitNavigation)
        )
          tab.readiness = 'ready';
      } catch {
        tab.readiness = 'quarantined';
      }
    });
    tab.guardWork = this.installGuard(tab);
    this.event(tab.guardWork);
    return tab;
  }

  /**
   * Normal Playwright routing skips redirect hops. A second public CDP session
   * pauses every main-document hop; popup first requests remain aborted until
   * this guard is installed and a new explicit navigate is authorized.
   */
  private async installGuard(tab: Tab): Promise<void> {
    const cdp = await this.own(this.context.newCDPSession(tab.page));
    cdp.on('Fetch.requestPaused', (event) => {
      this.event(
        (async () => {
          let allowed: boolean;
          try {
            permittedBrowserUrl(this.config.control, event.request.url);
            allowed =
              !this.closing &&
              this.fatal === undefined &&
              (tab.readiness === 'ready' || tab.explicitNavigation);
          } catch {
            allowed = false;
          }
          await this.own(
            cdp.send(
              allowed ? 'Fetch.continueRequest' : 'Fetch.failRequest',
              allowed
                ? { requestId: event.requestId }
                : {
                    requestId: event.requestId,
                    errorReason: 'BlockedByClient',
                  },
            ),
          );
          if (!allowed && tab.readiness === 'ready')
            this.fatal ??= 'ORIGIN_DENIED';
        })(),
      );
    });
    await this.enableGuard(cdp);
    tab.guardInstalled = true;
  }
  private async enableGuard(cdp: CDPSession): Promise<void> {
    await this.own(
      cdp.send('Fetch.enable', {
        patterns: [
          {
            urlPattern: '*',
            resourceType: 'Document',
            requestStage: 'Request',
          },
        ],
      }),
    );
  }

  async initialize(
    target: BrowserTargetConfig,
    deadline: OperationDeadline,
  ): Promise<void> {
    await this.rpc(
      () =>
        this.context.route('**/*', (route) => {
          const work = (async () => {
            const request = route.request();
            if (!request.isNavigationRequest()) {
              await route.continue();
              return;
            }
            const url = request.url();
            let tab: Tab | undefined;
            try {
              const frame = request.frame();
              if (frame !== frame.page().mainFrame()) {
                await route.abort();
                return;
              }
              tab = this.pages.get(frame.page());
            } catch {
              // Before an initial popup frame exists there is no public Page binding.
              // Never guess a target or dispatch the request while attaching its guard.
              this.popupRequests.push(url.slice(0, 4096));
              if (this.popupRequests.length > 1) this.fatal = 'FENCED';
              await route.abort();
              return;
            }
            if (tab === undefined) {
              await route.abort();
              return;
            }
            if (tab.readiness === 'quarantined' && !tab.explicitNavigation) {
              tab.requestedUrl = url.slice(0, 4096);
              await route.abort();
              return;
            }
            try {
              permittedBrowserUrl(this.config.control, url);
            } catch {
              await route.abort();
              return;
            }
            if (!tab.guardInstalled) {
              await route.abort();
              return;
            }
            await route.continue();
          })();
          this.event(work);
          return work;
        }),
      deadline,
    );
    this.context.on('page', (page) => {
      if (this.creatingInitial) return;
      try {
        this.register(page, randomUUID(), this.popupRequests.shift() ?? null);
      } catch {
        this.fatal = 'FENCED';
      }
    });
    const page = await this.rpc(() => this.context.newPage(), deadline);
    this.creatingInitial = false;
    const tab = this.register(page, 'viewport', null);
    await this.rpc(() => tab.guardWork ?? Promise.resolve(), deadline);
    tab.explicitNavigation = true;
    try {
      await this.rpc(
        () =>
          page.goto(target.url, {
            waitUntil: 'domcontentloaded',
            timeout: browserTimeout(deadline),
            signal: deadline.signal,
          }),
        deadline,
      );
      permittedBrowserUrl(this.config.control, page.url());
      tab.readiness = 'ready';
    } finally {
      tab.explicitNavigation = false;
    }
  }

  private async inspect(
    tab: Tab,
    deadline: OperationDeadline,
    root: ElementHandle<Element> | null = null,
  ): Promise<DomScan> {
    return this.rpc(
      () =>
        inspectBrowserDom(
          tab.page,
          {
            key: this.domKey,
            documentId: tab.documentId,
            maximumNodes: this.config.maxDomNodes ?? 200,
            maximumVisited: this.config.maxVisitedNodes ?? 2000,
          },
          tab.surfaceId,
          randomUUID(),
          deadline,
          root,
          this.config.cleanupBudgetMs ?? 2000,
        ),
      deadline,
    );
  }
  private async state(
    tab: Tab,
    deadline: OperationDeadline,
  ): Promise<BrowserState> {
    if (tab.page.isClosed() || tab.readiness !== 'ready')
      return {
        surfaceId: tab.surfaceId,
        documentId: tab.documentId,
        layoutGeneration: tab.documentId,
        focusGeneration: `${tab.documentId}:${this.activeSurface === tab.surfaceId}`,
        readiness: tab.page.isClosed() ? 'closed' : 'quarantined',
        guardInstalled: tab.page.isClosed() ? false : tab.guardInstalled,
        url: tab.page.isClosed()
          ? null
          : tab.page.url() === 'about:blank'
            ? 'about:blank'
            : 'chrome-error://chromewebdata/',
        origin: null,
        requestedUrl: tab.requestedUrl,
        focusedElementId: null,
      };
    const scan = await this.inspect(tab, deadline);
    try {
      return this.stateFromScan(tab, scan);
    } finally {
      await scan.dispose(deadline);
    }
  }
  private stateFromScan(tab: Tab, scan: DomScan): BrowserState {
    const url = permittedBrowserUrl(this.config.control, tab.page.url());
    return {
      ...scan.guard,
      focusGeneration: `${scan.guard.focusGeneration}:${this.activeSurface === tab.surfaceId}`,
      readiness: 'ready',
      guardInstalled: tab.guardInstalled,
      url,
      origin: new URL(url).origin,
      requestedUrl: null,
      focusedElementId: scan.focusedElementId,
    };
  }
  async windows(deadline: OperationDeadline): Promise<BrowserWindows> {
    this.ready();
    await this.rpc(() => Promise.all([...this.eventWork]), deadline);
    const tabs = [];
    for (const tab of this.tabs.values()) {
      const state = await this.state(tab, deadline);
      const title =
        state.readiness === 'ready'
          ? (await this.rpc(() => tab.page.title(), deadline)).slice(0, 256)
          : '';
      tabs.push({ state, title });
    }
    const windowsId = randomUUID();
    this.windowsId = windowsId;
    return {
      windowsId,
      capturedAt: new Date().toISOString(),
      tabs,
      truncated: false,
    };
  }
  async observe(
    request: BrowserObserveRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserObservation> {
    try {
      return await this.observeAttempt(request, deadline);
    } catch (error) {
      // A read may race the observer's first layout callback. One bounded fresh
      // read is safe; no input or publication is repeated by this retry.
      if (
        !(error instanceof BrowserControlError) ||
        error.code !== 'STALE_OBSERVATION'
      )
        throw error;
      deadline.requireActive();
      return this.observeAttempt(request, deadline);
    }
  }
  private async observeAttempt(
    request: BrowserObserveRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserObservation> {
    this.ready();
    const tab = this.tab(request.surfaceId);
    if (tab.readiness !== 'ready' || !tab.guardInstalled)
      throw new BrowserControlError('TAB_NOT_READY');
    let root: ElementHandle<Element> | null = null;
    if (request.root !== undefined) root = this.element(tab, request.root);
    // Subtree geometry is not the whole document geometry. Bind its bounded
    // structure between independent full-document state reads around capture.
    const globalBefore =
      root === null ? undefined : await this.state(tab, deadline);
    const before = await this.inspect(tab, deadline, root);
    try {
      const beforeState = globalBefore ?? this.stateFromScan(tab, before);
      const bytes = await this.rpc(
        () =>
          tab.page.screenshot({
            type: 'png',
            scale: 'css',
            fullPage: false,
            timeout: browserTimeout(deadline),
            signal: deadline.signal,
            caret: 'initial',
            animations: 'allow',
          }),
        deadline,
      );
      const after = await this.state(tab, deadline);
      if (!sameBrowserGuard(beforeState, after))
        throw new BrowserControlError('STALE_OBSERVATION');
      const viewport = tab.page.viewportSize();
      if (viewport === null)
        throw new BrowserControlError('OBSERVATION_FAILED');
      const observation = decodeBrowserObservation({
        image: {
          bytes,
          mimeType: 'image/png',
          width: viewport.width,
          height: viewport.height,
          transform: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
          capturedAt: new Date().toISOString(),
          layoutGeneration: after.layoutGeneration,
        },
        state: after,
        snapshot: request.structured
          ? { ...before.snapshot, guard: stateGuard(after) }
          : null,
      });
      const old = tab.snapshot;
      if (request.structured) tab.snapshot = before;
      else delete tab.snapshot;
      if (old !== undefined) await old.dispose(deadline);
      if (!request.structured) await before.dispose(deadline);
      return observation;
    } catch (error) {
      await before.dispose();
      throw error;
    }
  }
  private element(
    tab: Tab,
    target: { readonly snapshotId: string; readonly elementId: string },
  ): ElementHandle<Element> {
    const snapshot = tab.snapshot;
    const handle =
      snapshot?.snapshot.snapshotId === target.snapshotId
        ? snapshot.handles.get(target.elementId)
        : undefined;
    if (handle === undefined)
      throw new BrowserControlError('STALE_OBSERVATION');
    return handle;
  }
  private async resolve(
    tab: Tab,
    target: PreparedBrowserTarget,
    deadline: OperationDeadline,
  ): Promise<{
    handle: ElementHandle<Element>;
    temporary: boolean;
    point: { x: number; y: number };
    position: { x: number; y: number };
  }> {
    let handle: ElementHandle<Element>,
      temporary = false;
    const point = target.kind === 'point' ? { x: target.x, y: target.y } : null;
    if (target.kind === 'element') handle = this.element(tab, target);
    else {
      const viewport = tab.page.viewportSize();
      if (
        viewport === null ||
        target.x >= viewport.width ||
        target.y >= viewport.height
      )
        throw new BrowserControlError('INVALID_ARGUMENT');
      const selected = await this.rpc(
        () =>
          tab.page.evaluateHandle(
            ({ x, y }) => document.elementFromPoint(x, y),
            {
              x: target.x,
              y: target.y,
            },
          ),
        deadline,
      );
      const element = selected.asElement();
      if (element === null) {
        await this.rpc(() => selected.dispose(), deadline);
        throw new BrowserControlError('STALE_OBSERVATION');
      }
      handle = element;
      temporary = true;
    }
    try {
      const checked = await checkBrowserElement(handle, point, deadline);
      if (!checked.connected || !checked.hit || checked.bounds === null)
        throw new BrowserControlError('STALE_OBSERVATION');
      if (checked.frame) throw new BrowserControlError('UNSUPPORTED_FRAME');
      if (checked.sensitive) throw new BrowserControlError('SENSITIVE_TARGET');
      if (target.kind === 'element') {
        const old = tab.snapshot?.snapshot.nodes.find(
          (node) => node.elementId === target.elementId,
        )?.bounds;
        const currentBounds = checked.bounds;
        if (
          old === undefined ||
          old === null ||
          (['x', 'y', 'width', 'height'] as const).some(
            (key) => Math.abs(old[key] - currentBounds[key]) > 0.5,
          )
        )
          throw new BrowserControlError('STALE_OBSERVATION');
      }
      const resolved = point ?? {
        x: checked.bounds.x + checked.bounds.width / 2,
        y: checked.bounds.y + checked.bounds.height / 2,
      };
      return {
        handle,
        temporary,
        point: resolved,
        position: {
          x: resolved.x - checked.bounds.x - (checked.borderLeft ?? 0),
          y: resolved.y - checked.bounds.y - (checked.borderTop ?? 0),
        },
      };
    } catch (error) {
      if (temporary) await this.rpc(() => handle.dispose(), deadline);
      throw error;
    }
  }

  private onDialog(dialog: Dialog): void {
    const scope = this.dialogScope,
      anticipated = scope?.anticipated;
    if (scope === undefined || anticipated === undefined || scope.consumed) {
      if (scope !== undefined) scope.failure = 'UNEXPECTED_DIALOG';
      this.failStop('UNEXPECTED_DIALOG');
      return;
    }
    scope.consumed = true;
    if (
      dialog.type() !== anticipated.expectedType ||
      (anticipated.exactMessage !== undefined &&
        dialog.message() !== anticipated.exactMessage)
    ) {
      scope.failure = 'DIALOG_MISMATCH';
      this.failStop('DIALOG_MISMATCH');
      return;
    }
    scope.event = {
      type: dialog.type() as BrowserDialogType,
      message: dialog.message().slice(0, 1024),
      messageTruncated: dialog.message().length > 1024,
      decision: anticipated.decision,
    };
    const work = this.own(
      anticipated.decision === 'accept'
        ? dialog.accept(anticipated.promptText)
        : dialog.dismiss(),
    );
    scope.work.add(work);
    work.then(
      () => scope.work.delete(work),
      () => {
        scope.failure = 'INPUT_FAILED';
      },
    );
  }

  private async execute(
    tab: Tab,
    guard: BrowserStateGuard | undefined,
    reserve: number,
    decision: AnticipatedDialog | undefined,
    deadline: OperationDeadline,
    navigation: boolean,
    dispatch: (input: OperationDeadline) => Promise<BrowserDelivery>,
    action?: PreparedBrowserAction,
  ): Promise<BrowserActionResult> {
    let started = false;
    const scope: DialogScope = {
      anticipated: decision,
      consumed: false,
      event: null,
      failure: undefined,
      work: new Set(),
    };
    let input: OperationDeadline | undefined;
    try {
      this.ready();
      deadline.requireActive();
      if (deadline.remainingMs() <= reserve) return refused('DEADLINE_EXPIRED');
      if (guard !== undefined && this.activeSurface !== tab.surfaceId)
        return refused('FOCUS_MISMATCH');
      if (guard !== undefined) {
        const actual = await this.state(tab, deadline);
        if (navigation) requireBrowserNavigationState(actual, guard);
        else if (action !== undefined)
          requireBrowserActionState(actual, guard, action);
        else if (!sameBrowserGuard(actual, guard))
          throw new BrowserControlError('STALE_OBSERVATION');
      }
      if (action !== undefined) await this.preflight(tab, action, deadline);
      if (deadline.remainingMs() <= reserve) return refused('DEADLINE_EXPIRED');
      input = deadline.child(
        Math.max(1, Math.floor(deadline.remainingMs() - reserve)),
      );
      scope.cancel = () => input?.cancel();
      this.dialogScope = scope;
      // Dispatch closures perform their exact-node preflight before marking a
      // mutation. A rejected vendor RPC after this point is conservatively UNKNOWN.
      started = true;
      const delivery = await dispatch(input);
      await this.rpc(() => Promise.all([...scope.work]), input);
      if (scope.failure !== undefined || this.fatal !== undefined)
        throw new BrowserControlError(
          scope.failure ?? this.fatal ?? 'INPUT_FAILED',
        );
      this.dialogScope = undefined;
      let observation: BrowserActionResult['observation'];
      try {
        observation = {
          state: 'CAPTURED',
          value: await this.observe(
            { surfaceId: tab.surfaceId, structured: true },
            deadline,
          ),
        };
      } catch (error) {
        observation = {
          state: 'FAILED',
          code:
            error instanceof BrowserControlError
              ? error.code
              : 'OBSERVATION_FAILED',
        };
      }
      return { delivery, observation, dialog: scope.event };
    } catch (error) {
      const code = scope.failure ?? this.fatal ?? failure(error, deadline);
      if (!started) return refused(code);
      this.fatal ??= code;
      const cleanup = new OperationDeadline(
        this.config.cleanupBudgetMs ?? 2000,
      );
      let settled: BrowserCleanupResult;
      try {
        settled = await this.close(cleanup);
      } finally {
        cleanup.dispose();
      }
      return {
        delivery: {
          outcome: 'UNCERTAIN',
          physicalSettlement: settled.physicalSettlement,
          code,
        },
        observation: { state: 'FAILED', code: 'OBSERVATION_FAILED' },
        dialog: scope.event,
      };
    } finally {
      this.dialogScope = undefined;
      input?.dispose();
    }
  }
  async focus(
    request: BrowserFocusRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    if (this.windowsId !== request.windowsId)
      return refused('STALE_OBSERVATION');
    const tab = this.tab(request.surfaceId);
    if (!tab.guardInstalled) return refused('TAB_NOT_READY');
    return this.execute(
      tab,
      undefined,
      request.observationReserveMs,
      undefined,
      deadline,
      false,
      async (input) => {
        await this.rpc(() => tab.page.bringToFront(), input);
        this.activeSurface = tab.surfaceId;
        return {
          outcome: 'DISPATCHED',
          physicalSettlement: 'settled',
          mechanism: 'focus',
        };
      },
    );
  }
  navigate(
    request: BrowserNavigateRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    const tab = this.tab(request.expected.surfaceId);
    return this.execute(
      tab,
      request.expected,
      request.observationReserveMs,
      request.dialogDecision,
      deadline,
      true,
      async (input) => {
        tab.explicitNavigation = true;
        try {
          await this.rpc(
            () =>
              tab.page.goto(request.url, {
                waitUntil: 'domcontentloaded',
                timeout: browserTimeout(input),
                signal: input.signal,
              }),
            input,
          );
          permittedBrowserUrl(this.config.control, tab.page.url());
          tab.readiness = 'ready';
          return {
            outcome: 'DISPATCHED',
            physicalSettlement: 'settled',
            mechanism: 'goto',
          };
        } finally {
          tab.explicitNavigation = false;
        }
      },
    );
  }
  perform(
    request: BrowserPerformRequest,
    deadline: OperationDeadline,
  ): Promise<BrowserActionResult> {
    const tab = this.tab(request.expected.surfaceId);
    return this.execute(
      tab,
      request.expected,
      request.observationReserveMs,
      request.dialogDecision,
      deadline,
      false,
      (input) => this.input(tab, request.action, input),
      request.action,
    );
  }
  private async preflight(
    tab: Tab,
    action: PreparedBrowserAction,
    deadline: OperationDeadline,
  ): Promise<void> {
    if (
      action.kind === 'type' ||
      (action.kind === 'key' && action.expectedFocus !== null)
    ) {
      const target =
        action.kind === 'type' ? action.target : action.expectedFocus;
      if (target === null) throw new BrowserControlError('FOCUS_MISMATCH');
      const checked = await checkBrowserElement(
        this.element(tab, target),
        null,
        deadline,
      );
      if (!checked.connected)
        throw new BrowserControlError('STALE_OBSERVATION');
      if (checked.sensitive) throw new BrowserControlError('SENSITIVE_TARGET');
      if (!checked.focused) throw new BrowserControlError('FOCUS_MISMATCH');
      if (action.kind === 'type' && !checked.editable)
        throw new BrowserControlError('NOT_EDITABLE');
      return;
    }
    if (action.kind === 'key') {
      const body = await this.rpc(
        () =>
          tab.page.evaluate(
            () =>
              document.activeElement === document.body ||
              document.activeElement === document.documentElement,
          ),
        deadline,
      );
      if (!body) throw new BrowserControlError('FOCUS_MISMATCH');
      return;
    }
    if (action.kind === 'drag') {
      for (const point of action.points) {
        const target = await this.resolve(
          tab,
          { kind: 'point', x: point.x, y: point.y },
          deadline,
        );
        if (target.temporary)
          await this.rpc(() => target.handle.dispose(), deadline);
      }
      const last = action.points.at(-1);
      if (last === undefined || deadline.remainingMs() <= last.atMs)
        throw new BrowserControlError('DEADLINE_EXPIRED');
      return;
    }
    if (action.kind === 'scroll')
      browserScrollConversion(action, this.config.pixelsPerLine ?? 40);
    const target = await this.resolve(tab, action.target, deadline);
    if (target.temporary)
      await this.rpc(() => target.handle.dispose(), deadline);
  }
  private async modifiers(
    page: Page,
    names: readonly string[],
    deadline: OperationDeadline,
  ): Promise<void> {
    const held = this.heldKeys.get(page) ?? new Set<string>();
    this.heldKeys.set(page, held);
    for (const name of names) {
      held.add(name);
      await this.rpc(() => page.keyboard.down(name), deadline);
    }
  }
  private async releaseInputs(
    page: Page,
    deadline: OperationDeadline,
  ): Promise<void> {
    for (const button of this.heldButtons.get(page) ?? []) {
      await this.rpc(() => page.mouse.up({ button }), deadline);
      this.heldButtons.get(page)?.delete(button);
    }
    for (const key of this.heldKeys.get(page) ?? []) {
      await this.rpc(() => page.keyboard.up(key), deadline);
      this.heldKeys.get(page)?.delete(key);
    }
  }
  private async input(
    tab: Tab,
    action: PreparedBrowserAction,
    deadline: OperationDeadline,
  ): Promise<BrowserDelivery> {
    const page = tab.page;
    if (action.kind === 'type') {
      const handle = this.element(tab, action.target);
      const checked = await checkBrowserElement(handle, null, deadline);
      if (checked.sensitive) throw new BrowserControlError('SENSITIVE_TARGET');
      if (!checked.connected || !checked.editable)
        throw new BrowserControlError('NOT_EDITABLE');
      if (!checked.focused) throw new BrowserControlError('FOCUS_MISMATCH');
      if (action.mode === 'replace')
        await this.rpc(
          () =>
            handle.fill(action.text, {
              timeout: browserTimeout(deadline),
              signal: deadline.signal,
            }),
          deadline,
        );
      else
        await this.rpc(() => page.keyboard.insertText(action.text), deadline);
      return {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: action.mode === 'replace' ? 'fill' : 'insertText',
      };
    }
    if (action.kind === 'key') {
      if (action.expectedFocus === null) {
        const body = await this.rpc(
          () =>
            page.evaluate(
              () =>
                document.activeElement === document.body ||
                document.activeElement === document.documentElement,
            ),
          deadline,
        );
        if (!body) throw new BrowserControlError('FOCUS_MISMATCH');
      } else {
        const handle = this.element(tab, action.expectedFocus);
        const checked = await checkBrowserElement(handle, null, deadline);
        if (checked.sensitive)
          throw new BrowserControlError('SENSITIVE_TARGET');
        if (!checked.focused) throw new BrowserControlError('FOCUS_MISMATCH');
      }
      const hold = deadline.child(
        Math.min(1000, Math.max(1, Math.floor(deadline.remainingMs()))),
      );
      try {
        await this.modifiers(page, action.modifiers, hold);
        const key = action.key === 'Space' ? ' ' : action.key;
        await this.modifiers(page, [key], hold);
        await this.releaseInputs(page, hold);
      } finally {
        hold.dispose();
      }
      return {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'keyboard',
      };
    }
    if (action.kind === 'drag') {
      const first = action.points[0];
      if (first === undefined)
        throw new BrowserControlError('INVALID_ARGUMENT');
      const start = await this.resolve(
        tab,
        { kind: 'point', x: first.x, y: first.y },
        deadline,
      );
      if (start.temporary)
        await this.rpc(() => start.handle.dispose(), deadline);
      const hold = deadline.child(
        Math.min(1000, Math.max(1, Math.floor(deadline.remainingMs()))),
      );
      try {
        await this.modifiers(page, action.modifiers, hold);
        await this.rpc(() => page.mouse.move(first.x, first.y), hold);
        const held =
          this.heldButtons.get(page) ?? new Set<'left' | 'middle' | 'right'>();
        this.heldButtons.set(page, held);
        held.add(action.button);
        await this.rpc(() => page.mouse.down({ button: action.button }), hold);
        const began = performance.now();
        for (const point of action.points.slice(1)) {
          const delay = Math.max(0, point.atMs - (performance.now() - began));
          if (delay > 0)
            await this.rpc(
              () => wait(delay, undefined, { signal: hold.signal }),
              hold,
            );
          // Never drag through an unowned frame, including a frame introduced mid-drag.
          const at = await this.resolve(
            tab,
            { kind: 'point', x: point.x, y: point.y },
            hold,
          );
          if (at.temporary) await this.rpc(() => at.handle.dispose(), hold);
          await this.rpc(() => page.mouse.move(point.x, point.y), hold);
        }
        await this.releaseInputs(page, hold);
      } finally {
        hold.dispose();
      }
      return {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'mouse',
      };
    }
    const target = await this.resolve(tab, action.target, deadline);
    try {
      if (action.kind === 'move')
        await this.rpc(
          () => page.mouse.move(target.point.x, target.point.y),
          deadline,
        );
      else if (action.kind === 'scroll') {
        const scroll = browserScrollConversion(
          action,
          this.config.pixelsPerLine ?? 40,
        );
        await this.rpc(
          () => page.mouse.move(target.point.x, target.point.y),
          deadline,
        );
        await this.rpc(() => page.mouse.wheel(scroll.dx, scroll.dy), deadline);
        return {
          outcome: 'DISPATCHED',
          physicalSettlement: 'settled',
          mechanism: 'wheel',
          scroll,
        };
      } else {
        // Exact handle: no selector retry can silently target a replacement node.
        const hold = deadline.child(
          Math.min(1000, Math.max(1, Math.floor(deadline.remainingMs()))),
        );
        try {
          await this.modifiers(page, action.modifiers, hold);
          await this.rpc(
            () =>
              target.handle.click({
                button: action.button,
                clickCount: action.kind === 'double_click' ? 2 : 1,
                force: false,
                scroll: 'none',
                timeout: browserTimeout(hold),
                signal: hold.signal,
                ...(action.target.kind === 'point'
                  ? { position: target.position }
                  : {}),
              }),
            hold,
          );
          await this.releaseInputs(page, hold);
        } finally {
          hold.dispose();
        }
      }
      return {
        outcome: 'DISPATCHED',
        physicalSettlement: 'settled',
        mechanism: 'mouse',
      };
    } finally {
      if (target.temporary)
        await this.rpc(() => target.handle.dispose(), deadline);
    }
  }
  close(deadline: OperationDeadline): Promise<BrowserCleanupResult> {
    if (this.closed)
      return Promise.resolve({
        physicalSettlement: 'settled',
        heldInputsReleased: true,
      });
    if (this.closeWork !== undefined) return this.closeWork;
    deadline.requireActive();
    this.closing = true;
    this.closeWork = (async () => {
      try {
        // Release only keys/buttons entered in our ownership sets. A pending
        // modal can prevent acknowledgements; closing the isolated context then
        // establishes that its input domain no longer exists.
        for (const page of this.pages.keys()) {
          if (!page.isClosed()) {
            const attempt = deadline.child(
              Math.min(100, Math.max(1, Math.floor(deadline.remainingMs()))),
            );
            try {
              await this.releaseInputs(page, attempt);
            } catch {
              this.fatal ??= 'CLEANUP_UNCERTAIN';
            } finally {
              attempt.dispose();
            }
          }
        }
        deadline.requireActive();
        await awaitBrowserWork(
          this.own(
            this.context.close({ reason: 'Computer control lease ended' }),
          ),
          deadline,
        );
        await awaitBrowserWork(
          Promise.allSettled([...this.pending, ...this.eventWork]),
          deadline,
        );
        for (const tab of this.tabs.values()) {
          if (tab.snapshot !== undefined) await tab.snapshot.dispose(deadline);
        }
        this.closed = true;
        this.heldKeys.clear();
        this.heldButtons.clear();
        return {
          physicalSettlement: 'settled' as const,
          heldInputsReleased: true,
        };
      } catch {
        return {
          physicalSettlement: 'uncertain' as const,
          heldInputsReleased: false,
        };
      }
    })();
    return this.closeWork;
  }
}

export async function launchBrowserControlDriver(
  config: BrowserControlBackendConfig,
  deadline: OperationDeadline,
): Promise<BrowserControlDriver> {
  deadline.requireActive();
  const browser: Browser = await chromium.launch({
    executablePath: config.executablePath,
    headless: config.headless,
    env: {},
    timeout: browserTimeout(deadline),
  });
  const lateCleanup = new Set<Promise<void>>();
  let driverClosed = false;
  const closeLate = (context: BrowserContext): void => {
    const cleanup = new OperationDeadline(config.cleanupBudgetMs ?? 2000);
    const work = awaitBrowserWork(
      context.close({ reason: 'Expired computer context creation' }),
      cleanup,
    );
    lateCleanup.add(work);
    work.then(
      () => {
        lateCleanup.delete(work);
        cleanup.dispose();
      },
      () => {
        cleanup.dispose();
      },
    );
  };
  return {
    async open(target, operation) {
      operation.requireActive();
      let controlled: PlaywrightControlledContext | undefined;
      let rawContext: BrowserContext | undefined;
      let failed = false;
      try {
        if (driverClosed) throw new BrowserControlError('TARGET_GONE');
        const context = await awaitBrowserWork(
          browser
            .newContext({
              viewport: target.viewport,
              deviceScaleFactor: 1,
              acceptDownloads: false,
              serviceWorkers: 'block',
              permissions: [],
            })
            .then((context) => {
              rawContext = context;
              if (failed || driverClosed || operation.signal.aborted) {
                closeLate(context);
                throw new BackendError('DEADLINE_EXPIRED', 'uncertain');
              }
              return context;
            }),
          operation,
        );
        controlled = new PlaywrightControlledContext(context, config);
        await controlled.initialize(target, operation);
        return controlled;
      } catch {
        failed = true;
        const cleanup = new OperationDeadline(config.cleanupBudgetMs ?? 2000);
        try {
          if (controlled !== undefined) await controlled.close(cleanup);
          else if (rawContext !== undefined)
            await awaitBrowserWork(rawContext.close(), cleanup);
        } finally {
          cleanup.dispose();
        }
        throw new BackendError('CAPTURE_FAILED', 'uncertain');
      }
    },
    async close(operation) {
      operation.requireActive();
      driverClosed = true;
      await awaitBrowserWork(browser.close(), operation);
      await awaitBrowserWork(Promise.allSettled([...lateCleanup]), operation);
    },
  };
}
