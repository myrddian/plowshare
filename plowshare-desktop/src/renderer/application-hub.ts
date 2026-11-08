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

/**
 * A schedule record has no project. It belongs to an Application only when a
 * file on the loaded page stores that schedule under `internalName` and names
 * the Application. `ScheduleFile.name` is the file stem, and the schedule id
 * for a file-backed row is `internalName`, so a stem match is a different
 * schedule. A trigger belongs when its own `project` is the Application.
 * An empty project is the account-wide list.
 */
export function scheduleNamesProject(
  project: string,
  kind: 'schedule' | 'trigger',
  row: { readonly name: string; readonly project?: string | null },
  files: readonly {
    readonly internalName: string;
    readonly project: string | null;
  }[],
): boolean {
  if (!project) return true;
  if (kind === 'trigger') return row.project === project;
  return files.some(
    (item) => item.internalName === row.name && item.project === project,
  );
}

/**
 * `scheduledId` is `JSON.stringify([kind, name])`. The detail pane resolves
 * that id from the unfiltered schedule and trigger arrays. After an
 * Application bind, drop the id when that row is no longer in the filtered
 * list so Pause and Remove are not offered for a hidden row. A selection that
 * does not resolve to a loaded row is left alone. An empty project keeps the
 * selection because the account-wide list still shows it.
 */
export function scheduledIdForProject(
  scheduledId: string,
  project: string,
  schedules: readonly { readonly name: string }[],
  triggers: readonly {
    readonly name: string;
    readonly project?: string | null;
  }[],
  files: readonly {
    readonly internalName: string;
    readonly project: string | null;
  }[],
): string {
  if (!scheduledId || !project) return scheduledId;
  const selected = selectedSchedule(scheduledId);
  if (!selected) return scheduledId;
  if (selected.kind === 'schedule') {
    const row = schedules.find((item) => item.name === selected.name);
    if (!row || scheduleNamesProject(project, 'schedule', row, files))
      return scheduledId;
    return '';
  }
  const row = triggers.find((item) => item.name === selected.name);
  if (!row || scheduleNamesProject(project, 'trigger', row, files))
    return scheduledId;
  return '';
}

function selectedSchedule(
  scheduledId: string,
): { kind: 'schedule' | 'trigger'; name: string } | undefined {
  try {
    const value: unknown = JSON.parse(scheduledId);
    if (!Array.isArray(value) || value.length !== 2) return undefined;
    const kind: unknown = value[0];
    const name: unknown = value[1];
    if ((kind !== 'schedule' && kind !== 'trigger') || typeof name !== 'string')
      return undefined;
    return { kind, name };
  } catch {
    // A hand-edited id is not a row the filter can hide.
    return undefined;
  }
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
