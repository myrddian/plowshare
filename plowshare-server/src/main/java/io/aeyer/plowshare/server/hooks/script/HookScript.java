package io.aeyer.plowshare.server.hooks.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/** Executable hook text and the optional single-file source map emitted by SWC. */
record HookScript(String code, String mappings) {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String BASE64 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  static HookScript javascript(String code) {
    return new HookScript(code, null);
  }

  static HookScript typescript(String code, String map) throws JsonProcessingException {
    return new HookScript(code, map == null ? "" : JSON.readTree(map).path("mappings").asText(""));
  }

  /**
   * Remap the nearest preceding segment on this generated line. Never label an unmapped line TS.
   */
  Optional<ScriptLocation> originalLocation(ScriptLocation generated) {
    if (mappings == null) return Optional.of(generated);
    int targetLine = generated.line() - 1;
    int targetColumn = generated.column() - 1;
    int line = 0;
    int column = 0;
    int originalLine = 0;
    int originalColumn = 0;
    ScriptLocation best = null;
    // Source map v3 keeps original coordinates relative across lines. Generated columns
    // restart at each semicolon. Ignore optional name indices, which cannot affect positions.
    for (int start = 0, end = 0; end <= mappings.length(); end++) {
      char separator = end == mappings.length() ? ';' : mappings.charAt(end);
      if (separator != ',' && separator != ';') continue;
      if (end > start) {
        int field = 0;
        int delta = 0;
        int shift = 0;
        for (int index = start; index < end; index++) {
          int digit = BASE64.indexOf(mappings.charAt(index));
          if (digit < 0 || shift > 25) return Optional.empty();
          delta |= (digit & 31) << shift;
          if ((digit & 32) != 0) {
            shift += 5;
            continue;
          }
          int value = (delta & 1) == 0 ? delta >>> 1 : -(delta >>> 1);
          switch (field++) {
            case 0 -> column += value;
            case 1 -> {
              if (value != 0) return Optional.empty(); // SWC compiled one admitted file.
            }
            case 2 -> originalLine += value;
            case 3 -> originalColumn += value;
            default -> {}
          }
          shift = 0;
          delta = 0;
        }
        if (shift != 0 || (field != 1 && field != 4 && field != 5)) return Optional.empty();
        if (line == targetLine) {
          if (column > targetColumn) break;
          best = field == 1 ? null : new ScriptLocation(originalLine + 1, originalColumn + 1);
        }
      }
      if (separator == ';') {
        if (line++ == targetLine) break;
        column = 0;
      }
      start = end + 1;
    }
    return Optional.ofNullable(best);
  }
}
