package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.EntryRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps consent bookkeeping in the trajectory and projects the eventual result in its call slot.
 */
final class ApprovalProjection {
  private ApprovalProjection() {}

  static List<EntryRecord> settledResults(List<EntryRecord> log) {
    List<EntryRecord> result = new ArrayList<>();
    Map<String, Integer> positions = new HashMap<>();
    for (EntryRecord entry : log) {
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
                  entry.export()));
        }
      }
    }
    return result;
  }
}
