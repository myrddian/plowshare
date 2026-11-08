/**
 * Bindings from the Application hub to the Activity and Relay documents.
 *
 * Those documents do not share the hub's DOM, and the desktop `activity` and
 * `relay` requests cannot carry a project. All three documents are the same
 * origin, so the hub writes the binding here and the open document reads it.
 * A relay binding is one-shot: `at` increases on every hub open so a later
 * manual scope change is left alone, including after the Relay document is
 * created again.
 */
export const applicationScheduleProjectKey =
  'plowshare.desktop.application-schedule';
export const applicationRelayScopeKey = 'plowshare.desktop.application-relay';

export interface RelayScopeBinding {
  readonly project: string;
  readonly at: number;
}

let relayWrites = 0;

export function writeRelayScope(project: string): void {
  relayWrites += 1;
  const binding: RelayScopeBinding = {
    project,
    at: Date.now() * 1000 + relayWrites,
  };
  localStorage.setItem(applicationRelayScopeKey, JSON.stringify(binding));
}

export function readRelayScope(): RelayScopeBinding | undefined {
  const raw = localStorage.getItem(applicationRelayScopeKey);
  if (!raw) return undefined;
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value ||
      typeof value !== 'object' ||
      !('project' in value) ||
      !('at' in value)
    )
      return undefined;
    const project = value.project;
    const at = value.at;
    if (typeof project !== 'string' || project === '') return undefined;
    if (typeof at !== 'number' || !Number.isFinite(at)) return undefined;
    return { project, at };
  } catch {
    // A hand-edited or partial value is not a binding.
    return undefined;
  }
}
