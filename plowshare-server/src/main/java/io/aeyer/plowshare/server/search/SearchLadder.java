package io.aeyer.plowshare.server.search;

import io.aeyer.plowshare.protocol.search.AnswerStatus;
import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.SearchAsk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One search, walked down {@link SearchProperties#ladderNow()} until a
 * registered, healthy rung answers or the ladder runs out.
 *
 * <h2>Exactly one provider is dialled — there is no fan-out</h2>
 *
 * <p>This is not fan-out defaulted off; there is no fan-out mode at all.
 * Aletheia's dispatcher calls every eligible handler for a query and merges
 * whatever comes back, which spends every provider's quota — including the
 * ones billed per call — on every single question, whether or not the
 * cheapest one would have answered it. The ladder this class walks exists so
 * that the free provider serves all the ordinary traffic and the metered one
 * is only ever dialled once the free one is unavailable. Calling more than
 * one rung per search would bill every configured provider for every query,
 * which is the exact cost this design is trying to avoid rather than an
 * optimisation left for later.
 *
 * <h2>No sufficiency test — a thin or empty answer is still the answer</h2>
 *
 * <p>A rung that returns six hits against a {@code max} of fifty has not
 * failed at anything: {@link SearchAsk}'s own javadoc already says {@code
 * max} is a ceiling this class is willing to accept, never a target it is
 * owed. A provider returning zero hits is answering honestly about a query
 * that has nothing to find. Escalating either case to the next rung would
 * mean this class inventing a judgement about a query's difficulty that it
 * has no basis for making — the only fact it is entitled to act on is whether
 * a rung produced {@link AnswerStatus#SUCCESS} at all, and every hit count
 * from zero to {@code max} counts as one.
 *
 * <h2>A model does not decide sufficiency either</h2>
 *
 * <p>Handing a thin result set to an LLM to judge whether it is "enough" was
 * considered and refused, not merely deferred: that judgement would run
 * inside a tool invocation and spend an allowance the caller — a person or
 * another model, several layers up — never budgeted for, on a decision made
 * where they cannot see it happen. {@code TODO.md} §4.3 makes the same
 * argument about a tool that puts a turn into somebody's conversation without
 * their say-so; a sufficiency judge hidden inside this ladder would be that
 * same move wearing a different tool's name.
 *
 * <h2>Unavailability is the only reason a rung is passed over</h2>
 *
 * <p>Three things, and only these three, remove a rung from consideration,
 * and {@link #run} keeps one note per rung naming which of them applied so
 * that an exhausted ladder can hand back a refusal that names every rung
 * rather than one opaque "no results".
 *
 * <p><b>Spec §5's first skip condition lists a fourth — "the provider is
 * network-tier ineligible" — and it is not implemented, here or anywhere.</b>
 * {@link io.aeyer.plowshare.protocol.search.NetworkTier} is fetched at
 * registration, stored in {@code search_providers.network_tier} and mapped
 * back onto {@link Registration#facts()}, and then read by nothing: there is
 * no eligibility policy for it to be read against, and inventing one here
 * would be this class deciding operator policy. It is reserved-and-unserved
 * exactly as {@link io.aeyer.plowshare.protocol.search.Verb#FETCH} is, and
 * the spec has been amended to say so rather than left describing a skip that
 * does not happen. The three below are the whole list this code implements:
 *
 * <ul>
 *   <li>its name resolves to no row in {@link ProviderStore} — <em>not
 *       registered</em>;
 *   <li>its {@link Registration#consecutiveFailures()} has reached {@link
 *       SearchProperties#failureThresholdNow()} — <em>skipped</em>, without
 *       being dialled at all;
 *   <li>the invocation itself does not come back {@link
 *       AnswerStatus#SUCCESS} — <em>failed</em>, carrying the provider's own
 *       message (or a synthesised one when it left none), except {@link
 *       AnswerStatus#UNSUPPORTED}, which is named for what it actually is: a
 *       registration mistake an operator can fix, not a transient failure.
 * </ul>
 *
 * <h2>A bean, but one every test builds by hand</h2>
 *
 * <p>This class takes a {@link SearchProvider} through its constructor and is
 * wired by {@link SearchConfig#searchLadder}, alongside the {@link
 * RemoteSearchProvider} it dials through. (This section used to say it was
 * wired by nothing and that doing so was a later task's job; that task
 * landed, and the sentence outlived it.) Every test in this package still
 * builds a {@code SearchLadder} directly, against a hand-written fake
 * provider rather than the real adapter, because what is worth proving here
 * is which rung gets dialled and not how one is dialled over HTTP — {@code
 * SearchEndToEndTest} is the one place the wired shape is exercised against
 * real sockets.
 */
public class SearchLadder {

    private static final int MAX_LOGGED_FAILURE_CHARS = 2_000;
    private static final Logger log = LoggerFactory.getLogger(SearchLadder.class);

    private final ProviderStore store;
    private final SearchProperties properties;
    private final SearchProvider provider;

    public SearchLadder(ProviderStore store, SearchProperties properties, SearchProvider provider) {
        this.store = store;
        this.properties = properties;
        this.provider = provider;
    }

    /**
     * Walk the ladder for one query, dialling at most one provider.
     *
     * <p>Mints a fresh {@link SearchAsk#requestId} — a new random {@link
     * UUID} — for every rung it dials, rather than accepting one from a
     * caller or reusing one across rungs. {@link SearchAsk} refuses a blank
     * {@code requestId} at construction and nothing upstream of this method
     * supplies one, so this is the one place an id can come from; and two
     * rungs dialled for what is, from here, one logical search are still two
     * separate calls to two separate processes, each entitled to its own id
     * in whatever it logs.
     *
     * <h2>A malformed query throws here, uniformly, before any rung is
     * considered</h2>
     *
     * <p>{@link SearchAsk}'s constructor already refuses a blank {@code query}
     * and a {@code max} below one — but it is only reached from {@link
     * #invoke}, which runs only once a rung is registered and under the
     * failure threshold. Without the guard below, the same malformed call
     * threw an {@link IllegalArgumentException} on a server with a healthy
     * rung and answered a plain refusal on one with an empty ladder: a
     * contract that varies with registry state the caller cannot see, and
     * therefore no contract at all. {@code SearchService.validate} covers the
     * one production path today, but this class is a {@code @Bean} ({@link
     * SearchConfig}) and the next caller inherits whatever this method
     * actually does rather than what its one existing caller does first.
     *
     * <p>It throws rather than returning a refusal, on purpose. A refusal is
     * prose about a search that was legitimately asked and could not be
     * served; a blank query is not a search that failed, it is a call that
     * was never well formed, and folding the two together would hand a caller
     * the same shape of answer for "no provider is configured" as for "you
     * passed nothing". This still throws {@link IllegalArgumentException} and
     * not {@link io.aeyer.plowshare.server.faults.CallerFault} — the type that
     * now answers "below the HTTP edge" for the rest of this module — because
     * {@link SearchAsk} lives in {@code plowshare-protocol}, which this module
     * depends on and not the reverse, so it cannot import a {@code
     * plowshare-server} type at all; matching its choice is what keeps a
     * malformed call answered identically whichever of the two checks catches
     * it, this one or {@link SearchAsk}'s own constructor, reached one line
     * later through {@link #invoke}. {@code SearchService.validate} is what
     * turns either into the caller-facing {@code CallerFault}, and does so
     * before this method is ever reached on the one path production calls
     * today.
     *
     * @return a result carrying the answering rung's key and hits, or — once
     *     every rung has been passed over or has failed — a null {@code
     *     providerKey} and a refusal naming each rung and what became of it
     * @throws IllegalArgumentException if {@code query} is blank or {@code
     *     max} is below one, regardless of what is registered — the same two
     *     conditions {@link SearchAsk} refuses, checked here so that which
     *     one of them happens does not depend on the registry
     */
    public LadderResult run(String query, int max, List<String> ignoredDomains) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1, was " + max);
        }
        List<String> ladder = properties.ladderNow();
        if (ladder.isEmpty()) {
            String refusal = "There is no search provider configured on"
                    + " this server. Register one and set plowshare.search.ladder to its key"
                    + " before searching.";
            log.warn("Search failed before a provider could be called: {}", diagnostic(refusal));
            return new LadderResult(null, List.of(), refusal);
        }

        int threshold = properties.failureThresholdNow();
        List<String> notes = new ArrayList<>();

        for (String providerKey : ladder) {
            Optional<Registration> found = store.find(providerKey);
            if (found.isEmpty()) {
                notes.add(providerKey + " is not registered as a search provider.");
                continue;
            }

            Registration registration = found.get();
            if (registration.consecutiveFailures() >= threshold) {
                notes.add(providerKey + " was skipped: " + registration.consecutiveFailures()
                        + " consecutive failures meets or exceeds the failure threshold of "
                        + threshold + ".");
                continue;
            }

            SearchAnswer answer = invoke(registration, query, max, ignoredDomains);
            if (answer.status() == AnswerStatus.SUCCESS) {
                store.recordOutcome(providerKey, AnswerStatus.SUCCESS, null);
                return new LadderResult(providerKey, answer.hits(), null);
            }

            // The remote plugin has reported an error. Log it here as well as
            // in the provider process: this is Plowshare's operator-facing
            // log, and a later rung may succeed, in which case the aggregate
            // exhausted-ladder warning below is never reached.
            log.warn(
                    "Search provider reported failure providerKey={} requestId={} status={}"
                            + " elapsedMs={} detail={}",
                    providerKey,
                    diagnostic(answer.requestId() == null ? "" : answer.requestId()),
                    answer.status(),
                    answer.elapsedMs(),
                    diagnostic(messageOrDefault(answer.message())));
            store.recordOutcome(providerKey, answer.status(), answer.message());
            notes.add(answer.status() == AnswerStatus.UNSUPPORTED
                    ? providerKey + " is registered for search but answered that it does not"
                            + " support it."
                    : providerKey + " failed: " + messageOrDefault(answer.message()));
        }

        String refusal = "No search provider answered the query. " + String.join(" ", notes);
        // The model receives this same reason through SearchPage, but an
        // operator diagnosing the harness should not have to find and decode
        // the model's tool result to learn why no internet search happened.
        log.warn("Search failed after walking the provider ladder: {}", diagnostic(refusal));
        return new LadderResult(null, List.of(), refusal);
    }

    /** A provider controls part of a refusal, so it gets one bounded log line. */
    private static String diagnostic(String refusal) {
        String oneLine = refusal.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_LOGGED_FAILURE_CHARS
                ? oneLine
                : oneLine.substring(0, MAX_LOGGED_FAILURE_CHARS) + "…";
    }

    /**
     * Dials one rung, defensively — normalising every way a provider can
     * misbehave worse than {@link SearchProvider#search}'s own contract
     * allows into a plain {@link SearchAnswer#failed}, because this ladder is
     * the layer that decides what a misbehaving provider means, and a
     * provider worse-behaved than its contract is exactly the case this
     * class exists to survive rather than propagate.
     *
     * <p>{@link SearchProvider#search} is documented to never throw. A
     * {@link RuntimeException} escaping it anyway must not abandon the rungs
     * after it, so it is caught here and folded into a failure — that part
     * was always true.
     *
     * <p>A returned, non-throwing {@link SearchAnswer} with a {@code null}
     * {@link SearchAnswer#status()} is a second, quieter way the same
     * contract can be broken: {@link RemoteSearchProvider} builds its answer
     * by deserialising whatever JSON body a remote process sent, with no
     * check that the body actually named a status, so a remote answering an
     * incomplete body reaches this method as a non-null {@code SearchAnswer}
     * whose {@code status} is null. {@link #run} calls {@code status.name()}
     * through {@link ProviderStore#recordOutcome} on every non-success path;
     * an unnormalised null there would throw out of {@code run} itself and
     * strand every rung after this one — the exact failure mode this class's
     * own contract forbids. Normalising both a null answer and a null status
     * here, in the one place a raw provider answer enters this class, means
     * {@code run} never has to guard against either again.
     */
    private SearchAnswer invoke(Registration at, String query, int max, List<String> ignoredDomains) {
        SearchAsk ask = new SearchAsk(UUID.randomUUID().toString(), query, max, ignoredDomains);
        SearchAnswer answer;
        try {
            answer = provider.search(at, ask);
        } catch (RuntimeException escaped) {
            String detail = escaped.getMessage() == null || escaped.getMessage().isBlank()
                    ? escaped.getClass().getSimpleName()
                    : escaped.getMessage();
            return SearchAnswer.failed(ask.requestId(), at.providerKey(), detail, 0L);
        }
        if (answer == null) {
            return SearchAnswer.failed(ask.requestId(), at.providerKey(),
                    "the provider returned no answer at all, which its own contract forbids", 0L);
        }
        if (answer.status() == null) {
            return SearchAnswer.failed(ask.requestId(), at.providerKey(),
                    "the provider returned an answer with no status, which its own contract"
                            + " forbids",
                    0L);
        }
        return answer;
    }

    /**
     * {@code message}, or a synthesised detail when a provider left it
     * {@code null} or blank. {@link SearchAnswer#failed} does not require a
     * message and {@link AnswerStatus#UNSUPPORTED} answers — the shape a
     * remote body deserialises into directly, bypassing every factory method
     * on {@link SearchAnswer} — commonly arrive with none at all. The
     * refusal this feeds is prose a person or a model has to act on, per this
     * class's own brief, so a rung's line in it must never read "failed:
     * null" — a dumped field rather than something either reader can use.
     */
    private static String messageOrDefault(String message) {
        return (message == null || message.isBlank()) ? "no message was given" : message;
    }
}
