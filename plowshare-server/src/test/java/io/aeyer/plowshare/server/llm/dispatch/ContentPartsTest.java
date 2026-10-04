package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a message is now that it is parts, and the two questions it answers differently.
 *
 * <p>{@code parts()} is what the model is shown and {@code content()} is what this server reads.
 * Everything here is about keeping those two apart, because the day they are one thing is the day a
 * base64 payload turns up in a transcript entry.
 */
class ContentPartsTest {

  private static final String UID = "img_" + "a".repeat(32);
  private static final Content.Image RED = new Content.Image(UID, "data:image/png;base64,iVBORw0K");

  /**
   * The whole compatibility claim in one assertion: a message built the way every caller in this
   * repository builds one is one text part, and reads back as the string it was given.
   */
  @Test
  void a_text_message_is_one_part_and_reads_back_as_the_string_it_was_given() {
    ChatMessage said = ChatMessage.user("hello");

    assertEquals(List.of(new Content.Text("hello")), said.parts());
    assertEquals("hello", said.content());
    assertFalse(said.carriesAnImage());
  }

  /**
   * An image contributes nothing to {@code content()}, and that is the decision this file exists to
   * protect.
   *
   * <p>The alternative is a stand-in sentence — "[image img_…]" — and it is worse in a way that is
   * easy to miss: {@code Compaction} decides a fold from character counts, {@code Transcript}
   * records what was said, and both would then be counting and recording words the model was never
   * sent.
   */
  @Test
  void an_image_adds_nothing_to_what_the_harness_reads() {
    ChatMessage said = ChatMessage.user("what is this", List.of(RED));

    assertEquals(
        "what is this",
        said.content(),
        "the words and only the words: a stand-in sentence here would be text no"
            + " model was ever shown, which every character count would count");
    assertTrue(said.carriesAnImage());
    assertEquals(2, said.parts().size());
    assertEquals("", RED.text());
  }

  /**
   * And a UID is nameable even though the bytes are not quotable, which is what lets a log line or
   * a refusal refer to an image at all.
   */
  @Test
  void an_image_part_names_its_uid_and_never_prints_its_payload() {
    assertEquals("Image[" + UID + "]", RED.toString());
    assertFalse(
        RED.toString().contains("iVBORw0K"),
        "a base64 payload in a log is a log nobody can read and a picture stored"
            + " a second time nobody meant to store");
  }

  /**
   * An image belongs to a user turn and to no other role.
   *
   * <p>Each of the three is a different impossibility and each would be sent by the transport and
   * refused by the endpoint as a whole request: a tool message with one is a tool returning bytes,
   * an assistant message with one is a model emitting a picture, and no chat template here has a
   * meaning for a system message with one.
   */
  @Test
  void only_a_user_turn_may_carry_a_picture() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatMessage(ChatMessage.Role.SYSTEM, List.of(RED), List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(RED), List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatMessage(ChatMessage.Role.TOOL, List.of(RED), List.of(), "c1"));
  }

  /**
   * A picture is something in the message: the whole instruction may be in the system prompt, and
   * "here" is a legitimate turn.
   */
  @Test
  void a_user_turn_that_is_only_a_picture_is_a_message() {
    ChatMessage said = new ChatMessage(ChatMessage.Role.USER, List.of(RED), List.of(), null);

    assertEquals("", said.content());
    assertTrue(said.carriesAnImage());
  }

  @Test
  void a_message_with_nothing_at_all_in_it_is_still_not_a_message() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatMessage(ChatMessage.Role.USER, List.<Content>of(), List.of(), null));
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.user(""));
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.system("  "));
  }

  /**
   * An empty text part is dropped and a blank one is kept.
   *
   * <p>{@code RequestTest} pins that an assistant turn of two spaces keeps its two spaces, because
   * what a model said is what a model said. Empty is a different thing — it is how a turn that is
   * entirely tool calls is spelled — and a {@code Text("")} in the list would make a turn's parts
   * and its {@code content()} disagree about whether it said anything.
   */
  @Test
  void an_empty_text_part_is_not_a_part_and_a_blank_one_is() {
    ChatMessage calling =
        ChatMessage.assistant("", List.of(new ToolCall("c1", "memory_read", "{}")));

    assertEquals(List.of(), calling.parts());
    assertEquals("", calling.content());
    assertEquals(List.of(new Content.Text("  ")), ChatMessage.assistant("  ", List.of()).parts());
  }

  /**
   * The overload says a picture is being attached. One that quietly attached none would be a call
   * site that looks like it showed the model something.
   */
  @Test
  void the_image_overload_refuses_to_attach_nothing() {
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.user("what is this", List.of()));
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.user("what is this", null));
  }

  /**
   * The containment control, stated as a type rather than as a rule.
   *
   * <p>There is no value an image part can hold that a transport could be asked to resolve, so
   * there is nothing anywhere else that has to remember not to resolve it.
   */
  @Test
  void an_image_part_cannot_hold_a_url() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Content.Image(UID, "https://example.invalid/red.png"));

    assertTrue(refused.getMessage().contains("data:"), refused.getMessage());
    assertThrows(
        IllegalArgumentException.class, () -> new Content.Image(UID, "file:///etc/passwd"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Content.Image(UID, "http://169.254.169.254/latest/meta-data/"));
  }

  /**
   * Two messages built the two ways are one message, so nothing downstream can tell which door a
   * caller came through.
   */
  @Test
  void the_string_door_and_the_parts_door_build_the_same_message() {
    assertEquals(
        new ChatMessage(
            ChatMessage.Role.USER, List.<Content>of(new Content.Text("hello")), List.of(), null),
        ChatMessage.user("hello"));
  }
}
