import { ConnectionFault } from '../binding/connection.ts';
import { fieldsOf } from './response.ts';
import { requirePayload } from './sdk.ts';
import type { Plowshare } from './sdk.ts';

/** The same closed scalar vocabulary is used by every language's tool façade. */
export type ToolScalar = string | number | boolean;
export type ToolArguments = Readonly<Record<string, ToolScalar>>;
export type ToolState = 'COMPLETED' | 'REJECTED' | 'UNKNOWN';
export interface ToolParameter {
  readonly name: string;
  readonly type: 'STRING' | 'NUMBER' | 'INTEGER' | 'BOOLEAN';
  readonly description: string;
  readonly required: boolean;
}
export interface ToolDeclaration {
  readonly name: string;
  readonly description: string;
  readonly parameters: readonly ToolParameter[];
  readonly timeoutSeconds: number;
}
export interface ToolCall {
  readonly invocationId: string;
  readonly project: string;
  readonly provider: string;
  readonly tool: string;
  readonly account: string;
  readonly run: string;
  readonly call: string;
  readonly deadline: string;
  readonly arguments: ToolArguments;
}
export interface ToolResult {
  readonly state: ToolState;
  readonly text: string;
}
export interface RegisteredTool {
  readonly declaration: ToolDeclaration;
  readonly handler: (call: ToolCall) => Promise<ToolResult>;
}
export interface ToolReceipt {
  readonly invocationId: string;
  readonly request: string;
  readonly phase: 'executing' | 'ready' | 'publishing' | 'done';
  readonly result: ToolResult | null;
  readonly occurredAt: string | null;
}
/** Exclusive provider-owned persistence. Save must finish durably before returning. */
export interface ToolJournal {
  readonly identity: string;
  all(): Promise<readonly ToolReceipt[]>;
  save(receipt: ToolReceipt): Promise<void>;
}
/** Host facilities are explicit in the platform-neutral SDK. Node supplies an implementation. */
export interface ToolHost {
  uuid(): string;
  sha256(text: string): Promise<string>;
  now(): Date;
  execute(
    handler: RegisteredTool['handler'],
    call: ToolCall,
    deadline: Date,
  ): Promise<ToolResult>;
}
export interface ToolBinding {
  readonly project: string;
  readonly provider: string;
  readonly account: string;
}

