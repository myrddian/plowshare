package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/**
 * {@code document_ask} — one document, asked a question, named the way a person
 * names one.
 *
 * <h2>The gap this closes, and it is not the one it looks like</h2>
 *
 * <p>Stage 8 built the deliberation and gave it three doors: {@code POST
 * /v1/documents/&#123;id&#125;/ask}, the {@code document_ask} MCP tool, and
 * {@code plowshare document ask}. Every one of them takes a document id. <b>No
 * surface of this system rendered one</b> when this tool was written: {@code
 * DocumentSearchResponse} carries {@code documentId} on every hit and {@code
 * ServerClient.DocumentHit} holds it, and all three renderers of a search
 * dropped it — this package's {@code DocumentTools.render} printed the source
 * name, the title, the paragraph ordinal and the paragraph id; the MCP tool's
 * renderer printed the same five fields; the CLI printed them too. So the ask
 * was reachable from {@code curl} and, in practice, from nothing else — and the
 * MCP tool's own description told a model <i>"Use document_search first ... it
 * needs that document's id"</i>, which was advice that could not be followed.
 *
 * <p><b>All three now print it</b> — TODO.md 4.5, closed on 2026-09-05: each
 * hit carries an {@code ask document} line under its {@code cite paragraph} one.
 * <b>That changes nothing below.</b> The argument for resolving a name inside
 * this tool was never that no id could be had; it is that this tool's caller is
 * a model holding a person's words, and a person names a paper by what it is
 * about. The paragraphs that follow are what they were.
 *
 * <p>That is why the resolution is <b>inside this tool</b> rather than a second
 * tool beside it. Anchor's shell resolves once, into session state — {@code
 * use <id-or-title>} binds a {@code volatile AnchorDocument} and {@code
 * describe}, {@code retrieve} and {@code ask} are unavailable until it does.
 * <b>Plowshare has no such state to bind into and should not grow one here.</b>
 * A conversation is the session state this system already has, and it is
 * durable, inspectable and shared by every front end; a bound document would be
 * a second one, invisible in the log, reachable from one client. So the binding
 * <em>is</em> the transcript: this tool says back the id it resolved to, that
 * sentence is a tool result in the conversation, and the next ask spends the id
 * directly. Resolution happens once per document per conversation, as Anchor's
 * does, without a mutable field anywhere.
 *
 * <p><b>And the availability gate is a refusal rather than a hidden tool</b>,
 * which is the other half of the same argument. Spring Shell can hide a command
 * because its menu is recomputed per keystroke; a tool block is serialised into
 * every request and an agent's prefix is extension-only — {@code implementation rationale}
 * §6 measures what breaking that costs. A tool that appeared and disappeared
 * between turns would rewrite the front of every prompt for a saving of one
 * description.
 *
 * <h2>What is searched is what the document says, and not its title</h2>
 *
 * <p>Anchor resolves a substring against {@code documents.title}. This one
 * cannot: {@code DocumentStore} offers {@code find(UUID)} and {@code
 * find(String sourceName)} — an <em>exact</em> filename — and no title query at
 * all, and adding one is a change to the corpus's own reader. So the resolver
 * is {@link RetrievalService#search}, the same hybrid ranking {@code
 * document_search} runs, collapsed to the distinct documents it named. <b>A
 * paper is therefore found by something it discusses</b>, which is usually
 * better than a title and is occasionally worse — a paper whose only mention of
 * its own author is on a cover page that did not chunk well is found by its
 * subject and not by its byline. The description says so, because a caller that
 * believes this is a title index will read an empty answer as a missing
 * document.
 *
 * <h2>More than one document is a refusal, and the asymmetry is deliberate</h2>
 *
 * <p>The ranking is asked for {@link RetrievalService#MAX_HITS} rather than for
 * a smaller number, which makes a second document <em>more</em> likely to be
 * seen and an ambiguity refusal more common. That is the safe direction, and it
 * is not a close call: guessing costs four model calls spent answering about the
 * wrong paper, and what comes back from a wrong guess is a fluent, grounded,
 * correctly attributed answer to a question nobody asked — the exact failure
 * this apparatus exists to catch, arriving through the one door it does not
 * watch. Refusing costs one turn, and the refusal hands back the ids, so the
 * next call is exact.
 *
 * <h2>It blocks, and the caller pays one turn and no model calls</h2>
 *
 * <p>A pass is three model calls in series and a fourth when the critic's JSON
 * has to be asked for again, at 22–60 s each on the hardware this was measured
 * on: minutes. <b>Blocking is still right, and start-and-poll is the expensive
 * choice here rather than the cheap one.</b>
 *
 * <ul>
 *   <li><b>Blocking costs one turn and zero model calls of the caller's own
 *       allowance.</b> {@link Asking} is handed a fresh {@link Budget} built
 *       from {@code plowshare.documents.ask-budget}, so the pass spends the
 *       system's number — {@code Curator.pass}' precedent — and the calling
 *       agent's {@code max-model-calls} is untouched by all four calls.
 *   <li><b>Start-and-poll costs a turn and a model call per poll.</b> A tool
 *       returning a handle would put the caller in a loop it pays for: every
 *       poll is a turn against {@code max-turns} and a completion against {@code
 *       max-model-calls}, at minutes of wall clock and no way to sleep. An agent
 *       burning its turn cap waiting is the failure mode, and it is what {@code
 *       librarian}'s twenty turns and twelve calls would be spent on.
 *   <li><b>Nothing is held while it waits.</b> {@code JobStore}'s javadoc owns
 *       the measurement: a job is a virtual thread, the scarce resource is the
 *       lane, and a job blocked inside a tool holds no lane. {@link
 *       AgentRunTool} blocks in exactly this shape for exactly this reason, and
 *       {@code a_parent_blocked_on_a_child_holds_no_lane_slot} pins it. There is
 *       no watchdog on a tool call to trip, either — {@code JobRuntime} measures
 *       how long one took and bounds nothing.
 * </ul>
 *
 * <p>The MCP {@code document_ask} takes the other choice and is right to:
 * <em>its</em> caller is a foreign harness over HTTP, where a request open for
 * four minutes is a timeout somewhere in between. Two callers, two shapes, one
 * deliberation.
 *
 * <h2>Built per run, for the cancellation flag alone</h2>
 *
 * <p>{@link AgentRunTool}'s shape narrowed to one field. A shared instance would
 * have nothing but {@code () -> false} to hand down, so cancelling a job blocked
 * in here would leave three or four model calls running for nobody. The budget
 * is <em>not</em> the run's — that is the whole point of the paragraph above —
 * so this is registered as an ordinary shared tool and {@link JobRuntime}
 * rebinds it per run through {@link #forRun}; {@link #schema()} is the same
 * object either way, so what a context prices is what a run is offered.
 *
 * <h2>The answer is quoted whole, and that is a reversal</h2>
 *
 * <p>{@code Deliberation.Tally.answered} leaves the synthesiser's prose at
 * column zero and says why: quoting a whole answer would make it unreadable, and
 * <i>"the reader of this text is a person, not an agent"</i>. <b>This tool is
 * what makes that sentence false.</b> The first reader is now a model choosing
 * its next move, the prose is a model roleplaying a document in the first
 * person, and the blocks under it quote uploaded text verbatim — including, in
 * the failed block, words the document does <em>not</em> contain. So the trade
 * flips back to {@code DocumentTools}' rule, every line at column zero is one
 * this renderer wrote, and the whole outcome is quoted.
 *
 * <p><b>What is not quoted is one line saying what this renderer did</b>, and it
 * carries the instruction that matters: reproduce the grounding blocks rather
 * than summarising them. That is an instruction and not a guarantee, and it is
 * named as the weak point here rather than dressed up — a calling agent that
 * paraphrases the answer has destroyed the capability, and no rendering can stop
 * it. What the rendering can do is make the blocks impossible to mistake for the
 * agent's own words, which is what the quoting is for.
 */
