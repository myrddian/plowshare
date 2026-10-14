import type {
  NativeCommand,
  NativeCommandResult,
  NativeManagedStatus,
  NativeProfile,
  NativeOwner,
} from 'plowshare-mcp-integration';
import {
  NATIVE_FIELDS,
  nativeDraft,
  nativeFormConfig,
  DEFAULT_LIMITS,
} from 'plowshare-mcp-integration/native-editor';
import type { NativeEnvironmentReference } from 'plowshare-mcp-integration/native-editor';
import type { ProviderManagement } from 'plowshare-client-ts/operations/provider-management';
import type { ProviderConnectionDefinition } from 'plowshare-client-ts/operations/provider-credentials';
import { decodePayload } from 'plowshare-client-ts/operations/schema';
import { randomUUID } from 'node:crypto';

export interface McpControlPrompt {
  show(message: string): void;
  read(masked?: boolean): Promise<string | undefined>;
}
class Cancelled extends Error {}
/** Guided forms use the same native command owner and server management capability as
 * direct commands. Prompts never replay mutations, store drafts or adopt another host.
 */
export async function mcpControls(options: {
  readonly prompt: McpControlPrompt;
  readonly project: string;
  readonly owner: NativeOwner;
  readonly current: () => boolean;
  readonly native: (command: NativeCommand) => Promise<NativeCommandResult>;
  readonly server: ProviderManagement;
  readonly authorize: (url: string) => Promise<void>;
}): Promise<void> {
  const { prompt } = options;
  async function native(command: NativeCommand) {
    if (!options.current()) throw new Cancelled();
    const result = await options.native(command);
    if (!options.current()) throw new Cancelled();
    return result;
  }
  async function ask(
    label: string,
    fallback = '',
    masked = false,
  ): Promise<string> {
    prompt.show(
      label +
        (fallback && !masked ? ` [${fallback}]` : '') +
        ' (/clear for empty; /cancel to return)',
    );
    const value = await prompt.read(masked);
    if (value === undefined || value === '/cancel' || !options.current())
      throw new Cancelled();
    return value === '/clear' ? '' : value === '' ? fallback : value;
  }
  async function choose(label: string, names: readonly string[]) {
    const value = await ask(
      label +
        '\n' +
        names.map((name, index) => `${index + 1}. ${name}`).join('\n'),
    );
    const index = Number(value) - 1;
    if (!Number.isInteger(index) || index < 0 || index >= names.length)
      throw new Cancelled();
    return index;
  }
  async function confirm(message: string) {
    if ((await ask(message + '\nType yes to submit once')) !== 'yes')
      throw new Cancelled();
  }
  async function nativeProfile(file: string): Promise<NativeProfile> {
    const result = await native({ action: 'profile-read', file });
    if (!options.current() || !('config' in result)) throw new Cancelled();
    return result;
  }
  async function editNative(file: string, existing?: NativeProfile) {
    const config = existing?.config,
      draft = nativeDraft(options.project, config);
    const kind =
      (await choose('Upstream transport', ['HTTP', 'STDIO'])) === 0
        ? 'http'
        : 'stdio';
    for (const [key, label, group] of NATIVE_FIELDS)
      if (group === 'common' || group === kind)
        draft[key] = await ask(label, draft[key]);
    const args: string[] = [],
      environment: NativeEnvironmentReference[] = [];
    if (kind === 'stdio') {
      const original =
        config?.transport.kind === 'stdio' ? config.transport : undefined;
      const count = Number(
        await ask(
          'Number of arguments (no shell; no secrets)',
          String(original?.args.length ?? 0),
        ),
      );
      if (!Number.isSafeInteger(count) || count < 0 || count > 65536)
        throw new Error('Invalid argument count');
      for (let index = 0; index < count; index++)
        args.push(
          await ask(`Argument ${index + 1}`, original?.args[index] ?? ''),
        );
      const refs = Object.entries(original?.environment ?? {});
      const envCount = Number(
        await ask(
          'Number of selected environment references',
          String(refs.length),
        ),
      );
      if (!Number.isSafeInteger(envCount) || envCount < 0 || envCount > 65536)
        throw new Error('Invalid environment count');
      for (let index = 0; index < envCount; index++)
        environment.push({
          name: await ask('Child environment name', refs[index]?.[0]),
          reference: await ask(
            'Parent environment reference',
            refs[index]?.[1],
          ),
        });
    }
    async function policy(label: string, fallback: boolean) {
      const answer = await ask(label + '? yes/no', fallback ? 'yes' : 'no');
      if (answer !== 'yes' && answer !== 'no')
        throw new Error('Network policy must be yes/no');
      return answer === 'yes';
    }
    const allowHttp =
      kind === 'http' &&
      (await policy(
        'Allow unencrypted HTTP',
        config?.transport.kind === 'http' && config.transport.allowHttp,
      ));
    const allowPrivateNetwork =
      kind === 'http' &&
      (await policy(
        'Allow private network endpoints',
        config?.transport.kind === 'http' &&
          config.transport.allowPrivateNetwork,
      ));
    const limits = { ...(config?.limits ?? DEFAULT_LIMITS) };
    if (
      (await ask('Edit timeouts and admission budgets? yes/no', 'no')) === 'yes'
    ) {
      for (const [key, label, group] of NATIVE_FIELDS)
        if (group === 'advanced') draft[key] = await ask(label, draft[key]);
      for (const key of Object.keys(limits) as (keyof typeof limits)[])
        limits[key] = Number(await ask(key, String(limits[key])));
    }
    const next = nativeFormConfig({
      owner: options.owner,
      draft,
      kind,
      args,
      environment,
      allowHttp,
      allowPrivateNetwork,
      limits,
    });
    await confirm(
      `Save private ${kind.toUpperCase()} profile ${next.instance}/${next.profile}? Saving does not launch it.`,
    );
    await native({
      action: 'profile-save',
      file,
      config: next,
      expectedRevision: existing?.revision ?? null,
    });
    prompt.show('Profile saved. Start remains explicit.');
  }
  async function local() {
    const action = await choose('This client · HTTP/STDIO · instance-owned', [
      'List hosts and tools',
      'Add profile',
      'Edit profile',
      'Review and start local host',
      'Stop host',
      'Review and share with server',
    ]);
    if (action === 0) {
      const result = await native({ action: 'list' });
      if (!Array.isArray(result)) throw new Error('Invalid native list');
      // Native command owner supplies a validated list; no process command or secret is printed.
      for (const host of result as readonly NativeManagedStatus[]) {
        prompt.show(
          `${host.instance}/${host.profile} · ${host.transport} · ${host.active ? 'available' : 'unavailable'} · ${host.sharing} · project ${host.project}`,
        );
        for (const tool of host.tools)
          prompt.show(
            `${tool.name}: ${tool.description}\n${JSON.stringify(tool.inputSchema ?? tool.parameters)}`,
          );
      }
      if (!result.length) prompt.show('No hosts in this client instance.');
      return;
    }
    if (action === 4) {
      const instance = await ask('Client instance ID'),
        profile = await ask('Connection name');
      await confirm(
        `Stop ${instance}/${profile} and withdraw any server sharing?`,
      );
      await native({ action: 'stop', instance, profile });
      prompt.show('Host stopped.');
      return;
    }
    const file = await ask(
      'Private profile file (absolute path in a private directory)',
    );
    if (action === 1) {
      await editNative(file);
      return;
    }
    const profile = await nativeProfile(file);
    if (action === 2) {
      await editNative(file, profile);
      return;
    }
    const result = await native({
      action: 'inspect',
      file,
      expectedRevision: profile.revision,
    });
    if (!('tools' in result)) throw new Error('Invalid native review');
    prompt.show(
      `${result.instance}/${result.profile} · ${result.transport} · project ${result.project}\n${result.review.endpoint ?? result.review.executable ?? ''} · ${result.review.argumentCount} arguments · environment names: ${result.review.environmentNames.join(', ') || 'none'}`,
    );
    if (action === 3) {
      await confirm(
        'Permit this instance to connect or launch the reviewed profile? No tools are shared with the server.',
      );
      await native({
        action: 'start',
        file,
        consent: true,
        expectedRevision: profile.revision,
      });
      prompt.show('Local host started.');
      return;
    }
    const config = profile.config;
    const split = (value: string) =>
      value
        .split(',')
        .map((text) => text.trim())
        .filter(Boolean);
    const scope = decodePayload('tool.scope.connect', {
      project: config.project,
      prefix: config.prefix,
      scope: await ask('Sharing scope'),
      provider: await ask('Provider name'),
      agents: split(await ask('Dynamic agents (comma separated)')),
      grants: split(await ask('Tool grants (comma separated)')),
      leaseSeconds: Number(await ask('Lease seconds', '120')),
    });
    const pollMs = Number(await ask('Poll interval milliseconds', '1000')),
      renewalFraction = Number(await ask('Lease renewal fraction', '0.5'));
    await confirm(
      `Launch and share ${config.instance}/${config.profile} with server project ${scope.project}? Existing server grants still apply.`,
    );
    await native({
      action: 'share',
      file,
      expectedRevision: profile.revision,
      consent: true,
      scope,
      pollMs,
      renewalFraction,
    });
    prompt.show('Explicit server sharing started.');
  }
  async function endpointDefinition(
    existing?: ProviderConnectionDefinition,
  ): Promise<ProviderConnectionDefinition> {
    const auth = ['API_KEY', 'OAUTH_PKCE', 'CLIENT_CREDENTIALS'] as const;
    const authentication = auth[await choose('Authentication', auth)];
    if (authentication === undefined) throw new Cancelled();
    const name = await ask('Endpoint name', existing?.name),
      endpoint = await ask('HTTPS MCP endpoint', existing?.endpoint);
    if (authentication === 'API_KEY') {
      const headers = ['Authorization', 'X-API-Key', 'Api-Key'] as const,
        header = headers[await choose('API key header', headers)];
      if (header === undefined) throw new Cancelled();
      return {
        name,
        endpoint,
        authentication,
        apiKeyHeader: header,
        oauth: null,
      };
    }
    return {
      name,
      endpoint,
      authentication,
      apiKeyHeader: null,
      oauth: {
        issuer: await ask('OAuth issuer', existing?.oauth?.issuer),
        resource: await ask('OAuth resource', existing?.oauth?.resource),
        resourceMetadataUrl: await ask(
          'Resource metadata URL',
          existing?.oauth?.resourceMetadataUrl,
        ),
        issuerMetadataUrl: await ask(
          'Issuer metadata URL',
          existing?.oauth?.issuerMetadataUrl,
        ),
        authorizationEndpoint: await ask(
          'Authorization endpoint',
          existing?.oauth?.authorizationEndpoint,
        ),
        tokenEndpoint: await ask(
          'Token endpoint',
          existing?.oauth?.tokenEndpoint,
        ),
        clientId: await ask('OAuth client ID', existing?.oauth?.clientId),
        redirectUri: await ask(
          'OAuth redirect URI',
          existing?.oauth?.redirectUri,
        ),
        scopes: (
          await ask(
            'OAuth scopes (space separated)',
            existing?.oauth?.scopes.join(' '),
          )
        )
          .split(/\s+/u)
          .filter(Boolean),
      },
    };
  }
  async function server() {
    const action = await choose(
      'Server · HTTP only · credentials follow your login',
      [
        'List connections and assignments',
        'Add endpoint',
        'Edit endpoint',
        'Enable/disable/delete endpoint',
        'Inspect or update authorization',
        'Add/edit assignment',
        'Enable/disable/delete assignment',
        'Inspect original unconfirmed receipts',
      ],
    );
    if (action === 7) {
      const pending = await options.server.pending();
      if (!pending.length) {
        prompt.show('No unconfirmed management requests.');
        return;
      }
      const intent =
        pending[
          await choose(
            'Read an original receipt (never resubmit)',
            pending.map(
              (row) => `${row.operation} · ${row.project} · ${row.requestId}`,
            ),
          )
        ];
      if (intent === undefined) throw new Cancelled();
      const receipt = await options.server.recover(intent);
      prompt.show(
        `Original ${receipt.operation} receipt ${receipt.requestId} confirmed; refresh current state separately.`,
      );
      return;
    }
    const credentialProject = await ask(
      'Credential project',
      options.project.startsWith('personal:') ? '' : options.project,
    );
    const connections = await options.server.request(
      'provider.connection.list',
      { project: credentialProject },
    );
    if (action === 0) {
      const bindings = await options.server.request('tool.scope.host.list', {
          project: options.project,
        }),
        active = await options.server.request('tool.scope.list', {
          project: options.project,
        });
      prompt.show(JSON.stringify({ connections, bindings, active }, null, 2));
      prompt.show(
        'Saved configuration and active routes are separate snapshots. Remote catalogue and worker health are not reported by these owner capabilities.',
      );
      return;
    }
    if (action === 6) {
      const bindings = await options.server.request('tool.scope.host.list', {
        project: options.project,
      });
      const binding =
        bindings[
          await choose(
            'Assignment',
            bindings.map((row) => row.definition.scope),
          )
        ];
      if (!binding) throw new Cancelled();
      const actions = ['ENABLE', 'DISABLE', 'DELETE'] as const,
        change = actions[await choose('Assignment action', actions)];
      if (!change) throw new Cancelled();
      await confirm(`${change} server assignment ${binding.definition.scope}?`);
      const receipt = await options.server.request('tool.scope.host.change', {
        project: options.project,
        binding: binding.binding,
        expectedGeneration: binding.generation,
        requestId: randomUUID(),
        action: change,
      });
      prompt.show(`Original receipt confirmed: ${receipt.requestId}`);
      return;
    }
    const selected =
      action === 1
        ? undefined
        : connections[
            await choose(
              'Shared HTTP endpoint',
              connections.map(
                (row) =>
                  `${row.definition.name} · ${row.enabled ? 'enabled' : 'disabled'}`,
              ),
            )
          ];
    if (action !== 1 && selected === undefined) throw new Cancelled();
    if (action === 1 || action === 2) {
      const definition = await endpointDefinition(selected?.definition);
      const payload = decodePayload('provider.connection.save', {
        project: credentialProject,
        connection: selected?.connection ?? randomUUID(),
        expectedRevision: selected?.revision ?? null,
        requestId: randomUUID(),
        definition,
      });
      await confirm(
        'Save server endpoint? Existing authorization is revoked on a revision change.',
      );
      const receipt = await options.server.request(
        'provider.connection.save',
        payload,
      );
      prompt.show(`Original receipt confirmed: ${receipt.requestId}`);
      return;
    }
    if (!selected) throw new Cancelled();
    if (action === 3) {
      const actions = ['ENABLE', 'DISABLE', 'DELETE'] as const,
        change = actions[await choose('Endpoint action', actions)];
      if (!change) throw new Cancelled();
      await confirm(
        `${change} shared server endpoint ${selected.definition.name}? Other assignments can be affected.`,
      );
      const receipt = await options.server.request(
        'provider.connection.change',
        {
          project: credentialProject,
          connection: selected.connection,
          expectedRevision: selected.revision,
          requestId: randomUUID(),
          action: change,
        },
      );
      prompt.show(`Original receipt confirmed: ${receipt.requestId}`);
      return;
    }
    const subject = await ask(
      'Service credential subject (empty uses my authorization)',
    );
    if (action === 4) {
      const target = {
        project: credentialProject,
        connection: selected.connection,
        subject: subject || null,
      };
      const credential = await options.server.request(
        'provider.credential.status',
        target,
      );
      prompt.show(
        `Authorization for ${credential.subject}: ${credential.state} · expires ${credential.expiresAt ?? 'not reported'}`,
      );
      const operation = await choose('Authorization action', [
        'Return',
        'Set API key/machine secret',
        'Authorize in browser',
        'Refresh token',
        'Revoke authorization',
      ]);
      if (operation === 0) return;
      const payload = {
        ...target,
        expectedGeneration: credential.generation,
        requestId: randomUUID(),
      };
      if (operation === 1) {
        if (selected.definition.authentication === 'OAUTH_PKCE')
          throw new Error('Use browser authorization');
        const secret = await ask('Credential material (masked)', '', true);
        await confirm(`Set authorization for ${credential.subject}?`);
        const receipt = await options.server.request(
          'provider.credential.put',
          { ...payload, secret },
        );
        prompt.show(`Original receipt confirmed: ${receipt.requestId}`);
      } else if (operation === 2) {
        await confirm(
          `Authorize ${credential.subject} in the upstream browser?`,
        );
        const result = await options.server.request(
          'provider.credential.authorize',
          payload,
        );
        prompt.show(
          `Authorization receipt confirmed: ${result.receipt.requestId}. Inspect current status after completion.`,
        );
        try {
          await options.authorize(result.authorizationUrl);
        } catch {
          prompt.show(
            'Authorization confirmed, but the browser could not be opened. Inspect current status before a new request.',
          );
        }
      } else {
        await confirm(
          `${operation === 3 ? 'Refresh' : 'Revoke'} authorization for ${credential.subject}?`,
        );
        const receipt = await options.server.request(
          operation === 3
            ? 'provider.credential.refresh'
            : 'provider.credential.revoke',
          payload,
        );
        prompt.show(`Original receipt confirmed: ${receipt.requestId}`);
      }
      return;
    }
    const bindings = await options.server.request('tool.scope.host.list', {
      project: options.project,
    });
    const index = await choose('Server assignment', [
        'New assignment',
        ...bindings.map((row) => row.definition.scope),
      ]),
      existing = index === 0 ? undefined : bindings[index - 1],
      definition = existing?.definition;
    const split = (value: string) =>
      value
        .split(',')
        .map((text) => text.trim())
        .filter(Boolean);
    const payload = decodePayload('tool.scope.host.save', {
      project: options.project,
      binding: existing?.binding ?? randomUUID(),
      requestId: randomUUID(),
      expectedGeneration: existing?.generation ?? null,
      definition: {
        credentialProject,
        connection: selected.connection,
        subject: subject || null,
        scope: await ask('Scope', definition?.scope),
        provider: await ask('Provider name', definition?.provider),
        prefix: await ask('Tool prefix', definition?.prefix),
        agents: split(
          await ask(
            'Dynamic agents (comma separated)',
            definition?.agents.join(', '),
          ),
        ),
        grants: split(
          await ask(
            'Tool grants (comma separated)',
            definition?.grants.join(', '),
          ),
        ),
        leaseSeconds: Number(
          await ask(
            'Catalogue lease seconds',
            String(definition?.leaseSeconds ?? 120),
          ),
        ),
        executor: await ask(
          'Operator-approved executor principal',
          definition?.executor,
        ),
      },
    });
    await confirm(
      `Save server assignment in ${options.project}? Credential subject, executor and accounting owner are separate; service credentials do not select a spending pool.`,
    );
    const receipt = await options.server.request(
      'tool.scope.host.save',
      payload,
    );
    prompt.show(`Original receipt confirmed: ${receipt.requestId}`);
  }
  try {
    if (
      (await choose('MCP connections', [
        'This client (HTTP/STDIO)',
        'Server (HTTP)',
      ])) === 0
    )
      await local();
    else await server();
  } catch (failure) {
    if (failure instanceof Cancelled) {
      prompt.show('Returned without another submission.');
      return;
    }
    prompt.show(
      'Action refused or unconfirmed. Inspect the selected profile or original server receipts before another action; no mutation was replayed.',
    );
  }
}
