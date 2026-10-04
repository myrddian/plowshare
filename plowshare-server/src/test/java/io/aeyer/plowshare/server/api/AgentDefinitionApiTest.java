package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.DefineAgentRequest;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.AgentsProperties;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionAlreadyExistsException;
import io.aeyer.plowshare.server.agents.DefinitionChecks;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.DefinitionWriter;
import io.aeyer.plowshare.server.agents.Definitions;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Limits;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.auth.AuthProperties;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.images.ImageStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code POST /v1/agents}, the one door {@code DefinitionWriter} is reached through in this
 * repository.
 *
 * <p>{@code AgentControllerTest}'s own shape: a real {@link AgentRegistry} and a real {@link
 * DefinitionResolver} over a real {@link DataLayout} under a fresh {@code @TempDir}, standing in
 * only for {@link JobStore}, {@link Curator}, {@link ProjectStore} and {@link Turn}, none of which
 * this door touches. The boot set is deliberately empty rather than the real shipped seed — {@code
 * DefinitionWriterTest} uses the shipped seed because its delegation tests need a real inherited
 * agent to call; nothing here does, and an empty boot set means these tests cannot be broken by an
 * unrelated change to what this repository ships.
 *
 * <p><b>{@link #writer} and {@link #resolver} are built over the identical {@code bootSet}, {@code
 * TOOLS} and {@code required}</b>, on {@code AgentsConfig.definitionWriter}'s own precedent: a
 * fixture in which the two disagreed would validate against a rule production never lets them
 * disagree on, and would therefore prove nothing about the endpoint under test.
 *
 * <p><b>{@code PROJECT_NAME}/{@code PROJECT_ID} are two views of one project, not two projects.</b>
 * {@code POST /v1/agents} takes a name — see {@code DefineAgentRequest}'s own javadoc for why round
 * 1's numeric {@code projectId} was wrong — and {@code AgentController} translates it through
 * {@link ProjectStore#id}, stubbed here exactly as production resolves it. Everything this file
 * writes or reads on disk still addresses the project by its surrogate id, because {@link
 * DataLayout} and {@link DefinitionWriter} both take that and never a name.
 */
class AgentDefinitionApiTest {

  private static final Long PROJECT_ID = 7L;
  private static final String PROJECT_NAME = "payments";

  /**
   * {@code memory_recall} only, so a fixture may declare it without a second tool needing to exist
   * — used by the invalidate-under-a-spoofed- stamp test below, which needs a frontmatter change
   * {@link AgentView} actually surfaces.
   */
  private static final Set<String> TOOLS = Set.of("memory_recall");

  private DataLayout layout;
  private ProjectStore projects;
  private DefinitionResolver resolver;
  private DefinitionWriter writer;
  private MockMvc mvc;

  @BeforeEach
  void setUp(@TempDir Path data) {
    layout = new DataLayout(data).initialise();
    projects = mock(ProjectStore.class);
    // Mockito's own default answer for an unstubbed Long-returning method
    // is boxed ZERO, not null -- ReturnsEmptyValues treats a wrapper
    // numeric type like its primitive. The real ProjectStore.id answers
    // null for a name with no row (an empty JDBC result), which is what
    // the unknown-project test below needs, so that has to be stubbed
    // explicitly rather than trusted as the mock's default; the specific
    // stub for PROJECT_NAME below overrides it for that one name.
    when(projects.id(anyString())).thenReturn(null);
    when(projects.id(PROJECT_NAME)).thenReturn(PROJECT_ID);

    AgentRegistry bootSet = new AgentRegistry(Map.of());
    resolver =
        new DefinitionResolver(
            bootSet,
            layout,
            id -> true,
            TOOLS,
            Set.of(),
            mock(SessionChannel.class),
            session -> true,
            DefinitionChecks.NONE);
    writer = new DefinitionWriter(bootSet, layout, TOOLS, Set.of(), DefinitionChecks.NONE);

    mvc = mvc();
  }

  private MockMvc mvc() {
    return mvcWriting(writer);
  }

  @Test
  void a_new_definition_is_created_and_answers_201() throws Exception {
    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(PROJECT_NAME, "helper", definition("helper"), false)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.agent.name").value("helper"))
        .andExpect(jsonPath("$.agent.served").value(true))
        .andExpect(jsonPath("$.restartRequired").value(false));

    assertTrue(
        Files.exists(layout.botsFor(PROJECT_ID).resolve("helper.md")),
        "a created write must actually land on disk");
  }

  @Test
  void replacing_an_existing_definition_with_overwrite_answers_200() throws Exception {
    write(layout.botsFor(PROJECT_ID), "helper", "old body");

    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(PROJECT_NAME, "helper", definition("helper"), true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent.name").value("helper"))
        .andExpect(jsonPath("$.restartRequired").value(false));

    assertTrue(
        Files.readString(layout.botsFor(PROJECT_ID).resolve("helper.md")).contains("hi"),
        "the replacement text never landed");
  }

  /**
   * Without {@code overwrite}, a name already on disk is a 409 naming the definition, and the file
   * already there is untouched — the caller has to say {@code overwrite} before this surface will
   * destroy what was there, exactly as {@code DefinitionAlreadyExistsException}'s own javadoc
   * argues.
   */
  @Test
  void replacing_an_existing_definition_without_overwrite_is_409() throws Exception {
    write(layout.botsFor(PROJECT_ID), "helper", "old body");

    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(PROJECT_NAME, "helper", definition("helper"), false)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("helper")));

    assertTrue(
        Files.readString(layout.botsFor(PROJECT_ID).resolve("helper.md")).contains("old body"),
        "a refused overwrite must not have replaced the file");
  }

  /**
   * {@link DefinitionAlreadyExistsException} is the one refusal {@code DefinitionWriter#write}
   * raises that is not a {@code faults.CallerFault}, and {@code Faults} is now what gives each its
   * status — {@code AgentController.define} catches neither. A 400 here rather than 409 would mean
   * the two rows had stopped being distinguished, wherever that happened.
   */
  @Test
  void a_definition_that_would_not_load_is_a_400_naming_the_fault_and_nothing_is_written()
      throws Exception {
    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(PROJECT_NAME, "helper", "not frontmatter at all", false)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("helper")));

    assertFalse(
        Files.exists(layout.botsFor(PROJECT_ID).resolve("helper.md")),
        "a refused write left a file behind");
  }

  /**
   * The gate is a servlet {@link AuthFilter} in front of the dispatcher and not a check this
   * controller makes, so it has to be measured with the filter actually in the chain — {@code
   * RuntimeConfigControllerTest}'s own shape for the same reason. No credential is presented at
   * all, which is refused before the filter chain reaches this controller.
   */
  @Test
  void an_unauthenticated_post_is_refused_like_every_other_v1_path() throws Exception {
    TokenStore tokens =
        new TokenStore(
            Clock.systemUTC(), Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(10));
    MockMvc unauthenticatedMvc =
        MockMvcBuilders.standaloneSetup(controllerOver(writer))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new AuthFilter(tokens, new AuthProperties()))
            .build();

    unauthenticatedMvc
        .perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(PROJECT_NAME, "helper", definition("helper"), false)))
        .andExpect(status().isUnauthorized());

    assertFalse(
        Files.exists(layout.botsFor(PROJECT_ID).resolve("helper.md")),
        "an unauthenticated request must never reach the writer");
  }

  /**
   * Finding 1: a project name with no row must not be silently written into the global tier. {@code
   * ProjectStore.id} answers {@code null} for {@code "ghost-project"} exactly as it would for the
   * genuine "write to global" case ({@code project} absent or blank) — the only thing that tells
   * the two apart is this refusal running before {@link DefinitionWriter#write} is ever called.
   * Before the fix, this request landed a file at {@code global/bots/helper.md} and answered {@code
   * 201} with a message blaming "the global tier is read once at boot", which is the wrong
   * diagnosis for a caller who simply mistyped a project name.
   */
  @Test
  void a_write_naming_an_unknown_project_is_refused_and_nothing_is_written() throws Exception {
    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("ghost-project", "helper", definition("helper"), false)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("ghost-project")));

    assertFalse(Files.exists(layout.botsFor(PROJECT_ID).resolve("helper.md")));
    assertFalse(
        Files.exists(layout.botsFor(null).resolve("helper.md")),
        "an unknown project name must not silently fall back to the global tier");
  }

  /**
   * Finding 2: {@code text} is bounded. Nothing in {@code application.yml}'s multipart limits or
   * Tomcat's {@code maxPostSize} covers a JSON body, so without this check any bearer-token holder
   * could write an arbitrarily large file into the data directory.
   *
   * <p><b>The sentence is pinned here and not only the number.</b> It read "See {@code
   * AgentController.MAX_DEFINITION_BYTES}" while the ceiling lived there, went on reading it
   * through the move that took the ceiling down to {@link DefinitionWriter} — the plan forbade
   * changing a refusal by a character — and names the constant's real home now. Nothing asserted
   * over that half of the message before, which is how a caller could be pointed at a class that no
   * longer held the number for as long as it was.
   */
  @Test
  void text_larger_than_the_ceiling_is_refused_and_nothing_is_written() throws Exception {
    String huge = "a".repeat(DefinitionWriter.MAX_DEFINITION_BYTES + 1);

    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(PROJECT_NAME, "helper", huge, false)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value(containsString(String.valueOf(DefinitionWriter.MAX_DEFINITION_BYTES))))
        .andExpect(
            jsonPath("$.detail")
                .value(containsString("See DefinitionWriter.MAX_DEFINITION_BYTES for why")));

    assertFalse(Files.exists(layout.botsFor(PROJECT_ID).resolve("helper.md")));
  }

  /**
   * A null {@code name} used to reach {@code Objects.requireNonNull} inside the writer and surface
   * as an opaque 500; it is a 400 naming the field now, caught before the writer is ever called.
   */
  @Test
  void a_body_naming_no_name_is_a_400() throws Exception {
    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"project\":\"" + PROJECT_NAME + "\",\"text\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("name")));
  }

  /** {@link #a_body_naming_no_name_is_a_400()}'s twin for {@code text}. */
  @Test
  void a_body_naming_no_text_is_a_400_and_nothing_is_written() throws Exception {
    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"project\":\"" + PROJECT_NAME + "\",\"name\":\"helper\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("text")));

    assertFalse(Files.exists(layout.botsFor(PROJECT_ID).resolve("helper.md")));
  }

  /**
   * Ruling A and Finding 3: a global write is accepted — {@code 201}, the bytes really do land on
   * disk — and is machine-readably flagged as not yet served, rather than left to a prose sentence
   * a client would have to string-match. Needs no restart to prove: an empty boot set that
   * genuinely does not know the name is exactly what "not served" means here, with no process
   * restart required to demonstrate it.
   */
  @Test
  void a_global_write_is_created_but_flagged_as_needing_a_restart() throws Exception {
    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(null, "helper", definition("helper"), false)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.restartRequired").value(true))
        .andExpect(jsonPath("$.agent.name").value("helper"))
        .andExpect(jsonPath("$.agent.served").value(false));

    assertTrue(
        Files.exists(layout.botsFor(null).resolve("helper.md")),
        "a global write must still land on disk even though this process cannot serve" + " it yet");
    mvc.perform(get("/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].name").value(not(hasItem("helper"))));
  }

  /**
   * The whole point of this endpoint, measured rather than assumed: a definition posted through it
   * is nameable by {@code GET /v1/agents} with no restart of this process in between.
   */
  @Test
  void a_posted_definition_becomes_resolvable_without_a_restart() throws Exception {
    mvc.perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(PROJECT_NAME, "helper", definition("helper"), false)))
        .andExpect(status().isCreated());

    mvc.perform(get("/v1/agents").param("project", PROJECT_NAME))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].name").value(hasItem("helper")));
  }

  /**
   * The exact gap {@code DefinitionResolver.invalidate}'s own javadoc names, made deterministic
   * rather than left to whatever the filesystem's clock happens to do: a replacement of the <b>same
   * byte length</b>, with its modification time forced back to <em>the value the cached stamp was
   * taken at</em>, so the stamp {@link DefinitionResolver} compares before serving a cached entry
   * is bit-for-bit the one that entry already holds.
   *
   * <p>Without {@code AgentController.define} calling {@code invalidate}, {@code byProject} would
   * still hold the pre-write entry under a stamp that — because this test forces it to — has not
   * moved, and {@code forCaller} would serve the stale registry straight out of the map without
   * ever reading the file again. This is why round 1's version of this test proved nothing: a bare
   * create against an empty cache always rebuilds, stamp or no stamp, so it could not have told an
   * {@code invalidate} call apart from one that was never made.
   *
   * <p><b>Round 2's version proved nothing either, and for a subtler reason.</b> It captured the
   * modification time <em>after</em> the POST and set the file to that same value, which is a
   * no-op: the two writes land at genuinely different wall-clock moments, so the natural difference
   * between them had already moved the stamp and would have invalidated the cache on its own.
   * Measured, rather than reasoned about: with {@code resolver.invalidate(projectId)} commented out
   * of {@code AgentController.define}, that version still passed. The time that has to be restored
   * is the one the cache last saw — the file's own, read <em>before</em> the POST.
   *
   * <p><b>And restoring it after the POST returns is still too late, which is why the writer is
   * wrapped.</b> {@code define} resolves the project again <em>inside</em> the request to build its
   * response body, so by the time a test regains control the cache has already been repopulated
   * from a file whose modification time the write had just moved — with or without {@code
   * invalidate}, and equally either way. The collision has to exist at the moment {@code define}
   * re-resolves, and the only seam between the write landing and that re-resolution is the writer
   * itself. {@link #sameTickWriter} is therefore the real {@link DefinitionWriter}, delegated to
   * for every byte it writes and every refusal it makes, with one thing added on the way out: the
   * replacement is stamped with the modification time the cached entry was read at. That is not a
   * convenience — it <em>is</em> the case the stamp's own javadoc says it cannot see, which no test
   * can wait for on a filesystem whose timestamps are finer than the gap between two writes.
   */
  @Test
  void a_same_length_replacement_is_resolvable_even_under_a_spoofed_unchanged_stamp()
      throws Exception {
    String oldFrontmatter =
        "---\nname: helper\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\nexported: true\n---\n";
    String newFrontmatter =
        "---\nname: helper\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\nexported: true\ntools: [memory_recall]\n---\n";
    int diff =
        newFrontmatter.getBytes(StandardCharsets.UTF_8).length
            - oldFrontmatter.getBytes(StandardCharsets.UTF_8).length;
    assertTrue(diff > 0, "fixture assumption: adding a tools: line lengthens the file");
    String oldText = oldFrontmatter + "hi" + " ".repeat(diff) + "\n";
    String newText = newFrontmatter + "hi\n";
    assertEquals(
        oldText.getBytes(StandardCharsets.UTF_8).length,
        newText.getBytes(StandardCharsets.UTF_8).length,
        "fixture bug: old and new must be the exact same byte length, or the stamp"
            + " would move on size alone and this test would prove nothing");

    Path file = layout.botsFor(PROJECT_ID).resolve("helper.md");
    writeRaw(file, oldText);
    // Populate the resolver's cache with the OLD content, exactly as an
    // earlier GET /v1/agents or run against this project already would
    // have.
    AgentRegistry before = resolver.forCaller(new DefinitionResolver.Caller(PROJECT_ID, null));
    assertFalse(before.get("helper").tools().contains("memory_recall"));
    // THE VALUE THE CACHED STAMP HOLDS, read before anything overwrites
    // the file. This is the whole instrument: the entry cached by the
    // lookup above was stamped `<size>@<this>`, so a replacement carrying
    // this same millisecond is one that stamp cannot tell from the file it
    // was taken from.
    FileTime asTheCacheSawIt = Files.getLastModifiedTime(file);
    MockMvc sameTickMvc = mvcWriting(sameTickWriter(asTheCacheSawIt));

    sameTickMvc
        .perform(
            post("/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(PROJECT_NAME, "helper", newText, true)))
        .andExpect(status().isOk())
        // The body is resolved after the invalidation, so it is the
        // first place a cache that was never cleared would show: this
        // is the OLD definition, tool-less, if `invalidate` did not run.
        .andExpect(jsonPath("$.agent.tools").value(hasItem("memory_recall")));

    // THE COLLISION IS REAL AND STILL STANDING, asserted rather than
    // assumed: same name, same byte count, same modification time as the
    // cached entry was stamped with. If either of these fails the stamp
    // moved on its own and nothing below is a proof about `invalidate`.
    assertEquals(
        asTheCacheSawIt,
        Files.getLastModifiedTime(file),
        "this host would not take the modification time back, so the stamp has moved on"
            + " time and this test cannot prove what it exists to prove");
    assertEquals(
        oldText.getBytes(StandardCharsets.UTF_8).length,
        Files.size(file),
        "the write changed the file's byte count, so the stamp would move on size alone"
            + " and this test would prove nothing");
    assertTrue(
        Files.readString(file).contains("memory_recall"),
        "the fixture is wrong: the NEW text never reached the disk at all");

    AgentRegistry after = resolver.forCaller(new DefinitionResolver.Caller(PROJECT_ID, null));
    assertTrue(
        after.get("helper").tools().contains("memory_recall"),
        "the cached OLD registry was served even though the file on disk holds the NEW"
            + " content under a stamp identical to the cached one --"
            + " AgentController.define's call to DefinitionResolver.invalidate did"
            + " not actually clear the entry");
  }

  // --- fixtures ------------------------------------------------------------

  /**
   * The real {@link DefinitionWriter} of this fixture, wrapped so that what it writes lands
   * carrying {@code tick} as its modification time.
   *
   * <p>Everything the writer decides is still the writer's: validation, refusals, the
   * temp-file-and-atomic-move, the bytes, the {@code Written} it answers with. The one thing added
   * is the timestamp, because the case under test — see the calling test's own javadoc — is a
   * replacement that lands inside the timestamp tick the previous read was stamped at, and a test
   * cannot make two writes share a tick on a filesystem that resolves finer than the gap between
   * them.
   *
   * <p>Mocked rather than subclassed because {@link DefinitionWriter} is {@code final}; the
   * delegation is what keeps this a wrapper rather than a stand-in for the thing under test.
   */
  private DefinitionWriter sameTickWriter(FileTime tick) {
    DefinitionWriter sameTick = mock(DefinitionWriter.class);
    when(sameTick.write(any(), anyString(), anyString(), anyBoolean()))
        .thenAnswer(
            call -> {
              DefinitionWriter.Written written =
                  writer.write(
                      call.getArgument(0),
                      call.getArgument(1),
                      call.getArgument(2),
                      call.getArgument(3));
              Files.setLastModifiedTime(written.file(), tick);
              return written;
            });
    return sameTick;
  }

  /**
   * {@link #mvc()} with the writer named, for the one test that needs the write to land in a tick
   * of its choosing.
   */
  private MockMvc mvcWriting(DefinitionWriter writing) {
    ObjectMapper json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    return MockMvcBuilders.standaloneSetup(controllerOver(writing))
        .setControllerAdvice(new ApiExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
        .build();
  }

  /**
   * The controller over the six services, wired as the container wires them -- one {@link Callers},
   * shared by {@link Runs} and {@link Definitions}, which is what the scanned singletons are. Only
   * {@code writer} varies between this file's two harnesses, so it is the only parameter.
   */
  private AgentController controllerOver(DefinitionWriter writing) {
    JobStore jobs = mock(JobStore.class);
    Turn turns = mock(Turn.class);
    Callers callers =
        new Callers(
            resolver,
            projects,
            turns,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
    return new AgentController(
        jobs,
        resolver,
        projects,
        callers,
        new Runs(callers, jobs, turns),
        new Pictures(ImageStore.NONE),
        new Passes(jobs, mock(Curator.class), new AgentsProperties()),
        new Limits(jobs),
        new Definitions(writing, resolver, projects, callers));
  }

  /**
   * The wire body {@code POST /v1/agents} reads, built through the real record and a plain {@link
   * ObjectMapper} rather than a hand-escaped JSON literal -- {@code text} carries its own newlines
   * and quotes, and serialising it is what {@link DefineAgentRequest} is for.
   */
  private static String requestBody(String project, String name, String text, boolean overwrite)
      throws Exception {
    return new ObjectMapper()
        .writeValueAsString(new DefineAgentRequest(project, name, text, overwrite));
  }

  /**
   * A minimal, valid definition file -- {@code DefinitionWriterTest}'s own shape: {@code
   * max-turns}/{@code max-model-calls} are required frontmatter with no default.
   */
  private static String definition(String name) {
    return "---\nname: "
        + name
        + "\ndescription: d\nmodel: m\nmax-turns: 2\n"
        + "max-model-calls: 4\nexported: true\n---\nhi\n";
  }

  /**
   * A definition written straight to disk, for the tests that need one already there before the
   * request under test is sent.
   */
  private static void write(Path dir, String name, String body) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(name + ".md"),
        "---\nname: "
            + name
            + "\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\nexported: true\n---\n"
            + body
            + "\n");
  }

  /**
   * {@link #write}'s twin for a test that needs exact control over the whole file, frontmatter
   * included -- the same-byte-length fixture above cannot go through {@link #write}'s own generated
   * frontmatter.
   */
  private static void writeRaw(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }
}