public final class AskTool implements AgentTool {

    /** The name the model calls, the job log records, and {@code
     *  AgentRegistry.load} is told about. Spelled once. */
    public static final String NAME = "document_ask";

    /** What every line of the answer is prefixed with; {@code DocumentTools}'
     *  convention and {@code MemoryTools}' before it. */
    private static final String QUOTE = "> ";

    /** The full Unicode linebreak set, not the three {@code String.lines()}
     *  splits on — {@code MemoryTools} owns the measurement. */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private final Asking asking;
    private final RetrievalService corpus;
    private final IntSupplier allowance;
    private final BooleanSupplier cancelled;
    private final ToolSchema schema;
    private io.aeyer.plowshare.server.information.InformationAccess policy;
    private String owner;
    private String callerSession;
    private Budget rootBudget;
    private io.aeyer.plowshare.server.information.InformationJobs inputs;
    private String parentLog;
    private java.util.function.Consumer<UUID> inputAudit;

    /**
     * The shared instance, which answers no run and cancels for nobody.
     *
     * @param asking the deliberation, behind the seam. See {@link Asking} for
     *     why it is not {@code Deliberation} itself
     * @param corpus the ranking a name is resolved through. The same service
     *     {@code document_search} reads, deliberately: two resolvers over one
     *     corpus would disagree, and the one a caller can see is that one
     * @param allowance {@code plowshare.documents.ask-budget}, read per call
     *     rather than captured, so an operator moving it moves the next pass
     */
    public AskTool(Asking asking, RetrievalService corpus, IntSupplier allowance) {
        this(asking, corpus, allowance, () -> false, null);
    }

