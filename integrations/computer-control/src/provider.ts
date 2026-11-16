import { randomUUID } from 'node:crypto';
import {
  admitToolImage,
  imageToolResult,
  toolTextPart,
} from 'plowshare-client-node';
import {
  ToolProvider,
  nodeToolHost,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import { decodeToolCall, decodeToolResult } from 'plowshare-client-ts';
import type {
  Plowshare,
  RegisteredTool,
  ToolBinding,
  ToolCall,
  ToolDeclaration,
  ToolHost,
  ToolJournal,
  ToolResult,
} from 'plowshare-client-ts';
import type {
  ComputerBackend,
  ComputerOwner,
  ComputerTarget,
  CaptureLimits,
} from './contracts.ts';
import {
  BackendError,
  computerId,
  decodeCaptureRequest,
  decodeCapturedImage,
  decodeCloseArguments,
  decodeObserveArguments,
  decodeOpenArguments,
  decodeTargets,
  decodeTargetsArguments,
  DEFAULT_CAPTURE_LIMITS,
  validateCaptureLimits,
} from './contracts.ts';
import type { Clock, OperationDeadline } from './deadline.ts';
import {
  DeadlineExceeded,
  fromCallDeadline,
  positiveBudget,
  systemClock,
} from './deadline.ts';
import { CapacityToolJournal } from './journal.ts';
import { ComputerSessions, SessionError } from './sessions.ts';

export interface ComputerProviderOptions {
  readonly connection: Pick<Plowshare, 'request'>;
  readonly binding: ToolBinding;
  readonly journal: ToolJournal;
  readonly backends: readonly ComputerBackend[];
  readonly generation: string;
  readonly origin: string;
  readonly currentBearer: () => Promise<string>;
  readonly http?: typeof fetch;
  readonly clock?: Clock;
  readonly prefix?: string;
  readonly controlBudgetMs?: number;
  readonly observeBudgetMs?: number;
  readonly captureLimits?: CaptureLimits;
  readonly maxImagesPerRun?: number;
  /** Owning host pass shares its earlier monotonic cutoff with local capture and binary admission. */
  readonly parentDeadline?: () => OperationDeadline | undefined;
}
export interface ComputerProvider {
  readonly provider: ToolProvider;
  /** SDK registrations for inspection/composition, not a client execution API; use provider.poll(). */
  readonly tools: readonly RegisteredTool[];
  readonly declarations: readonly ToolDeclaration[];
  readonly sessions: ComputerSessions;
  readonly journal: CapacityToolJournal;
  readonly journalIdentity: string;
  /** Stop intake and request cancellation; drain/host cleanup still owns physical settlement. */
  stop(): void;
  /** Settles owned JS handlers only. Host must separately close backend handles and honor fences. */
  drain(deadline: OperationDeadline): Promise<void>;
}
export interface ProviderBudgets {
  readonly controlBudgetMs: number;
  readonly observeBudgetMs: number;
}

function checkedPrefix(prefix: string): string {
  if (!/^[a-z][a-z0-9_]*_$/u.test(prefix) || prefix.length > 200)
    throw new Error('Computer tool prefix must end in underscore');
  return prefix;
}

/** Stable declarations allow a host to derive the SDK journal identity before opening the store. */
export function computerDeclarations(
  prefix = 'computer_',
  budgets: ProviderBudgets = { controlBudgetMs: 2000, observeBudgetMs: 10_000 },
): readonly ToolDeclaration[] {
  checkedPrefix(prefix);
  positiveBudget(budgets.controlBudgetMs);
  positiveBudget(budgets.observeBudgetMs);
  const parameter = (name: string) => ({
    name,
    type: 'STRING' as const,
    description: name,
    required: true,
  });
  const specs = [
    {
      suffix: 'targets',
      description:
        'List explicitly configured computer targets and capabilities.',
      fields: [],
    },
    {
      suffix: 'open',
      description:
        'Acquire an owner-bound observation session for a configured target.',
      fields: ['targetId'],
    },
    {
      suffix: 'observe',
      description:
        'Capture a fresh image from a selected session surface for model observation.',
      fields: ['sessionId', 'generation', 'surfaceId'],
    },
    {
      suffix: 'close',
      description:
        'Release an idle observation session; uncertain captures remain fenced.',
      fields: ['sessionId', 'generation'],
    },
  ];
  return Object.freeze(
    specs.map((spec): ToolDeclaration =>
      Object.freeze({
        name: prefix + spec.suffix,
        description: spec.description,
        parameters: Object.freeze(spec.fields.map(parameter)),
        resultProfile: 'content/1',
        timeoutSeconds: Math.ceil(
          (spec.suffix === 'observe'
            ? budgets.observeBudgetMs
            : budgets.controlBudgetMs) / 1000,
        ),
      }),
    ),
  );
}

function checkedOrigin(origin: string): string {
  const value = new URL(origin);
  if (
    !['https:', 'http:'].includes(value.protocol) ||
    value.username ||
    value.password ||
    value.pathname !== '/' ||
    value.search ||
    value.hash
  )
    throw new Error('Computer provider requires an explicit server origin');
  return value.origin;
}

function owner(call: ToolCall): ComputerOwner {
  return {
    account: call.account,
    project: call.project,
    run: call.run,
    provider: call.provider,
  };
}

function validatedResult(
  call: ToolCall,
  result: ToolResult,
  producer: string,
): ToolResult {
  const checked = decodeToolResult(result, call, producer);
  const wire = JSON.stringify({
    schema: 'plowshare-tool/3',
    invocationId: call.invocationId,
    project: call.project,
    provider: call.provider,
    tool: call.tool,
    state: checked.state,
    content: checked.content,
  });
  if (wire.length > 32768)
    throw new Error('Computer result exceeds the encoded envelope limit');
  return checked;
}

function textResult(
  call: ToolCall,
  producer: string,
  metadata: object,
  state: ToolResult['state'] = 'COMPLETED',
): ToolResult {
  const text = JSON.stringify(metadata);
  if (text.length > 12000)
    throw new Error('Computer metadata exceeds its bound');
  return validatedResult(call, { state, text }, producer);
}

async function abortable<T>(
  work: Promise<T>,
  deadline: OperationDeadline,
): Promise<T> {
  let expired: (() => void) | undefined;
  const aborted = new Promise<never>((_resolve, reject) => {
    expired = () => reject(new DeadlineExceeded());
    deadline.signal.addEventListener('abort', expired, { once: true });
    if (deadline.signal.aborted) expired();
  });
  try {
    // Register both outcomes even when work synchronously exhausted the shared monotonic budget.
    const raced = Promise.race([work, aborted]);
    if (deadline.remainingMs() <= 0) deadline.cancel();
    return await raced;
  } finally {
    if (expired !== undefined)
      deadline.signal.removeEventListener('abort', expired);
  }
}

/**
 * Embedding seam for TUI/GUI and standalone SDK hosts. The caller supplies its existing authenticated
 * connection and private, exclusively locked journal. This function never logs in or starts a backend.
 */
export async function createComputerProvider(
  options: ComputerProviderOptions,
): Promise<ComputerProvider> {
  const origin = checkedOrigin(options.origin);
  const prefix = checkedPrefix(options.prefix ?? 'computer_');
  const controlBudgetMs = positiveBudget(options.controlBudgetMs ?? 2000);
  const observeBudgetMs = positiveBudget(options.observeBudgetMs ?? 10_000);
  const captureLimits = validateCaptureLimits(
    options.captureLimits ?? DEFAULT_CAPTURE_LIMITS,
  );
  computerId(options.generation);
  if (options.backends.length < 1 || options.backends.length > 32)
    throw new Error('Computer provider requires 1..32 configured backends');
  // The public image-admission API currently accepts ordinary projects only.
  if (computerId(options.binding.project).toLowerCase().startsWith('personal:'))
    throw new Error(
      'Computer image observations require an ordinary explicit project',
    );
  const binding = Object.freeze({ ...options.binding });
  const backends = Object.freeze([...options.backends]);
  const clock = options.clock ?? systemClock;
  const http = options.http ?? fetch;
  const declarations = computerDeclarations(prefix, {
    controlBudgetMs,
    observeBudgetMs,
  });
  const journalIdentity = await nodeToolHost.sha256(
    toolDeploymentConfig(binding, declarations),
  );
  if (options.journal.identity !== journalIdentity)
    throw new Error('Foreign computer tool journal');
  const journal = await CapacityToolJournal.create(options.journal);
  const sessions = new ComputerSessions({
    generation: options.generation,
    id: randomUUID,
    clock,
    captureLimits,
    ...(options.maxImagesPerRun === undefined
      ? {}
      : { maxImagesPerRun: options.maxImagesPerRun }),
  });
  const restored = new Map<
    string,
    { readonly owner: ComputerOwner; count: number }
  >();
  for (const receipt of await journal.all()) {
    const call = decodeToolCall(receipt.request);
    if (
      call.project !== binding.project ||
      call.provider !== binding.provider ||
      !declarations.some((declaration) => declaration.name === call.tool)
    )
      throw new Error('Foreign computer invocation history');
    if (call.tool === prefix + 'observe') {
      const invocationOwner = owner(call),
        key = JSON.stringify(invocationOwner);
      const previous = restored.get(key);
      restored.set(key, {
        owner: invocationOwner,
        count: (previous?.count ?? 0) + 1,
      });
    }
  }
  for (const row of restored.values())
    sessions.restoreImageCount(row.owner, row.count);
  let active = true;
  const activeDeadlines = new Map<string, OperationDeadline>();
  const physical = new Set<Promise<ToolResult>>();
  interface SelectedTarget {
    readonly target: ComputerTarget;
    readonly backend: ComputerBackend;
  }
  // Ownership is pinned, not a permission cache. Every selected backend is rechecked before use.
  const knownTargets = new Map<string, SelectedTarget>();

  const remember = (row: SelectedTarget) => {
    const previous = knownTargets.get(row.target.targetId);
    if (previous !== undefined && previous.backend !== row.backend)
      throw new Error('Computer target ownership changed between backends');
    if (previous === undefined && knownTargets.size >= 32)
      throw new Error('Computer target discovery exceeds its configured bound');
    knownTargets.set(row.target.targetId, row);
  };

  const discover = async (deadline: OperationDeadline) => {
    const rows: SelectedTarget[] = [];
    const unavailable: {
      readonly backendIndex: number;
      readonly code: string;
    }[] = [];
    for (const [backendIndex, backend] of backends.entries()) {
      deadline.requireActive();
      let targets: readonly ComputerTarget[];
      try {
        targets = decodeTargets(await backend.targets(deadline));
      } catch (error) {
        // Backend permissions/availability are independent. Do not turn a native denial into a
        // browser denial, or reuse the denied backend's earlier target snapshot as current access.
        deadline.requireActive();
        unavailable.push({
          backendIndex,
          code: error instanceof BackendError ? error.code : 'CAPTURE_FAILED',
        });
        continue;
      }
      for (const target of targets) {
        const row = { target, backend };
        remember(row);
        rows.push(row);
      }
    }
    deadline.requireActive();
    decodeTargets(rows.map((row) => row.target));
    return { rows, unavailable };
  };

  const selected = async (
    targetId: string,
    deadline: OperationDeadline,
  ): Promise<SelectedTarget | undefined> => {
    const previous = knownTargets.get(targetId);
    if (previous === undefined)
      return (await discover(deadline)).rows.find(
        (row) => row.target.targetId === targetId,
      );
    // Current backend permission and surface availability are a freshness reason for this read;
    // unrelated backends are neither queried nor allowed to reinterpret the selected target.
    const target = decodeTargets(await previous.backend.targets(deadline)).find(
      (target) => target.targetId === targetId,
    );
    deadline.requireActive();
    if (target === undefined) return undefined;
    const row = { target, backend: previous.backend };
    remember(row);
    return row;
  };

  const handle = async (call: ToolCall): Promise<ToolResult> => {
    const inherited = activeDeadlines.get(call.invocationId);
    const deadline =
      inherited ??
      fromCallDeadline(
        call.deadline,
        call.tool === prefix + 'observe' ? observeBudgetMs : controlBudgetMs,
        clock,
        options.parentDeadline?.(),
      );
    let captureStarted = false;
    try {
      deadline.requireActive();
      if (!active)
        return textResult(
          call,
          binding.account,
          { version: 'computer-control/1', code: 'STOPPED' },
          'REJECTED',
        );
      if (
        call.project !== binding.project ||
        call.provider !== binding.provider ||
        call.schema !== 'plowshare-tool/3' ||
        call.resultProfile !== 'content/1'
      )
        throw new Error('Foreign or unnegotiated computer invocation');
      const invocationOwner = owner(call);
      if (call.tool === prefix + 'targets') {
        decodeTargetsArguments(call.arguments);
        const discovery = await discover(deadline);
        const targets = discovery.rows.map((row) => row.target);
        let visible = targets.length;
        while (
          visible > 0 &&
          JSON.stringify({
            version: 'computer-control/1',
            targets: targets.slice(0, visible),
            unavailable: discovery.unavailable,
            truncated: visible !== targets.length,
          }).length > 12000
        )
          visible--;
        return textResult(call, binding.account, {
          version: 'computer-control/1',
          targets: targets.slice(0, visible),
          unavailable: discovery.unavailable,
          truncated: visible !== targets.length,
        });
      }
      if (call.tool === prefix + 'open') {
        const args = decodeOpenArguments(call.arguments);
        const target = await selected(args.targetId, deadline);
        if (
          target === undefined ||
          !target.target.capabilities.includes('capture')
        )
          throw new BackendError('TARGET_GONE');
        return textResult(call, binding.account, {
          version: 'computer-control/1',
          session: sessions.open(invocationOwner, args.targetId),
        });
      }
      if (call.tool === prefix + 'close') {
        const args = decodeCloseArguments(call.arguments);
        sessions.close(invocationOwner, args);
        return textResult(call, binding.account, {
          version: 'computer-control/1',
          closed: true,
        });
      }
      if (call.tool !== prefix + 'observe')
        throw new Error('Unknown computer tool');
      const args = decodeObserveArguments(call.arguments);
      return await sessions.withSession(
        invocationOwner,
        args,
        deadline,
        async (session) => {
          const target = await selected(session.targetId, deadline);
          if (
            target === undefined ||
            !target.target.capabilities.includes('capture') ||
            !target.target.surfaces.some(
              (surface) => surface.id === args.surfaceId,
            )
          )
            throw new BackendError('TARGET_GONE');
          const bearer = await options.currentBearer();
          if (!bearer.trim() || /[\r\n]/u.test(bearer))
            throw new Error(
              'Computer image admission credential is unavailable',
            );
          deadline.requireActive();
          sessions.reserveImage(invocationOwner);
          captureStarted = true;
          const image = decodeCapturedImage(
            await target.backend.capture(
              decodeCaptureRequest({
                targetId: session.targetId,
                surfaceId: args.surfaceId,
              }),
              deadline,
            ),
            captureLimits,
          );
          deadline.requireActive();
          const boundedHttp: typeof fetch = (input, init) => {
            deadline.requireActive();
            const signal =
              init?.signal == null
                ? deadline.signal
                : AbortSignal.any([deadline.signal, init.signal]);
            return http(input, { ...init, signal });
          };
          const admitted = await admitToolImage(
            origin,
            bearer,
            call,
            0,
            image.bytes,
            'OBSERVATION',
            boundedHttp,
          );
          deadline.requireActive();
          if (
            admitted.receipt.width !== image.width ||
            admitted.receipt.height !== image.height ||
            admitted.receipt.mimeType !== image.mimeType ||
            admitted.receipt.producer !== binding.account
          )
            throw new Error(
              'Image admission geometry or producer differs from capture',
            );
          const frame = sessions.recordFrame(
            invocationOwner,
            args,
            args.surfaceId,
            image,
          );
          const text = JSON.stringify({
            version: 'computer-control/1',
            invocationId: call.invocationId,
            session,
            targetId: session.targetId,
            frame,
            image: {
              slot: 0,
              imageId: admitted.receipt.imageId,
              mimeType: image.mimeType,
            },
            actionOutcome: 'NOT_DISPATCHED',
            observationOutcome: 'CAPTURED',
          });
          if (text.length > 12000)
            throw new Error('Computer observation metadata exceeds bound');
          return validatedResult(
            call,
            imageToolResult(
              call,
              [toolTextPart(text), admitted.part],
              [admitted.receipt],
            ),
            binding.account,
          );
        },
      );
    } catch (error) {
      const code =
        error instanceof SessionError || error instanceof BackendError
          ? error.code
          : error instanceof DeadlineExceeded
            ? 'DEADLINE_EXPIRED'
            : 'INVALID_OR_FAILED_OPERATION';
      const uncertain =
        error instanceof BackendError &&
        error.physicalSettlement === 'uncertain';
      return textResult(
        call,
        binding.account,
        {
          version: 'computer-control/1',
          invocationId: call.invocationId,
          code,
        },
        captureStarted || uncertain ? 'UNKNOWN' : 'REJECTED',
      );
    } finally {
      if (inherited === undefined) deadline.dispose();
    }
  };
  const tools = Object.freeze(
    declarations.map((declaration): RegisteredTool =>
      Object.freeze({ declaration, handler: handle }),
    ),
  );
  const host: ToolHost = {
    uuid: () => nodeToolHost.uuid(),
    sha256: (value) => nodeToolHost.sha256(value),
    now: () => new Date(clock.wallNow()),
    async execute(handler, call, sdkDeadline) {
      const earlier = new Date(
        Math.min(Date.parse(call.deadline), sdkDeadline.getTime()),
      ).toISOString();
      const deadline = fromCallDeadline(
        earlier,
        call.tool === prefix + 'observe' ? observeBudgetMs : controlBudgetMs,
        clock,
        options.parentDeadline?.(),
      );
      activeDeadlines.set(call.invocationId, deadline);
      const work = (async () => {
        try {
          return await handler(call);
        } finally {
          activeDeadlines.delete(call.invocationId);
          deadline.dispose();
        }
      })();
      physical.add(work);
      // Both fulfillment/rejection release only this promise's physical-work accounting.
      const settled = work.then(
        (result) => {
          physical.delete(work);
          return result;
        },
        (error: unknown) => {
          physical.delete(work);
          throw error;
        },
      );
      return abortable(settled, deadline);
    },
  };
  const provider = await ToolProvider.create(
    options.connection,
    binding,
    tools,
    journal,
    host,
  );
  return Object.freeze({
    provider,
    tools,
    declarations,
    sessions,
    journal,
    journalIdentity,
    stop() {
      active = false;
      for (const deadline of activeDeadlines.values()) deadline.cancel();
    },
    async drain(deadline: OperationDeadline) {
      await abortable(
        Promise.all(
          [...physical].map((work) =>
            work.then(
              () => undefined,
              () => undefined,
            ),
          ),
        ),
        deadline,
      );
    },
  });
}
