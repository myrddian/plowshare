import {
  canonicalServer,
  connectionAccount,
} from 'plowshare-client-node/connections';
import type { ToolScopeConnect } from 'plowshare-client-ts/operations/tool-scopes';
import {
  decodePayload,
  decodeReply,
} from 'plowshare-client-ts/operations/schema';
import { succeeded } from 'plowshare-client-ts/binding/codes';
import type { OperationTransport } from 'plowshare-client-ts/operations/response';
import type { ToolObject, ToolResult } from 'plowshare-client-ts';
import { decodeToolValue } from 'plowshare-client-ts/operations/tool-schemas';
import { setTimeout as delay } from 'node:timers/promises';
import { object, text } from './codec.ts';
import { uuid } from './journal.ts';
import { boundedInteger, renewalFraction, MAX_TIMER_MS } from './settings.ts';
import { readNativeConfig } from './native-config.ts';
import type { NativeOwner } from './native-config.ts';
import { NativeMcpHosts, nativeLaunchReview } from './native-host.ts';
import type {
  NativeSessions,
  NativeHostStatus,
  NativeInvocation,
} from './native-host.ts';
import type { NativeSharingConnection } from './native-bridge.ts';
import { NativeProviderBridge } from './native-bridge.ts';

export type NativeCommand =
  | { readonly action: 'inspect'; readonly file: string }
  | {
      readonly action: 'start';
      readonly file: string;
      readonly consent: boolean;
    }
  | {
      readonly action: 'share';
      readonly file: string;
      readonly consent: boolean;
      readonly scope: ToolScopeConnect;
      readonly pollMs: number;
      readonly renewalFraction: number;
    }
  | { readonly action: 'list' }
  | {
      readonly action: 'stop';
      readonly instance: string;
      readonly profile: string;
    }
  | {
      readonly action: 'call';
      readonly instance: string;
      readonly profile: string;
      readonly invocation: NativeInvocation;
    };
export type NativeCommandResult =
  | NativeHostStatus
  | readonly NativeHostStatus[]
  | ToolResult
  | { readonly stopped: true };
function fields(
  row: Record<string, unknown>,
  allowed: readonly string[],
): void {
  if (Object.keys(row).some((key) => !allowed.includes(key)))
    throw new Error('Unknown native MCP command field');
}
export function decodeNativeInvocation(source: unknown): NativeInvocation {
  const row = object(source);
  fields(row, ['invocationId', 'tool', 'arguments', 'deadline']);
  const arguments_ = decodeToolValue(row.arguments);
  if (
    arguments_ === null ||
    typeof arguments_ !== 'object' ||
    Array.isArray(arguments_)
  )
    throw new Error('Native MCP call arguments must be an object');
  const deadline = text(row.deadline, 40);
  if (!Number.isFinite(Date.parse(deadline)))
    throw new Error('Native MCP call requires a deadline');
  // Complete recursive value decoding establishes this object variant before the assertion.
  return {
    invocationId: uuid(row.invocationId),
    tool: text(row.tool, 256),
    arguments: arguments_ as ToolObject,
    deadline,
  };
}
export function decodeNativeCommand(source: unknown): NativeCommand {
  const row = object(source);
  switch (row.action) {
    case 'inspect':
      fields(row, ['action', 'file']);
      return { action: 'inspect', file: text(row.file, 4096) };
    case 'start':
    case 'share': {
      fields(
        row,
        row.action === 'start'
          ? ['action', 'file', 'consent']
          : ['action', 'file', 'consent', 'scope', 'pollMs', 'renewalFraction'],
      );
      if (typeof row.consent !== 'boolean')
        throw new Error('Native MCP launch requires explicit consent');
      const base = { file: text(row.file, 4096), consent: row.consent };
      return row.action === 'start'
        ? { action: 'start', ...base }
        : {
            action: 'share',
            ...base,
            scope: decodePayload('tool.scope.connect', row.scope),
            pollMs: boundedInteger(row.pollMs, 1, MAX_TIMER_MS),
            renewalFraction: renewalFraction(row.renewalFraction),
          };
    }
    case 'list':
      fields(row, ['action']);
      return { action: 'list' };
    case 'stop':
      fields(row, ['action', 'instance', 'profile']);
      return {
        action: 'stop',
        instance: text(row.instance, 64),
        profile: text(row.profile, 64),
      };
    case 'call':
      fields(row, ['action', 'instance', 'profile', 'invocation']);
      return {
        action: 'call',
        instance: text(row.instance, 64),
        profile: text(row.profile, 64),
        invocation: decodeNativeInvocation(row.invocation),
      };
    default:
      throw new Error('Unsupported native MCP command');
  }
}
/** Borrow a client's existing checked public operation transport; no socket or HTTP fallback. */
export function nativeProviderConnection(
  transport: OperationTransport,
  invalidate: () => void,
): NativeSharingConnection {
  return {
    invalidate,
    async request(type, payload) {
      const input = decodePayload(type, payload);
      const outcome = await transport.ask(type, input);
      if (!succeeded(outcome.code))
        return {
          kind: 'refused',
          outcome: {
            code: outcome.code,
            ...(outcome.said === undefined ? {} : { said: outcome.said }),
          },
        };
      return {
        kind: 'success',
        outcome: {
          code: outcome.code,
          ...(outcome.said === undefined ? {} : { said: outcome.said }),
          payload: decodeReply(type, outcome.payload),
        },
      };
    },
  };
}
/** Native main-process command owner. Only explicit share installs a server provider bridge.
 * Direct start/call/list/stop never perform a Plowshare operation. Main-window/terminal consent
 * remains the owning client's responsibility; views receive bounded status/results only.
 */
