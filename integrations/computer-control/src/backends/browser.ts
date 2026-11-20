import { createHash } from 'node:crypto';
import { isAbsolute } from 'node:path';
import { chromium } from 'playwright';
import type { Browser, Page } from 'playwright';
import {
  BackendError,
  computerId,
  decodeCapturedImage,
  decodeCaptureRequest,
  decodeTargets,
} from '../contracts.ts';
import type {
  CapturedImage,
  CaptureRequest,
  ComputerBackend,
  ComputerTarget,
} from '../contracts.ts';
import {
  DeadlineExceeded,
  OperationDeadline,
  positiveBudget,
} from '../deadline.ts';
import type { BackendHandle } from './types.ts';

export interface BrowserTargetConfig {
  readonly targetId: string;
  readonly label: string;
  readonly url: string;
  readonly viewport: { readonly width: number; readonly height: number };
}
export interface BrowserBackendConfig {
  readonly executablePath: string;
  readonly headless: boolean;
  readonly cleanupBudgetMs?: number;
  readonly targets: readonly BrowserTargetConfig[];
}
export interface BrowserPixels {
  readonly bytes: Uint8Array;
  readonly width: number;
  readonly height: number;
  readonly capturedAt: string;
  readonly layoutGeneration: string;
}

/** A testable browser boundary; vendor Page/Browser objects do not escape this implementation. */
export interface BrowserCaptureDriver {
  capture(
    targetId: string,
    deadline: OperationDeadline,
  ): Promise<BrowserPixels>;
  close(deadline: OperationDeadline): Promise<void>;
}
export type BrowserDriverFactory = (
  config: BrowserBackendConfig,
  deadline: OperationDeadline,
) => Promise<BrowserCaptureDriver>;

/**
 * Bounds a browser RPC without claiming a caller abort physically stopped it.
 * Startup owns the browser and enters bounded close on failure; Promise.race
 * retains a rejection observer on an RPC that settles after the caller expires.
 */
export async function browserStartupStep<T>(
  work: Promise<T>,
  deadline: OperationDeadline,
): Promise<T> {
  deadline.requireActive();
  let detach = () => {};
  const expired = new Promise<never>((_resolve, reject) => {
    const abort = () =>
      reject(new BackendError('DEADLINE_EXPIRED', 'uncertain'));
    deadline.signal.addEventListener('abort', abort, { once: true });
    detach = () => deadline.signal.removeEventListener('abort', abort);
    if (deadline.signal.aborted) abort();
  });
  try {
    const result = await Promise.race([work, expired]);
    deadline.requireActive();
    return result;
  } finally {
    detach();
  }
}

function object(
  value: unknown,
  allowed: readonly string[],
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid browser configuration');
  const row = value as Readonly<Record<string, unknown>>;
  if (Object.keys(row).some((key) => !allowed.includes(key)))
    throw new Error('Unknown browser configuration field');
  return row;
}

/** No executable/profile/address fallback. Navigation is configured host work, never model input. */
export function decodeBrowserBackendConfig(
  value: unknown,
): BrowserBackendConfig {
  const row = object(value, [
    'executablePath',
    'headless',
    'cleanupBudgetMs',
    'targets',
  ]);
  const executablePath = row['executablePath'];
  if (
    typeof executablePath !== 'string' ||
    !isAbsolute(executablePath) ||
    Array.from(executablePath).some(
      (character) => (character.codePointAt(0) ?? 0) < 32,
    )
  )
    throw new Error('Browser executablePath must be an explicit absolute path');
  if (typeof row['headless'] !== 'boolean')
    throw new Error('Browser headless must be explicit');
  const cleanupBudgetMs = row['cleanupBudgetMs'] ?? 2000;
  if (typeof cleanupBudgetMs !== 'number')
    throw new Error('Invalid browser cleanup budget');
  positiveBudget(cleanupBudgetMs);
  const rawTargets = row['targets'];
  if (
    !Array.isArray(rawTargets) ||
    rawTargets.length < 1 ||
    rawTargets.length > 32
  )
    throw new Error('Browser requires 1..32 configured targets');
  const targets = rawTargets.map((value: unknown): BrowserTargetConfig => {
    const target = object(value, ['targetId', 'label', 'url', 'viewport']);
    const viewport = object(target['viewport'], ['width', 'height']);
    const width = viewport['width'],
      height = viewport['height'];
    if (
      typeof width !== 'number' ||
      !Number.isSafeInteger(width) ||
      width < 1 ||
      width > 1600 ||
      typeof height !== 'number' ||
      !Number.isSafeInteger(height) ||
      height < 1 ||
      height > 1600
    )
      throw new Error('Browser viewport must fit the 1600-pixel capture bound');
    if (typeof target['url'] !== 'string' || target['url'].length > 4096)
      throw new Error('Browser target requires an explicit HTTP(S) URL');
    const url = new URL(target['url']);
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password
    )
      throw new Error(
        'Browser target requires an explicit HTTP(S) URL without credentials',
      );
    return Object.freeze({
      targetId: computerId(target['targetId']),
      label: computerId(target['label']),
      url: url.href,
      viewport: Object.freeze({ width, height }),
    });
  });
  if (new Set(targets.map((target) => target.targetId)).size !== targets.length)
    throw new Error('Duplicate browser target');
  return Object.freeze({
    executablePath,
    headless: row['headless'],
    cleanupBudgetMs,
    targets: Object.freeze(targets),
  });
}

