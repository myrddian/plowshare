package io.aeyer.plowshare.server.information;

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
import java.util.UUID;

/**
 * The information-event notice as it travels inside Relay's general message.
 *
 * <p>Relay is a publish/subscribe substrate: it carries a contract and stores a publication's
 * content opaquely. A payload family per consumer would make every new consumer a change to the
 * broker, so the structure an information subscriber needs is encoded here, by the publishing
 * subsystem, written into {@code RelayPayload.Text}, and decoded here on the read path. This is the
 * same arrangement {@code RelayToolCodec} uses for hosted tool envelopes.
 *
 * <p>The codec owns the wire shape and nothing else. It does not decide who may read a notice and
 * it does not redact: {@code owner} is carried so that {@link RelayInformationEvents} can reapply
 * the owner-only {@code detail} redaction in memory, and applying it remains that reader's job.
 *
 * <p>Validation is the notice's own invariant and runs in the record's constructor, so a notice
 * that was decoded is a notice that was validated. Every field the catalogue publishes is required
 * to be present on the wire; {@code detail} and {@code documentSubtype} are written as JSON null
 * when absent rather than omitted, so a missing key is a corrupt payload rather than an absent
 * value.
 */
public final class InformationEventCodec {
  /**
   * The envelope version. It is a codec concern rather than a field of the notice, so it is written
   * and required here and never travels into {@link Notice}.
   */
  public static final String VERSION = "plowshare-information-event/1";

  private static final String SCHEMA = "schema";

  /** The complete wire field allowlist: the envelope version plus the notice's eight fields. */
  private static final Set<String> FIELDS =
      Set.of(
          SCHEMA,
          "revision",
          "generation",
          "stage",
          "action",
          "detail",
          "documentType",
          "documentSubtype",
          "owner");

  // A notice is small, but detail is free-form text bounded only by Relay's text rule, so the
  // parser's string allowance is that same rule rather than Jackson's much smaller default.
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

  private InformationEventCodec() {}

  /**
   * One information event, carrying only what a subscriber needs in order to invalidate what it
   * holds and render the change. The catalogue retains the revision itself.
   *
   * <p>{@code owner} is the home resource's author and exists for one reason: {@code detail} was
   * only ever shown to that account, and the publication is read back by every subscriber on the
   * topic. Carrying the owner in the notice lets the read path reapply that redaction in memory, so
   * no reader gains detail it could not see before and no per-event query is needed to decide it.
   *
   * <p>{@code detail} and {@code documentSubtype} are absent rather than empty when unset. The
   * event's position in the topic is its sequence and the publication's {@code occurredAt} is its
   * recorded time, so neither is duplicated here.
   */
  public record Notice(
      String revision,
      long generation,
      String stage,
      String action,
      String detail,
      String documentType,
      String documentSubtype,
      String owner) {
    public Notice {
      revision = UUID.fromString(identity(revision)).toString();
      if (generation < 1) throw new IllegalArgumentException("event generation must be positive");
      stage = identity(stage);
      action = identity(action);
      documentType = identity(documentType);
      if (documentSubtype != null) documentSubtype = identity(documentSubtype);
      owner = identity(owner);
      // Detail is free-form diagnostic text a stage wrote, so it is bounded by the broker's text
      // rule rather than the identity rule, and an empty one is carried as absent.
      if (detail != null && detail.isBlank()) detail = null;
      if (detail != null) detail = RelayPort.text(detail);
    }
  }

  /** The notice as the general message's text. The returned value is never blank. */
  public static String write(Notice notice) {
    java.util.Objects.requireNonNull(notice, "notice");
    // Built field by field rather than reflected from the record, so the wire shape is stated here
    // and a future field on the record cannot silently widen it.
    ObjectNode value = JSON.createObjectNode();
    value.put(SCHEMA, VERSION);
    value.put("revision", notice.revision());
    value.put("generation", notice.generation());
    value.put("stage", notice.stage());
    value.put("action", notice.action());
    value.put("detail", notice.detail());
    value.put("documentType", notice.documentType());
    value.put("documentSubtype", notice.documentSubtype());
    value.put("owner", notice.owner());
    try {
      return JSON.writeValueAsString(value);
    } catch (IOException broken) {
      throw new IllegalStateException("information event notice could not be encoded", broken);
    }
  }

  /**
   * The notice a general message carries.
   *
   * @throws IllegalArgumentException if the text is not a notice of this envelope version, carries
   *     a field outside the allowlist, omits one, or fails the notice's own validation. The message
   *     is fixed: a parser diagnostic can quote the payload, and a notice's detail is not disclosed
   *     to whoever provoked the failure.
   */
  public static Notice read(String source) {
    try {
      JsonNode value = JSON.readTree(RelayPort.text(source));
      if (value == null || !value.isObject() || value.size() != FIELDS.size())
        throw new IllegalArgumentException("invalid information event fields");
      value
          .fieldNames()
          .forEachRemaining(
              name -> {
                if (!FIELDS.contains(name))
                  throw new IllegalArgumentException("unsupported information event field");
              });
      if (!VERSION.equals(text(value, SCHEMA)))
        throw new IllegalArgumentException("unsupported information event version");
      return new Notice(
          text(value, "revision"),
          integer(value, "generation"),
          text(value, "stage"),
          text(value, "action"),
          optionalText(value, "detail"),
          text(value, "documentType"),
          optionalText(value, "documentSubtype"),
          text(value, "owner"));
    } catch (IOException | IllegalArgumentException invalid) {
      throw new IllegalArgumentException("invalid information event notice");
    }
  }

  /** A persisted whole number; a float, a string or an absent field is a corrupt notice. */
  private static long integer(JsonNode value, String name) {
    JsonNode field = value.get(name);
    if (field == null || !field.isIntegralNumber() || !field.canConvertToLong())
      throw new IllegalArgumentException("information event field requires a whole number");
    return field.longValue();
  }

  private static String optionalText(JsonNode value, String name) {
    JsonNode field = value.get(name);
    if (field == null) throw new IllegalArgumentException("information event field is absent");
    return field.isNull() ? null : text(value, name);
  }

  private static String text(JsonNode value, String name) {
    JsonNode field = value.get(name);
    if (field == null || !field.isTextual())
      throw new IllegalArgumentException("information event field requires text");
    return field.textValue();
  }

  /**
   * Preserves the 1,024-character event identity contract these fields have always had: bounded,
   * stripped, and free of control characters and line/paragraph separators.
   */
  private static String identity(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException(
          "information event identity must be bounded without controls or padding");
    return value;
  }
}