export class NativeHostCommands {
  readonly hosts: NativeMcpHosts;
  private shares = new Map<
    string,
    {
      bridge: NativeProviderBridge;
      stop: AbortController;
      work: Promise<void>;
      failed: () => boolean;
    }
  >();
  constructor(
    private readonly owner: NativeOwner,
    sessions: NativeSessions,
    private readonly connection?: NativeSharingConnection,
  ) {
    this.owner = {
      origin: canonicalServer(owner.origin),
      account: connectionAccount(owner.account),
    };
    this.hosts = new NativeMcpHosts(this.owner, sessions);
  }
  async execute(source: NativeCommand): Promise<NativeCommandResult> {
    const command = decodeNativeCommand(source);
    if (command.action === 'list') return this.hosts.list();
    if (command.action === 'call')
      return this.hosts.invoke(
        command.instance,
        command.profile,
        command.invocation,
      );
    if (command.action === 'stop') {
      const key = command.instance + '/' + command.profile;
      const share = this.shares.get(key);
      if (share !== undefined) {
        this.hosts.host(command.instance, command.profile).stopIntake();
        share.stop.abort();
        await share.work;
        await share.bridge.close();
        this.shares.delete(key);
      }
      await this.hosts.stopHost(command.instance, command.profile);
      return { stopped: true };
    }
    const config = await readNativeConfig(command.file);
    if (
      config.owner.origin !== this.owner.origin ||
      config.owner.account !== this.owner.account
    )
      throw new Error('Native MCP profile belongs to another login');
    if (command.action === 'inspect')
      return {
        instance: config.instance,
        profile: config.profile,
        project: config.project,
        transport: config.transport.kind,
        review: nativeLaunchReview(config),
        active: false,
        tools: [],
      };
    if (command.action === 'share' && this.connection === undefined)
      throw new Error('Server sharing requires an authenticated owning socket');
    const status = await this.hosts.start(config, command.consent);
    if (command.action === 'share') {
      const connection = this.connection;
      if (connection === undefined)
        throw new Error(
          'Server sharing requires an authenticated owning socket',
        );
      const host = this.hosts.host(config.instance, config.profile);
      let bridge: NativeProviderBridge;
      try {
        bridge = await NativeProviderBridge.start(
          connection,
          command.scope,
          host,
        );
      } catch (error) {
        await this.hosts.stopHost(config.instance, config.profile);
        throw error;
      }
      const stop = new AbortController();
      const work = (async () => {
        let next =
          Date.now() +
          command.scope.leaseSeconds * 1000 * command.renewalFraction;
        while (!stop.signal.aborted && host.status().active) {
          if (Date.now() >= next) {
            await bridge.renew();
            next =
              Date.now() +
              command.scope.leaseSeconds * 1000 * command.renewalFraction;
          }
          await bridge.poll();
          try {
            await delay(command.pollMs, undefined, { signal: stop.signal });
          } catch {
            if (!stop.signal.aborted)
              throw new Error('Native sharing wait failed');
          }
        }
      })();
      // Retain every settlement; failed sharing removes local intake without replaying mutations.
      let failed = false;
      const observed = work
        .then(async () => {
          if (!stop.signal.aborted && !host.status().active)
            await bridge.close();
        })
        .catch(async () => {
          failed = true;
          host.stopIntake();
          // Preserve the failure state while withdrawing the owning scope. A failed withdrawal
          // invalidates its socket inside the bridge; no metadata mutation is retried here.
          const cleanup = await Promise.allSettled([bridge.close()]);
          if (cleanup.some((result) => result.status === 'rejected'))
            failed = true;
        });
      this.shares.set(config.instance + '/' + config.profile, {
        bridge,
        stop,
        work: observed,
        failed: () => failed,
      });
    }
    return status;
  }
  private closing: Promise<void> | undefined;
  close(): Promise<void> {
    this.closing ??= this.finish();
    return this.closing;
  }
  private async finish(): Promise<void> {
    for (const share of this.shares.values()) share.stop.abort();
    // Abort native handlers before waiting for a server provider poll to settle.
    const closing = this.hosts.close();
    const results = await Promise.allSettled(
      [...this.shares.values()].map(async (share) => {
        try {
          await share.work;
        } finally {
          await share.bridge.close();
        }
      }),
    );
    const sharingFailed = [...this.shares.values()].some((share) =>
      share.failed(),
    );
    this.shares.clear();
    await closing;
    const failed = results.find((result) => result.status === 'rejected');
    if (sharingFailed || failed?.status === 'rejected')
      throw new Error('Native MCP sharing ended with retained uncertainty');
  }
}
