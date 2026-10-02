package io.aeyer.plowshare.client;

import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.protocol.search.SearchPage;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the client can ask the server for. The archive lives on the other
 * side of this interface and nothing on this side holds durable state.
 *
 * <p>An interface rather than the HTTP class itself, for the same reason {@code
 * EmbeddingClient} is one on the server: {@code MemoryToolsTest} stubs it, so
 * the tests measure how a tool maps arguments and renders results rather than
 * measuring a socket.
 *
 * <h2>Two ways a call fails, and they must not be confused</h2>
 *
 * <ul>
 *   <li><b>{@link IOException}</b> — the server was not reached at all. It is
 *       declared checked, and deliberately not translated here, because the
 *       caller that has to say something legible about it is {@code
 *       MemoryTools}: a checked exception is what makes forgetting to say
 *       anything a compile error rather than an empty answer.
 *   <li><b>{@link ServerError}</b> — the server answered, and said no. A 404
 *       for an unknown id and a 422 for a malformed proposal are facts about
 *       the request, not about the network, and reporting them as "the server
 *       is unreachable" would send the caller off restarting a server that is
 *       fine.
 * </ul>
 */
public interface ServerClient {

    /** Where this client is pointed, for messages that have to say so. A
     *  "could not reach the server" with no address in it is a message the
     *  reader cannot act on. */
    record Navigation(String level, List<String> ids, String text, boolean complete, int modelCalls) {}
    default Navigation navigateMemory(String project, String question) throws IOException {
        throw new IOException("This client transport does not implement memory navigation");
    }
    default StartedJob digestMemory(String project) throws IOException {
        throw new IOException("This client transport does not implement memory digest building");
    }

    /**
     * {@code POST /v1/search} — one page of results, with no provider key ever
     * in the answer. See {@link SearchPage}'s own javadoc for the whole
     * contract: a domain-level miss comes back as {@link SearchPage#refusal}
     * rather than a thrown exception, and only an unreachable server or a
     * malformed request reaches this method as {@link IOException} or {@link
     * ServerError}.
     *
     * @param pageSize how many hits one page holds
     * @param max the most hits worth fetching on a fresh search — a ceiling,
     *     not a target. Part of what identifies the stored result set a later
     *     page is served from, so a caller reading page 2 of one search must
     *     send the SAME {@code max} it sent for page 1; a different value is a
     *     different search and starts over from page one
     * @param page which page to serve, counting from one
     */
    default SearchPage search(String query, int pageSize, int max, int page) throws IOException {
        throw new IOException("This client transport does not implement search");
    }

    /**
     * {@code POST /v1/fetch} — one window of one page's readable text, on
     * exactly {@code FetchService.read}'s own shape: {@link
     * FetchWindow#nextOffset()} from one answer is what a caller sends back as
     * {@code offset} to keep reading, there is no handle minted anywhere in
     * between, and a page that could not be read comes back as {@link
     * FetchWindow#refusal()} in a 200 rather than a thrown exception — the
     * identical domain-miss-versus-caller-mistake split {@link #search}'s own
     * javadoc argues, and for the same reason: a dead host or a suppressed
     * domain is not this client's fault and not the caller's either.
     *
     * @param offset where to start this window, counting characters into the
     *     page's whole stored text; {@code 0} for a page never read before, or
     *     re-issued from the previous window's {@code nextOffset} to keep
     *     reading
     */
    default FetchWindow fetch(String url, int offset) throws IOException {
        throw new IOException("This client transport does not implement fetch");
    }

    /** New information controls travel over the authenticated WebSocket, never a REST fallback. */
    default Object information(String operation,java.util.Map<String,Object> payload) throws IOException {
        throw new IOException("This client transport does not implement information WebSocket frames");
    }

    String baseUrl();

    /**
     * File a proposal in one tier.
     *
     * <p><b>No verdict.</b> The shape of a write — new, a refinement, or a
     * replacement — is judged server-side by the scribe, against candidates it
     * retrieves itself; the answer comes back on {@link WriteResult}. This used
     * to take one and every caller passed {@code NEW}. Letting a caller name a
     * target instead would hand it the power to retire memories it never read,
     * which is why the parameter is gone rather than nullable.
     *
     * @param project the tier; {@code null} means the global one, matching
     *     {@link io.aeyer.plowshare.protocol.Home}. A blank string is passed
     *     through rather than folded into {@code null} — the server refuses it,
     *     and folding it here would let an unset field silently write into the
     *     tier every project reads.
     */
    WriteResult write(String project, MemoryProposal proposal) throws IOException;

    /** The memories nearest this question, and how much of the archive the
     *  search could not look at. A project also draws on global. */
    Recall recall(String project, String question, Integer limit) throws IOException;

    /** One memory in full, in whatever state it is in — tombstones included,
     *  because the reason on a tombstone is the thing that stops the stale fact
     *  being rediscovered and written back in. */
    Memory read(String id) throws IOException;

    /** One tier's index: active records only, as summary lines with no body. */
    List<IndexEntry> index(String project) throws IOException;

    /**
     * The passages of the document corpus nearest this question.
     *
     * <p><b>Text goes over, never a vector.</b> The corpus's chunks were
     * embedded by whatever {@code plowshare.llm.embedding-model} named on the
     * server, and a vector computed anywhere else is not a worse ranking — it is
     * no ranking at all, computed without error and sorted without complaint. So
     * the server embeds, and there is no parameter here for a caller that
     * thought it could help.
     *
     * <p><b>No project.</b> Every other read here takes one; this does not,
     * because there is nothing for it to select. The corpus has no tier — "a
     * memory has exactly one home; a document has none. Memory is scoped because
     * contradiction needs an owner. Documents are not because relevance does
     * not." A nullable parameter would make that look revisable from here while
     * the server had no column to answer it with.
     *
     * @param limit how many at most, or {@code null} for the server's own
     *     number. <b>The server caps it either way</b> and the answer carries
     *     the figure it used, which is what {@link DocumentSearch#limit}
     *     is for — this module holds no copy of the cap
     */
    DocumentSearch searchDocuments(String query, Integer limit) throws IOException;

