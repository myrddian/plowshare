package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.client.HttpServerClient;
import io.aeyer.plowshare.client.mcp.StdioTransport;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import io.aeyer.plowshare.client.tools.AgentTools;
import io.aeyer.plowshare.client.tools.MemoryTools;
import io.aeyer.plowshare.client.tools.ProjectTools;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.LocalHooks;
import io.aeyer.plowshare.server.agents.PinnedLocalHooks;
import io.aeyer.plowshare.server.archive.ReasonLog;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.hooks.script.ScriptHooks;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.orchestrations.Orchestrations;
import io.aeyer.plowshare.server.orchestrations.Studio;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The test the slice exists for: a memory written over MCP comes back from a recall over MCP.
 *
 * <p>Everything between the two is real — the JSON-RPC transport, the tool registry, the HTTP
 * client, Spring, Flyway, Postgres and pgvector — with one deliberate exception, the embedding
 * model. {@link MarkerEmbeddings} stands in for it, because a test that reached a live model would
 * be measuring the model: Excalibur's golden-set eval scored anywhere from 2/4 to 4/4 on identical
 * code, and a suite whose red runs mean "the model had a bad day" cannot tell anyone whether a
 * change helped.
 *
 * <p><b>What that stub does and does not prove.</b> It maps a question and a memory onto the same
 * direction by keyword, so the sentence "recall matched on meaning" is not what passes here. What
 * passes is that a question becomes a vector, crosses two processes' worth of protocol, reaches
 * {@code ORDER BY embedding <=> ?}, and brings back the row nearest it and not the other one — with
 * no substring of the question appearing anywhere in the memory it finds. Whether real English
 * questions land near the right vectors is a property of the embedding model, and it is measured by
 * using it, not by asserting on it.
 */
