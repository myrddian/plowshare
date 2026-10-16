import { randomUUID } from 'node:crypto';
import { requirePayload } from 'plowshare-client-ts';
import {
  hostedCredentialCheck,
  hostedRegistrationCheck,
} from 'plowshare-client-ts/operations/hosted-tool-scopes';
import type {
  Plowshare,
  HostedToolCredential,
  HostedToolRegistration,
  RegisteredTool,
  ToolDeclaration,
  ToolCall,
  ToolJournal,
  ToolResult,
} from 'plowshare-client-ts';
import {
  ToolProvider,
  nodeToolHost,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import type {
  McpSessions,
  McpTool,
  McpProtocol,
  HttpPolicy,
} from './contracts.ts';
import { rejected, unknownResult, withinDeadline } from './contracts.ts';
import { DEFAULT_LIMITS, PROVIDER_CATALOGUE_TOOL_LIMIT } from './settings.ts';
import type { McpLimits } from './settings.ts';
import { contractHash, declaration } from './codec.ts';
import { McpToolClient } from './tool-client.ts';
import { validateMcpInvocation } from './invocation.ts';
import type { CataloguePublicationIntent } from './journal.ts';

export type ProviderConnection = Pick<Plowshare, 'request'>;
export interface McpWorkerOptions {
  readonly protocol: McpProtocol;
  readonly policy: Omit<HttpPolicy, 'endpoint'>;
  readonly timeoutSeconds: number;
  readonly limits?: McpLimits;
}
/** Public SDK credential release remains the authority for discovery and exact invocation execution. */
export interface McpCredentials {
  release(invocation: string | null): Promise<HostedToolCredential>;
}
export class WorkerCredentials implements McpCredentials {
  constructor(
    private readonly connection: ProviderConnection,
    private readonly registration: HostedToolRegistration,
  ) {}
  async release(invocation: string | null): Promise<HostedToolCredential> {
    return requirePayload(
      await this.connection.request('tool.scope.worker.credential', {
        binding: this.registration.binding.binding,
        registration: this.registration.registration,
        purpose: invocation === null ? 'DISCOVERY' : 'EXECUTION',
        invocation,
      }),
    );
  }
}
function coordinates(
  credential: HostedToolCredential,
  registration: HostedToolRegistration,
  invocation: string | null,
): void {
  const binding = registration.binding;
  if (
    !hostedCredentialCheck(credential) ||
    credential.binding !== binding.binding ||
    credential.registration !== registration.registration ||
    credential.generation !== binding.generation ||
    credential.subject !== binding.credentialSubject ||
    credential.connection.project !== binding.definition.credentialProject ||
    credential.connection.connection !== binding.definition.connection ||
    !credential.connection.enabled ||
    credential.connection.deleted ||
    credential.purpose !== (invocation === null ? 'DISCOVERY' : 'EXECUTION') ||
    credential.invocation !== invocation
  )
    throw new Error('Foreign or inactive MCP credential context');
}
function identity(
  credential: HostedToolCredential,
  registration: HostedToolRegistration,
): string {
  return contractHash({
    binding: credential.binding,
    generation: credential.generation,
    owner: registration.binding.owner,
    subject: credential.subject,
    project: credential.connection.project,
    connection: credential.connection.connection,
    revision: credential.connection.revision,
    credentialGeneration: credential.credentialGeneration,
    endpoint: credential.connection.definition.endpoint,
  });
}
/** One immutable catalogue and upstream session per credential context. A change requires a new worker. */
export class McpProvider {
  readonly tools: readonly RegisteredTool[];
  readonly journalIdentity: string;
  private active = true;
  private busy = false;
  private constructor(
    private readonly registration: HostedToolRegistration,
    private readonly credentials: McpCredentials,
    private readonly credential: HostedToolCredential,
    private readonly client: McpToolClient,
    private readonly options: McpWorkerOptions,
    private readonly stop: AbortSignal,
    tools: readonly RegisteredTool[],
    journalIdentity: string,
  ) {
    this.tools = tools;
    this.journalIdentity = journalIdentity;
  }
  static async discover(
    registration: HostedToolRegistration,
    credentials: McpCredentials,
    sessions: McpSessions,
    options: McpWorkerOptions,
    stop: AbortSignal = new AbortController().signal,
  ): Promise<McpProvider> {
    const binding = registration.binding;
    const limits = options.limits ?? DEFAULT_LIMITS;
    if (
      !hostedRegistrationCheck(registration) ||
      !binding.enabled ||
      binding.deleted ||
      Date.parse(registration.expiresAt) <= Date.now() ||
      options.timeoutSeconds < 1 ||
      options.timeoutSeconds > 300
    )
      throw new Error('Inactive MCP worker or invalid deadline');
    const signal = AbortSignal.any([
      stop,
      AbortSignal.timeout(options.policy.timeoutMs),
    ]);
    const credential = await withinDeadline(
      () => credentials.release(null),
      signal,
    );
    signal.throwIfAborted();
    coordinates(credential, registration, null);
    const context = identity(credential, registration);
    const session = await sessions.open(
      {
        protocol: options.protocol,
        limits,
        policy: {
          ...options.policy,
          maxWireBytes: limits.maxWireBytes,
          endpoint: credential.connection.definition.endpoint,
        },
        header: credential.header,
        value: credential.value,
      },
      signal,
    );
    try {
      const client = await McpToolClient.discover(session, signal, limits);
      const snapshot = client.tools;
      if (snapshot.length > PROVIDER_CATALOGUE_TOOL_LIMIT)
        throw new Error(
          'Duplicate or oversized MCP catalogue for server provider publication',
        );
      const tools = Object.freeze(
        snapshot.map((tool) => {
          const offered = declaration(
            tool,
            binding.definition.prefix,
            options.protocol,
            context,
            options.timeoutSeconds,
            limits,
          );
          return {
            declaration: offered,
            handler: (call: ToolCall) => provider.execute(tool, offered, call),
          };
        }),
      );
      if (
        new Set(tools.map((tool) => tool.declaration.name)).size !==
        tools.length
      )
        throw new Error('MCP offered-name collision');
      const config = toolDeploymentConfig(
        {
          project: binding.project,
          provider: binding.route.provider,
          account: binding.route.account,
        },
        tools.map((tool) => tool.declaration),
      );
      if (
        Buffer.byteLength(
          JSON.stringify({
            version: 'plowshare-tool-catalog/2',
            tools: tools.map((tool) => tool.declaration),
          }),
        ) > limits.maxCatalogueBytes
      )
        throw new Error('MCP catalogue exceeds provider publication budget');
      const provider = new McpProvider(
        registration,
        credentials,
        credential,
        client,
        options,
        stop,
        tools,
        await nodeToolHost.sha256(config),
      );
      return provider;
    } catch (error) {
      await session.close();
      throw error;
    }
  }
  async refresh(): Promise<void> {
    this.assertActive();
    const signal = AbortSignal.any([
      this.stop,
      AbortSignal.timeout(this.options.policy.timeoutMs),
    ]);
    const credential = await withinDeadline(
      () => this.credentials.release(null),
      signal,
    );
    coordinates(credential, this.registration, null);
    if (!this.sameCredential(credential)) {
      this.active = false;
      throw new Error(
        'MCP credentials changed; withdraw and start a new context',
      );
    }
    try {
      await this.client.refresh(signal);
    } catch (error) {
      this.active = false;
      throw error;
    }
  }

  assertActive(): void {
    if (!this.active) throw new Error('MCP context is no longer safe to offer');
  }
  private sameCredential(next: HostedToolCredential): boolean {
    return (
      identity(next, this.registration) ===
        identity(this.credential, this.registration) &&
      next.header === this.credential.header &&
      next.value === this.credential.value
    );
  }
  private async execute(
    tool: McpTool,
    offered: ToolDeclaration,
    call: ToolCall,
  ): Promise<ToolResult> {
    if (!this.active || this.busy)
      return rejected(
        'MCP context is unavailable or already executing. No upstream call was sent.',
      );
    const remaining = Math.min(
      Date.parse(call.deadline) - Date.now(),
      this.options.timeoutSeconds * 1000,
    );
    if (!Number.isFinite(remaining) || remaining <= 0)
      return rejected('MCP invocation expired before dispatch.');
    this.busy = true;
    const signal = AbortSignal.any([this.stop, AbortSignal.timeout(remaining)]);
    try {
      try {
        const binding = this.registration.binding;
        await validateMcpInvocation(
          {
            project: binding.project,
            provider: binding.route.provider,
            account: binding.owner,
          },
          offered,
          call,
        );
      } catch {
        return rejected(
          'MCP invocation did not match its owner, route or offered contract. No upstream call was sent.',
        );
      }
      let credential: HostedToolCredential;
      try {
        credential = await withinDeadline(
          () => this.credentials.release(call.invocationId),
          signal,
        );
        coordinates(credential, this.registration, call.invocationId);
        signal.throwIfAborted();
        if (!this.sameCredential(credential)) {
          this.active = false;
          return rejected('MCP credential context changed before dispatch.');
        }
      } catch {
        return rejected(
          'MCP invocation authorization could not be verified. No upstream call was sent.',
        );
      }
      // From this point any error is uncertain. Never refresh auth, fulfil input, or resend.
      try {
        const outcome = await this.client.invoke(
          tool.name,
          call.arguments,
          signal,
        );
        if (outcome.state === 'UNKNOWN') this.active = false;
        return outcome;
      } catch {
        this.active = false;
        return unknownResult();
      }
    } finally {
      this.busy = false;
    }
  }
  async attach(
    connection: ProviderConnection,
    journal: ToolJournal,
  ): Promise<ToolProvider> {
    const binding = this.registration.binding;
    return ToolProvider.create(
      connection,
      {
        project: binding.project,
        provider: binding.route.provider,
        account: binding.route.account,
      },
      this.tools,
      journal,
      nodeToolHost,
      this.registration,
    );
  }
  async close(): Promise<void> {
    this.active = false;
    await this.client.close();
  }
}
/** Persist catalogue submission intent before the SDK mutation; ambiguity remains actionable on disk. */
export async function publish(
  provider: ToolProvider,
  intent: CataloguePublicationIntent,
  action: 'publish' | 'withdraw',
): Promise<void> {
  const requestId = randomUUID();
  await intent.begin(requestId, action);
  if (action === 'publish') await provider.publishCatalog(requestId);
  else await provider.withdrawCatalog(requestId);
  await intent.settled();
}
