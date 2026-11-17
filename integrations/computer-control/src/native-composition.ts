import type { ComputerBackend } from './contracts.ts';
import type { NativeControlBackend, NativeToolName } from './native-control.ts';
import { checkedNativeTools } from './native-tool-arguments.ts';
import type { NativeArmingBackend } from './native-arming.ts';

/** Validate host-only capabilities before caller-owned handles/state transfer. */
export function nativeArmingComposition(
  native: readonly NativeControlBackend[],
  controls: readonly NativeArmingBackend[],
): readonly NativeArmingBackend[] {
  const copied = Object.freeze([...controls]);
  if (
    copied.length > 32 ||
    new Set(copied).size !== copied.length ||
    copied.some(
      (backend) =>
        !native.includes(backend) ||
        typeof backend.nativeArming?.request !== 'function' ||
        typeof backend.nativeArming.status !== 'function' ||
        typeof backend.nativeArming.revoke !== 'function',
    )
  )
    throw new Error(
      'Native arming requires exact configured native backend capabilities',
    );
  return copied;
}

export interface NativeComposition {
  readonly controls: readonly NativeControlBackend[];
  readonly tools: readonly NativeToolName[];
}

/** Disjoint ports prevent a session from being interpreted by two controllers. */
export function requireDistinctControlPorts(
  browser: readonly ComputerBackend[],
  native: readonly ComputerBackend[],
): void {
  if (native.some((backend) => browser.includes(backend)))
    throw new Error(
      'A backend cannot expose both native and browser control ports',
    );
}

/** Validate the exact injected native capabilities before filesystem or SDK effects. */
export function nativeComposition(
  backends: readonly ComputerBackend[],
  controls: readonly NativeControlBackend[],
  tools: readonly NativeToolName[],
): NativeComposition {
  if (backends.length < 1 || backends.length > 32)
    throw new Error('Computer provider requires 1..32 configured backends');
  const copied = Object.freeze([...controls]);
  const selected = checkedNativeTools(tools);
  // Snapshot before any await or ownership transfer. Unknown third-party
  // backends cannot silently acquire an independent physical input domain.
  const isolation = backends.map((backend) => backend.desktopIsolation);
  if (
    copied.length > 32 ||
    new Set(copied).size !== copied.length ||
    (copied.length === 0) !== (selected.length === 0)
  )
    throw new Error(
      'Native control capabilities and exact tool names must be configured together',
    );
  if (
    copied.length > 0 &&
    isolation.some((value) => value !== 'isolated' && value !== 'native-broker')
  )
    throw new Error(
      'Native control requires every backend to be isolated or join the shared native broker',
    );
  for (const backend of copied) {
    const index = backends.indexOf(backend);
    if (index < 0)
      throw new Error(
        'Native control requires the exact configured backend instance',
      );
    if (isolation[index] !== 'native-broker')
      throw new Error(
        'Native control must join the shared native desktop broker',
      );
  }
  const offered = checkedNativeTools([
    ...new Set(copied.flatMap((backend) => backend.nativeControl.allowedTools)),
  ]);
  if (JSON.stringify(offered) !== JSON.stringify(selected))
    throw new Error(
      'Native tool declarations must match the configured backend capabilities',
    );
  return Object.freeze({ controls: copied, tools: selected });
}