    /**
     * What a corpus search found, and what it could not look at.
     *
     * @param limit the number the server actually applied, which is smaller than
     *     what was asked for exactly when the request was above its cap
     * @param searchable how many passages in the corpus a question can reach
     * @param unsearchable how many hold their text and no vector, and so were
     *     skipped whatever the question was. <b>{@link Recall#unsearchable}'s
     *     reason at corpus scale</b>: an empty list is a conclusion a harness
     *     acts on, so every way of producing one that is not "the corpus holds
     *     nothing close" has to be distinguishable from it — and here a single
     *     failed ingest can make the whole corpus unanswerable while every word
     *     of it is on disk
     */
    record DocumentSearch(
            String query, int limit, List<DocumentHit> hits, int searchable, int unsearchable) {}

    /**
     * One passage, and the citation that outlives it.
     *
     * @param chunkId what matched, and not the thing to cite: a chunk is an
     *     artefact of the chunker, so it moves whenever the chunk rule does
     * @param paragraphId <b>the citation.</b> Stable across a re-ingest that
     *     left the paragraph's text alone, and replaced rather than repointed by
     *     one that changed it — so a citation kept across an edit either still
     *     means these words or means nothing
     * @param paragraphOrdinal where it sits in the document today. Position, not
     *     identity: it moves when a paragraph is inserted above it
     * @param text <b>content the server did not write.</b> Somebody's uploaded
     *     paper, note or source, surfaced out of context — not a claim this
     *     system makes and not something anything has checked
     */
    record DocumentHit(
            UUID chunkId, String text, double similarity, UUID paragraphId, String paragraphText,
            int paragraphOrdinal, UUID documentId, String sourceName, String title) {}

    /**
     * <b>The chunks nearest a question, each carrying the whole paper around
     * it.</b>
     *
     * <p>{@code POST /v1/documents/retrieve}. {@link #searchDocuments} beside it
     * answers "which paragraph should I cite"; this answers "what does this
     * passage sit inside", which is a different question and the one Anchor's
     * hierarchy was built for — its own row javadoc calls the answer "one row per
     * chunk with no follow-up reads".
     *
     * @param query what is being asked, in prose
     * @param documentId the document to read, or <b>null for the whole
     *     corpus</b>. Anchor's shell never reaches the corpus-wide mode: every
     *     command there that retrieves is unavailable until a document is bound
     * @param limit how many chunks at most, or null for the server's default
     */
    Retrieved retrieve(String query, String documentId, Integer limit) throws IOException;

    /**
     * @param document the document that was read, or null for the whole corpus.
     *     <b>Echoed because an empty {@code hits} means something different
     *     under each</b> — the corpus holds nothing close, or this paper does
     * @param limit the figure the server applied, after its own cap
     */
    record Retrieved(String query, UUID document, int limit, List<RetrievedHit> hits) {}

    /**
     * @param score nearness in {@code [0, 1]}
     * @param chunk the chunk and every tier above it. <b>The same object {@code
     *     GET /v1/documents/chunks/&#123;id&#125;} answers with</b>, which is
     *     what makes that follow-up read unnecessary rather than merely
     *     available
     */
    record RetrievedHit(double score, ChunkDetail chunk) {}

    /**
     * One chunk and its whole ancestry.
     *
     * @param text <b>content the server did not write.</b> Somebody's uploaded
     *     paper, and so is every summary's subject; whatever renders this owes
     *     the quoting rule this family keeps
     * @param paragraphId <b>the citation</b>, where {@link #chunkId} is not:
     *     {@link DocumentHit}'s distinction, unchanged
     * @param section where in the document this sits, or <b>null for a paragraph
     *     the hierarchy cannot place</b>. Not an error and not a gap in the
     *     answer — a document re-ingested since the hierarchy was written can
     *     hold text that belongs to no section, and the server says so rather
     *     than dropping the row
     * @param chapter the chapter above that section, null under the same
     *     condition and no other
     */
    record ChunkDetail(
            UUID chunkId, String text, UUID paragraphId, int paragraphOrdinal,
            String paragraphSummary, Unit section, Unit chapter, UUID documentId,
            String sourceName, String title, String documentSummary) {}

    /**
     * One chapter or section of a document.
     *
     * <p><b>{@code title} is null exactly when {@code synthetic} is true</b>, and
     * a renderer must not print the null. The server's {@code StructuralRef}
     * discards a parser-invented unit's stored title at the one place that
     * builds this, so what arrives is an honest absence; turning that absence
     * into words a person reads is this side's job, and {@code
     * Structural.name} is where it is done. Anchor's shell is the cautionary
     * case: it interpolates the null straight into a heading and prints the four
     * letters "null".
     *
     * @param title the document's own words, or null for a unit its parser
     *     invented
     * @param synthetic whether the parser invented this unit — <b>the field that
     *     carries the meaning</b> when the title is null
     */
    record Unit(UUID id, String title, boolean synthetic, String summary) {}

    /**
     * <b>Which papers are about this, without touching a passage.</b>
     *
     * <p>{@code POST /v1/documents/rank}. One vector comparison per document
     * against its own summary, where {@link #searchDocuments} compares against
     * every passage in the corpus. It answers a different question from both
     * reads beside it: not <em>which paragraph should I cite</em> and not
     * <em>what does this paper argue</em>, but <em>which paper should I be
     * reading</em>.
     *
     * @param query what you want to find a paper about, in prose
     * @param limit how many documents at most, or null for the server's default
     */
    Ranking rankDocuments(String query, Integer limit) throws IOException;

    /**
     * @param rankable how many documents could have appeared here
     * @param unranked how many have a summary and <b>no vector for it yet</b>.
     *     {@link DocumentSearch#unsearchable}'s reason and not a rare state: a
     *     document is summarised before anything embeds the summary, so a corpus
     *     ingested minutes ago and never restarted can be entirely unrankable
     *     while every word of it is searchable
     */
    record Ranking(
            String query, int limit, List<RankedDocument> documents,
            int rankable, int unranked) {}

