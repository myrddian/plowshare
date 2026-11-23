package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.EntryRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps consent bookkeeping in the trajectory and projects the eventual result in its call slot.
 */
final class ApprovalProjection {
  private ApprovalProjection() {}

  static List<EntryRecord> settledResults(List<EntryRecord> log) {
    List<EntryRecord> result = new ArrayList<>();
    Map<String, Integer> positions = new HashMap<>();
    for (EntryRecord entry : placeLinkedResults(log)) {
      if (entry.kind() == EntryKind.ANSWER) {
        // Call IDs need only identify a request's batch. Reusing an ID in a later model request
        // must never cause that later result to replace an earlier command's result.
        entry.toolCalls().forEach(call -> positions.remove(call.id()));
      }
      if (entry.kind() != EntryKind.TOOL_RESULT) result.add(entry);
      else {
        Integer position = positions.putIfAbsent(entry.toolCallId(), result.size());
        if (position == null) result.add(entry);
        else {
          EntryRecord original = result.get(position);
          result.set(
              position,
              new EntryRecord(
                  original.conversationId(),
                  original.ordinal(),
                  original.kind(),
                  entry.content(),
                  original.toolCallId(),
                  original.toolCalls(),
                  original.supersededBy(),
                  entry.handle(),
                  original.turnOrdinal(),
                  entry.recordedAt(),
                  entry.tookMillis(),
                  entry.ejectedAt(),
                  entry.export(),
                  null,
                  entry.fileWindow()));
        }
      }
    }
    return result;
  }

  /**
   * Place direct file results by the durable declaration, even when recovery appended them later.
   */
  private static List<EntryRecord> placeLinkedResults(List<EntryRecord> log) {
    Map<Integer, Map<String, EntryRecord>> linked = new HashMap<>();
    for (EntryRecord entry : log) {
      if (entry.retainedAnswerOrdinal() != null)
        linked
            .computeIfAbsent(entry.retainedAnswerOrdinal(), ordinal -> new LinkedHashMap<>())
            .put(entry.toolCallId(), entry);
    }
    if (linked.isEmpty()) return log;
    List<EntryRecord> placed = new ArrayList<>(log.size());
    Map<String, EntryRecord> pending = Map.of();
    Set<String> attached = Set.of();
    int declaration = 0;
    int turn = 0;
    for (EntryRecord entry : log) {
      if (entry.retainedAnswerOrdinal() != null) {
        if (entry.retainedAnswerOrdinal() == declaration
            && pending.remove(entry.toolCallId()) != null)
          placed.add(resultAt(entry, entry.ordinal(), turn));
        continue;
      }
      if (entry.kind() != EntryKind.TOOL_RESULT && entry.kind().projects()) {
        addPending(placed, pending, turn);
        declaration = entry.ordinal();
        pending = linked.getOrDefault(declaration, Map.of());
        attached = Set.copyOf(pending.keySet());
        turn = entry.turnOrdinal();
      }
      if (entry.kind() == EntryKind.TOOL_RESULT && attached.contains(entry.toolCallId())) {
        EntryRecord settled = pending.remove(entry.toolCallId());
        if (settled != null) placed.add(resultAt(settled, entry.ordinal(), turn));
      } else placed.add(entry);
    }
    addPending(placed, pending, turn);
    return placed;
  }

  private static void addPending(
      List<EntryRecord> placed, Map<String, EntryRecord> pending, int turn) {
    pending.values().forEach(entry -> placed.add(resultAt(entry, entry.ordinal(), turn)));
  }

  private static EntryRecord resultAt(EntryRecord entry, int ordinal, int turn) {
    return new EntryRecord(
        entry.conversationId(),
        ordinal,
        entry.kind(),
        entry.content(),
        entry.toolCallId(),
        entry.toolCalls(),
        entry.supersededBy(),
        entry.handle(),
        turn,
        entry.recordedAt(),
        entry.tookMillis(),
        entry.ejectedAt(),
        entry.export(),
        entry.observation(),
        entry.fileWindow());
  }
}
