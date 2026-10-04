package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.files.WorkspaceCodeMap;
import java.util.*;

/** Deterministic next steps from already verified observations; never reads or executes tools. */
final class CodeNavigationHints {
  private CodeNavigationHints() {}

  static List<Map<String, Object>> forResult(
      WorkspaceCodeMap.View view,
      String operation,
      JsonNode request,
      Map<String, Object> result,
      FileTools.Reads reads) {
    var hints = new ArrayList<Map<String, Object>>();
    if (!view.state().equals("observed") || !view.issues().isEmpty()) {
      hints.add(
          note(
              "incomplete_coverage",
              "This scan is incomplete. Missing declarations do not establish absence."
                  + " Check issues and select a narrower relative pattern with files or overview before searching again."));
    }
    // Only select paths already shown in this response. A hint must not leak an unshown
    // file or expand a retained source's input ledger beyond what this call displayed.
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) result.getOrDefault("results", List.of());
    @SuppressWarnings("unchecked")
    var selected = (Map<String, Object>) result.get("file");
    var fallback =
        selected != null
            ? selected
            : rows.stream()
                .filter(row -> !"ready".equals(row.get("outline_status")))
                .findFirst()
                .orElse(null);
    if (fallback != null && !"ready".equals(fallback.get("outline_status"))) {
      String message =
          "This file's outline is incomplete or unavailable. Use source text to check declarations;"
              + " syntax navigation does not resolve references or prove absence.";
      hints.add(
          reads.canUse(FileTools.READ_NAME)
              ? call(
                  "source_fallback",
                  message + " file_read offsets count lines.",
                  FileTools.READ_NAME,
                  Map.of("path", fallback.get("path"), "offset", 0, "limit", 100))
              : note("source_fallback", message));
    }
    if (operation.equals("symbols") && ((Number) result.get("total")).intValue() == 0) {
      String message =
          "No declaration names match this prefix in the selected pattern."
              + " This does not check call sites or references. A literal text search can locate usages;"
              + " its scope may differ from this code map.";
      var arguments = new LinkedHashMap<String, Object>();
      arguments.put("text", request.path("query").asText());
      arguments.put("ignore_case", true);
      if (selected != null) arguments.put("path", selected.get("path"));
      hints.add(
          reads.canUse(FileTools.GREP_NAME)
              ? call("no_declarations", message, FileTools.GREP_NAME, arguments)
              : note("no_declarations", message));
    }
    if (Boolean.TRUE.equals(result.get("has_more"))) {
      var arguments = new LinkedHashMap<String, Object>();
      arguments.put("operation", operation);
      for (String key : List.of("path", "query"))
        if (request.has(key)) arguments.put(key, request.path(key).asText());
      if (List.of("files", "overview").contains(operation))
        arguments.put("pattern", view.pattern());
      int limit =
          request.has("limit")
              ? request.path("limit").asInt()
              : operation.equals("overview") ? 20 : 50;
      arguments.put("limit", limit);
      arguments.put("offset", ((Number) result.get("offset")).longValue() + limit);
      hints.add(
          call(
              "next_page",
              "More results remain in this scan. Reconciliation can change pages if files change.",
              CodeMapTool.NAME,
              arguments));
    } else if (operation.equals("read")
        && ((Number) result.get("end")).intValue() < ((Number) result.get("total")).intValue()) {
      hints.add(
          call(
              "continue_read",
              "Continue this normalized snapshot with the same raw hash; changed content is refused.",
              CodeMapTool.NAME,
              Map.of(
                  "operation",
                  "read",
                  "path",
                  result.get("path"),
                  "source_hash",
                  result.get("source_hash"),
                  "offset",
                  result.get("end"),
                  "limit",
                  request.has("limit") ? request.path("limit").asInt() : 8192)));
    }
    if (List.of("outline", "symbols").contains(operation) && !rows.isEmpty()) {
      var row = rows.getFirst();
      int start = ((Number) row.get("start_offset")).intValue(),
          end = ((Number) row.get("end_offset")).intValue();
      hints.add(
          call(
              "read_declaration",
              "Read the first displayed declaration with its verified hash and UTF-16 offsets."
                  + " Signatures are abbreviated navigation aids; source text is evidence.",
              CodeMapTool.NAME,
              Map.of(
                  "operation",
                  "read",
                  "path",
                  row.get("path"),
                  "source_hash",
                  row.get("source_hash"),
                  "offset",
                  start,
                  "limit",
                  Math.min(32768, end - start))));
    } else if (List.of("files", "overview").contains(operation) && !rows.isEmpty()) {
      hints.add(
          call(
              "inspect_file",
              "Inspect declarations in the first displayed file. Keep its path inside the selected pattern.",
              CodeMapTool.NAME,
              Map.of("operation", "outline", "path", rows.getFirst().get("path"))));
    }
    return List.copyOf(hints.subList(0, Math.min(4, hints.size())));
  }

  private static Map<String, Object> note(String reason, String message) {
    return Map.of("reason", reason, "message", message);
  }

  private static Map<String, Object> call(
      String reason, String message, String tool, Map<String, Object> arguments) {
    return Map.of("reason", reason, "message", message, "tool", tool, "arguments", arguments);
  }
}
