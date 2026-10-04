package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Redemption;
import io.aeyer.plowshare.server.archive.StoredResults;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two tools a conversation's own stored results are reached through: {@code result_read}, which
 * turns one reference back into the result it stands for, and {@code result_list}, which names the
 * ones whose reference lines a fold has taken away.
 *
 * <h2>What this file asserts and what no file can</h2>
 *
 * <p>Everything here is about the mechanism: a handle redeems its own conversation's result
 * verbatim, a handle from anywhere else is refused, a listing carries what a reference line carries
 * and is bounded like a windowed read, and every mistake a model can make with an argument comes
 * back as prose rather than as an exception. <b>What it cannot assert is the thing the whole change
 * turns on</b> — that a model reads a reference and decides to call this. This repository has a
 * measured scar there: removing one sentence from the interlocutor prompt stopped an agent calling
 * {@code file_grep} at all, twice, and 1 892 tests passed either way. That is settled by a live run
 * and by nothing in here.
 */
class ResultToolsTest {

  private static final Home PAYMENTS = Home.of("payments");

  /**
   * What a handle is worth: exactly the bytes the tool returned, with nothing added and nothing
   * trimmed. A redemption that reformatted its answer would make the reference's promise false.
   */
  @Test
  void a_handle_answers_with_the_stored_result_verbatim() {
    UUID handle = UUID.randomUUID();
    AgentTool tool = new ResultTools.Read(holding(handle, "line one\nline two\n"));

    assertEquals("line one\nline two\n", tool.run("{\"handle\": \"" + handle + "\"}", PAYMENTS));
  }

  /**
   * A handle this conversation does not hold is a tool result and not an ending.
   *
   * <p>The scoping check and the model's own typo are the same fact from this side, deliberately: a
   * refusal that distinguished them would tell a model whether a handle exists somewhere it may not
   * read, which is the one thing an authorisation check must not leak. {@code EntryStore.redeem}
   * answers empty for both.
   */
  @Test
  void a_handle_this_conversation_does_not_hold_is_a_tool_result() {
    AgentTool tool = new ResultTools.Read(holding(UUID.randomUUID(), "mine"));

    String answer = tool.run("{\"handle\": \"" + UUID.randomUUID() + "\"}", PAYMENTS);

    assertFalse(answer.isBlank(), "a blank tool result reads as a tool that does not work");
    assertTrue(answer.contains(ResultTools.READ_NAME), answer);
  }

  /**
   * Every way a model can get the argument wrong, and none of them ends the run. See {@code
   * AgentTool}: a caller's mistake is a result it can correct on its next turn, and the turn that
   * produced it was already paid for.
   */
  @Test
  void a_bad_handle_is_a_tool_result_and_not_an_exception() {
    AgentTool tool = new ResultTools.Read(holding(UUID.randomUUID(), "mine"));

    for (String arguments :
        List.of(
            "not json at all",
            "[]",
            "{}",
            "{\"handle\": \"\"}",
            "{\"handle\": 7}",
            "{\"handle\": \"the one from turn 3\"}")) {
      String answer = tool.run(arguments, PAYMENTS);
      assertFalse(answer.isBlank(), "blank answer for " + arguments);
    }
  }

  /**
   * A handle that is not a UUID says so, rather than being reported as a handle that does not
   * exist.
   *
   * <p>The two are different mistakes and the model fixes them differently. "There is no such
   * result" sends a model looking for another handle; "that is not a handle" sends it back to the
   * reference line to copy one properly.
   */
  @Test
  void something_that_is_not_a_handle_at_all_says_which_mistake_it_was() {
    AgentTool tool = new ResultTools.Read(holding(UUID.randomUUID(), "mine"));

    String answer = tool.run("{\"handle\": \"turn-3-result\"}", PAYMENTS);

    assertTrue(
        answer.contains("turn-3-result"),
        "a model cannot correct a mistake the answer does not quote: " + answer);
  }

