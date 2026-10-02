package io.aeyer.plowshare.server.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.auth.AuthProperties;
import io.aeyer.plowshare.server.auth.TokenStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The two verbs an operator changes a running server through.
 *
 * <p><b>The context is real and only the database is not.</b> Every test here
 * boots an {@link ApplicationContextRunner} carrying two {@code @Live}
 * properties classes and the real {@link RuntimeConfigSeed}, then builds the
 * controller over the seed that boot produced. That shape is the point rather
 * than convenience: {@code pinned} is {@link RuntimeConfigSeed#operatorPinned}'s
 * answer, and a test that mocked the seed would assert only that this class can
 * copy a boolean out of a stub. The fixture that decides pinned-ness — a source
 * shaped like the packaged {@code application.yml} — is {@code
 * RuntimeConfigSeedTest.packagedDefault}, shared rather than rebuilt here so
 * that the two files cannot disagree about what the jar's own source looks like.
 *
 * <p>The map is {@code RuntimeConfigSeedTest.InMemory} for the same reason it is
 * there: what a row does against a real Postgres is {@code RuntimeConfigTest}'s
 * subject, and nothing in this file turns on SQL.
 *
 * <p><b>What this file cannot assert is that no agent tool exists for either
 * verb</b>, which is the design decision it sits beside — no test proves an
 * absence. What stands in for it: the tool surface is assembled in {@code
 * AgentsConfig} and every tool an agent can reach is written out in the fixtures
 * under {@code src/test/resources/surface}, so a tool over this controller
 * cannot be added without changing files whose whole purpose is to be read in
 * review.
 */
class RuntimeConfigControllerTest {

    /**
     * Pinned in every fixture below: supplied from above the packaged sources,
     * which is an operator saying so.
     */
    private static final String BUDGET = "plowshare.documents.ingest-budget";

    /**
     * Unpinned in every fixture below: supplied only by a source shaped like the
     * {@code application.yml} inside the jar.
     *
     * <p>Was a now-retired directory key until task 10; {@code
     * plowshare.llm.sampling-directory} holds the same two properties this
     * fixture needs — real, bound, genuinely not {@code @Live} — so the
     * refusal it demonstrates is unchanged.
     */
    private static final String DIRECTORY = "plowshare.llm.sampling-directory";

    /** Real, bound, and deliberately not {@code @Live}: the key a refusal is
     *  demonstrated on has to be one that genuinely exists, or the test would
     *  pass for the wrong reason. */
    private static final String NOT_LIVE = "plowshare.auth.token-file";

    private static final String OPERATOR_TOKEN = "an-operator-token-for-this-test";

    /** The map the seed writes into at boot, per test method. */
    private final RuntimeConfigSeedTest.InMemory config = new RuntimeConfigSeedTest.InMemory();

    /**
     * 401 without a credential, 200 with one — and nothing written in between.
     *
     * <p>The route being under {@code /v1} is asserted for the whole tree by
     * {@code
     * AuthFilterTest.every_route_this_application_publishes_is_gated_or_deliberately_open},
     * which scans the classpath for controllers and needs no entry for this one.
     * What that cannot see is this endpoint through the filter: that a refusal
     * is a 401 rather than a 500, and above all <b>that the refusal happens
     * before the write</b>. An endpoint that answered 401 after upserting the
     * row would satisfy a status-only assertion and would have changed the
     * server anyway.
     *
     * <p><b>This asserts authentication and not authorization, and this server
     * has no finer distinction to assert.</b> The method was called {@code
     * a_write_needs_the_operator_token}, which named a privilege tier that does
     * not exist here: {@code AuthFilter.accepted} asks {@link
     * TokenStore#validAccess} and nothing else, and {@link
     * TokenStore#acceptOperator} stores the operator token <em>as</em> an
     * ordinary access grant — on a never-expiring chain, but an access grant. So
     * a session token minted by {@code AuthController} passes this endpoint
     * exactly as the operator token does, and the old name asserted a rule the
     * code could not have broken. The credential below is an operator token only
     * because it is the cheapest one to mint in a unit test.
     */
    @Test
    void a_write_is_refused_without_a_credential_and_refused_before_it_writes() {
        TokenStore tokens =
                new TokenStore(Clock.systemUTC(), Duration.ofMinutes(15), Duration.ofDays(7),
                        Duration.ofSeconds(10));
        tokens.acceptOperator(OPERATOR_TOKEN);

        serving(config, controller -> {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new ApiExceptionHandler())
                    .addFilters(new AuthFilter(tokens, new AuthProperties()))
                    .build();

            mvc.perform(write(BUDGET, "900")).andExpect(status().isUnauthorized());
            assertEquals(Optional.of("300"), config.get(BUDGET),
                    "the refusal must happen before the upsert, not after it");

            mvc.perform(write(BUDGET, "900").header("Authorization", "Bearer " + OPERATOR_TOKEN))
                    .andExpect(status().isOk());
            assertEquals(Optional.of("900"), config.get(BUDGET));
        });
    }

    /**
     * A key with no {@code @Live} accessor is refused, and the refusal says what
     * may be written instead.
     *
     * <p>Writing it would be accepted by the schema, persisted, and read by
     * nobody — {@code plowshare.auth.token-file} is bound once and held in a
     * final field — so an operator would change a value, see it stick, and watch
     * the server go on ignoring it. That is the silent no-op {@link Live} exists
     * to refuse at boot, arriving at runtime instead.
     *
     * <p>The message assertion is not decoration: a bare 400 tells an operator
     * they were wrong and not what would have been right, and the listing of
     * live keys is the shortest route from the refusal to the write they meant.
     */
    @Test
    void a_key_that_is_not_live_cannot_be_written() {
        serving(config, controller -> {
            open(controller).perform(write(NOT_LIVE, "/tmp/token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString(NOT_LIVE)))
                    .andExpect(jsonPath("$.detail", containsString("not a live configuration key")))
                    .andExpect(jsonPath("$.detail", containsString(BUDGET)))
                    .andExpect(jsonPath("$.detail", containsString(DIRECTORY)));

            assertEquals(Optional.empty(), config.get(NOT_LIVE));
        });
    }

    /**
     * §1.2 is only usable if a caller can see it <em>before</em> writing.
     *
     * <p>A runtime write is permanent unless an operator pinned that key outside
     * the jar, and temporary if they have — the next boot puts the pin back.
     * Both keys are here in one listing because either alone would pass against
     * a constant: a controller that hard-coded {@code false} satisfies the
     * unpinned row, one that hard-coded {@code true} satisfies the pinned row,
     * and only the pair says the field is computed.
     *
     * <p>The rest of the row is asserted alongside it for a reason of the same
     * shape. {@code updatedBy} of {@code "boot"} on both is what says these
     * values were written by the seed rather than typed by somebody, which is
     * the question the column exists to answer.
     */
    @Test
    void the_listing_says_whether_an_operator_has_pinned_each_key() {
        serving(config, controller -> {
            open(controller).perform(get("/v1/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    // Sorted by key, which RuntimeConfigSeed.declared() fixes so
                    // that the same tree answers the same way on every JVM.
                    .andExpect(jsonPath("$[0].key").value(BUDGET))
                    .andExpect(jsonPath("$[0].value").value("300"))
                    .andExpect(jsonPath("$[0].updatedBy").value("boot"))
                    .andExpect(jsonPath("$[0].pinned").value(true))
                    .andExpect(jsonPath("$[1].key").value(DIRECTORY))
                    .andExpect(jsonPath("$[1].value").value("sampling"))
                    .andExpect(jsonPath("$[1].updatedBy").value("boot"))
                    .andExpect(jsonPath("$[1].pinned").value(false));
        });
    }

    /**
     * An operator who wrote without looking first is still told whether it
     * survives a restart.
     *
     * <p>The listing is the documented route to {@code pinned}, and it is the
     * route nobody takes at three in the morning. Carrying the same field back
     * from the write costs one line and is the difference between an operator
     * who knows their change is temporary and one who finds out at the next
     * deploy.
     */
    @Test
    void a_write_answers_with_whether_it_will_survive_a_restart() {
        serving(config, controller -> {
            MockMvc mvc = open(controller);

            mvc.perform(write(BUDGET, "900"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value(BUDGET))
                    .andExpect(jsonPath("$.value").value("900"))
                    .andExpect(jsonPath("$.pinned").value(true));

            mvc.perform(write(DIRECTORY, "elsewhere"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.value").value("elsewhere"))
                    .andExpect(jsonPath("$.pinned").value(false));
        });
    }

    /**
     * A write answers with a timestamp, because the field is there to carry one.
     *
     * <p>{@code updatedAt} was asserted exactly once in this file and asserted to
     * be <em>null</em> — the no-row case — so nothing held the field on the path
     * it exists for. Dropping {@code updatedAt} from {@link
     * RuntimeConfigController.Setting} entirely left every test green, and so did
     * echoing the request back instead of reading the row. Both are changes that
     * silently undo the correction {@code RuntimeConfig}'s upsert took in review:
     * the {@code ON CONFLICT} branch assigns {@code now()} precisely so this
     * surface can show when a key last changed rather than when it was first
     * written, and the comment on that statement names this endpoint as the
     * reader that would otherwise show a frozen timestamp for ever.
     *
     * <p>Non-null and not an exact instant, because the exact instant is the
     * map's to choose — against Postgres it is the transaction's {@code now()},
     * and against {@code InMemory} it is a constant. What the assertion holds is
     * that a written key carries one at all, which is the half a dropped field
     * or an echo breaks.
     */
    @Test
    void a_write_answers_with_the_timestamp_the_map_assigned() {
        serving(config, controller -> {
            open(controller).perform(write(BUDGET, "900"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updatedAt", notNullValue()));

            open(controller).perform(get("/v1/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].key").value(BUDGET))
                    .andExpect(jsonPath("$[0].updatedAt", notNullValue()));
        });
    }

    /**
     * <b>The answer is read back from the map, not echoed from the request.</b>
     *
     * <p>The controller's javadoc argues this in as many words — "if another
     * write lands between the two statements the caller is shown the newer one,
     * which is the truer answer to what is this key now" — and nothing held it.
     * Every other test writes to a quiet map and reads its own value back, which
     * an echo satisfies exactly.
     *
     * <p>So the map here answers {@code entry()} with a row nobody in this test
     * wrote: a different value, a different author, a different timestamp. That
     * is the interleaving the javadoc describes, modelled at the only seam a unit
     * test can reach it — an echo cannot produce any of the three, and each of
     * them is a field the caller acts on.
     */
    @Test
    void a_write_answers_with_what_the_map_holds_and_not_with_what_the_request_hoped() {
        serving(overtaken(), controller -> {
            open(controller).perform(write(BUDGET, "900"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value(BUDGET))
                    .andExpect(jsonPath("$.value").value(OVERTAKEN_VALUE))
                    .andExpect(jsonPath("$.updatedBy").value(OVERTAKEN_AUTHOR))
                    .andExpect(jsonPath("$.updatedAt", notNullValue()))
                    // The row's instant and not merely some instant, so a
                    // read-back that invented `now()` would fail too. Matched
                    // against the raw body in epoch seconds because that is how
                    // this converter renders an Instant -- asserted through
                    // jsonPath it is a BigDecimal, and the type of the comparison
                    // would become the subject of the test.
                    .andExpect(content().string(
                            containsString(String.valueOf(OVERTAKEN_AT.getEpochSecond()))))
                    // Still this controller's own answer and not the row's:
                    // pinned is computed from the seed, whatever the map says.
                    .andExpect(jsonPath("$.pinned").value(true));
        });
    }

    /**
     * <b>The refusal of a key that is not live must not become validation of one
     * that is.</b>
     *
     * <p>Spec §1.1 chose, with the alternative written down and rejected on
     * blast radius, that a live key is checked at boot by the {@code
     * @Configuration} class that has always checked it and is not checked on
     * write. So a budget of {@code 0} — which {@code DocumentsConfig} refuses a
     * boot over — is accepted here, and so is text that is not a number at all.
     * Both are asserted because they fail differently: a range check passes the
     * second and a parse check passes the first.
     *
     * <p>What it costs is stated rather than implied: an operator can write a
     * value that makes the next ingest useless and will find out from the
     * ingest. {@code RuntimeConfig.intOr} is what keeps that survivable, by
     * falling back and warning; nothing here pretends the write was checked.
     *
     * <p>The author is asserted in the same test because this is the only path
     * that writes one: {@code "operator"} against the seed's {@code "boot"} is
     * the whole of what this server can truthfully say about who typed a value,
     * and it is the distinction the column exists for.
     */
    @Test
    void a_live_key_takes_a_value_the_boot_check_would_have_refused() {
        serving(config, controller -> {
            MockMvc mvc = open(controller);

            mvc.perform(write(BUDGET, "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.value").value("0"))
                    .andExpect(jsonPath("$.updatedBy").value("operator"));
            assertEquals(Optional.of("0"), config.get(BUDGET));

            mvc.perform(write(BUDGET, "not a number at all")).andExpect(status().isOk());
            assertEquals(Optional.of("not a number at all"), config.get(BUDGET));
        });
    }

    /**
     * A key nobody has written is still live and still writable, so it is
     * listed — showing what the accessor is falling back to.
     *
     * <p>After an ordinary boot there is no such key, because the seed writes a
     * shipped default into every gap. This is the shape of a map somebody
     * deleted a row from, and it is modelled by handing the controller a
     * <em>different</em>, empty map from the one the boot filled.
     *
     * <p>Omitting the key would be the worse of the two available errors: it is
     * writable, and a caller reading this listing to decide what they may write
     * would be told it does not exist. Showing it blank would be worse still,
     * because {@code RuntimeConfig} treats a blank as a value somebody chose.
     * {@code updatedBy} of null is the whole discriminator — it says the value
     * shown came from the jar or the operator's pin and not from the map — so
     * the null assertion is what holds this test up, not the value one.
     */
    @Test
    void a_live_key_the_map_has_no_row_for_is_listed_with_the_value_it_is_running_on() {
        serving(new RuntimeConfigSeedTest.InMemory(), controller -> {
            open(controller).perform(get("/v1/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].key").value(BUDGET))
                    .andExpect(jsonPath("$[0].value").value("300"))
                    .andExpect(jsonPath("$[0].updatedBy", nullValue()))
                    .andExpect(jsonPath("$[0].pinned").value(true))
                    .andExpect(jsonPath("$[1].key").value(DIRECTORY))
                    .andExpect(jsonPath("$[1].value").value("sampling"))
                    .andExpect(jsonPath("$[1].updatedBy", nullValue()))
                    .andExpect(jsonPath("$[1].updatedAt", nullValue()))
                    .andExpect(jsonPath("$[1].pinned").value(false));
        });
    }

    /**
     * <b>A row for a key that is no longer live is not listed.</b>
     *
     * <p>{@code RuntimeConfig.all}'s javadoc says rows are not the live set in
     * both directions, and hands the reconciliation here; the listing's javadoc
     * closes the argument by claiming a stale row "is <em>not</em> listed, and a
     * caller cannot be told they may write something that would change nothing".
     * Nothing held that half. Both existing listing fixtures are blind to it: in
     * the seeded one the rows are exactly the two declared keys, and in the empty
     * one there are no rows at all — so a listing built from the <em>union</em>
     * of {@code store.all()} and {@code declared()} passes both, and would tell a
     * caller that a dead key is theirs to write. It is the same silent no-op
     * {@link Live} refuses at boot, arriving through the listing instead of
     * through the write.
     *
     * <p>The stale row is written after the boot rather than declared in the
     * fixture, because that is what a stale row is: a key the seed wrote when it
     * was live, still in the map after the accessor that made it live was
     * deleted. {@code plowshare.auth.token-file} stands in for it — a real bound
     * key that genuinely has no {@code @Live} accessor, so this fails for the
     * right reason.
     *
     * <p>The length assertion and the absence assertion are both here on purpose:
     * a union of two sets that overlap in two of three members is caught by the
     * count, and a listing that substituted the stale row for a live one is
     * caught only by naming it.
     */
    @Test
    void a_row_for_a_key_that_is_no_longer_live_is_not_listed() {
        serving(config, controller -> {
            // Not through the controller, which would refuse it -- this is a row
            // left behind by a build in which the key was live.
            config.put(NOT_LIVE, "/tmp/token", "boot");
            assertEquals(Optional.of("/tmp/token"), config.get(NOT_LIVE),
                    "the fixture is pointless unless the row is really there");

            open(controller).perform(get("/v1/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].key").value(BUDGET))
                    .andExpect(jsonPath("$[1].key").value(DIRECTORY))
                    .andExpect(jsonPath("$[?(@.key == '" + NOT_LIVE + "')]").isEmpty());
        });
    }

    /**
     * A database nobody can reach is a 503, and the body does not blame the
     * request.
     *
     * <p>This is the sentence the task exists to delete. With no handler for it,
     * an operator's {@code PUT} against a dead Postgres reached the catch-all
     * and came back 500 ending <i>"This is a fault in the server, not in the
     * request"</i> — which sent the one person who could restart the database
     * looking for a bug in this code. The absence assertion is the one that
     * fails if the handler is removed; the status alone would not distinguish a
     * 503 that said the wrong thing.
     */
    @Test
    void an_unreachable_map_does_not_blame_the_request() {
        serving(unreachable(), controller -> {
            open(controller).perform(write(BUDGET, "900"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("config_unavailable"))
                    .andExpect(jsonPath("$.detail",
                            containsString("Nothing was read and nothing was written")))
                    .andExpect(jsonPath("$.detail",
                            containsString("nothing wrong with the request")))
                    .andExpect(jsonPath("$.detail",
                            not(containsString("fault in the server, not in the request"))))
                    // The driver's half of the message, not only Spring's: "could
                    // not obtain a connection" without "connection refused" sends
                    // an operator looking at the wrong host.
                    .andExpect(jsonPath("$.detail", containsString("Connection refused")));

            open(controller).perform(get("/v1/config"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("config_unavailable"));
        });
    }

    /**
     * A write that could not take its lock is answered the same way, and that is
     * a decision rather than an accident.
     *
     * <p>Only {@code NonTransientDataAccessResourceException} was exercised: the
     * other two disjuncts of {@code ConfigUnavailableException.unreachable} could
     * both be deleted with every test still green, so the classification was held
     * against widening and not against narrowing. These are the narrowing side.
     *
     * <p>{@code CannotAcquireLockException} is the case worth spending a test on,
     * because it is the one the branch's name is wrong about. It sits under
     * {@code ConcurrencyFailureException} and so under {@code
     * TransientDataAccessException} — verified against the spring-tx on this
     * classpath — so two operators writing one key at once, one of them losing
     * the row lock on the {@code ON CONFLICT DO UPDATE}, is reported as a map
     * that "could not be reached" although it plainly was. The status and the
     * advice are right and the noun is not; the exception's own javadoc now says
     * so rather than leaving a reader to find it. {@code QueryTimeoutException}
     * is the branch's documented member and is asserted beside it so that
     * narrowing the disjunct to that one class is caught too.
     */
    @Test
    void a_write_that_lost_its_lock_is_an_outage_the_caller_should_retry() {
        serving(throwing(new CannotAcquireLockException(
                        "could not obtain lock on row in relation \"runtime_config\"")),
                controller -> open(controller).perform(write(BUDGET, "900"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.error").value("config_unavailable")));

        serving(throwing(new QueryTimeoutException("statement timeout")), controller ->
                open(controller).perform(write(BUDGET, "900"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.error").value("config_unavailable")));
    }

    /**
     * A Postgres still coming back up is an outage, not a broken server.
     *
     * <p>The third disjunct, and the one with no subclass in play: {@code
     * RecoverableDataAccessException} extends {@code DataAccessException}
     * directly, so it is reachable by nothing else on this list and deleting the
     * disjunct is invisible without a case of its own. A database in recovery
     * refuses the write for a reason the operator fixes by waiting, which is the
     * whole test for whether 503 is the honest status.
     */
    @Test
    void a_database_still_recovering_is_an_outage_and_not_a_broken_server() {
        serving(throwing(new RecoverableDataAccessException("the database system is in recovery")),
                controller -> open(controller).perform(write(BUDGET, "900"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.error").value("config_unavailable")));
    }

    /**
     * A row the schema refused is a fault in this server, and must not be
     * dressed up as an outage.
     *
     * <p>V28's {@code CHECK (updated_by <> '')} raises {@code
     * DataIntegrityViolationException}, and 503 would advise an operator to wait
     * for a database that is perfectly healthy and will refuse the identical
     * write for ever — advice that cannot come true, which is the rule {@code
     * ApiExceptionHandler.unreadableDocument} already states in the other
     * direction. So {@code ConfigUnavailableException} classifies rather than
     * catching {@code DataAccessException} whole, and this is the test that
     * makes the classification load-bearing: widen it by one line and this goes
     * red while every other test in the file stays green.
     */
    @Test
    void a_write_the_schema_refuses_is_a_fault_in_this_server_and_not_an_outage() {
        serving(refusing(), controller -> {
            open(controller).perform(write(BUDGET, "900"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("internal_error"))
                    .andExpect(jsonPath("$.detail",
                            containsString("DataIntegrityViolationException")));
        });
    }

    /**
     * The 503's message must not carry the password the datasource was built
     * with, and nor must anything in its cause chain.
     *
     * <p><b>This class's scrubbing rule is an acknowledged copy of {@code
     * ArchiveUnavailableException}'s, and until now it was a copy of the rule
     * without a copy of the measurement.</b> That exception's own javadoc says
     * the credential guarantee is <em>a measurement of these two messages on
     * this driver</em> rather than a general claim that JDBC never carries one —
     * so a second class that splices the same two messages together needs its
     * own, or it is trusting an argument made about somebody else's code. {@code
     * no_database_password_reaches_the_message_or_any_cause_in_the_chain} in
     * {@code ArchiveUnavailableTest} is the original; this is the same
     * instrument pointed at {@link ConfigUnavailableException#describe}.
     *
     * <p>It matters more here than it did there, and that is what moved it from
     * "acknowledged gap" to a test. The archive's 503 reaches a model. This one
     * reaches a person, through a console that <em>renders the detail
     * verbatim</em> — {@code api.ts} carries a refusal's own sentence to the
     * page now, so this body is on a screen rather than only in a log, and the
     * config 503 is one it shows in full.
     *
     * <p>The real driver against a port nothing listens on, and not a hand-built
     * exception: what is being measured is what the driver puts in its message,
     * which a fixture would decide for itself.
     */
    @Test
    void no_database_password_reaches_the_message_or_any_cause_in_the_chain() {
        RuntimeConfig behindADeadDatabase = new RuntimeConfig(unreachableDatabase());

        ConfigUnavailableException down = assertThrows(ConfigUnavailableException.class,
                () -> ConfigUnavailableException.translating("write " + BUDGET, () -> {
                    behindADeadDatabase.put(BUDGET, "900", "an-operator");
                    return null;
                }));

        assertTrue(down.getMessage().contains("could not be reached"), down.getMessage());
        for (Throwable link = down; link != null; link = link.getCause()) {
            assertFalse(String.valueOf(link.getMessage()).contains(PASSWORD),
                    link.getClass().getName() + " carried the password: " + link.getMessage());
        }
    }

    /**
     * And the same, one layer out, where the string actually goes.
     *
     * <p>The test above pins the exception; this pins the HTTP body, which is
     * the thing that leaves the process. They are not the same assertion: a
     * future handler that logged the cause chain into the detail would keep the
     * first green.
     */
    @Test
    void the_503_an_operator_reads_does_not_carry_the_database_password() {
        serving(new RuntimeConfig(unreachableDatabase()), controller ->
                open(controller).perform(write(BUDGET, "900"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.error").value("config_unavailable"))
                        .andExpect(jsonPath("$.detail", not(containsString(PASSWORD))))
                        // The driver's half is still there: scrubbed is not the
                        // same as withheld, and "could not obtain a connection"
                        // without the host sends an operator to the wrong box.
                        .andExpect(jsonPath("$.detail", containsString("refused"))));
    }

    /**
     * Boot a context holding two live keys, and hand the caller a controller
     * reading {@code store}.
     *
     * <p><b>Two, where the server has one</b>, and the second is a fixture
     * rather than a report. A listing has to be shown distinguishing keys from
     * one another — pinned from unpinned above all — and one key cannot show
     * that. {@code plowshare.llm.sampling-directory} is borrowed for the shape
     * because it is a real bound property of a different type; it is
     * <b>not</b> annotated {@link Live} in this server, on the same argument
     * spec §6.2 makes for the now-retired directory key that used to sit
     * beside it before task 10 removed it. Nothing here should be read as the live set.
     *
     * <p>{@code store} is a parameter and is usually {@link #config} — the same
     * map the seed just filled. The tests that need a map in a state a boot
     * cannot leave behind (empty, unreachable, refusing) pass a different one,
     * which works because the seed touches the map only during the refresh and
     * the controller only after it.
     */
    private void serving(RuntimeConfig store, Served body) {
        new ApplicationContextRunner()
                .withBean(RuntimeConfig.class, () -> config)
                .withInitializer(context ->
                        ConfigurationPropertySources.attach(context.getEnvironment()))
                .withUserConfiguration(TwoLiveKeys.class)
                // Only the jar ships this one, so no operator pinned it.
                .withInitializer(RuntimeConfigSeedTest.packagedDefault(DIRECTORY, "sampling"))
                // Supplied from above every packaged source, which is what an
                // operator pinning a key looks like to RuntimeConfigSeed.
                .withPropertyValues(BUDGET + "=300")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    body.accept(new RuntimeConfigController(
                            context.getBean(RuntimeConfigSeed.class),
                            store,
                            context.getEnvironment()));
                });
    }

    private static MockMvc open(RuntimeConfigController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * A {@code PUT} of one key, with the servlet path spelled out.
     *
     * <p>{@link org.springframework.mock.web.MockHttpServletRequest} leaves
     * {@code servletPath} empty unless something sets it, and {@code
     * AuthFilter.gates} is written against the servlet path rather than the
     * request URI — so without this the gated test would be asserting that an
     * <em>empty</em> path is refused, which is a different rule that happens to
     * give the same answer.
     */
    private static MockHttpServletRequestBuilder write(String key, String value) {
        String path = "/v1/config/" + key;
        return put(path).contentType(MediaType.TEXT_PLAIN).content(value).with(servletPath(path));
    }

    private static RequestPostProcessor servletPath(String path) {
        return request -> {
            request.setServletPath(path);
            return request;
        };
    }

    /**
     * A map behind a Postgres that is not answering.
     *
     * <p>{@code CannotGetJdbcConnectionException} with a {@code SQLException}
     * under it, which is the pair Spring actually produces and the pair the 503
     * body is asserted to carry both halves of.
     */
    private static RuntimeConfig unreachable() {
        return new RuntimeConfig(null) {
            @Override
            public List<Entry> all() {
                throw gone();
            }

            @Override
            public Optional<Entry> entry(String key) {
                throw gone();
            }

            @Override
            public void put(String key, String value, String updatedBy) {
                throw gone();
            }

            private CannotGetJdbcConnectionException gone() {
                return new CannotGetJdbcConnectionException(
                        "Failed to obtain JDBC Connection",
                        new SQLException(
                                "Connection to localhost:5432 refused. Connection refused"));
            }
        };
    }

    /**
     * A map that answers every call by raising {@code failure}.
     *
     * <p>{@link #unreachable} stays as it is because its test asserts both halves
     * of a real Spring/driver message pair; this one exists for the branches
     * whose whole subject is <em>which</em> exception, where the message is not
     * the point.
     */
    private static RuntimeConfig throwing(DataAccessException failure) {
        return new RuntimeConfig(null) {
            @Override
            public List<Entry> all() {
                throw failure;
            }

            @Override
            public Optional<Entry> entry(String key) {
                throw failure;
            }

            @Override
            public void put(String key, String value, String updatedBy) {
                throw failure;
            }
        };
    }

    /** What another operator's write left in the row, between this request's
     *  upsert and its read-back. */
    private static final String OVERTAKEN_VALUE = "1500";

    private static final String OVERTAKEN_AUTHOR = "somebody-else";

    private static final Instant OVERTAKEN_AT = Instant.parse("2026-09-06T22:14:33Z");

    /**
     * A map in which a second write always lands between the upsert and the read
     * back.
     *
     * <p>{@code put} is accepted and then ignored, and {@code entry} answers the
     * row the other writer left. Nothing a request sent can produce this row, so
     * an answer echoed from the request cannot pass the test that uses it.
     */
    private static RuntimeConfig overtaken() {
        return new RuntimeConfig(null) {
            @Override
            public void put(String key, String value, String updatedBy) {
                // Accepted, and then overtaken.
            }

            @Override
            public Optional<Entry> entry(String key) {
                return Optional.of(
                        new Entry(key, OVERTAKEN_VALUE, OVERTAKEN_AT, OVERTAKEN_AUTHOR));
            }
        };
    }

    /**
     * The password the two credential tests look for, and nothing else reads.
     *
     * <p>Distinctive enough that a match cannot be a coincidence, which is the
     * whole instrument: the assertion is an absence, and an absence measured
     * against a common word would pass for the wrong reason.
     */
    private static final String PASSWORD = "hunter2-not-in-any-config-message";

    /**
     * A real {@code JdbcTemplate} pointed at a port nothing listens on, holding
     * {@link #PASSWORD}.
     *
     * <p>Loopback and a closed port, so nothing is reached and no container has
     * to be started. {@code ArchiveUnavailableTest.unreachableDatabase} is the
     * same fixture for the same reason: the classification tests above hand the
     * rule exceptions built by hand, which measure the rule and not what the
     * driver really says — and what the driver really says is the whole subject
     * of a credential test.
     */
    private static JdbcTemplate unreachableDatabase() {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("could not find a port to leave closed", e);
        }
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:postgresql://localhost:" + closed + "/plowshare", "plowshare", PASSWORD));
    }

    /** A map behind a healthy Postgres that will not take this row. */
    private static RuntimeConfig refusing() {
        return new RuntimeConfig(null) {
            @Override
            public void put(String key, String value, String updatedBy) {
                throw new DataIntegrityViolationException(
                        "new row for relation \"runtime_config\" violates check constraint"
                                + " \"runtime_config_has_an_author\"");
            }
        };
    }

    @FunctionalInterface
    private interface Served {
        void accept(RuntimeConfigController controller) throws Exception;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({Documents.class, Sampling.class})
    @Import(RuntimeConfigSeed.class)
    static class TwoLiveKeys {}

    /**
     * A stand-in for {@code DocumentsProperties}, carrying only what the seam
     * under test reads.
     *
     * <p>Not the real class, because binding it drags in every other key it
     * declares and its {@code @Autowired} setter for the store; what the
     * controller asks of a properties bean is only that it declares a {@code
     * @Live} key under its own prefix.
     */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class Documents {

        private int ingestBudget;

        @Live(BUDGET)
        public int ingestBudgetNow() {
            return ingestBudget;
        }

        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }
    }

    /** A second live key so that a listing has something to distinguish, and
     *  live only inside this fixture: the real {@code LlmProperties} does not
     *  carry {@code @Live} on this field, for spec §6.2's reason -- the same
     *  reason the now-retired directory key it used to sit beside carried
     *  none either, before task 10 removed it. */
    @ConfigurationProperties(prefix = "plowshare.llm")
    static class Sampling {

        private String samplingDirectory = "";

        @Live(DIRECTORY)
        public String samplingDirectoryNow() {
            return samplingDirectory;
        }

        public void setSamplingDirectory(String samplingDirectory) {
            this.samplingDirectory = samplingDirectory;
        }
    }
}
