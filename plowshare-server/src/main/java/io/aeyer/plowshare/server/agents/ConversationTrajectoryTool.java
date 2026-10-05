package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.api.EntryPageView;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code conversation_trajectory}: a bounded read of another conversation's append-only log for a
 * diagnostic agent.
 *
 * <h2>The home is authority, not an argument</h2>
 *
 * <p>The model names a conversation but cannot name a tier. {@link AgentTool#run} supplies the
 * run's {@link Home}, and this tool returns the same not-found sentence both when the id does not
 * exist and when it belongs to a different home. A diagnostic bot can therefore inspect runs in the
 * tier it was opened in without becoming a cross-project log reader or learning that another
 * project's id exists.
 *
 * <h2>The log, not the projection</h2>
 *
 * <p>{@link EntryStore#pageOfLog} includes superseded entries, diagnostics, failed attempts, tool
 * calls and tool results. That distinction is the reason this is a new tool rather than a read of
 * the diagnostic bot's own transcript: the projected conversation may omit precisely the event that
 * explains a failure. The result uses {@link EntryPageView}, the same bounded wire shape as the
 * HTTP and frame trajectory surfaces, so all three readers agree about kinds, excerpts, timing and
 * provenance.
 *
 * <p>A diagnostic reader has one additional operation those person-facing surfaces do not:
 * presenting a tool-result handle reads the exact persisted result. Daedalus cannot use its
 * ordinary {@code result_read} for that because the handle belongs to the target conversation
 * rather than its diagnostic conversation. This tool has already established the shared home before
 * it asks {@link EntryStore#redeem} with the target id, preserving both scope checks while making a
 * cut entry inspectable.
 */
public final class ConversationTrajectoryTool implements AgentTool {

  public static final String NAME = "conversation_trajectory";
  public static final int MOST_ENTRIES = 20;

  private static final String EXAMPLE = "{\"conversation\":\"cnv_123\",\"offset\":0,\"limit\":20}";

  private static final ToolSchema SCHEMA =
      ToolSchema.from(
          NAME,
          "Read the persisted append-only trajectory of a conversation in this run's tier."
              + " Returns JSON containing bounded entry excerpts, tool calls and results,"
              + " superseded and diagnostic rows, timestamps, durations, and model"
              + " provenance. Read further pages by increasing offset; at most 20 entries"
              + " are returned per call. Set tail=true to jump directly to the final page;"
              + " its returned offset can be decreased to walk backward. A row's cut flag"
              + " describes only its 8,000-character"
              + " diagnostic excerpt, not what the original model received. To read the"
              + " exact persisted content of a tool-result row, call this tool with that"
              + " row's handle and conversation. Set ordinal to read one exact search hit, independent of page order.",
          parameters());

  /** Immutable and safe to share between every concurrent diagnostic read. */
  private static final ObjectWriter JSON = new ObjectMapper().findAndRegisterModules().writer();

  private final ConversationStore conversations;
  private final EntryStore entries;

  public ConversationTrajectoryTool(ConversationStore conversations, EntryStore entries) {
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.entries = Objects.requireNonNull(entries, "entries");
  }

  @Override
  public ToolSchema schema() {
    return SCHEMA;
  }

  @Override
  public String run(String argumentsJson, Home home) {
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    Objects.requireNonNull(home, "home");
    try {
      JsonNode args = ToolArguments.parse(argumentsJson, NAME, EXAMPLE);
      String id =
          ToolArguments.requireText(
              args,
              "conversation",
              NAME,
              "the id of the conversation whose persisted trajectory should be read");
      int limit = Math.min(whole(args, "limit", MOST_ENTRIES), MOST_ENTRIES);
      boolean tail =
          ToolArguments.optionalFlag(
              args,
              "tail",
              false,
              value ->
                  new ToolArguments.BadArguments(
                      NAME
                          + " needs 'tail' to be true or false;"
                          + " this received "
                          + value
                          + "."));
      String sentHandle =
          ToolArguments.optionalText(
              args,
              "handle",
              value ->
                  new ToolArguments.BadArguments(
                      NAME
                          + " needs 'handle' to be a UUID from a"
                          + " tool-result row; this received "
                          + value
                          + "."));
      if (limit < 1) {
        throw new ToolArguments.BadArguments(
            NAME + " needs 'limit' to be 1 or more; this asked for " + limit + ".");
      }
      int offset = tail || sentHandle != null ? 0 : whole(args, "offset", 0);
      if (offset < 0) {
        throw new ToolArguments.BadArguments(
            NAME + " needs 'offset' to be 0 or later; this asked for " + offset + ".");
      }

      ConversationRecord conversation = conversations.find(id).orElse(null);
      if (conversation == null || !home.equals(conversation.home())) {
        return "No conversation with id "
            + id
            + " is available in this run's tier, so no trajectory was read.";
      }
      if (sentHandle != null) {
        return result(id, sentHandle);
      }
      if (args.has("ordinal")) {
        int ordinal = whole(args, "ordinal", 0);
        if (ordinal < 1) throw new ToolArguments.BadArguments("ordinal must be at least 1");
        return render(EntryPageView.of(entries.entryAt(id, ordinal), 0, 1));
      }
      if (tail) {
        EntryPageView first = EntryPageView.of(entries.pageOfLog(id, 0, 1), 0, limit);
        int lastOffset = Math.max(0, first.total() - limit);
        if (first.total() == 0) {
          return render(first);
        }
        return render(
            EntryPageView.of(entries.pageOfLog(id, lastOffset, limit), lastOffset, limit));
      }
      return render(EntryPageView.of(entries.pageOfLog(id, offset, limit), offset, limit));
    } catch (ToolArguments.BadArguments refused) {
      return refused.getMessage();
    }
  }

  /**
   * The full persisted result from the conversation being diagnosed.
   *
   * <p>This is deliberately not {@code result_read}. That tool is authorised by its run's
   * transcript, which for Daedalus is the diagnostic conversation; a handle in the target
   * conversation therefore cannot be redeemed there. The home check above and {@link
   * EntryStore#redeem}'s conversation predicate are the two fences on this exceptional read.
   */
  private String result(String conversation, String sentHandle) {
    final UUID handle;
    try {
      handle = UUID.fromString(sentHandle);
    } catch (IllegalArgumentException unreadable) {
      throw new ToolArguments.BadArguments(
          NAME
              + " needs 'handle' to be a UUID from a"
              + " tool-result row; this received '"
              + sentHandle
              + "'.");
    }
    EntryRecord result = entries.redeem(conversation, handle).orElse(null);
    if (result == null) {
      return "No tool result with handle "
          + sentHandle
          + " is stored in conversation "
          + conversation
          + ".";
    }
    if (result.content() == null) {
      return "The tool result with handle "
          + sentHandle
          + " was ejected on "
          + result.ejectedAt()
          + "; its persisted content is no longer available."
          + (result.export() == null
              ? " This deployment kept no export."
              : " It was exported to " + result.export() + ".");
    }
    return result.content();
  }

  private int whole(JsonNode args, String name, int fallback) {
    JsonNode sent = args.path(name);
    if (sent.isNumber() && !sent.isIntegralNumber()) {
      throw unreadable(name, sent);
    }
    return ToolArguments.optionalInt(args, name, fallback, value -> unreadable(name, value));
  }

  private ToolArguments.BadArguments unreadable(String name, JsonNode value) {
    return new ToolArguments.BadArguments(
        NAME
            + " needs '"
            + name
            + "' to be a whole number; this received "
            + value
            + ". Send "
            + EXAMPLE
            + ".");
  }

  private String render(EntryPageView page) {
    try {
      return JSON.writeValueAsString(page);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("the trajectory page could not be rendered", impossible);
    }
  }

  private static Map<String, Object> parameters() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "conversation",
        ToolArguments.string(
            "Conversation id, such as cnv_123. It must belong to this run's tier."));
    properties.put(
        "handle",
        ToolArguments.string(
            "Optional handle from a tool-result row. When present, return that exact"
                + " persisted result instead of an entry page. This is how a diagnostic"
                + " agent reads beyond a row whose excerpt has cut=true."));
    properties.put(
        "ordinal",
        integer(
            "Exact entry ordinal from a conversation_search hit; returns one bounded entry excerpt.",
            1,
            null));
    properties.put(
        "tail",
        ToolArguments.flag(
            "Set true to ignore offset and return the final page of the trajectory. Use this"
                + " when the operator says end, latest, last, recent, or towards the end."
                + " The response gives the computed offset; subtract limit from it to"
                + " inspect the preceding page."));
    properties.put(
        "offset", integer("How many trajectory entries to skip. Defaults to 0.", 0, null));
    properties.put(
        "limit",
        integer("Maximum entries to return. Defaults to 20 and is capped at 20.", 1, MOST_ENTRIES));
    return ToolArguments.object(properties, List.of("conversation"));
  }

  private static Map<String, Object> integer(String description, int minimum, Integer maximum) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "integer");
    schema.put("description", description);
    schema.put("minimum", minimum);
    if (maximum != null) {
      schema.put("maximum", maximum);
    }
    return schema;
  }
}