const VERSION = 'plowshare-tool/1';
function identity(value: string): string {
  if (
    typeof value !== 'string' ||
    !value ||
    value.length > 256 ||
    value !== value.trim() ||
    Array.from(value).some((character) => {
      const point = character.codePointAt(0) ?? 0;
      return (
        point < 32 ||
        (point >= 127 && point <= 159) ||
        point === 0x2028 ||
        point === 0x2029
      );
    })
  )
    throw new Error('Invalid tool identity');
  return value;
}
function text(value: string, bound: number, nonblank = false): string {
  if (
    typeof value !== 'string' ||
    value.length > bound ||
    value.includes('\0') ||
    (nonblank && !value.trim())
  )
    throw new Error('Invalid tool text');
  return value;
}
function uuid(value: string): string {
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value))
    throw new Error('Invalid tool UUID');
  return value;
}
function instant(value: string): number {
  if (!value.endsWith('Z') || !Number.isFinite(Date.parse(value)))
    throw new Error('Invalid UTC tool timestamp');
  return Date.parse(value);
}
function declaration(value: ToolDeclaration): void {
  if (
    !/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/.test(value.name) ||
    value.name.length > 64
  )
    throw new Error('Invalid tool name');
  text(value.description, 4096, true);
  if (
    !Number.isInteger(value.timeoutSeconds) ||
    value.timeoutSeconds < 1 ||
    value.timeoutSeconds > 300 ||
    value.parameters.length > 32 ||
    new Set(value.parameters.map((p) => p.name)).size !==
      value.parameters.length
  )
    throw new Error('Invalid tool declaration');
  for (const p of value.parameters) {
    if (
      !/^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/.test(p.name) ||
      !['STRING', 'NUMBER', 'INTEGER', 'BOOLEAN'].includes(p.type) ||
      typeof p.required !== 'boolean'
    )
      throw new Error('Invalid tool parameter');
    text(p.description, 4096);
  }
}
function normalizedDecimal(source: string): string {
  const [mantissa = '', exponent = '0'] = source.toLowerCase().split('e');
  const negative = mantissa.startsWith('-');
  const digits = mantissa
    .replace(/[-.]/g, '')
    .replace(/^0+/, '')
    .replace(/0+$/, '');
  if (!digits) return '0';
  const trailing =
    mantissa.replace(/[-.]/g, '').length -
    mantissa.replace(/[-.]/g, '').replace(/0+$/, '').length;
  const scale =
    Number(exponent) - (mantissa.split('.')[1] ?? '').length + trailing;
  return `${negative ? '-' : ''}${digits}e${scale}`;
}
function portableNumber(value: number): boolean {
  const [mantissa = '', exponent = '0'] = value
    .toString()
    .toLowerCase()
    .split('e');
  const fraction = (mantissa.split('.')[1] ?? '').replace(/0+$/, '').length;
  return (
    Number.isFinite(value) &&
    Math.abs(value) <= Number.MAX_SAFE_INTEGER &&
    fraction - Number(exponent) <= 18
  );
}
export function validateToolArguments(
  tool: ToolDeclaration,
  args: ToolArguments,
): void {
  declaration(tool);
  if (
    Object.keys(args).some(
      (key) => !tool.parameters.some((p) => p.name === key),
    )
  )
    throw new Error('Unknown tool argument');
  for (const p of tool.parameters) {
    if (!Object.hasOwn(args, p.name)) {
      if (p.required) throw new Error('Required tool argument missing');
      continue;
    }
    const value = args[p.name];
    const valid =
      (p.type === 'STRING' && typeof value === 'string') ||
      (p.type === 'BOOLEAN' && typeof value === 'boolean') ||
      ((p.type === 'NUMBER' || p.type === 'INTEGER') &&
        typeof value === 'number' &&
        portableNumber(value) &&
        (p.type !== 'INTEGER' || Number.isInteger(value)));
    if (!valid) throw new Error('Tool argument type mismatch');
    if (typeof value === 'string') text(value, 4096);
  }
}
export function toolDeploymentConfig(
  binding: ToolBinding,
  tools: readonly ToolDeclaration[],
): string {
  identity(binding.project);
  identity(binding.account);
  if (
    !/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(binding.provider) ||
    binding.provider.length > 48 ||
    !tools.length ||
    tools.length > 256 ||
    new Set(tools.map((t) => t.name)).size !== tools.length
  )
    throw new Error('Invalid tool binding');
  tools.forEach(declaration);
  return JSON.stringify(
    {
      plowshare: {
        relay: {
          tools: { bindings: tools.map((t) => ({ ...binding, ...t })) },
        },
      },
    },
    null,
    2,
  );
}

