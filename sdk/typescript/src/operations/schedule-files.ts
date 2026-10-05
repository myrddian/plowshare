import { isOneOf } from '../binding/values.ts';
/** Portable desired schedule configuration. Execution and ownership remain on the server. */
export interface ScheduleDefinition {
  readonly version: 1;
  readonly cron: string;
  readonly zone: string;
  readonly paused: boolean;
  readonly action: {
    readonly kind: 'agent' | 'skill' | 'orchestration';
    readonly agent: string;
    readonly name: string | null;
    readonly input: string;
    readonly mode: 'INHERITED' | 'SUMMARISED' | 'NEW' | 'DIRECT' | null;
  };
  readonly target: {
    readonly kind: 'mailbox' | 'conversation' | 'message';
    readonly project: string | null;
    readonly conversation: string | null;
    readonly to: string | null;
    readonly route: string | null;
  };
  readonly limits: {
    readonly maxModelCalls: number | null;
    readonly maxTurns: number | null;
    readonly queueCap: number;
  };
}
export interface ScheduleFile {
  readonly name: string;
  readonly project: string | null;
  readonly source: 'server' | 'workspace';
  readonly path: string;
  readonly internalName: string;
  readonly definition: ScheduleDefinition | null;
  readonly status: 'active' | 'refused';
  readonly error: string | null;
}
export function definitionFromProposal(proposal: {
  cron: string;
  zone: string;
  agent: string;
  task: string;
  project?: string | null;
  conversation?: string | null;
}): ScheduleDefinition {
  const command =
    /^\/(skill|orchestration):([A-Za-z0-9_.-]+)(?:[ \t]+--mode=(INHERITED|SUMMARISED|NEW|DIRECT))?[ \t]+([\s\S]+)$/.exec(
      proposal.task,
    );
  const action: ScheduleDefinition['action'] = command
    ? {
        kind: command[1] as 'skill' | 'orchestration',
        agent: proposal.agent,
        name: command[2]!,
        input: command[4]!,
        mode: (command[3] ?? null) as ScheduleDefinition['action']['mode'],
      }
    : {
        kind: 'agent',
        agent: proposal.agent,
        name: null,
        input: proposal.task,
        mode: null,
      };
  return {
    version: 1,
    cron: proposal.cron,
    zone: proposal.zone,
    paused: false,
    action,
    target: {
      kind: proposal.conversation ? 'conversation' : 'mailbox',
      project: null,
      conversation: proposal.conversation ?? null,
      to: null,
      route: null,
    },
    limits: { maxModelCalls: null, maxTurns: null, queueCap: 1 },
  };
}

/** Offline CLI validation mirrors the portable contract; grants and cron validity are server-owned. */
export function schedulePayloadProblem(
  type: string,
  body: Record<string, unknown>,
): string | undefined {
  const text = (v: unknown, max = 256): v is string =>
    typeof v === 'string' && !!v.trim() && v.length <= max && !v.includes('\0');
  const identity = (v: unknown) =>
    text(v, 128) && /^[A-Za-z0-9_.-]+$/.test(v) && v !== '.' && v !== '..';
  const object = (v: unknown, keys: string[]): v is Record<string, unknown> =>
    !!v &&
    typeof v === 'object' &&
    !Array.isArray(v) &&
    Object.keys(v).every((k) => keys.includes(k));
  if (!['server', 'workspace'].includes(String(body['source'])))
    return 'source must be server or workspace';
  if (body['project'] != null && !text(body['project']))
    return 'project must be nonblank text or null';
  if (body['source'] === 'workspace' && body['project'] == null)
    return 'workspace schedule files require a project';
  if (type === 'schedule.sync') return undefined;
  if (!identity(body['name']) || String(body['name']).startsWith('.'))
    return 'name must be a safe JSON file name without an extension';
  if (body['overwrite'] !== undefined && typeof body['overwrite'] !== 'boolean')
    return 'overwrite must be boolean';
  const d = body['definition'];
  if (
    !object(d, [
      'version',
      'cron',
      'zone',
      'paused',
      'action',
      'target',
      'limits',
    ]) ||
    d['version'] !== 1 ||
    !text(d['cron']) ||
    !text(d['zone'], 128) ||
    typeof d['paused'] !== 'boolean'
  )
    return 'definition needs version:1, cron, zone and paused:boolean';
  const a = d['action'],
    t = d['target'],
    l = d['limits'];
  if (
    !object(a, ['kind', 'agent', 'name', 'input', 'mode']) ||
    !identity(a['agent']) ||
    !text(a['input'], 16000) ||
    !['agent', 'skill', 'orchestration'].includes(String(a['kind']))
  )
    return 'action needs kind, agent and bounded input';
  if (
    a['kind'] === 'agent'
      ? a['name'] != null || a['mode'] != null
      : !identity(a['name'])
  )
    return 'command actions need a name; agent tasks do not take name or mode';
  if (
    a['mode'] != null &&
    (a['kind'] !== 'skill' ||
      !isOneOf(a['mode'], ['INHERITED', 'SUMMARISED', 'NEW', 'DIRECT']))
  )
    return 'only skills take a valid context mode';
  if (
    !object(t, ['kind', 'project', 'conversation', 'to', 'route']) ||
    !['mailbox', 'conversation', 'message'].includes(String(t['kind'])) ||
    (t['project'] != null && !text(t['project']))
  )
    return 'invalid target';
  if (
    t['kind'] === 'conversation'
      ? !identity(t['conversation']) ||
        t['project'] != null ||
        t['to'] != null ||
        t['route'] != null
      : t['conversation'] != null
  )
    return 'conversation targets require only a conversation id';
  if (t['kind'] === 'message') {
    if (
      (t['to'] == null) === (t['route'] == null) ||
      (t['to'] != null && !identity(t['to'])) ||
      (t['route'] != null && (!identity(t['route']) || t['project'] != null))
    )
      return 'message targets need to or route; a route supplies its project';
  } else if (t['to'] != null || t['route'] != null)
    return 'only message targets take to or route';
  if (l != null) {
    if (
      !object(l, ['maxModelCalls', 'maxTurns', 'queueCap']) ||
      !Number.isInteger(l['queueCap']) ||
      Number(l['queueCap']) < 1 ||
      Number(l['queueCap']) > 100
    )
      return 'limits require queueCap between 1 and 100';
    for (const k of ['maxModelCalls', 'maxTurns'])
      if (
        l[k] != null &&
        (!Number.isInteger(l[k]) || Number(l[k]) < 1 || Number(l[k]) > 100000)
      )
        return 'invalid schedule limits';
    if (
      t['kind'] === 'conversation' &&
      (l['maxModelCalls'] != null || l['maxTurns'] != null)
    )
      return 'conversation schedules use conversation limits';
  }
  return undefined;
}