  /**
   * The runtime's own bugs are not the model's, and are not softened into prose. {@code AgentTool}
   * draws that line and every tool holds it.
   */
  @Test
  void a_null_argument_is_the_runtimes_bug_and_not_the_models() {
    AgentTool tool = new ResultTools.Read(holding(UUID.randomUUID(), "mine"));

    assertThrows(NullPointerException.class, () -> tool.run(null, PAYMENTS));
    assertThrows(NullPointerException.class, () -> tool.run("{}", null));
  }

  /**
   * A run in no conversation has nothing to redeem, and says so rather than failing.
   *
   * <p>{@link Transcript#NONE} used to be every caller of {@code JobRuntime.run} but a turn. Since
   * {@code V17__conversation_origin.sql} it is {@code JobRuntime.schemasOfferedTo} — which builds a
   * run's tools to read their schemas and runs none of them — and fixtures like this one. What the
   * defaults on that interface still buy is that a tool built over it answers rather than failing,
   * which is what makes "this run is in no conversation" an ordinary state rather than a wiring
   * hole.
   */
  @Test
  void a_run_in_no_conversation_has_nothing_to_redeem() {
    AgentTool tool = new ResultTools.Read(Transcript.NONE);

    String answer = tool.run("{\"handle\": \"" + UUID.randomUUID() + "\"}", PAYMENTS);

    assertFalse(answer.isBlank(), "a blank tool result reads as a tool that does not work");
  }

  /**
   * The tool the model is shown is the one the name constant spells, and it takes the argument the
   * reference line hands it.
   *
   * <p>A weak assertion on purpose. The wording is the load-bearing part and no suite can see
   * whether it earns a call. That the reference line and this description name <em>one</em> tool is
   * held one file over, in {@code CompactionTest}, where both texts are in scope.
   */
  @Test
  void the_tool_the_model_is_shown_is_the_one_the_reference_names() {
    AgentTool tool = new ResultTools.Read(Transcript.NONE);

    assertEquals(ResultTools.READ_NAME, tool.schema().name());
    assertTrue(tool.schema().description().contains("handle"), tool.schema().description());
    assertNotNull(tool.schema().parameters().get("properties"));
  }

  /**
   * The instant a payload went, chosen rather than observed: an assertion about a date read off
   * {@code Instant.now()} is an assertion about the day the suite happens to run.
   */
  private static final Instant EJECTED_AT = Instant.parse("2026-09-04T11:30:00Z");

  // --- an ejected payload ---------------------------------------------------------------

  /**
   * The one rule the retention design puts on this tool by name: <b>{@code result_read} must not
   * answer not-found for an ejected payload.</b>
   *
   * <p>A model told "there is nothing at that address" concludes it invented the handle and stops
   * looking; the truth is that the result was here, was read, and has been written somewhere a
   * person can fetch it from. The two call for opposite next steps, so the answers have to be
   * different — and the assertion here is that they are, in a way a reader of the two sentences can
   * see.
   */
  @Test
  void an_ejected_payload_is_not_answered_as_a_handle_that_does_not_exist() {
    UUID handle = UUID.randomUUID();
    AgentTool tool =
        new ResultTools.Read(ejected(handle, EJECTED_AT, "exports/cnv_1/payloads/cnv_1/0007.txt"));

    String answer = tool.run("{\"handle\": \"" + handle + "\"}", PAYMENTS);

    assertTrue(answer.contains("ejected"), answer);
    assertTrue(
        answer.contains("2026-09-04"),
        "a model telling a person which run to look for needs the date: " + answer);
    assertTrue(
        answer.contains("exports/cnv_1/payloads/cnv_1/0007.txt"),
        "the answer does not say where it went: " + answer);
    assertFalse(
        answer.contains("has no stored result under the handle"),
        "an ejected payload got the sentence for a handle that never resolved, which"
            + " tells a model to stop looking: "
            + answer);
  }

