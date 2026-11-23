package io.aeyer.plowshare.server.security;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.files.FileReadWindow;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.security.LocalTextFilter.InjectionRefusal;
import java.time.Instant;
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
                                String approved = inspectText(m, text.text(), injection);
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
                                    new Content.Text(inspectText(m, approved, injection));
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
                  if (m.losslessToolText()) {
                    var released = (Content.Text) parts.getFirst();
                    return released.text().equals(m.content())
                        ? m
                        : ChatMessage.tool(m.toolCallId(), FileReadWindow.WITHHELD);
                  }
                  return new ChatMessage(m.role(), parts, calls, m.toolCallId());
                })
            .toList();
    return request.withMessages(messages);
  }

  private String inspectText(ChatMessage message, String text, boolean injection) {
    try {
      return local.inspect(text, injection, true);
    } catch (InjectionRefusal refused) {
      var receipt = message.webRelease();
      if (receipt == null) throw refused;
      if (!receipt.covers(message.toolCallId(), text, refused.rule(), Instant.now()))
        return "Web evidence was withheld because its reviewed release is no longer valid.";
      // Only the exact reviewed rule is exempt. Disclosure and other injection rules still apply.
      return local.inspectReleased(text, refused.rule());
    }
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
