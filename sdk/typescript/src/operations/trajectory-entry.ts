import type { EntryView } from './conversation-replies.ts';
import type { Entry } from './client-views.ts';

/** Project a validated retained-record DTO into the model used by TUI/Desktop.
 * Missing optional legacy metadata stays absent. This does not decide job state
 * or infer results that are outside the current page. */
export function trajectoryEntry(row: EntryView): Entry {
  return {
    ordinal: row.ordinal,
    turnOrdinal: row.turnOrdinal,
    kind: row.kind,
    state:
      row.ejectedAt != null
        ? 'ejected'
        : row.supersededBy != null
          ? 'folded'
          : 'stands',
    length: row.length,
    cut: row.cut,
    ...(row.excerpt != null ? { text: row.excerpt } : {}),
    ...(row.dispatch != null ? { dispatch: row.dispatch } : {}),
    ...(row.wireModel != null ? { wireModel: row.wireModel } : {}),
    ...(row.completion != null ? { completion: row.completion } : {}),
    ...(row.speaker != null ? { speaker: row.speaker } : {}),
    ...(row.speakerName != null ? { speakerName: row.speakerName } : {}),
    ...(row.job != null ? { job: row.job } : {}),
    ...(row.source != null ? { source: row.source } : {}),
    ...(row.toolCallId != null ? { toolCallId: row.toolCallId } : {}),
    ...(row.outcome != null ? { outcome: row.outcome } : {}),
    ...(row.tookMillis != null ? { tookMillis: row.tookMillis } : {}),
    ...(row.recordedAt != null ? { recordedAt: row.recordedAt } : {}),
    ...(row.handle != null ? { handle: row.handle } : {}),
    ...(row.supersededBy != null ? { supersededBy: row.supersededBy } : {}),
    ...(row.ejectedAt != null ? { ejectedAt: row.ejectedAt } : {}),
    calls: row.toolCalls.map((call) => ({
      id: call.id,
      name: call.name,
      arguments: call.arguments,
      length: call.length,
      cut: call.cut,
      ...(call.salient != null ? { salient: call.salient } : {}),
      ...(call.opened != null ? { opened: call.opened } : {}),
    })),
  };
}