  /**
   * A deployment that keeps no export says so rather than naming an empty place, which would send
   * somebody looking for a file nobody wrote.
   */
  @Test
  void an_ejected_payload_with_no_export_says_there_is_none() {
    UUID handle = UUID.randomUUID();
    AgentTool tool = new ResultTools.Read(ejected(handle, EJECTED_AT, null));

    String answer = tool.run("{\"handle\": \"" + handle + "\"}", PAYMENTS);

    assertTrue(answer.contains("ejected"), answer);
    assertTrue(answer.contains("no export"), answer);
  }

  /**
   * A listing shows an ejected result <b>as ejected</b> rather than leaving it out.
   *
   * <p>Without the clause a model cannot tell <em>was never here</em> from <em>was here and
   * went</em>, and it corrects those two differently: the first sends it back to the line it was
   * copying from, the second tells it to run the tool again or ask the person for the export.
   * Dropping the row would also make the total disagree with the trajectory, which still holds
   * every one of these calls.
   */
  @Test
  void a_listing_shows_an_ejected_result_as_ejected_and_still_says_how_large_it_was() {
    UUID handle = UUID.randomUUID();
    AgentTool tool =
        new ResultTools.Listing(
            listing(
                new StoredResults(
                    List.of(new StoredResults.Result("file_read", 96000, EJECTED_AT, handle)), 1)));

    String answer = tool.run("{}", PAYMENTS);

    assertTrue(answer.contains("file_read"), answer);
    assertTrue(
        answer.contains("96000"),
        "an ejected result listed with no size says less about itself than the row"
            + " beside it: "
            + answer);
    assertTrue(answer.contains(handle.toString()), answer);
    assertTrue(answer.contains("ejected"), answer);
    assertTrue(answer.contains("2026-09-04"), answer);
  }

  // --- result_list ---------------------------------------------------------------------

  /**
   * A listing carries what a reference line carries, for the lines a fold took away: which tool
   * ran, how large its result was, and the handle.
   *
   * <p>Those three are what a model decides on <b>without</b> redeeming, which is the same claim
   * {@code Compaction.REFERENCE} makes about a result still in the prompt. A listing that named
   * fewer would cost a turn and leave the choosing undone.
   */
  @Test
  void a_listing_names_the_tool_the_size_and_the_handle_of_each_stored_result() {
    UUID handle = UUID.randomUUID();
    AgentTool tool =
        new ResultTools.Listing(
            listing(
                new StoredResults(
                    List.of(new StoredResults.Result("file_read", 10214, null, handle)), 1)));

    String answer = tool.run("{}", PAYMENTS);

    assertTrue(answer.contains("file_read"), answer);
    assertTrue(answer.contains("10214"), answer);
    assertTrue(answer.contains(handle.toString()), answer);
  }

  /**
   * The listing names the tool that turns one of its handles back into a result.
   *
   * <p>The pair is the mechanism: this hands out addresses and {@code result_read} spends them. A
   * listing that did not name the second is a turn spent on addresses a model has no instruction to
   * use — the same failure a reference line with no redeeming tool would be.
   */
  @Test
  void a_listing_names_the_tool_that_redeems_a_handle() {
    AgentTool tool =
        new ResultTools.Listing(
            listing(
                new StoredResults(
                    List.of(new StoredResults.Result("file_read", 10, null, UUID.randomUUID())),
                    1)));

    assertTrue(
        tool.run("{}", PAYMENTS).contains(ResultTools.READ_NAME),
        "a listing handed out handles without saying what redeems them");
  }

  /**
   * A conversation with nothing behind a seam says which state it is in.
   *
   * <p>Not "no results": every result it has is in front of the model already, each as its own
   * stored-result line. A blank or a bare "none" would read as a conversation whose history had
   * been lost.
   */
  @Test
  void a_conversation_with_nothing_behind_a_seam_says_so_rather_than_nothing() {
    AgentTool tool = new ResultTools.Listing(listing(StoredResults.NONE));

    String answer = tool.run("{}", PAYMENTS);

    assertFalse(answer.isBlank(), "a blank tool result reads as a tool that does not work");
    assertTrue(answer.contains(ResultTools.LIST_NAME), answer);
  }

