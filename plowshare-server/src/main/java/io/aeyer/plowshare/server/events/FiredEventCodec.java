package io.aeyer.plowshare.server.events;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.util.Set;

/**
 * A fired event as it travels inside Relay's general message.
 *
 * <p>Relay is a publish/subscribe substrate: it carries a contract and stores a publication's
 * content opaquely. A payload family per consumer would make every new consumer a change to the
 * broker, its persisted vocabulary, its codec, the protocol's kind allowlist and five SDKs, so the
 * structure a subscriber needs is encoded here, by the publishing subsystem, written into {@code
 * RelayPayload.Text}, and decoded here on the read path. This is the arrangement {@code
 * RelayToolCodec} established for hosted tool envelopes and {@code InformationEventCodec} followed
 * for information notices; it adds no payload kind, no codec family and no CHECK allowlist entry.
 *
 * <p><b>Why the whole of a fired event may be published.</b> The rule is that a publication carries
 * only what the topic's entire audience may see, because {@code relay.log} serves a scope's topics
 * to anyone admitted to the scope and reports a general message's content verbatim. This family
 * clears that rule rather than being trimmed to fit it: {@code EventPayloadCodec.manual} accepts
 * exactly {@code Empty} or the explicit {@code {text: string}} DTO from {@code event.fire} and
 * refuses every other shape, so the entire content of a fired event is an event name and one piece
 * of caller-supplied text. Neither is restricted more narrowly than the topic's audience, which is
 * the server administrators who are the only accounts able to fire one. Nothing here may acquire a
 * field whose audience is narrower than that.
 *
 * <p>The codec owns the wire shape and nothing else. It decides no authority and interprets no
 * content: the text it carries is data, and {@code Dispatcher.utterance} is what fences it as data
 * when it eventually reaches a model, exactly as before this family travelled through Relay.
 *
 * <p>Validation is the notice's own invariant and runs in the record's constructor, so a notice
 * that was decoded is a notice that was validated — and it validates by building the real {@link
 * EventPayload}, so the family's rules cannot drift from the ones {@code firings} enforces. Both
 * fields are required to be present on the wire; {@code text} is written as JSON null for an empty
 * payload rather than omitted, so a missing key is a corrupt payload rather than an absent value.
 */
final class FiredEventCodec {
  /**
   * The envelope version. It is a codec concern rather than a field of the notice, so it is written
   * and required here and never travels into {@link Notice}.
   */
  static final String VERSION = "plowshare-event-fired/1";

  private static final String SCHEMA = "schema";

  /** The complete wire field allowlist: the envelope version plus the notice's two fields. */
  private static final Set<String> FIELDS = Set.of(SCHEMA, "event", "text");

  // The event name is a bounded identity and the text is bounded by its own family rule, so the
  // parser's string allowance is the broker's own text ceiling: generous enough never to refuse a
  // well-formed notice, and still a ceiling.
  private static final ObjectMapper JSON =
      JsonMapper.builder(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxStringLength(RelayPort.MAX_TEXT_BYTES)
                          .build())
                  .build())
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private FiredEventCodec() {}

  /**
   * One fired event: the name triggers listen for, and the caller's data.
   *
   * <p>{@code data} is the real {@link EventPayload} rather than a loose string, so the notice's
   * validation is the family's own validation and a decoded notice can be handed straight to {@code
   * FiringStore.arrive}. Only the two manual families are admitted: a wake or a scheduled payload
   * reaching this codec would mean something other than {@code event.fire} had published here, and
   * {@code EventPayloadCodec.manual} already refuses to manufacture one from a caller's object.
   *
   * <p>Nothing else is carried. The publication's position is the event's sequence and its {@code
   * occurredAt} is its recorded time, so neither is duplicated here, and the firing identities the
   * arrival allocates are the subscriber's own and not part of what was published.
   */
  record Notice(String event, EventPayload data) {
    Notice {
      event = EventPayload.identity(event, "event");
      if (!(data instanceof EventPayload.Empty) && !(data instanceof EventPayload.Text))
        throw new IllegalArgumentException("a fired event carries only the manual DTO families");
    }
  }

  /** The notice as the general message's text. The returned value is never blank. */
  static String write(Notice notice) {
    java.util.Objects.requireNonNull(notice, "notice");
    // Built field by field rather than reflected from the record, so the wire shape is stated here
    // and a future field on the record cannot silently widen it.
    ObjectNode value = JSON.createObjectNode();
    value.put(SCHEMA, VERSION);
    value.put("event", notice.event());
    value.put("text", notice.data() instanceof EventPayload.Text text ? text.text() : null);
    try {
      return JSON.writeValueAsString(value);
    } catch (IOException broken) {
      throw new IllegalStateException("fired event notice could not be encoded", broken);
    }
  }

  /**
   * The notice a general message carries.
   *
   * @throws IllegalArgumentException if the text is not a notice of this envelope version, carries
   *     a field outside the allowlist, omits one, or fails the notice's own validation. The message
   *     is fixed rather than derived: a parser diagnostic can quote the payload it rejected, and a
   *     publication's content is not disclosed to whoever provoked the failure.
   */
  static Notice read(String source) {
    try {
      JsonNode value = JSON.readTree(RelayPort.text(source));
      if (value == null || !value.isObject() || value.size() != FIELDS.size())
        throw new IllegalArgumentException("invalid fired event fields");
      value
          .fieldNames()
          .forEachRemaining(
              name -> {
                if (!FIELDS.contains(name))
                  throw new IllegalArgumentException("unsupported fired event field");
              });
      if (!VERSION.equals(text(value, SCHEMA)))
        throw new IllegalArgumentException("unsupported fired event version");
      JsonNode carried = value.get("text");
      if (carried == null) throw new IllegalArgumentException("fired event field is absent");
      return new Notice(
          text(value, "event"),
          carried.isNull() ? new EventPayload.Empty() : new EventPayload.Text(text(value, "text")));
    } catch (IOException | IllegalArgumentException invalid) {
      throw new IllegalArgumentException("invalid fired event notice");
    }
  }

  private static String text(JsonNode value, String name) {
    JsonNode field = value.get(name);
    if (field == null || !field.isTextual())
      throw new IllegalArgumentException("fired event field requires text");
    return field.textValue();
  }
}