    private AskTool(
            Asking asking, RetrievalService corpus, IntSupplier allowance,
            BooleanSupplier cancelled, ToolSchema schema) {
        this.asking = Objects.requireNonNull(asking, "asking");
        this.corpus = Objects.requireNonNull(corpus, "corpus");
        this.allowance = Objects.requireNonNull(allowance, "allowance");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        // Built once on the shared instance and handed to every bound copy, so
        // that schemasOfferedTo and a real run compare equal by identity as well
        // as by value. ModelSurfaceTest's
        // what_a_context_prices_is_what_a_run_is_offered is what would notice.
        this.schema = schema != null
                ? schema
                : new ToolSchema(NAME, DESCRIPTION, askSchema());
    }

    /**
     * The same tool, able to stop when this run does.
     *
     * @param cancelled the run's flag, asked by the pass at every stage boundary
     */
    AskTool forRun(BooleanSupplier cancelled) {
        return new AskTool(asking, corpus, allowance, cancelled, schema);
    }

    AskTool forRun(BooleanSupplier cancelled,
            io.aeyer.plowshare.server.information.InformationAccess access, String account,
            Budget budget, String session) {
        AskTool bound = forRun(cancelled);
        bound.policy = access;
        bound.owner = account;
        bound.rootBudget = budget;
        bound.callerSession = session;
        return bound;
    }

    AskTool withInputs(io.aeyer.plowshare.server.information.InformationJobs inputs, String log) {
        this.inputs = inputs;
        this.parentLog = log;
        return this;
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
        // Outside the try: a null here is the runtime's bug and not the model's,
        // and the never-throw rule is about a caller's mistakes. See AgentTool.
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        // Required although it is never read, for DocumentTools' reason exactly:
        // V18 stores no project column, so a corpus has no tier — and the day it
        // grows one, a tool that had quietly accepted a null home would start
        // answering from one.
        Objects.requireNonNull(home, "home");
        if (policy != null) {
            var context = policy.forRun(owner, home);
            var reader = corpus.scoped(policy, context);
            if (inputs != null) reader = reader.audited(inputs.reads(parentLog, context));
            AskTool bound = new AskTool(asking.scoped(policy, context, callerSession, parentLog),
                    reader, allowance, cancelled, schema);
            if (inputs != null) bound.inputAudit = inputs.reads(parentLog, context);
            bound.rootBudget = rootBudget;
            return bound.run(argumentsJson, home);
        }
        try {
            return answer(argumentsJson);
        } catch (BadArguments unusable) {
            // Every validation failure below lands here, and nothing else does.
            // An embedding endpoint that could not turn the name into a vector
            // is not a mistake the model made and not one it can correct by
            // calling again; rendering it as "no document matched" would be a
            // confident empty answer about a corpus that was never searched.
            // DocumentTools.Search takes the same two-way split.
            return unusable.getMessage();
        }
    }