    /**
     * @param score nearness in {@code [-1, 1]}, and <b>topical relevance rather
     *     than agreement</b>: a paper that spends forty pages demolishing a
     *     claim is close to that claim
     * @param summary the sentence that was compared. A model's paraphrase of
     *     somebody's uploaded document, which is two removes from anything this
     *     system asserts
     */
    record RankedDocument(
            UUID documentId, String sourceName, String title, String summary,
            String ingestedAt, double score) {}

    /**
     * <b>A vector-only guess at what one paper says about a claim.</b>
     *
     * <p>{@code POST /v1/documents/&#123;id&#125;/stance}. No model call: the
     * claim and {@code "not " + claim} are embedded and both are scored against
     * the document's summary vector.
     *
     * <p><b>A heuristic, and the signature will not be the place that forgets
     * it.</b> It embeds a negation and hopes the embedding model puts it
     * somewhere useful, against a summary a model wrote about a paper nothing
     * here has read; nothing checks that any of that holds. What answers the
     * question is {@link #askDocument}, which reads the paper and costs minutes.
     *
     * @param claim a <b>statement</b> and not a question — the server negates
     *     it, and "does X inhibit Y" negates to nothing useful
     * @throws ServerError with status 404 for a document the corpus does not
     *     hold <b>or</b> one whose summary has no vector. Deliberately not a
     *     zero score: zero is a real reading
     */
    Stance documentStance(String documentId, String claim) throws IOException;

    /**
     * @param topical cosine of the claim against the summary. <b>Read this
     *     first:</b> a {@code stance} near zero means "argues both ways" when
     *     this is high and "is not about this at all" when it is low
     * @param stance {@code topical} minus the same cosine for the negated claim
     * @param basis what the reading was made of — {@code "vector_only"} is the
     *     only value there has ever been. On the wire so that a caller storing
     *     one of these can tell it from a judgment something read the paper to
     *     reach
     */
    record Stance(
            UUID documentId, String claim, double topical, double stance, String basis) {}

    /**
     * <b>What the corpus holds.</b>
     *
     * <p>{@code GET /v1/documents}. The only read on this surface that enumerates
     * documents: {@link #searchDocuments} answers with the ones a question
     * reaches and {@link #citations} with the ones an answer has drawn on, and
     * neither is the same set as the ones that exist.
     *
     * @param naming a substring of a document's filed name or its title, or null
     *     for the whole corpus. <b>This is how a person names a document without
     *     a uuid</b> — Anchor's shell binds one by calling exactly this and
     *     refusing anything but a single match
     * @param limit how many at most, or null for the server's default
     * @param offset how many to skip, or null for none
     */
    DocumentPage listDocuments(String naming, Integer limit, Integer offset) throws IOException;

    /**
     * @param total how many the naming reaches across every page
     * @param naming what narrowed the listing, echoed. <b>An empty {@code
     *     documents} means the corpus is empty or nothing is called that</b>,
     *     and only this says which
     */
    record DocumentPage(
            List<DocumentRow> documents, int total, int limit, int offset, String naming) {}

    /**
     * One document in a listing.
     *
     * @param summary what the document argues, or null for one nothing has
     *     summarised yet. <b>Uploaded text's summary is still about uploaded
     *     text</b>, and it is a model's paraphrase of it besides
     * @param vocabulary what the document calls its own top-level parts, or null
     *     for one ingested before the hierarchy existed
     * @param chunks how many chunks, which is not how many are searchable: a
     *     chunk written while the embedding endpoint was down holds its text and
     *     no vector, and {@link DocumentSearch#unsearchable} is where that is
     *     answered
     */
    record DocumentRow(
            UUID documentId, String sourceName, String title, String summary, String vocabulary,
            String ingestedAt, String ingestedBy, long byteSize, int chapters, int sections,
            int paragraphs, int chunks) {}

    /**
     * <b>One document's structure, with none of its text in it.</b>
     *
     * <p>{@code GET /v1/documents/&#123;id&#125;}. The outline a caller reads to
     * decide whether {@link #askDocument} is worth minutes of deliberation —
     * which is the decision that route's own cost makes worth taking, and which
     * nothing on this surface could inform until this arrived.
     */
    DocumentOutline describeDocument(String documentId) throws IOException;

    /**
     * @param vocabulary the document's own word for its top-level parts —
     *     {@code CHAPTER}, {@code SECTION} or {@code PART} — or null for one
     *     ingested before the hierarchy existed. <b>The word to render the
     *     outline in</b>, so a paper that calls its parts sections is never told
     *     it has chapters
     * @param chapters the top tier in document order, each carrying the tier
     *     under it. There is no ordinal: the order is the answer
     */
    record DocumentOutline(
            UUID documentId, String sourceName, String title, String summary, String vocabulary,
            String ingestedAt, String ingestedBy, long byteSize, List<ChapterOutline> chapters) {}

    /** One chapter and its sections. {@link Unit}'s null-title rule governs this
     *  record's own {@code title} exactly as it governs a section's. */
    record ChapterOutline(
            UUID id, String title, boolean synthetic, String summary, List<Unit> sections) {}

    /**
     * What answers have said they took from the corpus.
     *
     * <p>{@code GET /v1/documents/citations}. The two filters are exclusive and
     * the server refuses both at once: what one conversation cited and what has
     * cited one document are different questions asked of different keys.
     *
     * @param conversationId list only what this conversation cited, or null
     * @param documentId list only what has cited this document, or null
     * @param limit how many at most, or null for the server's default
     */
    Citations citations(String conversationId, String documentId, Integer limit)
            throws IOException;

    /**
     * @param scope which of the three questions was asked — {@code "all"},
     *     {@code "conversation"} or {@code "document"}. <b>An empty list means
     *     something different under each</b>, and only this says which was asked
     * @param limit the number the server actually applied
     */
    record Citations(String scope, int limit, List<Citation> citations) {}