  /**
   * A long conversation's listing is bounded, says how many there are in all, and names the call
   * that fetches the rest.
   *
   * <p>{@code file_read}'s shape exactly — a cap the tool owns, both numbers in the sentence, and
   * the next call spelled out — and for its reason: a model that was handed a short list and no
   * total cannot tell a bound from the end of the list, and only one of those readings is true.
   */
  @Test
  void a_listing_is_bounded_and_names_the_call_that_fetches_the_rest() {
    List<StoredResults.Result> many = new java.util.ArrayList<>();
    for (int each = 0; each < 60; each++) {
      many.add(new StoredResults.Result("file_read", 100, null, UUID.randomUUID()));
    }
    AgentTool tool =
        new ResultTools.Listing(
            listing(
                new StoredResults(many.subList(0, ResultTools.Listing.MOST_LISTED), many.size())));

    String answer = tool.run("{}", PAYMENTS);

    assertEquals(
        ResultTools.Listing.MOST_LISTED,
        answer.lines().filter(line -> line.contains("— handle ")).count(),
        "a listing returned more rows than its own bound: " + answer);
    assertTrue(answer.contains("60"), "the total is missing: " + answer);
    assertTrue(
        answer.contains("offset=" + ResultTools.Listing.MOST_LISTED),
        "the next call is missing: " + answer);
  }

  /**
   * An offset past the end says where the list ends, rather than reading as an empty conversation.
   *
   * <p>{@code file_read} splits the same two answers for the same reason — a file with nothing in
   * it and a caller that has paged one window too far are different facts, and a model that reads
   * the second as the first stops looking.
   */
  @Test
  void an_offset_past_the_end_says_where_the_list_ends() {
    AgentTool tool = new ResultTools.Listing(listing(new StoredResults(List.of(), 7)));

    String answer = tool.run("{\"offset\": 40}", PAYMENTS);

    assertTrue(answer.contains("7"), answer);
    assertTrue(answer.contains("offset=0"), "nothing told it how to get back: " + answer);
  }

  /**
   * Every way a model can get this argument wrong, and none of them ends the run. {@code
   * AgentTool}: a caller's mistake is a result it can correct on its next turn.
   */
  @Test
  void a_bad_offset_is_a_tool_result_and_not_an_exception() {
    AgentTool tool =
        new ResultTools.Listing(
            listing(
                new StoredResults(
                    List.of(new StoredResults.Result("file_read", 10, null, UUID.randomUUID())),
                    1)));

    for (String arguments :
        List.of("not json at all", "[]", "{\"offset\": -3}", "{\"offset\": \"the top\"}")) {
      String answer = tool.run(arguments, PAYMENTS);
      assertFalse(answer.isBlank(), "blank answer for " + arguments);
    }
    assertThrows(NullPointerException.class, () -> tool.run(null, PAYMENTS));
  }

  /**
   * A tool name a model invented cannot run away with the answer.
   *
   * <p>The name on a listing line is <b>model-supplied text</b>, not a name from any registry: a
   * call to a tool that does not exist is still answered and still recorded, so the name is bounded
   * by nothing and can carry line breaks. It is flattened for {@code JobRuntime.noSuchTool}'s
   * reason — one invented name must not turn one line into twenty — and shortened, because this is
   * the one part of a listing row whose length is not this server's to decide.
   */
  @Test
  void a_tool_name_a_model_invented_cannot_run_away_with_the_answer() {
    String invented = "read\nthe\nfile".repeat(400);
    AgentTool tool =
        new ResultTools.Listing(
            listing(
                new StoredResults(
                    List.of(new StoredResults.Result(invented, 10, null, UUID.randomUUID())), 1)));

    String answer = tool.run("{}", PAYMENTS);

    assertEquals(
        1,
        answer.lines().filter(line -> line.contains("— handle ")).count(),
        "an invented name broke one row into several: " + answer);
    assertTrue(answer.length() < 1000, "an invented name filled the answer: " + answer);
  }

