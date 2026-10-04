package io.aeyer.plowshare.server.llm;

import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import java.util.OptionalInt;

/**
 * A transport, plus whatever the backend behind it can be asked that {@code /v1} has no field for.
 *
 * <p><b>This is a facility of the LLM layer and not a conversation feature.</b> Compaction is its
 * first caller and the reason it was built now; nothing about it is specific to a conversation.
 *
 * <pre>
 * LlmProvider                what it knows
 *   OpenAiCompatible         /v1 only. Context length: whatever it was told.
 *   LmStudio (decorates it)  /v1 for calls, /api/v0/models for facts.
 * </pre>
 *
 * <p>A provider <em>decorates</em> rather than replaces. Every chat and embedding call is delegated
 * untouched, because {@code /v1} is the contract every backend honours and a second wire shape for
 * the same endpoints would be a second set of timeout and retry behaviours to reason about. What a
 * vendor adds is knowledge, not calls.
 *
 * <p><b>No probe may happen on the boot path.</b> {@code application.yml} records a deliberate
 * property — a model name the server does not know "fails at the call rather than at startup" — and
 * a provider that probed during startup and refused when the node was down would trade that away.
 * Discovery is opportunistic and cached, and a failure is an unknown length rather than an outage.
 *
 * <p>That is a rule about connections rather than about methods, and the difference is what {@link
 * #canDiscover()} is for. Both methods <em>are</em> called at boot: {@code canDiscover()} always,
 * and {@link #contextLength(String)} only once {@code canDiscover()} has answered false, where an
 * implementation with nowhere to look can do nothing but read what it was configured with. {@code
 * starting_the_server_opens_no_connection_to_the_endpoint} pins the result against a socket that
 * counts, because an implementation that probed at boot would otherwise look identical from outside
 * — merely slower.
 */
public interface LlmProvider extends LlmTransport {

  /**
   * How many tokens this pool's copy of {@code wireModel} is loaded to accept, or empty if nobody
   * has said and nobody can be asked.
   *
   * <p><b>Re-declared here without a body, over {@link LlmTransport}'s default answering empty.</b>
   * That default exists so {@code LlmPool} can ask any transport without a cast; re-declaring it
   * abstract here is what stops a <em>provider</em> — the type whose whole reason to exist is
   * answering what {@code /v1} cannot — from inheriting that silence by omission. A new backend
   * added to this hierarchy has to decide.
   *
   * <p><b>Precedence, stated where it is implemented.</b> A configured length wins: an operator
   * overriding a discovered number is doing it on purpose. Discovery fills the gap where nothing is
   * configured. Where neither answers, the answer is empty — and a caller that needs a number has
   * to decide what to do without one, rather than being handed a guess.
   *
   * <p>May open a connection, and must never throw because it could not. A failed probe is an empty
   * answer.
   *
   * @param wireModel the model name as the endpoint knows it, not a class
   */
  OptionalInt contextLength(String wireModel);

  /**
   * Whether this provider has anywhere at all to look for a length nobody configured.
   *
   * <p>Read at boot, where {@link #contextLength(String)} must not be. It is the difference between
   * "not known yet" and "never knowable", and only the second is worth telling an operator about
   * before anything has run: a pool that can discover has simply not been asked yet, while a pool
   * that cannot is serving a model at a length nobody will ever supply.
   */
  boolean canDiscover();
}
