package io.aeyer.plowshare.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.protocol.search.AnswerStatus;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.Verb;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link SearchLadder} against a real Postgres — it reads {@link
 * Registration#consecutiveFailures()} through {@link ProviderStore} and
 * {@link SearchProperties#ladderNow()}/{@link
 * SearchProperties#failureThresholdNow()}, both Postgres-backed, so a
 * stand-in for either would be testing the stand-in rather than the ladder.
 * Follows {@code ProviderStoreTest} and {@code SearchPropertiesTest}: no
 * Spring context, one Testcontainers Postgres migrated once for the class,
 * and a truncated registry per test. {@link SearchProvider} itself is a hand
 * written fake — {@code recordingProvider()} — never {@link
 * RemoteSearchProvider}, because this class's whole job is deciding which
 * rung to dial, not how one is dialled over HTTP.
 */
@Testcontainers
class SearchLadderTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private ProviderStore store;
    private SearchProperties properties;
    private final Map<String, SearchAnswer> cannedAnswers = new HashMap<>();
    private final List<String> dialledKeys = new ArrayList<>();
    private final Set<String> throwingKeys = new HashSet<>();

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void freshRegistry() {
        jdbc.execute("TRUNCATE TABLE search_providers");
        store = new ProviderStore(jdbc);
        properties = new SearchProperties();
        properties.setFailureThreshold(3);
        cannedAnswers.clear();
        dialledKeys.clear();
        throwingKeys.clear();
    }

    private static ProviderFacts facts(String key) {
        return new ProviderFacts(key, key, "0.1.0", "d",
                Set.of(Verb.SEARCH), CostClass.FREE, NetworkTier.INTERNAL_NETWORK, 25, 512, true);
    }

    /** Registers {@code providerKey} with zero consecutive failures. */
    private void register(String providerKey) {
        store.upsert(providerKey, "http://localhost:9", facts(providerKey));
    }

    /** Sets the ladder {@link SearchLadder} will walk. */
    private void ladderIs(String commaSeparated) {
        properties.setLadder(commaSeparated);
    }

    private Hit hit(String url) {
        return new Hit(url, "title for " + url, "snippet for " + url);
    }

    /** The next dial for {@code providerKey} through {@code recordingProvider()} succeeds
     *  with these hits (zero hits is a legal, successful answer). */
    private void answers(String providerKey, Hit... hits) {
        cannedAnswers.put(providerKey,
                SearchAnswer.success("placeholder", providerKey, List.of(hits), 1L));
    }

    /** The next dial for {@code providerKey} through {@code recordingProvider()} fails
     *  with this message. */
    private void fails(String providerKey, String message) {
        cannedAnswers.put(providerKey, SearchAnswer.failed("placeholder", providerKey, message, 1L));
    }

    /** Seeds {@code providerKey}'s consecutive-failure counter by recording {@code count}
     *  real failures through the store — the same path a search would leave it in. */
    private void failuresOn(String providerKey, int count) {
        for (int i = 0; i < count; i++) {
            store.recordOutcome(providerKey, AnswerStatus.FAILED, "seeded failure");
        }
    }

    /** The next dial for {@code providerKey} through {@code recordingProvider()} throws a
     *  {@link RuntimeException} instead of returning — a provider misbehaving worse than
     *  {@link SearchProvider#search}'s own "never throws" contract allows. */
    private void throwsOn(String providerKey) {
        throwingKeys.add(providerKey);
    }

    /** The next dial for {@code providerKey} returns a non-null {@link SearchAnswer} whose
     *  {@code status} is null — the shape a body missing its status field deserialises to,
     *  bypassing {@link SearchAnswer#success} and {@link SearchAnswer#failed} entirely. */
    private void malformedNoStatus(String providerKey) {
        cannedAnswers.put(providerKey,
                new SearchAnswer("placeholder", providerKey, null, List.of(), 1L, null));
    }

    /** The next dial for {@code providerKey} returns {@link AnswerStatus#FAILED} with a null
     *  message — legal by {@link SearchAnswer}'s own constructor, but not something the
     *  refusal text this feeds may repeat verbatim. */
    private void malformedFailedNoMessage(String providerKey) {
        cannedAnswers.put(providerKey, new SearchAnswer(
                "placeholder", providerKey, AnswerStatus.FAILED, List.of(), 1L, null));
    }

    /** Every provider key {@code recordingProvider()} was actually asked to search, in order. */
    private List<String> dialled() {
        return dialledKeys;
    }

    /**
     * A {@link SearchProvider} that never talks to a network: it appends the
     * key it was asked for to {@link #dialledKeys}, then either throws (for a
     * key named to {@link #throwsOn}) or answers from {@link #cannedAnswers},
     * re-stamped with the {@link SearchAsk} it was actually handed so a
     * well-formed canned answer's {@code requestId} always matches the ask
     * {@link SearchLadder} minted for that rung. A canned answer built by
     * {@link #malformedNoStatus} or {@link #malformedFailedNoMessage} is
     * copied through exactly as stored, null fields included — restamping it
     * must not accidentally repair the malformation the test set up.
     */
    private SearchProvider recordingProvider() {
        return (at, ask) -> {
            dialledKeys.add(at.providerKey());
            if (throwingKeys.contains(at.providerKey())) {
                throw new RuntimeException("recordingProvider was told to throw for "
                        + at.providerKey());
            }
            SearchAnswer canned = cannedAnswers.get(at.providerKey());
            if (canned == null) {
                return SearchAnswer.failed(ask.requestId(), at.providerKey(),
                        "test fixture set up no canned answer for " + at.providerKey(), 0L);
            }
            return new SearchAnswer(ask.requestId(), canned.providerKey(), canned.status(),
                    canned.hits(), canned.elapsedMs(), canned.message());
        };
    }

    private SearchLadder ladder(SearchProvider provider) {
        return new SearchLadder(store, properties, provider);
    }

    @Test
    void the_first_rung_that_answers_is_the_answer_and_the_second_is_never_dialled() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        answers("searxng", hit("https://a.example"));

        LadderResult r = ladder(recordingProvider()).run("q", 10, List.of());

        assertEquals("searxng", r.providerKey());
        assertIterableEquals(List.of("searxng"), dialled());
    }

    @Test
    void a_rung_that_fails_escalates_and_the_next_rung_answers() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        fails("searxng", "connect timed out");
        answers("brave", hit("https://b.example"));

        Logger logger = (Logger) LoggerFactory.getLogger(SearchLadder.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);
        LadderResult r;
        try {
            r = ladder(recordingProvider()).run("secret query", 10, List.of());
        } finally {
            logger.detachAppender(warnings);
            warnings.stop();
        }

        assertEquals("brave", r.providerKey());
        assertIterableEquals(List.of("searxng", "brave"), dialled());
        String logged = warnings.list.getFirst().getFormattedMessage();
        assertTrue(logged.contains("providerKey=searxng"), logged);
        assertTrue(logged.contains("status=FAILED"), logged);
        assertTrue(logged.contains("connect timed out"), logged);
        assertFalse(logged.contains("secret query"), logged);
    }

    @Test
    void a_thin_answer_is_still_the_answer_and_nothing_escalates() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        answers("searxng", hit("https://only-one.example"));

        LadderResult r = ladder(recordingProvider()).run("q", 50, List.of());

        assertEquals(1, r.hits().size());
        assertIterableEquals(List.of("searxng"), dialled());
    }

    @Test
    void an_empty_answer_is_still_an_answer_because_that_is_a_fact_about_the_query() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        answers("searxng");

        assertEquals("searxng", ladder(recordingProvider()).run("q", 10, List.of()).providerKey());
        assertIterableEquals(List.of("searxng"), dialled());
    }

    @Test
    void a_rung_over_the_failure_threshold_is_passed_over_without_being_dialled() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        failuresOn("searxng", 5);
        answers("brave", hit("https://b.example"));

        ladder(recordingProvider()).run("q", 10, List.of());

        assertIterableEquals(List.of("brave"), dialled());
    }

    @Test
    void a_ladder_entry_naming_nothing_registered_is_skipped_and_named_in_the_refusal() {
        register("brave");
        ladderIs("typo-searxng,brave");
        fails("brave", "down");

        LadderResult r = ladder(recordingProvider()).run("q", 10, List.of());

        assertNull(r.providerKey());
        assertTrue(r.refusal().contains("typo-searxng"));
        assertTrue(r.refusal().contains("not registered"));
    }

    /**
     * A blank query throws the same way whether or not a rung is registered.
     *
     * <p>Two calls, deliberately, because the defect was that they disagreed:
     * before the guard, a registered healthy rung meant {@code SearchAsk}'s
     * constructor threw {@link IllegalArgumentException} from inside {@code
     * invoke}, while an empty ladder meant this method returned a refusal and
     * never constructed one. A caller could not know which it would get,
     * because the difference was registry state it cannot see. {@code
     * SearchService.validate} covers the production path, but this class is a
     * bean and the next caller inherits what this method does, not what its
     * current caller does before calling it.
     *
     * <p>The empty-ladder half is the one that actually changed behaviour;
     * the registered half is here so the test says "these two agree" rather
     * than only "this one throws".
     */
    @Test
    void a_blank_query_throws_whether_or_not_a_rung_is_registered() {
        register("searxng");
        ladderIs("searxng");
        answers("searxng", hit("https://a.example"));
        assertThrows(IllegalArgumentException.class,
                () -> ladder(recordingProvider()).run("   ", 10, List.of()));

        ladderIs("");
        assertThrows(IllegalArgumentException.class,
                () -> ladder(recordingProvider()).run("   ", 10, List.of()));
        assertTrue(dialled().isEmpty(), "a malformed query must not dial anything");
    }

    /** {@code max} below one, on the same reasoning — {@link
     *  io.aeyer.plowshare.protocol.search.SearchAsk} refuses it too, and it
     *  must refuse it here for the same reason regardless of the registry. */
    @Test
    void a_max_below_one_throws_on_an_empty_ladder_too() {
        ladderIs("");

        assertThrows(IllegalArgumentException.class,
                () -> ladder(recordingProvider()).run("q", 0, List.of()));
    }

    @Test
    void an_empty_ladder_refuses_by_saying_no_provider_is_configured() {
        ladderIs("");

        assertTrue(ladder(recordingProvider()).run("q", 10, List.of()).refusal()
                .contains("no search provider"));
    }

    @Test
    void the_refusal_distinguishes_a_rung_that_failed_from_a_rung_that_was_skipped() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        failuresOn("searxng", 5);
        fails("brave", "connect timed out");

        String refusal = ladder(recordingProvider()).run("q", 10, List.of()).refusal();

        assertTrue(refusal.contains("searxng"));
        assertTrue(refusal.contains("skipped"));
        assertTrue(refusal.contains("brave"));
        assertTrue(refusal.contains("connect timed out"));
    }

    @Test
    void a_success_clears_the_failure_count_and_a_failure_raises_it() {
        register("searxng");
        ladderIs("searxng");
        fails("searxng", "down");
        ladder(recordingProvider()).run("q", 10, List.of());
        assertEquals(1, store.find("searxng").orElseThrow().consecutiveFailures());

        answers("searxng", hit("https://a.example"));
        ladder(recordingProvider()).run("q", 10, List.of());
        assertEquals(0, store.find("searxng").orElseThrow().consecutiveFailures());
    }

    @Test
    void a_rung_that_throws_escalates_and_the_next_rung_answers() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        throwsOn("searxng");
        answers("brave", hit("https://b.example"));

        LadderResult r = ladder(recordingProvider()).run("q", 10, List.of());

        assertEquals("brave", r.providerKey());
        assertIterableEquals(List.of("searxng", "brave"), dialled());
    }

    @Test
    void a_malformed_answer_with_no_status_escalates_and_the_next_rung_answers() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        malformedNoStatus("searxng");
        answers("brave", hit("https://b.example"));

        LadderResult r = ladder(recordingProvider()).run("q", 10, List.of());

        assertEquals("brave", r.providerKey());
        assertIterableEquals(List.of("searxng", "brave"), dialled());
    }

    @Test
    void a_malformed_failed_answer_with_no_message_escalates_and_the_next_rung_answers() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        malformedFailedNoMessage("searxng");
        answers("brave", hit("https://b.example"));

        LadderResult r = ladder(recordingProvider()).run("q", 10, List.of());

        assertEquals("brave", r.providerKey());
        assertIterableEquals(List.of("searxng", "brave"), dialled());
    }

    @Test
    void a_refusal_built_from_only_malformed_answers_contains_no_null() {
        register("searxng");
        register("brave");
        ladderIs("searxng,brave");
        malformedNoStatus("searxng");
        malformedFailedNoMessage("brave");

        LadderResult r = ladder(recordingProvider()).run("q", 10, List.of());

        assertNull(r.providerKey());
        assertFalse(r.refusal().contains("null"));
        assertIterableEquals(List.of("searxng", "brave"), dialled());
    }
}
