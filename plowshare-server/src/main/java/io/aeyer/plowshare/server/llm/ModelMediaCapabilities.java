package io.aeyer.plowshare.server.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Operator-declared native formats of a model, independent of sampling and transport support.
 *
 * <p>These immutable declarations do not enable a serializer, authorize artifact access or supply
 * image token costs. Output modes describe combinations permitted in one response; they do not
 * guarantee that every listed format occurs. A caller's required output parts are a separate
 * contract. Separate text and image modes do not declare mixed output support.
 *
 * @param inputs exact normalized MIME types accepted as native input
 * @param outputs named output combinations, in declaration order
 */
public record ModelMediaCapabilities(Set<String> inputs, Map<String, OutputMode> outputs) {
  static final int MAX_FORMATS = 32;
  static final int MAX_OUTPUT_MODES = 32;

  private static final Pattern MEDIA_TYPE =
      Pattern.compile("[a-z0-9!#$%&'+.^_`|~-]+/[a-z0-9!#$%&'+.^_`|~-]+");
  private static final Pattern MODE_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

  /** Conservative native support for transports without a media serializer declaration. */
  public static final ModelMediaCapabilities TEXT_ONLY =
      new ModelMediaCapabilities(
          Set.of("text/plain"), Map.of("text", new OutputMode(Set.of("text/plain"))));

  public ModelMediaCapabilities {
    inputs = formats(inputs);
    if (outputs == null || outputs.isEmpty() || outputs.size() > MAX_OUTPUT_MODES) {
      throw new IllegalArgumentException("media outputs must contain 1..32 named modes");
    }
    Map<String, OutputMode> copy = new LinkedHashMap<>();
    outputs.forEach(
        (name, mode) -> {
          if (name == null || !MODE_NAME.matcher(name).matches() || mode == null) {
            throw new IllegalArgumentException("invalid media output mode name or value");
          }
          copy.put(name, mode);
        });
    outputs = Collections.unmodifiableMap(copy);
  }

  /** Formats permitted together in one native response; a mode may be image-only. */
  public record OutputMode(Set<String> formats) {
    public OutputMode {
      formats = ModelMediaCapabilities.formats(formats);
    }
  }

  /** Reject parameters, wildcards and non-MIME values instead of broadening a declaration. */
  static String mediaType(String value) {
    if (value == null || value.length() > 255) {
      throw new IllegalArgumentException("invalid exact media type");
    }
    String normalized = value.toLowerCase(Locale.ROOT);
    if (!MEDIA_TYPE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("invalid exact media type");
    }
    return normalized;
  }

  private static Set<String> formats(Set<String> declared) {
    if (declared == null || declared.isEmpty() || declared.size() > MAX_FORMATS) {
      throw new IllegalArgumentException("media formats must contain 1..32 exact MIME types");
    }
    Set<String> copy = new LinkedHashSet<>();
    for (String value : declared) {
      if (!copy.add(mediaType(value))) {
        throw new IllegalArgumentException("duplicate normalized media type");
      }
    }
    return Collections.unmodifiableSet(copy);
  }
}
