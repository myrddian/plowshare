import type {
  ToolBinding,
  ToolCall,
  ToolDeclaration,
} from 'plowshare-client-ts';
import {
  nodeToolHost,
  validateToolArguments,
} from 'plowshare-client-node/tools';
import { matchesToolSchemaFingerprint } from 'plowshare-client-ts/operations/tool-schemas';

/** Check the SDK-owned invocation again at the external-effect boundary. The execution account
 * is the admitted caller, not a hosted worker or upstream credential subject. This check cannot
 * grant authority: hosted handlers still require exact server credential release, and native
 * sharing still requires the original socket's scope and SDK journal.
 */
export async function validateMcpInvocation(
  binding: ToolBinding,
  tool: ToolDeclaration,
  call: ToolCall,
): Promise<void> {
  if (
    call.project !== binding.project ||
    call.provider !== binding.provider ||
    call.account !== binding.account ||
    call.tool !== tool.name
  )
    throw new Error('Foreign MCP invocation');
  if (
    tool.inputSchema === undefined ||
    call.schema !== 'plowshare-tool/2' ||
    !(await matchesToolSchemaFingerprint(
      tool.inputSchema,
      call.schemaFingerprint,
      (value) => nodeToolHost.sha256(value),
    ))
  )
    throw new Error('MCP invocation does not match the offered schema');
  validateToolArguments(tool, call.arguments);
}
