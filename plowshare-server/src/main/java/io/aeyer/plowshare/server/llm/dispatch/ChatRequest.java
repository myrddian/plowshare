package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;

import io.aeyer.plowshare.protocol.ToolCall;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A chat call, named by what kind of model it needs rather than by a host.
 *
 * @param specifier a model ({@code qwen3.5-9b}) or a class ({@code fast}). The
 *     dispatcher owns the map from this to a pool and a wire model; the caller
 *     never learns which one exists or where it runs. The example names what
 *     the reference box actually serves, and what {@code application.yml} ships
 *     as {@code LLM_CHAT_MODEL}'s default: a wire model name is matched exactly
 *     by the endpoint, so an example naming a model nothing serves is the wrong
 *     shape for a reader to copy.
 * @param messages the conversation so far, in order, ending with whatever the
 *     model is being asked to answer. Never null and never empty.
 *
 *     <p><b>This replaced a system prompt and a user prompt.</b> Slice 2 built
 *     chat for prose and two fields were enough; a turn loop needs the middle of
 *     a conversation — the assistant turn that asked for tools, and one tool
 *     message per {@code tool_call_id} answering it. See {@link ChatMessage} for
 *     why that has to be a real message array rather than a rendered transcript,
 *     and what was measured against which shape.
 *
 *     <p>{@link #of(String, String, String)} builds the two-message list every
 *     prose caller wants, so the change is invisible to one: a request built
 *     through it produces the same body byte for byte as the two loose fields
 *     did.
 * @param sampling how to sample, with every parameter able to be <em>absent</em>.
 *     Never null; {@link Sampling#NONE} is the ordinary value and means <b>send
 *     no sampling keys at all</b>, so the model's own defaults apply.
 *
 *     <p><b>This replaced a primitive {@code double temperature} that was
 *     hardcoded to {@code 0.0} on every prose call.</b> That constant was never
 *     a decision — until {@code temperature:} shipped there was no other option
 *     — and it was measured on 2026-09-04 driving a summariser cascade into
 *     reproducible repetition loops: 3 999 completion tokens, 3 997 of them
 *     reasoning, empty content, the same paragraph twice over. The endpoint
 *     resolves a setting model defaults → {@code model.yaml} → load-time →
 *     inference-time, later winning, so a request that sent <em>nothing</em>
 *     would have run that model at its own vendor's numbers. The runaway was
 *     not a missing setting; it was a wrong one being sent, which is why the
 *     absence of a value now has a representation.
 *
 *     <p>{@code Sampling} carries the range rules: finite temperature and
 *     {@code top_p}, positive {@code top_k} and {@code max_tokens}, and a
 *     {@code reasoning_effort} closed at the three values the vendor names.
 * @param submitTimeout how long this request is willing to wait to <em>start</em>,
 *     or {@code null} for the pool's default. Nullable rather than zero because
 *     zero is a meaningful value that means the opposite thing. The budget
 *     belongs to the request: thirty seconds of queueing is a failed
 *     interaction for a recall and exactly right for a batch ingest, and one
 *     per-pool number cannot serve both.
 * @param tools what the model may call this turn; empty for a prose call.
 *     Never null, because the transport iterates it and a null would be a
 *     NullPointerException on a lane thread, several frames from the caller
 *     that left it out. An empty list is also not an empty {@code tools} array
 *     on the wire: {@code OpenAiTransport.chatBody} omits the key entirely,
 *     because some OpenAI-compatible servers refuse the former.
 *
 *     <p><b>{@link LlmDispatcher#complete} and {@link LlmDispatcher#stream}
 *     honour these alike.</b> This paragraph used to say the opposite — that
 *     streaming refuses a tool-carrying request, because reading tool calls out
 *     of an SSE body means reassembling {@code tool_calls} deltas fragmented
 *     across chunks, "which this slice does not do". That was an
 *     unimplemented-work note rather than a design refusal, and the work is now
 *     done: {@code OpenAiTransport.stream} accumulates the fragments per {@code
 *     index} and returns the same {@link ToolCall} list the blocking path
 *     returns for the same turn. Nothing about a request changes with how it is
 *     dispatched.
 * @param attribution server-validated ownership, carried explicitly across pool queue threads.
 *     Compatibility callers use {@link UsageAttribution#LEGACY}; production callers will supply
 *     an owner as durable recording is integrated. This metadata is not sent to the model.
 * @param toolChoice whether the model may end this call without calling
 *     anything, or {@code null} to send no {@code tool_choice} key at all.
 *
 *     <p><b>Here and not in {@link Sampling}, which was the first place it was
 *     tried.</b> Sampling carries {@code responseFormat}, which is the same kind
 *     of provider-side decoding constraint, so that looked like the home. It is
 *     not: sampling is filtered per pool by {@link Sampling#carriedBy}, and a
 *     pool that had not declared this parameter would <em>silently drop it</em>
 *     — restoring the exact bug it exists to close, in the one shape nobody
 *     would look for. It belongs beside {@code tools}, which is what it is about.
 *
 *     <p>Null and not {@code Optional}, matching {@code submitTimeout} above
 *     rather than the Optionals inside {@code Sampling}: this record's own
 *     absent-valued component is a null, and two spellings of absence in one
 *     record is worse than either.
 */
public record ChatRequest(
        String specifier,
        List<ChatMessage> messages,
        Sampling sampling,
        Duration submitTimeout,
        List<ToolSchema> tools,
        ToolChoice toolChoice,
        UsageAttribution attribution) {

    /** Compatibility for existing callers; production ownership is supplied with withAttribution. */
    public ChatRequest(String specifier, List<ChatMessage> messages, Sampling sampling,
            Duration submitTimeout, List<ToolSchema> tools, ToolChoice toolChoice) {
        this(specifier, messages, sampling, submitTimeout, tools, toolChoice, UsageAttribution.LEGACY);
    }

    public ChatRequest {
        java.util.Objects.requireNonNull(attribution, "attribution");
        Specifiers.require(specifier);
        // Copied, so a caller that builds a conversation, passes it and then
        // appends to it cannot change what a request already in a pool queue
        // will send. List.copyOf also rejects a null list and a null element,
        // both of which would otherwise surface inside Jackson on a lane thread.
        messages = List.copyOf(messages);
        // The successor to slice 2's "a chat request needs a user prompt". A
        // conversation of nothing but system prompts is a call with nothing to
        // answer, which is a bug at the call site rather than a request the
        // endpoint should be asked about. IllegalArgumentException for the
        // reason RequestTest sets out at length: a chat surface translates this
        // one type to a 400 at a single boundary site, and a second spelling of
        // the same mistake would go on reading "the server is broken".
        if (messages.stream().allMatch(message -> message.role() == ChatMessage.Role.SYSTEM)) {
            throw new IllegalArgumentException(
                    "a chat request needs something to answer; it carried "
                            + (messages.isEmpty() ? "no messages" : "only a system prompt"));
        }
        requireEveryToolResultAnswersACall(messages);
        requireAtMostOneSystemMessageAndItFirst(messages);
        // Never null, because every consumer asks it what it carries and a null
        // would be a NullPointerException on a lane thread. Sampling itself
        // owns the range rules — JSON has no encoding for NaN or an infinity,
        // so a non-finite value must fail where it was written rather than at
        // the endpoint, as a type error about a field the caller never sees.
        java.util.Objects.requireNonNull(sampling, "sampling");
        tools = List.copyOf(tools);
    }

    /**
     * At most one {@code system} message, and it is the first.
     *
     * <h2>This one was paid for</h2>
     *
     * <p>Slice 3e held a conversation against the live {@code qwen3.5-9b} until
     * its history compacted, and <b>every turn after the fold ended {@code
     * UNAVAILABLE}</b>. The endpoint said why itself, and the sentence reached
     * {@code Outcome.detail()} intact:
     *
     * <pre>    Jinja Exception: System message must be at the beginning.</pre>
     *
     * <p>{@code Compaction.messages} introduces a seam as {@code
     * ChatMessage.system(SEAM…)} — rightly, a seam being the harness speaking and
     * neither the person nor the model — and {@code JobRuntime.opening} put the
     * agent's own prompt in front of it. Two system messages, the second at index
     * one, and that model's chat template refuses the shape outright. A fold is
     * permanent, so the conversation never recovered: it bought a fresh summary
     * every turn and answered none of them.
     *
     * <h2>Why the check belongs here and not only where it broke</h2>
     *
     * <p>It was fixed in {@code opening} first, which is the local repair. This
     * is the durable one, and the difference is what a test can see. <b>Fifteen
     * files in this repository implement {@link LlmTransport} or stand in for a
     * dispatcher, and not one of them validates a message list</b> — nor does
     * MockWebServer, which is what the transport tests speak to. So a request no
     * backend on earth would accept read as green across the whole suite, and the
     * defect was found by hand against a real node rather than by any of it.
     *
     * <p>A rule enforced at the chokepoint is enforced by every test that reaches
     * the chokepoint. Every model call in this server is a {@code ChatRequest},
     * so putting it here makes the entire suite an instrument for it without a
     * single test being written — which is the property {@code opening} alone
     * could never have, because it is one of three places a message list is
     * assembled.
     *
     * <p><b>Conversation shape belongs to this constructor</b>, which is the
     * division {@link #requireEveryToolResultAnswersACall} already states: a
     * message cannot see its neighbours and a request can. This is the second
     * rule of that kind and it arrives by the same route the first did — a real
     * conversation that a strict endpoint refused.
     *
     * <h2>What is deliberately not claimed</h2>
     *
     * <p>Not that the OpenAI contract forbids a later system message; it does
     * not, and a hosted endpoint will take one. <b>What this asserts is the
     * narrowest shape every backend this project targets accepts</b>, and a
     * request built wider than that is a call-site mistake in the same sense a
     * dangling tool result is: it will be refused, and refused somewhere far from
     * the code that built it. Widening this is a decision to make deliberately,
     * with a second backend in front of you, and not by deleting a line to make a
     * test pass.
     *
     * <p>Zero system messages is ordinary and is not touched — {@code
     * ChatMessage.conversation} omits the message rather than sending a blank
     * one, and {@code AgentDefinition}s with no prompt exist.
     */
    private static void requireAtMostOneSystemMessageAndItFirst(List<ChatMessage> messages) {
        for (int at = 0; at < messages.size(); at++) {
            if (messages.get(at).role() != ChatMessage.Role.SYSTEM) {
                continue;
            }
            if (at != 0) {
                throw new IllegalArgumentException(
                        "a chat request carries at most one system message and it must be the"
                                + " first; this one has a system message at position " + at
                                + " of " + messages.size() + ". A model whose chat template"
                                + " allows one refuses the whole request — measured, as"
                                + " \"System message must be at the beginning\" — so a caller"
                                + " with two things to say as the system says them in one"
                                + " message.");
            }
        }
    }

    /**
     * Every {@code tool} message answers a call some earlier {@code assistant}
     * message declared.
     *
     * <p><b>Conversation shape is checked here; {@link ChatMessage} checks only
     * one message at a time.</b> The division is not arbitrary — a message
     * cannot see its neighbours and a request can — but it was worth writing
     * down, because {@code ChatMessage}'s javadoc said it "refuses the
     * combinations that are not messages" and a reader could take that for more
     * than per-message shape.
     *
     * <p>The rule catches a real bug rather than a theoretical one. The turn
     * loop mints a stand-in id for a call that arrives without one; an earlier
     * version minted it for the {@code tool} message only and left the {@code
     * assistant} turn declaring the empty id it was given, so the result
     * referenced a call nothing declared. A strict OpenAI-compatible server
     * rejects that conversation, and it rejects it on the <em>next</em> request
     * — so the run would have ended {@code UNAVAILABLE} for a reason nothing in
     * the message would explain, at a point far from the mistake.
     *
     * <p><b>The converse is deliberately not enforced:</b> an assistant turn may
     * declare a call that no {@code tool} message answers. The turn loop always
     * answers every call, so nothing built here needs it — but the shape is
     * legitimate for a caller that means it, such as a run that stops between
     * issuing calls and running them, and refusing it would forbid a turn loop
     * this task did not write. It is left representable knowingly rather than by
     * omission.
     */
    private static void requireEveryToolResultAnswersACall(List<ChatMessage> messages) {
        Set<String> declared = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message.role() == ChatMessage.Role.ASSISTANT) {
                for (ToolCall call : message.toolCalls()) {
                    declared.add(call.id());
                }
            } else if (message.role() == ChatMessage.Role.TOOL
                    && !declared.contains(message.toolCallId())) {
                throw new IllegalArgumentException(
                        "a tool message answers call '" + message.toolCallId() + "', which no"
                                + " earlier assistant message asked for; the model has nothing"
                                + " to attach the result to");
            }
        }
    }

    /**
     * The prose call: one system prompt, one user prompt, <b>no sampling
     * parameters</b> and the pool's default budget.
     *
     * <p>Nearly every internal caller, the scribe included — one call, no tools,
     * no history.
     *
     * <p><b>This used to build at a hardcoded {@code 0.0} and no longer does,
     * and that is a deliberate behaviour change rather than a refactor.</b>
     * Nobody chose that zero: it was the only option before {@code temperature:}
     * existed, and eleven agents inherited it. It is also the exact
     * configuration measured driving this server's summariser cascade into
     * deterministic repetition loops on a model whose vendor forbids greedy
     * decoding in as many words. Sending nothing is not a fallback to a
     * conservative number — it is the absence of numbers, and it hands the
     * decision to the layer that actually knows the model.
     */
    public static ChatRequest of(String specifier, String system, String user) {
        return new ChatRequest(
                specifier, ChatMessage.conversation(system, user), Sampling.NONE, null,
                List.of(), null);
    }

    /** A conversation of any shape: what a turn loop builds, and the only entry
     *  point that can carry an assistant turn or a tool result. */
    public static ChatRequest of(String specifier, List<ChatMessage> messages) {
        return new ChatRequest(specifier, messages, Sampling.NONE, null, List.of(), null);
    }

    public ChatRequest withBudget(Duration budget) {
        return new ChatRequest(specifier, messages, sampling, budget, tools, toolChoice, attribution);
    }

    /**
     * The same call, sampled as {@code resolved} says.
     *
     * <p>Replaces {@code withTemperature(double)}, which could not express the
     * one value this design turns on: <em>nothing</em>. A caller holding an
     * agent definition composes this over {@link #of}; see {@code
     * JobRuntime.requestFor}, which is the one place in {@code main} that does.
     */
    public ChatRequest withSampling(Sampling resolved) {
        return new ChatRequest(specifier, messages, resolved, submitTimeout, tools, toolChoice, attribution);
    }

    /** What the model may call this turn. See the {@code tools} parameter for
     *  why an empty list and an empty wire array are not the same thing. */
    public ChatRequest withTools(List<ToolSchema> offered) {
        return new ChatRequest(specifier, messages, sampling, submitTimeout, offered, toolChoice, attribution);
    }

    /**
     * The same call, with the model forbidden to answer without calling something.
     *
     * <p><b>Composed after {@link #withTools} and never before it.</b> The transport drops the key
     * when no tool is offered, so the order does not change what is sent — but a reader following
     * a call site should see the tools arrive first, because requiring a call from nothing is the
     * one combination that is meaningless.
     */
    public ChatRequest withToolChoice(ToolChoice choice) {
        return new ChatRequest(specifier, messages, sampling, submitTimeout, tools,
                java.util.Objects.requireNonNull(choice, "toolChoice"), attribution);
    }

    /**
     * The same call with a different conversation — what a turn loop builds
     * between turns.
     *
     * <p>Returns a new request rather than mutating: one may already be queued
     * in a pool, and a conversation that changed under a queued request would
     * send the model a history it was not given.
     */
    public ChatRequest withMessages(List<ChatMessage> conversation) {
        return new ChatRequest(specifier, conversation, sampling, submitTimeout, tools, toolChoice, attribution);
    }

    /** Immutable ownership travels with the request when a pool submits it on another thread. */
    public ChatRequest withAttribution(UsageAttribution owner) {
        return new ChatRequest(specifier, messages, sampling, submitTimeout, tools, toolChoice, owner);
    }
}