    private String answer(String argumentsJson) {
        JsonNode args = ToolArguments.parse(argumentsJson, NAME,
                "{\"document\": \"the retry budget paper\", \"question\": \"how is it"
                        + " refilled\"}");
        String named = ToolArguments.requireText(args, "document", NAME,
                "the document's id, or words naming the one you mean");
        String question = ToolArguments.requireText(args, "question", NAME,
                "what you want to know about that document, in plain language");

        UUID direct = asUuid(named);
        if (direct != null) {
            return asked(direct, "You asked document " + direct + " by id.", question);
        }

        RetrievalService.Found found = corpus.search(named, RetrievalService.MAX_HITS);
        List<Candidate> candidates = distinctDocuments(found.hits());
        if (candidates.isEmpty()) {
            return nothingMatched(named, found);
        }
        if (candidates.size() > 1) {
            return ambiguous(named, candidates);
        }
        Candidate only = candidates.get(0);
        return asked(only.documentId(),
                "\"" + oneLine(named) + "\" names one document in this corpus: " + only.describe()
                        + ", document " + only.documentId() + ". Ask it again by that id and no"
                        + " search is run.",
                question);
    }

    // --- the pass ----------------------------------------------------------------

    /**
     * Spend a pass on one document and render what it came to.
     *
     * <p>The budget is built here and now — {@code allowance.getAsInt()} rather
     * than a captured number — so an operator who moved {@code
     * plowshare.documents.ask-budget} moves the next ask rather than the next
     * boot. {@code Budget.of} refuses a non-positive number by throwing, which
     * is right and unreachable: {@code DeliberationConfig} refuses anything
     * below {@code A_PASS} before this server accepts a request.
     */
    private String asked(UUID document, String resolution, String question) {
        if (inputAudit != null) inputAudit.accept(document);
        Outcome outcome = asking.ask(document, question,
                rootBudget == null ? Budget.of(allowance.getAsInt()) : rootBudget,
                cancelled);

        StringBuilder out = new StringBuilder();
        if (outcome.ending() != Outcome.Ending.ANSWERED) {
            // FIRST, AND IN THIS RENDERER'S WORDS. Every ending but ANSWERED
            // carries the orchestrator's or the runtime's own sentence in
            // Outcome.text, and that sentence reads like prose; quoted under a
            // heading that said nothing, a refusal would be indistinguishable
            // from a short answer. Naming the ending is the one line that stops
            // "this document could not be asked" being read as what the document
            // said.
            out.append("No answer: the pass ended ").append(outcome.ending())
                    .append(" rather than answering, after ").append(outcome.modelCalls())
                    .append(" model call(s). ").append(resolution)
                    .append(" What it has to say is below, quoted.\n\n");
            return out.append(quote(outcome.text())).toString();
        }
        out.append(resolution).append(' ').append(CARRY_IT_THROUGH).append("\n\n");
        return out.append(quote(outcome.text())).toString();
    }

    /**
     * The one instruction this renderer gives, and the one it cannot enforce.
     *
     * <p>Stage 8's grounding is a paragraph id and a verbatim quote, checked
     * against the paragraph, with a failure <b>rendered loudly rather than
     * dropped</b> — <i>"silence would hide the exact failure this system exists
     * to find"</i>. Between that rendering and a person there is now an agent,
     * and an agent that summarises has removed the check while keeping the
     * answer, which is worse than not having asked: a reader is handed something
     * that looks grounded and is no longer checkable.
     *
     * <p><b>Stated as a duty rather than as a formatting note</b>, because that
     * is what it is. It cannot be enforced from here — nothing in a tool result
     * binds what a model writes next — so the definition of any agent granted
     * this tool has to say it too, and {@code close_reader.md} does at length.
     * Two statements of one rule, in the two places a model reads.
     */
    private static final String CARRY_IT_THROUGH =
            "The answer is below, quoted whole. Its GROUNDED IN and ATTRIBUTION FAILED blocks"
                    + " are the only record of which claims were checked against the paragraphs"
                    + " they name and which failed that check: pass them on as they stand."
                    + " Summarising them away leaves an answer that looks grounded and is no"
                    + " longer checkable, which is worse than not having asked.";

