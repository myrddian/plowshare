package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.EntryPage;
import java.time.Instant;
import java.util.List;

/**
 * One entry of a conversation on the wire: where it came, what it is, as much of it as a page
 * carries, and when.
 *
 * <h2>{@code kind} is the stored spelling and not the constant's name</h2>
 *
 * <p>{@code EntryKind.wireName} is the string the {@code kind} column holds, and it is what travels
 * — {@code utterance}, {@code tool_result}, {@code attempt_failed}. A client reading this and a
 * person reading the table are then looking at the same word, which is the whole point of a
 * trajectory. {@code TurnView} sends {@code Outcome.Ending} as {@code name()} for the opposite
 * reason and it is not an inconsistency: an ending has no stored spelling of its own, the column
 * holds the constant's name, and both records send whatever the database holds.
 *
 * <h2>{@code excerpt} and {@code length} are two fields, and {@code cut} is the question</h2>
 *
 * <p>{@link EntryPage} argues it: a reader cannot tell a text that fitted from one that was
 * shortened by looking at the text, and a client that inferred it by comparing lengths would have
 * to know this server's cap. The three fields are sent rather than two so that no client has to.
 *
 * <p>Utterances, answers, refusals and summaries carry their complete text. Other entry kinds
 * remain capped. A {@code tool_result} is reachable in full through its {@code handle}: {@code
 * result_read} redeems one in its own conversation, and the diagnostic {@code
 * conversation_trajectory} tool redeems one from the target conversation after enforcing its home.
 * Cut diagnostics have no full-text retrieval through this surface; their length and cut flag tell
 * readers how much was omitted.
 *
 * @param ordinal where in the log this came; 1 is the first thing recorded. Arrival order, which is
 *     not the order the page is in
 * @param turnOrdinal which turn produced this — which of the person's utterances it belongs to — or
 *     for a {@code summary} the last turn it stands for. <b>The other number, and the vocabulary
 *     this server settled</b>: a turn is a person speaking, a step is a model call
 * @param kind what this is, as the column spells it
 * @param excerpt complete transcript text or a bounded excerpt for other kinds, or null for a tool
 *     result whose payload has been ejected — see {@code ejectedAt}. Blank for an answer that was
 *     entirely tool calls
 * @param length how many characters the entry really is
 * @param cut whether there is more of it than {@code excerpt} shows
 * @param supersededBy the ordinal of the summary that folded this entry away, or null for one no
 *     fold covers. <b>Never set on an entry from the chat reading</b>, which is what makes the two
 *     readings tell one story: the trajectory shows what the fold hid and the chat shows what
 *     stands
 * @param toolCallId the call this result answers, null for every other kind
 * @param toolCalls what the model asked for, in the order it asked
 * @param handle the address {@code result_read} redeems this result at, null for every other kind
 *     and for a result older than the column
 * @param recordedAt when this was written, or null for an entry written before the column existed.
 *     <b>Null is a real answer</b> and a client must render it as "not recorded" rather than as an
 *     epoch
 * @param ejectedAt when this payload was ejected, or null while it is still here. Present for
 *     exactly the rows with a null {@code excerpt}, and it is what tells a reader that a blank is
 *     not a tool that returned nothing — <b>{@code cut} is false for one of these</b>, because the
 *     page did not cut it and there is nothing further to ask for
 * @param tookMillis how long the operation that produced this entry took, or null for a kind that
 *     records no operation and for one nobody measured. <b>Not the gap to the entry before it</b>,
 *     which holds everything that happened in between; a client wanting that subtracts two {@code
 *     recordedAt}s and is measuring a different thing
 * @param speaker who spoke this — {@code "person"} or {@code "harness"} — or null for every kind
 *     but {@code utterance}. {@code EntryPage.Row.speaker}'s {@code kind}, as {@code
 *     Speaker.Kind.wireName} spells it, so a client branches on the same two words the server does
 * @param speakerName the handle, for a person, or the harness source (a run's id, an approval's, an
 *     event's, or plain {@code "harness"}); null when nothing was recorded — an older entry, or a
 *     person whose handle nobody knew
 * @param outcome a {@code tool_result}'s outcome in a word, as the runtime told it, or null for
 *     every other kind and for a result older than {@code V62}
 */
public record EntryView(
    int ordinal,
    int turnOrdinal,
    String kind,
    String excerpt,
    int length,
    boolean cut,
    Instant ejectedAt,
    Integer supersededBy,
    String toolCallId,
    List<AskedView> toolCalls,
    String handle,
    Instant recordedAt,
    Long tookMillis,
    String dispatch,
    String wireModel,
    String completion,
    String speaker,
    String speakerName,
    String outcome) {

  static EntryView of(EntryPage.Row row) {
    return new EntryView(
        row.ordinal(),
        row.turnOrdinal(),
        row.kind().wireName(),
        row.excerpt(),
        row.length(),
        row.cut(),
        row.ejectedAt(),
        row.supersededBy(),
        row.toolCallId(),
        row.toolCalls().stream().map(AskedView::of).toList(),
        // A string and not a UUID: the id travels as text everywhere
        // else on this surface, and result_read takes what it is given.
        row.handle() == null ? null : row.handle().toString(),
        row.recordedAt(),
        row.tookMillis(),
        // Which target answered, on an answer or a refusal, and null on
        // everything else -- an utterance carries no model. The rest of
        // the provenance (pool, tokens, the fallback reason) is behind a
        // deliberate second read; a trajectory shows who spoke, not the
        // whole audit. These are stored spellings passed straight
        // through: the wire carries what the column holds.
        row.dispatch(),
        row.wireModel(),
        row.completion(),
        row.speaker() == null ? null : row.speaker().kind().wireName(),
        row.speaker() == null ? null : row.speaker().name(),
        row.outcome());
  }

  /**
   * One call a model asked for, with its arguments bounded the way the entry's own text is.
   *
   * <p>The same three fields for the same reason — {@code file_edit} sends a whole file as an
   * argument, so a view that carried them whole would be unbounded in the dimension the page exists
   * to bound.
   *
   * @param id the address the model gave this call, which a {@code tool_result} answers by
   * @param name the tool asked for. <b>Model-supplied text</b>: a call to a tool that does not
   *     exist is still recorded, so this is neither a name from any registry nor free of line
   *     breaks, and whatever renders it for a person is what has to flatten it
   * @param arguments the raw JSON the model emitted, up to this server's cap
   * @param length how many characters the arguments really were
   * @param cut whether there is more of them than {@code arguments} shows
   * @param salient the one argument {@code ToolLines.salient} picked out, whole even where {@code
   *     arguments} is cut, or null for a call recorded before {@code V62}
   * @param opened the child conversation this call started, and the agent that ran in it, or null
   *     for a call that opened none
   */
  public record AskedView(
      String id,
      String name,
      String arguments,
      int length,
      boolean cut,
      String salient,
      OpenedView opened) {

    static AskedView of(EntryPage.Asked asked) {
      return new AskedView(
          asked.id(),
          asked.name(),
          asked.arguments(),
          asked.length(),
          asked.cut(),
          asked.salient(),
          asked.opened() == null
              ? null
              : new OpenedView(asked.opened().conversation(), asked.opened().agent()));
    }
  }

  /** Where a call's delegated child logs: its conversation and its agent. */
  public record OpenedView(String conversation, String agent) {}
}
