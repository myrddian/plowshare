import { decodeToolResult } from 'plowshare-client-ts';
import type { ToolCall, ToolResult } from 'plowshare-client-ts';

/**
 * Owning output boundary for already-typed provider metadata. Enforce the actual
 * escaped content envelope as well as its inner text bound; an action handler
 * prevalidates a minimal outcome through this same codec before dispatch.
 */
export function validateComputerResult(
  call: ToolCall,
  result: ToolResult,
  producer: string,
): ToolResult {
  const checked = decodeToolResult(result, call, producer);
  const wire = JSON.stringify({
    schema: 'plowshare-tool/3',
    invocationId: call.invocationId,
    project: call.project,
    provider: call.provider,
    tool: call.tool,
    state: checked.state,
    content: checked.content,
  });
  if (wire.length > 32768)
    throw new Error('Computer result exceeds the encoded envelope limit');
  return checked;
}

export function computerTextResult(
  call: ToolCall,
  producer: string,
  metadata: object,
  state: ToolResult['state'] = 'COMPLETED',
): ToolResult {
  const text = JSON.stringify(metadata);
  if (text.length > 12000)
    throw new Error('Computer metadata exceeds its bound');
  return validateComputerResult(call, { state, text }, producer);
}
