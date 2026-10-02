package io.aeyer.plowshare.server.agents;

/**
 * What a finished answer said it took from the corpus, written down.
 *
 * <h2>Three shapes a citation could have been recorded in, and why this one</h2>
 *
 * <p><b>1. A tool the agent calls to record a citation.</b> Explicit and
 * structured, and refused. It is the shape {@code DocumentTools.Search#arguments}
 * already argues against in a neighbouring decision — <em>"a model that must
 * remember to call a second tool to check whether it is about to misquote will
 * not call it, and a guardrail that is an instruction is the shape this
 * repository refuses everywhere else"</em> — and the failure is worse here than
 * there. A citation the model forgot to record leaves an answer that looks
 * uncited, which is indistinguishable from an answer that had nothing to cite:
 * the gap is invisible from every surface. It would also cost a fourth tool
 * description on {@code librarian} and a fifth on {@code interlocutor}, which
 * {@code implementation rationale} says no test in this
 * suite can evaluate.
 *
 * <p><b>2. A join over what is already logged.</b> Tool results are stored
 * faithfully, so <em>which paragraphs the agent was shown</em> is already
 * durable and needs no new writing at all. What it cannot know is which of them
 * the answer used, which is the only thing a citation is. Ten hits shown and one
 * claim made is not ten citations, and recording it as ten would make the table
 * say something false about every answer in it.
 *
 * <p><b>3. Read them out of the answer.</b> Taken. The agent already names
 * paragraph ids in prose because its definition tells it to, and a paragraph id
 * is a uuid — so the extraction is a match on a shape no phrasing changes, not a
 * parse of English. Nothing new is put in front of a model: no tool, no
 * description, not one byte of any agent's prompt.
 *
 * <p><b>The three are not exclusive and this is 3 standing on 2.</b> The reason
 * shape 3 is safe to trust is that the corpus is a check on it — {@code
 * CitationStore.record} writes through a join on {@code paragraphs}, so a uuid
 * that names no paragraph writes nothing. That class's javadoc says why the
 * <em>log</em> join is deliberately not also a gate: the shown-set is the
 * ejectable half, and gating on it would drop citations exactly in the
 * conversations that have run longest.
 *
 * <h2>The failure mode, stated plainly</h2>
 *
 * <p><b>An answer that attributes a claim without writing the id down records
 * nothing.</b> That is this shape's whole exposure and it is a silence, not a
 * wrong row: the table under-reports and never mis-reports. It is also the
 * failure {@code librarian.md} was already written against a slice earlier —
 * <em>"a sentence you cannot attach a paragraph to is a sentence the corpus did
 * not give you"</em>, pinned by {@code LibrarianDefinitionTest
 * .the_librarian_is_told_to_name_the_paragraph_a_claim_came_from} — so the
 * instruction that makes this work was already load-bearing and already guarded
 * before there was anywhere to put a citation. Nothing about it changed here.
 *
 * <p>The second exposure, smaller: an answer that names a paragraph without
 * having taken anything from it — a sentence saying a passage does <em>not</em>
 * answer the question — is recorded as a citation. A citation is "this answer
 * drew on this paragraph", and that reading is wrong for such a sentence. No
 * shape above tells them apart, including the tool, since the model would call
 * it or not on the same judgement.
 *
 * <h2>The guardrail is in the loader</h2>
 *
 * <p>{@link AgentDefinition#canCite()}, which is {@code canRecall()}'s shape
 * exactly: whether an agent could have got a paragraph id honestly is a fact
 * about the tools an operator granted it, and it is read off the definition
 * rather than decided by an implementation or asserted in prose. It also keeps
 * this off the hot path — a document ingest is ~220 runs of agents declaring
 * {@code tools: []}, and not one of them is asked.
 *
 * <h2>An implementation must not throw</h2>
 *
 * <p>{@link Transcript#closed}'s rule, which is where this is called from. What
 * is lost when a write fails is the record that an answer cited something; what
 * is kept is the answer, the log and the spending. An implementation that cannot
 * reach the corpus says so in the log and returns.
 */
@FunctionalInterface
public interface Citing {

    /**
     * A server that records no citations.
     *
     * <p>{@link Reminding#NONE}'s shape and its justification: a server with no
     * corpus wired is a legal running server whose answers are exactly what they
     * were, and this is what every fixture gets — which is what keeps this
     * feature out of the hundreds of tests that assert on what a turn wrote.
     */
    Citing NONE = (definition, conversationId, turnOrdinal, answer) -> { };

    /**
     * Write down what this answer cited, if this agent could have cited
     * anything.
     *
     * <p>Called once per run, from {@code Compaction.TurnTranscript.closed},
     * after the answer is in the log and before the turn's row is written.
     * <b>Only for a run that answered</b>: every other ending carries the
     * runtime's own sentence in {@code Outcome.text}, and a citation read out of
     * a harness sentence would be a citation the agent never made.
     *
     * @param definition the agent that answered. <b>The guardrail is here and
     *     not in any prose</b>: {@link AgentDefinition#canCite()} is a fact the
     *     loader validated
     * @param conversationId the conversation the answer was spoken into, or null
     *     for a run that is in none
     * @param turnOrdinal which turn of it, or null with the above
     * @param answer the answer, verbatim as the log holds it
     */
    void whatTheAnswerCited(
            AgentDefinition definition, String conversationId, Integer turnOrdinal, String answer);
}
