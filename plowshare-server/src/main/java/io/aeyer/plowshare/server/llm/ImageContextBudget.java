package io.aeyer.plowshare.server.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.nio.charset.StandardCharsets;

/**
 * Local admission reservations, not measured model costs or a tokenizer upper bound.
 *
 * <p>Hard byte/occurrence limits bound the request. The configured text meter, with estimate
 * headroom, plus framing and per-image reservations decides whether to submit it within the known
 * context/output budget. The backend still enforces its actual token window. Operators can tune
 * these reservations in the existing model profile without installing a count service.
 */
public record ImageContextBudget(
    int tokensPerImage, int framingTokens, int maxImages, int maxPromptBytes) {
  /** Operational reservations, deliberately independent of any model's image encoder formula. */
  public static final ImageContextBudget DEFAULT =
      new ImageContextBudget(4096, 1024, 16, 256 * 1024);

  private static final ObjectMapper WIRE = new ObjectMapper();

  public ImageContextBudget {
    if (tokensPerImage < 1
        || tokensPerImage > 1_000_000
        || framingTokens < 0
        || framingTokens > 1_000_000
        || maxImages < 1
        || maxImages > 16
        || maxPromptBytes < 1
        || maxPromptBytes > 1024 * 1024)
      throw new IllegalArgumentException("Invalid image context budget");
  }

  /** Input reservation only; the dispatcher separately checks the explicit output reserve. */
  public long reserve(ChatRequest request, Tokenizer tokenizer) {
    Meter meter = new Meter(tokenizer, maxPromptBytes);
    int images = 0;
    long framing = framingTokens;
    for (var message : request.messages()) {
      // Role/turn delimiters are backend-specific. Reserve them rather than claiming to render
      // a native chat template. Metadata and tool bodies still contribute their actual text.
      framing = Math.addExact(framing, 32);
      meter.add(message.content());
      meter.add(message.role().wireName());
      if (message.toolCallId() != null) meter.add(message.toolCallId());
      for (var call : message.toolCalls()) {
        meter.add(call.id());
        meter.add(call.name());
        meter.add(call.arguments());
        framing = Math.addExact(framing, 32);
      }
      for (var part : message.parts()) {
        if (part instanceof Content.StoredImage)
          throw new IllegalArgumentException("Image budget needs resolved image references");
        if (part instanceof Content.Image) images++;
      }
    }
    if (images < 1 || images > maxImages)
      throw new IllegalArgumentException("Image observation exceeds its image occurrence budget");
    try {
      // This is the same typed declaration used by the transport and context inspector. Never
      // meter image data URIs or omit offered tools/response schemas from the text reservation.
      meter.add(WIRE.writeValueAsString(ToolSchema.asDeclared(request.tools())));
      if (request.sampling().responseFormat().isPresent())
        meter.add(
            WIRE.writeValueAsString(
                request.sampling().responseFormat().orElseThrow().asDeclared()));
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Cannot project the image context budget", invalid);
    }
    if (request.toolChoice() != null) meter.add(request.toolChoice().wire());
    request.sampling().reasoningEffort().ifPresent(effort -> meter.add(effort.wire()));
    return Math.addExact(
        Math.addExact(meter.tokens, framing), Math.multiplyExact((long) images, tokensPerImage));
  }

  /** Encodes the actual policy in provenance without introducing a second model registry. */
  public String revision() {
    return "image-budget-v1-"
        + tokensPerImage
        + "-"
        + framingTokens
        + "-"
        + maxImages
        + "-"
        + maxPromptBytes;
  }

  private static final class Meter {
    private final Tokenizer tokenizer;
    private long remainingBytes;
    private long tokens;

    private Meter(Tokenizer tokenizer, int bytes) {
      this.tokenizer = tokenizer;
      remainingBytes = bytes;
    }

    private void add(String text) {
      if (text.length() > remainingBytes)
        throw new IllegalArgumentException(
            "Image observation exceeds its non-image prompt byte budget");
      remainingBytes -= text.getBytes(StandardCharsets.UTF_8).length;
      if (remainingBytes < 0)
        throw new IllegalArgumentException(
            "Image observation exceeds its non-image prompt byte budget");
      TokenCount count = tokenizer.count(text);
      long contribution = count.tokens();
      // Reuse chat's existing one-third headroom convention. Even a measured component count
      // does not make the combined image/framing reservation a measured whole-prompt count.
      if (count.basis() == TokenCount.Basis.ESTIMATED) contribution = (contribution * 4 + 2) / 3;
      tokens = Math.addExact(tokens, contribution);
    }
  }
}
