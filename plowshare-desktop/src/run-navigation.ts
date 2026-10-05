import { isList, isObject } from 'plowshare-client-ts/binding/values';
import type { DesktopState } from './shared.ts';
import {
  stepsOf,
  stepKey,
  type Step,
  type Call,
} from 'plowshare-client-ts/operations/trajectory';

export interface StageSpan {
  start: number;
  end?: number;
}
export interface StageScope {
  run: string;
  stage: string;
}

/** Stage visits come from the ordered stage transition journal, scoped to this run.
 * A return opens another visit; descendants' transitions never change the parent's stage. */
export function stageSpans(
  state: DesktopState,
  id: string,
): Map<string, StageSpan[]> {
  const detail = state.activity.details[id],
    stages = detail?.value?.stages ?? [];
  const result = new Map<string, StageSpan[]>();
  const open = new Map<string, StageSpan>();
  for (const row of state.activity.navigation?.[id]?.rows ?? []) {
    if (row.run !== id || row.kind !== 'stage_moved') continue;
    const match =
      /^(.+): (pending|in_progress|done|cancelled) → (pending|in_progress|done|cancelled)$/.exec(
        row.text,
      );
    const at = Date.parse(row.at);
    if (!match || !Number.isFinite(at)) continue;
    const stage = stages.find(
      (stage) =>
        stage.stage &&
        (stage.stage === match[1] ||
          (match[1] ?? '').endsWith(` · ${stage.stage}`)),
    )?.stage;
    if (!stage) continue;
    if (match[3] === 'in_progress') {
      // A duplicate notification must not open overlapping visits.
      if (open.has(stage)) continue;
      const span = { start: at };
      result.set(stage, [...(result.get(stage) ?? []), span]);
      open.set(stage, span);
    } else if (open.has(stage)) {
      open.get(stage)!.end = at;
      open.delete(stage);
    }
  }
  const ended = Date.parse(detail?.wire?.orchestration.endedAt ?? '');
  if (Number.isFinite(ended))
    for (const span of open.values()) span.end = ended;
  return result;
}
export function inStage(
  at: string | undefined,
  spans: readonly StageSpan[],
): boolean {
  const time = Date.parse(at ?? '');
  return (
    Number.isFinite(time) &&
    spans.some(
      (span) =>
        time >= span.start && (span.end === undefined || time < span.end),
    )
  );
}
export function questionStage(
  state: DesktopState,
  id: string,
  createdAt: string,
): string | undefined {
  const matches = [...stageSpans(state, id)].filter(([, spans]) =>
    inStage(createdAt, spans),
  );
  return matches.length === 1 ? matches[0]?.[0] : undefined;
}
export function stageSteps(
  state: DesktopState,
  conversation: string,
  scope?: StageScope,
): Step[] {
  const steps = stepsOf(state.history[conversation]?.entries ?? []);
  if (!scope) return steps;
  const spans = stageSpans(state, scope.run).get(scope.stage) ?? [];
  return steps.filter((step) => inStage(step.entry.recordedAt, spans));
}
/** Follow a durable opened edge, never a generated id in text or arguments. */
export function delegatedConversation(
  state: DesktopState,
  parent: string,
  key: string,
): string {
  const step = stepsOf(state.history[parent]?.entries ?? []).find(
    (step) => stepKey(step) === key,
  );
  if (step?.kind !== 'call' || !step.opened?.conversation)
    throw new Error('Choose a recorded delegation in this trajectory.');
  return step.opened.conversation;
}

/** Only successful, complete receipts of orchestration-start tools name a run.
 * Model prose and tool arguments are never navigation authority. */
export function orchestrationCallId(call: Call): string | undefined {
  if (
    !call.tool.startsWith('orchestrate_') ||
    !call.result ||
    call.result.cut ||
    call.result.outcome !== 'ok'
  )
    return;
  try {
    const result: unknown = JSON.parse(call.result.text ?? '');
    if (
      isObject(result) &&
      typeof result.id === 'string' &&
      result.id.trim() &&
      result.id.length <= 512 &&
      isList(result.stages)
    )
      return result.id;
  } catch {
    /* An incomplete receipt remains readable, without a speculative link. */
  }
}
export function navigableCall(call: Call): boolean {
  return Boolean(call.opened?.conversation || orchestrationCallId(call));
}
