import type { ComputerBackend } from './contracts.ts';
import type {
  BrowserControlBackend,
  BrowserToolName,
} from './browser-control.ts';
import { checkedBrowserTools } from './browser-tool-arguments.ts';

export interface BrowserComposition {
  readonly controls: readonly BrowserControlBackend[];
  readonly tools: readonly BrowserToolName[];
}

/**
 * Pure admission for the exact injected capability snapshot. Both direct SDK
 * providers and foreground hosts validate it before persistence, publication or
 * transfer of caller-owned handles. Configuration never silently grants a port.
 */
export function browserComposition(
  backends: readonly ComputerBackend[],
  controls: readonly BrowserControlBackend[],
  tools: readonly BrowserToolName[],
): BrowserComposition {
  if (backends.length < 1 || backends.length > 32)
    throw new Error('Computer provider requires 1..32 configured backends');
  const copied = Object.freeze([...controls]),
    selected = checkedBrowserTools(tools);
  if (copied.length > 32 || (copied.length === 0) !== (selected.length === 0))
    throw new Error(
      'Controlled browser capabilities and exact tool names must be explicitly configured together',
    );
  for (const backend of copied)
    if (!backends.includes(backend))
      throw new Error(
        'Browser control must use the same selected backend instance',
      );
  const offered = checkedBrowserTools([
    ...new Set(
      copied.flatMap((backend) => backend.browserControl.allowedTools),
    ),
  ]);
  if (JSON.stringify(offered) !== JSON.stringify(selected))
    throw new Error(
      'Browser tool declarations must match the configured backend capabilities',
    );
  return Object.freeze({ controls: copied, tools: selected });
}
