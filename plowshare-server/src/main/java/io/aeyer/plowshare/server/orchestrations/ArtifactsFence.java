package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.aeyer.plowshare.server.agents.FileTools;
import io.aeyer.plowshare.server.agents.RunExtras;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rule 4 (spec 2026-09-29 §3): a conductor's {@code file_edit} — and {@code file_delete} and {@code
 * file_move}, should one ever hold them — reaches its own artifacts directory and, for a phase, its
 * phase directory, and nothing else. Measured, orc_31893856D8F462A1: the conductor's prose said
 * "for goal.md, spec.md, plan.md and test-design.md only" and it wrote rpg/*.py itself, eleven
 * edits, with the coder called once in phases 1-4. Code and tests go through the coder.
 *
 * <h2>How a path is judged</h2>
 *
 * <p>On the path the model named, normalised, so {@code ..} is judged where it lands. Backslashes
 * are read as separators before normalising: to a Windows client they are, and a {@code ..\..} left
 * unnormalised would be judged inside while landing outside. The directories are project-relative
 * (V60's {@code artifacts_dir}) and a tool's path is absolute under a root, so a path is inside
 * when the directory appears in it as a whole run of segments.
 *
 * <p><b>Links are not resolved here, and need not be.</b> The files may be on the client, where the
 * server cannot follow a link, and the file tools already refuse to write through one: a link named
 * as the target is refused and every write opens with {@code NOFOLLOW_LINKS}. So a path this fence
 * lets through is still judged by the tool's own fences on what it actually reaches.
 *
 * <h2>The refusal says what happened, where it may write, and who writes the rest — and when</h2>
 *
 * <p>code_implementation has a coder; implement_specification has none (its {@code calls} is {@code
 * code_reviewer} alone) and its code is written by the phase runs it starts — a refusal naming an
 * agent the run cannot call would be one more dead end to spend a turn on.
 *
 * <p><b>Not an order to act now.</b> The spec's first wording ("That is the coder's work: agent_run
 * coder with the check's output.") and its phase twin ("start it with
 * orchestrate_code_implementation") were stage-blind commands. Measured 2026-09-29,
 * orc_318D8534E8B9D04B: at the {@code spec} stage, told by the verifier that a named test observes
 * a requirement, the conductor tried to write six test files; each was answered only "That is a
 * phase's work: start it with orchestrate_code_implementation." — which neither said the write was
 * refused (its next thought counted four of the files as created) nor that the tests come later,
 * and told it to go and have them written at once. So the refusal now says it refused, names the
 * path and the directory it may write in, and says at which stage the code and tests are written
 * and that until then they are only named.
 */
public final class ArtifactsFence implements RunExtras.Fence {

  /**
   * Who writes code and tests, for code_implementation's conductor — and who corrects a test found
   * wrong. Measured 2026-09-29/30, orc_318DFD3782228160: told to correct a wrong test "in place
   * with file_edit", the conductor was refused here 17 times on test files; the way that works is
   * the coder, reached from {@code code} or {@code review} by a return to {@code tests}.
   */
  public static final String REFUSAL =
      "Code and tests are written by the coder, called with"
          + " agent_run at the `tests` and `code` stages; until then they are only named, in"
          + " spec.md and plan.md, and a test found wrong is the coder's to correct: give it the"
          + " reason, returning to `tests` first from `code` or `review`.";

  /** Who writes code and tests, for implement_specification's conductor. */
  public static final String PHASE_REFUSAL =
      "Code and tests are written by the phase runs you"
          + " start at the `phases` stage; until then they are only named, in spec.md and"
          + " plan.md.";

  /** Who writes them, for any other definition's conductor that can call a coder. */
  static final String CODER_ELSEWHERE =
      "Code and tests are written by the coder, called with"
          + " agent_run at the stage that builds them; until then they are only named, in your"
          + " documents.";

  /** Who writes them, for any other definition's conductor that cannot. */
  static final String PHASE_ELSEWHERE =
      "Code and tests are written by the phase runs you start"
          + " at the stage that builds them; until then they are only named, in your documents.";

  private static final Set<String> WRITES =
      Set.of(FileTools.EDIT_NAME, FileTools.DELETE_NAME, FileTools.MOVE_NAME);

  /** The paths a write names: {@code file_move} names two, and both must be inside. */
  private static final List<String> PATHS = List.of("path", "to");

  /** As {@code ToolArguments} reads them, so the fence and the tool read the same call. */
  private static final ObjectReader JSON = new ObjectMapper().reader();

