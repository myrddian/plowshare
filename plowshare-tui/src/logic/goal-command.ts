export const GOALS_COMMAND = '/goals';
export const goalUsage =
  '/goals [list [cursor] | status <goal> | pause <goal> | resume <goal> [activations=N] [calls=N] | cancel <goal> | clear <goal>]';

export type GoalCommand =
  | { kind: 'list'; after?: string }
  | { kind: 'status'; goal: string }
  | { kind: 'clear'; goal: string }
  | {
      kind: 'pause' | 'resume' | 'cancel';
      goal: string;
      limits: { maxActivations?: number; maxModelCalls?: number };
    }
  | { kind: 'usage' };

export function goalCommand(line: string): GoalCommand | undefined {
  const [command, verb, arg, ...rest] = line.trim().split(/\s+/u);
  if (command !== GOALS_COMMAND) return undefined;
  if (!verb) return { kind: 'list' };
  if (verb === 'list' && rest.length === 0)
    return { kind: 'list', ...(arg === undefined ? {} : { after: arg }) };
  if (!arg) return { kind: 'usage' };
  if (rest.length === 0) {
    if (verb === 'status' || verb === 'clear') return { kind: verb, goal: arg };
    if (verb === 'pause' || verb === 'cancel')
      return { kind: verb, goal: arg, limits: {} };
  }
  if (verb !== 'resume') return { kind: 'usage' };
  let maxActivations: number | undefined;
  let maxModelCalls: number | undefined;
  for (const option of rest) {
    const [name, value, extra] = option.split('=');
    if (extra !== undefined || !value || !/^[1-9][0-9]*$/u.test(value))
      return { kind: 'usage' };
    const limit = Number(value);
    if (
      name === 'activations' &&
      maxActivations === undefined &&
      limit <= 1000
    ) {
      maxActivations = limit;
    } else if (
      name === 'calls' &&
      maxModelCalls === undefined &&
      limit <= 1000000
    ) {
      maxModelCalls = limit;
    } else return { kind: 'usage' };
  }
  return {
    kind: 'resume',
    goal: arg,
    limits: {
      ...(maxActivations === undefined ? {} : { maxActivations }),
      ...(maxModelCalls === undefined ? {} : { maxModelCalls }),
    },
  };
}