function failure(error: unknown, deadline: OperationDeadline): BackendError {
  if (error instanceof BackendError) return error;
  return new BackendError(
    error instanceof DeadlineExceeded || deadline.signal.aborted
      ? 'DEADLINE_EXPIRED'
      : 'CAPTURE_FAILED',
  );
}

class BrowserBackend implements ComputerBackend {
  private closed = false;
  private readonly busy = new Set<string>();
  private readonly targetList: readonly ComputerTarget[];

  constructor(
    private readonly config: BrowserBackendConfig,
    private readonly driver: BrowserCaptureDriver,
  ) {
    this.targetList = decodeTargets(
      config.targets.map((target) => ({
        targetId: target.targetId,
        backend: 'browser',
        label: target.label,
        surfaces: [{ id: 'viewport', label: 'Browser viewport' }],
        capabilities: ['capture'],
      })),
    );
  }

  targets(deadline: OperationDeadline): Promise<readonly ComputerTarget[]> {
    deadline.requireActive();
    if (this.closed) return Promise.reject(new BackendError('TARGET_GONE'));
    return Promise.resolve(this.targetList);
  }

  async capture(
    value: CaptureRequest,
    deadline: OperationDeadline,
  ): Promise<CapturedImage> {
    deadline.requireActive();
    const request = decodeCaptureRequest(value);
    const target = this.config.targets.find(
      (item) => item.targetId === request.targetId,
    );
    if (this.closed || target === undefined || request.surfaceId !== 'viewport')
      throw new BackendError('TARGET_GONE');
    if (this.busy.has(target.targetId))
      throw new BackendError('CAPTURE_FAILED');
    this.busy.add(target.targetId);
    try {
      // Keep ownership until the driver's physical promise settles, even if the caller stops waiting.
      const pixels = await this.driver.capture(target.targetId, deadline);
      deadline.requireActive();
      if (
        pixels.width !== target.viewport.width ||
        pixels.height !== target.viewport.height
      )
        throw new BackendError('CAPTURE_FAILED');
      return decodeCapturedImage({
        ...pixels,
        mimeType: 'image/png',
        transform: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      });
    } catch (error) {
      if (deadline.signal.aborted) this.closed = true;
      throw failure(error, deadline);
    } finally {
      this.busy.delete(target.targetId);
    }
  }

  async close(deadline: OperationDeadline): Promise<void> {
    this.closed = true;
    await this.driver.close(deadline);
  }
}

/** A timed-out close remains owned and handled; the backend is permanently closed, never reused. */
async function closeBrowser(
  browser: Browser,
  deadline: OperationDeadline,
): Promise<void> {
  deadline.requireActive();
  let detach = () => {};
  const expired = new Promise<never>((_resolve, reject) => {
    const abort = () =>
      reject(new BackendError('DEADLINE_EXPIRED', 'uncertain'));
    deadline.signal.addEventListener('abort', abort, { once: true });
    detach = () => deadline.signal.removeEventListener('abort', abort);
    if (deadline.signal.aborted) abort();
  });
  try {
    // Promise.race owns the close rejection after expiry; it never reopens browser capacity.
    await Promise.race([browser.close(), expired]);
    deadline.requireActive();
  } finally {
    detach();
  }
}

