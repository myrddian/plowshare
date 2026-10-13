import { createHash } from 'node:crypto';
import { canonicalToolSchema } from 'plowshare-client-ts/operations/tool-schemas';
import type {
  HostedToolRegistration,
  HostedToolCredential,
  ToolCall,
  ToolJournal,
  ToolReceipt,
} from 'plowshare-client-ts';
import type { McpCredentials } from './provider.ts';
import { decodeMcpTool } from './codec.ts';
import { rawTool } from './wire.test-support.ts';
import type {
  McpSessions,
  McpSession,
  McpSessionOptions,
  McpTool,
  McpReply,
} from './contracts.ts';

export const id = '11111111-1111-1111-1111-111111111111';
export const secondId = '22222222-2222-2222-2222-222222222222';
export const executor = '@service/' + secondId;
export const routedProvider = 'host-' + id.replaceAll('-', '');
export const registration: HostedToolRegistration = {
  registration: secondId,
  registeredAt: '2026-01-01T00:00:00Z',
  expiresAt: '2099-01-01T00:00:00Z',
  binding: {
    binding: id,
    project: 'fixture',
    owner: 'owner',
    generation: 1,
    enabled: true,
    deleted: false,
    application: false,
    credentialSubject: 'owner',
    definition: {
      scope: 'mcp',
      provider: 'mcp-fixture',
      prefix: 'mcp_',
      grants: ['*'],
      agents: ['agent'],
      leaseSeconds: 60,
      credentialProject: 'fixture',
      connection: id,
      subject: null,
      executor,
    },
    route: {
      project: 'fixture',
      scope: 'mcp',
      sourceProvider: 'mcp-fixture',
      provider: routedProvider,
      account: executor,
      prefix: 'mcp_',
      grants: ['*'],
      agents: ['agent'],
      leaseSeconds: 60,
    },
  },
};
export class Credentials implements McpCredentials {
  readonly invocations: (string | null)[] = [];
  denied = false;
  credential: HostedToolCredential = {
    binding: id,
    registration: secondId,
    generation: 1,
    purpose: 'DISCOVERY',
    invocation: null,
    subject: 'owner',
    credentialGeneration: secondId,
    header: 'Authorization',
    value: 'Bearer fixture-secret',
    connection: {
      project: 'fixture',
      connection: id,
      revision: secondId,
      enabled: true,
      deleted: false,
      definition: {
        name: 'fixture',
        endpoint: 'https://example.invalid/mcp',
        authentication: 'API_KEY',
        apiKeyHeader: 'Authorization',
        oauth: null,
      },
    },
  };
  async release(invocation: string | null): Promise<HostedToolCredential> {
    this.invocations.push(invocation);
    if (this.denied) throw new Error('Refused fixture release');
    return {
      ...this.credential,
      purpose: invocation === null ? 'DISCOVERY' : 'EXECUTION',
      invocation,
    };
  }
}
export class Sessions implements McpSessions, McpSession {
  tools: readonly McpTool[] = [decodeMcpTool(rawTool)];
  nextCursor: string | null = null;
  pages = 0;
  calls = 0;
  opens: McpSessionOptions[] = [];
  fail = false;
  closed = false;
  reply: McpReply = {
    content: ['received'],
    structured: undefined,
    isError: false,
  };
  async open(options: McpSessionOptions): Promise<McpSession> {
    this.opens.push(options);
    return this;
  }
  async page() {
    this.pages++;
    return { tools: this.tools, cursor: this.nextCursor };
  }
  async call() {
    this.calls++;
    if (this.fail) throw new Error('Lost reply');
    return this.reply;
  }
  async close(): Promise<void> {
    this.closed = true;
  }
}
export const options = {
  protocol: '2026-07-28',
  policy: { allowHttp: false, allowPrivateNetwork: false, timeoutMs: 2000 },
  timeoutSeconds: 30,
} as const;
export function invocation(tool: string): ToolCall {
  return {
    schema: 'plowshare-tool/2',
    schemaFingerprint: createHash('sha256')
      .update(canonicalToolSchema(decodeMcpTool(rawTool).input))
      .digest('hex'),
    invocationId: id,
    project: 'fixture',
    provider: routedProvider,
    tool,
    account: 'original-caller',
    run: id,
    call: secondId,
    deadline: '2099-01-01T00:00:00Z',
    arguments: { values: [{ name: 'nested' }] },
  };
}
export class Journal implements ToolJournal {
  readonly rows = new Map<string, ToolReceipt>();
  constructor(readonly identity: string) {}
  async all() {
    return [...this.rows.values()];
  }
  async save(row: ToolReceipt) {
    this.rows.set(row.invocationId, row);
  }
}
