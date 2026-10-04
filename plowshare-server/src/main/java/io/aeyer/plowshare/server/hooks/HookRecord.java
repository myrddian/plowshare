package io.aeyer.plowshare.server.hooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * One hook decision, as the content of a {@code HOOK} log entry.
 *
 * <h2>Why every decision is written down, including the invisible ones</h2>
 *
 * <p>dsh records what a plugin injects but not what a pipeline listener transformed in flight. Here
 * the rule is the other way round: a transform the model never sees afterwards — a volatile
 * addition, a redaction, a denial — is still a fact about what happened, and the log is the record
 * of what happened. The entry never projects, so recording it costs no prefix.
 *
 * @param hook the hook's declared name, or {@code harness:<step>} for the harness
 * @param file the file it was loaded from, or {@code null} for the harness
 * @param tool the tool name on a tool stage, otherwise {@code null}
 * @param decision one of the constants below
 * @param reason a denial's or failure's sentence, otherwise {@code null}
 * @param added what an addition or a note contributed, otherwise {@code null}
 * @param original what a redaction or a rewrite replaced, otherwise {@code null}
 * @param tookMs how long the hook took, including waiting for a context
 */
public record HookRecord(
    String hook,
    String file,
    Tier tier,
    Stage stage,
    String tool,
    String decision,
    String reason,
    String added,
    String original,
    long tookMs) {

  public static final String ALLOW = "allow";
  public static final String DENY = "deny";
  public static final String REWRITE = "rewrite";

  /** A hook sent a call to a person. */
  public static final String ASK = "ask";

  public static final String REDACT = "redact";
  public static final String NOTE = "note";
  public static final String ADD = "add";

  /** A {@code fold.post} hook kept text verbatim after the folder's summary. */
  public static final String KEEP = "keep";

  /** A hook asked for the log's owner to be told something. */
  public static final String NOTIFY = "notify";

  public static final String FAILED = "failed";

  /**
   * A hook's decision the harness did not apply: the stuck trap's advice when the turn ended before
   * it was used, or a {@code fold.post} keep dropped by the 512-token cap. A dropped keep's record
   * comes straight after that hook's {@link #KEEP} record, so the log reads keep then unused for
   * the same hook.
   */
  public static final String UNUSED = "unused";

  /**
   * A hook's answer arrived and was dropped as nothing worth sending: the stuck advisor's {@code
   * {"note": null}}, or an answer that is not advice. Only this record says it came.
   */
  public static final String SWALLOWED = "swallowed";

  /** The most of {@code added} or {@code original} one entry carries. */
  public static final int MAX_TEXT = 4_000;

  /** What a capped text ends with, so a reader knows the log is not the whole of it. */
  public static final String TRUNCATED = " … [truncated by the log]";

  private static final ObjectMapper JSON = new ObjectMapper();

  public HookRecord {
    Objects.requireNonNull(hook, "hook");
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(decision, "decision");
  }

  /** The entry's content: a JSON object, absent fields left out, long text capped. */
  public String json() {
    ObjectNode node = JSON.createObjectNode();
    node.put("hook", hook);
    if (file != null) {
      node.put("file", file);
    }
    node.put("tier", tier.wireName());
    node.put("stage", stage.wireName());
    if (tool != null) {
      node.put("tool", tool);
    }
    node.put("decision", decision);
    if (reason != null) {
      node.put("reason", capped(reason));
    }
    if (added != null) {
      node.put("added", capped(added));
    }
    if (original != null) {
      node.put("original", capped(original));
    }
    node.put("tookMs", tookMs);
    try {
      return JSON.writeValueAsString(node);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(
          "a tree of strings and numbers did not serialise", impossible);
    }
  }

  private static String capped(String text) {
    return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + TRUNCATED;
  }
}
