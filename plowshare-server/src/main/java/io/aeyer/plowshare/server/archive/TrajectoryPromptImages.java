package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.blobs.BinaryBlobStorage;
import io.aeyer.plowshare.server.blobs.BlobUnavailableException;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.PromptImages;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Reuses immutable blob reads. References, not pixels, remain in the runtime and durable history.
 */
public final class TrajectoryPromptImages implements PromptImages {
  private final TrajectoryArtifacts artifacts;
  private final BinaryBlobStorage blobs;

  public TrajectoryPromptImages(TrajectoryArtifacts artifacts, BinaryBlobStorage blobs) {
    this.artifacts = Objects.requireNonNull(artifacts);
    this.blobs = Objects.requireNonNull(blobs);
  }

  @Override
  public ChatRequest resolve(ChatRequest request) {
    if (!PromptImages.hasStored(request)) return request;
    request.authorization().requireRequest(request);
    if (request.attribution().status() == UsageAttribution.Status.SYSTEM
        && (request.authorization().binding() == null
            || !Objects.equals(
                request.attribution().conversations().id(),
                request.authorization().binding().conversation())))
      throw new IllegalArgumentException(
          "System image input requires its bound conversation authority");
    var addresses =
        request.messages().stream()
            .flatMap(message -> message.parts().stream())
            .filter(part -> part instanceof Content.StoredImage)
            .map(part -> (Content.StoredImage) part)
            .toList();
    if (addresses.size() > 16)
      throw new IllegalArgumentException("Image prompt exceeds 16 retained observations");
    var retained = artifacts.read(addresses.stream().distinct().toList(), request.attribution());
    long bytes = 0;
    for (var image : retained) bytes = Math.addExact(bytes, image.byteLength());
    // A bounded transient prompt, not an unbounded replay allocation. Repeated slots reuse bytes.
    if (bytes > 20L * 1024 * 1024)
      throw new IllegalArgumentException("Image prompt exceeds 20 MiB");
    var materialized = new LinkedHashMap<Content.StoredImage, Content.Image>();
    for (var image : retained) {
      byte[] data = blobs.read(image.key(), image.claim());
      if (data.length != image.byteLength()) throw new BlobUnavailableException();
      materialized.put(
          image.address(),
          new Content.Image(
              image.address().uid(),
              "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(data)));
    }
    List<ChatMessage> messages = new ArrayList<>();
    for (var message : request.messages()) {
      List<Content> parts =
          message.parts().stream()
              .map(
                  part -> {
                    if (part instanceof Content.StoredImage image) {
                      var held = materialized.get(image);
                      if (held == null) throw new BlobUnavailableException();
                      return (Content) held;
                    }
                    return part;
                  })
              .toList();
      messages.add(
          new ChatMessage(message.role(), parts, message.toolCalls(), message.toolCallId()));
    }
    return request
        .withMessages(messages)
        .withMediaRequirements(
            request.mediaRequirements() == null
                ? io.aeyer.plowshare.server.llm.MediaRequirements.TEXT
                : request.mediaRequirements());
  }
}
