import {
  type GoalAgentClient,
  goalControls,
} from 'plowshare-client-ts/operations/goal-controls';
import type { GoalStatus } from 'plowshare-client-ts/operations/replies';
import { cleaned } from '../logic/clean.ts';
import {
  GOALS_COMMAND,
  goalUsage,
  type GoalCommand,
} from '../logic/goal-command.ts';

const words = (text: string) => text.toLowerCase().replaceAll('_', ' ');

function oneLine(text: string): string {
  return cleaned(text).replace(/\s+/gu, ' ').trim();
}

function objectivePreview(objective: string): string {
  const points = Array.from(oneLine(objective));
  return points.length <= 120
    ? points.join('')
    : points.slice(0, 119).join('') + '…';
}

function statusLines(status: GoalStatus): string[] {
  const lines = [
    `${status.goal} · ${words(status.lifecycle)} · ${words(status.state)} · version ${status.version}`,
    `Conversation ${status.conversation} · Instance ${status.instance}`,
  ];
  if (status.reason) lines.push(`Reason · ${words(status.reason)}`);
  if (!status.readable)
    return [...lines, 'Conversation content is restricted.'];
  if (status.objective) lines.push(`Objective · ${status.objective}`);
  if (status.agent) lines.push(`Agent · ${status.agent}`);
  if (status.detail) lines.push(status.detail);
  if (status.nextScheduleAt)
    lines.push(`Next schedule · ${status.nextScheduleAt}`);
  for (const wake of status.schedules)
    lines.push(
      `Schedule · ${wake.schedule} · ${wake.paused ? 'paused' : (wake.nextFireAt ?? 'no next occurrence')}`,
    );
  if (status.moreSchedules) lines.push('More schedules are attached.');
  for (const wake of status.relayListeners) {
    const batch = wake.pendingBatch;
    lines.push(
      `Relay · ${wake.topic ?? wake.binding}${wake.failure ? ` · ${words(wake.failure)}` : ''}${batch ? ` · ${words(batch.state)} batch, ${batch.memberCount} events, deadline ${batch.deadline}` : ''}`,
    );
  }
  if (status.moreRelayListeners)
    lines.push('More Relay listeners are attached.');
  if (status.latestResult) {
    lines.push(
      `Latest result · turn ${status.latestResult.turn}`,
      status.latestResult.summary,
    );
    for (const evidence of status.latestResult.evidence)
      lines.push(`Evidence · ${evidence}`);
  }
  lines.push(`History · /trajectory ${status.conversation}`);
  return lines;
}

/** Keep the project selection in the normal TUI navigation and use exact Goal IDs
 * and versions from the server. Report uncertain delivery with its recovery path. */
export async function runGoalCommand(
  goals: GoalAgentClient,
  project: string | undefined,
  command: GoalCommand,
): Promise<string[]> {
  if (command.kind === 'usage') return [goalUsage];
  if (!project)
    throw new Error(
      'Select a project with /project before opening Goal Agents.',
    );
  if (command.kind === 'list') {
    const page = await goals.list(project, command.after);
    return [
      `Goal Agents · ${oneLine(project)}`,
      ...page.goals.map((row) =>
        oneLine(
          `${row.goal} · ${words(row.lifecycle)} · ${words(row.state)}${row.objective ? ` · ${objectivePreview(row.objective)}` : ''}`,
        ),
      ),
      ...(page.goals.length
        ? [`${GOALS_COMMAND} status <goal> to inspect`]
        : ['No Goal Agents on this page.']),
      ...(page.next
        ? [`Next page · ${GOALS_COMMAND} list ${oneLine(page.next)}`]
        : []),
    ];
  }
  let status = await goals.inspect(project, command.goal);
  if (command.kind === 'clear') {
    goals.forget(status);
    return ['Cleared the local control marker.'];
  }
  const lines: string[] = [];
  if (command.kind !== 'status') {
    await goals.change(status, command.kind, command.limits);
    const pending = goals.pending(status);
    if (!pending) lines.push('Goal Agent control confirmed.');
    try {
      status = await goals.inspect(project, command.goal);
      if (pending && status.readable && !goals.pending(status))
        lines.push('Goal Agent control confirmed by receipt.');
    } catch {
      if (!pending)
        return [
          ...lines,
          'Refresh status to see the current Goal Agent state.',
        ];
      return ['Control outcome pending. Inspect status to check its receipt.'];
    }
  }
  lines.push(...statusLines(status));
  if (status.readable) {
    const pending = goals.pending(status);
    if (pending?.notice) lines.push(pending.notice);
    if (pending)
      lines.push(
        `Control outcome pending · ${GOALS_COMMAND} status ${status.goal}`,
      );
    if (pending?.state === 'missing')
      lines.push(
        `No receipt found. Review the status before running ${GOALS_COMMAND} clear ${status.goal}`,
      );
    if (!pending)
      for (const control of goalControls(status))
        lines.push(`${GOALS_COMMAND} ${control} ${status.goal}`);
  }
  return lines.map((line) => cleaned(line));
}