async function launchPlaywright(
  config: BrowserBackendConfig,
  deadline: OperationDeadline,
): Promise<BrowserCaptureDriver> {
  deadline.requireActive();
  let browser: Browser;
  try {
    browser = await chromium.launch({
      executablePath: config.executablePath,
      headless: config.headless,
      timeout: Math.max(1, Math.floor(deadline.remainingMs())),
    });
  } catch (error) {
    throw failure(error, deadline);
  }
  const pages = new Map<string, Page>();
  // Closing the owned browser aborts startup work for APIs without a per-call AbortSignal.
  let abortClose: Promise<void> | undefined;
  let abortFailed = false;
  const abort = () => {
    // Preserve failed cancellation as a startup failure, rather than an unhandled rejection.
    abortClose ??= browser.close().catch(() => {
      abortFailed = true;
    });
  };
  deadline.signal.addEventListener('abort', abort, { once: true });
  try {
    for (const target of config.targets) {
      deadline.requireActive();
      const context = await browserStartupStep(
        browser.newContext({
          viewport: target.viewport,
          deviceScaleFactor: 1,
          acceptDownloads: false,
        }),
        deadline,
      );
      deadline.requireActive();
      const page = await browserStartupStep(context.newPage(), deadline);
      deadline.requireActive();
      await page.goto(target.url, {
        timeout: Math.max(1, Math.floor(deadline.remainingMs())),
        waitUntil: 'domcontentloaded',
        signal: deadline.signal,
      });
      deadline.requireActive();
      pages.set(target.targetId, page);
    }
    if (abortFailed) throw new BackendError('CAPTURE_FAILED');
  } catch (error) {
    const cleanup = new OperationDeadline(config.cleanupBudgetMs ?? 2000);
    try {
      await closeBrowser(browser, cleanup);
    } finally {
      cleanup.dispose();
    }
    throw failure(error, deadline);
  } finally {
    deadline.signal.removeEventListener('abort', abort);
  }
  return {
    async capture(targetId, operation) {
      operation.requireActive();
      const page = pages.get(targetId);
      if (page === undefined || page.isClosed())
        throw new BackendError('TARGET_GONE');
      const before = page.viewportSize();
      if (before === null) throw new BackendError('CAPTURE_FAILED');
      const bytes = await page.screenshot({
        type: 'png',
        fullPage: false,
        scale: 'css',
        caret: 'initial',
        animations: 'allow',
        timeout: Math.max(1, Math.floor(operation.remainingMs())),
        signal: operation.signal,
      });
      operation.requireActive();
      const after = page.viewportSize();
      if (after?.width !== before.width || after.height !== before.height)
        throw new BackendError('CAPTURE_FAILED');
      return {
        bytes,
        width: before.width,
        height: before.height,
        capturedAt: new Date().toISOString(),
        layoutGeneration: createHash('sha256')
          .update(`${targetId}:${page.url()}:${before.width}:${before.height}`)
          .digest('hex'),
      };
    },
    close: (operation) => closeBrowser(browser, operation),
  };
}

export async function createBrowserBackend(
  value: BrowserBackendConfig,
  deadline: OperationDeadline,
  factory: BrowserDriverFactory = launchPlaywright,
): Promise<BackendHandle> {
  const config = decodeBrowserBackendConfig(value);
  deadline.requireActive();
  let created: BrowserCaptureDriver | undefined;
  let abandoned = false;
  let closing: Promise<void> | undefined;
  const closeCreated = (driver: BrowserCaptureDriver): Promise<void> => {
    closing ??= (async () => {
      const cleanup = new OperationDeadline(config.cleanupBudgetMs ?? 2000);
      try {
        await browserStartupStep(driver.close(cleanup), cleanup);
      } finally {
        cleanup.dispose();
      }
    })();
    return closing;
  };
  const startup = Promise.resolve()
    .then(() => {
      deadline.requireActive();
      return factory(config, deadline);
    })
    .then(async (driver) => {
      created = driver;
      if (abandoned) {
        // This chain stays owned by browserStartupStep's race after expiry. A late
        // driver is closed, never published or treated as automatically recovered.
        await closeCreated(driver);
        throw new BackendError('DEADLINE_EXPIRED', 'uncertain');
      }
      return driver;
    });
  let driver: BrowserCaptureDriver;
  try {
    driver = await browserStartupStep(startup, deadline);
    deadline.requireActive();
  } catch (error) {
    abandoned = true;
    if (created !== undefined) await closeCreated(created);
    throw failure(error, deadline);
  }
  const backend = new BrowserBackend(config, driver);
  return { backend, close: (operation) => backend.close(operation) };
}