    /**
     * One citation as the corpus stands now.
     *
     * @param standing {@code "resolves"}, {@code "paragraph_gone"} or {@code
     *     "document_gone"}. <b>The field a reader has to look at</b>: a citation
     *     is not a link that either works or does not, and the two ways of going
     *     stale leave different things behind
     * @param paragraphId null once the citation has gone stale — a paragraph
     *     whose text was edited took a new id rather than keeping this one
     * @param sourceName what the corpus filed the document as <b>when the
     *     citation was made</b>, which is what is left to say what was cited
     *     once the ids are gone
     * @param paragraphText the words as the corpus holds them today, or null for
     *     a citation that no longer resolves. <b>Somebody's uploaded document</b>
     *     — whatever renders it owes the quoting rule this family keeps
     */
    record Citation(
            UUID id, String standing, UUID paragraphId, UUID documentId, String sourceName,
            int paragraphOrdinal, String title, String paragraphText, String conversationId,
            Integer turnOrdinal, String agent, String citedAt) {}

    /**
     * What a recall found, and what it could not see.
     *
     * <p><b>{@code unsearchable} is here for the same reason {@code
     * MemoryTools.ServerUnreachableException} exists</b>: an empty list is a
     * conclusion an agent acts on, so every way of producing one that is not
     * "the archive holds nothing close" has to be distinguishable from it. A
     * memory whose write happened while the embedding endpoint was down is
     * stored, active and listed in the index, and is skipped by every vector
     * search — so the archive can hold exactly one memory and answer nothing.
     * This count is how the tool says so.
     *
     * @param memories the hits, nearest first, project tier ahead of global
     * @param unsearchable how many live memories in the searched tiers have no
     *     embedding. Zero means the answer is complete.
     */
    /**
     * Start one declared agent on one task.
     *
     * <h2>The session is a parameter and not an overload</h2>
     *
     * <p><b>Widened rather than joined by a three-argument sibling</b>, which is
     * the choice {@code JobStore.submit} made on the other side of this wire for
     * the same reason. A caller holding a session that reached for an overload
     * without one would submit a run that silently reaches no client machine:
     * the server accepts a body with no session, runs the job against its own
     * filesystems, and reports nothing amiss. There is no signal anywhere that
     * this went wrong, so the only place it can be prevented is a signature that
     * makes every caller say which it is. The churn is three test stubs and one
     * production call site, all of which are saying {@code null} on purpose.
     *
     * @param project the tier the run answers from; {@code null} for global. A
     *     run's home is fixed here and the agent cannot widen it.
     * @param session the session the run is submitted under, or {@code null} for
     *     a caller that has none — a script, a scheduled tick, the MCP tool
     *     surface. Optional and load-bearing: the design spec's phrase is that
     *     such a run has "a smaller set, not an empty capability". A blank string
     *     is not the same statement and the server refuses it
     * @param conversation the conversation this run is a turn in, or {@code
     *     null} for a run that is not one. <b>Widened here for the same reason
     *     {@code session} was, and the failure it prevents is the same shape</b>:
     *     a caller holding a conversation that reached for a four-argument
     *     sibling would submit a run that looks like a turn and is not — a fresh
     *     budget out of the agent's own {@code max-model-calls} instead of the
     *     conversation's remaining one, and none of the history in front of it.
     *     Nothing anywhere reports that; the person sees an agent that has
     *     forgotten the last thing they said. So every caller says which it is.
     *
     *     <p>{@code project} must be left out when this is given. The server
     *     refuses the pair rather than preferring one, because two answers to
     *     "where does this run" is how a person comes to believe a run reached a
     *     project it never touched, and that refusal is left where it is instead
     *     of being restated here
     */
    StartedJob run(String agent, String task, String project, String session,
            String conversation) throws IOException;

    /**
     * What {@code POST /v1/images} answered with, cut down to what this side
     * uses.
     *
     * <p>The response carries the filename, the size and the time as well;
     * neither is bound here, on the rule {@link IndexEntry} states about not
     * keeping a second copy of a server type. What a caller does with this is
     * write the id into a sentence, and {@code format} is here only so that
     * sentence can say what the <em>server</em> decided the bytes were rather
     * than what the uploader thought — the two are read by one {@link
     * io.aeyer.plowshare.protocol.ImageFormat} now, so they agree within a
     * build and can still differ across two.
     *
     * @param id the UID an {@code agent_run} names in its {@code images}
     * @param project the tier it landed in, or null for the global one. Echoed
     *     back rather than assumed: an uploader that guessed wrong about the
     *     tier has produced an id that resolves nowhere its run can see
     * @param format {@code png}, {@code jpeg}, {@code gif} or {@code webp}
     */
    record UploadedImage(String id, String project, String format) {}

    /**
     * Put an image on the server and get back the id that names it.
     *
     * <h2>An upload, because the file channel carries lines</h2>
     *
     * <p>{@code FileRequest}'s operations are roots, read, stat, glob, write,
     * grep, edit, delete and move, none of them carries bytes back, and a read
     * answers with a {@code Window} of <em>lines</em>. So a
     * picture sitting in a remote workspace is an upload or it is unreachable —
     * §6a of {@code implementation rationale}
     * makes that an either/or rather than an implementation detail — and this is
     * the half of it that exists. The bytes go over HTTP, from the process that
     * already opened the file under its own leash.
     *
     * <p><b>The server never names a path here and could not use one.</b> That
     * is {@code ImageController}'s rule and slice 2's: a route taking a path for
     * the server to read off somebody else's disk is the leash bypass the whole
     * file channel exists to prevent.
     *
     * @param project the tier the id has to resolve in, or {@code null} for the
     *     global one. <b>Not a convenience.</b> An {@code agent_run} resolves an
     *     id against the home its own run was given and no other, so a picture
     *     filed in the wrong tier produces an id that is real, unguessable and
     *     useless
     * @param filename what to record it as, or {@code null} to let the server
     *     keep whatever the multipart part called it. Never blank — the server
     *     refuses a blank name rather than defaulting, so a caller with nothing
     *     to say passes null
     * @param bytes the image itself, already read. <b>The caller checks {@link
     *     io.aeyer.plowshare.protocol.ImageFormat} first</b> and does not send
     *     what it knows will be refused: an upload is a user's file leaving
     *     their machine, and letting a 415 decide would ship arbitrary binaries
     *     to find out
     * @throws ServerError the server answered and said no — {@code 415} for
     *     bytes it does not take, {@code 413} for an image over its cap, {@code
     *     400} for a deployment with no data directory to put one in. Three
     *     statuses because the three remedies differ, and the caller writing a
     *     sentence about it needs to know which
     * @throws IOException the server was not reached at all, which is this
     *     interface's standing distinction and not a special case here
     */
    UploadedImage uploadImage(String project, String filename, byte[] bytes) throws IOException;

