package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aeyer.plowshare.server.files.WorkspaceCodeMap;
import java.util.*;

/** Deterministic next steps from typed, already verified observations; never executes tools. */
final class CodeNavigationHints {
  private CodeNavigationHints() {}

  record Request(String operation, String path, String query, int limit) {
    Request {
      if (!Set.of("files", "overview", "outline", "symbols", "read").contains(operation)
          || limit < 1) throw new IllegalArgumentException("invalid navigation request");
      if (path != null) checkedPath(path);
      if (query != null && (query.isBlank() || query.length() > 128 || query.indexOf('\0') >= 0))
        throw new IllegalArgumentException("invalid navigation prefix");
    }
  }

  /** Only rows displayed by this call may contribute a suggested path or retained hash. */
  record Displayed(
      String path, String sourceHash, String outlineStatus, Integer start, Integer end) {
    Displayed {
      checkedPath(path);
      if (sourceHash == null || !sourceHash.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("verified source hash required");
      Objects.requireNonNull(outlineStatus, "outlineStatus");
      if ((start == null) != (end == null) || start != null && (start < 0 || end <= start))
        throw new IllegalArgumentException("invalid declaration span");
    }

    static Displayed file(WorkspaceCodeMap.Entry entry) {
      return new Displayed(
          entry.path().toString(),
          entry.fingerprint().sha256(),
          entry.outline().status(),
          null,
          null);
    }
  }

  record Observation(
      Displayed selected,
      List<Displayed> rows,
      int total,
      int offset,
      Integer readEnd,
      boolean hasMore) {
    Observation {
      rows = List.copyOf(rows);
      if (total < 0 || offset < 0 || readEnd != null && (readEnd < offset || readEnd > total))
        throw new IllegalArgumentException("invalid navigation observation bounds");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Hint(String reason, String message, String tool, Arguments arguments) {
    Hint {
      Objects.requireNonNull(reason);
      Objects.requireNonNull(message);
      if ((tool == null) != (arguments == null))
        throw new IllegalArgumentException("hint tool/arguments differ");
    }
  }

  sealed interface Arguments permits FileRead, Grep, CodeMap {}

  record FileRead(String path, int offset, int limit) implements Arguments {
    FileRead {
      checkedPath(path);
      if (offset < 0 || limit < 1) throw new IllegalArgumentException("invalid line window");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Grep(String text, @JsonProperty("ignore_case") boolean ignoreCase, String path)
      implements Arguments {
    Grep {
      Objects.requireNonNull(text);
      if (path != null) checkedPath(path);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CodeMap(
      String operation,
      String path,
      String query,
      String pattern,
      @JsonProperty("source_hash") String sourceHash,
      Long offset,
      Integer limit)
      implements Arguments {
    CodeMap {
      if (!Set.of("files", "overview", "outline", "symbols", "read").contains(operation))
        throw new IllegalArgumentException("invalid suggested navigation operation");
      if (path != null) checkedPath(path);
      if (offset != null && offset < 0 || limit != null && limit < 1)
        throw new IllegalArgumentException("invalid suggested source window");
      if (sourceHash != null && !sourceHash.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("verified suggested source hash required");
    }
  }

  static List<Hint> forResult(
      WorkspaceCodeMap.View view, Request request, Observation result, FileTools.Reads reads) {
    var hints = new ArrayList<Hint>();
    String operation = request.operation();
    if (!view.state().equals("observed") || !view.issues().isEmpty()) {
      hints.add(
          note(
              "incomplete_coverage",
              "This scan is incomplete. Missing declarations do not establish absence."
                  + " Check issues and select a narrower relative pattern with files or overview before searching again."));
    }
    var rows = result.rows();
    var selected = result.selected();
    var fallback =
        selected != null
            ? selected
            : rows.stream()
                .filter(row -> !"ready".equals(row.outlineStatus()))
                .findFirst()
                .orElse(null);
    if (fallback != null && !"ready".equals(fallback.outlineStatus())) {
      String message =
          "This file's outline is incomplete or unavailable. Use source text to check declarations;"
              + " syntax navigation does not resolve references or prove absence.";
      hints.add(
          reads.canUse(FileTools.READ_NAME)
              ? call(
                  "source_fallback",
                  message + " file_read offsets count lines.",
                  FileTools.READ_NAME,
                  new FileRead(fallback.path(), 0, 100))
              : note("source_fallback", message));
    }
    if (operation.equals("symbols") && result.total() == 0) {
      String message =
          "No declaration names match this prefix in the selected pattern."
              + " This does not check call sites or references. A literal text search can locate usages;"
              + " its scope may differ from this code map.";
      hints.add(
          reads.canUse(FileTools.GREP_NAME)
              ? call(
                  "no_declarations",
                  message,
                  FileTools.GREP_NAME,
                  new Grep(request.query(), true, selected == null ? null : selected.path()))
              : note("no_declarations", message));
    }
    if (result.hasMore()) {
      hints.add(
          call(
              "next_page",
              "More results remain in this scan. Reconciliation can change pages if files change.",
              CodeMapTool.NAME,
              new CodeMap(
                  operation,
                  request.path(),
                  request.query(),
                  List.of("files", "overview").contains(operation) ? view.pattern() : null,
                  null,
                  (long) result.offset() + request.limit(),
                  request.limit())));
    } else if (operation.equals("read") && result.readEnd() < result.total()) {
      hints.add(
          call(
              "continue_read",
              "Continue this normalized snapshot with the same raw hash; changed content is refused.",
              CodeMapTool.NAME,
              new CodeMap(
                  "read",
                  selected.path(),
                  null,
                  null,
                  selected.sourceHash(),
                  result.readEnd().longValue(),
                  request.limit())));
    }
    if (List.of("outline", "symbols").contains(operation) && !rows.isEmpty()) {
      var row = rows.getFirst();
      hints.add(
          call(
              "read_declaration",
              "Read the first displayed declaration with its verified hash and UTF-16 offsets."
                  + " Signatures are abbreviated navigation aids; source text is evidence.",
              CodeMapTool.NAME,
              new CodeMap(
                  "read",
                  row.path(),
                  null,
                  null,
                  row.sourceHash(),
                  row.start().longValue(),
                  Math.min(32768, row.end() - row.start()))));
    } else if (List.of("files", "overview").contains(operation) && !rows.isEmpty()) {
      hints.add(
          call(
              "inspect_file",
              "Inspect declarations in the first displayed file. Keep its path inside the selected pattern.",
              CodeMapTool.NAME,
              new CodeMap("outline", rows.getFirst().path(), null, null, null, null, null)));
    }
    return List.copyOf(hints.subList(0, Math.min(4, hints.size())));
  }

  private static Hint note(String reason, String message) {
    return new Hint(reason, message, null, null);
  }

  private static Hint call(String reason, String message, String tool, Arguments arguments) {
    return new Hint(reason, message, tool, arguments);
  }

  private static void checkedPath(String value) {
    if (value == null || value.length() > 8192 || !java.nio.file.Path.of(value).isAbsolute())
      throw new IllegalArgumentException("absolute bounded displayed path required");
  }
}
