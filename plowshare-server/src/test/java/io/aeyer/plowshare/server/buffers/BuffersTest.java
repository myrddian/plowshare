package io.aeyer.plowshare.server.buffers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.fetch.FetchProperties;
import io.aeyer.plowshare.server.fetch.FetchedPageStore;
import io.aeyer.plowshare.server.search.ResultSetStore;
import io.aeyer.plowshare.server.search.SearchProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link Buffers#purge()}'s wiring, pinned against mocked stores and mocked
 * properties — the seam Ruling 3 of this task introduced, so this class is
 * this task's own responsibility to cover rather than something the brief's
 * file list omitted on purpose.
 *
 * <h2>Why this class exists beside {@code BufferPurgeControllerTest}</h2>
 *
 * <p>The controller test proves there is a door and that it answers
 * synchronously with what a mocked {@link Buffers} handed it; it never
 * constructs a real {@link Buffers}, so it cannot catch a mistake inside this
 * class's one method. {@code FetchedPageStoreTest} and {@code
 * ResultSetStoreTest} prove each store's own {@code purgeExpired} does the
 * right thing for the arguments <em>it</em> is given; neither can catch this
 * class handing either store the <em>wrong</em> arguments, because both call
 * their store directly with values the test itself chose. The gap between
 * those two proofs is exactly this class's four lines of wiring, and it is a
 * gap a positional mistake can fill silently: swapping {@code ttlNow()} and
 * {@code liveWindowNow()} in the call to {@link
 * FetchedPageStore#purgeExpired} compiles, every other test in this tree
 * keeps passing, and the liveness guard {@code fetched_pages} exists to
 * provide inverts — a page an agent is mid-read on would be evicted on its
 * fetch age instead of kept by its liveness, which is precisely the failure
 * {@link FetchedPageStore}'s own javadoc argues at length must not happen.
 *
 * <h2>Three different durations, on purpose</h2>
 *
 * <p>{@link #TTL}, {@link #LIVE_WINDOW} and {@link #RESULT_SET_TTL} are three
 * distinct values, and the bound-getter stand-ins below are three further,
 * distinct values again. A test that reused one duration for two roles could
 * pass on a mis-wiring that happened to read the right value under the wrong
 * name; distinct values make every assertion here fail loudly on exactly the
 * substitution it is written to catch.
 */
class BuffersTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");

    // Three different fetch/search durations, so a value landing in the
    // wrong slot is a distinct, loud failure rather than a coincidental pass.
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration LIVE_WINDOW = Duration.ofMinutes(10);
    private static final Duration RESULT_SET_TTL = Duration.ofMinutes(30);

    // The bound getters answer three more, still-different values, so a
    // purge that read these instead of the live accessors is caught rather
    // than passing because a stub happened to agree with the live one.
    private static final Duration BOUND_TTL = Duration.ofDays(9);
    private static final Duration BOUND_LIVE_WINDOW = Duration.ofDays(8);
    private static final Duration BOUND_RESULT_SET_TTL = Duration.ofDays(7);

    private FetchedPageStore pages;
    private ResultSetStore resultSets;
    private FetchProperties fetchProperties;
    private SearchProperties searchProperties;
    private Buffers buffers;

    @BeforeEach
    void setUp() {
        pages = mock(FetchedPageStore.class);
        resultSets = mock(ResultSetStore.class);
        fetchProperties = mock(FetchProperties.class);
        searchProperties = mock(SearchProperties.class);

        when(fetchProperties.ttlNow()).thenReturn(TTL);
        when(fetchProperties.liveWindowNow()).thenReturn(LIVE_WINDOW);
        when(fetchProperties.getTtl()).thenReturn(BOUND_TTL);
        when(fetchProperties.getLiveWindow()).thenReturn(BOUND_LIVE_WINDOW);

        when(searchProperties.resultSetTtlNow()).thenReturn(RESULT_SET_TTL);
        when(searchProperties.getResultSetTtl()).thenReturn(BOUND_RESULT_SET_TTL);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        buffers = new Buffers(pages, resultSets, fetchProperties, searchProperties, clock);
    }

    /**
     * The one wiring mistake that matters most: {@code ttlNow()} and {@code
     * liveWindowNow()} reaching {@link FetchedPageStore#purgeExpired} in the
     * order that method declares them, not swapped.
     *
     * <p>Reversing them at the call site would compile and every other test
     * in this tree — this file's own other three tests, both controller
     * tests, and both stores' Testcontainers suites — would keep passing,
     * because none of them constructs a real {@link Buffers} and hands it
     * both a distinguishable TTL and a distinguishable live window at once.
     * What would change, silently, is the liveness guard itself: a page an
     * agent is still reading would become purgeable the moment its fetch
     * crossed {@link #LIVE_WINDOW} (ten minutes) rather than {@link #TTL}
     * (24 hours), and a page nobody has touched in a day would be protected
     * as though it were still being read. This assertion is what stands
     * between that inversion and a green build.
     */
    @Test
    void the_fetch_purge_receives_the_ttl_and_the_live_window_in_that_order() {
        buffers.purge();

        verify(pages).purgeExpired(NOW, TTL, LIVE_WINDOW);
    }

    /**
     * The cross-wiring case: {@link ResultSetStore#purgeExpired} receives
     * {@link SearchProperties#resultSetTtlNow()}'s value, not {@link #TTL} or
     * {@link #LIVE_WINDOW} — the fetch side's own two durations, sitting
     * right next to this call at the same call site.
     */
    @Test
    void the_result_set_purge_receives_the_search_result_set_ttl_not_a_fetch_duration() {
        buffers.purge();

        verify(resultSets).purgeExpired(NOW, RESULT_SET_TTL);
    }

    /**
     * The live accessors are what a purge reads, never the bound getters —
     * {@link FetchProperties#ttlNow()}, {@link FetchProperties#liveWindowNow()}
     * and {@link SearchProperties#resultSetTtlNow()}, not {@code getTtl()},
     * {@code getLiveWindow()} or {@code getResultSetTtl()}. Each live
     * accessor is stubbed to a value the matching bound getter does not
     * share, so a purge that silently fell back to the bound value would
     * fail this test's {@code verify(..., never())} lines rather than merely
     * receiving a number nothing here would distinguish from the right one.
     *
     * <p>What this protects: an operator's {@code PUT /v1/config} write to
     * {@code plowshare.fetch.ttl}, {@code plowshare.fetch.live-window} or
     * {@code plowshare.search.result-set-ttl} reaching the very next purge
     * without a restart — the whole reason those three keys are {@link
     * io.aeyer.plowshare.server.config.Live}. A purge that read the bound
     * getters instead would still run, still delete rows, and would ignore
     * that write completely, which is a silent policy failure rather than a
     * loud one.
     */
    @Test
    void the_live_accessors_are_read_not_the_bound_getters() {
        buffers.purge();

        verify(fetchProperties).ttlNow();
        verify(fetchProperties).liveWindowNow();
        verify(searchProperties).resultSetTtlNow();
        verify(fetchProperties, never()).getTtl();
        verify(fetchProperties, never()).getLiveWindow();
        verify(searchProperties, never()).getResultSetTtl();
    }

    /**
     * Both stores are judged against the one instant {@link #purge()} read
     * from its clock, not two separate reads a moment apart.
     *
     * <p>{@link Clock#fixed} answers the same instant on every call by
     * construction, so it cannot by itself tell a single read from two —
     * both would look identical through a fixed clock. The clock here is
     * instead a mock stubbed to answer two different instants on its first
     * and second call: a {@link Buffers#purge()} that reads {@code
     * clock.instant()} once, into a local value it hands to both stores,
     * gives both the first instant; one that read it twice — a purge slow
     * enough, or careless enough, to ask the clock again before calling the
     * second store — would hand the second store a later instant, and the
     * two captured values below would disagree.
     */
    @Test
    void both_stores_are_judged_against_the_same_instant() {
        Clock twoDifferentInstants = mock(Clock.class);
        when(twoDifferentInstants.instant())
                .thenReturn(NOW, NOW.plus(Duration.ofSeconds(5)));
        Buffers withSequencedClock = new Buffers(
                pages, resultSets, fetchProperties, searchProperties, twoDifferentInstants);

        withSequencedClock.purge();

        ArgumentCaptor<Instant> fetchNow = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> resultSetNow = ArgumentCaptor.forClass(Instant.class);
        verify(pages).purgeExpired(fetchNow.capture(), eq(TTL), eq(LIVE_WINDOW));
        verify(resultSets).purgeExpired(resultSetNow.capture(), eq(RESULT_SET_TTL));

        assertEquals(fetchNow.getValue(), resultSetNow.getValue(),
                "both stores must be judged against the same now -- a purge that read the "
                        + "clock twice could judge one store's rows a moment later than the "
                        + "other's");
    }
}