/** Validate the entire envelope, including duplicate keys, before returning a typed call. */
export function decodeToolCall(source: string): ToolCall {
  text(source, 32768, true);
  const parsed: unknown = JSON.parse(source);
  const tokens =
    source.match(
      /"(?:\\.|[^"\\])*"|[{}[\]:,]|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null/g,
    ) ?? [];
  const stack: (Set<string> | null)[] = [];
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (
      token &&
      /^-?\d/.test(token) &&
      normalizedDecimal(token) !== normalizedDecimal(Number(token).toString())
    )
      throw new Error('Tool number cannot round-trip across SDK languages');
    if (token === '{') stack.push(new Set());
    else if (token === '[') stack.push(null);
    else if (token === '}' || token === ']') stack.pop();
    else if (token?.startsWith('"') && tokens[i + 1] === ':') {
      const key: unknown = JSON.parse(token);
      const names = stack.at(-1);
      if (typeof key !== 'string' || names == null || names.has(key))
        throw new Error('Duplicate tool field');
      names.add(key);
    }
  }
  const row = fieldsOf(parsed);
  const keys = [
    'schema',
    'invocationId',
    'project',
    'provider',
    'tool',
    'account',
    'run',
    'call',
    'deadline',
    'arguments',
  ];
  if (
    Object.keys(row).length !== keys.length ||
    keys.some((key) => !(key in row)) ||
    row['schema'] !== VERSION
  )
    throw new Error('Invalid tool envelope');
  const read = (key: string): string => {
    const v = row[key];
    if (typeof v !== 'string') throw new Error('Invalid tool field');
    return identity(v);
  };
  const args = fieldsOf(row['arguments']);
  if (
    row['arguments'] === null ||
    typeof row['arguments'] !== 'object' ||
    Array.isArray(row['arguments'])
  )
    throw new Error('Invalid tool arguments');
  const values: Record<string, ToolScalar> = {};
  for (const [key, value] of Object.entries(args)) {
    if (
      !['string', 'number', 'boolean'].includes(typeof value) ||
      (typeof value !== 'string' &&
        typeof value !== 'number' &&
        typeof value !== 'boolean')
    )
      throw new Error('Invalid tool scalar');
    if (!/^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/.test(key))
      throw new Error('Invalid argument name');
    if (typeof value === 'string') text(value, 4096);
    if (typeof value === 'number' && !portableNumber(value))
      throw new Error('Invalid tool number');
    // Defining an own field preserves valid names such as __proto__ without invoking setters.
    Object.defineProperty(values, key, { value, enumerable: true });
  }
  if (Object.keys(values).length > 32) throw new Error('Too many arguments');
  const provider = read('provider');
  const tool = read('tool');
  if (
    !/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(provider) ||
    provider.length > 48 ||
    !/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/.test(tool) ||
    tool.length > 64
  )
    throw new Error('Invalid tool routing');
  const id = uuid(read('invocationId'));
  const deadline = read('deadline');
  instant(deadline);
  return Object.freeze({
    invocationId: id,
    project: read('project'),
    provider,
    tool,
    account: read('account'),
    run: read('run'),
    call: read('call'),
    deadline,
    arguments: Object.freeze(values),
  });
}
function resultText(call: ToolCall, result: ToolResult | null): string {
  if (
    result === null ||
    !['COMPLETED', 'REJECTED', 'UNKNOWN'].includes(result.state)
  )
    throw new Error('Missing tool result');
  text(result.text, 16384, true);
  const encoded = JSON.stringify({
    schema: VERSION,
    invocationId: call.invocationId,
    project: call.project,
    provider: call.provider,
    tool: call.tool,
    state: result.state,
    text: result.text,
  });
  text(encoded, 32768, true);
  return encoded;
}
export async function toolResultRequestId(
  host: ToolHost,
  id: string,
): Promise<string> {
  uuid(id);
  const hex = (await host.sha256('tool-result:' + id)).slice(0, 32);
  if (!/^[0-9a-f]{32}$/.test(hex))
    throw new Error('Host returned an invalid SHA-256 digest');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Normal SDK requests underneath; transport mutations are never automatically replayed. */
export class ToolProvider {
  private readonly consumer: string;
  private busy = false;
  private readonly connection: Pick<Plowshare, 'request'>;
  private readonly binding: ToolBinding;
  private readonly tools: readonly RegisteredTool[];
  private readonly journal: ToolJournal;
  private readonly host: ToolHost;
  private readonly publisher: string;
  private constructor(
    connection: Pick<Plowshare, 'request'>,
    binding: ToolBinding,
    tools: readonly RegisteredTool[],
    journal: ToolJournal,
    host: ToolHost,
    publisher: string,
  ) {
    this.connection = connection;
    this.binding = Object.freeze({ ...binding });
    this.tools = Object.freeze(
      tools.map((tool) =>
        Object.freeze({
          handler: tool.handler,
          declaration: Object.freeze({
            ...tool.declaration,
            parameters: Object.freeze(
              tool.declaration.parameters.map((p) => Object.freeze({ ...p })),
            ),
          }),
        }),
      ),
    );
    this.journal = journal;
    this.host = host;
    this.publisher = publisher;
    this.consumer = uuid(host.uuid());
  }
  static async create(
    connection: Pick<Plowshare, 'request'>,
    binding: ToolBinding,
    tools: readonly RegisteredTool[],
    journal: ToolJournal,
    host: ToolHost,
  ): Promise<ToolProvider> {
    const config = toolDeploymentConfig(
      binding,
      tools.map((t) => t.declaration),
    );
    if (journal.identity !== (await host.sha256(config)))
      throw new Error('Foreign tool journal');
    return new ToolProvider(
      connection,
      binding,
      tools,
      journal,
      host,
      'sdk:' + (await host.sha256(binding.account)),
    );
  }
  private topic(tool: string, kind: 'request' | 'result'): string {
    return `tool.${this.binding.provider}.${tool}.${kind}`;
  }
  async poll(): Promise<number> {
    if (this.busy) throw new Error('Tool provider already has an active pass');
    this.busy = true;
    try {
      for (const receipt of await this.receipts()) {
        if (receipt.phase === 'publishing')
          throw new Error('Uncertain tool result; reconcile before polling');
        let pending = receipt;
        if (receipt.phase === 'executing') {
          pending = {
            ...receipt,
            phase: 'ready',
            result: {
              state: 'UNKNOWN',
              text: 'Provider restarted after execution intent; external effects may have occurred.',
            },
            occurredAt: null,
          };
          await this.journal.save(pending);
        }
        if (pending.phase === 'ready') await this.publish(pending);
      }
      let count = 0;
      for (const registered of this.tools) {
        const name = registered.declaration.name;
        const topic = this.topic(name, 'request');
        const batch = requirePayload(
          await this.connection.request('relay.consume', {
            project: this.binding.project,
            topic,
            group: 'tool-provider',
            consumerId: this.consumer,
            start: 'OLDEST_RETAINED',
            limit: 1,
            waitMs: 0,
          }),
        );
        if (batch.status === 'GAP')
          throw new Error(
            'Tool request history expired; inspect before acknowledging',
          );
        if (batch.status !== 'DATA') continue;
        const event = batch.events[0];
        if (
          !event ||
          event.publisher !== 'tool-runtime' ||
          event.payload.kind !== 'TEXT' ||
          event.payload.text === null
        )
          throw new Error('Invalid tool request publisher or payload');
        const call = decodeToolCall(event.payload.text);
        if (
          call.project !== this.binding.project ||
          call.provider !== this.binding.provider ||
          call.tool !== name ||
          call.invocationId !== event.eventId ||
          call.invocationId !== event.correlationId
        )
          throw new Error('Foreign tool invocation');
        validateToolArguments(registered.declaration, call.arguments);
        let receipt = (await this.receipts()).find(
          (r) => r.invocationId === call.invocationId,
        );
        const fresh = receipt === undefined;
        if (receipt && receipt.request !== event.payload.text)
          throw new Error('Conflicting tool invocation identity');
        if (!receipt) {
          receipt = {
            invocationId: call.invocationId,
            request: event.payload.text,
            phase: 'executing',
            result: null,
            occurredAt: null,
          };
          await this.journal.save(receipt);
        }
        if (batch.batchId === null || batch.fence === null)
          throw new Error('Missing tool acknowledgement authority');
        requirePayload(
          await this.connection.request('relay.ack', {
            project: this.binding.project,
            topic,
            group: 'tool-provider',
            consumerId: this.consumer,
            batchId: batch.batchId,
            fence: batch.fence,
          }),
        );
        if (fresh) {
          let result: ToolResult;
          if (instant(call.deadline) <= this.host.now().getTime())
            result = {
              state: 'REJECTED',
              text: 'Tool deadline expired before execution; no handler ran.',
            };
          else {
            try {
              result = await this.host.execute(
                registered.handler,
                call,
                new Date(
                  Math.min(
                    instant(call.deadline),
                    this.host.now().getTime() +
                      registered.declaration.timeoutSeconds * 1000,
                  ),
                ),
              );
              resultText(call, result);
            } catch {
              result = {
                state: 'UNKNOWN',
                text: 'Handler ended after execution intent; external effects may have occurred.',
              };
            }
          }
          receipt = { ...receipt, phase: 'ready', result };
          await this.journal.save(receipt);
        }
        if (receipt.phase === 'ready') await this.publish(receipt);
        count++;
      }
      return count;
    } finally {
      this.busy = false;
    }
  }
  async reconcile(): Promise<number> {
    if (this.busy) throw new Error('Tool provider already has an active pass');
    this.busy = true;
    try {
      let count = 0;
      for (const receipt of await this.receipts()) {
        if (receipt.phase !== 'publishing') continue;
        const call = decodeToolCall(receipt.request);
        const id = await toolResultRequestId(this.host, call.invocationId);
        let after = '0';
        let settled = false;
        for (let pageNumber = 0; pageNumber < 100; pageNumber++) {
          const page = requirePayload(
            await this.connection.request('relay.log', {
              project: this.binding.project,
              system: false,
              topic: this.topic(call.tool, 'result'),
              after,
              limit: 100,
            }),
          );
          const event = page.events.find((e) => e.eventId === id);
          if (event) {
            if (
              event.publisher !== this.publisher ||
              event.payload.kind !== 'TEXT' ||
              event.payload.text !== resultText(call, receipt.result) ||
              event.correlationId !== call.invocationId ||
              event.causationId !== call.invocationId ||
              receipt.occurredAt === null ||
              instant(event.occurredAt) !== instant(receipt.occurredAt)
            )
              throw new Error('Conflicting retained provider result');
            await this.journal.save({ ...receipt, phase: 'done' });
            count++;
            settled = true;
            break;
          }
          const last = page.events.at(-1);
          if (!last || last.position === after) {
            settled = true;
            break;
          }
          after = last.position;
        }
        if (!settled)
          throw new Error('Tool reconciliation exceeded retained read bound');
      }
      return count;
    } finally {
      this.busy = false;
    }
  }
  private async receipts(): Promise<readonly ToolReceipt[]> {
    const rows = await this.journal.all();
    if (
      rows.length > 1000 ||
      new Set(rows.map((r) => r.invocationId)).size !== rows.length
    )
      throw new Error('Invalid tool journal size or identities');
    for (const receipt of rows) {
      const call = decodeToolCall(receipt.request);
      const tool = this.tools.find((t) => t.declaration.name === call.tool);
      if (
        receipt.invocationId !== call.invocationId ||
        call.project !== this.binding.project ||
        call.provider !== this.binding.provider ||
        !tool
      )
        throw new Error('Foreign tool receipt');
      validateToolArguments(tool.declaration, call.arguments);
      if (
        !['executing', 'ready', 'publishing', 'done'].includes(receipt.phase) ||
        (receipt.phase === 'executing') !== (receipt.result === null) ||
        (receipt.phase === 'publishing' || receipt.phase === 'done') !==
          (receipt.occurredAt !== null)
      )
        throw new Error('Invalid tool receipt phase');
      if (receipt.result !== null) resultText(call, receipt.result);
      if (receipt.occurredAt !== null) instant(receipt.occurredAt);
    }
    return rows;
  }
  private async publish(receipt: ToolReceipt): Promise<void> {
    const call = decodeToolCall(receipt.request);
    const encoded = resultText(call, receipt.result);
    const occurredAt = this.host.now().toISOString();
    const id = await toolResultRequestId(this.host, call.invocationId);
    const intent = { ...receipt, phase: 'publishing' as const, occurredAt };
    await this.journal.save(intent);
    // Only proven non-submission restores READY. Uncertain delivery requires retained reads.
    try {
      requirePayload(
        await this.connection.request('relay.publish', {
          project: this.binding.project,
          topic: this.topic(call.tool, 'result'),
          requestId: id,
          occurredAt,
          correlationId: call.invocationId,
          parentTopic: this.topic(call.tool, 'request'),
          parentEventId: call.invocationId,
          text: encoded,
        }),
      );
    } catch (error) {
      if (
        error instanceof ConnectionFault &&
        error.delivery === 'NOT_SUBMITTED'
      )
        await this.journal.save(receipt);
      throw error;
    }
    await this.journal.save({ ...intent, phase: 'done' });
  }
}