    // --- the ser ------------------------------------------------------------------

    /** The id, or null for anything that is not one. Not an exception: a name is
     *  the ordinary argument and a uuid is the exact one. */
    private static UUID asUuid(String named) {
        try {
            return UUID.fromString(named.strip());
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * The documents a ranking named, once each, best first.
     *
     * <p>Insertion-ordered on {@code DocumentTools.Search#arguments}' reasoning:
     * the order a corpus-wide ranking first mentions a document is the only
     * ordering over documents this ranking has, and it is the one a caller would
     * infer from the hits anyway.
     */
    private static List<Candidate> distinctDocuments(List<DocumentStore.Hit> hits) {
        Map<UUID, Candidate> byDocument = new LinkedHashMap<>();
        for (DocumentStore.Hit hit : hits) {
            byDocument.putIfAbsent(hit.documentId(),
                    new Candidate(hit.documentId(), hit.sourceName(), hit.title()));
        }
        return new ArrayList<>(byDocument.values());
    }

    /**
     * A document a name might have meant.
     *
     * <p>Both strings are somebody's upload — a filename they chose and a title
     * V18 froze off the document — so both are flattened onto one line before
     * they reach column zero. {@code DocumentTools.Search#arguments} renders the
     * same pair the same way and owns the argument: a title carrying a break
     * would otherwise turn one heading into two.
     */
    private record Candidate(UUID documentId, String sourceName, String title) {

        String describe() {
            return oneLine(sourceName) + " — \"" + oneLine(title) + "\"";
        }
    }

    /**
     * More than one document, so no pass was spent.
     *
     * <p>Anchor's {@code AnchorClient.use} refuses the same case in the same
     * direction — <i>"Ambiguous title substring ... disambiguate or pass the
     * UUID"</i> — and this says the ids out loud, which is the half a caller
     * needs and the half no other surface of this system provides.
     */
    private static String ambiguous(String named, List<Candidate> candidates) {
        StringBuilder out = new StringBuilder();
        out.append('"').append(oneLine(named)).append("\" matches ").append(candidates.size())
                .append(" documents in this corpus, so nothing was asked. A pass is three model"
                        + " calls and a fourth when the critic has to be asked again, and an"
                        + " answer about the wrong paper would be grounded, attributed and about"
                        + " a question nobody put. Call again with one of these as `document`,"
                        + " best match first.\n");
        for (Candidate candidate : candidates) {
            out.append('\n').append(candidate.describe()).append('\n')
                    .append("document ").append(candidate.documentId()).append('\n');
        }
        return out.toString();
    }

    /**
     * Nothing matched, and which of the three kinds of nothing it is.
     *
     * <p>{@code DocumentTools.Search#nothing}'s three cases and its argument
     * exactly: one empty ranking means the name found nothing, or that the
     * corpus holds no document at all, or that it holds documents nothing
     * embedded, and only the first is worth naming the document differently for.
     * The first case carries one sentence that tool does not need — that this is
     * a search of what documents <em>say</em> — because a caller that believes
     * it is a title index reads an empty answer as a corpus that is missing a
     * paper.
     */
    private static String nothingMatched(String named, RetrievalService.Found found) {
        if (!found.corpusIsEmpty()) {
            return "Nothing in the corpus is close to \"" + oneLine(named) + "\", so no document"
                    + " could be resolved and nothing was asked. This resolves a document by"
                    + " what it SAYS rather than by its title: name something the document"
                    + " actually discusses, or pass its id as `document`. document_search"
                    + " returns the same ranking if you want to see what is there.";
        }
        if (found.unsearchable() == 0) {
            return "The corpus is empty: no document has been ingested, so there is nothing to"
                    + " resolve \"" + oneLine(named) + "\" against and nothing to ask. This says"
                    + " nothing about the question.";
        }
        return "Nothing in the corpus can be searched, so \"" + oneLine(named) + "\" could not be"
                + " resolved to a document. All " + found.unsearchable() + " passage(s) hold"
                + " their text and no vector, which is what an ingest leaves behind when the"
                + " embedding endpoint could not be reached. Ingesting those documents again"
                + " embeds them. A document id passed as `document` still works — an ask does"
                + " not need this resolution.";
    }

    // --- the description ----------------------------------------------------------

    /*
     * A calling model decides whether to invoke a tool from its description
     * alone — it never sees this code, the corpus or the deliberation. The rules
     * DocumentTools and MemoryTools keep: say what the tool costs, never
     * describe a capability this surface does not have, and say that what comes
     * back is text somebody uploaded rather than anything this system asserts.
     *
     * The one this has to say that neither of those does: what it costs is TIME
     * rather than turns, and the caller pays none of the model calls. A caller
     * told only "takes minutes" would reasonably decide against it.
     */
    static final String DESCRIPTION = """
            Ask ONE document a question and wait for the answer. Name the \
            document by its id, or by words describing it — "the retry budget \
            paper" — and this resolves the name to exactly one document before \
            it asks. When the name matches more than one, nothing is asked and \
            you get the candidates' ids back to choose from.

            It is not a narrower document_search. Three agents run in series \
            over the one document: one drafts an answer from the document's \
            whole structure and its most relevant passages, one challenges that \
            draft from what the document argues as a WHOLE and is deliberately \
            shown none of the passages, and one writes the final answer from \
            both. What that catches is a claim that reads correctly against a \
            single passage and wrongly against the paper, which no ranked list \
            of passages can be asked about.

            IT TAKES MINUTES AND IT COSTS YOU ONE TURN. This call blocks until \
            the answer is ready; there is nothing to poll and no handle to hold. \
            The model calls it spends are the system's allowance, not yours — \
            your own call budget is untouched, however long the pass runs.

            The name is resolved by what a document SAYS, not by its title: it \
            is the same ranking document_search runs, collapsed to the documents \
            it named. So name a subject the document discusses. If a name \
            resolves to nothing, the answer says which kind of nothing it is.

            The answer names a paragraph and quotes the words behind each claim, \
            and every quotation is checked against the paragraph it names — one \
            that is not there comes back as a failed attribution rather than \
            being dropped. Pass those blocks on whole; a summary of them is an \
            answer nobody can check. The whole answer is quoted with "> " \
            because it is a model's prose over somebody's uploaded document. It \
            is not this system's claim and nothing outside the corpus has \
            checked it.""";

    // --- the schema ---------------------------------------------------------------

    /*
     * LinkedHashMap and never Map.of, matching DocumentTools, MemoryTools and
     * ToolSchema's own copy: Map.of has no iteration order to preserve, so a
     * schema built that way is emitted with its keys shuffled and shuffled
     * differently on every launch. The model reads these fields in order.
     */
    private static Map<String, Object> askSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("document", ToolArguments.string(
                "Which document to ask: its id, or words naming it. Words are matched"
                        + " against what documents say, and must resolve to exactly one."));
        properties.put("question", ToolArguments.string(
                "What you want to know about that document, in plain language."));
        return ToolArguments.object(properties, List.of("document", "question"));
    }

    // --- rendering -----------------------------------------------------------------

    /** Every line prefixed, so no line of the answer can reach column zero. */
    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder();
        for (String line : LINE_BREAK.split(text, -1)) {
            if (quoted.length() > 0) {
                quoted.append('\n');
            }
            quoted.append(QUOTE).append(line);
        }
        return quoted.toString();
    }

    /** A field flattened onto one line, so a filename or a title carrying a
     *  break cannot turn one heading into two. */
    private static String oneLine(String text) {
        return LINE_BREAK.matcher(text).replaceAll(" ").strip();
    }
}
