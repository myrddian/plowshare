package io.aeyer.plowshare.server.llm;

import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact native input requirements and required/permitted output formats for one inference call.
 *
 * <p>This is eligibility, not artifact authorization, context admission or a wire output-mode
 * parameter. Actual prompt images are added after filtering so callers cannot omit them. Successful
 * matching requires one output combination from each declaration, not their union.
 */
public record MediaRequirements(
    Set<String> inputs, Set<String> requiredOutputs, Set<String> permittedOutputs) {
  public static final MediaRequirements TEXT =
      new MediaRequirements(Set.of("text/plain"), Set.of("text/plain"), Set.of("text/plain"));

  public MediaRequirements {
    inputs = formats(inputs);
    requiredOutputs = formats(requiredOutputs);
    permittedOutputs = formats(permittedOutputs);
    if (!permittedOutputs.containsAll(requiredOutputs)) {
      throw new IllegalArgumentException("required media outputs exceed permitted outputs");
    }
  }

  /** Add every native image in the complete effective prompt, retaining explicit requirements. */
  public MediaRequirements including(List<ChatMessage> messages) {
    Set<String> all = new LinkedHashSet<>(inputs);
    for (ChatMessage message : messages) {
      for (Content part : message.parts()) {
        all.add(
            switch (part) {
              case Content.Image image -> imageType(image);
              case Content.StoredImage image ->
                  throw new IllegalArgumentException(
                      "Retained image format must be resolved before routing");
              case Content.Text text -> "text/plain";
            });
      }
    }
    return new MediaRequirements(all, requiredOutputs, permittedOutputs);
  }

  private static Set<String> formats(Set<String> values) {
    // Reuse the profile's normalization, duplicate and bounded exact-MIME contract.
    return new ModelMediaCapabilities.OutputMode(values).formats();
  }

  /** Require one common output combination, never a union of independent mode declarations. */
  public boolean matches(ModelMediaCapabilities model, ModelMediaCapabilities transport) {
    return model.inputs().containsAll(inputs)
        && transport.inputs().containsAll(inputs)
        && model.outputs().values().stream()
            .anyMatch(
                mode ->
                    mode.formats().containsAll(requiredOutputs)
                        && permittedOutputs.containsAll(mode.formats())
                        && transport.outputs().values().stream()
                            .anyMatch(wire -> wire.formats().equals(mode.formats())));
  }

  private static String imageType(Content.Image image) {
    String uri = image.dataUri();
    int comma = uri.indexOf(',');
    if (comma < 0 || comma > 64 || !uri.substring(0, comma).endsWith(";base64")) {
      throw new IllegalArgumentException("native image routing requires a base64 image data URI");
    }
    String declared = ModelMediaCapabilities.mediaType(uri.substring(5, comma - 7));
    // Only twelve signature bytes are needed by the existing ImageFormat detector. Do not decode
    // and allocate another complete image while choosing a pool. Full validation is admission's
    // job.
    ImageFormat detected;
    try {
      detected =
          ImageFormat.of(
              Base64.getDecoder()
                  .decode(uri.substring(comma + 1, Math.min(uri.length(), comma + 17))));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("invalid native image encoding");
    }
    if (detected == null || !detected.mediaType().equals(declared)) {
      throw new IllegalArgumentException("native image MIME type does not match its signature");
    }
    return detected.mediaType();
  }
}