    /**
     * Open a conversation, and answer with the id its turns name.
     *
     * <p><b>{@code maxModelCalls} may be null, and this client still invents no
     * default.</b> This said the field was required because the server refused a
     * body without one; the server takes {@code
     * plowshare.conversations.default-budget} now, which is an <em>operator's</em>
     * number rather than a computed one — {@code ConversationsProperties} carries
     * why that is a different thing from a number this client could pick. Null
     * goes over as a null and the server reads it as no allowance named, which is
     * what it reads an absent key as too; the allowance the conversation was
     * actually opened with comes back on {@link Conversation#maxModelCalls}, which
     * is what a caller should report rather than what it asked for.
     *
     * <p><b>The CLI no longer requires {@code --max-model-calls} with {@code
     * --talk}, and that decision was taken where it belonged.</b> This paragraph
     * said the terminal went on asking and that whether it should was a decision
     * for whoever owns the CLI; the judgement that took the same field off the
     * console's REPL screen names REPL mode specifically, {@code --talk} is one,
     * and {@code cli.Plowshare.Options.conversation} carries the argument. The
     * flag is still honoured when it is given, and this method still invents
     * nothing when it is not.
     *
     * @param project the tier the conversation is held in, and every turn in it
     *     runs in; {@code null} for the global one, matching {@link #run}. A
     *     blank string is passed through rather than folded into {@code null},
     *     for the reason {@link #write} states: an unset field must not silently
     *     open a conversation in the tier every agent everywhere reads
     * @param maxModelCalls the whole conversation's allowance
     */
    Conversation openConversation(String project, Integer maxModelCalls) throws IOException;

    /**
     * What is open in one tier, oldest first.
     *
     * <p><b>The first thing a foreign harness needs and the one it never
     * had.</b> Every other conversation verb takes an id, and until this
     * existed the only way to hold one was to have opened the conversation in
     * the same process — so a harness that restarted, or one that wanted to look
     * at work somebody else started, had nothing to look at.
     *
     * @param project the tier; {@code null} means the global one. <b>Not "all
     *     tiers"</b>, matching {@link #openConversation} and {@link #write}: a
     *     person resuming one project's conversation must not be offered
     *     another's
     */
    List<Conversation> conversations(String project) throws IOException;

    /**
     * One page of what the model is shown in this conversation.
     *
     * <p>The projection: superseded entries gone, kinds that carry no role gone,
     * in the order a model reads them. <b>Not the same as the turns</b> — a turn
     * is what a person said and what the run came to, and this is every message
     * in between, the assistant turns that were entirely tool calls and the
     * results that answered them included.
     *
     * @param offset how many entries to pass over, or {@code null} to start at
     *     the beginning
     * @param limit how many this page may hold, or {@code null} for the server's
     *     own number. <b>The server caps it either way</b> and answers with the
     *     figure it used, which is what {@link Entries#limit} carries
     * @throws ServerError 404 if no conversation has that id, which is a
     *     different fact from a conversation nothing has been said in — that one
     *     is an empty page with a total of zero
     */
    Entries chat(String conversationId, Integer offset, Integer limit) throws IOException;

    /**
     * One page of everything that happened in this conversation.
     *
     * <p>The log rather than the projection, and the difference is everything a
     * client cannot otherwise see: which entries a fold covered and which
     * summary covered them, the diagnostics the harness leaves about a
     * conversation, the failed attempts that are how a stopped run is legible,
     * and the timings.
     *
     * @see #chat for the paging and the refusals, which are the same
     */
    Entries trajectory(String conversationId, Integer offset, Integer limit) throws IOException;

    /**
     * One page of where, in a tier's conversations, something was said.
     *
     * <p><b>The one read here that does not begin by naming a conversation.</b>
     * {@link #chat} and {@link #trajectory} answer questions about a conversation
     * somebody already identified; this answers the question that finds one.
     *
     * <p>Scoped to a tier, the same tier {@link #conversations} lists, and
     * bounded the same way the two pages are. What it cannot reach travels with
     * the answer — see {@link LogHits#reach} — because a search that quietly
     * skipped a conversation's ejected payloads would be answering "nothing" with
     * no way for a reader to know it had been looking at less than the log.
     *
     * @param project which tier, or {@code null} for the global one. A project's
     *     conversations are not in the global tier and this read does not cross
     *     the boundary
     * @param query the question in prose. <b>Refused when blank</b>: a search
     *     with no question is not a listing of everything
     * @see #chat for the paging and the refusals, which are the same
     */
    LogHits searchEntries(String project, String query, Integer offset, Integer limit)
            throws IOException;

    /**
     * What this conversation's prompt costs, and every part of it the server
     * declines to estimate.
     *
     * @param agent whose fixed block to price, or {@code null} for none. <b>A
     *     conversation does not name an agent</b> — it is chosen per turn — so
     *     the server cannot supply one and does not guess; without it the answer
     *     carries what the conversation itself measured and a null {@link
     *     Context#prefix}
     * @throws ServerError 400 if {@code agent} names nothing this server runs,
     *     404 if no conversation has that id
     */
    Context context(String conversationId, String agent) throws IOException;

    /**
     * Every seam this conversation's history has, oldest first.
     *
     * <p><b>The one thing a speaker cannot see for itself.</b> A client that held
     * the conversation typed every utterance and read every answer, so its own
     * scrollback is the transcript; a compaction happens inside the next turn,
     * publishes no lifecycle event and is not counted in the model calls the
     * outcome reports. This is how it finds out one happened, and what the model
     * is now being shown in place of what was said.
     *
     * @throws ServerError 404 if no conversation has that id, which is a
     *     different fact from a conversation that has never been folded — that
     *     one is an empty list
     */
    List<Seam> compactions(String conversationId) throws IOException;

