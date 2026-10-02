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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The summariser cascade: every paragraph of a document labelled with what it
 * claims, folded upward until one sentence stands for the document.
 *
 * <h2>Why this is agents and not a service</h2>
 *
 * <p>Anchor's {@code SummariserService} calls the model directly. Ported that
 * way, the largest workload in this system — 233 model calls on the 30-page
 * paper {@code SummariserTest} measures, at a 6.5 s median on {@code
 * gpt-oss-20b}, so about twenty-five minutes serial — would bypass the agent
 * runtime entirely: no log, no
 * compaction, no budget, no references, no trajectory. That would prove
 * Plowshare can <em>host</em> a document pipeline and not that its architecture
 * carries one. Every call here goes through {@link JobRuntime} instead, so the
 * cascade exercises the whole substrate and is the first thing that does at
 * volume.
 *
 * <p>{@code Curator} is the class this is shaped after and the resemblance is
 * deliberate: the listing, the ordering and the folding are in code, and what is
 * spent on a model is the one thing only a model can do.
 *
 * <h2>Four tiers, and a fold that is no longer one of them</h2>
 *
 * <p><b>This class shipped with three levels, argued for at length, and the
 * argument is kept below because the thing that changed is not the reasoning
 * but a fact it rested on.</b> It said: Anchor has four tiers, V18 declined its
 * section and chapter <em>detectors</em>, and read in Anchor's code rather than
 * in its comments what that leaves is the degenerate path — {@code
 * SectionDetector} with no boundaries returns ONE synthetic section over the
 * whole body, {@code ChapterDetector} with none returns ONE synthetic chapter,
 * and two of the four levels then summarise a single input. Every word of that
 * is still true of a server with no detectors.
 *
 * <p>V26 and the detectors landed, so this one has them. A section and a chapter
 * are rows now, with ids, and {@code sections.summary} and {@code
 * chapters.summary} are read by something: Anchor's per-document ask gives every
 * one of its three agents the chapter summaries, and the proposer and
 * synthesiser the summaries of the sections that own the retrieved chunks. It
 * gives none of them a paragraph summary at all. <b>So the two middle calls stop
 * being paraphrases in transit and become the only way those rows are ever
 * written</b>, and the objection to them does not survive the tables existing.
 *
 * <p>What survives, demoted, is the fold. {@link #SPAN} is no longer a tier: it
 * is how a tier's children are made to fit one call, and it fires inside the
 * section, chapter and document levels rather than between them. The reason it
 * is kept at all is a limit in Anchor that a faithful port would otherwise
 * inherit without noticing — on a document with no detected heading Anchor calls
 * {@code summariseSection} once with <em>every</em> paragraph summary in the
 * document, two hundred of them on a real paper, and that this holds was never
 * established. Three things about it are unchanged and are why it was worth
 * keeping:
 *
 * <ul>
 *   <li><b>The depth is a property of the unit.</b> A section of four paragraphs
 *       folds not at all; one of two hundred folds twice. Nothing in a file says
 *       how deep.
 *   <li><b>A run of one is carried and never summarised.</b> Folding a single
 *       summary is a paraphrase rather than a compression. This is now the
 *       opposite of what a tier does over one child, and deliberately: see
 *       {@link #tier}.
 *   <li><b>Nothing is stored for it</b>, because a span has no identity: it is
 *       an artefact of a batch size, as a chunk is an artefact of the chunker.
 *       What records the intermediate work is the conversation each fold runs
 *       in — which is what building the cascade as agents buys.
 * </ul>
 *
 * <p><b>The grouping rule is this class's and never the agent's</b>, which is
 * what made restoring the tiers a change here rather than to a prompt.
 *
 * <h2>The compression invariant</h2>
 *
 * <p>Raw text enters at {@link #PARAGRAPH} and nowhere else; every level above
 * reads summaries. Anchor calls this the critical invariant and enforces it by
 * construction, and so does this — by what goes into an opening message, and by
 * the four upper agents holding no tools with which to reach the corpus.
 *
 * <p>One more thing never enters an upper prompt, and it is not text the
 * document wrote: <b>a title the parser invented</b>. A synthetic chapter or
 * section carries a sentinel in its {@code title} column, and every render here
 * goes through {@link StructuralRef} with {@link
 * StructuralRef.WhenSynthetic#BLANK} — Anchor's Policy C, whose reason is the
 * invariant above rather than tidiness: the model echoes a name it is given back
 * into the summary, and the tier above reads that summary instead of the text.
 *
 * <h2>One allowance, and where it is recorded</h2>
 *
 * <p><b>An ingest is a job with its own allowance, exactly as a curator pass
 * is.</b> {@code agents.curator.Passes.start} mints one {@code Budget} from
 * plowshare.agents.curator-budget} and hands the same object to every ruling in
 * a pass; this takes one minted from {@code plowshare.documents.ingest-budget}
 * and hands it to every run in a cascade. That is the answer to the collision
 * the survey names — 242 calls against an {@code interlocutor}'s allowance of 40
 * — and it is a precedent followed rather than an exception invented: neither
 * charging a person's conversation six times its whole allowance for one tool
 * call, nor exempting the most expensive operation in the system from the one
 * honest cost bound it has.
 *
 * <p><b>Where it differs from a pass is that this allowance has a row.</b> A
 * curator's rulings are parentless roots of origin {@code curator}, and V17
 * records that the pass's budget "is not a conversation and has no row
 * anywhere". Here there is something for the tree to hang off: the cascade opens
 * ONE conversation of origin {@link Origin#SUBMISSION}, named for {@link
 * #DOCUMENT} and owning the ingest's allowance, and every paragraph and fold run
 * is a {@link Origin#DELEGATION} child of it. That is the only shape V17's
 * constraints permit without a migration:
 *
 * <ul>
 *   <li>{@code conversations_an_allowance_is_owned_or_shared} makes {@code
 *       turn} and {@code submission} the origins that own a budget. Two hundred
 *       submissions would each carry a copy of the ingest's number, and the
 *       first thing to sum the column would count every model call two hundred
 *       times — with every row holding a plausible figure, which is what makes
 *       it quiet.
 *   <li>{@code conversations_a_delegation_is_what_has_a_parent} makes a
 *       delegation exactly the row with a parent and, through the constraint
 *       above, exactly the row that holds no numbers. One shared object, one
 *       row that records it.
 *   <li>{@code curator} would be a lie about what this is, and origin decides
 *       which retention policy a row falls under — permanently, because a nulled
 *       payload cannot be reclassified.
 * </ul>
 *
 * <p>The root's own run is the document-level call, which happens last. So the
 * tree reads the way the cascade does: this document's summary, and under it
 * everything that was read to write it.
 *
 * <h2>What a stopped cascade leaves behind</h2>
 *
 * <p>A summary is attached the moment it comes back, one row at a time. A
 * cascade that spent its allowance, was cancelled, or died with the process
 * leaves the paragraphs it never reached holding null, and {@link
 * DocumentStore#unsummarised} is what the next ingest of that document asks —
 * so the work already paid for is kept and nothing outside Postgres needs to
 * have survived. <b>That matters here in a way it does not for a curator
 * pass:</b> {@code JobStore} is in memory and mints ids from a counter that
 * restarts at {@code job_000001}, so a half-hour ingest is precisely the job
 * whose handle a restart loses. The handle is lost; the work is not.
 */
public final class Summariser implements UsageAware {
    private UsageOwners usageOwners = UsageOwners.NONE;
    @Override public void useUsageOwners(UsageOwners source) { usageOwners = java.util.Objects.requireNonNull(source); }


    private static final Logger log = LoggerFactory.getLogger(Summariser.class);

    /** The only level that reads raw text. */
    public static final String PARAGRAPH = "paragraph_summariser";

    /** The fold: an ordered run of summaries into one summary. <b>Not a tier</b>
     *  — see the class javadoc. */
    public static final String SPAN = "span_summariser";

    /** A section, from the summaries of the paragraphs in it. */
    public static final String SECTION = "section_summariser";

    /** A chapter, from the summaries of the sections in it. */
    public static final String CHAPTER = "chapter_summariser";

    /** The top, and the agent the cascade's one owning conversation is named
     *  for. */
    public static final String DOCUMENT = "document_summariser";

    /**
     * The tier a cascade runs in.
     *
     * <p><b>Global, and that is V18's decision restated rather than a default.</b>
     * "A memory has exactly one home; a document has none. Memory is scoped
     * because contradiction needs an owner. Documents are not because relevance
     * does not." A corpus is reachable from every project, so a cascade over it
     * belongs to no project — and {@code Home.global()} is the absence of a
     * project rather than a project named "global", which is exactly the fact
     * being stated.
     */
    private Home home = Home.global();
    private String owner;
    private String parentLog;

    private final DocumentStore store;
    private final JobRuntime runtime;
    private DocumentStages stages=DocumentStages.NONE;
    public Summariser withStages(DocumentStages stages) { this.stages=stages;return this; }
    private final Supplier<AgentRegistry> agents;
    private final int spanSize;

    /**
     * Where the cascade's conversations are opened, or null for a cascade that
     * keeps no log.
     *
     * <p>{@code Curator.logs}' shape and its note: <b>nullable, and the null is
     * a fixture rather than a deployment</b>. Every boot supplies it. A cascade
     * without one still runs and still writes summaries; what it loses is the
     * whole reason for building this as agents, so a production wiring that
     * reached this state would be the defect and not the configuration.
     */
    private final Compaction logs;

    /**
     * What puts a vector on the document summary this cascade writes, or null
     * for a cascade whose summaries are text only.
     *
     * <p><b>{@link #logs}' shape exactly, and the null means the same kind of
     * thing</b>: a fixture rather than a deployment. Every boot supplies it. A
     * cascade without one still writes every summary; what those summaries lose
     * is being rankable by {@code GET /v1/documents/rank} until the startup
     * backfill next runs, which is a delay rather than a loss and is counted by
     * {@code DocumentStore.Ranking}.
     *
     * <p><b>Here rather than in the ingest</b>, because this is the method that
     * holds the summary. The alternative was a second query to read back a
     * string that has not left this class, on a path that has just spent two
     * hundred model calls.
     */
    private final SummaryEmbeddings summaryVectors;

    public Summariser(
            DocumentStore store, JobRuntime runtime, Supplier<AgentRegistry> agents,
            int spanSize) {
        this(store, runtime, agents, spanSize, null);
    }

    /**
     * The production wiring.
     *
     * @param spanSize how many summaries one fold reads. <b>At least two</b>: a
     *     fold of one is a paraphrase, and a span size of one would fold a level
     *     into a level of the same length for ever
     * @param logs where each run's conversation is opened
     */
    public Summariser(
            DocumentStore store, JobRuntime runtime, Supplier<AgentRegistry> agents,
            int spanSize, Compaction logs) {
        this(store, runtime, agents, spanSize, logs, null);
    }

    /**
     * The production wiring, once the document summary became something that is
     * also a vector.
     *
     * @param summaryVectors what embeds the document-level summary as it is
     *     written, or null. See the field
     */
    public Summariser(
            DocumentStore store, JobRuntime runtime, Supplier<AgentRegistry> agents,
            int spanSize, Compaction logs, SummaryEmbeddings summaryVectors) {
        this.summaryVectors = summaryVectors;
        this.store = Objects.requireNonNull(store, "store");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.agents = Objects.requireNonNull(agents, "agents");
        if (spanSize < 2) {
            throw new IllegalArgumentException(
                    "plowshare.documents.span-size is " + spanSize + "; it must be at least 2."
                            + " A fold reads that many summaries and writes one, so a size of"
                            + " one would fold a level into a level of the same length and"
                            + " never finish, and a fold of a single summary is a paraphrase"
                            + " rather than a compression");
        }
        this.spanSize = spanSize;
        this.logs = logs;
    }

    /**
     * Summarise one document, to an ending.
     *
     * <p>Blocking, on the caller's thread — {@link JobRuntime#run}'s and {@code
     * Curator.pass}' position, and for their reason: this class owns no
     * executor, so a caller that wants a cascade on a virtual thread starts one.
     *
     * <p><b>Every level is serial and no level is parallel</b>, which is
     * Anchor's choice kept rather than inherited without looking. Its own
     * javadoc says paragraph summaries are "parallel-friendly but here
     * sequential per paragraph so the ledger and back-pressure stay simple", and
     * two things here say the same more strongly: {@code Budget} is a shared
     * counter whose whole meaning is a ceiling on calls in flight <em>and</em>
     * calls already made, and the dispatcher routes least-loaded with no
     * priority — so a cascade that fanned out would evict every conversation's
     * KV cache harder rather than for less time.
     *
     * @param documentId the document, which must be in the corpus already: the
     *     text is committed before any model is called, for the reason {@code
     *     IngestService} sets out
     * @param title what the corpus files it as, which is the one fact about the
     *     document the top level is given beyond the summaries below it
     * @param budget the model calls this whole cascade may spend, across every
     *     run it starts. Shared by reference; running out of it is an ending of
     *     the cascade and not of one run
     * @param cancelled asked at every paragraph and every fold, at the boundary
     *     before the call rather than after, and handed to each run so one
     *     cancelled mid-turn stops at its own boundary
     */
    /** Owned pipeline logs and a generation-fenced checkpoint writer. */
    public Summariser forRevision(DocumentStore writer, Home selectedHome, String account) {
        return forRevision(writer, selectedHome, account, null);
    }

    public Summariser forRevision(DocumentStore writer, Home selectedHome, String account, String parent) {
        Summariser copy = new Summariser(writer, runtime, agents, spanSize, logs, null);
        copy.home = selectedHome;
        copy.owner = account;
        copy.parentLog = parent;
        copy.stages=stages;
        copy.usageOwners=usageOwners;
        return copy;
    }

    public Outcome summarise(
            UUID documentId, String title, Budget budget, BooleanSupplier cancelled) {
        return summarise(documentId, title, budget, cancelled, owner);
    }

    public Outcome summarise(
            UUID documentId, String title, Budget budget, BooleanSupplier cancelled, String account) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(cancelled, "cancelled");
        String named = title == null ? "this document" : title;

        AgentRegistry registry = agents.get();
        if (registry == null) {
            return unavailable("this server has no agent registry, so nothing could be"
                    + " summarised", named);
        }
        for (String name : List.of(PARAGRAPH, SPAN, SECTION, CHAPTER, DOCUMENT)) {
            if (!registry.names().contains(name)) {
                // By name rather than through AgentRegistry.get, which throws
                // for a miss. Curator's shape and its reason: a boot wired over
                // a directory missing one of these is a different fault from a
                // boot with no registry, and one sentence for both would leave a
                // wiring test green over a wiring that had changed.
                return unavailable("this server defines no agent named '" + name + "', so the"
                        + " cascade has no " + name.replace("_summariser", "") + " level",
                        named);
            }
        }
        AgentDefinition paragraph = registry.get(PARAGRAPH);
        AgentDefinition span = registry.get(SPAN);
        AgentDefinition section = registry.get(SECTION);
        AgentDefinition chapter = registry.get(CHAPTER);
        AgentDefinition document = registry.get(DOCUMENT);

        List<DocumentStore.UnsummarisedParagraph> waiting = store.unsummarised(documentId);
        boolean documentIsSummarised = store.documentSummary(documentId) != null;
        // AND THE TWO TIERS BETWEEN THEM, which is what a re-ingest makes a
        // separate question. Paragraph summaries survive one -- the identity
        // rule keeps an unchanged paragraph's id -- and the hierarchy does not,
        // because the detectors run again over new text and DocumentStore.write
        // replaces it wholesale. So a document can hold every paragraph summary
        // and a document summary, over chapters and sections that hold nothing.
        // Asking only the two ends would call that finished and leave the tiers
        // the per-document ask actually reads empty for ever.
        if (waiting.isEmpty() && documentIsSummarised
                && store.unsummarisedUnits(documentId) == 0) {
            // The identity rule's payoff, and the reason a corpus is worth
            // re-ingesting: an unchanged paragraph keeps its id and therefore
            // keeps its summary, so there is nothing here to pay for. Answered
            // rather than silent, because "no model call was made" is the
            // interesting half of this outcome.
            return new Outcome(Ending.ANSWERED,
                    "'" + named + "' was already summarised and nothing in it changed, so this"
                            + " cost no model call.", 0, 0, "");
        }

        Tally tally = new Tally(named);
        Transcript root = logs == null
                ? Transcript.NONE
                : logs.logFor(parentLog == null ? Origin.SUBMISSION : Origin.DELEGATION, home, document, parentLog,
                        parentLog == null ? budget : null,
                        io.aeyer.plowshare.server.agents.Speaker.harness(), account == null ? owner : account);
        if (logs != null && logs.accountingEnabled()) { root = logs.own(home, root, document, account == null ? owner : account); }

        for (DocumentStore.UnsummarisedParagraph unsummarised : waiting) {
            if (cancelled.getAsBoolean()) {
                return tally.stopped(Ending.CANCELLED, "This cascade was cancelled.", "");
            }
            Answer answered = ask(paragraph, unsummarised.text(), root.delegate(paragraph, home),
                    budget, cancelled, tally);
            if (answered.stopped() != null) {
                return answered.stopped();
            }
            store.attachSummary(unsummarised.id(), answered.text());
        }

        // THE HOLE THE UPPER TIERS CANNOT SEE, asked before any of them runs.
        // `paragraphs.section_id` is nullable -- V18 froze `document_id NOT
        // NULL` and V26 could only add beside it -- so a paragraph written
        // before the hierarchy existed is labelled at the bottom tier and read
        // by nothing above it. A document summary written over that is a summary
        // of a document with a paragraph missing, and every level above reads
        // summaries rather than text, so nothing downstream could tell.
        int orphans = store.summarisedParagraphsInNoSection(documentId);
        if (orphans > 0) {
            return tally.stopped(Ending.STUCK,
                    orphans + " labelled paragraph(s) of '" + named + "' are in no section, so"
                            + " every level above them would be summarising a document with"
                            + " those paragraphs missing. Ingesting it again derives the"
                            + " hierarchy over the whole of it.", "");
        }

        // Read back rather than accumulated in memory, which is what makes a
        // cascade resumable across the ingest that stopped: this run's own
        // summaries and the ones a previous run already paid for are the same
        // rows, in document order, with nothing to reconcile. The two middle
        // tiers are read the same way, which is what lets a run resume between
        // them rather than only below them.
        List<String> chapterSummaries = new ArrayList<>();
        // Held rather than iterated in place, because how many chapters the
        // document has is one of the two facts the tiers below need to say what
        // a parser-invented unit IS — see forASection and forAChapter.
        List<DocumentStore.StoredChapter> hierarchy = store.hierarchy(documentId);
        for (DocumentStore.StoredChapter storedChapter : hierarchy) {
            boolean theOnlyChapter = hierarchy.size() == 1;
            boolean theOnlySection = storedChapter.sections().size() == 1;
            List<String> sectionSummaries = new ArrayList<>();
            for (DocumentStore.StoredSection storedSection : storedChapter.sections()) {
                if (storedSection.summary() != null) {
                    sectionSummaries.add(storedSection.summary());
                    continue;
                }
                List<String> below = store.sectionParagraphSummaries(storedSection.id());
                if (below.isEmpty()) {
                    // A heading immediately followed by the next one. It is a
                    // real row and it claims nothing, so it is owed no summary
                    // and `sections_summary_is_not_blank` would refuse the
                    // empty string anyway.
                    continue;
                }
                Answer summarised = tier(section, span, below,
                        run -> forASection(storedSection.title(), theOnlySection, run),
                        root, budget, cancelled, tally,
                        summary -> store.attachSectionSummary(storedSection.id(), summary));
                if (summarised.stopped() != null) {
                    return summarised.stopped();
                }
                sectionSummaries.add(summarised.text());
            }
            if (storedChapter.summary() != null) {
                chapterSummaries.add(storedChapter.summary());
                continue;
            }
            if (sectionSummaries.isEmpty()) {
                continue;
            }
            Answer summarised = tier(chapter, span, sectionSummaries,
                    run -> forAChapter(storedChapter.title(), theOnlyChapter, run),
                    root, budget, cancelled, tally,
                    summary -> store.attachChapterSummary(storedChapter.id(), summary));
            if (summarised.stopped() != null) {
                return summarised.stopped();
            }
            chapterSummaries.add(summarised.text());
        }

        if (chapterSummaries.isEmpty()) {
            // Unreachable through IngestService, which refuses a document that
            // derived no paragraphs, and answered rather than thrown for that
            // method's reason: a caller reaching it has a real document that
            // summarised to nothing and needs to be told which.
            return tally.stopped(Ending.ANSWERED,
                    "'" + named + "' holds no summarised chapter, so there was nothing to"
                            + " summarise it from.", "");
        }
        Folded top = fold(span, chapterSummaries, root, budget, cancelled, tally);
        if (top.stopped() != null) {
            return top.stopped();
        }
        List<String> level = top.run();

        // IN THE ROOT'S OWN CONVERSATION, and last. The children ran first
        // because that is the order the cascade computes in; the row they hang
        // off was opened before any of them, so the tree is whole at every
        // moment and this is one more turn in a conversation that already has
        // two hundred children.
        // IN THE ROOT ITSELF and not in a child of it. The root conversation is
        // named for this agent and owns the allowance, so the document-level
        // call is the run that conversation IS -- a delegation here would be a
        // parent that never spoke, holding an allowance for a run it did not
        // make, with an extra row saying what the root was for.
        Answer summary = ask(document, forTheDocument(named, level), root, budget, cancelled,
                tally);
        if (summary.stopped() != null) {
            return summary.stopped();
        }
        store.attachDocumentSummary(documentId, summary.text());
        // AFTER the summary is committed and never instead of it. An embedding
        // call that fails here costs the document its place in a ranking until
        // the startup pass runs; one that could fail the cascade would throw
        // away two hundred model calls that succeeded, which is the trade
        // `unsummarisedUnits` and the allowance already decided the direction
        // of. SummaryEmbeddings swallows its own failures for that reason.
        if (summaryVectors != null) {
            if (account == null) { summaryVectors.attach(documentId, summary.text()); }
            else { summaryVectors.attach(documentId, summary.text(), usageOwners.in(home,account,UsageAttribution.Operation.EMBEDDING_WRITE)); }
        }
        return tally.finished();
    }

    // --- one run --------------------------------------------------------------

    /**
     * One summariser run, and what came back — or the ending the whole cascade
     * takes from it.
     *
     * <p>Exactly one of the two is set. A record rather than an exception
     * because every stopping ending here is an ordinary outcome of a correct
     * cascade, and this class returns {@link Outcome}s rather than throwing them
     * for {@code Curator}'s reason.
     */
    private record Answer(String text, Outcome stopped) {}

    /**
     * One stored tier — a section or a chapter — summarised from the tier below
     * it, and written to its row the moment it comes back.
     *
     * <p><b>Called once when its children fit and after a fold when they do
     * not</b>, which is §4.2's decision and the reason {@link #SPAN} survives.
     * Anchor's own path is the fold's absence: with no detected headings it
     * makes one synthetic section over every paragraph in the document and calls
     * {@code summariseSection} once with all of them — two hundred summaries in
     * a single prompt on a real paper, which its corpus has not made fail and
     * which nobody established. Here "fit" is a number rather than a hope; see
     * {@link #fold}.
     *
     * <p><b>The tier is called even over a single child, and the fold still is
     * not.</b> That looks like a contradiction and is the whole distinction: a
     * fold's output has no row and no reader — it is an artefact of a batch size
     * — so rewriting one sentence into another buys nothing. A section's and a
     * chapter's summary are rows the per-document ask reads directly, and
     * Anchor gives every one of its three ask agents the chapter summaries and
     * gives none of them a paragraph summary. A tier over one child writes
     * something addressable; a fold over one would only rewrite it in transit.
     *
     * @param task what this tier is asked, given the run its children came to
     *     <em>after</em> any fold — so the title and the numbering are composed
     *     over what the model will actually read
     * @param attach where the summary goes, one row at a time, for {@code
     *     DocumentStore.attachSummary}'s reason
     */
    private Answer tier(
            AgentDefinition level, AgentDefinition span, List<String> children,
            Function<List<String>, String> task, Transcript root, Budget budget,
            BooleanSupplier cancelled, Tally tally, Consumer<String> attach) {

        Folded fitting = fold(span, children, root, budget, cancelled, tally);
        if (fitting.stopped() != null) {
            return new Answer(null, fitting.stopped());
        }
        Answer answered = ask(level, task.apply(fitting.run()), root.delegate(level, home),
                budget, cancelled, tally);
        if (answered.stopped() != null) {
            return answered;
        }
        attach.accept(answered.text());
        return answered;
    }

    /** A run of summaries compressed until it fits one call, or the ending the
     *  cascade took while compressing it. */
    private record Folded(List<String> run, Outcome stopped) {}

    /**
     * <b>The fold, demoted: it makes a tier's children fit and is not a tier.</b>
     *
     * <p><b>What "fit" means, as a rule and not a vibe: at most {@link #spanSize}
     * of them.</b> The bound is a count because the count is the only dimension
     * a document controls. Each child is written by an agent that was told how
     * long to be — one sentence at the paragraph level, two to four at the
     * section level, three to five at the chapter level — so a child's own
     * length is bounded by the agent that wrote it; how many children a unit has
     * is bounded by nothing at all, and is exactly what runs to two hundred on
     * the degenerate path. {@code plowshare.documents.span-size} is therefore
     * the same number in both of its jobs: how many summaries one fold reads,
     * and how many a tier may be shown at once.
     *
     * <p><b>A run of one is carried and never summarised.</b> Folding a single
     * summary rewrites a sentence into another sentence and calls it
     * compression.
     */
    private Folded fold(
            AgentDefinition span, List<String> children, Transcript root, Budget budget,
            BooleanSupplier cancelled, Tally tally) {

        List<String> level = children;
        while (level.size() > spanSize) {
            List<String> folded = new ArrayList<>();
            for (int from = 0; from < level.size(); from += spanSize) {
                if (cancelled.getAsBoolean()) {
                    return new Folded(null, tally.stopped(
                            Ending.CANCELLED, "This cascade was cancelled.", ""));
                }
                List<String> run = level.subList(from, Math.min(from + spanSize, level.size()));
                if (run.size() == 1) {
                    folded.add(run.get(0));
                    continue;
                }
                Answer answered = ask(span, numbered(run), root.delegate(span, home), budget,
                        cancelled, tally);
                if (answered.stopped() != null) {
                    return new Folded(null, answered.stopped());
                }
                folded.add(answered.text());
                tally.folds++;
            }
            level = folded;
        }
        return new Folded(level, null);
    }

    private Answer ask(
            AgentDefinition level, String task, Transcript conversation, Budget budget,
            BooleanSupplier cancelled, Tally tally) {

        Outcome ran;
        try {
            ran = stages.run(level,task,conversation.conversationId(),home,owner,stageTask -> runtime.run(level, stageTask, home, budget, cancelled, null,
                    JobWatch.UNWATCHED, conversation));
        } catch(DocumentStages.Blocked blocked) {
            tally.calls+=blocked.modelCalls();
            Outcome stopped=tally.stopped(Ending.UNAVAILABLE,blocked.getMessage(),"document.stage.denied");
            conversation.closed(task,new Outcome(Ending.UNAVAILABLE,blocked.getMessage(),0,blocked.modelCalls(),"document.stage.denied"));
            return new Answer(null,stopped);
        }
        conversation.closed(task, ran);
        tally.calls += ran.modelCalls();

        switch (ran.ending()) {
            case ANSWERED -> {
                if (ran.text().isBlank()) {
                    // ANCHOR'S EMPTY-SUMMARY DEFENCE, and the reason it ports
                    // rather than being dropped. A blank stored as a summary is
                    // folded upward as though it were a claim, and every level
                    // above reads summaries rather than text -- so nothing
                    // downstream can tell, the row is perfect and the search
                    // misses it. Anchor retries once at temperature 0 and then
                    // throws; there is no per-call temperature on this path, so
                    // what is kept is the refusal. The schema refuses it again
                    // (paragraphs_summary_is_not_blank), which is the half that
                    // cannot be forgotten at a later call site.
                    //
                    // STUCK and not UNAVAILABLE: nothing was unreachable. The
                    // run had its whole allowance and did nothing with it, which
                    // is what that ending says.
                    return new Answer(null, tally.stopped(Ending.STUCK,
                            "The " + level.name() + " answered with nothing, twice over: a blank"
                                    + " summary would be folded upward as though it were a"
                                    + " claim, so this cascade stopped instead of storing one.",
                            level.name() + ": an empty answer"));
                }
                return new Answer(ran.text().strip(), null);
            }
            // The run's own ceiling, and it says nothing about the rest of the
            // document -- but unlike a curator's undecided candidate there is
            // nothing to skip TO. A paragraph with no summary is a hole in what
            // every level above reads, and a document summary written over a
            // hole is a summary of a document with a paragraph missing, which
            // nothing downstream could see. So the cascade stops, keeps what it
            // wrote, and the next ingest finds exactly this paragraph waiting.
            case TURN_CAP -> {
                return new Answer(null, tally.stopped(Ending.TURN_CAP,
                        "The " + level.name() + " ran out of turns before it answered.",
                        level.name() + ": " + ran.detail()));
            }
            case CALL_BUDGET -> {
                return new Answer(null, tally.stopped(Ending.CALL_BUDGET,
                        "This cascade stopped after spending its whole allowance of "
                                + budget.limit() + " model calls.", ""));
            }
            case CANCELLED -> {
                return new Answer(null, tally.stopped(Ending.CANCELLED,
                        "This cascade was cancelled part-way through a summary.", ""));
            }
            // Not reachable today: AWAITING is spent only by an orchestration's
            // conductor, and a summary level is never one. Its own arm rather
            // than falling to default, so the sentence names what actually
            // happened instead of a dead endpoint that was never the cause.
            case AWAITING -> {
                return new Answer(null, tally.stopped(ran.ending(),
                        "This cascade could not go on: the " + level.name() + " ended waiting"
                                + " for an answer, which a summariser run never asks for.",
                        level.name() + ": " + ran.detail()));
            }
            default -> {
                // Not summarised around, on Curator's terms exactly and for more
                // money: carrying on would spend the rest of the ingest's
                // allowance rediscovering that the endpoint is still down -- one
                // failed run per remaining paragraph, every one charged to the
                // same budget, on a document that may have two hundred of them.
                return new Answer(null, tally.stopped(ran.ending(),
                        "This cascade could not go on: the " + level.name() + " stopped because"
                                + " something it depends on could not be reached.",
                        level.name() + ": " + ran.detail()));
            }
        }
    }

    // --- what a level is asked ------------------------------------------------

    /**
     * A run of summaries as one message: numbered, in order, one to a line.
     *
     * <p>Anchor's {@code joinNumbered}, and the numbering is not decoration. The
     * upper agents are asked to report a reversal — "line 2 sets up a position
     * and line 7 rejects it" — and a model cannot name a line in a list that has
     * no numbers.
     */
    private static String numbered(List<String> summaries) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(i + 1).append(". ").append(summaries.get(i));
        }
        return joined.toString();
    }

    /**
     * What a section reads: its own heading, and the summaries of the paragraphs
     * under it.
     *
     * <p><b>{@link StructuralRef}'s first production render site, and the policy
     * is chosen here rather than derived here.</b> {@link
     * StructuralRef.WhenSynthetic#BLANK} is Anchor's Policy C in its own words —
     * "never feed it into a summariser prompt (the model would echo it back into
     * the generated summary, contaminating the downstream context that uses
     * summaries instead of raw text)". Anchor leaves the slot empty and this
     * says the absence in a sentence instead: {@code SECTION TITLE:} with
     * nothing after it is a label a model has to guess the meaning of, and what
     * it has to guess is exactly the thing the tier above will inherit if it
     * guesses wrong. <b>What is never done is the thing the policy forbids</b> —
     * no invented name reaches the prompt, and the sentinel cannot, because the
     * only string this method can obtain from a synthetic unit is the empty one.
     *
     * <h2>Two kinds of synthetic section, and one sentence used to describe both</h2>
     *
     * <p>{@link SectionDetector} invents a section for two different documents.
     * A chapter with no detectable heading anywhere becomes <em>one</em>
     * synthetic section over the whole of it. A chapter whose first heading has
     * text above it — a paper's title block, its authors, an unlabelled abstract
     * — gets a synthetic section at ordinal 1 for that text and named sections
     * after it. That second case is §3.3(b)'s fix and stage 5's prompt was
     * written before it existed.
     *
     * <p><b>So the opening written for the first case was false in the
     * second</b>, and
     * {@code implementation rationale} §4
     * caught it saying "This document heads no sections in this chapter" to the
     * preamble of a paper that heads five. That is the whole reason it changes:
     * a prompt asserting what the rows beneath it contradict is a defect on its
     * own terms. The same evaluation measured a correlation with a runaway
     * generation — one run against one run, on a model that looped on 5 of 42
     * calls — and <b>that is not evidence this fixes anything about looping.</b>
     *
     * <p><b>The distinction is not carried on the unit, and deliberately.</b>
     * {@link StructuralRef} answers what may be rendered, not why the parser
     * invented something; a third variant would make every render site switch
     * over a distinction none of them can act on, and it would not survive the
     * round trip anyway — this method reads {@link DocumentStore#hierarchy},
     * where a unit is rebuilt from {@code title} and {@code is_synthetic} and
     * there is no third column to rebuild a reason from. What the prompt
     * actually needs is not the detector's motive but whether this section is
     * the whole of what sits under its chapter, and that is a fact about the
     * stored hierarchy the summaries came out of — which is the list the caller
     * is already walking.
     *
     * @param theOnlySection whether this is the one section its chapter has.
     *     A synthetic section that is not is the text above the chapter's first
     *     heading, and there is no other way for one to arise
     */
    private static String forASection(
            StructuralRef title, boolean theOnlySection, List<String> summaries) {
        String heading = title.render(StructuralRef.WhenSynthetic.BLANK);
        String opening;
        if (!heading.isEmpty()) {
            opening = "This section is headed \"" + heading + "\".";
        } else if (theOnlySection) {
            opening = "This document heads no sections in this chapter, so this section is the"
                    + " whole of it.";
        } else {
            opening = "This section is the text above this chapter's first heading, so the"
                    + " document gives it no heading of its own.";
        }
        return opening + " These are the summaries of the paragraphs in it, in order:\n\n"
                + numbered(summaries);
    }

    /**
     * What a chapter reads: its own heading, and the summaries of the sections
     * under it.
     *
     * <p><b>The synthetic sentence here is load-bearing rather than symmetrical.</b>
     * {@code chapter-summary.txt} carries the instruction that makes the whole
     * synthetic path work — "For documents without explicit chapter divisions
     * (where this is the only chapter), treat the document's body as a single
     * argumentative arc and summarise accordingly" — and in Anchor a model has
     * no way to tell whether it is in that case, because the only signal is an
     * empty slot. A synthetic chapter is exactly a document that declared no
     * chapters, and {@code ChapterDetector}'s fallback emits one and only one,
     * so saying so is stating a fact this server knows and Anchor's prompt only
     * hoped for.
     *
     * <p><b>That stopped being the only kind of synthetic chapter.</b>
     * {@code ChapterDetector} now invents one for the text above a document's
     * first chapter heading, the way {@link SectionDetector} does one level
     * down, so this method has the same second case {@link #forASection} does
     * and for the same reason. Adding the preamble chapter without adding the
     * sentence for it would have shipped the exact falsehood being fixed below:
     * a document that declares two chapters being told it declares none.
     *
     * @param theOnlyChapter whether this is the one chapter the document has.
     *     A synthetic chapter that is not is the text above the document's first
     *     chapter heading, and — as with a section — there is no other way for
     *     one to arise
     */
    private static String forAChapter(
            StructuralRef title, boolean theOnlyChapter, List<String> summaries) {
        String heading = title.render(StructuralRef.WhenSynthetic.BLANK);
        String opening;
        if (!heading.isEmpty()) {
            opening = "This chapter is headed \"" + heading + "\".";
        } else if (theOnlyChapter) {
            opening = "This document declares no chapters of its own, so this chapter is its"
                    + " whole body.";
        } else {
            opening = "This chapter is the text above this document's first chapter heading, so"
                    + " the document gives it no heading of its own.";
        }
        return opening + " These are the summaries of the sections in it, in order:\n\n"
                + numbered(summaries);
    }

    /**
     * What the top level reads: the document's name, and the summaries under it.
     *
     * <p>The name is the one fact about the document that is not derived from
     * its own text, and Anchor gives its top level the same. <b>It is given as
     * what the corpus files the document as rather than as a title to be
     * trusted</b>: {@code TextExtraction} derives a title from the file name for
     * every format this server reads, so a document called {@code notes} is
     * named after somebody's disk and not after its own argument.
     */
    private static String forTheDocument(String title, List<String> summaries) {
        return "This document is filed as \"" + title + "\". These are the summaries of"
                + " everything under it, in order:\n\n" + numbered(summaries);
    }

    // --- what a cascade came to -----------------------------------------------

    private static Outcome unavailable(String why, String title) {
        return new Outcome(Ending.UNAVAILABLE,
                "'" + title + "' could not be summarised: " + why + ".", 0, 0, "");
    }

    /** What a cascade has done so far, so that a stopped one can say it. */
    private static final class Tally {

        private final String title;
        private int calls;
        private int folds;

        private Tally(String title) {
            this.title = title;
        }

        private Outcome finished() {
            return new Outcome(Ending.ANSWERED,
                    "Summarised '" + title + "': " + calls + " model call(s), of which " + folds
                            + " folded a run of summaries into one, and the document now has a"
                            + " summary of its own.", calls, calls, "");
        }

        /**
         * What a cascade that stopped says, and it has to say what is now
         * missing.
         *
         * <p>{@code IngestService.stopped}'s sentence at the moment it becomes
         * true one derivation over: a document stored with some paragraphs
         * unlabelled has no document summary and never gets one from this run,
         * and an outcome that reported plain failure would leave somebody
         * unable to tell a corpus that lost half a cascade from one that never
         * started it.
         */
        private Outcome stopped(Ending ending, String why, String detail) {
            String text = why + " '" + title + "' kept the " + calls
                    + " summary/summaries it had already paid for; ingesting it again"
                    + " summarises what is left and re-summarises nothing.";
            if (ending == Ending.UNAVAILABLE || ending == Ending.STUCK) {
                log.warn("the cascade over '{}' stopped {} after {} model call(s): {}",
                        title, ending, calls, detail);
            }
            return new Outcome(ending, text, calls, calls, detail);
        }
    }
}
