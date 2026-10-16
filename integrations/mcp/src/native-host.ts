import { readdir } from 'node:fs/promises';
import { join, basename } from 'node:path';
import {
  localLock,
  canonicalServer,
  connectionAccount,
} from 'plowshare-client-node/connections';
import { decodeToolCall } from 'plowshare-client-node/tools';
import {
  canonicalToolSchema,
  validateSchemaArguments,
  decodeToolValue,
} from 'plowshare-client-ts/operations/tool-schemas';
import type {
  ToolDeclaration,
  ToolObject,
  ToolResult,
  ToolJournal,
  ToolCall,
  RegisteredTool,
  ToolBinding,
} from 'plowshare-client-ts';
import { createHash } from 'node:crypto';
import type { McpSession } from './contracts.ts';
import { rejected, unknownResult } from './contracts.ts';
import { contractHash, declaration } from './codec.ts';
import { FileToolJournal, uuid, recoverInterrupted } from './journal.ts';
import { HttpMcpSessions } from './session.ts';
import { openStdio } from './stdio.ts';
import { McpToolClient } from './tool-client.ts';
import { validateMcpInvocation } from './invocation.ts';
import { decodeNativeConfig, nativeEnvironment } from './native-config.ts';
import type { NativeMcpConfig, NativeOwner } from './native-config.ts';

/** Transport owner supplied at composition; direct execution requires no Plowshare socket. */
export interface NativeSessions {
  open(config: NativeMcpConfig, signal: AbortSignal): Promise<McpSession>;
  validate?(config: NativeMcpConfig): void;
}
export class NativeMcpSessions implements NativeSessions {
  private fingerprints = new Map<string, string>();
  private values(config: NativeMcpConfig): Readonly<Record<string, string>> {
    return config.transport.kind === 'stdio'
      ? nativeEnvironment(config.transport.environment, this.environment)
      : config.transport.authorization === null
        ? {}
        : nativeEnvironment(
            { value: config.transport.authorization.environment },
            this.environment,
          );
  }
  validate(config: NativeMcpConfig): void {
    if (
      this.fingerprints.get(configIdentity(config)) !==
      contractHash(this.values(config))
    )
      throw new Error('Native MCP environment changed');
  }
  constructor(
    private readonly environment: Readonly<Record<string, string | undefined>>,
  ) {}
  async open(
    config: NativeMcpConfig,
    signal: AbortSignal,
  ): Promise<McpSession> {
    this.fingerprints.set(
      configIdentity(config),
      contractHash(this.values(config)),
    );
    if (config.transport.kind === 'stdio')
      return openStdio(config, this.environment, signal);
    const auth = config.transport.authorization;
    let credentials = {};
    if (auth !== null) {
      const value = nativeEnvironment(
        { value: auth.environment },
        this.environment,
      ).value;
      if (value === undefined || !value || /[\r\n]/.test(value))
        throw new Error('Native MCP authorization is unavailable');
      credentials = { header: auth.header, value };
    }
    return new HttpMcpSessions(config.clientInfo).open(
      {
        protocol: config.protocol,
        limits: config.limits,
        policy: {
          endpoint: config.transport.endpoint,
          allowHttp: config.transport.allowHttp,
          allowPrivateNetwork: config.transport.allowPrivateNetwork,
          timeoutMs: config.timeoutMs,
        },
        ...credentials,
      },
      signal,
    );
  }
}
export interface NativeInvocation {
  readonly invocationId: string;
  readonly tool: string;
  readonly arguments: ToolObject;
  readonly deadline: string;
}
export interface NativeLaunchReview {
  readonly executable: string | null;
  readonly argumentCount: number;
  readonly environmentNames: readonly string[];
  readonly endpoint: string | null;
  readonly authorizationConfigured: boolean;
}
export function nativeLaunchReview(
  config: NativeMcpConfig,
): NativeLaunchReview {
  const transport = config.transport;
  return transport.kind === 'stdio'
    ? {
        executable: basename(transport.executable),
        argumentCount: transport.args.length,
        environmentNames: Object.keys(transport.environment),
        endpoint: null,
        authorizationConfigured: false,
      }
    : {
        executable: null,
        argumentCount: 0,
        environmentNames:
          transport.authorization === null
            ? []
            : [transport.authorization.environment],
        endpoint: transport.endpoint,
        authorizationConfigured: transport.authorization !== null,
      };
}
export interface NativeHostStatus {
  readonly instance: string;
  readonly profile: string;
  readonly project: string;
  readonly transport: 'http' | 'stdio';
  readonly review: NativeLaunchReview;
  readonly active: boolean;
  readonly tools: readonly ToolDeclaration[];
}
function directory(config: NativeMcpConfig): string {
  return join(
    config.journalDirectory,
    contractHash({
      origin: config.owner.origin,
      account: config.owner.account,
      instance: config.instance,
      project: config.project,
      profile: config.profile,
    }),
  );
}
function configIdentity(config: NativeMcpConfig): string {
  return contractHash(decodeToolValue(config));
}
/** One explicitly consented process lifetime, exclusive SDK receipt journal and pinned catalogue.
 * A direct call saves execution intent before entering MCP. Reopening the same profile preserves
 * receipts: executing means UNKNOWN, never another handler execution. Host metadata contains no
 * credential values, stdout/stderr, process argv or environment. Server sharing is a separate bridge.
 */