  private final List<String> directories;

  /** Who writes code and tests, and when: the end of every refusal. */
  private final String refusal;

  private ArtifactsFence(List<String> directories, String refusal) {
    this.directories = List.copyOf(directories);
    this.refusal = refusal;
  }

  /**
   * The fence for a run whose first message named {@code artifactsDir}, refused in the words for a
   * conductor with a coder.
   *
   * @param artifactsDir the run's directory, or null for a run that has none
   * @return the fence, or {@link RunExtras.Fence#NONE} for none
   */
  public static RunExtras.Fence of(String artifactsDir) {
    return of(artifactsDir, REFUSAL);
  }

  /**
   * The fence for a run whose first message named {@code artifactsDir}.
   *
   * @param artifactsDir the run's directory, or null for a run that has none
   * @param refusal who writes code and tests, and when — the end of what the model is shown for a
   *     write outside it; {@link #refusalFor} picks it
   * @return the fence, or {@link RunExtras.Fence#NONE} for a run with no directory, which has
   *     nothing to be fenced to
   */
  public static RunExtras.Fence of(String artifactsDir, String refusal) {
    if (artifactsDir == null || artifactsDir.isBlank()) {
      return RunExtras.Fence.NONE;
    }
    List<String> directories = new ArrayList<>();
    String own = trimmed(artifactsDir);
    if (own != null) {
      directories.add(own);
    }
    String phase = trimmed(Utterances.phaseDirOf(artifactsDir));
    if (phase != null) {
      directories.add(phase);
    }
    if (directories.isEmpty()) {
      // A directory that normalises to nothing — "./", say — would be every path's prefix,
      // and so no fence at all; it is none, said plainly.
      return RunExtras.Fence.NONE;
    }
    return new ArtifactsFence(directories, refusal == null ? REFUSAL : refusal);
  }

  /**
   * The refusal for a conductor of {@code definitionName}: code_implementation's names its coder,
   * implement_specification's the phase run it has instead, and any other definition's the coder
   * when it can call one and a phase otherwise.
   *
   * @param definitionName the orchestration the run conducts
   * @param calls the agents its conductor may {@code agent_run}, or empty when not known
   * @return {@link #REFUSAL} or {@link #PHASE_REFUSAL} for the two shipped definitions, whose
   *     stages are known; for any other, the same without a stage's name
   */
  public static String refusalFor(String definitionName, List<String> calls) {
    if ("code_implementation".equals(definitionName)) {
      return REFUSAL;
    }
    if ("implement_specification".equals(definitionName)) {
      return PHASE_REFUSAL;
    }
    return calls != null && calls.contains("coder") ? CODER_ELSEWHERE : PHASE_ELSEWHERE;
  }

  @Override
  public String refusal(String tool, String argumentsJson) {
    if (!WRITES.contains(tool)) {
      return null;
    }
    JsonNode args;
    try {
      args = JSON.readTree(argumentsJson == null ? "" : argumentsJson);
    } catch (Exception unreadable) {
      return null; // the tool's own argument errors say what is wrong, and it runs nothing
    }
    for (String key : PATHS) {
      JsonNode value = args.path(key);
      if (value.isTextual() && !inside(value.asText())) {
        return tool
            + " refused: "
            + value.asText()
            + " is outside this run's directory, "
            + directories.get(0)
            + ", so nothing was written. You write only your own"
            + " documents there. "
            + refusal;
      }
    }
    return null;
  }

  private boolean inside(String path) {
    String normal = normal(path);
    if (normal == null) {
      return false;
    }
    for (String directory : directories) {
      if (normal.equals(directory)
          || normal.startsWith(directory + "/")
          || normal.contains("/" + directory + "/")
          || normal.endsWith("/" + directory)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A stored directory as the paths it is compared with are judged: normalised the same way (Task
   * 6's review), so a {@code ./docs/…/} or {@code docs//…} the run was given still matches the
   * {@code docs/…} a write names — and one with a {@code ..} in it is the directory it lands on. No
   * trailing slash.
   *
   * @return the directory, or null for none, or one that normalises to nothing
   */
  private static String trimmed(String directory) {
    if (directory == null) {
      return null;
    }
    String normal = normal(directory);
    return normal == null || normal.isEmpty() ? null : normal;
  }

  /** {@code path} with backslashes as separators, normalised; null for one that is not a path. */
  private static String normal(String path) {
    try {
      return Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/');
    } catch (InvalidPathException unreadable) {
      return null;
    }
  }
}
