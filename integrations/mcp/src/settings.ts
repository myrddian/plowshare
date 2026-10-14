import { MAX_RELAY_TEXT_BYTES } from 'plowshare-client-ts/binding/relay-text';

/** Mirrors the existing public ToolProvider catalogue contract; this is not an MCP tuning knob. */
export const PROVIDER_CATALOGUE_TOOL_LIMIT = 128;

/** Operator budgets may be tightened; platform-owned envelope ceilings remain enforced. */
export interface McpLimits {
  readonly maxToolNameLength: number;
  readonly maxDescriptionLength: number;
  readonly maxCursorLength: number;
  readonly maxOfferedNameLength: number;
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
  maxToolNameLength: 128,
  maxDescriptionLength: 1024,
  maxCursorLength: 1024,
  maxOfferedNameLength: 256,
  maxPages: 32,
  maxWireBytes: 1_048_576,
  maxCatalogueBytes: 1_048_576,
  maxResultBytes: 16384,
  maxContentBlocks: 128,
});
/** Platform-owned ceilings are independent of MCP recommendations and operator budgets. */
export const LIMIT_CEILINGS: McpLimits = Object.freeze({
  // Portable ToolValue metadata strings and provider descriptions have 4,096-unit ceilings.
  maxToolNameLength: 4096,
  maxDescriptionLength: 4096,
  maxCursorLength: Number.MAX_SAFE_INTEGER,
  maxOfferedNameLength: 256,
  maxPages: Number.MAX_SAFE_INTEGER,
  maxWireBytes: Number.MAX_SAFE_INTEGER,
  maxCatalogueBytes: MAX_RELAY_TEXT_BYTES,
  maxResultBytes: 16384,
  maxContentBlocks: Number.MAX_SAFE_INTEGER,
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
    value <= 0 ||
    value >= 1
  )
    throw new Error(
      'MCP renewal fraction must be greater than zero and less than one',
    );
  return value;
}

/** Node timers must remain below their overflow threshold; the operator chooses the actual deadline. */
export const MAX_TIMER_MS = 2_147_483_647;
