import { PROVIDER_CATALOGUE_TOOL_LIMIT } from './settings.ts';
import type { ToolScopeConnect } from 'plowshare-client-ts/operations/tool-scopes';
import { join } from 'node:path';
import {
  ToolProvider,
  nodeToolHost,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import { requirePayload } from 'plowshare-client-ts';
import type { Plowshare } from 'plowshare-client-ts';

export interface NativeSharingConnection extends Pick<Plowshare, 'request'> {
  /** Close the original owning socket after ambiguous metadata; never reconnect/replay here. */
  invalidate(): void;
}
import { decodePayload } from 'plowshare-client-ts/operations/schema';
import type { NativeMcpHost } from './native-host.ts';
import { FileToolJournal, CatalogueIntent } from './journal.ts';
import { contractHash } from './codec.ts';
import { publish } from './provider.ts';

/** Only server-owned calls cross this boundary. Native components always invoke the shared host
 * directly. The existing socket scope, grants and ToolProvider journal own cross-process admission.
 * The caller retains this object on the owning interactive socket and closes it on socket loss.
 */
export class NativeProviderBridge {
  private constructor(
    private readonly connection: NativeSharingConnection,
    private readonly scope: ToolScopeConnect,
    private readonly host: NativeMcpHost,
    private readonly provider: ToolProvider,
    private readonly intent: CatalogueIntent,
  ) {}
  static async start(
    connection: NativeSharingConnection,
    scope: ToolScopeConnect,
    host: NativeMcpHost,
  ): Promise<NativeProviderBridge> {
    decodePayload('tool.scope.connect', scope);
    if (host.tools.length > PROVIDER_CATALOGUE_TOOL_LIMIT)
      throw new Error(
        'Native server sharing exceeds the shared provider catalogue contract',
      );
    if (
      scope.project !== host.config.project ||
      scope.prefix !== host.config.prefix ||
      !host.status().active
    )
      throw new Error('Native sharing policy differs from the consented host');
    try {
      const route = requirePayload(
        await connection.request('tool.scope.connect', scope),
      );
      if (
        route.account !== host.config.owner.account ||
        route.project !== scope.project ||
        route.scope !== scope.scope ||
        route.prefix !== scope.prefix
      )
        throw new Error('Foreign native provider scope');
      const tools = host.registeredTools({
        project: route.project,
        provider: route.provider,
        account: route.account,
      });
      const identity = await nodeToolHost.sha256(
        toolDeploymentConfig(
          {
            project: route.project,
            provider: route.provider,
            account: route.account,
          },
          tools.map((tool) => tool.declaration),
        ),
      );
      const directory = join(
        host.config.journalDirectory,
        contractHash({
          origin: host.config.owner.origin,
          account: route.account,
          instance: host.config.instance,
          profile: host.config.profile,
          project: route.project,
        }),
        'shared',
        identity,
      );
      const journal = await FileToolJournal.open(directory, identity);
      const intent = await CatalogueIntent.open(directory);
      const provider = await ToolProvider.create(
        connection,
        {
          project: route.project,
          provider: route.provider,
          account: route.account,
        },
        tools,
        journal,
        nodeToolHost,
      );
      const bridge = new NativeProviderBridge(
        connection,
        scope,
        host,
        provider,
        intent,
      );
      await publish(provider, intent, 'publish');
      return bridge;
    } catch (error) {
      // connect/catalogue metadata has no owning receipt read. Closing the original interactive
      // socket removes its ephemeral authority without retrying or guessing which mutation landed.
      connection.invalidate();
      throw error;
    }
  }

  async poll(): Promise<number> {
    if (!this.host.status().active)
      throw new Error('Native MCP host is unavailable');
    return this.provider.poll();
  }
  async renew(): Promise<void> {
    if (!this.host.status().active)
      throw new Error('Native MCP host is unavailable');
    if (!(await this.host.refresh())) return;
    await publish(this.provider, this.intent, 'publish');
  }
  async reconcile(): Promise<number> {
    return this.provider.reconcile();
  }
  private closing: Promise<void> | undefined;
  close(): Promise<void> {
    this.closing ??= this.withdraw();
    return this.closing;
  }
  private async withdraw(): Promise<void> {
    this.host.stopIntake();
    try {
      requirePayload(
        await this.connection.request('tool.scope.disconnect', {
          project: this.scope.project,
          scope: this.scope.scope,
        }),
      );
    } catch (error) {
      this.connection.invalidate();
      throw error;
    } finally {
      await this.host.close();
    }
  }
}
