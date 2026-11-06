package io.aeyer.plowshare.server.security;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Preserves roles, images, tool identities and ownership; only approved text replaces text. */
public final class FilteredChat implements ChatFiltering {
  private final TextFiltering local;
  private final MessageReview review;

  public FilteredChat(TextFiltering local, MessageReview review) {
    this.local = Objects.requireNonNull(local);
    this.review = Objects.requireNonNull(review);
  }

  @Override
  public ChatRequest input(ChatRequest request, BooleanSupplier abandoned) {
    return inspect(request, abandoned, false);
  }

  @Override
  public ChatRequest historyInput(ChatRequest request, BooleanSupplier abandoned) {
    if (!request.tools().isEmpty())
      throw new IllegalArgumentException("Historical summary requests cannot carry tools");
    return inspect(request, abandoned, true);
  }

  private ChatRequest inspect(ChatRequest request, BooleanSupplier abandoned, boolean history) {
    var messages =
        request.messages().stream()
            .map(
                m -> {
                  boolean untrusted =
                      m.role() == ChatMessage.Role.USER || m.role() == ChatMessage.Role.TOOL;
                  // Compaction flattens historical roles into USER text. Do not reinterpret quoted
                  // past instructions as a new attack on the folder. They retain their original
                  // message role and remain subject to disclosure policy and external review.
                  boolean injection = untrusted && !history;
                  var parts =
                      m.parts().stream()
                          .map(
                              p -> {
                                if (!(p instanceof Content.Text text)) return p;
                                String approved = local.inspect(text.text(), injection, true);
                                if (untrusted && !approved.isBlank())
                                  approved =
                                      review.review(
                                          request.attribution(),
                                          m.role().wireName(),
                                          approved,
                                          abandoned);
                                // A reviewer must not introduce content that the local policy would
                                // refuse.
                                return (Content)
                                    new Content.Text(local.inspect(approved, injection, true));
                              })
                          .toList();
                  var calls =
                      m.toolCalls().stream()
                          .map(
                              c -> {
                                local.inspect(c.arguments(), false, false);
                                return c;
                              })
                          .toList();
                  return new ChatMessage(m.role(), parts, calls, m.toolCallId());
                })
            .toList();
    return request.withMessages(messages);
  }

  @Override
  public Completion output(Completion completion) {
    String text = local.inspect(completion.content(), false, true);
    for (ToolCall call : completion.toolCalls()) local.inspect(call.arguments(), false, false);
    return new Completion(
        text,
        completion.finishReason(),
        completion.usage(),
        completion.toolCalls(),
        completion.servedBy(),
        completion.capture());
  }

  @Override
  public boolean bufferOutput() {
    return local.filtersOutput();
  }
}
