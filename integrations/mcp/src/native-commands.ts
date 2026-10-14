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
import { decodeNativeConfig, readNativeConfig } from './native-config.ts';
import type { NativeMcpConfig } from './native-config.ts';
import { NativeProfiles } from './native-profiles.ts';
import type { NativeProfile, NativeProfileStore } from './native-profiles.ts';
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
  | { readonly action: 'profile-read'; readonly file: string }
  | {
      readonly action: 'profile-save';
      readonly file: string;
      readonly config: NativeMcpConfig;
      readonly expectedRevision: string | null;
    }
  | {
      readonly action: 'inspect';
      readonly file: string;
      readonly expectedRevision?: string;
    }
  | {
      readonly action: 'start';
      readonly file: string;
      readonly consent: boolean;
      readonly expectedRevision?: string;
    }
  | {
      readonly action: 'share';
      readonly file: string;
      readonly consent: boolean;
      readonly expectedRevision?: string;
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
export type NativeManagedStatus = NativeHostStatus & {
  readonly sharing: 'local' | 'server' | 'uncertain';
};
export type NativeCommandResult =
  | NativeManagedStatus
  | readonly NativeManagedStatus[]
  | NativeProfile
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
  if (row.action !== 'profile-save' && row.expectedRevision === null)
    throw new Error('Native MCP launch revision must be a hash when supplied');
  const revision =
    row.expectedRevision === undefined || row.expectedRevision === null
      ? {}
      : { expectedRevision: text(row.expectedRevision, 64) };
  if (
    'expectedRevision' in revision &&
    !/^[a-f0-9]{64}$/.test(revision.expectedRevision)
  )
    throw new Error('Invalid native MCP profile revision');
  switch (row.action) {
    case 'profile-read':
      fields(row, ['action', 'file']);
      return { action: 'profile-read', file: text(row.file, 4096) };
    case 'profile-save': {
      fields(row, ['action', 'file', 'config', 'expectedRevision']);
      if (
        row.expectedRevision !== null &&
        (typeof row.expectedRevision !== 'string' ||
          !/^[a-f0-9]{64}$/.test(row.expectedRevision))
      )
        throw new Error('Invalid native MCP profile revision');
      return {
        action: 'profile-save',
        file: text(row.file, 4096),
        config: decodeNativeConfig(row.config),
        expectedRevision: row.expectedRevision,
      };
    }
    case 'inspect':
      fields(row, ['action', 'file', 'expectedRevision']);
      return { action: 'inspect', file: text(row.file, 4096), ...revision };
    case 'start':
    case 'share': {
      fields(
        row,
        row.action === 'start'
          ? ['action', 'file', 'consent', 'expectedRevision']
          : [
              'action',
              'file',
              'consent',
              'scope',
              'pollMs',
              'renewalFraction',
              'expectedRevision',
            ],
      );
      if (typeof row.consent !== 'boolean')
        throw new Error('Native MCP launch requires explicit consent');
      const base = {
        file: text(row.file, 4096),
        consent: row.consent,
        ...revision,
      };
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
  private readonly profiles: NativeProfileStore;
  private configurationWork: Promise<NativeCommandResult> | undefined;
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
    profiles?: NativeProfileStore,
  ) {
    this.owner = {
      origin: canonicalServer(owner.origin),
      account: connectionAccount(owner.account),
    };
    this.hosts = new NativeMcpHosts(this.owner, sessions);
    this.profiles = profiles ?? new NativeProfiles(this.owner);
  }
  async execute(source: NativeCommand): Promise<NativeCommandResult> {
    const command = decodeNativeCommand(source);
    if (this.closing !== undefined)
      throw new Error('Native MCP command owner is closed');
    if (
      command.action !== 'profile-save' &&
      command.action !== 'start' &&
      command.action !== 'share'
    )
      return this.perform(command);
    // Authoring and launch share one configuration gate. Refuse overlapping requests
    // rather than queueing a launch under stale consent. Stop/call/read remain independent.
    if (this.configurationWork !== undefined)
      throw new Error(
        'Native MCP configuration is busy; finish the current action',
      );
    const work = this.perform(command);
    this.configurationWork = work;
    try {
      return await work;
    } finally {
      this.configurationWork = undefined;
    }
  }
  private async perform(command: NativeCommand): Promise<NativeCommandResult> {
    if (command.action === 'profile-read')
      return this.profiles.read(command.file);
    if (command.action === 'profile-save') {
      const original =
        command.expectedRevision === null
          ? undefined
          : await this.profiles.read(command.file);
      if (
        this.hosts
          .list()
          .some(
            (host) =>
              (host.instance === command.config.instance &&
                host.profile === command.config.profile) ||
              (original !== undefined &&
                host.instance === original.config.instance &&
                host.profile === original.config.profile),
          )
      )
        throw new Error(
          'Stop and remove the running host before editing its profile',
        );
      return this.profiles.save(
        command.file,
        command.config,
        command.expectedRevision,
      );
    }
    if (command.action === 'list')
      return this.hosts.list().map((host) => this.managed(host));
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
    const profile =
      command.expectedRevision === undefined
        ? undefined
        : await this.profiles.read(command.file);
    if (profile !== undefined && profile.revision !== command.expectedRevision)
      throw new Error(
        'Native MCP profile changed after review; reload before launch',
      );
    const config = profile?.config ?? (await readNativeConfig(command.file));
    if (
      config.owner.origin !== this.owner.origin ||
      config.owner.account !== this.owner.account
    )
      throw new Error('Native MCP profile belongs to another login');
    if (command.action === 'inspect')
      return this.managed({
        instance: config.instance,
        profile: config.profile,
        project: config.project,
        transport: config.transport.kind,
        review: nativeLaunchReview(config),
        active: false,
        tools: [],
      });
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
    return this.managed(status);
  }
  private managed(host: NativeHostStatus): NativeManagedStatus {
    const share = this.shares.get(host.instance + '/' + host.profile);
    return {
      ...host,
      sharing: share?.failed()
        ? 'uncertain'
        : share && host.active
          ? 'server'
          : 'local',
    };
  }
  private closing: Promise<void> | undefined;
  close(): Promise<void> {
    this.closing ??= this.finish();
    return this.closing;
  }
  private async finish(): Promise<void> {
    // Abort pending opens/effects now, then observe the in-progress editor/launch.
    // A bridge completing during shutdown must enter this owner's cleanup snapshot;
    // its returned failure is already owned by execute's caller, never replayed here.
    const closing = Promise.allSettled([this.hosts.close()]);
    if (this.configurationWork !== undefined)
      await Promise.allSettled([this.configurationWork]);
    for (const share of this.shares.values()) share.stop.abort();
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
    const [closed] = await closing;
    if (closed?.status === 'rejected')
      throw new Error('Native MCP host cleanup failed', {
        cause: closed.reason,
      });
    const failed = results.find((result) => result.status === 'rejected');
    if (sharingFailed || failed?.status === 'rejected')
      throw new Error('Native MCP sharing ended with retained uncertainty');
  }
}
