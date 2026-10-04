package io.aeyer.plowshare.server.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, bounded parsing of model-emitted JSON, shared by Java callers and Graal scripts. Recovery
 * combines Aletheia 851668f's deep-research, enrichment and graph readers. It decides syntax only:
 * callers still validate shape, references and judgments.
 */
public final class LlmJson {
  public static final int MAX_INPUT = 524288;
  public static final int MAX_RECOVERY = 32768;
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final ObjectReader STRICT;
  private static final ObjectReader LENIENT;

  static {
    JSON.getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(128)
                .maxStringLength(MAX_INPUT)
                .build());
    STRICT = JSON.reader();
    LENIENT =
        STRICT
            .with(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature())
            .with(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature())
            .with(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
  }

  private LlmJson() {}

  public record Attempt(
      String pass, String response, String error, int line, int column, long offset) {}

  public record Recovery(JsonNode value, String pass, List<Attempt> attempts) {
    public boolean recovered() {
      return pass != null;
    }

    /** JSON-only boundary: never expose a mapper, Java node or arbitrary host object to a guest. */
    public ObjectNode json() {
      ObjectNode result = JSON.createObjectNode();
      if (recovered()) result.set("value", value);
      result.put("pass", pass);
      var history = result.putArray("attempts");
      for (Attempt attempt : attempts) {
        var entry = history.addObject().put("pass", attempt.pass());
        String response = attempt.response();
        boolean cut = response.length() > MAX_RECOVERY;
        entry.put("response", cut ? response.substring(0, 4096) : response);
        if (cut) entry.put("cut", true).put("length", response.length());
        if (attempt.error() != null)
          entry
              .put("error", attempt.error())
              .put("line", attempt.line())
              .put("column", attempt.column())
              .put("offset", attempt.offset());
      }
      return result;
    }
  }

  public static Recovery parse(String raw) {
    String response = raw == null ? "" : raw;
    var attempts = new ArrayList<Attempt>();
    if (response.length() > MAX_INPUT) {
      attempts.add(
          new Attempt(
              "strict", response, "LLM JSON exceeds " + MAX_INPUT + " characters", 0, 0, 0));
      return new Recovery(null, null, List.copyOf(attempts));
    }
    Recovery parsed = attempt("strict", response, STRICT, attempts);
    if (parsed != null) return parsed;
    if (response.length() > MAX_RECOVERY) return new Recovery(null, null, List.copyOf(attempts));

    String cleaned = clean(response);
    parsed = attempt("fences_and_prose", cleaned, STRICT, attempts);
    if (parsed != null) return parsed;
    // Read valid single-quoted values before double-quote-oriented repair heuristics.
    parsed = attempt("single_quotes", cleaned, LENIENT, attempts);
    if (parsed != null) return parsed;
    String controls = repairControlChars(removeTrailingCommas(cleaned));
    parsed = attempt("commas_and_controls", controls, STRICT, attempts);
    if (parsed != null) return parsed;
    String commas = repairMissingCommas(controls);
    parsed = attempt("missing_commas", commas, LENIENT, attempts);
    if (parsed != null) return parsed;
    parsed = attempt("literal_quotes", repairUnescapedQuotes(commas), STRICT, attempts);
    if (parsed != null) return parsed;
    // Preserve lone backslashes (including LaTeX) before Aletheia's broad escape fallback.
    String preserved = escapeLoneBackslashes(commas);
    parsed = attempt("literal_backslashes", preserved, STRICT, attempts);
    if (parsed != null) return parsed;
    String structural = preserved.replace("\\\"", "\"");
    parsed = attempt("structural_escapes", structural, STRICT, attempts);
    if (parsed != null) return parsed;
    parsed =
        attempt(
            "structural_and_literal_quotes", repairUnescapedQuotes(structural), STRICT, attempts);
    if (parsed != null) return parsed;
    String fallback = commas.replace("\\\"", "\"").replaceAll("\\\\([^\"\\\\bfnrt/u])", "$1");
    parsed = attempt("aletheia_escape_fallback", repairUnescapedQuotes(fallback), STRICT, attempts);
    return parsed != null ? parsed : new Recovery(null, null, List.copyOf(attempts));
  }

  private static Recovery attempt(
      String pass, String text, ObjectReader reader, List<Attempt> attempts) {
    try {
      JsonNode value = reader.readTree(text);
      if (value == null || value.isMissingNode()) {
        attempts.add(new Attempt(pass, text, "No JSON value", 0, 0, 0));
        return null;
      }
      attempts.add(new Attempt(pass, text, null, 0, 0, 0));
      return new Recovery(value, pass, List.copyOf(attempts));
    } catch (JsonProcessingException failed) {
      var at = failed.getLocation();
      attempts.add(
          new Attempt(
              pass,
              text,
              failed.getOriginalMessage(),
              at == null ? 0 : at.getLineNr(),
              at == null ? 0 : at.getColumnNr(),
              at == null ? 0 : at.getCharOffset()));
      return null;
    }
  }

