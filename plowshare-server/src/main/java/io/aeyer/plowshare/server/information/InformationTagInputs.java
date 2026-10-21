package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Strict tag-control transport boundary; selection is already resolved from authenticated context.
 */
public final class InformationTagInputs {
  private InformationTagInputs() {}

  public record Replacement(UUID revision, UUID request, List<String> tags, long expectedVersion) {
    public Replacement {
      java.util.Objects.requireNonNull(revision);
      java.util.Objects.requireNonNull(request);
      tags = InformationFacets.tags(tags);
      if (expectedVersion < 0 || expectedVersion > 9007199254740991L)
        throw new CallerFault("expectedTagsVersion must be a nonnegative safe integer");
    }
  }

  public static Replacement replacement(Map<String, Object> fields) {
    if (!Set.of("scope", "corpus", "view", "revision", "requestId", "tags", "expectedTagsVersion")
        .containsAll(fields.keySet())) throw new CallerFault("Unsupported tag replacement field");
    Object version = fields.get("expectedTagsVersion");
    // Native SDK number fields can serialize integral JSON numbers with a decimal point.
    // Accept only exactly representable safe integers, never string/boolean coercion or fractions.
    long expected;
    if (version instanceof Byte
        || version instanceof Short
        || version instanceof Integer
        || version instanceof Long) expected = ((Number) version).longValue();
    else if (version instanceof Double number
        && Double.isFinite(number)
        && number >= 0
        && number <= 9007199254740991L
        && number == Math.rint(number)) expected = number.longValue();
    else throw new CallerFault("expectedTagsVersion must be a nonnegative safe integer");
    return new Replacement(
        identifier(fields.get("revision")),
        identifier(fields.get("requestId")),
        InformationFacets.tags(fields.get("tags")),
        expected);
  }

  private static UUID identifier(Object value) {
    if (!(value instanceof String text)
        || !text.matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"))
      throw new CallerFault("Tag replacement requires exact revision and request UUIDs");
    return UUID.fromString(text);
  }
}