  /**
   * A result whose call nothing in the log declares still gets its line: the address is the thing
   * that cannot be recovered any other way, and the missing name is said rather than guessed at.
   */
  @Test
  void a_result_nothing_declared_is_listed_without_a_name() {
    UUID handle = UUID.randomUUID();
    AgentTool tool =
        new ResultTools.Listing(
            listing(
                new StoredResults(List.of(new StoredResults.Result(null, 10, null, handle)), 1)));

    String answer = tool.run("{}", PAYMENTS);

    assertTrue(answer.contains(handle.toString()), answer);
    assertFalse(answer.contains("null"), "a missing name reached the model as 'null': " + answer);
  }

  /**
   * A run in no conversation has nothing behind a seam, and says so rather than failing. {@link
   * Transcript#NONE}'s default is what makes that an ordinary state of a correct run.
   */
  @Test
  void a_run_in_no_conversation_has_nothing_to_list() {
    AgentTool tool = new ResultTools.Listing(Transcript.NONE);

    assertFalse(
        tool.run("{}", PAYMENTS).isBlank(),
        "a blank tool result reads as a tool that does not work");
  }

  /**
   * The tool the seam sends a model to is the one this name constant spells. A weak assertion on
   * purpose, for {@code the_tool_the_model_is_shown_is_the_one_the_reference_names}' reason: the
   * wording is the load-bearing part and no suite can see whether it earns a call.
   */
  @Test
  void the_tool_the_model_is_shown_is_the_one_the_seam_names() {
    AgentTool tool = new ResultTools.Listing(Transcript.NONE);

    assertEquals(ResultTools.LIST_NAME, tool.schema().name());
    assertTrue(
        tool.schema().description().contains(ResultTools.READ_NAME), tool.schema().description());
    assertNotNull(tool.schema().parameters().get("properties"));
  }

  /**
   * A transcript holding one page of stored results and nothing else, which is all {@code
   * result_list} reads a conversation for. The page is returned whatever is asked for: what the
   * store does with {@code skip} and {@code most} is {@code EntryStoreTest}'s to hold, and what
   * this file asks is what the model is shown.
   */
  private static Transcript listing(StoredResults stored) {
    return new Transcript() {

      @Override
      public List<ChatMessage> before() {
        return List.of();
      }

      @Override
      public void promptMeasured(int promptTokens) {
        // Nothing is counting.
      }

      @Override
      public StoredResults stored(int skip, int most) {
        return stored;
      }
    };
  }

  /**
   * A transcript whose one stored result has had its payload ejected: the row is still addressable
   * and the bytes are not.
   */
  private static Transcript ejected(UUID handle, Instant at, String export) {
    return new Transcript() {

      @Override
      public List<ChatMessage> before() {
        return List.of();
      }

      @Override
      public void promptMeasured(int promptTokens) {
        // Nothing is counting.
      }

      @Override
      public Optional<Redemption> redeem(UUID asked) {
        return handle.equals(asked)
            ? Optional.of(Redemption.ejected(at, export))
            : Optional.empty();
      }
    };
  }

  /**
   * A transcript holding exactly one stored result, which is all this tool reads a conversation
   * for.
   */
  private static Transcript holding(UUID handle, String content) {
    return new Transcript() {

      @Override
      public List<ChatMessage> before() {
        return List.of();
      }

      @Override
      public void promptMeasured(int promptTokens) {
        // Nothing is counting.
      }

      @Override
      public Optional<Redemption> redeem(UUID asked) {
        return handle.equals(asked) ? Optional.of(Redemption.held(content)) : Optional.empty();
      }
    };
  }
}
