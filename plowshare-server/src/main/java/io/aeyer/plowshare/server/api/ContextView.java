package io.aeyer.plowshare.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * The newest completed turn's peak prompt cost, split as far as it honestly splits and no further.
 *
 * <h2>The gap is the deliverable</h2>
 *
 * <p>A reference design shows {@code System prompt ~1.7K · Tools ~6.5K · Messages ~12.9K} of {@code
 * ~19.9K} — a third of a context window spent on tool schemas — and this server can honestly report
 * exactly one of those four numbers. {@code usage.prompt_tokens} is the model's own tokenizer
 * counting the <em>whole request</em>, which is what makes {@link #sent} exact and what makes every
 * part of it unavailable: <b>there is no tokenizer on this box.</b> {@code LmStudio} measured it
 * rather than assumed it — {@code /api/v0/tokenize} and {@code /v1/tokenize} both answer {@code
 * HTTP 200} with {@code {"error":"Unexpected endpoint or method"}} — and {@code Compaction} records
 * the consequence in four places.
 *
 * <p>So the three token components are sent as explicit nulls, each with a sentence in {@link
 * #unavailable} saying why. <b>They are fields rather than omissions</b> because a client rendering
 * that layout has to find the slot, read nothing in it, and be able to say so; a missing key would
 * read as a client built against an older server.
 *
 * <h2>What could be measured and deliberately is not</h2>
 *
 * <p>The parts <em>are</em> obtainable by difference: send the same request without the tool block
 * and subtract, and the endpoint's own tokenizer answers exactly what the block costs. That is not
 * done here, for two reasons that are worth writing down because the idea keeps looking cheap.
 *
 * <ul>
 *   <li><b>It costs a generation of unbounded length.</b> Nothing in this server can ask an
 *       endpoint to count a prompt without answering it, and a measurement recorded on this
 *       project's own reference node spent 2 997 completion tokens and 96 seconds of wall clock on
 *       a 51-token prompt. A {@code GET} that may take a minute and a half is not a read.
 *   <li><b>It would evict the thing it is measuring.</b> The endpoint credits a prompt that
 *       <em>extends</em> a sequence it holds and gives no partial credit otherwise — measured at
 *       about 30x in {@code implementation rationale} — so a probe sending a deliberately different
 *       prefix is a probe that costs the next real turn the reuse the whole compaction design is
 *       built on.
 * </ul>
 *
 * <p><b>A third reason stood here and has been discharged, which is worth recording rather than
 * deleting.</b> It read "nothing records which agent answered a turn ... so even an exact per-agent
 * measurement could not be subtracted from a particular conversation's {@link #sent} without the
 * server guessing which agent's block was in it". {@code turns.agent} records it now, from {@code
 * V17__conversation_origin.sql}. <b>The absences above are untouched by that</b> — the whole prompt
 * is measured and its parts are not, and a tokenizer is still what would be needed — so nothing
 * this record refuses became answerable. What changed is one step further out: {@link #prefix} no
 * longer needs the caller to say which agent it means for a conversation that has had a turn,
 * because {@code ConversationController.context} can read who answered. The parameter survives as
 * an override, for the different question of what a conversation would cost under an agent that has
 * not answered in it yet.
 *
 * <h2>Characters are the unit this box can count</h2>
 *
 * <p>{@link Prefix} is the one part of the split that is exact, and it is exact in the wrong unit.
 * It measures the JSON the transport really sends, through the same {@link ToolSchema#asDeclared}
 * that builds a request's {@code tools} array, so it cannot drift from the wire. <b>It is not a
 * token count and must not be scaled into one</b> — a characters-per-token ratio is the estimate
 * this whole surface exists to refuse — but it is the first time anything here can say what the
 * ten-tool block costs at all, and per tool.
 *
 * @param sent what the newest measured turn's whole prompt cost, by the model's own tokenizer, or
 *     null for a conversation no turn of which reached a model call. <b>Never zero</b>: {@code
 *     turns_prompt_tokens_are_a_measurement} refuses one, because a zero read as a measurement says
 *     the history is empty
 * @param sentAtTurn which turn {@link #sent} was measured on, or null when nothing was. Not
 *     necessarily the last turn — a turn that ended before its first model call has no measurement,
 *     and the newest one that does is the honest answer to "what is this conversation costing now"
 * @param turns how many turns this conversation holds. <b>Turns and not steps</b>: one turn is one
 *     thing a person said, however many model calls answering it took
 * @param turnsMeasured how many of them reached a model call and were counted. The two numbers
 *     apart is what tells a reader that {@link #sent} is older than the conversation
 * @param systemPromptTokens always null; see {@link #unavailable}
 * @param toolTokens always null; see {@link #unavailable}
 * @param messageTokens always null; see {@link #unavailable}
 * @param cacheHitRate always null. <b>The load-bearing absence.</b> The ~30x prefix reuse the
 *     entire compaction design rests on is unobservable in production: this endpoint's {@code
 *     usage} carries no {@code cached_tokens} at all, which {@code Compaction} already records. It
 *     was validated by a separate measurement and cannot be watched live, and a rate inferred from
 *     timing would report the number the design was checked against as though it had been observed
 * @param measuredTurns every turn whose prompt the model counted, oldest first, each with what it
 *     cost and what it grew by since the previous measured one. <b>Exact arithmetic over exact
 *     numbers</b>: both terms of a difference are {@code usage.prompt_tokens} from the model's own
 *     tokenizer, so the difference is too, and no tokenizer is needed on this box to take it. It is
 *     the one breakdown of {@link #sent} this server can honestly give — not a share of the prompt,
 *     but where the prompt went
 * @param unavailable one entry per number above, naming it and saying why it cannot be given. Never
 *     empty, and its length is a fact about this server rather than about this conversation
 * @param prefix what one agent's fixed block costs in characters, or null when the caller named no
 *     agent — which is the ordinary answer, because the conversation cannot supply one
 */
public record ContextView(
    Integer sent,
    Integer sentAtTurn,
    int turns,
    int turnsMeasured,
    List<Measured> measuredTurns,
    TokenCount systemPromptTokens,
    TokenCount toolTokens,
    TokenCount messageTokens,
    Double cacheHitRate,
    List<Unavailable> unavailable,
    Prefix prefix) {

  /**
   * The four sentences, in the order a client would render the numbers.
   *
   * <p>Written out rather than composed, because each says something different and a template would
   * flatten three distinct reasons into one shape. They name where the evidence is — a class, a
   * file — so that a reader who doubts one can check it.
   */
  /**
   * The absence that is still an absence.
   *
   * <p>This list held four. Three of them said a count of part of a prompt was unobtainable, and
   * one of the three said so for a reason that was not true — that measuring the system block's
   * share "costs a generation of unbounded length", when {@code OpenAiTransport.chatBody} has
   * always threaded {@code max_tokens} through from {@code Sampling} and a probe bounded at one
   * token would have answered it. The three are now counted by {@link Tokenizer}, on whatever basis
   * this deployment can manage, and each says which basis that was.
   *
   * <p><b>The cache rate is different in kind and stays.</b> No arithmetic and no tokenizer
   * recovers it: the endpoint's {@code usage} carries no {@code cached_tokens} field, so there is
   * no observation to be had at any fidelity. Deliberately not inferred from timing.
   */
  private static final List<Unavailable> WHY_NOT =
      List.of(
          new Unavailable(
              "cacheHitRate",
              "This endpoint's 'usage' carries no cached_tokens field, so prefix reuse"
                  + " is unobservable in production. That is worth stating plainly:"
                  + " the ~30x speed-up the compaction design rests on was measured"
                  + " separately, in"
                  + " implementation rationale, and"
                  + " cannot be watched live. It is deliberately not inferred from"
                  + " timing — a run is slower or faster for a dozen reasons, and a"
                  + " rate derived that way would report the number the design was"
                  + " checked against as though it had been observed."));

  /**
   * Compact, and with no indenting, because what is being measured is the bytes a request carries.
   *
   * <p>{@code OpenAiTransport} serialises the body with an ordinary mapper, so a pretty-printer
   * here would count whitespace the wire never sees and would overstate the block by a third.
   */
  private static final ObjectWriter WIRE = new ObjectMapper().writer();

  public static ContextView of(List<TurnRecord> turns, Prefix prefix) {
    Integer sent = null;
    Integer at = null;
    int measured = 0;
    List<Measured> series = new ArrayList<>();
    for (TurnRecord turn : turns) {
      if (turn.promptTokens() == null) {
        continue;
      }
      measured++;
      // Both terms are the model's own count of a whole request, so the
      // difference is exact and needs no tokenizer here. Null on the
      // first measured turn rather than zero, for the reason stated
      // everywhere else on this surface: a zero says something was
      // counted and found to be none, and here nothing was counted.
      series.add(
          new Measured(
              turn.ordinal(),
              turn.promptTokens(),
              sent == null ? null : turn.promptTokens() - sent,
              at));
      // The newest completed turn, not the largest turn in the conversation.
      // Each TurnRecord holds its peak prompt, so an intra-turn fold does
      // not lower this historical value. It is not the next projection count.
      sent = turn.promptTokens();
      at = turn.ordinal();
    }
    return new ContextView(
        sent,
        at,
        turns.size(),
        measured,
        List.copyOf(series),
        prefix == null ? null : prefix.systemPromptTokens(),
        prefix == null ? null : prefix.toolTokens(),
        messageShare(sent, prefix),
        null,
        WHY_NOT,
        prefix);
  }

  /**
   * What the messages cost, as the remainder of a measured whole.
   *
   * <p><b>The total is a fact and the split is not</b>, and the result says so: {@code sent} is the
   * model's own count of the whole request, the fixed block's share is whatever {@link Tokenizer}
   * could manage, and a subtraction is no better founded than its worst term. So this is {@link
   * TokenCount.Basis#ESTIMATED} whenever the block's share was, and it is measured only when the
   * block's share was measured too.
   *
   * <p>Null rather than a guess when no agent was named: without the block there is nothing to
   * subtract, and reporting {@code sent} itself as the messages' share would attribute the system
   * prompt and every tool schema to the conversation.
   *
   * <p><b>Floored at zero, and that floor is a real case.</b> An agent's prompt file can be edited
   * between the turn that was measured and the read that is estimating now, so the fixed block can
   * estimate larger than a whole prompt measured before it grew. A negative remainder is that
   * mismatch and not a quantity of tokens.
   */
  private static TokenCount messageShare(Integer sent, Prefix prefix) {
    if (sent == null || prefix == null) {
      return null;
    }
    TokenCount prompt = prefix.systemPromptTokens();
    TokenCount tools = prefix.toolTokens();
    // Both, and not the better of the two: a subtraction is no better
    // founded than its worst term, and one measured half does not make a
    // remainder computed partly from a guess into a measurement.
    boolean counted = prompt.isMeasured() && tools.isMeasured();
    int block = prompt.tokens() + tools.tokens();
    int rest = Math.max(0, sent - block);
    String how =
        "the whole prompt was counted by the model at "
            + sent
            + " tokens, less the"
            + " fixed block's "
            + block
            + " — so this is only as well founded as the block's"
            + " share, which is: "
            + (counted ? prompt.how() : worse(prompt, tools).how());
    return counted ? TokenCount.measured(rest, how) : TokenCount.estimated(rest, how);
  }

  /** The weaker of two counts, which is what a sum of them is worth. */
  private static TokenCount worse(TokenCount one, TokenCount other) {
    return one.better(other) == one ? other : one;
  }

  /**
   * One measured turn, and what it grew by since the previous measured one.
   *
   * <p><b>{@code since} is carried rather than implied</b>, because measured turns are not
   * necessarily consecutive: a turn that never reached a model call records no {@code
   * prompt_tokens}, so a growth can span several turns and a reader who assumed it spanned one
   * would attribute the whole of it to the wrong turn.
   *
   * <p><b>A negative growth is the point and not a fault.</b> It is a fold: compaction replaced
   * history with a summary and the next prompt was smaller. It is the only place on this surface
   * where the compaction seam is visible as a number.
   *
   * @param turn which turn this was measured on
   * @param promptTokens what its whole prompt cost, by the model's tokenizer
   * @param grewBy the difference from the previous measured turn, or null on the first — where
   *     there is no previous turn to differ from
   * @param since which turn {@code grewBy} is measured from, or null with it
   */
  public record Measured(int turn, int promptTokens, Integer grewBy, Integer since) {}

  /**
   * One number this surface cannot give, and the reason.
   *
   * @param component the field it would have been, spelled as the field is, so a client can pair
   *     the two without a table of its own
   * @param reason why not, in prose a person reads. Long on purpose: a one-word reason is what
   *     makes an absence look like an oversight
   */
  public record Unavailable(String component, String reason) {}

  /**
   * What one agent's fixed block costs — the part of every request that does not vary with what was
   * said.
   *
   * <p>Measured in characters of the JSON the transport sends, which is the one exact unit
   * available. {@code toolCharacters} is the whole {@code tools} array as {@link
   * ToolSchema#asDeclared} builds it and {@code OpenAiTransport.chatBody} sends it; the per-tool
   * numbers are each entry of that array on its own, so they sum to slightly less than the total —
   * the difference is the array's own brackets and commas, which belong to no tool.
   *
   * <p><b>{@code systemPromptCharacters} is a length and never the text.</b> {@code AgentView}
   * declines to ship an agent's prompt to a browser and the reason is real — a prompt on a page is
   * one XSS away from being read by whatever influenced the text on it — and a character count
   * carries none of that. It is also only the agent's own prompt: a conversation with a standing
   * seam has the summary merged into the same system message at request time, which is a fact about
   * that conversation and not about this agent.
   *
   * @param agent the agent this block belongs to, as the caller named it
   * @param model the specifier its requests are dispatched under
   * @param systemPromptCharacters how long the agent's own prompt is
   * @param toolCharacters how long the whole {@code tools} array is on the wire
   * @param tools one entry per tool this boot would actually offer this agent, in the order a
   *     request carries them. <b>Can be shorter than the agent's {@code tools:} line</b>, when a
   *     deployment wired fewer — which is a real state a run is in silently, and this is the first
   *     surface that shows it
   * @param contextLength how long a prompt {@code model} accepts, or null when nothing serves it.
   *     <b>The number compaction folds under</b> — {@code Compaction.contextLengthOf}, default
   *     included — so a client dividing {@link ContextView#sent} by it shows the load against the
   *     same wall. A fact about the fleet now and not about any turn: a node reloaded at a
   *     different length answers differently on the next read
   */
  public record Prefix(
      String agent,
      String model,
      int systemPromptCharacters,
      int toolCharacters,
      TokenCount systemPromptTokens,
      TokenCount toolTokens,
      List<ToolCost> tools,
      Integer contextLength) {

    /**
     * The block, in characters exactly and in tokens on the best basis available.
     *
     * <p>Both, and not one or the other. Characters are what the wire carries and are exact here;
     * tokens are what the model charges for and are whatever {@link Tokenizer} can manage. Keeping
     * both is what stops the character count from being silently read as a token count, which is
     * the confusion the old reason line warned about — "characters are not tokens and must not be
     * scaled into them" — while a client had nothing else to reach for.
     *
     * @param contextLength what {@code Compaction.contextLengthOf} answered for {@code definition}
     */
    public static Prefix of(
        AgentDefinition definition,
        List<ToolSchema> offered,
        Tokenizer tokenizer,
        OptionalInt contextLength) {
      List<ToolCost> each = new ArrayList<>(offered.size());
      for (ToolSchema tool : offered) {
        each.add(new ToolCost(tool.name(), characters(List.of(tool))));
      }
      String prompt = definition.prompt() == null ? "" : definition.prompt();
      return new Prefix(
          definition.name(),
          definition.model(),
          prompt.length(),
          characters(offered),
          tokenizer.count(prompt),
          tokenizer.count(json(offered)),
          each,
          contextLength.isPresent() ? contextLength.getAsInt() : null);
    }

    /**
     * How many characters one or more schemas are on the wire, minus the array's own two brackets.
     *
     * <p>The brackets are removed so that the whole and the parts are measured the same way: with
     * them, a one-tool total would be two characters more than the tool, and a reader comparing the
     * sum of the parts against the whole would find a discrepancy that is punctuation. What remains
     * in the difference is the commas between entries, which are real bytes belonging to no tool.
     *
     * @throws IllegalStateException if a schema this server is about to offer a model cannot be
     *     serialised, which is not a caller's mistake and must not be reported as an empty
     *     measurement
     */
    private static int characters(List<ToolSchema> schemas) {
      return json(schemas).length();
    }

    /**
     * The block as the wire carries it, minus the array's own two brackets.
     *
     * <p>Split out of {@link #characters} so the tokenizer counts the same bytes the character
     * count reports. Counting a differently-built string would put the two numbers on this record
     * subtly out of step, and nothing downstream could tell.
     */
    private static String json(List<ToolSchema> schemas) {
      try {
        String whole = WIRE.writeValueAsString(ToolSchema.asDeclared(schemas));
        return whole.length() < 2 ? whole : whole.substring(1, whole.length() - 1);
      } catch (JsonProcessingException unwritable) {
        throw new IllegalStateException(
            "a tool schema this server offers could not be serialised, so what it"
                + " costs cannot be measured: "
                + unwritable.getOriginalMessage(),
            unwritable);
      }
    }
  }

  /** One tool's share of the block, in characters of the JSON sent. */
  public record ToolCost(String name, int characters) {}
}
