/** Mirrors the existing public ToolProvider catalogue contract; this is not an MCP tuning knob. */
export const PROVIDER_CATALOGUE_TOOL_LIMIT = 128;

/** Operator budgets may be tightened; platform-owned envelope ceilings remain enforced. */
export interface McpLimits {
  readonly maxPages: number;
  readonly maxWireBytes: number;
  readonly maxCatalogueBytes: number;
  readonly maxResultBytes: number;
  readonly maxContentBlocks: number;
}
export interface McpClientInfo {
  readonly name: string;
  readonly version: string;
}
export const DEFAULT_CLIENT_INFO: McpClientInfo = Object.freeze({
  name: 'plowshare-mcp-integration',
  version: '0.1.0',
});
export const DEFAULT_LIMITS: McpLimits = Object.freeze({
  maxPages: 32,
  maxWireBytes: 1_048_576,
  maxCatalogueBytes: 60000,
  maxResultBytes: 16384,
  maxContentBlocks: 128,
});
/** Platform catalogue/text ceilings include publication envelope headroom.
 * The other ceilings bound operator-selected resource use rather than protocol semantics.
 */
export const LIMIT_CEILINGS: McpLimits = Object.freeze({
  maxPages: 1024,
  maxWireBytes: 16_777_216,
  maxCatalogueBytes: 60000,
  maxResultBytes: 16384,
  maxContentBlocks: 1024,
});
export function boundedInteger(
  value: unknown,
  min: number,
  max: number,
): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value < min ||
    value > max
  )
    throw new Error('Invalid bounded MCP configuration number');
  return value;
}
export function renewalFraction(value: unknown): number {
  if (
    typeof value !== 'number' ||
    !Number.isFinite(value) ||
    value < 0.05 ||
    value > 0.5
  )
    throw new Error('MCP renewal fraction must be between 0.05 and 0.5');
  return value;
}
