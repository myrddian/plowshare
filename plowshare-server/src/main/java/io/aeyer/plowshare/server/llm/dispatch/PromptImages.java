package io.aeyer.plowshare.server.llm.dispatch;

/**
 * Materializes authorized entry references immediately before inference, outside SQL transactions.
 */
@FunctionalInterface
public interface PromptImages {
  ChatRequest resolve(ChatRequest request);

  /** Fixtures/servers without storage never silently turn references into empty native input. */
  PromptImages NONE =
      request -> {
        if (hasStored(request))
          throw new IllegalStateException("Trajectory image resolution is unavailable");
        return request;
      };

  static boolean hasStored(ChatRequest request) {
    return request.messages().stream()
        .flatMap(message -> message.parts().stream())
        .anyMatch(part -> part instanceof Content.StoredImage);
  }
}