    /** Start a curator pass over one project. */
    StartedJob curate(String project, Integer maxModelCalls) throws IOException;

    /**
     * Ask one document a question, and get back the handle of the deliberation
     * that will answer it.
     *
     * <p><b>Not a narrower {@link #searchDocuments}, and the signature says
     * so.</b> A search takes a question and gives back passages; this takes a
     * <em>document</em> and gives back a job, because three agents run in series
     * over it — a proposer with the whole hierarchy and the retrieved passages, a
     * critic with only what the document argues as a whole, and a synthesiser
     * with both plus the debate. What that catches is a claim that reads
     * correctly against a passage and wrongly against the paper, which is a
     * question the corpus-wide read cannot be asked.
     *
     * <p>Minutes rather than milliseconds, so it is {@link #curate}'s shape:
     * poll {@link #job(String)} for the answer and {@link #cancelJob(String)} to
     * stop it.
     *
     * @param documentId the document, as a uuid — the one every search hit and
     *     every citation carries
     * @param question what to ask it, in prose
     * @param maxModelCalls this pass's own allowance, or null for the server's
     * @throws ServerError 404 if the corpus holds no such document
     */
    StartedJob askDocument(String documentId, String question, Integer maxModelCalls)
            throws IOException;

    /** How a run is going, and how it ended once it has. */
    JobStatus job(String id) throws IOException;

    /** Ask a run to stop at its next turn boundary, and report where it is. */
    JobStatus cancelJob(String id) throws IOException;

    /**
     * Name a project's workspace and its exclusions, replacing whatever it had —
     * and lending nothing else.
     *
     * <p><b>The paths are the server's, not this machine's.</b> A local provider
     * opens files on the box the server runs on, so a workspace is a directory
     * there — which is why these travel as strings and are never resolved here:
     * a {@code Path} built in this process would normalise against this
     * machine's filesystem and mean a different directory.
     *
     * <p><b>This writes a whole definition, so it clears whatever the project was
     * lending.</b> That is the same sentence as the one about exclusions and it
     * has the same remedy: {@link #lendProject} adds a directory without
     * rewriting anything. The route behind this can carry lent roots — {@code
     * DefineProjectRequest} has the field — and this method deliberately does not
     * offer them, because a fourth argument here would put two adjacent {@code
     * List&lt;String&gt;} at every call site, one comma away from turning a fence
     * into a grant. Lending is a verb.
     *
     * @param exclusions extra paths, inside the workspace or inside anything lent
     *     alongside it, that the project may not reach. Never the ones no project
     *     may override — those are the server's and cannot be sent, dropped or
     *     seen from here except in the answer
     */
    ProjectView defineProject(String name, String workspace, List<String> exclusions)
            throws IOException;

    /**
     * Lend a project further directories on the server, keeping where it is and
     * everything else about it.
     *
     * <p><b>This is the verb {@code 809338d} said existed and did not.</b> That
     * change made a dot-prefixed path component unreachable unless a root names
     * it, and called the cost recoverable by adding {@code .github} as an
     * explicit root — which the CLI's {@code --workspace a,b} could express and a
     * server-side project could not.
     *
     * <p>Additive and it does not touch the workspace, which matters because the
     * workspace is the {@code <PATH>} of the project's canonical name: a lend
     * that rewrote it would rename the project. A directory need not be inside
     * the workspace; what it cannot do is out-reach the paths no project may
     * override, which the server drops a covering root against whether it was
     * lent or was the workspace.
     *
     * @param roots directories on the <b>server's</b> disk, as strings, for the
     *     same reason the workspace is one. At least one, and each must already
     *     be a directory there
     */
    ProjectView lendProject(String name, List<String> roots) throws IOException;

    /**
     * Stop lending directories, leaving the workspace alone.
     *
     * <p>Idempotent, and the server checks nothing about the paths on its disk:
     * the commonest reason to take a directory back is that it has gone.
     */
    ProjectView unlendProject(String name, List<String> roots) throws IOException;

    /**
     * Point an existing project at a different directory, keeping its exclusions
     * and whatever it lends.
     *
     * <p>Not {@link #defineProject} with the old lists passed back in: that
     * replaces them, so a caller that forgot to read them first would silently
     * drop every path an operator had fenced off — and every directory they had
     * lent.
     */
    ProjectView setProjectWorkspace(String name, String workspace) throws IOException;

    /**
     * Move a project: give it a different name, and its whole archive with it.
     *
     * <p><b>Not a workspace operation.</b> The other three change a project's
     * leash; this changes what the project is called, which since the server's
     * V14 is one row and one column — every memory and every conversation
     * reaches the project through a surrogate id and is untouched. It is
     * therefore also the one of the verbs here that works on a project with no
     * workspace at all, which is most of them under presence: {@link
     * #defineProject} can only name a directory on the <em>server's</em> disk,
     * and a project whose files are on this machine has none.
     *
     * <p>Nothing comes back, for the reason {@link #forgetProject} answers
     * nothing: there is no new leash to describe. What a person is told is
     * composed by {@code ProjectTools}.
     *
     * @throws ServerError 404 if no project is called {@code name}; 409 if
     *     another project is already called {@code to}, or if a live session is
     *     rooting either name
     */
    void moveProject(String name, String to) throws IOException;

    /** Drop a project's workspace and everything lent alongside it, leaving its
     *  memories alone. Its jobs go back to having no local file access. */
    void forgetProject(String name) throws IOException;

    /** What is waiting in one tier's promotion queue. */
    List<ProposalRow> proposals(String project) throws IOException;

    /** Settle one proposal, once. */
    Resolution resolve(String id, boolean accept, String reason, String by) throws IOException;

    record StartedJob(String id, String agent) {}

    /**
     * A conversation as it was opened.
     *
     * @param project the tier it was opened in, or {@code null} for the global
     *     one — echoed back rather than assumed, because a caller that named no
     *     project learns here that it got global
     * @param maxModelCalls the allowance every turn in it spends from
     */
    record Conversation(String id, String project, int maxModelCalls) {}

