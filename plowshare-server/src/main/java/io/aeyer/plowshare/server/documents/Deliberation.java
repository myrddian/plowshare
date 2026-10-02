package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.llm.accounting.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.JobWatch;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.archive.Origin;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>One document, three agents in series, and an evidence asymmetry that is the
 * whole design.</b>
 *
 * <p>Anchor's {@code AskService.runDeliberation}, and its class comment is the
 * thing being ported:
 *
 * <blockquote>evidence asymmetry is the design. Proposer and synthesiser see the
 * full hierarchy plus the top-K retrieved chunks; critic sees only chapter
 * summaries + doc summary. Giving critic the same evidence as proposer turns it
 * into a paraphrase generator — the asymmetry is what catches macro-vs-local
 * contradictions.</blockquote>
 *
 * <p><b>This is why the ask is per-document and why it is not RAG.</b> The
 * critic's job is to hold a proposed answer against what <em>this document</em>
 * argues as a whole, and "the document as a whole" has no corpus-wide analogue.
 * A {@code document:} filter on {@code document_search} would look like it
 * answered the same question and would not; {@code librarian} answers the
 * corpus-wide one — <em>which paper says anything about X</em> — and both
 * surfaces stay.
 *
 * <h2>Java, and not {@code calls:}</h2>
 *
 * <p>Plowshare lets one agent run another, so {@code calls: [ask_critic]} on the
 * proposer is the first instinct. <b>It inverts the design.</b> {@code
 * AgentRunTool}'s schema is {@code {agent, task}} and its description says the
 * callee <i>"does not see this conversation — so the task has to stand by
 * itself"</i>, which makes the proposer <em>the author of the critic's
 * evidence</em>. A critic reading a context its proposer composed is not a
 * critic. So this is a class in the mould of {@code Curator.pass} and {@link
 * Summariser#summarise}: the prompts are composed here out of database rows, and
 * the three definitions declare {@code tools: []} and {@code calls: []} so that
 * <b>the loader refuses what the orchestrator withholds</b>. Anchor's asymmetry
 * holds because its orchestrator is careful; this one holds because an agent
 * with no tools cannot go and get the evidence it was not given.
 *
 * <h2>What a pass costs, and the fourth call</h2>
 *
 * <p>Three model calls out of one shared {@link Budget}, and a fourth when the
 * critic's JSON does not parse. <b>Anchor discovers that call rather than
 * budgeting it</b> — nothing there counts model calls at all — and its "retry
 * once at temperature 0" is on its own defaults the identical request, since
 * {@code ask.temperatures.critic} is already 0.0. Here the retry is a fourth
 * claim on the pass's allowance and the zero is forced, so the behaviour
 * survives an operator warming {@code ask_critic.md}.
 *
 * <h2>The conversation shape</h2>
 *
 * <p>{@link Summariser}'s exactly, for its reasons. One conversation of origin
 * {@link Origin#SUBMISSION} owns the allowance and is named for {@link
 * #SYNTHESISER}; the proposer, the critic and the critic's retry are {@link
 * Origin#DELEGATION} children of it; and the synthesiser's run <em>is</em> the
 * root's own turn, last, so the tree reads the way the deliberation does — the
 * answer, and under it everything that was argued to reach it. That is §6's
 * divergence from Anchor's three-slot envelope: three conversations under one
 * root are strictly more inspectable than three JSONB columns.
 *
 * <p><b>One thing that shape does not do, named here rather than discovered
 * later.</b> {@code TurnTranscript.closed} is what writes a conversation's
 * spending back onto its row, and the root's is called only when the
 * synthesiser's run finishes — so a pass that stopped earlier leaves the row
 * that granted the allowance saying nothing was spent against it, although the
 * proposer and the critic spent from it. {@link Summariser} has exactly this
 * shape and exactly this hole; it is a property of "the root's own run is the
 * last stage", which is the arrangement V17's constraints permit. Closing it
 * needs a way to close a conversation that never took a turn, which is {@code
 * Compaction}'s to add and would have to move both classes together rather than
 * leaving them disagreeing about what a stopped root records.
 *
 * <h2>Grounding, and the one thing this port has that Anchor cannot</h2>
 *
 * <p>Anchor grounds in arrays of verbatim title strings because it has no stable
 * ids. Here {@code ask_synthesiser} returns a paragraph id and the words the
 * claim rests on, and <b>a quote either occurs in the paragraph it names or it
 * does not</b> — which extends V25's corpus-as-validator one step, so the
 * deliberation's own output is checkable by the mechanism this system applies to
 * everything else. A quote that survives is recorded as a citation; a quote that
 * does not is <em>said in the answer</em> and cited nowhere. V25's rule is that
 * the table under-reports and never mis-reports, and silence would hide exactly
 * the failure this whole apparatus exists to find.
 */
public final class Deliberation implements UsageAware {
    private UsageOwners usageOwners = UsageOwners.NONE;
    public boolean accountingEnabled() { return usageOwners != UsageOwners.NONE; }
    @Override public void useUsageOwners(UsageOwners source) { usageOwners = java.util.Objects.requireNonNull(source); }


    private static final Logger log = LoggerFactory.getLogger(Deliberation.class);

    /** Drafts the answer, from the whole hierarchy and the retrieved passages. */
    public static final String PROPOSER = "ask_proposer";

    /** Holds that draft against the macro view, and sees nothing below it. */
    public static final String CRITIC = "ask_critic";

    /** Writes the final answer, and the agent the root conversation is named
     *  for. */
    public static final String SYNTHESISER = "ask_synthesiser";

    /**
     * The synthesiser's first pass, which produces objections rather than prose.
     *
     * <p>A separate definition and not a second task for {@link #SYNTHESISER},
     * because an agent body here is a standing system prompt pinned byte for
     * byte — one body cannot honestly say both "analyse this draft" and "write
     * the final answer", and a body that said both would be describing neither.
     */
    public static final String REVIEWER = "ask_reviewer";

    /**
     * Anchor's {@code ask.retrieval.top-sections}, and <b>it is used twice for
     * two different things</b>.
     *
     * <p>Once as a factor in how many passages are retrieved, and once — on its
     * own, later, over the passages that came back — as a cap on how many
     * distinct sections get a summary bullet. The two uses are independent and
     * the name fits only the second; it is kept because the number is Anchor's
     * and splitting it would be inventing a second setting nobody has tuned.
     */
    public static final int TOP_SECTIONS = 5;

    /** Anchor's {@code ask.retrieval.top-chunks-per-section}. <b>Nothing groups
     *  by section</b> — see {@link #TOP_PASSAGES}. */
    public static final int TOP_CHUNKS_PER_SECTION = 3;

    /**
     * Fifteen, in <b>one flat vector search over the document</b>.
     *
     * <p>The name of the factor above says "per section" and the search does not
     * honour it: Anchor multiplies the two and asks for that many chunks in a
     * single query with no grouping, no per-section quota and no re-ranking. So
     * fifteen passages from one section is a legal and ordinary answer. Ported
     * as it is, with the arithmetic named, because the alternative is inventing
     * a distribution Anchor's corpus never validated.
     */
    public static final int TOP_PASSAGES = TOP_SECTIONS * TOP_CHUNKS_PER_SECTION;

    /**
     * What one whole pass can cost: three stages and the critic's one retry.
     *
     * <p>The number an allowance has to reach for a deliberation to be able to
     * finish in its worst case. <b>Five since the synthesiser became two
     * passes</b> — objections, then the answer — which is one call more than
     * Anchor's three plus the critic's retry. It is a fixed cost, unlike an ingest's, because
     * a deliberation has no per-paragraph dimension: it is three calls over one
     * document however long the document is.
     */
    public static final int A_PASS = 5;

    /**
     * What a passage in no section is marked as.
     *
     * <p><b>Not {@link StructuralRef#UNNAMED}, and the difference is the one
     * {@code DocumentStore.Attribution} refuses to fold.</b> {@code (unnamed
     * segment)} means the parser invented this unit and it has no name; this
     * means there is no unit — {@code paragraphs.section_id} is null, which V18's
     * extension clause made legal and which means <em>derived before the
     * hierarchy existed</em> or <em>detached mid-re-ingest</em>. Anchor cannot
     * reach this state at all: its paragraphs hang off sections, so it has no
     * word for it and its chunk block would simply lose the row.
     */
    static final String IN_NO_SECTION = "(in no section)";

    /** What the synthesiser is shown when the critic raised none. Anchor's
     *  string. */
    private static final String NO_CHALLENGES = "(no challenges raised)";

    /** What the synthesiser is shown when the review pass could not be run.
     *  Spelled as an absence rather than as an empty block, so the difference
     *  between "nothing was objected to" and "nobody looked" survives into the
     *  prompt. */
    private static final String NO_OBJECTIONS =
            "(the review pass could not be run, so nothing was objected to)";

    /**
     * Whitespace, <b>including the kinds Java's {@code \s} is not</b>.
     *
     * <p>{@code \s} is {@code [ \t\n\x0B\f\r]} and nothing else, so it does not
     * match U+00A0, U+2007, U+202F or the thin spaces a PDF or a DOCX routinely
     * leaves in {@code paragraphs.text} — nothing in this package normalises
     * them at ingest, and {@code String.strip()} does not remove them either. A
     * model correctly quoting a paragraph that contains one will render it as an
     * ordinary space, and with an ASCII-only pattern that comes back as a
     * <em>failed attribution</em>: the loudest signal this system produces,
     * fired on a character nobody reading the answer can see. {@code \p{Z}}
     * covers the separator categories, which is the whole of what the flatten is
     * for.
     */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final String QUOTE = "> ";

    /**
     * <b>Global, and it is V18's decision restated.</b> "A memory has exactly one
     * home; a document has none." A corpus is reachable from every project, so
     * an ask over one belongs to no project — and {@link Home#global()} is the
     * absence of a project rather than a project named "global".
     */
    private Home home = Home.global();
    private String owner;
    private String callerSession;
    private String parentLog;
    private io.aeyer.plowshare.server.information.InformationJobs inputs;
    private io.aeyer.plowshare.server.information.InformationContext inputContext;

    private final DocumentStore store;
    private final RetrievalService retrieval;
    private final CitationStore citations;
    private final JobRuntime runtime;
    private DocumentStages stages=DocumentStages.NONE;
    public void useStages(DocumentStages stages) { this.stages=stages; }
    private final java.util.function.Supplier<AgentRegistry> agents;
    private final Clock clock;

    /** Where the pass's conversations are opened, or null for a pass that keeps
     *  no log. {@code Summariser.logs}' shape and its note: nullable, and the
     *  null is a fixture rather than a deployment. */
    private final Compaction logs;

    public Deliberation(
            DocumentStore store, RetrievalService retrieval, CitationStore citations,
            JobRuntime runtime, java.util.function.Supplier<AgentRegistry> agents, Clock clock) {
        this(store, retrieval, citations, runtime, agents, clock, null);
    }

    public Deliberation(
            DocumentStore store, RetrievalService retrieval, CitationStore citations,
            JobRuntime runtime, java.util.function.Supplier<AgentRegistry> agents, Clock clock,
            Compaction logs) {
        this.store = Objects.requireNonNull(store, "store");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.citations = Objects.requireNonNull(citations, "citations");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logs = logs;
    }

    public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) { this.inputs = inputs; }

    public Deliberation scoped(io.aeyer.plowshare.server.information.InformationAccess access,
            io.aeyer.plowshare.server.information.InformationContext context, String session, String parent) {
        Deliberation copy = scoped(access, context, session);
        copy.parentLog = parent;
        return copy;
    }

    public Deliberation scoped(io.aeyer.plowshare.server.information.InformationAccess access,
            io.aeyer.plowshare.server.information.InformationContext context, String session) {
        Deliberation scoped = new Deliberation(store.scoped(access, context),
                retrieval.scoped(access, context), citations.scoped(access, context),
                runtime, agents, clock, logs);
        scoped.home = context.selection().project() == null ? Home.global()
                : Home.of(context.selection().project());
        scoped.owner = context.account();
        scoped.stages=stages;
        scoped.usageOwners=usageOwners;
        scoped.callerSession = session;
        scoped.inputs = inputs;
        scoped.inputContext = context;
        return scoped;
    }

    /**
     * Ask one document one question, to an ending.
     *
     * <p>Blocking, on the caller's thread — {@code Curator.pass}' and {@link
     * Summariser#summarise}'s position and for their reason: this class owns no
     * executor, so a caller that wants a deliberation on a virtual thread starts
     * one.
     *
     * @param documentId the document to ask. It must be summarised: the critic's
     *     entire evidence is the summaries, and an ask over an unsummarised
     *     document would run a critic against empty prompts and call whatever
     *     came back a macro view
     * @param question what the reader wants to know, in prose
     * @param budget the model calls this whole pass may spend across all three
     *     stages and the critic's retry. Shared by reference; running out of it
     *     is an ending of the pass and not of one stage
     * @param cancelled asked at every stage boundary and handed to each run, so
     *     one cancelled mid-turn stops at its own boundary
     */
    public Outcome ask(
            UUID documentId, String question, Budget budget, BooleanSupplier cancelled) {
        return ask(documentId, question, budget, cancelled, owner);
    }

    public Outcome ask(
            UUID documentId, String question, Budget budget, BooleanSupplier cancelled, String account) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(cancelled, "cancelled");
        if (question.isBlank()) {
            throw new IllegalArgumentException(
                    "an ask needs a question; this one is blank. Nothing was deliberated, which"
                            + " is not the same as a document having no answer");
        }

        AgentRegistry registry = agents.get();
        if (registry == null) {
            return unavailable("this server has no agent registry, so there is nothing to"
                    + " deliberate with");
        }
        for (String name : List.of(PROPOSER, CRITIC, SYNTHESISER)) {
            if (!registry.names().contains(name)) {
                // By name rather than through AgentRegistry.get, which throws
                // for a miss. Summariser's shape and its reason: a boot wired
                // over a directory missing one of these is a different fault
                // from a boot with no registry, and one sentence for both would
                // leave a wiring test green over a wiring that had changed.
                return unavailable("this server defines no agent named '" + name + "', and a"
                        + " deliberation missing one of its three stages is not a shorter"
                        + " deliberation");
            }
        }

        Optional<DocumentStore.StoredDocument> found = store.find(documentId);
        if (found.isEmpty()) {
            return unavailable("the corpus holds no document with the id " + documentId);
        }
        DocumentStore.StoredDocument document = found.get();
        // The title and never a fallback: V18 froze `documents.title NOT NULL`,
        // so a null here is a state the schema does not have and a branch for it
        // would be describing one.
        String named = document.title();

        Outcome refused = whyItCannotBeAsked(document, named);
        if (refused != null) {
            return refused;
        }

        List<DocumentStore.Passage> passages;
        try {
            passages = account == null ? retrieval.within(documentId, question, TOP_PASSAGES)
                    : retrieval.within(documentId, question, TOP_PASSAGES, usageOwners.in(home,account,UsageAttribution.Operation.EMBEDDING_QUERY));
        } catch (RuntimeException notRetrieved) {
            // CAUGHT, AND THE REASON IS WHAT THIS METHOD PROMISES. `ask` runs to
            // an ENDING; an exception out of it reaches JobStore's last-resort
            // catch, which files the job UNAVAILABLE saying "the job runtime
            // itself failed. Nothing can be concluded from it" and reports zero
            // calls. That sentence is true of a bug in the runtime and false of
            // an embedding endpoint that is down, which is an ordinary state
            // this class has a sentence for. The block below narrates the
            // corpus's *stored* half of the same failure -- a document nothing
            // embedded -- and leaving the live half to a stack trace would say
            // the two apart for no reason.
            return unavailable("'" + named + "' could not be searched, because the question"
                    + " could not be turned into a vector: " + describe(notRetrieved)
                    + ". Nothing was retrieved, which is not the same as nothing being close");
        }
        if (passages.isEmpty()) {
            // The per-document counterpart of `coverage`, and the duty
            // `DocumentStore.searchWithinDocument` names: an ask over a document
            // stored while the embedding endpoint was down finds nothing in a
            // document holding every word of its text, and answering "nothing
            // close" would be a claim about the question rather than about this
            // server.
            int unembedded = store.countUnembedded(documentId);
            if (unembedded > 0) {
                return unavailable("'" + named + "' holds " + unembedded + " passage(s) that no"
                        + " search can reach, because nothing has embedded them. Nothing was"
                        + " retrieved, which is not the same as nothing being close");
            }
        }

        List<DocumentStore.StoredChapter> hierarchy = store.hierarchy(documentId);
        if (hierarchy.stream().noneMatch(chapter -> chapter.summary() != null)) {
            // THE THIRD SHAPE OF THE SAME REFUSAL, and the one the two counts
            // above cannot see. `unsummarisedUnits` counts units that EXIST and
            // are owed a summary; a document with no chapter row at all -- one
            // ingested before V26 and never re-ingested -- is owed nothing and
            // answers zero. The critic would then be handed a heading with no
            // bullets under it and would be agreeing with a proposer from the
            // document's one-sentence summary, which is not a macro view.
            return unavailable("'" + named + "' has no summarised " + top(document)
                    + " at all, so the critic would hold nothing but one sentence and its"
                    + " agreement with a proposed answer would mean nothing. Ingesting it again"
                    + " derives the hierarchy and summarises it");
        }
        Evidence evidence = new Evidence(document, named, passages, hierarchy,
                store.references(documentId));

        Tally tally = new Tally(named);
        AgentDefinition proposer = registry.get(PROPOSER);
        AgentDefinition critic = registry.get(CRITIC);
        AgentDefinition reviewer = registry.get(REVIEWER);
        AgentDefinition synthesiser = registry.get(SYNTHESISER);
        Transcript root = logs == null
                ? Transcript.NONE
                : logs.logFor(parentLog == null ? Origin.SUBMISSION : Origin.DELEGATION, home, synthesiser, parentLog,
                        parentLog == null ? budget : null,
                        io.aeyer.plowshare.server.agents.Speaker.harness(), account == null ? owner : account, null, callerSession);
        if (logs != null && logs.accountingEnabled()) { root = logs.own(home, root, synthesiser, account == null ? owner : account); }

        if (inputs != null) {
            if (root.conversationId() == null || inputContext == null) throw new IllegalStateException("a document pass needs an owned input log");
            inputs.bind(root.conversationId(), inputContext, List.of(documentId));
        }
        if (cancelled.getAsBoolean()) {
            return tally.stopped(Ending.CANCELLED, "This deliberation was cancelled.", "");
        }
        Answer proposed = run(documentId,proposer, evidence.forTheProposer(question),
                root.delegate(proposer, home), budget, cancelled, tally);
        if (proposed.stopped() != null) {
            return proposed.stopped();
        }

        Challenged challenged = critique(critic, evidence, question, proposed.text(), root,
                budget, cancelled, tally);
        if (challenged.stopped() != null) {
            return challenged.stopped();
        }

        if (cancelled.getAsBoolean()) {
            return tally.stopped(Ending.CANCELLED, "This deliberation was cancelled.", "");
        }
        // IN THE ROOT ITSELF and not in a child of it, on Summariser's
        // reasoning: the root conversation is named for this agent and owns the
        // allowance, so the final answer is the run that conversation IS. A
        // delegation here would be a parent that never spoke, holding an
        // allowance for a run it did not make.
        // PASS ONE: OBJECTIONS, NOT PROSE. Delegated under the root, like the
        // proposer and the critic; the root itself is the run that answers.
        Answer reviewed = run(documentId,reviewer,
                evidence.forTheReview(question, proposed.text(), challenged.formatted()),
                root.delegate(reviewer, home), budget, cancelled, tally);
        String objections = null;
        if (reviewed.stopped() != null) {
            // A REVIEWER THAT FAILED IS NOT FATAL, AND MAKING IT FATAL WAS A
            // MISTAKE THIS CORRECTS.
            //
            // `critique` has carried Anchor's rule from the start -- "Critic
            // failure isn't fatal: synthesiser can proceed with no challenges"
            // -- and this stage was added without it. Measured on
            // arXiv:1504.04279 the reviewer ran past the 600s stream cap and
            // took the WHOLE deliberation down as UNAVAILABLE, discarding a
            // proposer and a critic that had both succeeded. A refinement of the
            // synthesiser is not a precondition for it: without objections this
            // is exactly the one-shot pass that ran before the split, which is a
            // worse answer and still an answer.
            //
            // The two endings that do stop are the two that are not this stage's
            // failure: a spent allowance and a cancellation are the pass ending,
            // and carrying on would start a synthesiser with nothing to spend.
            Ending ending = reviewed.stopped().ending();
            if (ending == Ending.CALL_BUDGET || ending == Ending.CANCELLED || reviewed.stopped().detail().equals("document.stage.denied")) {
                return reviewed.stopped();
            }
        } else {
            objections = reviewed.text();
        }
        if (cancelled.getAsBoolean()) {
            return tally.stopped(Ending.CANCELLED, "This deliberation was cancelled.", "");
        }
        Answer synthesised = run(documentId,synthesiser,
                evidence.forTheSynthesiser(question, proposed.text(), challenged.formatted(),
                        objections == null ? NO_OBJECTIONS : objections),
                root, budget, cancelled, tally);
        if (synthesised.stopped() != null) {
            return synthesised.stopped();
        }

        SynthesiserOutput answer = SynthesiserOutput.of(synthesised.text());
        if (answer.response().isBlank()) {
            // A NON-BLANK RUN WITH A BLANK ANSWER, which `run` cannot see and
            // this can. A synthesiser that emits `GROUNDING: {...}` and nothing
            // before it has answered the machine and not the reader, and the
            // prose is the only half a person gets -- so the alternative is an
            // ANSWERED outcome whose text opens with a grounding block, which is
            // a stopped stage dressed as an answer by a different route than the
            // one `Outcome` warns about.
            return tally.stopped(Ending.STUCK,
                    "The " + synthesiser.name() + " emitted a grounding block and no answer, so"
                            + " there is nothing here for a reader.",
                    synthesiser.name() + ": no prose before the grounding block");
        }
        if (!challenged.challenges().isEmpty()
                && flattened(answer.response()).equals(flattened(proposed.text()))) {
            // THE DRAFT BACK, WORD FOR WORD, WITH CHALLENGES AGAINST IT.
            //
            // Measured 2026-09-06 over twenty deliberations: two of the eight
            // that had live challenges returned the proposer's prose unchanged,
            // and ONE OF THOSE REPORTED INCORPORATING ALL THREE. That is the
            // hole in the reconciliation below it -- `unruled` checks that every
            // challenge was ACCOUNTED for, and an answer can satisfy it by
            // listing each one as incorporated while changing nothing.
            // Accounting is not effect, and only this comparison can tell them
            // apart.
            //
            // STOPPED rather than reported, on the reasoning that a blank
            // response already uses: what a reader would get is a fluent answer
            // whose deliberation block says challenges were raised and taken up,
            // over prose that no challenge touched. That is a claim about
            // process, and it is false. An unchallenged draft returned unchanged
            // is fine and is not this branch.
            return tally.stopped(Ending.STUCK,
                    "The " + synthesiser.name() + " returned the proposer's draft word for word"
                            + " with " + challenged.challenges().size() + " challenge(s) against"
                            + " it, so nothing adjudicated them.",
                    synthesiser.name() + ": the draft unchanged under challenge");
        }
        Attributions attributed = check(documentId, answer);
        return tally.answered(answer, attributed, challenged,
                record(documentId, attributed, root));
    }

    /**
     * The two states of a document that make an ask impossible, asked before a
     * model is called.
     *
     * <p><b>Both are about the critic.</b> Its whole evidence is {@code
     * documents.summary} and the top-level summaries, so a document missing
     * either does not produce a weaker critic — it produces one holding nothing,
     * whose agreement with the proposer means nothing at all.
     *
     * @return the refusal, or null for a document that can be asked
     */
    private Outcome whyItCannotBeAsked(DocumentStore.StoredDocument document, String named) {
        // THE HOLE THE SUMMARIES CANNOT SHOW, and it is asked here as well as in
        // the cascade rather than inherited from it. `Summariser` refuses to
        // write a document summary over a labelled paragraph in no section — but
        // that guard ran when the cascade ran, and a re-ingest since then
        // detaches paragraphs (`ON DELETE SET NULL`, which is what lets the
        // identity rule run over rows that are still there) without touching a
        // summary the identity rule preserved. So the cascade having passed says
        // nothing about now.
        //
        // NOTE WHAT THIS PREDICATE DOES NOT COVER, since it is narrower than
        // "this document has a hole": it counts only orphans that already hold a
        // summary. An orphan the cascade never reached is invisible to it, its
        // chunks are still retrieved, and IN_NO_SECTION is what says so at the
        // one place it can still be seen.
        int orphans = store.summarisedParagraphsInNoSection(document.id());
        if (orphans > 0) {
            return new Outcome(Ending.STUCK,
                    orphans + " labelled paragraph(s) of '" + named + "' are in no section, so"
                            + " the summaries this deliberation reads are summaries of a"
                            + " document with those paragraphs missing — and the critic, which"
                            + " sees nothing but summaries, could not tell. Ingesting it again"
                            + " derives the hierarchy over the whole of it.",
                    0, 0, "");
        }
        if (document.summary() == null) {
            return unavailable("'" + named + "' has no summary of its own, so the critic would"
                    + " be given nothing to hold a proposed answer against and its agreement"
                    + " would mean nothing. Ingesting it again summarises it");
        }
        int owed = store.unsummarisedUnits(document.id());
        if (owed > 0) {
            return unavailable("'" + named + "' has " + owed + " chapter(s) or section(s) that"
                    + " hold labelled paragraphs and no summary of their own, which is the"
                    + " state a re-ingest leaves: the hierarchy is replaced wholesale and the"
                    + " paragraph summaries survive it. Those summaries are the whole of what"
                    + " the critic reads. Ingesting it again writes them");
        }
        return null;
    }

    // --- the critic, and the one stage that is allowed to fail -----------------

    /** What the critic came to: the challenges, how they are rendered for the
     *  synthesiser, and the account of anything that went wrong. */
    private record Challenged(
            List<String> challenges, String formatted, String support, String note,
            Outcome stopped) {}

    /**
     * Run the critic, retrying once at temperature zero if its JSON does not
     * parse.
     *
     * <p><b>A critic that fails is not fatal, and that is Anchor's decision
     * ported rather than a lenience invented here</b> — <i>"Critic failure isn't
     * fatal — synthesiser can proceed with no challenges"</i>. The synthesiser
     * holds the same evidence as the proposer plus the draft, so what a missing
     * critic costs is the challenge, not the answer.
     *
     * <p><b>Two endings are exceptions, and they are not the critic's.</b> A run
     * that ended at the shared allowance or at a cancellation did not fail as a
     * stage; the <em>pass</em> ended, and carrying on would be starting a
     * synthesiser that has nothing left to spend, so those stop.
     */
    private Challenged critique(
            AgentDefinition critic, Evidence evidence, String question, String proposed,
            Transcript root, Budget budget, BooleanSupplier cancelled, Tally tally) {

        if (cancelled.getAsBoolean()) {
            return new Challenged(List.of(), NO_CHALLENGES, CriticOutput.UNKNOWN, null,
                    tally.stopped(Ending.CANCELLED, "This deliberation was cancelled.", ""));
        }
        String task = evidence.forTheCritic(question, proposed);
        Answer first = run(evidence.document.id(),critic, task, root.delegate(critic, home), budget, cancelled, tally);
        if (first.stopped() != null) {
            Ending ending = first.stopped().ending();
            if (ending == Ending.CALL_BUDGET || ending == Ending.CANCELLED || first.stopped().detail().equals("document.stage.denied")) {
                return new Challenged(List.of(), NO_CHALLENGES, CriticOutput.UNKNOWN, null,
                        first.stopped());
            }
            return notCritiqued("the critic could not be run (" + ending + ")");
        }
        Optional<CriticOutput> read = CriticOutput.of(first.text());
        if (read.isEmpty()) {
            log.warn("the critic of '{}' did not answer in JSON; retrying once, sampled as its"
                    + " own definition says", evidence.named);
            // THE RETRY NO LONGER FORCES ZERO, AND THAT IS A CORRECTION RATHER
            // THAN A SIMPLIFICATION.
            //
            // Anchor's `parseCriticOrRetry` logs "retrying once at temperature
            // 0" and calls the endpoint at a hardcoded 0.0 -- and on Anchor's
            // own defaults that is the identical request, because
            // `ask.temperatures.critic` is already 0.0, so the number in that
            // log line has never changed anything there. This port copied the
            // behaviour faithfully and thereby inherited a hazard Anchor never
            // had: here the critic's sampling is resolved per model, so forcing
            // zero meant the ONE call made because the model had already failed
            // to produce parseable output was the one call made greedily.
            //
            // Measured on 2026-09-04: greedy decoding on this project's loaded
            // model family produced 3 997 reasoning tokens and EMPTY CONTENT,
            // deterministically, twice over -- so the retry was reaching for the
            // configuration likeliest to return nothing at all, precisely when
            // something had already gone wrong. A retry that samples differently
            // from the call it is retrying has to justify the difference, and
            // this one could not.
            //
            // So it is one more run of the same agent, sampled the way its
            // definition says. What makes the retry worth anything is that
            // sampling is no longer greedy: a second draw from a real
            // distribution can differ, which is the entire mechanism a retry
            // depends on and the one a temperature of zero removes.
            Answer second =
                    run(evidence.document.id(),critic, task, root.delegate(critic, home), budget, cancelled, tally);
            if (second.stopped() != null) {
                Ending ending = second.stopped().ending();
                if (ending == Ending.CALL_BUDGET || ending == Ending.CANCELLED || second.stopped().detail().equals("document.stage.denied")) {
                    return new Challenged(List.of(), NO_CHALLENGES, CriticOutput.UNKNOWN, null,
                            second.stopped());
                }
                return notCritiqued("the critic could not be run again (" + ending + ")");
            }
            read = CriticOutput.of(second.text());
            if (read.isEmpty()) {
                return notCritiqued("the critic's answer could not be read, twice over");
            }
        }
        CriticOutput critique = read.get();
        return new Challenged(critique.challenges(), formatted(critique.challenges()),
                critique.support(), null, null);
    }

    private static Challenged notCritiqued(String why) {
        return new Challenged(List.of(), NO_CHALLENGES, CriticOutput.UNKNOWN, why, null);
    }

    // --- grounding -------------------------------------------------------------

    /** What the answer claimed, checked. */
    private record Attributions(
            List<SynthesiserOutput.Grounded> held, List<SynthesiserOutput.Grounded> failed,
            String unreadable) {}

    /**
     * Ask the corpus whether each quotation is where the answer says it is.
     *
     * <p><b>Normalised whitespace and not bytes.</b> A paragraph's stored text is
     * re-flowed from the wrapping of whatever it was extracted from, so a model
     * quoting it correctly will not reproduce the internal spacing; anything
     * stricter fails on quotes that are right.
     *
     * <p><b>Case is normalised too, and the design document names only the
     * whitespace.</b> This follows its stated principle rather than its list:
     * <i>"anything stricter fails on correct quotes"</i>. Ask what a case-only
     * difference can be. A model that writes {@code "the bound is tight"} where
     * the paragraph opens {@code "The bound is tight"} has quoted the paragraph;
     * the words are the same words and the attribution is the right one. And ask
     * what folding case can let through: a fabrication whose words differ from
     * the document's <em>only</em> in case is not a fabrication — it is the
     * document's words. So the check loses no detection and gains the quotes
     * that start a sentence, which is a large fraction of the quotes anybody
     * would pick.
     *
     * <p>That matters more here than the arithmetic suggests, because of what a
     * failure <em>is</em> on this path. A failed attribution is the loudest
     * signal this system produces — it is put in front of the reader beside the
     * answer, saying a claim may rest on words the document does not contain —
     * and a signal that fires on a capital letter is one a reader learns to skip.
     * {@link java.util.Locale#ROOT} rather than the default, on {@code
     * DocumentTools.similarity}'s correction: a box set to a Turkish locale
     * would fold {@code I} to a dotless {@code ı} and refuse quotes for a reason
     * nobody reading the answer can see.
     *
     * <p><b>The document is part of the check.</b> {@code paragraphTextIn} takes
     * both ids, so a quotation attributed to a paragraph of some other paper
     * fails — and nothing else here would catch it, because {@code
     * CitationStore.record} validates against the corpus, which that paragraph
     * really is in.
     */
    private Attributions check(UUID documentId, SynthesiserOutput answer) {
        List<SynthesiserOutput.Grounded> held = new ArrayList<>();
        List<SynthesiserOutput.Grounded> failed = new ArrayList<>();
        for (SynthesiserOutput.Grounded claim : answer.grounding()) {
            String text = store.paragraphTextIn(documentId, claim.paragraph());
            if (text != null && flattened(text).contains(flattened(claim.quote()))) {
                held.add(claim);
            } else {
                failed.add(claim);
            }
        }
        return new Attributions(held, failed, answer.groundingWasUnreadable().orElse(null));
    }

    /**
     * Write down the paragraphs whose quotation held.
     *
     * <p><b>Here rather than through {@link io.aeyer.plowshare.server.agents.Citing},
     * and that seam is right to refuse this.</b> {@code AgentDefinition.canCite}
     * asks whether the corpus was <em>granted</em> to the agent, and it was not:
     * {@code ask_synthesiser} declares {@code tools: []} and was handed its
     * passages by this class. Widening that predicate would make every agent
     * holding no tools a citer. What is written here is also narrower than what
     * that seam writes: not every uuid in the prose, but the ones whose
     * quotation was checked against the paragraph.
     *
     * <p>Deduplicated in first-mention order, on {@code Citations}' rule: an
     * answer citing one paragraph for three claims is one citation.
     *
     * @return how many rows were actually written. <b>Not the number of
     *     attributions</b>, and the answer reports this one: three claims on one
     *     paragraph are one row, and a row the corpus refused is no row. V25's
     *     rule is that the table under-reports and never mis-reports, and an
     *     answer that counted intentions would make the sentence about the table
     *     do the opposite
     */
    private int record(UUID documentId, Attributions attributed, Transcript root) {
        LinkedHashSet<UUID> once = new LinkedHashSet<>();
        attributed.held().forEach(claim -> once.add(claim.paragraph()));
        int written = 0;
        for (UUID paragraph : once) {
            try {
                // ASKED INSIDE THE TRY AND ONCE PER ROW, not hoisted above the
                // loop. `spokenIn` reaches the database on a transcript whose
                // `before()` could not read its own turns -- Compaction guards
                // the same call in `closed` for the same reason -- so hoisting
                // it would put a throw on the one line of this method that is
                // not covered by the catch, and lose a finished answer that
                // three model calls have already been paid for.
                Transcript.Spoken spoken = root.spokenIn();
                if (citations.record(paragraph,
                        spoken == null ? null : spoken.conversationId(),
                        spoken == null ? null : spoken.turnOrdinal(),
                        SYNTHESISER, clock.instant())) {
                    written++;
                }
            } catch (RuntimeException notWritten) {
                // Citing's rule, which this class is standing in for: a corpus
                // that could not be written to costs the record of a citation
                // and must not cost the answer.
                log.warn("the citation of paragraph {} in a deliberation over document {} could"
                                + " not be written down. Reason: {}",
                        paragraph, documentId, describe(notWritten));
            }
        }
        return written;
    }

    // --- one run ---------------------------------------------------------------

    /** One stage's answer, or the ending the whole pass takes from it. */
    private record Answer(String text, Outcome stopped) {}

    private Answer run(
            UUID documentId,AgentDefinition stage, String task, Transcript conversation, Budget budget,
            BooleanSupplier cancelled, Tally tally) {

        Outcome ran;
        try {
            ran = stages.forDocument(documentId).run(stage,task,conversation.conversationId(),home,owner,stageTask -> runtime.run(stage, stageTask, home, budget, cancelled, null,
                    JobWatch.UNWATCHED, conversation));
        } catch(DocumentStages.Blocked blocked) {
            tally.calls+=blocked.modelCalls();
            Outcome stopped=tally.stopped(Ending.UNAVAILABLE,blocked.getMessage(),"document.stage.denied");
            conversation.closed(task,new Outcome(Ending.UNAVAILABLE,blocked.getMessage(),0,blocked.modelCalls(),"document.stage.denied"));
            return new Answer(null,stopped);
        }
        conversation.closed(task, ran);
        tally.calls += ran.modelCalls();

        if (ran.ending() != Ending.ANSWERED && ran.ending() != Ending.CALL_BUDGET
                && ran.ending() != Ending.CANCELLED) {
            // NAMED AS A STAGE AND NOT AS THE PASS, which is the whole of why it
            // is here rather than in Tally.stopped: a critic that failed is not
            // fatal, so this line has to be true of a deliberation that goes on
            // to answer. Budget and cancellation are left out because both are
            // ordinary endings somebody asked for.
            log.warn("the {} of the deliberation over '{}' stopped {} after {} model call(s)"
                            + " of its own: {}",
                    stage.name(), tally.title, ran.ending(), ran.modelCalls(), ran.detail());
        }
        if (ran.ending() == Ending.ANSWERED) {
            if (ran.text().isBlank()) {
                return new Answer(null, tally.stopped(Ending.STUCK,
                        "The " + stage.name() + " answered with nothing, and a deliberation"
                                + " built on an empty stage is a deliberation that did not"
                                + " happen.",
                        stage.name() + ": an empty answer"));
            }
            return new Answer(ran.text().strip(), null);
        }
        return new Answer(null, switch (ran.ending()) {
            case CALL_BUDGET -> tally.stopped(Ending.CALL_BUDGET,
                    "This deliberation stopped after spending its whole allowance of "
                            + budget.limit() + " model calls.", "");
            case CANCELLED -> tally.stopped(Ending.CANCELLED,
                    "This deliberation was cancelled part-way through the " + stage.name() + ".",
                    "");
            case TURN_CAP -> tally.stopped(Ending.TURN_CAP,
                    "The " + stage.name() + " ran out of turns before it answered.",
                    stage.name() + ": " + ran.detail());
            // Not reachable today: AWAITING is spent only by an orchestration's
            // conductor, and a deliberation stage is never one. Its own arm
            // rather than falling to default, so the sentence names what
            // actually happened instead of a dead endpoint that was never the
            // cause.
            case AWAITING -> tally.stopped(ran.ending(),
                    "This deliberation could not go on: the " + stage.name() + " ended waiting"
                            + " for an answer, which a deliberation run never asks for.",
                    stage.name() + ": " + ran.detail());
            default -> tally.stopped(ran.ending(),
                    "This deliberation could not go on: the " + stage.name() + " stopped"
                            + " because something it depends on could not be reached.",
                    stage.name() + ": " + ran.detail());
        });
    }

    // --- the evidence, and who gets which of it --------------------------------

    /**
     * Everything the three stages are composed from, and <b>the one place the
     * asymmetry is a fact about code rather than about care</b>: {@link
     * #forTheCritic} does not call the two methods that render sections and
     * passages, and there is nothing else in this class that could.
     */
    private final class Evidence {

        private final DocumentStore.StoredDocument document;
        private final String named;
        private final List<DocumentStore.Passage> passages;
        private final List<DocumentStore.StoredChapter> hierarchy;
        private final List<DocumentStore.StoredReference> references;

        private Evidence(
                DocumentStore.StoredDocument document, String named,
                List<DocumentStore.Passage> passages,
                List<DocumentStore.StoredChapter> hierarchy,
                List<DocumentStore.StoredReference> references) {
            this.document = document;
            this.named = named;
            this.passages = passages;
            this.hierarchy = hierarchy;
            this.references = references;
        }

        private String forTheProposer(String question) {
            return whatYouAre()
                    + top()
                    + midLevel()
                    + passages()
                    + "\nQUESTION: " + oneLine(question) + "\n";
        }

        /**
         * <b>The macro view, and it is defined by what is missing.</b>
         *
         * <p>The document's identity facts, its own summary, its bibliography
         * and every top-level summary — and no section summary and no passage.
         * Anchor's {@code runCritic} says why the first four stay:
         *
         * <blockquote>Critic gets authors + citations too. They're
         * document-level identity facts (not mid-level structure) and don't
         * break the macro-only contract — they let the critic verify "this
         * proposer claim names an author who isn't actually on this document" or
         * similar.</blockquote>
         */
        private String forTheCritic(String question, String proposed) {
            return whatYouAre()
                    + top()
                    + "\nREADER'S QUESTION: " + oneLine(question) + "\n"
                    + "\nTHE PROPOSED ANSWER:\n" + quoted(proposed) + "\n";
        }

        /**
         * The first of the synthesiser's two passes: everything, and a request
         * for objections rather than for an answer.
         *
         * <p><b>The one-shot prompt is what made copying the draft the cheapest
         * move.</b> A complete, answer-shaped draft arrived 79% of the way into
         * 21 478 characters under an instruction to write the final answer, and
         * measured over twenty deliberations two of the eight with live
         * challenges returned that draft word for word — one of them while
         * reporting it had incorporated all three challenges.
         *
         * <p>Asking for objections first removes the copy from the set of
         * answers to the question being asked. Nothing here is withheld from the
         * second pass; what changes is that the model has written its own
         * objections before it is asked for prose.
         */
        private String forTheReview(String question, String proposed, String challenges) {
            return "THE READER'S QUESTION: " + oneLine(question) + "\n\n"
                    + whatYouAre()
                    + top()
                    + midLevel()
                    + passages()
                    + "\nTHE PROPOSER'S DRAFT:\n" + quoted(proposed) + "\n"
                    + "\nTHE CRITIC'S CHALLENGES:\n" + quoted(challenges) + "\n"
                    + "\nTHE READER'S QUESTION, AGAIN: " + oneLine(question) + "\n";
        }

        /**
         * The second pass: the same evidence, the objections the first pass
         * raised, and the question in the position that matters.
         *
         * <p><b>The question is first and last.</b> It was 118 characters at 78%
         * of the way in — the one thing the answer must be about, the smallest
         * element in the prompt, wedged between the passages and the draft.
         * Repeating it costs nothing and puts it where a model reads last.
         */
        private String forTheSynthesiser(String question, String proposed, String challenges,
                String objections) {
            return "THE READER'S QUESTION: " + oneLine(question) + "\n\n"
                    + whatYouAre()
                    + top()
                    + midLevel()
                    + passages()
                    + "\nTHE PROPOSER'S DRAFT:\n" + quoted(proposed) + "\n"
                    + "\nTHE CRITIC'S CHALLENGES:\n" + quoted(challenges) + "\n"
                    + "\nTHESE ARE THE OBJECTIONS. FIX THEM:\n" + quoted(objections) + "\n"
                    + "\nTHE READER'S QUESTION, AGAIN: " + oneLine(question) + "\n";
        }

        /**
         * The document's identity, and <b>the vocabulary substitution that
         * replaces Anchor's seven placeholders</b>.
         *
         * <p>Anchor substitutes {@code {structural_top}}, {@code
         * {Structural_Mid_Plural}} and five more into its three prompt
         * <em>files</em>. An agent body is not a file a caller may rewrite: it
         * is the standing system prompt, byte-for-byte pinned by {@code
         * ModelSurfaceTest}, and a per-document substitution into it would be a
         * different agent for every paper. So the bodies carry the rule — "use
         * the words you were told and only those" — and the words themselves
         * arrive here, in the task, which is the half that varies. The failure
         * being prevented is unchanged and is Anchor's own:
         *
         * <blockquote>the model was correctly citing our internal labels but
         * those labels contradicted the document's own self-references</blockquote>
         *
         * <p><b>{@code YOUR AUTHOR(S)} is {@code (unknown)} on every document
         * this server holds, and that is honest rather than a stub.</b> Anchor
         * keeps authors in {@code documents.metadata}, extracted by a model call
         * over the first 2000 characters; V26 deliberately declined the column
         * — "nothing on this server can extract an author" — so the slot renders
         * exactly what Anchor renders for a document whose metadata has none.
         * The instruction above it in the bodies is what makes it worth keeping
         * the slot: the day the column lands, nothing here changes.
         */
        private String whatYouAre() {
            Vocabulary vocabulary =
                    document.vocabulary() == null ? Vocabulary.SECTION : document.vocabulary();
            return "You are the document filed as \"" + oneLine(document.sourceName())
                    + "\". It calls its own top-level parts " + vocabulary.plural()
                    + " and the parts below them " + vocabulary.midLevelPlural() + "; those are"
                    + " the only words for them.\n"
                    + "\nYOUR TITLE: " + oneLine(nullSafe(document.title())) + "\n"
                    + "YOUR AUTHOR(S): (unknown)\n"
                    + "\nYOUR OVERALL SUMMARY:\n" + quoted(nullSafe(document.summary())) + "\n"
                    + "\nYOUR CITED REFERENCES:\n" + bibliography() + "\n";
        }

        /**
         * <b>Every</b> top-level unit with its summary, in document order — field
         * 5, and the only field all three stages share below the document level.
         */
        private String top() {
            Vocabulary vocabulary =
                    document.vocabulary() == null ? Vocabulary.SECTION : document.vocabulary();
            StringBuilder block =
                    new StringBuilder("\nYOUR " + upper(vocabulary.plural()) + ":\n");
            for (DocumentStore.StoredChapter chapter : hierarchy) {
                block.append(bullet(chapter.title(), chapter.summary()));
            }
            return block.toString();
        }

        /**
         * The summaries of the sections owning the retrieved passages, capped at
         * {@link #TOP_SECTIONS}.
         *
         * <p><b>This is the number's second and independent use.</b> It bounded
         * the retrieval as a factor; here it bounds a different thing entirely —
         * how many distinct sections earn a bullet — over the passages that came
         * back, in the order the ranking first mentions each. Anchor does exactly
         * this and the coincidence of the number is not a relationship.
         */
        private String midLevel() {
            Map<UUID, DocumentStore.StoredSection> bySection = new LinkedHashMap<>();
            for (DocumentStore.StoredChapter chapter : hierarchy) {
                for (DocumentStore.StoredSection section : chapter.sections()) {
                    bySection.put(section.id(), section);
                }
            }
            LinkedHashSet<UUID> ordered = new LinkedHashSet<>();
            for (DocumentStore.Passage passage : passages) {
                if (passage.attribution() instanceof DocumentStore.Attribution.InSection in) {
                    ordered.add(in.sectionId());
                }
                if (ordered.size() >= TOP_SECTIONS) {
                    break;
                }
            }
            Vocabulary vocabulary =
                    document.vocabulary() == null ? Vocabulary.SECTION : document.vocabulary();
            StringBuilder block = new StringBuilder(
                    "\nYOUR RELEVANT " + upper(vocabulary.midLevelPlural()) + ":\n");
            for (UUID id : ordered) {
                DocumentStore.StoredSection section = bySection.get(id);
                if (section != null) {
                    block.append(bullet(section.title(), section.summary()));
                }
            }
            return block.toString();
        }

        /**
         * The retrieved passages, numbered, each saying where it sits and which
         * paragraph to ground in.
         *
         * <p><b>The paragraph id is this port's, and it is what replaces half of
         * Anchor's synthesiser prompt.</b> Anchor's chunk block carries a title
         * and nothing else, so grounding has to be a verbatim copy of that title
         * and the prompt spends its length on copy discipline and three labelled
         * negative examples. A stable id makes the pointer trivial and moves the
         * verbatim rule onto the thing that is actually evidence — the words.
         * The line is spelled as {@code DocumentTools.render} spells it, so the
         * one convention a model meets for "this is the thing to cite" is the
         * same on both surfaces.
         *
         * <p><b>Every line of a passage is quoted and the attribution goes
         * through the gate.</b> Anchor's own chunk block bypasses its {@code
         * StructuralRef} helper and re-derives the synthetic rule by hand; §3.2's
         * decision is that every render site goes through it, so this reads the
         * {@code Attribution} the store already gated and never a title and a
         * flag.
         */
        private String passages() {
            StringBuilder block = new StringBuilder("\nYOUR RELEVANT PASSAGES:\n");
            for (int i = 0; i < passages.size(); i++) {
                DocumentStore.Passage passage = passages.get(i);
                String where = switch (passage.attribution()) {
                    case DocumentStore.Attribution.InSection in ->
                            in.title().render(StructuralRef.WhenSynthetic.PLACEHOLDER);
                    case DocumentStore.Attribution.InNoSection ignored -> IN_NO_SECTION;
                };
                block.append('\n').append(i + 1).append(". [").append(oneLine(where))
                        .append("]\ncite paragraph ").append(passage.paragraphId()).append('\n')
                        .append(quoted(passage.chunkText())).append('\n');
            }
            return block.toString();
        }

        /**
         * The bibliography, <b>as names and nothing else</b>.
         *
         * <p><b>It is here for exactly one rule</b>, which every body in this
         * deliberation states: a name in the author list is the document, a name
         * only in the references is a third party it cites. That rule needs the
         * names. It was being given the whole entry — title, journal, volume,
         * year, pages — and measured on arXiv:2212.12473 that came to <b>14.5%
         * of the synthesiser's 21 478 characters</b>, more than the critic's
         * challenges and nearly twice the proposer's draft.
         *
         * <p><b>And the cost was not only the size.</b> A measured run has the
         * answer claiming a section titled {@code RAPHAEL STEINER} — a name it
         * had in front of it in this block, on a paper whose running head is the
         * author's name. A bibliography rendered in full supplies candidates for
         * "section title"; a list of names supplies what the rule asks for.
         */
        private String bibliography() {
            if (references.isEmpty()) {
                return quoted("(none)");
            }
            StringBuilder listed = new StringBuilder();
            for (DocumentStore.StoredReference reference : references) {
                if (listed.length() > 0) {
                    listed.append('\n');
                }
                listed.append('[').append(reference.refNum()).append("] ")
                        .append(namesIn(oneLine(reference.raw())));
            }
            return quoted(listed.toString());
        }

        /**
         * One unit: its title on a line this renderer wrote, its summary quoted
         * under it.
         *
         * <p>Anchor wraps a title in double quotes so the synthesiser has an
         * unambiguous extraction rule for the grounding arrays. There are no
         * title arrays here, and the quoting is kept anyway for the other half
         * of that decision — a bare {@code title: summary} gave the model
         * nothing structural to tell the two apart. What is added is the house
         * rule: <b>a summary is not this server's prose</b>. It was written by a
         * model reading uploaded text and carries whatever that text carried, so
         * it is quoted exactly as a passage is, and derivation is not
         * laundering.
         */
        private String bullet(StructuralRef title, String summary) {
            return "- " + oneLine(quotedTitleOrUnnamed(title)) + "\n"
                    + quoted(nullSafe(summary)) + "\n";
        }
    }

    /** Anchor's {@code quotedTitleOrUnnamed}, through the gate rather than
     *  beside it. */
    private static String quotedTitleOrUnnamed(StructuralRef title) {
        String rendered = title.render(StructuralRef.WhenSynthetic.PLACEHOLDER);
        return title.isSynthetic() ? rendered : "\"" + rendered + "\"";
    }

    private static String formatted(List<String> challenges) {
        if (challenges.isEmpty()) {
            return NO_CHALLENGES;
        }
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < challenges.size(); i++) {
            if (i > 0) {
                numbered.append('\n');
            }
            // "CHALLENGE n" AND NOT "n.", BECAUSE THERE ARE TWO NUMBERED LISTS
            // IN THIS PROMPT NOW AND THEY COLLIDED.
            //
            // The review pass returns its objections numbered from one in
            // exactly the shape this used to have. Measured over the two-pass
            // run, the synthesiser then put OBJECTION numbers into
            // `incorporated_critic_challenges`: one answer reported three
            // incorporations against a critic that raised zero, and three more
            // reported five against three. `unruled` looks those numbers up in
            // the critic's list, so an index meaning "objection 2" marked
            // challenge 2 as ruled on and the check failed open.
            //
            // Naming the number is what makes the two lists un-confusable. It
            // costs eleven characters a line and it is the half of the fix that
            // does not depend on a model reading an instruction.
            numbered.append("CHALLENGE ").append(i + 1).append(". ")
                    .append(challenges.get(i));
        }
        return numbered.toString();
    }

    // --- what a pass came to ----------------------------------------------------

    /** What this document calls its own top-level parts, plural, for a sentence
     *  a person reads about it. */
    private static String top(DocumentStore.StoredDocument document) {
        return (document.vocabulary() == null ? Vocabulary.SECTION : document.vocabulary())
                .plural();
    }

    /** First line only, on {@code JobRuntime.describe}'s reason: a constraint
     *  violation's second line quotes the failing row. */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String first = message == null ? "" : message.split("\\R", 2)[0].strip();
        return failure.getClass().getSimpleName() + (first.isEmpty() ? "" : ": " + first);
    }

    private static Outcome unavailable(String why) {
        return new Outcome(Ending.UNAVAILABLE, "This document could not be asked: " + why + ".",
                0, 0, "");
    }

    /** What a pass has done so far, so that a stopped one can say it. */
    private static final class Tally {

        private final String title;
        private int calls;

        private Tally(String title) {
            this.title = title;
        }

        /**
         * The answer, and everything a reader needs in order to check it.
         *
         * <p>{@code Outcome}'s rule is that {@code ANSWERED} text out of {@code
         * JobRuntime} is the model's prose verbatim, and that this type has a
         * second producer — {@code Curator.pass}, whose text is the pass's own
         * account. This is the third, and it is both: the synthesiser's prose,
         * and under it the account only the orchestrator can give. <b>The failed
         * attributions are in the answer and not in a log line</b>, because the
         * one failure this whole apparatus exists to catch is a claim the
         * document does not support, and a reader who is not told has been given
         * a fluent paragraph with nothing under it.
         *
         * <p><b>The prose is at column zero and the blocks below it are this
         * class's, and that boundary is weaker here than it is anywhere else in
         * this system. Named rather than hidden.</b> {@code DocumentTools}' rule
         * — every line at column zero is one this renderer wrote — cannot hold
         * for an answer, because the answer <em>is</em> a model's prose and
         * quoting the whole of it would make it unreadable. So a document that
         * talked a synthesiser into writing the words {@code ATTRIBUTION FAILED}
         * would put a second block in front of a reader.
         *
         * <p>What that can and cannot do is the point. It can add noise; it
         * cannot remove the real blocks, which are appended after the prose by
         * this method and are built from what the corpus answered rather than
         * from what the model said. And it reaches no machine at all: {@code
         * citations} is written from the attributions that <em>held</em>, so a
         * forged block writes no row and {@code CitationStore} goes on
         * under-reporting rather than mis-reporting. The reader of this text is a
         * person, not an agent, which is the other half of why the trade is the
         * right way round.
         */
        private Outcome answered(
                SynthesiserOutput answer, Attributions attributed, Challenged challenged,
                int recorded) {

            StringBuilder text = new StringBuilder(answer.response());
            if (!attributed.held().isEmpty()) {
                text.append("\n\nGROUNDED IN\n");
                for (SynthesiserOutput.Grounded claim : attributed.held()) {
                    text.append("\nparagraph ").append(claim.paragraph()).append('\n')
                            .append(quoted(claim.quote())).append('\n');
                }
            }
            if (!attributed.failed().isEmpty()) {
                text.append("\n\nATTRIBUTION FAILED — this answer named these paragraphs and"
                        + " these words are not in them\n");
                for (SynthesiserOutput.Grounded claim : attributed.failed()) {
                    text.append("\nparagraph ").append(claim.paragraph()).append('\n')
                            .append(quoted(claim.quote())).append('\n');
                }
            }
            List<String> unruled = unruled(answer, challenged.challenges());
            if (!unruled.isEmpty()) {
                text.append("\n\nCHALLENGE UNANSWERED — the critic raised these and this answer"
                        + " neither took them up nor gave a reason for setting them aside\n");
                for (String challenge : unruled) {
                    text.append('\n').append(quoted(challenge)).append('\n');
                }
            }
            text.append("\n\nTHE DELIBERATION\n");
            if (challenged.note() != null) {
                text.append('\n').append(capitalised(challenged.note()))
                        .append(", so this answer was written with no challenges against it.\n");
            } else {
                text.append("\nThe critic raised ").append(challenged.challenges().size())
                        .append(" challenge(s) from the document's summaries alone, and judged"
                                + " that what the document argues as a whole supports the"
                                + " proposed answer: ")
                        // oneLine, because this is the one thing in this sentence
                        // a MODEL wrote. CriticOutput constrains it to four words
                        // and the flatten is the second half of that: a value the
                        // parser had to fall back on must not be able to open a
                        // line of its own under a heading this class wrote.
                        .append(oneLine(challenged.support())).append(".\n");
                if (!challenged.challenges().isEmpty()) {
                    // THE RECONCILIATION, AND IT IS THE WHOLE OF WHY THE TWO
                    // LISTS ARE PARSED. Without it the previous sentence was the
                    // end of the account: the reader learned that N challenges
                    // were raised and never learned whether the answer above had
                    // read one of them. An answer that ignores its critic and an
                    // answer that overrules it from better evidence are opposite
                    // facts, and they used to render identically.
                    text.append("\nOf those, this answer took up ")
                            .append(answer.incorporated().size()).append(" and set aside ")
                            .append(answer.rejected().size()).append(" with a reason");
                    if (unruled.isEmpty()) {
                        text.append(", ruling on every one.\n");
                    } else {
                        text.append(". ").append(unruled.size())
                                .append(" went unanswered and are quoted above.\n");
                    }
                    for (SynthesiserOutput.Rejection rejection : answer.rejected()) {
                        // Quoted, because the reason is a model's prose about a
                        // model's prose about somebody's document, arriving under
                        // a heading this class wrote.
                        text.append("\nchallenge ").append(rejection.challenge())
                                .append(", set aside\n").append(quoted(rejection.reason()))
                                .append('\n');
                    }
                }
            }
            if (attributed.unreadable() != null) {
                text.append("\nThe answer's grounding block could not be read (")
                        .append(attributed.unreadable()).append("), so nothing it claimed was"
                                + " checked against the paragraphs.\n");
            } else if (attributed.held().isEmpty() && attributed.failed().isEmpty()) {
                // A DIFFERENT FACT FROM "0 of 0", which is what this used to
                // say. An answer that named no paragraph was not checked at all,
                // and a reader told a ratio would read a clean bill of health off
                // an answer nothing verified. It is a legal state -- the
                // synthesiser is told that a claim taken from a summary has no
                // paragraph to name -- and it is one worth seeing.
                text.append("\nThis answer named no paragraph of the document, so nothing in it"
                        + " was checked against the document's own words.\n");
            } else {
                // TWO NUMBERS AND NOT ONE, because they answer two questions and
                // are not the same count. The first is about this answer: how
                // many of the claims it attributed hold. The second is about the
                // table: `citations` holds one row per PARAGRAPH, so three
                // claims on one paragraph are one row -- and a row the corpus
                // refused is no row at all. Reporting the first as the second
                // would make the sentence about V25 the one place this system
                // over-reports it.
                text.append('\n').append(attributed.held().size()).append(" of ")
                        .append(attributed.held().size() + attributed.failed().size())
                        .append(" attribution(s) were found in the paragraph they named. ")
                        .append(recorded).append(" citation(s) were recorded.\n");
            }
            return new Outcome(Ending.ANSWERED, text.toString(), calls, calls, "");
        }

        /**
         * The challenges nothing ruled on, in the order the critic raised them.
         *
         * <p><b>Computed from the critic's own list rather than from the
         * answer's</b>, which is the only direction that can find anything. An
         * answer that names no challenge at all — the observed failure, four
         * times in thirty-two calls with reasoning off — has two empty lists and
         * nothing in it to notice; the critic's list is where the challenges
         * exist.
         *
         * <p>A number outside the range simply covers no challenge, so an answer
         * that rules on challenge 5 of 2 leaves both of the real ones here. That
         * is deliberate: what is being asked is whether each raised challenge was
         * answered, and a ruling addressed to nothing does not answer one.
         */
        private static List<String> unruled(SynthesiserOutput answer, List<String> challenges) {
            Set<Integer> ruled = new HashSet<>(answer.incorporated());
            answer.rejected().forEach(rejection -> ruled.add(rejection.challenge()));
            List<String> unanswered = new ArrayList<>();
            for (int i = 0; i < challenges.size(); i++) {
                if (!ruled.contains(i + 1)) {
                    unanswered.add(challenges.get(i));
                }
            }
            return unanswered;
        }

        /**
         * The outcome of a pass that stopped.
         *
         * <p><b>It logs nothing, and that is a correction rather than an
         * omission.</b> A warning here would fire on every stopping outcome this
         * class <em>builds</em> — and one of them is deliberately built and
         * thrown away: a critic stage that failed is not fatal, so {@link
         * #critique} discards its {@link Outcome} and the pass answers. A line
         * saying "the deliberation stopped STUCK" on a deliberation that went on
         * to answer is a log that has to be argued with. What does log is {@link
         * #run}, which knows which stage it was and does not claim the pass ended
         * with it.
         */
        private Outcome stopped(Ending ending, String why, String detail) {
            String text = why + " '" + title + "' was not answered, and the "
                    + calls + " model call(s) this deliberation had already made are spent."
                    + " Asking again starts a new one.";
            return new Outcome(ending, text, calls, calls, detail);
        }
    }

    // --- rendering ---------------------------------------------------------------

    /** Whitespace runs collapsed, the ends trimmed and the case folded in a
     *  fixed locale. What both sides of a quotation check go through; {@link
     *  #check} owns why each of the three. */
    /**
     * The author list at the front of a bibliography entry, or a bounded prefix
     * of it.
     *
     * <p>An entry reads {@code J.-P. Allouche and J. O. Shallit. The ring of
     * k-regular sequences. Theoret. Comput. Sci. 98 (1992), 163-197.} The author
     * list ends at the first full stop that is not an initial — and an initial
     * is a single capital, so the terminator is a stop preceded by a
     * <em>lower-case</em> letter. That is a heuristic and it is bounded rather
     * than trusted: an entry that does not match is cut at {@link #MOST_OF_A_NAME}
     * characters, which is longer than any author list this corpus holds and far
     * shorter than an entry.
     *
     * <p><b>Being wrong here is cheap in one direction only</b>, which is why the
     * fallback truncates rather than drops. Too much of an entry costs context;
     * dropping a name would break the one rule this block exists to serve.
     */
    private static String namesIn(String entry) {
        java.util.regex.Matcher end = AUTHOR_LIST_ENDS.matcher(entry);
        if (end.find()) {
            return entry.substring(0, end.start() + 1);
        }
        return entry.length() <= MOST_OF_A_NAME
                ? entry
                : entry.substring(0, MOST_OF_A_NAME) + "...";
    }

    /** A full stop after a lower-case letter, followed by space — the end of an
     *  author list rather than an initial inside one. */
    private static final Pattern AUTHOR_LIST_ENDS = Pattern.compile("(?<=\\p{Ll})\\.\\s");

    /** How much of a reference is kept when the pattern finds no author list. */
    private static final int MOST_OF_A_NAME = 80;

    private static String flattened(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip()
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** A field flattened onto one line, so a title or a question carrying a break
     *  cannot turn one heading into two. {@code DocumentTools.oneLine}. */
    private static String oneLine(String text) {
        return LINE_BREAK.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Every line prefixed, so no line of somebody else's document can reach
     * column zero.
     *
     * <p>{@code DocumentTools.quote}, and the rule it enforces is V18's: chunk
     * text is <i>"content the server did not write and cannot vouch for"</i>. It
     * is applied here to summaries as well as to passages, and to the proposer's
     * draft and the critic's challenges — those were written by models reading
     * the same uploaded text, and derivation is not laundering.
     */
    private static String quoted(String text) {
        StringBuilder quoted = new StringBuilder();
        for (String line : LINE_BREAK.split(text, -1)) {
            if (quoted.length() > 0) {
                quoted.append('\n');
            }
            quoted.append(QUOTE).append(line);
        }
        return quoted.toString();
    }

    private static String nullSafe(String text) {
        return text == null ? "" : text;
    }

    private static String upper(String word) {
        return word.toUpperCase(java.util.Locale.ROOT);
    }

    private static String capitalised(String sentence) {
        return sentence.isEmpty()
                ? sentence
                : Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }
}
