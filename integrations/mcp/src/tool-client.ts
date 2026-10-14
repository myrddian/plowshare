import {
  validateSchemaArguments,
  decodeToolValue,
} from 'plowshare-client-ts/operations/tool-schemas';
import type { ToolObject, ToolResult, ToolValue } from 'plowshare-client-ts';
import type { McpSession, McpTool } from './contracts.ts';
import { unknownResult, rejected } from './contracts.ts';
import { contractHash, result } from './codec.ts';
import { DEFAULT_LIMITS } from './settings.ts';
import type { McpLimits } from './settings.ts';

/** Complete immutable discovery; pagination never merges independently published catalogues. */
export async function discoverTools(
  session: McpSession,
  signal: AbortSignal,
  limits: McpLimits,
): Promise<readonly McpTool[]> {
  const tools: McpTool[] = [];
  const cursors = new Set<string>();
  let cursor: string | null = null;
  for (let page = 0; page < limits.maxPages; page++) {
    signal.throwIfAborted();
    const next = await session.page(cursor, signal);
    tools.push(...next.tools);
    if (
      Buffer.byteLength(JSON.stringify(tools.map((tool) => tool.metadata))) >
      limits.maxCatalogueBytes
    )
      throw new Error('MCP discovery exceeds operator catalogue budget');
    if (new Set(tools.map((tool) => tool.name)).size !== tools.length)
      throw new Error('Duplicate or oversized MCP catalogue');
    if (next.cursor === null) {
      if (!tools.length)
        throw new Error('MCP catalogue has no supported tools');
      return Object.freeze(
        tools.sort((a, b) => a.name.localeCompare(b.name, 'en')),
      );
    }
    if (cursors.has(next.cursor))
      throw new Error('MCP pagination repeated a cursor');
    cursors.add(next.cursor);
    cursor = next.cursor;
  }
  throw new Error('MCP discovery exceeded page bound');
}

/** Direct in-process client. No Plowshare connection, provider publication or Relay dependency.
 * A snapshot is pinned until close; failed/changed contexts refuse further effects. The caller
 * owns consent, authorization and durable execution intent before invoking a handler.
 */
export class McpToolClient {
  private active = true;
  private busy = false;
  private constructor(
    private readonly session: McpSession,
    readonly tools: readonly McpTool[],
    private readonly limits: McpLimits,
  ) {}
  static async discover(
    session: McpSession,
    signal: AbortSignal,
    limits: McpLimits = DEFAULT_LIMITS,
  ): Promise<McpToolClient> {
    return new McpToolClient(
      session,
      await discoverTools(session, signal, limits),
      limits,
    );
  }
  async refresh(signal: AbortSignal): Promise<void> {
    if (!this.active) throw new Error('MCP context is unavailable');
    const fresh = await discoverTools(this.session, signal, this.limits);
    if (
      contractHash(fresh.map((tool) => tool.metadata)) !==
      contractHash(this.tools.map((tool) => tool.metadata))
    ) {
      this.active = false;
      throw new Error('MCP catalogue changed; stop and review a new snapshot');
    }
  }
  async invoke(
    name: string,
    arguments_: ToolObject,
    signal: AbortSignal,
  ): Promise<ToolResult> {
    if (!this.active || this.busy || signal.aborted)
      return rejected('MCP context is unavailable; no call was sent.');
    const tool = this.tools.find((tool) => tool.name === name);
    if (tool === undefined)
      return rejected(
        'MCP tool was not in the reviewed snapshot; no call was sent.',
      );
    // Copy/validate at the direct boundary as well as at a server provider's boundary.
    const values = decodeToolValue(arguments_);
    validateSchemaArguments(tool.input, values);
    if (!isToolObject(values))
      throw new Error('MCP arguments must be an object');
    this.busy = true;
    try {
      return result(
        tool,
        await this.session.call(tool, values, signal),
        this.limits.maxResultBytes,
      );
    } catch {
      this.active = false;
      return unknownResult();
    } finally {
      this.busy = false;
    }
  }
  async close(): Promise<void> {
    this.active = false;
    await this.session.close();
  }
}

function isToolObject(value: ToolValue): value is ToolObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