export class NativeMcpHost {
  private readonly controller = new AbortController();
  private busy = false;
  private active = true;
  private closing: Promise<void> | undefined;
  private constructor(
    readonly config: NativeMcpConfig,
    private readonly client: McpToolClient,
    readonly tools: readonly ToolDeclaration[],
    private readonly journal: ToolJournal,
    private readonly requestRun: string,
    private readonly sessions: NativeSessions,
    private readonly historicalRequests: ReadonlyMap<
      string,
      { request: string; journal: ToolJournal }
    >,
    private readonly finish: () => void,
    private readonly finished: Promise<void>,
  ) {}
  static async start(
    source: NativeMcpConfig,
    owner: NativeOwner,
    sessions: NativeSessions,
    consent: boolean,
    lifetime?: AbortSignal,
  ): Promise<NativeMcpHost> {
    const config = decodeNativeConfig(source);
    if (!consent)
      throw new Error(
        'Review and consent are required before native MCP launch',
      );
    if (
      config.owner.origin !== canonicalServer(owner.origin) ||
      config.owner.account !== connectionAccount(owner.account)
    )
      throw new Error('Native MCP profile belongs to a different login');
    lifetime?.throwIfAborted();
    const start = new AbortController();
    const signal = AbortSignal.any([
      start.signal,
      ...(lifetime === undefined ? [] : [lifetime]),
      AbortSignal.timeout(config.timeoutMs),
    ]);
    let ready: (host: NativeMcpHost) => void = () => undefined;
    let failed: (error: unknown) => void = () => undefined;
    const opened = new Promise<NativeMcpHost>((resolve, reject) => {
      ready = resolve;
      failed = reject;
    });
    const locked = localLock(
      directory(config),
      'native-host',
      async () => {
        // Inspect existing SDK receipts before launch, without a new ledger or hidden recovery effect.
        const files = (await readdir(directory(config))).filter((file) =>
          /^[0-9a-f]{64}\.json$/.test(file),
        );
        if (files.length > config.maxRecoveryCatalogues)
          throw new Error('Native recovery archive exceeds operator budget');
        const historicalRequests = new Map<
          string,
          { request: string; journal: ToolJournal }
        >();
        for (const file of files) {
          const journal = await FileToolJournal.open(
            directory(config),
            file.slice(0, -5),
          );
          for (const receipt of await journal.all()) {
            const call = decodeToolCall(receipt.request);
            if (
              call.account !== config.owner.account ||
              call.project !== config.project ||
              call.provider !== 'native' ||
              call.run !== config.instance ||
              receipt.hostedWorker !== undefined
            )
              throw new Error('Foreign native SDK journal');
            const prior = historicalRequests.get(receipt.invocationId);
            if (prior !== undefined && prior.request !== receipt.request)
              throw new Error('Conflicting native SDK archive identity');
            historicalRequests.set(receipt.invocationId, {
              request: receipt.request,
              journal,
            });
          }
          // The exclusive host lock proves no former process can still own these receipts.
          // Validate their owner first, then record interruption before opening any new session.
          await recoverInterrupted(journal);
        }
        const session = await sessions.open(config, signal);
        let host: NativeMcpHost | undefined;
        try {
          const client = await McpToolClient.discover(
            session,
            signal,
            config.limits,
          );
          const context = configIdentity(config);
          const tools = Object.freeze(
            client.tools.map((tool) =>
              declaration(
                tool,
                config.prefix,
                config.protocol,
                context,
                config.timeoutSeconds,
                config.limits,
              ),
            ),
          );
          if (
            new Set(tools.map((tool) => tool.name)).size !== tools.length ||
            Buffer.byteLength(JSON.stringify(tools)) >
              config.limits.maxCatalogueBytes
          )
            throw new Error(
              'Native MCP discovery exceeds operator catalogue policy',
            );
          const journalIdentity = createHash('sha256')
            .update(context)
            .update(contractHash(client.tools.map((tool) => tool.metadata)))
            .digest('hex');
          if (
            !files.includes(journalIdentity + '.json') &&
            files.length >= config.maxRecoveryCatalogues
          )
            throw new Error(
              'Native recovery archive is full; preserve and inspect original receipts',
            );
          const journal = await FileToolJournal.open(
            directory(config),
            journalIdentity,
          );
          let finish: () => void = () => undefined;
          const stopped = new Promise<void>((resolve) => {
            finish = resolve;
          });
          host = new NativeMcpHost(
            config,
            client,
            tools,
            journal,
            config.instance,
            sessions,
            historicalRequests,
            finish,
            locked,
          );
          const lost = () => {
            host?.stopIntake();
            finish();
          };
          session.closedSignal?.addEventListener('abort', lost, { once: true });
          lifetime?.addEventListener('abort', lost, { once: true });
          if (session.closedSignal?.aborted || lifetime?.aborted) lost();
          signal.throwIfAborted();
          ready(host);
          try {
            await stopped;
          } finally {
            session.closedSignal?.removeEventListener('abort', lost);
            lifetime?.removeEventListener('abort', lost);
            host.stopIntake();
            await host.closingCall;
          }
        } finally {
          await session.close();
        }
      },
      signal,
    );
    // Own both lock/open failure and late cleanup failures. close() still observes cleanup errors.
    locked.catch(failed);
    try {
      return await opened;
    } catch (error) {
      start.abort();
      throw error;
    }
  }
  private closingCall: Promise<void> = Promise.resolve();
  status(): NativeHostStatus {
    return {
      instance: this.config.instance,
      profile: this.config.profile,
      project: this.config.project,
      transport: this.config.transport.kind,
      review: nativeLaunchReview(this.config),
      active: this.active,
      tools: this.tools,
    };
  }
  stopIntake(): void {
    this.active = false;
    this.controller.abort();
  }
  invoke(input: NativeInvocation, signal?: AbortSignal): Promise<ToolResult> {
    if (!this.active || this.busy)
      return Promise.resolve(
        rejected('Native MCP host is unavailable; no call was sent.'),
      );
    this.busy = true;
    const call = this.execute(input, signal);
    this.closingCall = call.then(
      () => undefined,
      () => undefined,
    );
    return call.finally(() => {
      this.busy = false;
    });
  }
  private async execute(
    input: NativeInvocation,
    signal?: AbortSignal,
  ): Promise<ToolResult> {
    const declaration = this.tools.find((tool) => tool.name === input.tool);
    if (declaration?.inputSchema === undefined)
      return rejected(
        'Native MCP tool is outside the reviewed snapshot; no call was sent.',
      );
    const request = JSON.stringify({
      schema: 'plowshare-tool/2',
      invocationId: uuid(input.invocationId),
      project: this.config.project,
      provider: 'native',
      tool: input.tool,
      account: this.config.owner.account,
      run: this.requestRun,
      call: input.invocationId,
      deadline: input.deadline,
      arguments: input.arguments,
      schemaFingerprint: createHash('sha256')
        .update(canonicalToolSchema(declaration.inputSchema))
        .digest('hex'),
    });
    const call: ToolCall = decodeToolCall(request);
    const historical = this.historicalRequests.get(call.invocationId);
    if (historical !== undefined && historical.request !== request)
      throw new Error(
        'Native invocation belongs to a retained different context; inspect its original SDK receipt',
      );
    const current = (await this.journal.all()).find(
      (row) => row.invocationId === call.invocationId,
    );
    const ownerJournal =
      current === undefined
        ? (historical?.journal ?? this.journal)
        : this.journal;
    const existing =
      current ??
      (await ownerJournal.all()).find(
        (row) => row.invocationId === call.invocationId,
      );
    if (existing !== undefined && existing.request !== request)
      throw new Error(
        'Conflicting native MCP invocation; retained request was not changed',
      );
    if (existing?.result !== undefined && existing.result !== null)
      return existing.result;
    let outcome: ToolResult;
    if (existing !== undefined) outcome = unknownResult();
    else {
      const remaining = Math.min(
        Date.parse(call.deadline) - Date.now(),
        this.config.timeoutSeconds * 1000,
      );
      if (!Number.isFinite(remaining) || remaining <= 0 || signal?.aborted)
        return rejected('Native MCP invocation expired before dispatch.');
      const lifetime = AbortSignal.any([
        this.controller.signal,
        AbortSignal.timeout(remaining),
        ...(signal === undefined ? [] : [signal]),
      ]);
      const tool = this.client.tools[this.tools.indexOf(declaration)];
      if (tool === undefined)
        throw new Error('Native MCP catalogue identity is invalid');
      try {
        this.sessions.validate?.(this.config);
        await this.client.refresh(lifetime);
        lifetime.throwIfAborted();
      } catch {
        this.stopIntake();
        return rejected(
          'Native MCP credentials or catalogue changed; no call was sent.',
        );
      }
      // Input validation happens before saving intent, so malformed calls have no effects.
      validateSchemaArguments(declaration.inputSchema, call.arguments);
      await this.journal.save({
        invocationId: call.invocationId,
        request,
        phase: 'executing',
        result: null,
        occurredAt: null,
      });
      outcome = await this.client.invoke(tool.name, call.arguments, lifetime);
      if (outcome.state === 'UNKNOWN') {
        this.stopIntake();
        this.finish();
      }
    }
    await ownerJournal.save({
      invocationId: call.invocationId,
      request,
      phase: 'done',
      result: outcome,
      occurredAt: new Date().toISOString(),
    });
    return outcome;
  }
  async refresh(): Promise<boolean> {
    if (this.busy) return false;
    const signal = AbortSignal.any([
      this.controller.signal,
      AbortSignal.timeout(this.config.timeoutMs),
    ]);
    try {
      this.sessions.validate?.(this.config);
      await this.client.refresh(signal);
      return true;
    } catch (error) {
      this.stopIntake();
      this.finish();
      throw error;
    }
  }
  /** Optional server bridge handlers call this direct layer. Their SDK ToolProvider owns execution
   * intent/receipts; invoking a handler without such an owner is not an authorized client workflow.
   * The binding must be the route returned on that owner's sharing socket; retain a private copy
   * so later caller mutation cannot widen the handler's route.
   */
  registeredTools(binding: ToolBinding): readonly RegisteredTool[] {
    if (
      binding.project !== this.config.project ||
      binding.account !== this.config.owner.account
    )
      throw new Error('Native sharing route belongs to another owner');
    const route = Object.freeze({ ...binding });
    return this.tools.map((declaration, index) => ({
      declaration,
      handler: async (call: ToolCall) => {
        if (!this.active || this.busy || call.project !== this.config.project)
          return rejected('Native MCP host is unavailable; no call was sent.');
        const tool = this.client.tools[index];
        if (tool === undefined)
          throw new Error('Native MCP catalogue identity is invalid');
        const remaining = Math.min(
          Date.parse(call.deadline) - Date.now(),
          this.config.timeoutSeconds * 1000,
        );
        if (!Number.isFinite(remaining) || remaining <= 0)
          return rejected('Native MCP call expired before dispatch.');
        this.busy = true;
        const effect = (async () => {
          const signal = AbortSignal.any([
            this.controller.signal,
            AbortSignal.timeout(remaining),
          ]);
          try {
            await validateMcpInvocation(route, declaration, call);
            signal.throwIfAborted();
          } catch {
            return rejected(
              'Native MCP invocation did not match its owner, route or offered contract. No call was sent.',
            );
          }
          try {
            this.sessions.validate?.(this.config);
            await this.client.refresh(signal);
            signal.throwIfAborted();
          } catch {
            this.stopIntake();
            return rejected(
              'Native MCP credentials or catalogue changed; no call was sent.',
            );
          }
          const outcome = await this.client.invoke(
            tool.name,
            call.arguments,
            signal,
          );
          if (outcome.state === 'UNKNOWN') {
            this.stopIntake();
            this.finish();
          }
          return outcome;
        })();
        this.closingCall = effect.then(
          () => undefined,
          () => undefined,
        );
        try {
          return await effect;
        } finally {
          this.busy = false;
        }
      },
    }));
  }
  close(): Promise<void> {
    this.stopIntake();
    this.finish();
    this.closing ??= this.finished;
    return this.closing;
  }
}
/** A client-process registry. Account switches/exit dispose it; it never auto-starts or adopts hosts. */
export class NativeMcpHosts {
  private hosts = new Map<string, NativeMcpHost>();
  private starting = new Map<string, Promise<NativeMcpHost>>();
  private readonly stop = new AbortController();
  constructor(
    private readonly owner: NativeOwner,
    private readonly sessions: NativeSessions,
  ) {}
  async start(
    config: NativeMcpConfig,
    consent: boolean,
  ): Promise<NativeHostStatus> {
    this.stop.signal.throwIfAborted();
    const key = config.instance + '/' + config.profile;
    if (this.hosts.has(key) || this.starting.has(key))
      throw new Error('Native MCP host is already owned by this process');
    const starting = NativeMcpHost.start(
      config,
      this.owner,
      this.sessions,
      consent,
      this.stop.signal,
    );
    this.starting.set(key, starting);
    try {
      const host = await starting;
      this.hosts.set(key, host);
      return host.status();
    } finally {
      this.starting.delete(key);
    }
  }
  host(instance: string, profile: string): NativeMcpHost {
    const host = this.hosts.get(instance + '/' + profile);
    if (host === undefined)
      throw new Error('Native MCP host is not owned by this process');
    return host;
  }
  list(): readonly NativeHostStatus[] {
    return [...this.hosts.values()].map((host) => host.status());
  }
  invoke(
    instance: string,
    profile: string,
    input: NativeInvocation,
  ): Promise<ToolResult> {
    const host = this.hosts.get(instance + '/' + profile);
    if (host === undefined)
      throw new Error('Native MCP host is not owned by this process');
    return host.invoke(input);
  }
  async close(): Promise<void> {
    this.stop.abort();
    await Promise.all(
      [...this.starting.values()].map(async (starting) => {
        try {
          await (await starting).close();
        } catch {
          /* Opening failure has already been returned to its caller. */
        }
      }),
    );
    await Promise.all([...this.hosts.values()].map((host) => host.close()));
    this.hosts.clear();
  }
  async stopHost(instance: string, profile: string): Promise<void> {
    const key = instance + '/' + profile,
      host = this.hosts.get(key);
    if (host === undefined)
      throw new Error('Native MCP host is not owned by this process');
    await host.close();
    this.hosts.delete(key);
  }
}
