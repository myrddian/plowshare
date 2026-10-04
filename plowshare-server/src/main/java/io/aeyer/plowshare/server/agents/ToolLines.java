package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a tool call's record line says: its salient argument, and its outcome in a word — spec
 * 2026-09-28, the orchestration record §2. Pure over a tool's name, its arguments and its result;
 * {@code JobRuntime} names the outcomes it knows structurally (a refusal before a call, a hook's
 * denial, a throw, a result a hook withheld, a delegate's ending) and asks {@link #outcome} for the
 * rest.
 *
 * <h2>An outcome is read off a shape the tool produces, never off a word in its content</h2>
 *
 * <p>A tool refuses by returning a sentence, not by throwing, so the result is all there is to
 * read. But a word is not a shape: {@code file_grep} for "refused" answers with lines holding it,
 * an agent's answer may use it, and most real refusals ("there is no file at …") never say it. So
 * each tool is read by what it actually writes: {@code run}'s status line; the harness-written
 * success sentence of a tool that has one ({@code todo_write}, {@code file_edit}, {@code
 * file_delete}, {@code file_move}, {@code file_stat}), anything else from it being its refusal; the
 * fixed sentences a content tool's fence and not-found refusals begin with, which a file's own
 * first line is not going to; and, for every tool, the argument errors {@code ToolArguments}
 * writes, which begin with the tool's own name. Anything no shape claims is {@link #OK}: a word too
 * kind now and then is a smaller wrong than calling a found answer refused.
 *
 * <h2>A file that is not there was not refused</h2>
 *
 * <p>Rule 7 (spec 2026-09-29 §3): a file tool's own "there is no file at …" opening was, before
 * this, read the same as every other fence sentence -- {@link #REFUSED} -- which tells a person
 * reading the record that the model tried something it was not allowed, rather than that the path
 * it asked for is simply not there. {@link #NOT_FOUND} is that word, read for the file tools alone
 * ({@code file_read}, {@code file_stat}, {@code file_glob}, {@code file_grep}, {@code file_edit},
 * {@code file_delete}, {@code file_move}); every other opening in {@link #FENCE} is still {@link
 * #REFUSED}.
 */
public final class ToolLines {

  public static final String OK = "ok";
  public static final String REFUSED = "refused";
  public static final String DENIED = "denied";
  public static final String ERROR = "error";

  /** The {@code run} gate put the command to a person, or a delegate is waiting on one. */
  public static final String ASKED = "asked";

  public static final String TIMED_OUT = "timed out";
  public static final String CANCELLED = "cancelled";

  /** A {@code run} result whose status line the display cut took with the head. */
  public static final String RAN = "ran";

  /** A file tool's answer that the file is not there — rule 7 (spec 2026-09-29 §3). */
  public static final String NOT_FOUND = "not found";

  /** How long a salient argument may be. */
  static final int MOST = 120;

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern EXIT = Pattern.compile("^exit (-?\\d+) after ");
  private static final Set<String> BY_PATH =
      Set.of(
          FileTools.READ_NAME,
          FileTools.STAT_NAME,
          FileTools.GREP_NAME,
          FileTools.EDIT_NAME,
          FileTools.DELETE_NAME);

  /** The file tools, whose "there is no file at …" is an absence, not a refusal. */
  private static final Set<String> FILE_TOOLS =
      Set.of(
          FileTools.READ_NAME,
          FileTools.STAT_NAME,
          FileTools.GREP_NAME,
          FileTools.GLOB_NAME,
          FileTools.EDIT_NAME,
          FileTools.DELETE_NAME,
          FileTools.MOVE_NAME);

  /**
   * Every tool with a salient argument: any other is answered without reading its arguments, which
   * for a large write would be a parse spent on nothing.
   */
  private static final Set<String> SALIENT =
      Set.of(
          RunTool.NAME,
          TodoTools.WRITE_NAME,
          AgentRunTool.NAME,
          FileTools.GLOB_NAME,
          FileTools.MOVE_NAME,
          FileTools.READ_NAME,
          FileTools.STAT_NAME,
          FileTools.GREP_NAME,
          FileTools.EDIT_NAME,
          FileTools.DELETE_NAME);

  /** What {@code JobRuntime.usable} puts in place of a blank result, after the tool's name. */
  private static final String NOTHING = "' returned nothing at all";

  /**
   * The tools whose success is one sentence the harness writes: that sentence is {@link #OK}, and
   * anything else from them is a refusal, because a refusal is all else they return.
   */
  private static final Map<String, Predicate<String>> SUCCEEDS_AS =
      Map.of(
          TodoTools.WRITE_NAME, first -> first.startsWith("Done. The list is now:"),
          FileTools.EDIT_NAME,
              first ->
                  first.startsWith("Wrote ")
                      || first.startsWith("Replaced the one occurrence of the text in "),
          FileTools.DELETE_NAME, first -> first.startsWith("Deleted "),
          FileTools.MOVE_NAME, first -> first.startsWith("Moved "),
          FileTools.STAT_NAME, first -> first.startsWith("The file "));

  /**
   * The content tools, whose success is whatever they found: only a refusal's own opening sentence
   * says it was refused.
   */
  private static final Set<String> FENCED =
      Set.of(FileTools.READ_NAME, FileTools.GLOB_NAME, FileTools.GREP_NAME);

  /**
   * The openings of the file tools' fence, not-found and unreadable refusals, as {@code FileTools}
   * and the providers behind it write them — {@code FileWords}' for a client's file as for the
   * server's own.
   */
  private static final Pattern FENCE =
      Pattern.compile(
          "^(?:"
              + "there is no file at "
              + "|path .+? is outside every root this job"
              + "|path .+? is outside this session's workspace"
              + "|path .+? is inside this session's workspace, which is .+?, but hidden"
              + "|path .+? was inside this session's workspace, but the workspace moved"
              + "|no workspace is set for this session"
              + "|this request named no path"
              + "|'.*' is not a path this machine can even name"
              + "|path .+? is (?:not a regular file|not UTF-8|a directory|a link|\\d+ bytes, and"
              + " this (?:server|client) will not)"
              + "|path .+? is an? .+?(?:, which this client reads, and this one could not be"
              + " converted|, and it could not be uploaded|, and the server |, and at \\d+ bytes"
              + "|, and this client reads text files only)"
              + "|path .+? could not be (?:read|opened|written|edited|deleted|moved)"
              + "|line \\d+ of this file is \\d+ bytes, and one read carries at most"
              + "|this request asks for a (?:window|search) this (?:client|server) cannot"
              + "|the workspace is read-only to this agent"
              + "|no root is reachable on this run"
              + "|(?i:this job has no filesystem at all)"
              + "|no presence is serving the project "
              + "|a glob pattern is required"
              + "|'.*' is an absolute pattern"
              + "|'.*' is not a usable glob"
              + "|'.*' has \\d+ '\\*\\*/' segments"
              + "|more than \\d+ files match"
              + "|the tree under .+ could not be (?:searched|listed) in full"
              + ")");

  /**
   * What {@code agent_run} writes when a delegate ran, answered or not; anything else from it is a
   * refusal of the call before any delegate started.
   */
  private static final Pattern DELEGATE_RAN =
      Pattern.compile(
          "^the agent '[^']*'(?:, shown [^:]*?,)? (?:answered:|did not reach an answer\\.)");

  /** How many of the last lines of a failed command's answer {@link #tail} keeps. */
  public static final int TAIL_LINES = 20;

  /** How many characters {@link #tail} keeps at most, from the end: a line can be long. */
  public static final int TAIL_CHARS = 2_000;

  private ToolLines() {}

  /**
   * The end of a {@code run} result, for the record to keep beside a command that did not end ok:
   * its last {@link #TAIL_LINES} lines, stdout's and stderr's as {@code RunTool} writes them, and
   * no more than {@link #TAIL_CHARS} characters of them, from the end. The status line is left off
   * (the outcome's word says it), and so are the display cut's note and an empty stream's section,
   * which say nothing about the failure. A refusal, which is one sentence and no status, is kept
   * whole within the same bounds.
   *
   * @return that tail, or null when nothing is left
   */
  public static String tail(String result) {
    if (result == null || result.isBlank()) {
      return null;
    }
    List<String> lines = new ArrayList<>(result.strip().lines().toList());
    if (!lines.isEmpty() && isStatus(lines.get(0))) {
      lines.remove(0);
    }
    if (!lines.isEmpty() && lines.get(0).startsWith("[Cut:")) {
      lines.remove(0);
    }
    // An empty stream's section: "--- stderr ---" then "(nothing)", wherever it falls.
    for (int at = lines.size() - 2; at >= 0; at--) {
      if (lines.get(at).startsWith("--- ")
          && lines.get(at).endsWith(" ---")
          && "(nothing)".equals(lines.get(at + 1))) {
        lines.remove(at + 1);
        lines.remove(at);
      }
    }
    List<String> last = lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size());
    String kept = String.join("\n", last).strip();
    if (kept.isEmpty()) {
      return null;
    }
    if (kept.length() <= TAIL_CHARS) {
      return kept;
    }
    int from = kept.length() - (TAIL_CHARS - 1);
    if (Character.isLowSurrogate(kept.charAt(from))) {
      from++;
    }
    return "…" + kept.substring(from);
  }

  private static boolean isStatus(String first) {
    return EXIT.matcher(first).find()
        || first.startsWith("timed out after")
        || first.startsWith("cancelled after");
  }

  /** The argument a person follows this call by, one short line; empty when there is none. */
  public static String salient(String tool, String arguments) {
    if (!SALIENT.contains(tool)) {
      return "";
    }
    JsonNode args;
    try {
      args = arguments == null || arguments.isBlank() ? null : JSON.readTree(arguments);
    } catch (Exception unreadable) {
      return "";
    }
    if (args == null || !args.isObject()) {
      return "";
    }
    String said;
    if (RunTool.NAME.equals(tool)) {
      said = joined(args.path("command"));
    } else if (TodoTools.WRITE_NAME.equals(tool)) {
      int ops = args.path("ops").isArray() ? args.path("ops").size() : -1;
      said = ops < 0 ? "" : ops == 1 ? "1 op" : ops + " ops";
    } else if (AgentRunTool.NAME.equals(tool)) {
      said = args.path("agent").asText("");
    } else if (FileTools.GLOB_NAME.equals(tool)) {
      said = args.path("pattern").asText("");
    } else if (FileTools.MOVE_NAME.equals(tool)) {
      said =
          args.path("path").asText("")
              + (args.path("to").isTextual() ? " → " + args.path("to").asText() : "");
    } else if (BY_PATH.contains(tool)) {
      said = args.path("path").asText("");
    } else {
      said = "";
    }
    return cut(said);
  }

  /** A call that ran and returned {@code result}, in a word. */
  public static String outcome(String tool, String result) {
    String first = result == null ? "" : result.lines().findFirst().orElse("").strip();
    // JobRuntime.usable's stand-in for a blank result: a fault in the tool.
    if (first.startsWith("the tool '" + tool + NOTHING)) {
      return ERROR;
    }
    if (RunTool.NAME.equals(tool)) {
      return ran(first);
    }
    if (argumentsRefused(tool, first)) {
      return REFUSED;
    }
    if (FILE_TOOLS.contains(tool) && first.startsWith("there is no file at ")) {
      return NOT_FOUND;
    }
    if (AgentRunTool.NAME.equals(tool)) {
      return DELEGATE_RAN.matcher(first).find() ? OK : REFUSED;
    }
    Predicate<String> succeeded = SUCCEEDS_AS.get(tool);
    if (succeeded != null) {
      return succeeded.test(first) ? OK : REFUSED;
    }
    if (FENCED.contains(tool)) {
      return FENCE.matcher(first).find() ? REFUSED : OK;
    }
    return OK;
  }

  /**
   * A delegate that ran and came back with {@code ending}, in a word: {@link #OK} for an answer,
   * {@link #ASKED} for one waiting on a person, and otherwise the ending itself, in the spelling
   * the record's {@code delegate_returned} line uses.
   */
  public static String delegation(Outcome.Ending ending) {
    return switch (ending) {
      case ANSWERED -> OK;
      case AWAITING -> ASKED;
      default -> ending.name().toLowerCase(Locale.ROOT);
    };
  }

  /** {@code run}'s status line, or its refusal: every result that ran opens with one. */
  private static String ran(String first) {
    Matcher exit = EXIT.matcher(first);
    if (exit.find()) {
      return "0".equals(exit.group(1)) ? OK : "exit " + exit.group(1);
    }
    if (first.startsWith("timed out after")) {
      return TIMED_OUT;
    }
    if (first.startsWith("cancelled after")) {
      return CANCELLED;
    }
    if (first.startsWith("[Cut:")) {
      return RAN;
    }
    return REFUSED;
  }

  /**
   * {@code ToolArguments}' errors, and every tool's own argument refusals after them, open with the
   * tool's name and one of four verbs — {@code todo_write refused operation 2: …}.
   */
  private static boolean argumentsRefused(String tool, String first) {
    if (!first.startsWith(tool + " ")) {
      return false;
    }
    String rest = first.substring(tool.length() + 1);
    return rest.startsWith("needs ")
        || rest.startsWith("could not read ")
        || rest.startsWith("was given ")
        || rest.startsWith("refused");
  }

  private static String joined(JsonNode command) {
    if (command.isTextual()) {
      return command.asText();
    }
    if (!command.isArray()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    command.forEach(part -> parts.add(part.asText("")));
    return String.join(" ", parts);
  }

  private static String cut(String said) {
    String flat = said.replaceAll("\\s+", " ").strip();
    return flat.length() <= MOST ? flat : flat.substring(0, MOST - 1) + "…";
  }
}