    /**
     * One fold in a conversation's history.
     *
     * <p>Named for what a reader sees rather than for the table it comes out of,
     * which is the licence {@link IndexEntry} takes over the server's {@code
     * TocEntry}: this module knows the server by its wire format and not by its
     * class names. The endpoint is {@code compactions}; what it describes is a
     * seam, and every piece of prose in this project that argues about it — "the
     * person can see the seam and read what was behind it" — calls it one.
     *
     * @param throughOrdinal the last turn this summary stands for. The turns it
     *     folded are not gone; they are still in the conversation's transcript,
     *     and for a client that spoke them they are still in its scrollback
     * @param summary what the next turn is shown in place of those turns
     */
    record Seam(int throughOrdinal, String summary) {}

    /**
     * One page of a conversation's entries, and what it takes to ask for the
     * next.
     *
     * <p>All four numbers are needed and none is derivable from the others. A
     * short page is either the end of the list or a caller that paged past it,
     * which {@code total} tells apart; and {@code limit} is <b>what the server
     * used</b> rather than what was asked for, which are different exactly when
     * the request was above its cap. A caller pages by adding {@code limit} to
     * {@code offset}, so reading its own request back instead would step over
     * entries it never saw.
     *
     * @param entries the page, in conversation order
     * @param total how many entries this reading holds — different between a
     *     chat and a trajectory of the same conversation, and the gap between
     *     them is what folding and the invisible kinds have taken out
     */
    record Entries(List<Entry> entries, int total, int offset, int limit) {}

    /**
     * One entry as a page carries it.
     *
     * <p><b>{@code excerpt} may be short of the entry</b> and {@code cut} is how
     * a reader knows; it may also be <b>absent</b>, and {@code ejectedAt} is how
     * a reader knows that. The server bounds an entry's text in the database, because
     * one file read is a hundred thousand characters; the true length travels
     * beside it so that nothing is silently shortened.
     *
     * @param kind the stored spelling — {@code utterance}, {@code answer},
     *     {@code tool_result}, {@code summary}, {@code attempt_failed}, {@code
     *     diagnostic} and the rest. <b>Not enumerated here</b>, so a server that
     *     grows one reaches a reader without a change in this module
     * @param turnOrdinal which of the person's utterances this belongs to. A
     *     turn is a person speaking; the model calls answering it are steps
     * @param supersededBy the summary that folded this entry away, or null.
     *     Never set on an entry from {@link #chat}
     * @param recordedAt when it was written, or null for an entry older than the
     *     column. <b>Null is a real answer</b> and is not an epoch
     * @param tookMillis how long the operation that produced it took, or null.
     *     Not the gap to the entry before it
     * @param ejectedAt when this payload was ejected, or null while it is still
     *     here. <b>Present for exactly the entries whose {@code excerpt} is
     *     null</b>, and it is what stops a reader treating that null as text: a
     *     tool result whose payload a retention sweep has taken is a call that
     *     really happened and really returned something, and rendering it as
     *     empty would say the tool does not work. {@code cut} is false for one
     *     of these, because nothing on the page cut it
     */
    record Entry(
            int ordinal, int turnOrdinal, String kind, String excerpt, int length, boolean cut,
            Integer supersededBy, String toolCallId, List<Asked> toolCalls, String handle,
            Instant recordedAt, Long tookMillis, Instant ejectedAt) {}

    /** One call a model asked for, with its arguments bounded the way an entry's
     *  own text is — {@code file_edit} sends a whole file as an argument. */
    record Asked(String id, String name, String arguments, int length, boolean cut) {}

    /**
     * One page of search hits, and what the search could not look at.
     *
     * <p>The paging half is {@link Entries}'. The other half is {@link #reach},
     * and it is why this is a record of its own: an empty {@code hits} means "the
     * log was searched and held nothing" <b>only</b> if something says what was
     * searched, and two of the three ways an entry can be out of reach are
     * ordinary states rather than faults.
     *
     * @param total how many entries matched in all — about the tier, not this
     *     page
     * @param limit <b>what the server used</b> and not what was asked for
     */
    record LogHits(List<LogHit> hits, int total, int offset, int limit, Reach reach) {}

    /**
     * One entry a question's words reached.
     *
     * <p><b>{@code snippet} is not {@code Entry.excerpt}.</b> An excerpt is the
     * opening of an entry, so "there is more after it"; a snippet is the words
     * <em>around the match</em>, wherever in the entry that was, with the matched
     * words in square brackets. A reader that offered to fetch "the rest" of one
     * would be offering something that does not exist — {@code length} is how
     * much of the entry is elsewhere, and for a {@code tool_result} the {@code
     * handle} is the only way to the whole of it.
     *
     * @param conversationId which conversation said it, and the id {@code chat}
     *     and {@code trajectory} take
     * @param rank what the database scored this against the question, always
     *     above zero. <b>Not comparable across questions</b>: it orders one
     *     question's hits and means nothing on its own
     * @param supersededBy the summary that folded this away, or null. <b>A hit
     *     either way</b> — a fold does not unsay anything
     */
    record LogHit(
            String conversationId, int ordinal, int turnOrdinal, String kind, double rank,
            String snippet, int length, Integer supersededBy, String handle,
            Instant recordedAt) {}

    /**
     * What a search looked at, and what it could not.
     *
     * @param searched entries whose words the question was asked of
     * @param ejected entries whose payload a retention sweep took, keeping the
     *     row. <b>Never a hit</b>, because there is nothing left to match on, and
     *     never silently missing, because of this number
     * @param recordedOnly entries of a kind that never reaches a model — a
     *     diagnostic, a failed attempt, a runtime note, a plan. There and
     *     deliberately not searched; {@code trajectory} is where they are read
     */
    record Reach(int searched, int ejected, int recordedOnly) {}