@Tag("full-db")
@Testcontainers
// The application must release its pool and workers before its class-owned database stops.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    // Both named explicitly, and the second one is not ceremony.
    //
    // The application class has to be named because this test sits in
    // io.aeyer.plowshare, which is *above* the application's own package
    // rather than below it, and @SpringBootTest only searches upwards for a
    // @SpringBootConfiguration.
    //
    // Naming it is what makes the nested @TestConfiguration have to be
    // named too: Spring only auto-detects a nested @TestConfiguration when
    // the test declares no classes of its own, so with `classes` set and
    // StubbedEmbeddings left off this list, the stub is silently never
    // registered — and the archive quietly embeds against whatever real
    // endpoint plowshare.llm.pools[0].base-url points at. That is not a
    // hypothetical: it is what this test did on the first run, reaching a
    // live LM Studio on the developer's own box and failing with "No models
    // loaded". A suite that can reach a model is a suite whose red runs
    // mean "the model had a bad day" as often as they mean "the code is
    // wrong".
    classes = {PlowshareServerApplication.class, EndToEndTest.StubbedEmbeddings.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndTest {

  /**
   * The pgvector image, not stock postgres:16: the migration's first line is CREATE EXTENSION
   * vector, and stock Postgres has no vector.so to load.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final int CLOSED_PORT = closedPort();

  @TempDir static Path serverData;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    // The container's port is chosen when it starts, so it cannot be a
    // static value in application.yml or a properties file.
    registry.add("plowshare.data.dir", () -> serverData.resolve("data").toString());
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);

    // A second lock on the same door as StubbedEmbeddings. Nothing in this
    // context reaches a model at the moment, but the pool this configures
    // is what a real transport gets built onto, and @Primary would then be
    // the only thing keeping it out of the archive's hands. Pointing the
    // pool at a port nothing is listening on means that if that ever stops
    // working, this test fails with "connection refused" instead of quietly
    // measuring whichever model happens to be loaded on the machine running
    // the build. The default is LM Studio on localhost:1234, which on a
    // developer's own box is very often up.
    //
    // Overriding the environment variable and not the property, which is
    // not a stylistic choice. A base URL now lives at
    // plowshare.llm.pools[0].base-url, and a property source of higher
    // precedence that names one key inside pools[0] does not merge with
    // application.yml — it replaces the whole list. Registering that key
    // here left the pool with a base URL and nothing else: no name, no
    // models, an empty classes map, and only the two indexed models[n]
    // entries loud enough for ignoreUnknownFields to catch. The map was
    // discarded in silence. LLM_BASE_URL is the placeholder application.yml
    // already reads, so the YAML stays the only source of pools[0] and the
    // override lands where an operator would put it.
    //
    // That indirection is checked rather than assumed — see
    // the_pool_this_context_configures_points_at_a_port_nothing_answers.
    registry.add("LLM_BASE_URL", () -> "http://localhost:" + CLOSED_PORT + "/v1");

    // And every other pool the shipped YAML declares. A second pool arrived
    // with the DGX Spark, and its shipped default is loopback rather than a
    // real address -- but "the default is harmless" is a property of the
    // file and not of this context, and the guard below is written to hold
    // whatever the file says. One env var per pool, named the same way.
    registry.add("SPARK_BASE_URL", () -> "http://localhost:" + CLOSED_PORT + "/v1");

    // No directory property for the shipped definitions any more: since
    // task 6, AgentsConfig.agentRegistry always reads them off the
    // classpath through ClasspathDefinitions, which this context gets for
    // free, so there is nothing left for this method to point anywhere.
  }

  /**
   * Replaces the one thing in this test that would otherwise reach a model.
   *
   * <p>{@code @Primary}, and it now does two jobs rather than the one it was written for. {@code
   * LlmConfig} builds a real {@code DispatchingEmbeddingClient} in this context — harmlessly, since
   * it opens no connection until something calls it and nothing here does — so this annotation is
   * what keeps it out of the archive's hands <em>and</em> what resolves an injection point that now
   * has two candidates. Measured rather than assumed: dropping it fails all eight tests here at
   * context startup with {@code NoUniqueBeanDefinitionException}.
   */
  @TestConfiguration
  static class StubbedEmbeddings {
    @Bean
    @Primary
    EmbeddingClient markerEmbeddings() {
      return new MarkerEmbeddings();
    }
  }

  @LocalServerPort private int port;

  @Autowired private JdbcTemplate jdbc;

  /**
   * The {@code @Primary} stub, so a test can make the embedding endpoint fail for one call — the
   * only way to reach, over the real wire, the state a write during an outage leaves behind.
   */
  @Autowired private EmbeddingClient embeddings;

  @Autowired private LlmProperties llm;

  @Autowired private AgentRegistry agents;

  /**
   * What the server will bind for an agent, which is the other end of {@code
   * no_agent_tool_can_lend_a_project_a_directory}: the harness surface says a tool exists, and this
   * says an agent may not name it.
   */
  @Autowired private JobRuntime runtime;

  /** The engine whose installer the Studio is made at wiring. */
  @Autowired private Orchestrations orchestrations;

  /**
   * Where the account of each write ends up. Read directly rather than over the wire, because the
   * whole claim is that it outlives the response.
   */
  @Autowired private ReasonLog reasons;

  /**
   * The gate this suite has to get through. Slice 4 task 7 put an AuthFilter in front of every /v1
   * path, so a client with no credential reads nothing here — see
   * an_unauthenticated_call_reaches_nothing_over_the_real_wire.
   */
  @Autowired private TokenStore tokens;

  /** Where {@link #local_hooks_are_live_in_the_server_as_it_boots} finds the beans it names. */
  @Autowired private ApplicationContext context;

  /**
   * A live access token, minted per test from the server's own store.
   *
   * <p>Option (b) of the two ways to survive the filter, and the reason it was chosen for this
   * class: switching auth off here would leave <b>no</b> end-to-end test exercising the
   * authenticated path, so the suite would go green with the filter fundamentally broken. What this
   * file measures is a harness talking to a server, and a harness that cannot authenticate is not
   * one.
   *
   * <p>Never asserted on with anything that prints its operands. See {@code TokensTest}'s class
   * javadoc for the rule.
   */
  private String access;

  private ToolRegistry registry;

  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setUp() {
    ((MarkerEmbeddings) embeddings).working();
    // The container is per-class, so rows outlive a test unless they are
    // cleared. This is a fixture, not a policy: nothing deletes from the
    // archive in production, in any state — that rule is what makes a
    // tombstone worth writing.
    // CASCADE because V2's `proposals` references this table: a plain
    // TRUNCATE is refused outright, and this class holds no proposals of
    // its own to lose.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, memories CASCADE");
    // The corpus, on the same terms. CASCADE reaches `paragraphs` and
    // `chunks`, which is the whole of what a document is below its row.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, documents CASCADE");

    jdbc.update(
        "INSERT INTO admins (handle, password_hash, server_admin) VALUES ('test-user', 'fixture-hash', TRUE)"
            + " ON CONFLICT DO NOTHING");
    access = tokens.issuePair("test-user", false).access();

    registry = new ToolRegistry();
    // One client for both families, exactly as PlowshareClient.main wires
    // them: the whole surface a harness would be shown, over one base URL —
    // and one credential, which is also how that method wires it.
    HttpServerClient server = new HttpServerClient("http://localhost:" + port, access);
    new MemoryTools(server).registerOn(registry);
    new AgentTools(server).registerOn(registry);
    new ProjectTools(server).registerOn(registry);
  }

  /**
   * The safety net above is real, and this is what proves it.
   *
   * <p>{@code datasource} overrides {@code LLM_BASE_URL}, which only works for as long as {@code
   * application.yml} writes this pool's base URL as {@code ${LLM_BASE_URL:...}}. Someone inlining
   * that literal would not break a single assertion in this class — the stub keeps every test green
   * — and would quietly re-point the pool at LM Studio on localhost:1234, which on a developer's
   * machine is very often running. The suite would then be measuring whichever model happened to be
   * loaded.
   *
   * <p>Overriding the property directly is not the alternative: a source of higher precedence
   * naming one key inside {@code pools[0]} replaces the whole entry rather than merging into it, so
   * the pool would lose its name, its models and its classes. This is the check that keeps the
   * indirection honest instead.
   */
  @Test
  void the_pool_this_context_configures_points_at_a_port_nothing_answers() {
    // EVERY pool, and not pools[0]. This asserted a count of one until a
    // second pool landed, which made it a test that would have to be edited
    // by whoever added a third -- and the edit that satisfies it fastest is
    // to raise the number, which is precisely the edit that lets a suite
    // dial a real machine. Asserting the property instead means a new pool
    // either arrives with its own override above or fails here by name.
    for (PoolProperties pool : llm.getPools()) {
      assertEquals(
          "http://localhost:" + CLOSED_PORT + "/v1",
          pool.getBaseUrl(),
          "pool '" + pool.getName() + "' points somewhere this suite could reach");
    }
    // And the rest of the pool survived the override, which is the half a
    // direct property override silently lost.
    assertEquals("studio", llm.getPools().get(0).getName());
    assertTrue(
        llm.getPools().get(0).getModels().contains(llm.getEmbeddingModel()),
        "the pool has to serve the model the archive embeds with");
  }

  // --- the one that proves the slice ---------------------------------------

  @Test
  void a_memory_written_through_mcp_comes_back_from_recall_through_mcp() throws Exception {
    // The decoy is written *first*, and the order is the whole point. Ids
    // sort by creation time and MemoryStore breaks a distance tie with
    // `ORDER BY embedding <=> ?, id`, so an implementation that lost the
    // vector ordering altogether would answer with whichever memory was
    // written first. Writing the decoy first means only a working vector
    // query can pass this; with the answer written first, an archive that
    // ranked by nothing at all would still look right.
    call(
        "memory_write",
        args(
            "summary", "Deploys go out on Thursdays",
            "scope", "Release scheduling, and when things ship",
            "body", "Thursday afternoons, after the weekly review.",
            "formed_by", "claude",
            "project", "payments"));

    var written =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 4 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Four attempts since the timeout change.",
                "formed_by", "claude",
                "project", "payments"));
    assertTrue(written.contains("mem_"), written);

    // Deliberately shares no distinctive word with the summary: a substring
    // search cannot pass this, so passing means the answer came back from
    // the vector query and not from anything textual on the way.
    var recalled =
        call(
            "memory_recall",
            args(
                "question", "how many times do we try the billing service before giving up?",
                "project", "payments",
                "limit", 1));

    assertTrue(recalled.contains("The retry budget is 4 attempts"), recalled);
    assertFalse(recalled.contains("Thursdays"), recalled);
    // The body, verbatim, is what the caller came for.
    assertTrue(recalled.contains("Four attempts since the timeout change."), recalled);
  }

  // --- the rest of the surface, over the same wire --------------------------

  /**
   * A write the scribe could not judge is still written, and the answer says which failure it was.
   *
   * <p><b>This test used to assert "filed flat: this server has no agent registry", and that
   * sentence going away is the point rather than a regression.</b> It was written in Task 8, when
   * nothing built an {@code AgentRegistry} bean and the {@code ObjectProvider} answered null for
   * every write; it said so, and said it was a tripwire for the day the wiring became real. Task 10
   * is that day. A test still asserting it would be asserting that the wiring had not happened.
   *
   * <p>What replaces it is the wired path end to end, through two writes that take different
   * branches:
   *
   * <ul>
   *   <li>the first goes into an empty tier, so {@code Archive.recall} finds no candidate and the
   *       scribe short-circuits <em>before</em> any model call. That branch is what says candidates
   *       are really retrieved rather than assumed;
   *   <li>the second has the first as a candidate, so the scribe is really asked — and this
   *       context's pool points at a closed port, so the endpoint is what fails.
   * </ul>
   *
   * <p>Both are filed as new and both are indexed, which is the rule that has not changed: a write
   * is never lost over a scribe. What has changed is <em>which</em> of the fallback reasons the
   * caller is shown, and the two here are different strings — which is what "every fallback is
   * distinguishable a year later" buys, seen from outside the server for the first time.
   */
  @Test
  void a_write_the_scribe_could_not_judge_is_filed_flat_and_says_which() throws Exception {
    String first =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 4 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Four attempts since the timeout change.",
                "formed_by", "claude",
                "project", "payments"));

    assertTrue(
        first.contains("filed flat: the archive held nothing close to this proposal"), first);

    String second =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 6 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Raised from four after the incident.",
                "formed_by", "claude",
                "project", "payments"));

    // Asserted for what the answer must say, not for the absence of what it
    // used to: Task 9's last mutation survivor was an assertFalse naming an
    // old sentence verbatim, which passed a mutant that restored half of it.
    assertTrue(second.contains("filed flat: the scribe could not be reached"), second);
    // One negative, narrow and load-bearing: this is the exact sentence that
    // would mean the registry bean had gone away again.
    assertFalse(second.contains("no agent registry"), second);

    // And both memories are there: a write is never lost because the thing
    // that judges its shape could not be asked.
    String index = call("memory_index", args("project", "payments"));
    assertTrue(index.contains(idIn(first)), index);
    assertTrue(index.contains(idIn(second)), index);
  }

  /**
   * And a year later the archive can still tell which failure it was.
   *
   * <p><b>The spec's own sentence, met for the first time.</b> Every scribe fallback "says so in
   * the {@code reason} … So a year later <em>the archive</em> can distinguish 'no scribe judged
   * this' from 'the scribe was busy'." The test above asserts the two sentences in the two <em>HTTP
   * responses</em>, which is where they used to stop: {@code WriteResult} carried them to a client
   * renderer and nothing kept them. This reads them back out of the archive itself, with the
   * responses long gone.
   *
   * <p>Two writes, because one proves nothing. The claim is that the fallbacks are
   * <em>distinguishable</em>, and a build that recorded a single constant — or the same reason for
   * both — passes any assertion made about one row.
   */
  @Test
  void the_reason_a_write_was_filed_flat_outlives_the_response_that_carried_it() throws Exception {
    String first =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 4 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Four attempts since the timeout change.",
                "formed_by", "claude",
                "project", "payments"));
    String second =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 6 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Raised from four after the incident.",
                "formed_by", "claude",
                "project", "payments"));

    String heldNothingClose = onlyReasonFor(idIn(first));
    String couldNotBeReached = onlyReasonFor(idIn(second));

    assertTrue(
        heldNothingClose.contains("the archive held nothing close to this proposal"),
        heldNothingClose);
    assertTrue(couldNotBeReached.contains("the scribe could not be reached"), couldNotBeReached);
    assertFalse(
        heldNothingClose.equals(couldNotBeReached),
        "two fallbacks the archive cannot tell apart is the thing this table exists to"
            + " prevent: "
            + heldNothingClose);
  }

  /**
   * The one account filed for a memory, insisting there is exactly one: a build that recorded an
   * entry per attempt rather than per write would otherwise pass the assertions above on whichever
   * came first.
   */
  private String onlyReasonFor(String memoryId) {
    List<ReasonLog.Entry> filed = reasons.forMemory(memoryId);
    assertEquals(1, filed.size(), "expected one account for " + memoryId + ", got " + filed);
    return filed.get(0).reason();
  }

  /**
   * One write, one embedding call.
   *
   * <p>3a measured the write path at <b>two</b>: {@code Scribe} retrieves its candidates with a
   * vector query and the archive then embeds the memory — and the retrieval happens <em>before</em>
   * the empty-candidate short circuit, so it was 2× on every write past the scribe's three guards
   * and not only on the writes a model ruled on. The two texts were the same string, so the second
   * call recomputed numbers the first already had.
   *
   * <p>The count is the assertion and an equal vector would not be: an implementation that embedded
   * twice and got the same answer both times is exactly what this replaces. Asserted through the
   * whole wire, because the saving only exists if the vector survives the scribe, the controller
   * and the archive's transaction boundary.
   */
  @Test
  void one_write_costs_one_embedding_call_because_the_scribe_hands_its_vector_on()
      throws Exception {
    String written =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 4 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Four attempts since the timeout change.",
                "formed_by", "claude",
                "project", "payments"));

    assertEquals(
        1,
        ((MarkerEmbeddings) embeddings).calls(),
        "the write asked the endpoint more than once: " + written);
    // And the memory really has a vector, which is what says the one call
    // was used rather than skipped. A write that stored nothing would also
    // count one.
    String recalled =
        call(
            "memory_recall",
            args(
                "question", "how many retries before we give up",
                "project", "payments"));
    assertTrue(recalled.contains(idIn(written)), recalled);
  }

  /**
   * A write whose summary has whitespace at an end is still filed.
   *
   * <p><b>This is the cross-class instrument for a drift that was real.</b> {@code
   * Archive.newMemory} strips the summary and the scope, so {@code Archive.embeddedText} is the
   * stripped text; {@code Scribe.question} joined the <em>raw</em> ones. The two agreed for every
   * proposal without padding and differed for the rest — harmless while the two vectors were
   * computed separately, and fatal once one is reused for the other, because {@code applyVerdict}
   * refuses a vector computed from other text rather than storing it. Without the strip this write
   * comes back a 500.
   *
   * <p>Nothing in one class can see this: the two expressions live in {@code Scribe} and {@code
   * Archive} and only meet on the write path. This is the only place in the suite where they do.
   */
  @Test
  void a_write_whose_summary_is_padded_is_still_filed() throws Exception {
    String written =
        call(
            "memory_write",
            args(
                "summary", "   The retry budget is 4 attempts   ",
                "scope", "\n Calling the payments API, or tuning retries \n",
                "body", "Four attempts since the timeout change.",
                "formed_by", "claude",
                "project", "payments"));

    assertTrue(written.contains("filed flat:"), written);
    assertEquals(
        1,
        ((MarkerEmbeddings) embeddings).calls(),
        "and it still cost one call, so the vector really was the one reused");
    String index = call("memory_index", args("project", "payments"));
    assertTrue(index.contains(idIn(written)), index);
    // Stored stripped, which is what makes the two texts the same string.
    assertTrue(index.contains("The retry budget is 4 attempts"), index);
  }

  /**
   * The registry this context wires is the one this server ships.
   *
   * <p>Without this, the two sentences above would be equally satisfied by a context pointed at
   * some other directory that happened to contain a valid {@code scribe.md}. This is also the only
   * end-to-end place the shipped {@code promotion_judge} and {@code code_reviewer} are asserted to
   * have loaded at all — {@code AgentRegistry.load} validates the whole directory, so a shipped
   * file naming a tool this boot does not bind would refuse the context rather than be skipped.
   *
   * <p><b>{@code code_reviewer} is why the third name is here and why it is worth an assertion of
   * its own.</b> It is the first shipped definition to declare a file tool, and this is the only
   * place in the repository where that declaration meets a whole application rather than a context
   * runner: the tools are registered by {@code AgentsConfig}, the set is handed to the loader, and
   * a mismatch between those two is a server that does not start. Asserted as an equality over the
   * list, so that a reviewer which acquired {@code file_write} fails here as well as in {@code
   * CodeReviewerDefinitionTest}.
   *
   * <p><b>{@code interlocutor} is the fourth, and it is the one whose two halves of delegation meet
   * a whole application here.</b> It is the first shipped definition to declare {@code agent_run}
   * and the first to declare {@code file_write}, so it is the first whose {@code calls:} edge is
   * walked by {@code AgentRegistry.withholdEscalatingEdges} against a callee this boot also loaded.
   * A grant it could not hold, or a callee holding more than it, is a server that does not start —
   * and the difference between that check running and never looking is measured in {@code
   * InterlocutorDefinitionTest}, not here.
   *
   * <p><b>{@code close_reader} is the fifth, and it is the first shipped definition to declare
   * {@code document_ask}.</b> That tool is bound out of a {@code Deliberation} this configuration
   * cannot hold directly — a pass runs three agents back through {@code JobRuntime}, so the bean is
   * built after the runtime and arrives as a provider — and this is the only place in the
   * repository where that cycle is resolved by a whole application rather than by a context runner.
   * A wiring that closed it the wrong way round is a server that does not start, and a name
   * registered by the tool layer but never reachable from the loader is a definition refused at
   * boot.
   */
  /**
   * The Studio (spec 2026-09-29-orchestration-studio §3) is built beside the run extras and made
   * the engine's installer when its resolvers and data layout are beans — as they are in every
   * whole application. Wiring that left the engine's own installer in place would answer every
   * install "this server has no Studio", and no unit test builds the context that would show it.
   */
  @Test
  void the_context_makes_the_studio_the_engine_s_installer() {
    assertInstanceOf(Studio.class, orchestrations.installer());
  }

  @Test
  void the_context_wires_the_agents_this_server_ships() {
    // containsAll and not an exact equality any more: AgentsConfig.agentRegistry
    // now falls back to ClasspathDefinitions when no plowshare.data.dir is
    // configured (true of this test), and that source scans
    // classpath*:agents/* across every classpath root -- which in this
    // module's own test JVM is src/main/resources/agents (these 17) AND
    // src/test/resources/agents (the fixtures AgentsConfigTest and friends
    // use). This is exactly the trap AgentsConfig's class javadoc names as
    // "Measured by Task 8" and the reason ClasspathDefinitionsTest already
    // asserts by containsAll rather than by count.
    assertTrue(
        agents
            .names()
            .containsAll(
                List.of(
                    "ask_critic",
                    "ask_proposer",
                    "ask_reviewer",
                    "ask_synthesiser",
                    "chapter_summariser",
                    "close_reader",
                    "code_reviewer",
                    "document_summariser",
                    "image_reader",
                    "interlocutor",
                    "learner",
                    "librarian",
                    "paragraph_summariser",
                    "promotion_judge",
                    "scribe",
                    "section_summariser",
                    "span_summariser")),
        "boot set was " + agents.names());
    // The deliberation's three have no tools either, and there it is the
    // whole design rather than the compression invariant: the critic sees
    // only the document's summary and its top-level summaries, and an agent
    // that could reach the corpus could go and get the passages the
    // asymmetry withholds. Anchor holds that by being careful in one method;
    // here the loader holds it. See AskDefinitionsTest.
    for (String stage : List.of("ask_proposer", "ask_critic", "ask_reviewer", "ask_synthesiser")) {
      assertEquals(List.of(), agents.get(stage).tools(), stage);
    }
    // The five summarisers have no tools, and here that is structural
    // rather than measured: raw text enters the cascade at the paragraph
    // level and only summaries travel upward, so a level that could reach
    // the corpus could read the text it is deliberately not shown.
    for (String level :
        List.of(
            "paragraph_summariser",
            "span_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser")) {
      assertEquals(List.of(), agents.get(level).tools(), level);
    }
    assertEquals(
        List.of(),
        agents.get("scribe").tools(),
        "the scribe has no tools, and that is a measurement rather than a preference");
    // And the learner has none either, for a reason of its own rather than
    // scribe's: the window it reads is computed by the system and handed
    // over, so an agent that could query would be an agent choosing what to
    // look at -- and `learned_at` would then rest on it reporting what it
    // read. See learner.LearningWindow.
    assertEquals(List.of(), agents.get("learner").tools());
    assertEquals(List.of("memory_read"), agents.get("promotion_judge").tools());
    assertEquals(
        List.of(
            "code_map",
            "file_roots",
            "file_glob",
            "file_grep",
            "file_read",
            "file_stat",
            "memory_recall",
            "memory_read",
            "memory_write"),
        agents.get("code_reviewer").tools());
    // result_read and result_list on the interlocutor and on nothing else: a
    // reference exists only across turns of a conversation, and a seam only
    // where turns have been folded away. The other three are ONE-TURN runs
    // -- a delegated child, a curator's ruling, a submission -- so there is
    // no earlier turn to have substituted a reference for and nothing a fold
    // could have taken a reference line away from. This sentence used to say
    // "started on Transcript.NONE ... no conversation", which stopped being
    // true when every run got one; the conclusion is unchanged and the fact
    // under it is not. See InterlocutorDefinitionTest.
    // document_search likewise on the interlocutor and on nothing else, and
    // for a criterion of its own rather than that one. This is the only
    // shipped agent that takes an OPEN QUESTION from a person; the other
    // three take a specific judgement over evidence somebody else assembled
    // -- a diff, a memory and its neighbours, a proposal -- and an uploaded
    // paper is not that evidence for any of them. See
    // InterlocutorDefinitionTest, which asserts the absence agent by agent.
    // search and fetch, likewise on the interlocutor and on nothing else:
    // the two reach the open web rather than this server's corpus or its
    // own tree, and every other shipped agent already has its own reason
    // to stay narrower than that. See interlocutor.md's own frontmatter
    // and InterlocutorDefinitionTest.
    assertEquals(
        List.of(
            "code_map",
            "file_roots",
            "file_glob",
            "file_grep",
            "file_read",
            "file_stat",
            "file_edit",
            "file_delete",
            "file_move",
            "run",
            "todo_read",
            "todo_write",
            "memory_recall",
            "memory_read",
            "memory_write",
            "memory_navigate",
            "result_read",
            "result_list",
            "agent_run",
            "document_search",
            "document_list",
            "search",
            "fetch",
            "memory_index",
            "conversation_list",
            "conversation_search",
            "conversation_chat",
            "conversation_context",
            "document_retrieve",
            "document_rank",
            "document_outline",
            "document_citations",
            "conversation_trajectory",
            "information_read",
            "information_write"),
        agents.get("interlocutor").tools());
    // FOUR callees, and each names something this agent cannot do: judge
    // correctness, see a picture, ask one document, or implement a change
    // by running the project's commands. librarian is the one
    // Documents agent deliberately absent -- it holds document_search and
    // document_list, which this agent holds itself, so the edge would buy
    // turns rather than reach; interlocutor.md's document_search comment
    // carries why it was added and taken back out.
    assertEquals(
        List.of("code_reviewer", "image_reader", "close_reader", "coder"),
        agents.get("interlocutor").calls());
  }

  @Test
  void the_index_lists_what_was_written_and_read_returns_it_in_full() throws Exception {
    String written =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 4 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Four attempts since the timeout change.",
                "formed_by", "claude",
                "project", "payments"));
    String id = idIn(written);

    String index = call("memory_index", args("project", "payments"));
    assertTrue(index.contains(id), index);
    assertTrue(index.contains("The retry budget is 4 attempts"), index);
    // The index is read whole on every survey, so a body here is a body in
    // every prompt. It carries summaries and scopes and nothing else.
    assertFalse(index.contains("Four attempts since the timeout change."), index);

    String read = call("memory_read", args("ids", List.of(id)));
    assertTrue(read.contains("Four attempts since the timeout change."), read);
    assertTrue(read.contains("claude"), read);
    assertTrue(read.contains("active"), read);
  }

  /**
   * A project's own memory comes ahead of a global one, and the global one is untouched by that:
   * shadowing is not supersession. A project that could retire a global memory would take it away
   * from every other project.
   */
  @Test
  void a_project_memory_outranks_a_global_one_and_the_global_one_stays_active() throws Exception {

    // The global memory goes in first, for the same reason the decoy does
    // above: both memories are equally near the question, so a recall that
    // merged the two tiers and sorted by id would put this one first. Only
    // the project-ahead-of-global rule can pass this.
    String global =
        call(
            "memory_write",
            args(
                "summary", "The wire timeout is 10 seconds",
                "scope", "Any service calling any other service",
                "body", "The platform default.",
                "formed_by", "claude"));
    call(
        "memory_write",
        args(
            "summary", "The wire timeout is 30 seconds in payments",
            "scope", "Calling the payments API, or tuning retries",
            "body", "Raised from 10 during the mTLS migration.",
            "formed_by", "claude",
            "project", "payments"));
    String globalId = idIn(global);

    String recalled =
        call(
            "memory_recall",
            args(
                "question", "how long before a call to billing gives up?",
                "project", "payments"));

    assertTrue(recalled.indexOf("30 seconds in payments") >= 0, recalled);
    assertTrue(
        recalled.indexOf("30 seconds in payments") < recalled.indexOf("10 seconds"),
        "the project's own memory should come first:\n" + recalled);

    // And the global memory is not a tombstone: still active, still in the
    // global index, still the answer for every other project.
    String globalIndex = call("memory_index", args());
    assertTrue(globalIndex.contains(globalId), globalIndex);
    String read = call("memory_read", args("ids", List.of(globalId)));
    assertTrue(read.contains("active"), read);
    assertFalse(read.contains("no longer true"), read);
  }

  /**
   * The whole surface a harness would show the model, over the real protocol. Seventeen tools: four
   * over the archive, four over a run, three over the promotion queue, and six over a project —
   * five about what it reaches and one about the project itself.
   */
  @Test
  void tools_list_advertises_the_whole_surface() throws Exception {
    JsonNode response = exchange("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

    List<String> names = new ArrayList<>();
    for (JsonNode tool : response.path("result").path("tools")) {
      names.add(tool.path("name").asText());
      assertFalse(
          tool.path("description").asText().isBlank(),
          "a tool with no description is a tool the model cannot decide about");
      assertEquals("object", tool.path("inputSchema").path("type").asText());
    }
    assertEquals(
        List.of(
            "memory_index",
            "memory_read",
            "memory_recall",
            "memory_write",
            "agent_run",
            "agent_poll",
            "agent_result",
            "agent_cancel",
            "memory_curate",
            "memory_proposals",
            "memory_resolve",
            "project_define",
            "project_workspace_set",
            "project_lend",
            "project_unlend",
            "project_move",
            "project_forget"),
        names);
  }

  /**
   * No agent tool can lend a project a directory.
   *
   * <p><b>The whole point of the {@code lent} column is that it lengthens a leash, so the party
   * that sets it must not be a party the leash binds</b> — which is the rule already keeping {@code
   * project_define} away from agents, and lending is the quieter half of it: {@code
   * project_workspace_set} changes where a project <em>is</em>, and an operator would notice, while
   * a lend leaves every sentence about the project's place saying exactly what it said before.
   *
   * <p><b>Asserted from both ends, because either alone is satisfiable by an accident.</b> The
   * harness surface is where the tool exists at all, and {@code JobRuntime.knownTools()} is what an
   * agent's definition is checked against at boot; a name absent from the second but present in the
   * first is the state {@code AgentRegistry.WITHHELD} exists to explain, and a name absent from
   * both would let this test pass over a feature that was never built. The first assertion is what
   * makes the other two mean something — {@code tools_list_advertises_the_whole_surface} pins the
   * same list, and this pins the reason a person cannot delete the pair from it.
   */
  @Test
  void no_agent_tool_can_lend_a_project_a_directory() throws Exception {
    JsonNode response = exchange("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

    List<String> names = new ArrayList<>();
    for (JsonNode tool : response.path("result").path("tools")) {
      names.add(tool.path("name").asText());
    }
    assertTrue(
        names.contains("project_lend") && names.contains("project_unlend"),
        "the harness holds both lending verbs, or this test is measuring their"
            + " absence rather than their withholding: "
            + names);

    Set<String> forAgents = runtime.knownTools();
    assertFalse(
        forAgents.contains("project_lend"),
        "an agent that could lend its own project a directory could add the directory"
            + " its own definitions sit in, and nothing an operator reads about the"
            + " project would change: "
            + forAgents);
    assertFalse(
        forAgents.contains("project_unlend"),
        "the other direction is withheld beside it, so a definition naming either gets"
            + " AgentRegistry's withheld sentence rather than the unknown-tool one,"
            + " which sends the author somewhere useful: "
            + forAgents);
  }

  // --- a project's workspace, end to end -------------------------------------

  /**
   * A workspace is named, moved and dropped over MCP, and the row moves with it.
   *
   * <p><b>The spec's sentence is that a server whose project list requires a restart is not a
   * server</b>, and this is the only test that says it about the running server rather than about a
   * store: MCP request, HTTP, Spring, Postgres, no restart anywhere. The row is read straight out
   * of the database rather than out of the answer, because the answer is the thing under test.
   *
   * <p>The two directories are real and created here. {@code ProjectStore.define} refuses a path
   * that is not a directory, so a fixture naming one that does not exist would fail for a reason
   * unrelated to the surface.
   */
  @Test
  void a_workspace_is_named_moved_and_dropped_over_mcp_with_no_restart(@TempDir Path tmp)
      throws Exception {
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Path moved = Files.createDirectory(tmp.resolve("moved"));

    String defined =
        call(
            "project_define",
            args(
                "project",
                "payments",
                "workspace",
                repo.toString(),
                "exclusions",
                List.of(repo.resolve("secrets").toString())));

    assertEquals(repo.toString(), workspaceOf("payments"));
    // What nobody may override, reported to the person who set it.
    //
    // ONE path, not two, and that is the rule working rather than a gap:
    // this deployment's configuration file is named relatively, so it
    // resolves inside the server's own working directory, and
    // `ProjectStore.mandatoryExclusions` collapses a path that lies
    // inside another. The list an operator reads therefore states the
    // rule once instead of spelling out its own consequences.
    //
    // Both strings below appear ONLY in the rendered list. `ProjectTools
    // .fence` ends every answer with a fixed sentence, so an assertion on a
    // bare word that sentence also contains is true whatever the list holds
    // -- it passes on the boilerplate rather than on the exclusions, which
    // is trap 1, and it stood here until a review caught it. The module
    // directory's name is spelled out rather than computed from `Path.of("")`
    // so that the fixture does not derive from the expression it is pinning.
    assertTrue(
        defined.contains("plowshare-server"),
        "the answer names the server's own directory, which is what no workspace may"
            + " cover: "
            + defined);
    assertTrue(defined.contains("secrets"), defined);

    String set =
        call("project_workspace_set", args("project", "payments", "workspace", moved.toString()));

    assertEquals(moved.toString(), workspaceOf("payments"));
    assertTrue(
        set.contains("secrets"),
        "moving a workspace keeps the exclusions the project already had: " + set);

    String forgotten = call("project_forget", args("project", "payments"));

    // A NULL workspace and not a missing row, since V14. `forget` drops the
    // leash and keeps the project's identity, because `memories.project_id`
    // and `conversations.project_id` point at that row -- and keeping the id
    // is what makes a project that is forgotten and later re-defined the
    // same project rather than a new one wearing its name.
    assertEquals(
        java.util.Collections.singletonList(null),
        jdbc.queryForList(
            "SELECT workspace FROM projects WHERE name = ?", String.class, "payments"));
    assertTrue(
        forgotten.contains("memories"),
        "dropping a leash is not dropping an archive, and a person has to be told"
            + " which one happened: "
            + forgotten);
  }

  /**
   * <b>A project is moved over MCP and its archive is still its archive.</b>
   *
   * <p>A project's canonical name is {@code <MACHINE>/<PATH>/<PROJ_NAME>}, so moving one to another
   * machine changes its name — and this is the whole claim V14's surrogate key was added for,
   * driven through the real stack rather than argued: MCP request, HTTP, Spring, Postgres, one
   * {@code UPDATE} of one column, and a memory written under the old name answering an index under
   * the new one without having been rewritten.
   *
   * <p><b>The memory's id is compared, not its text.</b> A move that copied rather than moved, or
   * that wrote fresh rows, would produce an index with the right words in it and different ids; the
   * id is the only field that says <em>this row, the one that was already there</em>.
   *
   * <p>The project here is never given a workspace, which is deliberate and is the ordinary shape
   * under presence: {@code project_define} can only name a directory on the server's own disk, so a
   * project whose files are on somebody's laptop has a row with a NULL workspace and an archive. A
   * move has to work on exactly that project.
   */
  @Test
  void a_project_is_moved_over_mcp_and_its_memories_go_with_it() throws Exception {
    String written =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 4 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Four attempts since the timeout change.",
                "formed_by", "claude",
                "project", "ledger"));
    String id = idIn(written);

    String moved =
        call("project_move", args("project", "ledger", "to", "bench.local/srv/ledger/ledger"));

    String index = call("memory_index", args("project", "bench.local/srv/ledger/ledger"));
    assertTrue(
        index.contains(id), "the very row that was written answers under the new name: " + index);
    assertFalse(
        call("memory_index", args("project", "ledger")).contains(id),
        "and nothing is left behind under the old one");
    assertTrue(
        moved.contains("memories"), "and a person is told the archive came with it: " + moved);
    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM projects WHERE name = ?",
                Integer.class,
                "bench.local/srv/ledger/ledger"),
        "one row, moved -- not a second row with the archive split across the two");
  }

  /**
   * The refusal that has to be a sentence rather than a constraint violation: moving onto a name
   * that is taken would put two projects' archives under one id, and {@code
   * projects_name_is_unique} says none of that.
   */
  @Test
  void moving_a_project_onto_a_name_that_is_taken_is_refused_over_mcp() throws Exception {
    call(
        "memory_write",
        args(
            "summary",
            "The retry budget is 4 attempts",
            "scope",
            "Calling the payments API, or tuning retries",
            "body",
            "Four attempts since the timeout change.",
            "formed_by",
            "claude",
            "project",
            "ledger"));
    call(
        "memory_write",
        args(
            "summary",
            "Deploys go out on Thursdays",
            "scope",
            "Release scheduling, and when things ship",
            "body",
            "Thursday afternoons, after the weekly review.",
            "formed_by",
            "claude",
            "project",
            "accounts"));

    JsonNode response =
        exchange(registry, "project_move", args("project", "ledger", "to", "accounts"));

    assertTrue(response.get("result").path("isError").asBoolean(), response.toString());
    assertTrue(
        response
            .get("result")
            .path("content")
            .path(0)
            .path("text")
            .asText()
            .contains("already called accounts"),
        response.toString());
    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM projects WHERE name = ?", Integer.class, "ledger"),
        "and nothing moved");
  }

  /**
   * A project nobody ever defined has no workspace to move, and saying so is what stops a mistyped
   * name reading as a project quietly created.
   *
   * <p><b>The workspace is a real directory, and that is what separates the two refusals this
   * endpoint can make.</b> {@code moveWorkspace} validates the path before it touches the table, so
   * a workspace that did not exist would come back "cannot take workspace … it does not exist" — a
   * sentence that also names the project, and which an assertion on the project name alone cannot
   * tell from the one under test. Asserted positively on "no project named", which only the
   * absent-row branch produces.
   */
  @Test
  void moving_the_workspace_of_a_project_that_has_none_is_an_error_over_mcp(@TempDir Path tmp)
      throws Exception {
    Path real = Files.createDirectory(tmp.resolve("somewhere"));

    JsonNode response =
        exchange(
            registry,
            "project_workspace_set",
            args("project", "nowhere", "workspace", real.toString()));

    assertTrue(response.get("result").path("isError").asBoolean(), response.toString());
    assertTrue(
        response
            .get("result")
            .path("content")
            .path(0)
            .path("text")
            .asText()
            .contains("no project named nowhere"),
        response.toString());
    assertEquals(
        List.of(),
        jdbc.queryForList(
            "SELECT workspace FROM projects WHERE name = ?", String.class, "nowhere"));
  }

  private String workspaceOf(String project) {
    return jdbc.queryForObject(
        "SELECT workspace FROM projects WHERE name = ?", String.class, project);
  }

  // --- the job surface, end to end ------------------------------------------

  /**
   * A run that could not reach the model is never dressed as an answer.
   *
   * <p>The rule the whole slice is built on, seen from outside the server for the first time. This
   * context's pool points at a closed port, so {@code promotion_judge} gets one turn and an {@code
   * LlmException}; what a caller must be able to read is that the run <em>stopped</em>, which way
   * it stopped, and that the text under it is an account rather than a conclusion.
   *
   * <p>Excalibur paid for this: a run that exhausted its turns returned the model's own
   * deliberation as the answer, and a failed tool call came back looking like a considered reply.
   */
  @Test
  void a_run_that_could_not_reach_the_model_is_never_dressed_as_an_answer() throws Exception {
    // code_reviewer and not promotion_judge, and the swap is the `exported`
    // gate arriving: this is the MCP agent_run path, which that key gates,
    // and promotion_judge is not a front door. What the test measures is
    // unchanged -- one turn, an LlmException from a pool pointed at a closed
    // port, and an ending a caller cannot read as an answer.
    String started =
        call(
            "agent_run",
            args(
                "agent", "code_reviewer",
                "task", "review the ledger reader",
                "project", "payments"));
    String job = jobIn(started);

    String result = call("agent_result", finishedJob(job));

    assertTrue(result.contains("stopped without answering"), result);
    assertTrue(result.contains("This is NOT an answer"), result);
    assertTrue(result.contains("UNAVAILABLE"), result);
    // Every line of the run's own text is quoted, so nothing a model wrote
    // can look like a heading this renderer put there.
    assertFalse(result.contains("\nHow it ended: ANSWERED"), result);
  }

  /**
   * An agent nobody deployed is refused with the ones that are, because the caller is a model
   * choosing from a list and the list is the correction.
   *
   * <p><b>The name used to be {@code librarian}, and on 2026-09-04 this server grew one</b> — so
   * the test that pinned the refusal quietly started measuring a successful run instead, and said
   * so by failing on the {@code isError} line rather than on anything about the message. A
   * plausible name is exactly the wrong thing to reach for here: the whole point of the case is a
   * name nothing answers to, and the shipped directory is free to grow any word that sounds like an
   * agent. {@code no_such_agent} cannot be mistaken for one somebody meant to write.
   */
  @Test
  void running_an_agent_this_server_does_not_have_lists_the_ones_it_does() throws Exception {
    JsonNode response =
        exchange(registry, "agent_run", args("agent", "no_such_agent", "task", "go"));

    String text = response.path("result").path("content").path(0).path("text").asText();
    assertTrue(response.path("result").path("isError").asBoolean(), response.toString());
    // The EXPORTED agents, and not every agent this server serves. scribe and
    // promotion_judge are deployed here and deliberately absent from the
    // correction: a message naming what a caller may not run is a listing of
    // the private set with extra steps.
    assertTrue(text.contains("code_reviewer"), text);
    assertTrue(text.contains("interlocutor"), text);
    assertTrue(text.contains("librarian"), text);
    assertFalse(text.contains("scribe"), text);
    assertFalse(text.contains("promotion_judge"), text);
  }

  /**
   * A private agent is refused over MCP, and the refusal is a sentence about exposure rather than
   * about existence.
   *
   * <p>The gate reaching the furthest door. {@code agent_run} here is the client's MCP tool, which
   * starts a run over {@code POST /v1/agents/&#123;name&#125;/runs} — so one check on that endpoint
   * closes the HTTP surface and the foreign-harness surface at once, and this is what measures that
   * rather than assuming it.
   *
   * <p>{@code scribe} is the case worth taking end to end: it has no turn loop at all, so a run
   * started here would put it through {@code JobRuntime}, machinery {@code Scribe} never touches on
   * the write path it actually runs on.
   */
  @Test
  void an_agent_that_is_not_exported_is_refused_over_mcp_and_told_why() throws Exception {
    JsonNode response =
        exchange(registry, "agent_run", args("agent", "scribe", "task", "judge this"));

    String text = response.path("result").path("content").path(0).path("text").asText();
    assertTrue(response.path("result").path("isError").asBoolean(), response.toString());
    assertTrue(text.contains("not exported"), text);
    assertTrue(text.contains("exported: true"), text);
  }

  @Test
  void polling_a_job_this_server_never_started_is_an_error_and_not_a_run_still_going()
      throws Exception {
    JsonNode response = exchange(registry, "agent_poll", args("job_id", "job_999999"));

    String text = response.path("result").path("content").path(0).path("text").asText();
    assertTrue(response.path("result").path("isError").asBoolean(), response.toString());
    assertFalse(text.contains("still running"), text);
  }

  /**
   * A curator pass is a job, over the same four verbs, though no agent file describes it.
   *
   * <p>With the model unreachable the pass ends {@code UNAVAILABLE} after its first ruling — which
   * is the point rather than a limitation: what is being measured here is that a pass can be
   * started, polled and read through the same surface as an agent, and that its ending is as
   * distinguishable as a run's.
   */
  @Test
  void a_curator_pass_runs_as_a_job_and_says_how_it_ended() throws Exception {
    call(
        "memory_write",
        args(
            "summary", "The retry budget is 4 attempts",
            "scope", "Calling the payments API, or tuning retries",
            "body", "Four attempts since the timeout change.",
            "formed_by", "claude",
            "project", "payments"));

    String started = call("memory_curate", args("project", "payments"));
    String result = call("agent_result", finishedJob(jobIn(started)));

    assertTrue(result.contains("stopped without answering"), result);
    assertTrue(result.contains("UNAVAILABLE"), result);
    // The pass's own account, not the judge's: it says what it considered
    // before it stopped, which is what a caller has to know to decide
    // whether anything was promoted.
    assertTrue(result.contains("memory in the 'payments' archive"), result);
  }

  /**
   * A document goes in over the real wire and comes out as an embedded corpus.
   *
   * <p>Everything between is real — HTTP multipart through Tomcat, {@code AuthFilter}, the
   * controller, the job store's virtual thread, Flyway, V18, pgvector — with the one deliberate
   * exception this class already makes, the embedding model.
   *
   * <p><b>Two things are asserted that no narrower test can reach.</b> That the ingest transport is
   * HTTP and not the file channel: the bytes cross a socket as a multipart part, and nothing in
   * {@code plowshare-protocol} is involved, which is the whole of spec decision 4. And that an
   * ingest is a job like any other: it is polled at {@code GET /v1/jobs/&#123;id&#125;}, the
   * endpoint {@code POST /v1/curate}'s jobs are polled at, rather than at a second job endpoint of
   * its own.
   *
   * <p><b>A third thing now, and it is the one this class is uniquely placed to say.</b> An ingest
   * is two halves with different costs and different failure modes -- the text and its vectors,
   * then the summariser cascade -- and this context can reach an embedding endpoint and not a chat
   * one. So what it measures is the seam: the document survives whole, searchable and unlabelled,
   * and the outcome names both halves.
   */
  @Test
  void a_document_uploaded_over_the_real_wire_is_stored_and_embedded() throws Exception {
    String text =
        "The retry budget is four attempts.\n\n" + "It has been four since the timeout change.";
    MultipartBody upload =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "retry-budget.md",
                RequestBody.create(
                    text.getBytes(StandardCharsets.UTF_8), MediaType.get("text/markdown")))
            .build();
    Request request =
        new Request.Builder()
            .url("http://localhost:" + port + "/v1/documents")
            .header("Authorization", "Bearer " + access)
            .post(upload)
            .build();

    String job;
    try (Response accepted = new OkHttpClient().newCall(request).execute()) {
      // Read once and held: an OkHttp body is a one-shot stream, so
      // reading it for the assertion message and again for the parse
      // gives the parse an empty string.
      String body = accepted.body() == null ? "" : accepted.body().string();
      assertEquals(202, accepted.code(), body);
      JsonNode started = json.readTree(body);
      assertEquals("ingest", started.get("agent").asText());
      job = started.get("id").asText();
    }

    JsonNode finished = finishedHttpJob(job);
    String outcome = finished.get("outcome").toString();

    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class));
    assertEquals("retry budget", jdbc.queryForObject("SELECT title FROM documents", String.class));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM paragraphs", Integer.class));
    // The half that would otherwise pass silently: a document stored with
    // no vectors is a document no search can reach, and the row looks
    // perfect either way.
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM chunks WHERE embedding IS NULL", Integer.class));

    // AND THEN THE CASCADE, WHICH THIS CONTEXT CANNOT RUN. The chat endpoint
    // is unreachable here by construction -- the same fact that ends a
    // curator pass UNAVAILABLE two tests up -- so the summariser cascade
    // stops on its first paragraph. That is the interesting assertion rather
    // than a limitation of the fixture: the ingest ends UNAVAILABLE and the
    // DOCUMENT IS STILL THERE, stored, chunked, embedded and searchable,
    // with only its labels missing. A dead model loses what a document
    // claims and never the document.
    assertEquals("UNAVAILABLE", finished.get("outcome").get("ending").asText(), outcome);
    assertTrue(outcome.contains("retained with incomplete processing"), outcome);
    assertTrue(outcome.contains("paragraph_summariser"), outcome);
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM paragraphs WHERE summary IS NULL", Integer.class));
  }

  /** Real authenticated sockets: code intake -> syntax projection -> exact retained source. */
  @Test
  void code_symbols_navigate_exact_source_over_authenticated_websockets() throws Exception {
    var client = new HttpServerClient("http://localhost:" + port, access);
    var personal = Map.<String, Object>of("kind", "personal");
    String source = "// 😀 function fake() {}\nexport function load() { return '😀'; }\n";
    var upload =
        json.valueToTree(
            client.information(
                "upload",
                Map.of(
                    "scope",
                    personal,
                    "corpus",
                    "code",
                    "requestId",
                    java.util.UUID.randomUUID().toString(),
                    "name",
                    "src/load-" + java.util.UUID.randomUUID() + ".ts",
                    "text",
                    source)));
    String revision = upload.path("revision").asText();
    assertFalse(revision.isBlank());
    boolean derived = false;
    for (int poll = 0; poll < 100; poll++) {
      var status =
          json.valueToTree(
              client.information(
                  "status", Map.of("scope", personal, "corpus", "code", "revision", revision)));
      for (var step : status.path("steps"))
        if (step.path("stage").asText().equals("derive")
            && step.path("state").asText().equals("ready")) derived = true;
      if (derived) break;
      Thread.sleep(50);
    }
    assertTrue(derived);
    var outline =
        json.valueToTree(
            client.information(
                "outline", Map.of("scope", personal, "corpus", "code", "revision", revision)));
    assertEquals("ready", outline.path("status").asText());
    assertEquals("retained_revision", outline.path("source_kind").asText());
    assertEquals(1, outline.path("symbol_count").asInt());
    var symbols =
        json.valueToTree(
            client.information(
                "symbols",
                Map.of(
                    "scope", personal, "corpus", "code", "query", "load", "revision", revision)));
    var hit = symbols.path("symbols").get(0);
    assertEquals(revision, hit.path("revision").asText());
    assertEquals("load", hit.path("name").asText());
    int start = hit.path("start_offset").asInt(), end = hit.path("end_offset").asInt();
    var read =
        json.valueToTree(
            client.information(
                "read",
                Map.of(
                    "scope",
                    personal,
                    "corpus",
                    "code",
                    "revision",
                    revision,
                    "offset",
                    start,
                    "limit",
                    end - start)));
    assertEquals("function load() { return '😀'; }", read.path("text").asText());
    assertEquals(source.substring(start, end), read.path("text").asText());
    var evidence =
        json.valueToTree(
            client.information(
                "evidence.record",
                Map.of(
                    "scope",
                    personal,
                    "revision",
                    revision,
                    "requestId",
                    java.util.UUID.randomUUID().toString(),
                    "start",
                    start,
                    "end",
                    end,
                    "quote",
                    read.path("text").asText(),
                    "locator",
                    "extracted-text:utf16")));
    assertFalse(evidence.path("evidence").asText().isBlank());
    assertThrows(
        io.aeyer.plowshare.client.ServerClient.ServerError.class,
        () -> client.information("outline", Map.of("scope", personal, "revision", revision)));
    client.information(
        "withdraw",
        Map.of(
            "scope",
            personal,
            "revision",
            revision,
            "requestId",
            java.util.UUID.randomUUID().toString()));
    assertThrows(
        io.aeyer.plowshare.client.ServerClient.ServerError.class,
        () ->
            client.information(
                "outline", Map.of("scope", personal, "corpus", "code", "revision", revision)));
    assertTrue(
        json.valueToTree(
                client.information(
                    "symbols",
                    Map.of("scope", personal, "corpus", "code", "query", "load", "limit", 1)))
            .path("symbols")
            .isEmpty());
  }

  /**
   * Authenticated WS source/control reads plus the production scripted driver and report service.
   */
  @Test
  void a_scripted_orchestration_reads_a_ws_source_and_retains_a_policy_bound_draft()
      throws Exception {
    var client = new HttpServerClient("http://localhost:" + port, access);
    var personal = Map.<String, Object>of("kind", "personal");
    var upload =
        json.valueToTree(
            client.information(
                "upload",
                Map.of(
                    "scope",
                    personal,
                    "requestId",
                    java.util.UUID.randomUUID().toString(),
                    "name",
                    "script-source-" + java.util.UUID.randomUUID(),
                    "text",
                    "Immutable evidence about Example.")));
    String revision = upload.path("revision").asText();
    boolean extracted = false;
    for (int poll = 0; poll < 100; poll++) {
      var status =
          json.valueToTree(
              client.information("status", Map.of("scope", personal, "revision", revision)));
      for (var step : status.path("steps"))
        if (step.path("stage").asText().equals("extract")
            && step.path("state").asText().equals("ready")) extracted = true;
      if (extracted) break;
      Thread.sleep(50);
    }
    assertTrue(extracted);
    String script =
        """
            // plowshare-script v1
            export const manifest={name:'information_script_fixture',description:'Bounded scripted source/report integration',model:'fast',
              tools:['information_read','information_write'],calls:[],scopes:[],stages:[{id:'research'}],'max-turns':20,'max-model-calls':5};
            export function step(input) {
              const s=input.state || {n:0};const todo=input.todos.find(t=>t.stageId==='research');
              const previous=input.result?JSON.parse(input.result.startsWith('{')?input.result:'{}'):{};
              if(s.n===2)s.window=previous;
              if(s.n===3)s.evidence=previous.evidence;
              if(s.n===4)s.report=previous.revision;
              let command;
              switch(s.n++) {
                case 0:command={tool:'todo_write',arguments:{ops:[{op:'update',id:todo.id,status:'in_progress'}]}};break;
                case 1:command={tool:'information_read',arguments:{operation:'read',revision:'SOURCE_UUID',limit:32768}};break;
                case 2:command={tool:'information_write',arguments:{operation:'evidence',revision:'SOURCE_UUID',start:s.window.start,end:s.window.end,
                  quote:s.window.text,locator:'extracted-text:utf16',requestId:input.requestId}};break;
                case 3:command={tool:'information_write',arguments:{operation:'report',requestId:input.requestId,name:'script-report-'+input.run,
                  text:'Detailed analytical draft [evidence:'+s.evidence+']',inputs:['SOURCE_UUID'],citations:[s.evidence],objectives:['Test retained evidence']}};break;
                case 4:command={tool:'todo_write',arguments:{ops:[{op:'update',id:todo.id,status:'done',summary:'Retained draft '+s.report}]}};break;
                default:command={tool:'orchestration_finish',arguments:{result:s.report}};
              }
              return {state:s,command};
            }
            """
            .replace("SOURCE_UUID", revision);
    var definition =
        io.aeyer.plowshare.server.agents.OrchestrationRegistry.parsePinned(
            "information_script_fixture",
            "fixture.js",
            script,
            runtime.knownTools(),
            io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier.SHIPPED);
    var started =
        orchestrations.start(
            new Orchestrations.Start(
                definition,
                io.aeyer.plowshare.protocol.Home.global(),
                "Test the scripted information path",
                null,
                null,
                "test-user",
                "test-user",
                null,
                null,
                0));
    String ending = null, result = null;
    for (int poll = 0; poll < 100; poll++) {
      var row =
          jdbc.queryForMap(
              "SELECT state,result,failure FROM orchestrations WHERE id=?", started.id());
      ending = (String) row.get("state");
      result = (String) row.get("result");
      if (ending.equals("finished") || ending.equals("failed")) {
        assertEquals("finished", ending, row.toString());
        break;
      }
      Thread.sleep(50);
    }
    assertEquals("finished", ending);
    var retained =
        json.valueToTree(
            client.information("status", Map.of("scope", personal, "revision", result)));
    assertEquals("report", retained.path("kind").asText());
    assertEquals(
        definition.hash(),
        jdbc.queryForObject(
            "SELECT definition_hash FROM information_reports WHERE revision_id=?::uuid",
            String.class,
            result));
    assertEquals(
        started.conductorConversation(),
        jdbc.queryForObject(
            "SELECT produced_by FROM information_reports WHERE revision_id=?::uuid",
            String.class,
            result));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_inputs WHERE derived_revision=?::uuid AND input_revision=?::uuid",
            Integer.class,
            result,
            revision));
    assertEquals(
        6,
        jdbc.queryForObject(
            "SELECT count(*) FROM orchestration_script_steps WHERE conversation_id=? AND completed_at IS NOT NULL",
            Integer.class,
            started.conductorConversation()));
    assertEquals(
        "draft",
        jdbc.queryForObject(
            "SELECT status FROM information_reports WHERE revision_id=?::uuid",
            String.class,
            result));
    client.information(
        "withdraw",
        Map.of(
            "scope",
            personal,
            "revision",
            revision,
            "requestId",
            java.util.UUID.randomUUID().toString()));
    final String reportRevision = result;
    assertThrows(
        io.aeyer.plowshare.client.ServerClient.ServerError.class,
        () -> client.information("read", Map.of("scope", personal, "revision", reportRevision)));
  }

  /** Unsupported source bytes survive for inspection and an explicit converter/retry. */
  @Test
  void an_unsupported_pdf_is_retained_and_its_extraction_failure_is_visible() throws Exception {
    byte[] original = "%PDF-1.7\nbinary".getBytes(StandardCharsets.ISO_8859_1);
    MultipartBody upload =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "paper.pdf", RequestBody.create(original, MediaType.get("application/pdf")))
            .build();
    Request request =
        new Request.Builder()
            .url("http://localhost:" + port + "/v1/documents")
            .header("Authorization", "Bearer " + access)
            .post(upload)
            .build();
    String job;
    try (Response accepted = new OkHttpClient().newCall(request).execute()) {
      assertEquals(202, accepted.code());
      job = json.readTree(accepted.body().string()).get("id").asText();
    }
    JsonNode finished = finishedHttpJob(job);
    assertEquals("UNAVAILABLE", finished.get("outcome").get("ending").asText());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class));
    var revision =
        jdbc.queryForObject(
            "SELECT r.id FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id"
                + " WHERE q.source_name='paper.pdf' AND q.owner_handle='test-user' ORDER BY r.created_at DESC LIMIT 1",
            java.util.UUID.class);
    assertArrayEquals(
        original,
        jdbc.queryForObject(
            "SELECT source_bytes FROM information_revisions WHERE id=?", byte[].class, revision));
    assertEquals(
        "failed",
        jdbc.queryForObject(
            "SELECT state FROM information_steps WHERE revision_id=? AND stage='extract'",
            String.class,
            revision));
    assertTrue(
        jdbc.queryForObject(
                "SELECT error FROM information_steps WHERE revision_id=? AND stage='extract'",
                String.class,
                revision)
            .contains("PDF"));
  }

  /**
   * Poll {@code GET /v1/jobs/&#123;id&#125;} until the job is done.
   *
   * <p>Bounded and short, for {@link #finishedJob}'s reason: an ingest in this context calls a
   * stubbed endpoint in microseconds, so a run still going after ten seconds is a bug and not slow
   * work.
   */
  private JsonNode finishedHttpJob(String job) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      Request poll =
          new Request.Builder()
              .url("http://localhost:" + port + "/v1/jobs/" + job)
              .header("Authorization", "Bearer " + access)
              .build();
      try (Response response = new OkHttpClient().newCall(poll).execute()) {
        assertEquals(200, response.code());
        JsonNode view = json.readTree(response.body() == null ? "" : response.body().string());
        if ("DONE".equals(view.get("state").asText())) {
          return view;
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        job
            + " was still running after ten seconds; an ingest in this"
            + " context embeds against a stub and cannot take that long");
  }

  /**
   * And nothing was filed for a person, because nothing was ruled on. An empty queue that said
   * something else would be the confident-empty-answer shape one layer up.
   */
  @Test
  void an_empty_queue_says_nothing_is_waiting() throws Exception {
    String out = call("memory_proposals", args("project", "payments"));

    assertTrue(out.contains("nothing waiting"), out);
  }

  // --- the failure this project keeps paying for ---------------------------

  /**
   * With the server unreachable, the model must be told so — as a tool result carrying {@code
   * isError}, because a harness swallows JSON-RPC transport errors before the model ever sees them.
   *
   * <p>The assertion that matters most is the negative one. An empty result here would be
   * indistinguishable from "nothing is remembered", and that is a conclusion an agent acts on: it
   * stops asking, and it writes back facts the archive already held.
   */
  @Test
  void an_unreachable_server_reaches_the_model_as_an_error_and_not_as_an_empty_archive()
      throws Exception {

    ToolRegistry dead = new ToolRegistry();
    new MemoryTools(new HttpServerClient("http://localhost:" + closedPort())).registerOn(dead);

    JsonNode response =
        exchange(
            dead,
            "memory_recall",
            args("question", "what is the retry budget?", "project", "payments"));

    JsonNode result = response.get("result");
    assertTrue(result.path("isError").asBoolean(), response.toString());
    String text = result.get("content").get(0).get("text").asText();
    assertTrue(text.toLowerCase(Locale.ROOT).contains("could not reach"), text);
    assertFalse(text.contains("(no memories)"), text);
  }

  /**
   * <b>Finding A, over the whole wire.</b> The archive holds exactly one memory and a recall finds
   * nothing — and must not report that as an empty archive.
   *
   * <p>This is the transcript the finding was written from, reproduced end to end:
   *
   * <pre>
   * write  → "Wrote mem_… into the project 'payments' archive."   (success)
   * index  → "1 memories in the project 'payments' archive:"      (it is there)
   * recall → "(no memories) — nothing in the project 'payments' archive is
   *           close to that question."
   * </pre>
   *
   * <p>Every one of those sentences was true except the last, and the last was the only one the
   * agent would act on. The memory was written while the embedding endpoint was down, so it has no
   * vector; {@code searchByVector} filters {@code embedding IS NOT NULL}, and nothing anywhere on
   * the HTTP surface, the MCP surface or the rendered text said so. An agent told the archive holds
   * nothing close tries another question, forever.
   *
   * <p>What must hold now: the write still succeeds (never lose a write), the index still lists the
   * memory <em>and</em> marks it, the read still returns it in full, and the recall's empty answer
   * says how much of the archive it could not search.
   */
  @Test
  void a_memory_the_archive_cannot_search_is_never_rendered_as_an_empty_archive() throws Exception {

    // Two, and the count is a fact about a write whose endpoint is DOWN
    // rather than about writes in general: the scribe's candidate query
    // fails, so it has no vector to hand on, and the archive asks again for
    // the memory itself. A healthy write costs one — see
    // one_write_costs_one_embedding_call_because_the_scribe_hands_its_vector_on.
    // Failing only the first would leave the memory embedded and searchable,
    // and every assertion below would be about a healthy archive.
    ((MarkerEmbeddings) embeddings).failNext(2);
    String written =
        call(
            "memory_write",
            args(
                "summary", "The retry budget is 4 attempts",
                "scope", "Calling the payments API, or tuning retries",
                "body", "Four attempts since the timeout change.",
                "formed_by", "claude",
                "project", "payments"));
    String id = idIn(written);

    // Never lose a write: it is stored, and it is in the index.
    String index = call("memory_index", args("project", "payments"));
    assertTrue(index.contains(id), index);
    // …and the index says this one cannot be recalled, which is what makes
    // the count below something a reader can act on.
    assertTrue(index.contains("[not searchable]"), index);

    // Still readable in full, verbatim: the write lost the vector, not the
    // memory.
    String read = call("memory_read", args("ids", List.of(id)));
    assertTrue(read.contains("Four attempts since the timeout change."), read);

    String recalled =
        call(
            "memory_recall",
            args(
                "question", "how many times do we try the billing service before giving up?",
                "project", "payments"));

    // The answer really is empty — there is no vector to match — but it no
    // longer claims the archive holds nothing close.
    assertTrue(recalled.contains("Incomplete"), recalled);
    assertTrue(recalled.contains("1 memory"), recalled);
    assertTrue(recalled.contains("memory_index"), recalled);

    // And the repair: once the endpoint is back, one operator call makes the
    // memory findable, and the recall stops being incomplete.
    assertEquals(1, reembed("payments").path("repaired").size());
    String afterRepair =
        call(
            "memory_recall",
            args(
                "question", "how many times do we try the billing service before giving up?",
                "project", "payments"));
    assertTrue(afterRepair.contains("The retry budget is 4 attempts"), afterRepair);
    assertFalse(afterRepair.contains("Incomplete"), afterRepair);
  }

  /**
   * A fully embedded archive says nothing about incompleteness, which is what makes the assertions
   * above mean something. A warning printed on every recall is a warning nobody reads.
   */
  @Test
  void a_recall_over_a_healthy_archive_says_nothing_about_unsearchable_memories() throws Exception {

    call(
        "memory_write",
        args(
            "summary", "The retry budget is 4 attempts",
            "scope", "Calling the payments API, or tuning retries",
            "body", "Four attempts since the timeout change.",
            "formed_by", "claude",
            "project", "payments"));

    String recalled =
        call(
            "memory_recall",
            args(
                "question", "how many times do we try the billing service before giving up?",
                "project", "payments"));

    assertFalse(recalled.contains("Incomplete"), recalled);
    assertFalse(recalled.contains("[not searchable]"), recalled);
    assertFalse(call("memory_index", args("project", "payments")).contains("[not searchable]"));
  }

  /**
   * The operator-facing repair endpoint, over real HTTP. Not an MCP tool: re-embedding is
   * maintenance somebody does after fixing an endpoint, and it costs a model call per memory.
   */
  /**
   * A stale client's {@code verdict} is refused by the real server, and an unknown key it has never
   * heard of is not.
   *
   * <p>Driven with raw JSON over a real socket into a real Spring Boot context, because the two
   * facts it rests on are facts about <em>that</em> and not about a hand-built mapper. Measured
   * here while writing the tripwire: {@code MemoryControllerTest}'s standalone {@code ObjectMapper}
   * had {@code FAIL_ON_UNKNOWN_PROPERTIES} on, where Spring Boot's auto-configured one has it off —
   * so the harness refused unknown keys the server accepts, in exactly the direction that would
   * have hidden the bug the tripwire exists for. It is corrected there; this is the measurement.
   *
   * <p>The pair is the whole rule. Only a key that used to change what happened is refused; forward
   * compatibility is what tolerance is for.
   */
  @Test
  void the_server_refuses_a_stale_clients_verdict_and_tolerates_a_key_it_has_never_heard_of()
      throws Exception {

    try (Response refused =
        postRaw(
            """
                {"project": "payments",
                 "verdict": {"kind": "supersedes", "target": "mem_000001", "reason": "gone"},
                 "proposal": {"summary": "The retry budget is 6",
                              "scope": "calling payments",
                              "body": "Raised after the incident.",
                              "formedBy": "an old client",
                              "formedWhere": "a stale session"}}""")) {

      assertEquals(400, refused.code());
      String said = refused.body() == null ? "" : refused.body().string();
      assertTrue(said.contains("names a verdict"), said);
      assertTrue(said.contains("Nothing was written"), said);
    }

    try (Response tolerated =
        postRaw(
            """
                {"project": "payments", "urgency": "high",
                 "proposal": {"summary": "The retry budget is 6",
                              "scope": "calling payments",
                              "body": "Raised after the incident.",
                              "formedBy": "a newer client",
                              "formedWhere": "a session"}}""")) {

      assertEquals(
          200,
          tolerated.code(),
          "an unknown key a newer client sends must not be refused by an older server;"
              + " this is also the tolerance the verdict tripwire exists because of");
    }

    // The refused write really wrote nothing, and the tolerated one really
    // wrote something. Without this the pair of status codes would be
    // equally satisfied by a server that refused after saving.
    String index = call("memory_index", args("project", "payments"));
    assertEquals(1, index.split("mem_", -1).length - 1, index);
  }

  /**
   * The gate is on, in the context production builds, over the real wire.
   *
   * <p><b>The containment test for the whole of task 7 at this level.</b> Every other test in this
   * class now presents a credential, which means all of them would stay green if the filter were
   * deleted tomorrow — they would simply be sending a header nothing reads. This is the one that
   * fails for that, and it is deliberately in this file rather than only in {@code AuthFilterTest}:
   * that one builds a context by hand, and this one is the whole application, wired by
   * {@code @SpringBootTest} from {@link PlowshareServerApplication}. A filter that ran in a
   * hand-built context and was never registered in the real one would pass there and fail here.
   *
   * <p>The body is asserted whole, because "says nothing further" is the claim: a 401 that
   * distinguished an expired token from an unknown one would tell a caller whether a guess had ever
   * been real.
   */
  @Test
  void an_unauthenticated_call_reaches_nothing_over_the_real_wire() throws IOException {
    Request anonymous =
        new Request.Builder()
            .url("http://localhost:" + port + "/v1/memories/index?project=payments")
            .build();

    try (Response refused = new OkHttpClient().newCall(anonymous).execute()) {
      assertEquals(
          401,
          refused.code(),
          "this server answered an anonymous caller. Every memory, every job and"
              + " every conversation in the archive is behind that status code.");
      assertEquals(
          "{\"error\":\"unauthenticated\"}", refused.body() == null ? "" : refused.body().string());
    }
  }

  /**
   * Spec 2026-09-30-local-hooks-are-served: in the server as it boots, the local layer is real
   * script hooks and the pin is the real snapshot, not the {@code NONE} a slice without a file
   * channel or an archive gets. Either one quietly {@code NONE} here would leave every log with no
   * local hooks and no test failing.
   */
  @Test
  void local_hooks_are_live_in_the_server_as_it_boots() {
    assertInstanceOf(ScriptHooks.class, context.getBean("localHooks"));
    assertInstanceOf(PinnedLocalHooks.class, context.getBean(LocalHooks.class));
  }

  private Response postRaw(String body) throws IOException {
    return new OkHttpClient()
        .newCall(
            new Request.Builder()
                .url("http://localhost:" + port + "/v1/memories")
                .header("Authorization", "Bearer " + access)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build())
        .execute();
  }

  private JsonNode reembed(String project) throws Exception {
    Request request =
        new Request.Builder()
            .url("http://localhost:" + port + "/v1/memories/reembed?project=" + project)
            .header("Authorization", "Bearer " + access)
            .post(RequestBody.create(new byte[0], null))
            .build();
    try (Response response = new OkHttpClient().newCall(request).execute()) {
      assertEquals(200, response.code(), "reembed should have answered 200");
      return json.readTree(response.body() == null ? "" : response.body().string());
    }
  }

  /**
   * A port nothing is listening on: bound to claim it, then released. Called once and held in
   * {@link #CLOSED_PORT}, because two calls return two different ports and the assertion that the
   * pool points here has to be able to name the one it got.
   */
  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException("could not find a port to leave closed", e);
    }
  }

  // --- plumbing -------------------------------------------------------------

  /**
   * Sends one {@code tools/call} over the real stdio transport and returns the text content — so
   * every assertion above exercises the client, the protocol, HTTP and the server.
   *
   * <p>Fails on {@code isError} rather than returning its text: a broken call whose message
   * happened to contain the string a test looks for would otherwise pass as a success.
   */
  private String call(String tool, Map<String, Object> arguments) throws Exception {
    JsonNode response = exchange(registry, tool, arguments);
    JsonNode result = response.get("result");
    String text = result.path("content").path(0).path("text").asText();
    assertFalse(result.path("isError").asBoolean(), tool + " failed: " + text);
    return text;
  }

  private JsonNode exchange(ToolRegistry tools, String tool, Map<String, Object> arguments)
      throws Exception {
    ObjectNode request = json.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", 1);
    request.put("method", "tools/call");
    ObjectNode params = request.putObject("params");
    params.put("name", tool);
    params.set("arguments", json.valueToTree(arguments));
    return exchange(tools, json.writeValueAsString(request));
  }

  private JsonNode exchange(String request) throws Exception {
    return exchange(registry, request);
  }

  private JsonNode exchange(ToolRegistry tools, String request) throws Exception {
    var out = new ByteArrayOutputStream();
    new StdioTransport("end-to-end")
        .serve(
            new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)),
            out,
            tools);
    return json.readTree(out.toString(StandardCharsets.UTF_8));
  }

  /** The id out of a memory_write result, so a later call can name it. */
  /**
   * Poll a job until it finishes, or fail rather than hang.
   *
   * <p>Bounded and short: everything this context can run fails at a closed port in milliseconds,
   * so a run still going after ten seconds is a bug and not a slow model. A test that waited
   * forever would be a test that hangs a CI job over a regression instead of reporting it.
   */
  private Map<String, Object> finishedJob(String job) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (call("agent_poll", args("job_id", job)).contains("has finished")) {
        return args("job_id", job);
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        job
            + " was still running after ten seconds; nothing in this"
            + " context can take that long, because the pool points at a closed port");
  }

  private static String jobIn(String started) {
    int at = started.indexOf("job_");
    assertTrue(at >= 0, "no job id in: " + started);
    int end = at;
    while (end < started.length()
        && !Character.isWhitespace(started.charAt(end))
        && started.charAt(end) != ','
        && started.charAt(end) != '.') {
      end++;
    }
    return started.substring(at, end);
  }

  private static String idIn(String writeResult) {
    int start = writeResult.indexOf("mem_");
    assertTrue(start >= 0, "no memory id in: " + writeResult);
    int end = start;
    while (end < writeResult.length()
        && !Character.isWhitespace(writeResult.charAt(end))
        && writeResult.charAt(end) != '.') {
      end++;
    }
    return writeResult.substring(start, end);
  }

  /** Tool arguments, in the order a caller would send them. */
  private static Map<String, Object> args(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  /**
   * The stand-in for the embedding model: text containing a marker word gets that marker's basis
   * vector, and everything else gets the all-ones vector, which sits at the same distance from
   * every basis vector so ordering cannot rescue a rule that failed.
   *
   * <p>Deliberately crude. The point of the end-to-end test is that the vector a question turns
   * into reaches the query and the right row comes back; a cleverer stub would make it harder to
   * say which of those two things a red run meant.
   */
  private static final class MarkerEmbeddings implements EmbeddingClient {

    /**
     * Matches the schema's {@code vector(768)}. Postgres rejects any other width, with an error
     * that names the column rather than the test.
     */
    private static final int DIM = 768;

    /**
     * Insertion-ordered: a text carrying two markers takes the first, and which one that is has to
     * be readable from the source.
     */
    private static final Map<String, Integer> AXES = new LinkedHashMap<>();

    static {
      // Everything about retries, timeouts and billing points one way…
      AXES.put("retry", 1);
      AXES.put("timeout", 1);
      AXES.put("billing", 1);
      // …and the decoy points somewhere else.
      AXES.put("deploy", 2);
      AXES.put("thursday", 2);
    }

    /**
     * Makes exactly the next call throw, so the write path's behaviour under a dead endpoint is
     * reachable without killing one. The state it leaves — a stored, active, indexed memory with a
     * NULL embedding — is the whole subject of the finding-A test above.
     */
    private int failNext;

    private int calls;

    /**
     * Fail the next {@code calls} embeddings.
     *
     * <p>A count and not a flag, and the reason is a fact about the write path rather than about
     * this stub. <b>A write whose embedding endpoint is down costs two calls, and a write whose
     * endpoint answers costs one.</b> {@code Scribe} embeds its candidate question with the same
     * text the memory will be embedded for and hands the vector on, so a healthy write pays once —
     * but when the first call throws there is no vector to hand on, the scribe files flat without
     * one, and the archive asks again. With a single-shot flag the scribe's query consumed it and
     * the memory was written with a perfectly good vector, which is a green test asserting the
     * opposite of what it says.
     */
    void failNext(int calls) {
      this.failNext = calls;
    }

    /**
     * How many times the endpoint has been asked, counting the calls that threw. Zero and one are
     * both assertions a test makes: the write path now takes a vector the scribe already paid for.
     */
    int calls() {
      return calls;
    }

    /**
     * Clears the flag and the counter between tests. The bean is a context singleton, so a test
     * that set it and then threw before consuming it would otherwise fail an unrelated test's first
     * write, and a counter that carried over would make every count the sum of the suite.
     */
    void working() {
      this.failNext = 0;
      this.calls = 0;
    }

    @Override
    public float[] embed(String text) {
      calls++;
      if (failNext > 0) {
        failNext--;
        throw new EmbeddingException("stub: the embedding endpoint is down");
      }
      String haystack = text.toLowerCase(Locale.ROOT);
      for (Map.Entry<String, Integer> axis : AXES.entrySet()) {
        if (haystack.contains(axis.getKey())) {
          float[] vector = new float[DIM];
          vector[axis.getValue()] = 1.0f;
          return vector;
        }
      }
      float[] ones = new float[DIM];
      java.util.Arrays.fill(ones, 1.0f);
      return ones;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      List<float[]> vectors = new ArrayList<>(texts.size());
      for (String text : texts) {
        vectors.add(embed(text));
      }
      return List.copyOf(vectors);
    }
  }
}