  private static String clean(String raw) {
    String text = raw.strip();
    if (text.startsWith("```")) {
      int start = text.indexOf('\n'), end = text.lastIndexOf("```");
      if (start > 0 && end > start) text = text.substring(start + 1, end).strip();
    }
    if (!text.startsWith("{") && !text.startsWith("[")) {
      int object = text.indexOf('{'), array = text.indexOf('[');
      int start = object < 0 ? array : array < 0 ? object : Math.min(object, array);
      if (start >= 0) text = text.substring(start);
    }
    if (!text.startsWith("{") && !text.startsWith("[")) return text;
    int depth = 0;
    char quote = 0;
    boolean escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (quote != 0) {
        if (c == quote) quote = 0;
        continue;
      }
      if (c == '"' || c == '\'') {
        quote = c;
        continue;
      }
      if (c == '{' || c == '[') depth++;
      if ((c == '}' || c == ']') && --depth == 0) {
        String suffix = text.substring(i + 1).strip();
        // Multiple roots are ambiguous, not a licence to select the first judgment.
        if (suffix.startsWith("{") || suffix.startsWith("[")) return text;
        return text.substring(0, i + 1);
      }
    }
    return text;
  }

  private static String removeTrailingCommas(String text) {
    var out = new StringBuilder(text.length());
    boolean inString = false, escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaped) {
        out.append(c);
        escaped = false;
        continue;
      }
      if (c == '\\') {
        out.append(c);
        escaped = true;
        continue;
      }
      if (c == '"') inString = !inString;
      if (c == ',' && !inString) {
        int next = nextNonWhitespace(text, i + 1);
        if (next < text.length() && (text.charAt(next) == '}' || text.charAt(next) == ']'))
          continue;
      }
      out.append(c);
    }
    return out.toString();
  }

  private static String repairControlChars(String text) {
    var out = new StringBuilder(text.length());
    boolean inString = false, escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaped) {
        out.append(c);
        escaped = false;
        continue;
      }
      if (c == '\\') {
        out.append(c);
        escaped = true;
        continue;
      }
      if (c == '"') inString = !inString;
      if (inString && c < 32) out.append("\\u%04x".formatted((int) c));
      else out.append(c);
    }
    return out.toString();
  }

  private static String repairMissingCommas(String text) {
    String[] lines = text.split("\\R", -1);
    String[] next = new String[lines.length];
    String following = "";
    for (int i = lines.length - 1; i >= 0; i--) {
      next[i] = following;
      if (!lines[i].isBlank()) following = lines[i].strip();
    }
    var out = new StringBuilder(text.length());
    for (int i = 0; i < lines.length; i++) {
      out.append(lines[i]);
      String current = lines[i].strip(), after = next[i];
      if (!current.isEmpty()
          && !after.isEmpty()
          && ",{[:".indexOf(current.charAt(current.length() - 1)) < 0
          && "\"{[".indexOf(after.charAt(0)) >= 0) out.append(',');
      if (i + 1 < lines.length) out.append('\n');
    }
    return out.toString();
  }

  private static String repairUnescapedQuotes(String text) {
    var out = new StringBuilder(text.length());
    boolean inString = false, escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaped) {
        out.append(c);
        escaped = false;
        continue;
      }
      if (c == '\\') {
        out.append(c);
        escaped = true;
        continue;
      }
      if (c == '"') {
        if (!inString) {
          inString = true;
          out.append(c);
        } else {
          int next = nextNonWhitespace(text, i + 1);
          if (next == text.length() || ",}]:".indexOf(text.charAt(next)) >= 0) {
            inString = false;
            out.append(c);
          } else out.append("\\\"");
        }
      } else out.append(c);
    }
    return out.toString();
  }

  private static int nextNonWhitespace(String text, int from) {
    while (from < text.length() && Character.isWhitespace(text.charAt(from))) from++;
    return from;
  }

  private static String escapeLoneBackslashes(String text) {
    var out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); ) {
      if (text.charAt(i) != '\\') {
        out.append(text.charAt(i++));
        continue;
      }
      int length = escapeLength(text, i);
      if (length > 0) {
        out.append(text, i, i + length);
        i += length;
      } else {
        out.append("\\\\");
        i++;
      }
    }
    return out.toString();
  }

  private static int escapeLength(String text, int at) {
    if (at + 1 >= text.length()) return 0;
    char next = text.charAt(at + 1);
    if ("\"\\/bfnrt".indexOf(next) >= 0) return 2;
    if (next != 'u' || at + 5 >= text.length()) return 0;
    for (int i = at + 2; i <= at + 5; i++) if (Character.digit(text.charAt(i), 16) < 0) return 0;
    return 6;
  }
}