    /**
     * What a conversation's prompt costs, and what cannot honestly be said about
     * its parts.
     *
     * <p><b>The nulls are the answer and not a gap in this record.</b> The
     * server measures the whole request with the model's own tokenizer and has
     * no way to count a part of it; rather than estimate, it sends each
     * component empty and a sentence in {@link #unavailable} saying why. A
     * renderer shows the sentence.
     *
     * @param sent what the newest measured turn's whole prompt cost, or null for
     *     a conversation no turn of which reached a model call. Never zero
     * @param cacheHitRate always null today. The prefix reuse the server's
     *     compaction design rests on is unobservable: the inference endpoint's
     *     {@code usage} carries no {@code cached_tokens}
     * @param prefix one agent's fixed block, priced in characters, or null when
     *     no agent was named
     */
    record Context(
            Integer sent, Integer sentAtTurn, int turns, int turnsMeasured,
            Integer systemPromptTokens, Integer toolTokens, Integer messageTokens,
            Double cacheHitRate, List<Unavailable> unavailable, Prefix prefix) {}

    /** One number the server will not give, and the reason in prose. */
    record Unavailable(String component, String reason) {}

    /**
     * One agent's fixed block — the part of every request that does not vary
     * with what was said — measured in characters of the JSON sent.
     *
     * <p><b>Characters and not tokens</b>, deliberately. There is no tokenizer
     * on the server's box, and scaling characters into tokens is exactly the
     * estimate the context surface exists to refuse.
     *
     * @param tools one entry per tool the server would really offer this agent,
     *     which can be shorter than the agent's own declared list when a
     *     deployment wired fewer
     */
    record Prefix(
            String agent, String model, int systemPromptCharacters, int toolCharacters,
            List<ToolCost> tools) {}

    /** One tool's share of the block, in characters of the JSON sent. */
    record ToolCost(String name, int characters) {}

    /**
     * A project's leash as the server now holds it.
     *
     * @param workspace where the project <em>is</em>. One directory, and it stays
     *     one: it is the {@code <PATH>} of {@code MACHINE/PATH/NAME}, and an
     *     identity composed from a list would change whenever the list reordered
     * @param lent the further directories it reaches at that place, as the row
     *     holds them, workspace-first order preserved. Usually empty. Unlike
     *     {@code exclusions} this is complete as sent, because the server adds no
     *     lending of its own — it fences paths off, it does not grant them
     * @param exclusions <b>everything the project may not reach, including the
     *     ones no project may override</b> — the server's own directory, so that
     *     its configuration, its agent definitions, its sampling profiles and
     *     its own console token stay out of reach whatever
     *     a row says. It is therefore longer than whatever was sent, and that is
     *     the point: a person who never sees the server's own paths has no way
     *     to know the leash is shorter than the directory they named
     */
    record ProjectView(
            String name, String workspace, List<String> lent, List<String> exclusions) {}

    /**
     * @param outcome null while the run is going. That is the whole of the
     *     difference a caller needs: a job with no outcome has not finished, and
     *     one with an outcome has, whatever its ending.
     * @param cancelRequested whether somebody has asked it to stop. Separate
     *     from {@code state}, because a cancelled run is still RUNNING until it
     *     reaches its next turn boundary.
     */
    record JobStatus(
            String id, String agent, String state, boolean cancelRequested,
            RunOutcome outcome) {}

    /**
     * @param answered the one bit that separates "it decided" from "it stopped".
     *     A truncated run is never dressed as an answer, so {@code text} means
     *     nothing until this has been read.
     * @param steps how many steps the run completed — one model call plus the
     *     tool results it asked for, each time round the agent's loop. <b>Not
     *     turns</b>: a turn is one thing a person said and everything that
     *     answered it, and this terminal printing "after 4 turns" for a single
     *     question was telling somebody they had spoken four times. The server
     *     decided the word — see {@code Outcome} — so that this client, the web
     *     console and any harness over MCP all read the same one.
     */
    record RunOutcome(
            String ending, boolean answered, String text, int steps, int modelCalls,
            String detail) {}

    /**
     * @param proposedBy who raised the question, or null on a row filed before
     *     the server had a column for it. Rendered by {@code memory_proposals}:
     *     a person settling the queue is answering somebody, and a row that
     *     could not say who left the reason sentence to stand for both the
     *     argument and its author.
     */
    record ProposalRow(
            String id, String memoryId, String project, String action, String reason,
            String state, Instant createdAt, String proposedBy, Instant resolvedAt,
            String resolvedBy, String resolution) {}

    /**
     * @param promotedId the new global record, or null on a rejection
     * @param demoted what fell out of the global index to make room. Surfaced
     *     rather than silent: approving adds to the tier every project reads.
     */
    record Resolution(ProposalRow proposal, String promotedId, List<String> demoted) {}

    record Recall(List<Memory> memories, int unsearchable) {}

    /**
     * One line of the index.
     *
     * <p>Declared here rather than shared with the server's {@code TocEntry},
     * which is the same fields. The client is a separate process that knows the
     * server only by its wire format, and {@code TocEntry} lives in {@code
     * plowshare-server}, which this module must not depend on. Moving it down
     * into {@code plowshare-protocol} — where {@link WriteResult}, the other
     * endpoint's response body, already lives — would remove the duplication
     * and is the better long-term shape; it is a change to the server module's
     * own types, so it is recorded here rather than smuggled into the commit
     * that wires the two halves together.
     *
     * @param unsearchable true when this memory has no embedding, so no recall
     *     can reach it however the question is phrased. Named for the problem
     *     rather than for the column, so a client bound against an older server
     *     that sends no such field reads the benign {@code false} rather than
     *     reporting every memory as broken.
     */
    record IndexEntry(String id, String summary, String scope, boolean unsearchable) {}

    /**
     * The server answered with a status that is not a success.
     *
     * <p>Unchecked on purpose: there is nothing a tool can usefully do about a
     * 422 except tell the caller what the server said, and {@code
     * StdioTransport} already turns a thrown exception into a tool result
     * carrying {@code isError} — which is how the message reaches the model
     * instead of the log.
     */
    final class ServerError extends RuntimeException {

        private final int status;

        public ServerError(int status, String detail) {
            super("the Plowshare server answered " + status + ": " + detail);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
