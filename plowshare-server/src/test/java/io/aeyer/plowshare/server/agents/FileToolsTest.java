package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Replacement;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The file tools, driven the way the runtime drives them: a JSON string in, and prose out or an
 * exception that ends the run.
 *
 * <h2>Why a real provider and a real database are in here</h2>
 *
 * <p>Half this file is about <b>which sentence a model is handed</b>, and every sentence that
 * matters is {@link LocalProvider}'s rather than these tools'. A suite of fakes would pin the
 * rendering and assert nothing about whether "this job runs in the global tier" or "no workspace is
 * defined for project X" ever arrives — trap 2, a helper blind in the dimension the argument is
 * about, and the reason {@code LocalProviderTest} keeps a database too. Fakes appear only where the
 * rule is about <em>two</em> providers, which no single local one can be put into.
 *
 * <h2>What is measured here rather than assumed — an index, not a second copy</h2>
 *
 * <ul>
 *   <li>a {@code NUL} in a path is an {@code InvalidPathException} from {@code Path.of}, which is
 *       unchecked and never reaches the seam &mdash; {@code
 *       a_path_the_platform_cannot_even_parse_is_a_tool_result}, argued in {@code FileTools.path};
 *   <li>{@code ToolArguments.requireText} strips and refuses blank, which is right for a path and
 *       wrong for a file's contents &mdash; {@code content_is_written_exactly_as_it_was_sent} and
 *       {@code an_empty_file_is_an_ordinary_thing_to_write}, argued on {@code
 *       ToolArguments.requireExactText}.
 * </ul>
 *
 * <p><b>The fixture for a file with nothing in it is not called {@code empty.txt}</b>, and it was
 * until a mutant said otherwise. Both {@code file_read} and {@code file_stat} answer such a file
 * with a sentence containing the word "empty", both tests assert on that word, and the sentence
 * also carries the path — so the assertion passed on the filename alone, and deleting either branch
 * left both tests green. The name says the same thing without using the word.
 */
@Tag("full-db")
@Testcontainers
class FileToolsTest {

  /**
   * The pgvector image, as {@code LocalProviderTest} uses: V1's first line is CREATE EXTENSION
   * vector and this class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home PAYMENTS = Home.of("payments");
  private static final Grant READ = new Grant(Scope.WORKSPACE, Mode.READ);
  private static final Grant WRITE = new Grant(Scope.WORKSPACE, Mode.WRITE);

  private static JdbcTemplate jdbc;

  @TempDir Path tmp;

  /**
   * {@link #tmp} spelled the way it really is, for the reason {@code ProviderRouterTest.real}
   * gives: a provider advertises canonical roots and {@code /var} is a symlink on this host.
   */
  private Path real;

  private Path repo;
  private Path outside;
  private ProjectStore store;

  /**
   * What the agent's definition declared. Set per test and never defaulted: the mode is what two
   * tests here are about.
   */
  private List<Grant> grants = List.of(READ);

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void freshProjects() throws IOException {
    // CASCADE since V14: `memories` and `conversations` reference this
    // table now, so Postgres refuses a plain TRUNCATE of it whether or not
    // they hold anything. Nothing here holds a memory or a conversation.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, projects CASCADE");
    real = tmp.toRealPath();
    repo = Files.createDirectory(real.resolve("repo"));
    outside = Files.createDirectory(real.resolve("outside"));
    Path server = Files.createDirectory(real.resolve("srv"));
    // Not the names the server ships, for ProjectStoreTest's reason: a
    // hardcoded "application.yml" would pass a suite that used it.
    Path configFile = Files.writeString(server.resolve("plowshare.yml"), "a fixture");
    Path samplingDir = Files.createDirectory(server.resolve("profiles"));
    store =
        new ProjectStore(
            jdbc,
            configFile,
            samplingDir,
            server.resolve("console-token"),
            server.resolve("exports"),
            server.resolve("data"));
    grants = List.of(READ);
  }

  /**
   * The router a job gets: this server's own disk, for whatever tier it is asked about, with the
   * grants the test set.
   */
  private ProviderRouter local() {
    return new ProviderRouter(home -> List.of(new LocalProvider(store, home, grants)));
  }

  private static ProviderRouter over(FileProvider... providers) {
    return new ProviderRouter(home -> List.of(providers));
  }

  /**
   * The arguments as a model would send them.
   *
   * <p><b>Newlines are escaped, and the first draft did not escape them</b> — so every write test
   * sent JSON that was not JSON, got the parse refusal as its tool result, and would have reported
   * a tool that never wrote anything as a passing write had the assertion been on the message
   * rather than on the file. A helper blind in the dimension its callers are about, trap 2, caught
   * by the two assertions that read the disk back.
   */
  private static String json(String... pairs) {
    StringBuilder out = new StringBuilder("{");
    for (int i = 0; i < pairs.length; i += 2) {
      if (i > 0) {
        out.append(',');
      }
      out.append('"')
          .append(pairs[i])
          .append("\":\"")
          .append(
              pairs[i + 1]
                  .replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t"))
          .append('"');
    }
    return out.append('}').toString();
  }

  // --- file_read -----------------------------------------------------------

  @Test
  void a_file_inside_the_workspace_is_read_whole() throws IOException {
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("Main.java"), "class Main {}\n");

    String out = new FileTools.Read(local()).run(json("path", file.toString()), PAYMENTS);
    assertTrue(out.startsWith("[file_read metadata: corpus=code; type=code; subtype=java;"));
    assertTrue(out.contains("live file window, not an indexed document revision"));
    assertEquals("class Main {}", out.substring(out.indexOf("\n\n") + 2));
    // Without the file's own trailing newline, which is a real change and is
    // the lines carrier's and not this tool's: FileReply no longer carries a
    // read's text beside its span, and String.lines does not keep the
    // terminator that ended a line. There is nowhere for a final newline to
    // live, and adding one back here would invent it for the files that
    // never had one.
  }

  @Test
  void a_refused_path_is_a_tool_result_the_model_can_correct() {
    store.define("payments", repo, List.of());

    String out = new FileTools.Read(local()).run(json("path", "/etc/passwd"), PAYMENTS);

    assertTrue(out.contains("outside"), "the model is told why, in prose — " + out);
    assertTrue(
        out.contains(repo.toString()),
        "and where it may look instead, which is what turns the refusal into a"
            + " next move rather than another guess — "
            + out);
  }

  @Test
  void a_path_inside_a_root_and_inside_an_exclusion_is_routed_and_then_refused()
      throws IOException {
    // The composed path the spec's "enforcement stays doubled" is actually
    // about, and nothing pinned it. ProviderRouter.covers deliberately
    // ignores exclusions — it asks only whose filesystem this is — so the
    // router ROUTES this path and LocalProvider is what refuses it. Both
    // halves have their own tests; what had none is that the two together
    // reach the model as a tool result rather than as an ending, which is
    // the only thing an agent can act on.
    //
    // It closes no mutation gap, and should not be read as one: every mutant
    // it kills is killed by some other test here. It earns its place as a
    // multi-line regression instead — the spec's doubled enforcement is a
    // claim about two layers COMPOSING, and no single-line mutant models a
    // reordering that would break the composition while leaving each layer
    // correct on its own.
    Path secret = Files.createDirectory(repo.resolve("secret"));
    Files.writeString(secret.resolve("keys.txt"), "not for an agent");
    store.define("payments", repo, List.of(secret));

    String out =
        new FileTools.Read(local())
            .run(json("path", secret.resolve("keys.txt").toString()), PAYMENTS);

    // Only that it came back as prose saying "outside", and that no bytes
    // came with it. NOT which of the two layers refused: the router's
    // sentence and the provider's both contain the word, so an assertion
    // naming one would be claiming more than it can see — and the claim
    // worth making is that the composition is a tool result at all.
    assertTrue(
        out.contains("outside"),
        "a refusal the model reads, and the call returned rather than raising — " + out);
    assertFalse(
        out.contains("not for an agent"),
        "and the excluded file's bytes did not come back with it");
  }

  @Test
  void a_provider_that_cannot_be_reached_ends_the_run_instead() throws IOException {
    store.define("payments", repo, List.of());
    Path file = repo.resolve("Main.java");
    Files.delete(repo);

    WorkspaceUnavailableException gone =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> new FileTools.Read(local()).run(json("path", file.toString()), PAYMENTS),
            "an outage is not a tool result: rendering it would hand the model"
                + " 'there is no file there' about a workspace that is gone");
    assertTrue(gone.getMessage().contains("no longer there"), gone.getMessage());
  }

  @Test
  void a_global_job_has_no_workspace_and_says_so() {
    store.define("payments", repo, List.of());

    String out =
        new FileTools.Read(local())
            .run(json("path", repo.resolve("Main.java").toString()), Home.global());

    assertTrue(
        out.contains("global"),
        "global is the cross-project tier; no filesystem means everywhere — " + out);
  }

  @Test
  void a_project_with_no_workspace_says_whose_fix_that_is() {
    // No row at all, which is a different state from the global tier and
    // from an agent with no grant, and a different person fixes each.
    String out =
        new FileTools.Read(local())
            .run(json("path", repo.resolve("Main.java").toString()), PAYMENTS);

    assertTrue(out.contains("no workspace is defined"), out);
    assertFalse(out.contains("global"), "not the tier's sentence — " + out);
    assertFalse(out.contains("scopes"), "not the definition's sentence — " + out);
  }

  @Test
  void an_agent_with_no_grant_is_pointed_at_its_own_definition() {
    store.define("payments", repo, List.of());
    grants = List.of();

    String out =
        new FileTools.Read(local())
            .run(json("path", repo.resolve("Main.java").toString()), PAYMENTS);

    assertTrue(out.contains("scopes"), out);
    assertFalse(
        out.contains("no workspace is defined"),
        "the projects table is fine; the definition is the thing to change — " + out);
  }

  @Test
  void a_relative_path_is_refused_rather_than_resolved_against_the_servers_directory() {
    // Excalibur resolved a relative path against the workspace. Here it
    // would resolve against the process's working directory — the server's
    // own checkout — so it is refused, and refused with the reason rather
    // than as "outside every root", which would send the model looking for a
    // file that was never the problem.
    store.define("payments", repo, List.of());

    String out = new FileTools.Read(local()).run(json("path", "src/Main.java"), PAYMENTS);

    assertTrue(out.contains("absolute"), out);
    assertTrue(
        out.contains("file_roots"), "and told how to find out what to write instead — " + out);
  }

  @Test
  void a_path_the_platform_cannot_even_parse_is_a_tool_result() {
    // Measured on JDK 21: Path.of("a\0b") raises InvalidPathException, which
    // is unchecked and extends IllegalArgumentException. Task 3 recorded it
    // as the tools' problem rather than the seam's, because the seam is
    // handed a Path and cannot be reached with a string that is not one.
    store.define("payments", repo, List.of());

    String out = new FileTools.Read(local()).run("{\"path\": \"/a\\u0000b\"}", PAYMENTS);

    assertTrue(out.contains("file_read"), out);
    assertFalse(out.isBlank(), "a tool result with nothing in it reads as a broken tool");
  }

  @Test
  void a_missing_path_argument_is_a_tool_result_and_not_an_ending() {
    String out = new FileTools.Read(local()).run("{}", PAYMENTS);

    assertTrue(out.contains("path"), out);
  }

  @Test
  void a_file_of_many_lines_comes_back_as_all_of_them_in_order() throws IOException {
    // The fixture this tool had none of, found by mutation: every file_read
    // fixture in this class was ONE LINE — the two display-limit ones are a
    // single enormous line each — so a tool that asked for a one-line window,
    // or that joined the lines with nothing between them, passed the whole
    // suite. Both mutants survived until this test existed.
    //
    // It also pins what a read that reaches the end of a file looks like:
    // the file's own lines, joined as they were, and no continuation note.
    // The note exists to name a next call, so its absence is the statement
    // that there is not one.
    store.define("payments", repo, List.of());
    Path file =
        Files.writeString(
            repo.resolve("Many.java"), "package p;\n\nclass Many {\n    int n = 3;\n}\n");

    String out = new FileTools.Read(local()).run(json("path", file.toString()), PAYMENTS);

    assertEquals(
        "package p;\n\nclass Many {\n    int n = 3;\n}",
        out.substring(out.indexOf("\n\n") + 2),
        "every line, in order, separated as they were in the file — and without the"
            + " file's own trailing newline, which the lines carrier cannot hold");
  }

  @Test
  void a_file_of_fifty_thousand_characters_is_shown_whole() throws IOException {
    // The accepted side of the display limit, written as a literal and not
    // as an expression over the constant: a fixture derived from the number
    // it is meant to pin moves with it and holds nothing. This says fifty
    // thousand characters are shown; nothing here pins the ceiling itself,
    // which is a judgement about a model's context window and has no
    // instrument.
    store.define("payments", repo, List.of());
    String body = "x".repeat(50_000);
    Path file = Files.writeString(repo.resolve("big.txt"), body);

    assertEquals(body, new FileTools.Read(local()).run(json("path", file.toString()), PAYMENTS));
  }

  @Test
  void a_window_from_a_client_that_ignored_it_is_cut_like_any_other_answer() {
    // Display, not a refusal: the provider refuses at 8 MiB and this is a
    // different thing entirely — the answer is fine, and what is bounded is
    // how much of it goes into one turn. Excalibur applies the same 100 000
    // characters in its own file_read, and the note is the point: an answer
    // that simply stopped would read to the model as the end of the file.
    //
    // THE FIXTURE IS A PROVIDER AND NOT A FILE, and that is the whole of
    // what changed here. This used to be one 150 000-character line on disk,
    // which reached the cut because Window.cut returned an oversized line
    // whole. It refuses now, so no window this build cuts can get here — and
    // that is what makes MAX_DISPLAY_CHARS a backstop rather than a bound.
    // What is left to back stop is a client on the other side of a wire
    // returning more than the window it was asked for, which RemoteProvider
    // passes through uncut. That client is this Fake.
    String enormous = "y".repeat(150_000);
    Span ignored = new Span(List.of(enormous), 0, 1, false, Span.END);

    String out =
        new FileTools.Read(over(new Fake("remote").covering(Path.of("/laptop")).returning(ignored)))
            .run(json("path", "/laptop/bundle.min.js"), PAYMENTS);

    assertTrue(
        out.substring(out.indexOf("\n\n") + 2).startsWith("y".repeat(1000)),
        "the beginning of the source after code metadata is what is kept");
    assertTrue(
        out.contains("150000 characters"),
        "the number with its unit, not the bare digits: @TempDir's directory name"
            + " is random digits and could contain them by accident. The model"
            + " is told how long the text really was, or it will believe the"
            + " answer it got was all of it — "
            + out.substring(out.length() - 300));
    assertTrue(out.length() < 150_000, "something was actually cut");
  }

  @Test
  void a_line_no_window_can_carry_refuses_the_read_and_names_the_search_instead()
      throws IOException {
    // The refusal as a model receives it, which is the only place the two
    // modules' spellings of file_grep meet: Window writes the sentence and
    // cannot see GREP_NAME, this class owns GREP_NAME and does not write the
    // sentence. Nothing but this assertion holds them together.
    store.define("payments", repo, List.of());
    Path bundle =
        Files.writeString(
            repo.resolve("bundle.min.js"), "x".repeat(Window.MAX_WINDOW_BYTES * 2) + "\n");

    String out = new FileTools.Read(local()).run(json("path", bundle.toString()), PAYMENTS);

    assertTrue(out.contains("line 0"), out);
    assertTrue(
        out.contains(FileTools.GREP_NAME),
        "the refusal names the tool this class actually registers, or it sends a model"
            + " after something that is not there — "
            + out);
    assertFalse(
        out.contains("x".repeat(1000)),
        "and none of the line came back, which is the point of refusing — "
            + out.substring(0, Math.min(200, out.length())));
  }

  @Test
  void the_file_a_read_refuses_is_still_searchable() throws IOException {
    // What makes that refusal a redirection rather than a wall. Asserted
    // rather than claimed: the sentence sends a model to file_grep, so
    // file_grep had better answer on the file file_read would not.
    store.define("payments", repo, List.of());
    Path bundle =
        Files.writeString(
            repo.resolve("bundle.min.js"),
            "var a=1;" + "x".repeat(Window.MAX_WINDOW_BYTES * 2) + "needle\n");

    String refusal = new FileTools.Read(local()).run(json("path", bundle.toString()), PAYMENTS);
    String found =
        new FileTools.Grep(local())
            .run(json("text", "needle", "path", bundle.toString()), PAYMENTS);

    assertTrue(refusal.contains(FileTools.GREP_NAME), refusal);
    assertTrue(
        found.contains("offset=0"),
        "the search found the line the read could not carry — " + found);
    assertTrue(
        found.contains("[cut]"),
        "shown only as far as a match may carry, which is exactly why it survives a"
            + " file the window cannot — "
            + found);
  }

  @Test
  void an_empty_file_is_said_to_be_empty_rather_than_answered_with_nothing() throws IOException {
    // AgentTool: never null and never blank. A tool result with nothing in
    // it reads to a model as a tool that does not work, and "the file is
    // empty" is a real answer that an empty string cannot express.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("nothing-in-it.txt"), "");

    String out = new FileTools.Read(local()).run(json("path", file.toString()), PAYMENTS);

    assertFalse(out.isBlank());
    assertTrue(out.contains("empty"), out);
  }

  // --- file_read: the window and its continuation --------------------------

  /**
   * A file whose every line says which line it is, so a window's contents name their own offsets.
   *
   * <p>{@code numbered(7)} is the seven lines {@code "0"} through {@code "6"}, each terminated. A
   * fixture of repeated identical lines would pass a tool that returned the right <em>number</em>
   * of lines from the wrong place.
   */
  private static String numbered(int count) {
    StringBuilder out = new StringBuilder();
    for (int at = 0; at < count; at++) {
      out.append(at).append('\n');
    }
    return out.toString();
  }

  /**
   * The arguments a model sends when it is paging: a path and numbers, which {@link #json} cannot
   * build because it quotes every value.
   */
  private static String window(Path path, String numbers) {
    return "{\"path\": \"" + path + "\", " + numbers + "}";
  }

  @Test
  void a_read_begins_at_the_offset_it_was_given() throws IOException {
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(7));

    String out =
        new FileTools.Read(local()).run(window(file, "\"offset\": 3, \"limit\": 2"), PAYMENTS);

    assertTrue(out.endsWith("3\n4"), "the two lines at that offset, in order — " + out);
    assertFalse(
        out.contains("0\n1"),
        "and nothing from before it: an offset a tool ignores is a tool that pages"
            + " for ever over the same window — "
            + out);
  }

  @Test
  void a_read_with_no_window_argument_starts_at_the_beginning() throws IOException {
    // The defaults, which are what every read that does not mean to page
    // sends. An offset defaulting to anything but the first line, or a limit
    // defaulting to one, would leave this suite's other reads asserting on a
    // fragment and calling it a file.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(7));

    String out = new FileTools.Read(local()).run(json("path", file.toString()), PAYMENTS);

    assertEquals(
        "0\n1\n2\n3\n4\n5\n6",
        out,
        "the whole of a file that fits in one window, and no note on it — " + out);
  }

  @Test
  void a_window_short_of_the_end_says_what_it_showed_how_long_the_file_is_and_what_to_ask_next()
      throws IOException {
    // The three facts, and the third is the one Plowshare's own display cut
    // has never carried: a limit that states the problem and not the remedy
    // leaves the model to invent the next call, and the research this slice
    // followed found that is where a static instruction fails to reach.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(7));

    String out = new FileTools.Read(local()).run(window(file, "\"limit\": 4"), PAYMENTS);

    assertTrue(out.endsWith("0\n1\n2\n3"), "the window's own lines and no more — " + out);
    assertTrue(
        out.contains("Lines 0 to 3"),
        "which lines these are, in the same counting as offset, so the last one shown"
            + " and the first one still to come are a step apart and not a"
            + " conversion apart — "
            + out);
    assertTrue(
        out.contains("offset=4"),
        "the next call as a number that can be sent without working it out: a note"
            + " saying only that something was cut is the dead end this replaces — "
            + out);
    assertTrue(
        out.contains("of 7"),
        "and how long the file is, or a full window and a finished file read the"
            + " same — "
            + out);
  }

  @Test
  void the_continuation_note_survives_an_answer_long_enough_to_be_cut() {
    // file_glob's lesson, applied here before it can bite: display() cuts the
    // tail, so a note appended after the text is the first thing deleted.
    //
    // Reachable through the same fixture as the cut itself, and no longer
    // through a file: this was one 150 000-character line on disk with a
    // line after it, back when Window.cut returned such a line whole. What
    // is left is a client that returned more than its window and still
    // reported more to come, which is the shape that carries both a note and
    // an over-long text.
    Span ignored = new Span(List.of("y".repeat(150_000)), 0, 2, true, Span.BYTES);

    String out =
        new FileTools.Read(over(new Fake("remote").covering(Path.of("/laptop")).returning(ignored)))
            .run(json("path", "/laptop/huge.txt"), PAYMENTS);

    assertTrue(
        out.contains("offset=1"),
        "the note is still there after the cut — " + out.substring(0, 400));
    assertTrue(out.length() < 150_000, "and something really was cut");
  }

  @Test
  void a_window_stopped_by_size_reads_differently_from_one_stopped_by_its_line_limit()
      throws IOException {
    // The distinction Span.BYTES exists for, at the one place a model can
    // act on it: a wider limit will not widen this window, and a model that
    // cannot tell the two apart spends a turn discovering it.
    store.define("payments", repo, List.of());
    Path wide = Files.writeString(repo.resolve("wide.txt"), ("z".repeat(600) + "\n").repeat(400));
    Path narrow = Files.writeString(repo.resolve("N.txt"), numbered(7));

    String onBytes = new FileTools.Read(local()).run(json("path", wide.toString()), PAYMENTS);
    String onLines = new FileTools.Read(local()).run(window(narrow, "\"limit\": 4"), PAYMENTS);

    assertTrue(
        onBytes.contains("larger limit"),
        "the byte ceiling says a wider limit would not help — " + onBytes.substring(0, 400));
    assertFalse(
        onLines.contains("larger limit"),
        "and the line allowance must not, because there a wider limit is exactly the"
            + " right next move — "
            + onLines);
    assertTrue(
        onBytes.contains("offset="),
        "both still name the next offset — " + onBytes.substring(0, 400));
    assertFalse(
        onBytes.contains("Cut off here"),
        "and a window filled to the byte ceiling still fits under the display limit"
            + " with its note on it, which is the measurement MAX_DISPLAY_CHARS'"
            + " javadoc rests on when it calls itself a backstop — "
            + onBytes.length());
  }

  /**
   * The bracketed block this tool writes in front of a window, and none of the file behind it.
   *
   * <p><b>Every assertion about what the tool <em>said</em> goes through here.</b> The answers in
   * this section are numbered lines, so a bare {@code out.contains("300")} matches line 300 of the
   * fixture and passes with the sentence deleted — the same shape as the {@code empty.txt} fixture
   * this class's javadoc records, where the assertion matched the path instead of the message.
   * Cutting the note off first makes that impossible rather than unlikely.
   *
   * <p>An answer with no note returns the empty string, which is the assertion for "the tool added
   * nothing" and not a failure to find one.
   */
  private static String note(String out) {
    int end = out.indexOf(']');
    return end < 0 ? "" : out.substring(0, end + 1);
  }

  @Test
  void a_read_that_names_no_limit_stops_where_one_turn_can_still_think_about_it()
      throws IOException {
    // The call a model makes before it knows anything about the file: a path
    // and nothing else. That defaulted to MAX_WINDOW_LINES, a number chosen
    // for what a socket carries, and the live run measured what it costs the
    // model at the other end — roughly 25 000 tokens in one tool result, a
    // prompt over two minutes long, and a chat timeout that ends the run.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(900));

    String out = new FileTools.Read(local()).run(json("path", file.toString()), PAYMENTS);

    assertTrue(
        out.endsWith("\n299"),
        "an omitted limit is the cap and not the wire's bound — "
            + out.substring(out.length() - 40));
    assertTrue(
        note(out).contains("offset=300"),
        "and the answer still says where the next window starts — " + note(out));
  }

  @Test
  void a_limit_larger_than_one_turn_holds_is_reduced_and_the_answer_names_both_numbers()
      throws IOException {
    // The half of this that is easy to skip. A model that asked for 800
    // lines and silently got 300 has nothing to tell the cap apart from the
    // end of a short file: `more` and the next offset are exactly the clues
    // an ordinary short window already gives it. The harness knows which of
    // the two it was and is the only thing that can say.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(900));

    String out = new FileTools.Read(local()).run(window(file, "\"limit\": 800"), PAYMENTS);
    String note = note(out);

    assertTrue(
        out.endsWith("\n299"),
        "the window really was cut to the cap — " + out.substring(out.length() - 40));
    // A mutant that dropped the first of the sentence's two mentions of 800
    // SURVIVED this, and it deserves recording rather than patching: the
    // clause about sending 800 again still names it, so the note is still
    // doing the job this assertion is for. The mutant that removes the
    // number altogether is killed here, which is the property that matters
    // — the assertion pins "the model's own number is in the note", not a
    // particular clause of the wording.
    assertTrue(
        note.contains("800"),
        "the note quotes the number that was asked for, or the model cannot tell"
            + " which of its own arguments was overruled — "
            + note);
    assertTrue(note.contains("300"), "and the number that won — " + note);
    assertTrue(
        note.contains("reduced"),
        "in words that say an argument was overruled, rather than only reporting a"
            + " range, which is what an ordinary short window already reports — "
            + note);
  }

  @Test
  void a_window_cut_by_the_turn_cap_reads_differently_from_one_that_merely_has_more()
      throws IOException {
    // Both hand back some lines and name a next offset, and they call for
    // different readings: one says the file continues, the other says the
    // file continues AND the limit you sent was not the limit used. This is
    // the same distinction Span.BYTES exists for, one bound further out.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(900));
    AgentTool read = new FileTools.Read(local());

    String capped = note(read.run(window(file, "\"limit\": 800"), PAYMENTS));
    String ordinary = note(read.run(window(file, "\"limit\": 50"), PAYMENTS));

    assertTrue(capped.contains("reduced"), "the capped window says so — " + capped);
    assertFalse(
        ordinary.contains("reduced"),
        "and a limit the tool honoured must not, or the sentence says nothing"
            + " when it does appear — "
            + ordinary);
    assertTrue(
        ordinary.contains("offset=50"), "both still carry the ordinary continuation — " + ordinary);
    assertTrue(capped.contains("offset=300"), capped);
  }

  @Test
  void a_reduced_limit_over_a_file_that_ends_inside_the_window_says_nothing_at_all()
      throws IOException {
    // A read that reaches the end carries no note, and a cap that cost the
    // caller nothing must not break that. The model asked for more lines
    // than this tool ever returns and still got the whole file: there is no
    // correction to make, and a sentence about a reduced limit here would
    // send it paging on into nothing.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(40));

    String out = new FileTools.Read(local()).run(window(file, "\"limit\": 800"), PAYMENTS);

    assertTrue(out.startsWith("0\n1\n"), "the whole file — " + out.substring(0, 20));
    assertTrue(out.endsWith("\n39"), out.substring(out.length() - 20));
    assertEquals("", note(out), "and nothing the tool added — " + note(out));
  }

  @Test
  void a_limit_past_even_the_wire_is_brought_down_to_the_turn_cap_and_not_to_the_wire()
      throws IOException {
    // This test used to assert offset=MAX_WINDOW_LINES here, and that line
    // was the bug in miniature: the number a model got back was the one
    // chosen for what a socket carries. Both bounds still apply and the
    // smaller has to be the one that shows, or the transport clamp is doing
    // a job it was never sized for. Window.of's own clamp is not lost with
    // it — WindowTest and FileFramesTest hold it from the wire's side, which
    // is the only side that can still reach it.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(2_500));

    String out = new FileTools.Read(local()).run(window(file, "\"limit\": 999999"), PAYMENTS);

    assertTrue(note(out).contains("offset=300"), "the turn cap is where it stopped — " + note(out));
    assertFalse(
        note(out).contains("offset=" + Window.MAX_WINDOW_LINES),
        "and not the wire's bound — " + note(out));
    assertFalse(
        out.contains("\n2499"),
        "no line past the cap came back — " + out.substring(out.length() - 200));
  }

  @Test
  void an_offset_past_the_end_is_told_the_length_rather_than_that_the_file_is_empty()
      throws IOException {
    // A caller paging forward reaches this exactly once and it is how it
    // learns to stop. "The file is empty" would be a false statement about a
    // file with lines in it, made because the model asked one window too
    // many — and a model that believed it would stop looking.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(7));

    String out = new FileTools.Read(local()).run(window(file, "\"offset\": 99"), PAYMENTS);

    assertFalse(out.contains("empty"), "the file is not empty — " + out);
    assertTrue(out.contains("7"), "and it is told how long it really is — " + out);
  }

  @Test
  void a_negative_offset_is_a_tool_result_and_not_an_ending() {
    // Window's constructor refuses one with an IllegalArgumentException,
    // which shares no catch clause with a caller's mistake and would end the
    // run. A model that typed a minus sign gets a sentence instead.
    store.define("payments", repo, List.of());

    String out =
        new FileTools.Read(local()).run(window(repo.resolve("N.txt"), "\"offset\": -1"), PAYMENTS);

    assertTrue(out.contains("offset"), out);
    assertFalse(out.isBlank());
  }

  @Test
  void a_limit_of_zero_is_a_tool_result_and_not_an_ending() {
    // The same seam from the other side, and the reason Window refuses it:
    // a window that carries no lines while reporting that more remains is a
    // loop with no way out of it.
    store.define("payments", repo, List.of());

    String out =
        new FileTools.Read(local()).run(window(repo.resolve("N.txt"), "\"limit\": 0"), PAYMENTS);

    assertTrue(out.contains("limit"), out);
    assertFalse(out.isBlank());
  }

  @Test
  void a_window_argument_that_is_not_a_number_names_the_argument_it_could_not_read() {
    store.define("payments", repo, List.of());
    AgentTool read = new FileTools.Read(local());

    String badOffset = read.run(window(repo.resolve("N.txt"), "\"offset\": \"soon\""), PAYMENTS);
    String badLimit = read.run(window(repo.resolve("N.txt"), "\"limit\": [4]"), PAYMENTS);

    assertTrue(badOffset.contains("offset"), badOffset);
    assertTrue(badLimit.contains("limit"), badLimit);
  }

  @Test
  void a_window_argument_too_large_for_an_int_is_echoed_as_it_was_sent() {
    // Measured against Jackson 2.17.2, this project's version: asInt()
    // saturates or truncates in silence, so 99999999999999 arrives as
    // 276447231. A refusal built from the value rather than from the node
    // would quote a number the model never sent, and an offset accepted that
    // way would page into a part of the file nobody asked for.
    store.define("payments", repo, List.of());

    String out =
        new FileTools.Read(local())
            .run(window(repo.resolve("N.txt"), "\"offset\": 99999999999999"), PAYMENTS);

    assertTrue(out.contains("99999999999999"), out);
    assertFalse(out.contains("276447231"), "and not what an int made of it — " + out);
  }

  @Test
  void a_window_argument_sent_as_a_numeric_string_is_accepted() throws IOException {
    // memory_recall's leniency, for its reason: a model sends a number as a
    // string often enough that refusing one costs a whole turn to learn
    // nothing.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(7));

    String out =
        new FileTools.Read(local())
            .run(window(file, "\"offset\": \"3\", \"limit\": \"2\""), PAYMENTS);

    assertTrue(out.endsWith("3\n4"), out);
  }

  // --- file_stat -----------------------------------------------------------

  @Test
  void file_stat_answers_how_long_a_file_is_without_carrying_any_of_it() throws IOException {
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(7));

    String out = new FileTools.Stat(local()).run(json("path", file.toString()), PAYMENTS);

    assertTrue(out.contains("7"), "the count, which is what makes a read plannable — " + out);
    assertFalse(
        out.contains("0\n1"),
        "and none of the lines: a stat that carried them would be a read under"
            + " another name — "
            + out);
  }

  @Test
  void file_stat_quotes_the_number_of_lines_a_read_will_actually_hand_over() throws IOException {
    // The sentence that turns a length into a plan, which makes it the one
    // place a number chosen for a socket does real damage: it quoted
    // MAX_WINDOW_LINES, which was right while that was also what file_read
    // returned, and became a lie the moment the turn cap went in. A model
    // planning 2 000-line reads of a 4 795-line file expects three calls and
    // gets sixteen.
    //
    // Anchored to the phrase and not to the bare digits, because @TempDir's
    // directory name is random digits and the path is in this sentence.
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("N.txt"), numbered(900));

    String out = new FileTools.Stat(local()).run(json("path", file.toString()), PAYMENTS);

    assertTrue(out.contains("at most 300"), "the count a read will really hand over — " + out);
    assertFalse(
        out.contains("at most " + Window.MAX_WINDOW_LINES),
        "and never the wire's bound, which no read has returned since the cap — " + out);
  }

  @Test
  void file_stat_on_an_empty_file_says_so_rather_than_answering_with_a_zero() throws IOException {
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("nothing-in-it.txt"), "");

    String out = new FileTools.Stat(local()).run(json("path", file.toString()), PAYMENTS);

    assertFalse(out.isBlank());
    assertTrue(out.contains("empty"), out);
  }

  @Test
  void file_stat_is_refused_where_a_read_is_and_is_not_a_cheaper_way_past_the_leash() {
    store.define("payments", repo, List.of());

    String outside = new FileTools.Stat(local()).run(json("path", "/etc/passwd"), PAYMENTS);

    assertTrue(outside.contains("outside"), outside);
    assertTrue(outside.contains(repo.toString()), "and where it may look instead — " + outside);
  }

  @Test
  void an_agent_with_no_grant_cannot_measure_a_file_either() {
    // The mutant task 5 could not kill: a stat with no grant check is a way
    // to learn how long a file is that this agent was never given, and a
    // test named for the count would have passed with the check deleted.
    store.define("payments", repo, List.of());
    grants = List.of();

    String out =
        new FileTools.Stat(local()).run(json("path", repo.resolve("N.txt").toString()), PAYMENTS);

    assertTrue(out.contains("scopes"), out);
  }

  @Test
  void a_missing_path_argument_to_a_stat_is_a_tool_result_and_not_an_ending() {
    String out = new FileTools.Stat(local()).run("{}", PAYMENTS);

    assertTrue(out.contains("path"), out);
    assertTrue(out.contains("file_stat"), "and says which tool wanted it — " + out);
  }

  @Test
  void a_stat_over_a_vanished_workspace_ends_the_run() throws IOException {
    store.define("payments", repo, List.of());
    Path file = repo.resolve("N.txt");
    Files.delete(repo);

    assertThrows(
        WorkspaceUnavailableException.class,
        () -> new FileTools.Stat(local()).run(json("path", file.toString()), PAYMENTS),
        "an outage is not a tool result here either");
  }

  // --- file_glob -----------------------------------------------------------

  @Test
  void a_pattern_that_matches_lists_what_it_found() throws IOException {
    store.define("payments", repo, List.of());
    Files.createDirectory(repo.resolve("src"));
    Files.writeString(repo.resolve("src/Main.java"), "class Main {}");
    Files.writeString(repo.resolve("src/Other.java"), "class Other {}");
    Files.writeString(repo.resolve("src/notes.txt"), "not java");

    String out = new FileTools.Glob(local()).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(out.contains("Main.java") && out.contains("Other.java"), out);
    assertFalse(
        out.contains("notes.txt"),
        "the pattern filtered, and the fixture had something for it to filter" + " out — " + out);
  }

  @Test
  void a_pattern_that_matches_nothing_says_where_it_looked() throws IOException {
    // The confident empty answer, closed at the one place the model reads.
    // "No match" is only a true statement about a search that ran, so the
    // answer names the tree it ran over.
    store.define("payments", repo, List.of());
    Files.writeString(repo.resolve("notes.txt"), "not java");

    String out = new FileTools.Glob(local()).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(out.contains(repo.toString()), "the roots that were searched are named — " + out);
  }

  @Test
  void a_glob_with_no_root_is_the_absence_sentence_and_never_no_matches() {
    // Excalibur's agent spent 15 of its 16 turns inventing new patterns
    // against a root list that was empty the whole time.
    String out = new FileTools.Glob(local()).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(out.contains("no workspace is defined"), out);
    assertFalse(out.contains("no file"), "not dressed as a search that found nothing — " + out);
  }

  @Test
  void an_absolute_pattern_is_a_tool_result_naming_the_fix() {
    store.define("payments", repo, List.of());

    String out = new FileTools.Glob(local()).run(json("pattern", "/etc/**"), PAYMENTS);

    assertTrue(out.contains("absolute"), out);
    // Only that the pattern is quoted back. LocalProvider owns the wording
    // that tells the model what to write instead, and asserting on that
    // sentence here would be this file pinning another file's prose.
    assertTrue(out.contains("etc/**"), "the pattern it complained about is named — " + out);
  }

  @Test
  void a_missing_pattern_argument_is_a_tool_result() {
    assertTrue(new FileTools.Glob(local()).run("{}", PAYMENTS).contains("pattern"));
  }

  @Test
  void a_provider_that_could_not_be_searched_is_named_beside_the_one_that_could() {
    // A partial search reported as a whole answer is the confident empty
    // answer with extra steps: two filesystems, one searched, and a listing
    // that does not say so.
    Fake reachable = new Fake("remote").matching(Path.of("/laptop/A.java"));
    Fake empty = new Fake("local").explaining("no workspace is defined for project 'x'");

    String out =
        new FileTools.Glob(over(reachable, empty)).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(out.contains("A.java"), out);
    assertTrue(
        out.contains("no workspace is defined"),
        "and the half that was not searched is named — " + out);
  }

  @Test
  void a_glob_no_provider_could_run_never_reads_as_nothing_matched() {
    Fake one = new Fake("local").explaining("this job runs in the global tier");
    Fake two = new Fake("remote").explaining("no session owns this run");

    String out = new FileTools.Glob(over(one, two)).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(out.contains("global tier") && out.contains("no session"), out);
    assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("no file match"), out);
  }

  @Test
  void one_hit_is_one_file_and_not_one_files() throws IOException {
    // A branch is a branch. The plural is the only place this listing counts
    // anything, and a listing that says "1 files" is one a reader stops
    // trusting about the numbers it gives.
    store.define("payments", repo, List.of());
    Files.writeString(repo.resolve("Only.java"), "class Only {}");

    String out = new FileTools.Glob(local()).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(out.contains("1 file matching"), out);
  }

  @Test
  void a_listing_too_long_for_one_answer_is_cut_and_the_cut_is_named() throws IOException {
    // The display limit applies to a listing as well as to a file, and by
    // the same argument: the provider refuses at ten thousand matches rather
    // than truncating, and this is the other thing — the search succeeded,
    // and what is bounded is how much of the answer one turn carries. Two
    // thousand paths of this length are well past it.
    store.define("payments", repo, List.of());
    Path many = Files.createDirectory(repo.resolve("many"));
    for (int i = 0; i < 2_000; i++) {
      Files.writeString(many.resolve("File" + i + ".java"), "x");
    }

    String out = new FileTools.Glob(local()).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(
        out.contains("Cut off here"),
        "a listing that simply stopped would read"
            + " as the whole of what matched — "
            + out.substring(out.length() - 200));
    assertTrue(
        out.contains("2000 files matching"),
        "and the count it was cut from is still in it — " + out.substring(0, 200));
  }

  @Test
  void the_unsearched_half_survives_a_listing_long_enough_to_be_cut() throws IOException {
    // The note said which filesystem was never searched, and `display` cut
    // the tail, so the note was the first thing deleted. Measured before the
    // fix at 3000 hits and one refusing provider: length 100135, 'Not
    // searched' absent, 'Cut off here' present — 1941 paths from one
    // filesystem under a cut that reads as THERE ARE MORE MATCHING FILES
    // rather than A SECOND FILESYSTEM WAS NEVER SEARCHED. Verbatim the
    // failure Glob.answer's own javadoc forbids, produced by Glob.answer.
    //
    // It had not bitten because nothing is wired and one provider leaves
    // `refused` always empty. Task 7 makes two providers ordinary, which is
    // why this is a test and not a note.
    store.define("payments", repo, List.of());
    Path many = Files.createDirectory(repo.resolve("many"));
    for (int i = 0; i < 3_000; i++) {
      Files.writeString(many.resolve("File" + i + ".java"), "x");
    }
    ProviderRouter mixed =
        new ProviderRouter(
            home ->
                List.of(
                    new LocalProvider(store, home, grants),
                    new Fake("remote")
                        .explaining("no session owns this run, so there is no laptop")));

    String out = new FileTools.Glob(mixed).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(
        out.contains("Cut off here"),
        "the fixture is only a fixture if the answer is actually long enough to"
            + " be cut — otherwise this test passes without exercising the cut");
    assertTrue(out.contains("Not searched"), "the note survived the cut");
    assertTrue(
        out.contains("no laptop"),
        "and so did the sentence saying which half went unsearched, which is the"
            + " whole of what the note is for");
  }

  @Test
  void a_glob_on_a_job_with_no_provider_says_so_rather_than_finding_nothing() {
    String out = new FileTools.Glob(over()).run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(out.contains("no filesystem"), out);
  }

  @Test
  void a_provider_that_answers_a_search_it_has_no_root_for_still_gets_a_sentence() {
    // Both lists empty at once: a provider advertising no roots and yet
    // answering the search, which breaks FileProvider's contract and is
    // reachable only from the far side of a wire. Nothing goes into
    // `searched` because there are no roots, and nothing into `refused`
    // because nothing raised — so an answer assembled only from those two
    // would be the empty string, which AgentTool forbids and which reads to
    // a model as a tool that does not work.
    String out =
        new FileTools.Glob(over(new Fake("remote"))).run(json("pattern", "**/*.java"), PAYMENTS);

    assertFalse(out.isBlank());
    assertTrue(out.contains("Nothing was searched"), out);
  }

  @Test
  void an_answer_made_only_of_refusals_is_cut_like_any_other() {
    // The "Nothing was searched" path was left uncut on the reasoning that a
    // refusal is short. That is a fact about LocalProvider, not about the
    // interface: a remote provider composes its refusal on another machine
    // and FileProvider bounds nothing. The fixture is that provider.
    String enormous = "this half is unavailable because " + "x".repeat(150_000);

    String out =
        new FileTools.Glob(over(new Fake("remote").explaining(enormous)))
            .run(json("pattern", "**/*.java"), PAYMENTS);

    assertTrue(out.contains("Cut off here"), "cut like any other answer");
    assertTrue(out.length() < 150_000, "and actually shorter than what it was given");
  }

  @Test
  void a_root_list_too_long_for_one_answer_is_cut_like_any_other() {
    // Same argument for file_roots, whose text is a provider's too: roots()
    // places no bound on how many roots or how long a name, and the absence
    // sentence is provider-authored.
    String enormous = "no workspace here because " + "y".repeat(150_000);

    String out =
        new FileTools.Roots(over(new Fake("remote").explaining(enormous))).run("", PAYMENTS);

    assertTrue(out.contains("Cut off here"), out.substring(out.length() - 200));
    assertTrue(out.length() < 150_000);
  }

  @Test
  void a_glob_over_a_vanished_workspace_ends_the_run() throws IOException {
    store.define("payments", repo, List.of());
    Files.delete(repo);

    assertThrows(
        WorkspaceUnavailableException.class,
        () -> new FileTools.Glob(local()).run(json("pattern", "**/*.java"), PAYMENTS));
  }

  // --- file_grep -----------------------------------------------------------

  /**
   * Two files with something in each of them that the searches below must leave out.
   *
   * <p><b>The standing rule, and this tool is where breaking it would be invisible.</b> A fixture
   * whose every line matched would pass a {@code find} that ignored the needle entirely, an {@code
   * ignore_case} that was always on, and a path argument that was never applied. So every file here
   * carries lines that must not come back, one file that must not be reached when a path names the
   * other, and both cases of one word.
   */
  private void sources() throws IOException {
    store.define("payments", repo, List.of());
    Files.createDirectory(repo.resolve("src"));
    Files.writeString(
        repo.resolve("src/Main.java"),
        """
                package p;

                class Main {
                    boolean flag = true;
                    int flag;
                    void start() {
                        Beacon.lit();
                    }
                }
                """);
    Files.writeString(
        repo.resolve("src/Other.java"),
        """
                package p;

                class Other {
                    void stop() {
                        beacon.dark();
                    }
                }
                """);
  }

  /**
   * The arguments a search sends: a path is optional and a flag is not a string, neither of which
   * {@link #json} can build.
   */
  private static String search(String text, String rest) {
    return "{\"text\": \"" + text + "\"" + (rest.isEmpty() ? "" : ", " + rest) + "}";
  }

  /** Every {@code offset=} in an answer, in the order they appear. */
  private static List<Integer> offsets(String out) {
    List<Integer> found = new ArrayList<>();
    Matcher at = Pattern.compile("offset=(\\d+)").matcher(out);
    while (at.find()) {
      found.add(Integer.parseInt(at.group(1)));
    }
    return found;
  }

  @Test
  void a_search_names_the_file_the_offset_and_the_line() throws IOException {
    sources();

    String out = new FileTools.Grep(local()).run(search("void start", ""), PAYMENTS);

    assertTrue(
        out.contains(repo.resolve("src/Main.java") + " offset=5: " + "    void start() {"), out);
    assertFalse(
        out.contains("Other.java"),
        "a file with nothing in it for this needle is not in the answer — " + out);
    assertFalse(
        out.contains("package p;"),
        "and neither are the lines of Main.java that did not match — " + out);
  }

  /**
   * The whole point of the tool, asserted end to end rather than as a number in a string.
   *
   * <p>The measurement that asked for {@code file_grep} is fourteen reads to reach one line and a
   * run that ran out of turns before it arrived. What replaces it is exactly this: one search, one
   * read, and <b>the number the search printed typed straight into the read's {@code offset}</b>.
   * So the offset is not asserted against a literal here — it is taken out of the answer and spent,
   * which is what a model does with it.
   *
   * <p><b>An off-by-one is what this exists to catch and is what nothing else would.</b> {@code
   * Found.Match#offset} counts from zero and every convention a model has read counts line numbers
   * from one; a rendering that added one to look familiar would leave every match looking right and
   * every follow-up read one line late. A test that compared the printed number against a
   * hand-counted line would move with such a change, because whoever made it would fix the
   * expectation too.
   */
  @Test
  void the_offset_a_match_carries_is_the_one_file_read_takes() throws IOException {
    sources();

    String found = new FileTools.Grep(local()).run(search("Beacon.lit", ""), PAYMENTS);
    List<Integer> at = offsets(found);
    assertEquals(1, at.size(), "one match to spend — " + found);

    String read =
        new FileTools.Read(local())
            .run(
                window(
                    repo.resolve("src/Main.java"), "\"offset\": " + at.get(0) + ", \"limit\": 1"),
                PAYMENTS);

    assertTrue(
        read.endsWith("        Beacon.lit();"),
        "the line the search pointed at, and not its neighbour — " + read);
  }

  @Test
  void a_search_with_no_path_covers_every_root_and_names_what_it_covered() throws IOException {
    sources();

    String out = new FileTools.Grep(local()).run(search("class ", ""), PAYMENTS);

    assertTrue(out.contains(repo.resolve("src/Main.java") + " offset=2"), out);
    assertTrue(out.contains(repo.resolve("src/Other.java") + " offset=2"), out);
    // The clause and not the bare path. `out.contains(repo)` passes on the
    // match lines alone, since every one of them is an absolute path under
    // this root — a mutant deleting the "in <roots>" clause outright left
    // that assertion green. Same shape as the empty.txt fixture this class's
    // javadoc records: the assertion matched something the answer was
    // carrying for another reason.
    assertTrue(
        out.contains(", in " + repo + "."),
        "and it says which tree it walked, so an empty answer later can be told"
            + " apart from having nowhere to look — "
            + out);
  }

  @Test
  void a_path_narrows_the_search_to_what_it_names() throws IOException {
    sources();

    String out =
        new FileTools.Grep(local())
            .run(
                search("class ", "\"path\": \"" + repo.resolve("src/Other.java") + "\""), PAYMENTS);

    assertTrue(out.contains("Other.java offset=2"), out);
    assertFalse(
        out.contains("Main.java"),
        "a path that is not applied is a leash that is not applied — " + out);
  }

  @Test
  void a_search_that_matches_nothing_says_where_it_looked() throws IOException {
    sources();

    String out = new FileTools.Grep(local()).run(search("kubernetes", ""), PAYMENTS);

    assertTrue(
        out.contains(repo.toString()),
        "never a bare no-matches: that sentence is what cost Excalibur's agent 15"
            + " of its 16 turns — "
            + out);
    assertTrue(out.contains("The search ran"), out);
  }

  @Test
  void a_search_folds_case_only_when_it_was_asked_to() throws IOException {
    sources();

    String folded =
        new FileTools.Grep(local()).run(search("BEACON", "\"ignore_case\": true"), PAYMENTS);
    String exact = new FileTools.Grep(local()).run(search("BEACON", ""), PAYMENTS);

    assertEquals(2, offsets(folded).size(), "both spellings — " + folded);
    assertEquals(
        0,
        offsets(exact).size(),
        "and neither of them when the case was not folded, or the flag does"
            + " nothing — "
            + exact);
  }

  /**
   * A needle is not stripped, which is {@code file_write}'s finding arriving at a second argument.
   *
   * <p>{@code ToolArguments.requireText} calls {@code strip()}, which is right for a path and wrong
   * for the text of a search: indentation and a trailing space are things an agent looks for on
   * purpose. The fixture is what makes that visible — {@code flag } with a space matches one line
   * and {@code flag} without it matches two, so a strip shows up as an extra match rather than as
   * nothing at all.
   */
  @Test
  void a_needle_keeps_the_spaces_it_was_sent() throws IOException {
    sources();

    String out = new FileTools.Grep(local()).run(search("flag ", ""), PAYMENTS);

    assertEquals(
        1,
        offsets(out).size(),
        "the declaration with a space after the name, and not the one with a"
            + " semicolon — "
            + out);
    assertTrue(out.contains("boolean flag = true;"), out);
  }

  /**
   * The cap is reported, and the remedy is not a next offset.
   *
   * <p><b>This is where {@code file_grep} and {@code file_read} deliberately part company.</b> A
   * read that stopped names the number to send back; {@code Found}'s javadoc records that a search
   * has no such number and why inventing one would walk the tree again per page. So the sentence
   * has to carry the cap without carrying a page two, and both halves are asserted: that it says it
   * stopped, and that it does not offer a continuation.
   */
  @Test
  void a_capped_search_says_so_and_offers_no_next_page() throws IOException {
    store.define("payments", repo, List.of());
    StringBuilder many = new StringBuilder();
    for (int at = 0; at < Needle.MAX_MATCHES + 20; at++) {
      many.append("a marker here\nand a line without one\n");
    }
    Files.writeString(repo.resolve("Many.txt"), many.toString());

    String out = new FileTools.Grep(local()).run(search("marker", ""), PAYMENTS);

    assertEquals(
        Needle.MAX_MATCHES,
        offsets(out).size(),
        "the allowance, and no more of it — " + out.substring(0, 400));
    assertTrue(out.contains("Stopped at " + Needle.MAX_MATCHES + " matches"), out);
    assertTrue(out.contains("no next page"), out);
    assertTrue(
        out.contains("search for something longer"),
        "the remedy is a narrower needle and a path, which is the whole of what is"
            + " available — "
            + out.substring(0, 400));
    assertFalse(
        out.contains("again with offset"),
        "and never file_read's continuation, which would send a model paging"
            + " through a search that cannot be paged — "
            + out.substring(0, 400));
  }

  @Test
  void a_search_that_found_everything_there_was_says_nothing_about_a_cap() throws IOException {
    sources();

    String out = new FileTools.Grep(local()).run(search("void ", ""), PAYMENTS);

    assertEquals(2, offsets(out).size(), out);
    assertFalse(
        out.contains("Stopped at"),
        "a complete search reported as capped sends a model narrowing a needle that"
            + " was already right — "
            + out);
  }

  /**
   * A cut line is marked on the match it happened to, and explained once.
   *
   * <p>{@code Needle.MAX_LINE_CHARS} is per match rather than per search, so it cannot stop
   * anything and is reported where it happened. The fixture carries a sentinel past the cap: a mark
   * with nothing behind it would pass on a rendering that marked every line.
   */
  @Test
  void a_line_cut_at_the_length_cap_is_marked_and_the_mark_is_explained() throws IOException {
    store.define("payments", repo, List.of());
    Files.writeString(
        repo.resolve("Wide.txt"),
        "a marker then "
            + "x".repeat(Needle.MAX_LINE_CHARS * 2)
            + " ZULU\n"
            + "a marker on a short line\n");

    String out = new FileTools.Grep(local()).run(search("marker", ""), PAYMENTS);

    assertTrue(out.contains("[cut]"), out);
    assertFalse(
        out.contains("ZULU"),
        "the tail of a long line does not reach the answer — " + out.length());
    assertTrue(
        out.contains("longer than " + Needle.MAX_LINE_CHARS + " characters"),
        "and the mark is explained rather than left to be guessed at — " + out);
    assertEquals(
        1,
        out.lines().filter(line -> line.endsWith("[cut]")).count(),
        "on the line it happened to and not on the short one beside it — " + out);
  }

  @Test
  void one_match_is_one_line_and_not_one_lines() throws IOException {
    sources();

    String out = new FileTools.Grep(local()).run(search("Beacon.lit", ""), PAYMENTS);

    assertTrue(out.contains("1 line contains"), out);
  }

  @Test
  void a_search_outside_every_root_is_a_tool_result_the_model_can_correct() throws IOException {
    sources();
    Path elsewhere = Files.writeString(outside.resolve("Secret.java"), "class Secret {}");

    String out =
        new FileTools.Grep(local())
            .run(search("class", "\"path\": \"" + elsewhere + "\""), PAYMENTS);

    assertTrue(out.contains(elsewhere.toString()), out);
    assertFalse(
        out.contains("class Secret"),
        "a refusal that quoted the line would be the read it refused — " + out);
  }

  @Test
  void a_relative_path_on_a_search_is_refused_as_it_is_on_a_read() {
    String out =
        new FileTools.Grep(local()).run(search("class", "\"path\": \"src/Main.java\""), PAYMENTS);

    assertTrue(out.contains("absolute"), out);
    assertTrue(out.contains("file_roots"), out);
  }

  @Test
  void a_missing_text_argument_is_a_tool_result_and_not_an_ending() {
    String out = new FileTools.Grep(local()).run("{}", PAYMENTS);

    assertTrue(out.contains("'text'"), out);
  }

  /**
   * A blank needle comes back as a sentence, and the run goes on.
   *
   * <p>{@code Needle}'s constructor refuses a blank with an {@code IllegalArgumentException}, which
   * shares no supertype with either of the two {@code run} catches — so without the tool's own
   * check this call would leave {@code run}, reach {@code JobRuntime.dependencyFailure} and end the
   * job as unavailable, for a mistake one turn could have corrected.
   */
  @Test
  void a_blank_needle_is_a_tool_result_and_never_the_end_of_the_run() {
    String out = new FileTools.Grep(local()).run(search("   ", ""), PAYMENTS);

    assertTrue(out.contains("'text'"), out);
    assertTrue(out.contains("every line"), out);
  }

  /**
   * A flag that is neither true nor false is refused rather than read as one of them.
   *
   * <p>Measured against Jackson 2.17.2, this project's version, and against the JDK: {@code
   * asBoolean()} on the text {@code "yes"} is false, {@code Boolean.parseBoolean("yes")} is false,
   * and both would run a case-sensitive search for a model that plainly asked for the opposite.
   * There is nothing in an answer to say which fold it used, so the mistake has to be a sentence.
   */
  @Test
  void an_ignore_case_that_is_neither_true_nor_false_is_a_tool_result() throws IOException {
    sources();

    String out =
        new FileTools.Grep(local()).run(search("BEACON", "\"ignore_case\": \"yes\""), PAYMENTS);

    assertTrue(out.contains("'ignore_case'"), out);
    assertTrue(out.contains("yes"), out);
    assertEquals(0, offsets(out).size(), "and no search ran on a flag nobody could read — " + out);
  }

  /**
   * The word, in the case a model shouts it in.
   *
   * <p>Measured against Jackson 2.17.2: {@code TextNode("TRUE").asBoolean()} returns <b>false</b>,
   * because that method accepts only the lower-case spelling. A tool that leaned on it would answer
   * the opposite of what was asked, silently, on an argument whose whole job is to change the
   * answer.
   */
  @Test
  void a_flag_sent_as_a_string_is_read_in_whatever_case_it_arrived() throws IOException {
    sources();

    String out =
        new FileTools.Grep(local()).run(search("BEACON", "\"ignore_case\": \"TRUE\""), PAYMENTS);

    assertEquals(2, offsets(out).size(), out);
  }

  @Test
  void a_grep_on_a_job_with_no_provider_at_all_is_told_that_rather_than_no_matches() {
    String out = new FileTools.Grep(over()).run(search("anything", ""), PAYMENTS);

    assertTrue(out.contains("no filesystem"), out);
  }

  @Test
  void a_grep_with_no_root_is_the_absence_sentence_and_never_no_matches() {
    String out = new FileTools.Grep(local()).run(search("anything", ""), PAYMENTS);

    assertTrue(out.contains("payments"), out);
    assertFalse(
        out.contains("The search ran"),
        "a job with nowhere to look has not searched anything — " + out);
  }

  @Test
  void a_provider_that_could_not_be_searched_is_named_beside_the_one_a_search_reached()
      throws IOException {
    sources();
    FileProvider laptop = new Fake("laptop").explaining("this workspace has moved");

    String out =
        new FileTools.Grep(over(new LocalProvider(store, PAYMENTS, grants), laptop))
            .run(search("void start", ""), PAYMENTS);

    assertTrue(out.contains("Main.java offset=5"), out);
    assertTrue(
        out.contains("laptop: this workspace has moved"),
        "a half that was never searched, reported beside the half that was — " + out);
    assertTrue(
        out.indexOf("laptop:") < out.indexOf("Main.java"),
        "and ahead of the matches, because the display cut takes the tail — " + out);
  }

  @Test
  void a_search_no_provider_could_run_never_reads_as_nothing_matched() {
    String out =
        new FileTools.Grep(over(new Fake("laptop").explaining("no workspace")))
            .run(search("anything", ""), PAYMENTS);

    assertTrue(out.contains("Nothing was searched"), out);
    assertTrue(out.contains("laptop: no workspace"), out);
  }

  /**
   * The notes survive an answer long enough to be cut.
   *
   * <p>Fifty matches at the line cap do not reach the display limit on their own, so this is driven
   * through a fake: a provider on another machine chooses its own path strings and nothing on this
   * side bounds them. That is the same reason {@code file_glob} and {@code file_roots} go through
   * the cut at all, and the comment in {@code file_glob}'s answer records what it cost when a note
   * went after the bulk instead of in front of it.
   */
  @Test
  void the_cap_note_survives_an_answer_long_enough_to_be_cut() {
    List<Found.Match> many = new ArrayList<>();
    for (int at = 0; at < Needle.MAX_MATCHES; at++) {
      many.add(
          new Found.Match("/laptop/" + "d".repeat(4000) + "/F.java", at, "a marker here", false));
    }

    String out =
        new FileTools.Grep(
                over(new Fake("laptop").covering(Path.of("/laptop")).finding(Found.of(many, true))))
            .run(search("marker", ""), PAYMENTS);

    assertTrue(
        out.startsWith("[Stopped at"),
        "the note is the first thing in the answer — " + out.substring(0, 200));
    assertTrue(out.contains("Cut off here"), out.substring(out.length() - 300));
  }

  @Test
  void a_grep_over_a_vanished_workspace_ends_the_run() throws IOException {
    store.define("payments", repo, List.of());
    Files.delete(repo);

    assertThrows(
        WorkspaceUnavailableException.class,
        () -> new FileTools.Grep(local()).run(search("anything", ""), PAYMENTS));
  }

  // --- file_edit, the whole file ----------------------------------------------------------

  @Test
  void a_write_inside_the_workspace_lands_on_disk_and_is_confirmed() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = repo.resolve("deep/New.java");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "content", "class New {}\n"), PAYMENTS);

    assertEquals("class New {}\n", Files.readString(file));
    assertTrue(out.contains(file.toString()), out);
    assertTrue(
        out.contains("Wrote 1 line (13 bytes) to "),
        "the confirmation says how much was written, which is the one thing a"
            + " model cannot check for itself without another turn; asserting"
            + " only on the path would pass a refusal, since every refusal"
            + " names the path too — "
            + out);
    // In lines, as a read counts them, since 2026-09-30: the count a later
    // file_stat or file_read gives, where characters matched nothing a model
    // could send back.
    // The bare number is not enough, and the first draft asserted it: the
    // temp directory's name is random digits, so contains("13") passed the
    // mutant that drops the count — on some runs. A green suite that
    // depends on which directory @TempDir picked is worse than a red one.
  }

  @Test
  void content_is_written_exactly_as_it_was_sent() throws IOException {
    // ToolArguments.requireText strips, which is right for a path and wrong
    // for a file: stripping here would silently drop the trailing newline
    // from every file an agent writes, and no test of the tool's happy path
    // would notice.
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = repo.resolve("edges.txt");

    new FileTools.Edit(local())
        .run(json("path", file.toString(), "content", "  padded  \n"), PAYMENTS);

    assertEquals("  padded  \n", Files.readString(file));
  }

  @Test
  void an_empty_file_is_an_ordinary_thing_to_write() throws IOException {
    // requireText refuses blank. Truncating a file to nothing is a real
    // edit, and refusing it would be the tool inventing a rule the
    // filesystem does not have.
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("was.txt"), "something");
    ProviderRouter router = local();
    FileTools.Reads reads = new FileTools.Reads();
    new FileTools.Read(router, reads).run(json("path", file.toString()), PAYMENTS);

    new FileTools.Edit(router, reads).run(json("path", file.toString(), "content", ""), PAYMENTS);

    assertEquals("", Files.readString(file));
  }

  @Test
  void a_read_only_agent_is_refused_and_told_which_scope_it_holds() {
    store.define("payments", repo, List.of());

    String out =
        new FileTools.Edit(local())
            .run(json("path", repo.resolve("New.java").toString(), "content", "x"), PAYMENTS);

    assertTrue(out.contains("read-only"), out);
  }

  @Test
  void a_write_outside_every_root_is_refused_and_leaves_nothing_behind() {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path target = outside.resolve("planted.txt");

    String out =
        new FileTools.Edit(local()).run(json("path", target.toString(), "content", "x"), PAYMENTS);

    assertTrue(out.contains("outside"), out);
    assertFalse(
        Files.exists(target),
        "the refusal is the whole of what happened: a tool that refused after"
            + " writing would be a leash with a hole in it");
  }

  @Test
  void a_null_content_is_refused_rather_than_writing_the_word_null() throws IOException {
    // requireExactText checks isTextual and not asText, for requireText's
    // own measured reason: NullNode.asText() returns the four-character
    // string "null". A tool that read {"content": null} the other way would
    // write that word into the file and confirm it in good faith. Found by
    // the mutation sweep — the guard was there and nothing held it, which is
    // the shape this project keeps meeting.
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = repo.resolve("nulled.txt");

    String out =
        new FileTools.Edit(local())
            .run("{\"path\": \"" + file + "\", \"content\": null}", PAYMENTS);

    assertTrue(out.contains("content"), out);
    assertFalse(Files.exists(file), "no file was written at all");
  }

  @Test
  void a_missing_content_argument_is_a_tool_result() {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);

    String out =
        new FileTools.Edit(local())
            .run(json("path", repo.resolve("New.java").toString()), PAYMENTS);

    assertTrue(out.contains("content"), out);
  }

  // --- file_edit, one piece of text ------------------------------------------

  @Test
  void an_edit_replaces_one_occurrence_and_leaves_every_other_byte_alone() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Crlf.java"), "int a = 1;\r\nint b = 2;");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "old", "b = 2", "new", "b = 3"), PAYMENTS);

    assertEquals(
        "int a = 1;\r\nint b = 3;",
        Files.readString(file),
        "the CRLF and the missing last newline are the file's, and a read never"
            + " carries them -- which is why the edit is applied here");
    assertTrue(out.contains(file.toString()), out);
  }

  @Test
  void an_edit_whose_text_occurs_twice_changes_nothing_and_says_how_many() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Twice.java"), "x();\nx();\n");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "old", "x();", "new", "y();"), PAYMENTS);

    assertTrue(out.contains("2 times"), out);
    assertEquals("x();\nx();\n", Files.readString(file));
  }

  @Test
  void an_edit_whose_text_is_absent_is_refused() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("A.java"), "class A {}\n");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "old", "class B", "new", "class C"), PAYMENTS);

    assertTrue(out.contains("not in"), out);
    assertEquals("class A {}\n", Files.readString(file));
  }

  @Test
  void an_edit_needs_the_file_to_exist() {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = repo.resolve("Nowhere.java");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "old", "a", "new", "b"), PAYMENTS);

    assertTrue(out.contains("no file at"), out);
    assertFalse(Files.exists(file));
  }

  @Test
  void both_shapes_at_once_or_neither_is_refused_naming_the_two() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("A.java"), "a");

    String both =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "content", "b", "old", "a", "new", "b"), PAYMENTS);
    String neither = new FileTools.Edit(local()).run(json("path", file.toString()), PAYMENTS);
    String half =
        new FileTools.Edit(local()).run(json("path", file.toString(), "old", "a"), PAYMENTS);

    for (String out : List.of(both, neither, half)) {
      assertTrue(out.contains("\"old\"") && out.contains("\"content\""), out);
    }
    assertEquals("a", Files.readString(file));
  }

  @Test
  void the_new_text_may_be_empty() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("A.java"), "keep drop keep");

    new FileTools.Edit(local())
        .run(json("path", file.toString(), "old", " drop", "new", ""), PAYMENTS);

    assertEquals("keep keep", Files.readString(file));
  }

  // --- file_edit, what the answer shows ------------------------------------------

  /** {@code l0\n} through {@code l<n-1>\n}. */
  private static String lettered(int n) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < n; i++) {
      out.append('l').append(i).append('\n');
    }
    return out.toString();
  }

  /**
   * Eleven of the 27 misses in the run this was measured on were a model editing from its copy of a
   * file from before its own last edit, because a success said one sentence. It now shows the file
   * as it is there.
   */
  @Test
  void an_edit_shows_the_changed_lines_numbered_as_file_read_numbers_them() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("A.txt"), lettered(20));
    ProviderRouter router = local();

    String out =
        new FileTools.Edit(router)
            .run(json("path", file.toString(), "old", "l10\n", "new", "A\nB\n"), PAYMENTS);
    String read =
        new FileTools.Read(router)
            .run("{\"path\": \"" + file + "\", \"offset\": 7, \"limit\": 8}", PAYMENTS);

    assertEquals(
        "Replaced the one occurrence of the text in "
            + file
            + ". The new text is on"
            + " lines 10 to 11 now, shown with the 3 lines either side:\n"
            + "[Lines 7 to 14 of 21, counting from 0 as offset does.]\n"
            + "l7\nl8\nl9\nA\nB\nl11\nl12\nl13",
        out);
    assertTrue(read.startsWith("[Lines 7 to 14 of 21, counting from 0 as offset does."), read);
    assertTrue(read.endsWith("\n\nl7\nl8\nl9\nA\nB\nl11\nl12\nl13"), read);
    assertEquals(ToolLines.OK, ToolLines.outcome(FileTools.EDIT_NAME, out));
  }

  @Test
  void a_long_replacement_is_shown_by_its_first_and_last_lines() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("A.txt"), lettered(20));

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "old", "l10\n", "new", "n\n".repeat(100)), PAYMENTS);

    assertTrue(out.contains("\n[… 66 lines not shown: 27 to 92 …]\n"), out);
    long shown =
        out.lines().filter(line -> !line.startsWith("[") && !line.startsWith("Replaced ")).count();
    assertEquals(Replacement.MAX_SHOWN_LINES, shown, out);
  }

  @Test
  void a_whole_file_write_answers_with_its_line_and_byte_count_only() {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = repo.resolve("New.txt");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "content", "a\nb\nc\n"), PAYMENTS);

    // The file side's own count of what it wrote, lines and bytes: the state
    // the file is in now, from the record and not from what was sent.
    assertEquals("Wrote 3 lines (6 bytes) to " + file + ".", out);
    assertEquals(ToolLines.OK, ToolLines.outcome(FileTools.EDIT_NAME, out));
  }

  @Test
  void a_miss_by_indentation_is_named_and_shown_and_nothing_is_changed() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("A.java"), "class A {\n\tint x = 1;\n}\n");

    String out =
        new FileTools.Edit(local())
            .run(
                json(
                    "path",
                    file.toString(),
                    "old",
                    "class A {\n    int x = 1;",
                    "new",
                    "class A {\n    int x = 2;"),
                PAYMENTS);

    assertTrue(out.startsWith("the text to replace is not in " + file), out);
    assertTrue(
        out.endsWith(
            "`old` differs from the file only in whitespace/indentation. The"
                + " file's text there:\n[Lines 0 to 1 of 3, counting from 0 as offset does.]\n"
                + "class A {\n\tint x = 1;"),
        out);
    assertEquals("class A {\n\tint x = 1;\n}\n", Files.readString(file));
    assertEquals(ToolLines.REFUSED, ToolLines.outcome(FileTools.EDIT_NAME, out));
  }

  @Test
  void a_look_alike_character_is_named() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("A.md"), "a well-known fact\n");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "old", "well‑known", "new", "famous"), PAYMENTS);

    assertTrue(out.contains("`old` has U+2011 NON-BREAKING HYPHEN where the file has '-'."), out);
    assertEquals("a well-known fact\n", Files.readString(file));
  }

  @Test
  void a_paraphrase_is_shown_the_closest_lines_and_an_unrelated_text_nothing() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file =
        Files.writeString(repo.resolve("A.java"), "a();\nif (ready) {\n    go();\n}\nb();\n");

    String near =
        new FileTools.Edit(local())
            .run(
                json("path", file.toString(), "old", "if (ready) {\n    start();\n}", "new", "x"),
                PAYMENTS);
    String far =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "old", "zzz", "new", "x"), PAYMENTS);

    assertTrue(
        near.endsWith(
            "The closest lines in the file now are:\n"
                + "[Lines 1 to 3 of 5, counting from 0 as offset does.]\n"
                + "if (ready) {\n    go();\n}"),
        near);
    assertEquals(
        "the text to replace is not in "
            + file
            + "; it must match the file exactly,"
            + " spaces and line breaks included — read the file again and copy it",
        far);
  }

  @Test
  void an_edit_of_no_file_says_how_to_create_it() {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = repo.resolve("Nowhere.java");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "old", "a", "new", "b"), PAYMENTS);

    assertEquals(
        "there is no file at "
            + file
            + ", so nothing was edited; to create it,"
            + " send {\"path\", \"content\"} with the whole file",
        out);
    assertEquals(ToolLines.NOT_FOUND, ToolLines.outcome(FileTools.EDIT_NAME, out));
  }

  @Test
  void an_edit_through_a_client_with_nothing_to_show_says_the_one_sentence() {
    Path file = Path.of("/laptop/A.java");

    String out =
        new FileTools.Edit(
                over(new Fake("remote").covering(Path.of("/laptop")).editing((String) null)))
            .run(json("path", file.toString(), "old", "a", "new", "b"), PAYMENTS);

    assertEquals("Replaced the one occurrence of the text in " + file + ".", out);
  }

  @Test
  void a_view_from_the_far_side_is_held_to_the_display_limit() {
    Path file = Path.of("/laptop/A.java");
    String flood = "x".repeat(FileTools.MAX_DISPLAY_CHARS * 2);

    String out =
        new FileTools.Edit(over(new Fake("remote").covering(Path.of("/laptop")).editing(flood)))
            .run(json("path", file.toString(), "old", "a", "new", "b"), PAYMENTS);

    assertTrue(
        out.startsWith("Replaced the one occurrence of the text in " + file + ". x"),
        out.substring(0, 100));
    assertTrue(out.length() < FileTools.MAX_DISPLAY_CHARS + 500, "length " + out.length());
    assertTrue(out.contains("[Cut off here: " + FileTools.MAX_DISPLAY_CHARS), "cut, and says so");
  }

  /**
   * An old client's words are shown as they came, and never read: only the facts say an edit showed
   * every line of the file, so an old client's view that looks like the whole file does not let a
   * whole write replace it.
   */
  @Test
  void read_before_replace_keys_off_the_facts_and_never_off_an_old_client_s_words() {
    Path file = Path.of("/laptop/A.java");
    String looksWhole =
        "The new text is on line 1 now, shown with the 3 lines either side:\n"
            + "[The whole file, lines 0 to 2 of 3, counting from 0 as offset does.]\na\nB\nc";
    Fake old = new Fake("remote").covering(Path.of("/laptop")).editing(looksWhole);
    FileTools.Reads oldReads = new FileTools.Reads();
    Fake current =
        new Fake("remote")
            .covering(Path.of("/laptop"))
            .editing(
                io.aeyer.plowshare.server.files.Changed.of(
                    Replacement.edit("a\nb\nc\n", "b", "B").result(file.toString())));
    FileTools.Reads currentReads = new FileTools.Reads();

    String shown =
        FileTools.of(FileTools.EDIT_NAME, over(old), oldReads)
            .run(json("path", file.toString(), "old", "b", "new", "B"), PAYMENTS);
    FileTools.of(FileTools.EDIT_NAME, over(old), oldReads)
        .run(json("path", file.toString(), "content", "whole\n"), PAYMENTS);
    String worded =
        FileTools.of(FileTools.EDIT_NAME, over(current), currentReads)
            .run(json("path", file.toString(), "old", "b", "new", "B"), PAYMENTS);
    FileTools.of(FileTools.EDIT_NAME, over(current), currentReads)
        .run(json("path", file.toString(), "content", "whole\n"), PAYMENTS);

    assertEquals(
        "Replaced the one occurrence of the text in " + file + ". " + looksWhole,
        shown,
        "an old client's view, passed through");
    assertEquals(List.of("create"), old.writes, "its header is not a read");
    assertEquals(
        "Replaced the one occurrence of the text in " + file + ". " + looksWhole,
        worded,
        "the same words, written here from the facts");
    assertEquals(List.of("write"), current.writes, "the facts said every line, uncut");
  }

  // --- read before overwrite --------------------------------------------------

  /**
   * The lines an edit shows are not the file, so they do not let a whole write replace it — unless
   * they are every line of it.
   */
  @Test
  void the_lines_an_edit_shows_are_not_a_read_of_the_file() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Long.txt"), lettered(20));
    ProviderRouter router = local();
    FileTools.Reads reads = new FileTools.Reads();

    FileTools.of(FileTools.EDIT_NAME, router, reads)
        .run(json("path", file.toString(), "old", "l10\n", "new", "ten\n"), PAYMENTS);
    String out =
        FileTools.of(FileTools.EDIT_NAME, router, reads)
            .run(json("path", file.toString(), "content", "gone\n"), PAYMENTS);

    assertTrue(out.contains("already exists"), out);
    assertTrue(Files.readString(file).startsWith("l0\n"));
  }

  @Test
  void an_edit_that_showed_the_whole_file_lets_it_be_replaced() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Short.txt"), "a\nb\nc\n");
    ProviderRouter router = local();
    FileTools.Reads reads = new FileTools.Reads();

    String edited =
        FileTools.of(FileTools.EDIT_NAME, router, reads)
            .run(json("path", file.toString(), "old", "b", "new", "B"), PAYMENTS);
    FileTools.of(FileTools.EDIT_NAME, router, reads)
        .run(json("path", file.toString(), "content", "whole\n"), PAYMENTS);

    assertTrue(edited.contains("[The whole file, lines 0 to 2 of 3,"), edited);
    assertEquals("whole\n", Files.readString(file));
  }

  @Test
  void a_file_read_and_then_edited_is_still_read() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Long.txt"), lettered(20));
    ProviderRouter router = local();
    FileTools.Reads reads = new FileTools.Reads();

    FileTools.of(FileTools.READ_NAME, router, reads).run(json("path", file.toString()), PAYMENTS);
    FileTools.of(FileTools.EDIT_NAME, router, reads)
        .run(json("path", file.toString(), "old", "l10\n", "new", "ten\n"), PAYMENTS);
    FileTools.of(FileTools.EDIT_NAME, router, reads)
        .run(json("path", file.toString(), "content", "whole\n"), PAYMENTS);

    assertEquals("whole\n", Files.readString(file));
  }

  @Test
  void a_whole_file_write_over_a_file_this_run_never_read_is_refused() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Theirs.java"), "a person's work\n");

    String out =
        new FileTools.Edit(local())
            .run(json("path", file.toString(), "content", "gone\n"), PAYMENTS);

    assertTrue(out.contains("already exists"), out);
    assertEquals("a person's work\n", Files.readString(file));
  }

  @Test
  void reading_it_first_in_the_same_run_lets_the_whole_file_be_replaced() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Seen.java"), "old\n");
    ProviderRouter router = local();
    FileTools.Reads reads = new FileTools.Reads();

    FileTools.of(FileTools.READ_NAME, router, reads).run(json("path", file.toString()), PAYMENTS);
    FileTools.of(FileTools.EDIT_NAME, router, reads)
        .run(json("path", file.toString(), "content", "new\n"), PAYMENTS);

    assertEquals("new\n", Files.readString(file));
  }

  @Test
  void a_stat_or_a_grep_is_not_a_read() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Glimpsed.java"), "old\n");
    ProviderRouter router = local();
    FileTools.Reads reads = new FileTools.Reads();

    FileTools.of(FileTools.STAT_NAME, router, reads).run(json("path", file.toString()), PAYMENTS);
    FileTools.of(FileTools.GREP_NAME, router, reads)
        .run(json("path", file.toString(), "text", "old"), PAYMENTS);
    String out =
        FileTools.of(FileTools.EDIT_NAME, router, reads)
            .run(json("path", file.toString(), "content", "new\n"), PAYMENTS);

    assertTrue(out.contains("already exists"), out);
    assertEquals("old\n", Files.readString(file));
  }

  @Test
  void a_file_this_run_wrote_can_be_written_again() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = repo.resolve("Mine.java");
    ProviderRouter router = local();
    FileTools.Reads reads = new FileTools.Reads();
    AgentTool edit = FileTools.of(FileTools.EDIT_NAME, router, reads);

    edit.run(json("path", file.toString(), "content", "first\n"), PAYMENTS);
    edit.run(json("path", file.toString(), "content", "second\n"), PAYMENTS);

    assertEquals("second\n", Files.readString(file));
  }

  // --- file_delete ---------------------------------------------------------------

  @Test
  void a_delete_removes_one_file_and_says_so() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(repo.resolve("Old.java"), "x\ny\n");

    String out = new FileTools.Delete(local()).run(json("path", file.toString()), PAYMENTS);

    assertFalse(Files.exists(file));
    // What went, from the file side's record of it: a model told only
    // "Deleted" cannot tell a stub from the module it meant to keep.
    assertEquals("Deleted " + file + ", which held 2 lines (4 bytes); nothing is there now.", out);
    assertEquals(ToolLines.OK, ToolLines.outcome(FileTools.DELETE_NAME, out));
  }

  @Test
  void a_delete_refuses_a_directory_a_link_and_nothing() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path dir = Files.createDirectory(repo.resolve("pkg"));
    Path target = Files.writeString(repo.resolve("Target.java"), "x");
    Path link = Files.createSymbolicLink(repo.resolve("Link.java"), target);

    String directory = new FileTools.Delete(local()).run(json("path", dir.toString()), PAYMENTS);
    String linked = new FileTools.Delete(local()).run(json("path", link.toString()), PAYMENTS);
    String absent =
        new FileTools.Delete(local())
            .run(json("path", repo.resolve("Gone.java").toString()), PAYMENTS);

    // Said to be a directory, as the clients always said it: one wording.
    assertTrue(directory.contains("is a directory, so it cannot be deleted"), directory);
    assertTrue(linked.contains("is a link"), linked);
    assertTrue(absent.contains("no file at"), absent);
    assertTrue(Files.isDirectory(dir));
    assertTrue(Files.exists(target), "the file a link points at is not the file named");
    assertTrue(Files.isSymbolicLink(link));
  }

  @Test
  void a_read_only_agent_cannot_delete() throws IOException {
    store.define("payments", repo, List.of());
    Path file = Files.writeString(repo.resolve("Kept.java"), "x");

    String out = new FileTools.Delete(local()).run(json("path", file.toString()), PAYMENTS);

    assertTrue(out.contains("read-only"), out);
    assertTrue(Files.exists(file));
  }

  @Test
  void a_delete_outside_every_root_is_refused() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path file = Files.writeString(outside.resolve("theirs.txt"), "x");

    String out = new FileTools.Delete(local()).run(json("path", file.toString()), PAYMENTS);

    assertTrue(out.contains("outside"), out);
    assertTrue(Files.exists(file));
  }

  // --- file_move -----------------------------------------------------------------

  @Test
  void a_move_renames_one_file_creating_the_directories_above_it() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path from = Files.writeString(repo.resolve("Old.java"), "body\n");
    Path to = repo.resolve("moved/deep/New.java");

    String out =
        new FileTools.Move(local())
            .run(json("path", from.toString(), "to", to.toString()), PAYMENTS);

    assertFalse(Files.exists(from));
    assertEquals("body\n", Files.readString(to));
    assertEquals(
        "Moved " + from + " to " + to + " (5 bytes); nothing is at " + from + " now.", out);
    assertEquals(ToolLines.OK, ToolLines.outcome(FileTools.MOVE_NAME, out));
  }

  @Test
  void a_move_never_replaces_a_file() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path from = Files.writeString(repo.resolve("A.java"), "a");
    Path to = Files.writeString(repo.resolve("B.java"), "b");

    String out =
        new FileTools.Move(local())
            .run(json("path", from.toString(), "to", to.toString()), PAYMENTS);

    assertTrue(out.contains("already exists"), out);
    assertEquals("a", Files.readString(from));
    assertEquals("b", Files.readString(to));
  }

  @Test
  void a_move_out_of_the_fence_is_refused_on_either_end() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path inside = Files.writeString(repo.resolve("In.java"), "in");
    Path theirs = Files.writeString(outside.resolve("out.txt"), "out");

    String out =
        new FileTools.Move(local())
            .run(
                json("path", inside.toString(), "to", outside.resolve("stolen.java").toString()),
                PAYMENTS);
    String in =
        new FileTools.Move(local())
            .run(
                json("path", theirs.toString(), "to", repo.resolve("taken.txt").toString()),
                PAYMENTS);

    assertTrue(out.contains("outside"), out);
    assertTrue(in.contains("outside"), in);
    assertTrue(Files.exists(inside));
    assertTrue(Files.exists(theirs));
    assertFalse(Files.exists(outside.resolve("stolen.java")));
  }

  @Test
  void a_move_between_two_filesystems_is_refused_naming_both() {
    Path laptop = Path.of("/laptop/repo");
    Path server = Path.of("/srv/repo");
    ProviderRouter two =
        over(new Fake("laptop").covering(laptop), new Fake("server").covering(server));

    String out =
        new FileTools.Move(two)
            .run(
                json(
                    "path",
                    laptop.resolve("A.java").toString(),
                    "to",
                    server.resolve("A.java").toString()),
                PAYMENTS);

    assertTrue(out.contains("laptop") && out.contains("server"), out);
    assertTrue(out.contains("one filesystem"), out);
  }

  @Test
  void a_moved_file_this_run_read_can_be_replaced_at_its_new_path() throws IOException {
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
    Path from = Files.writeString(repo.resolve("Old.java"), "old\n");
    Path to = repo.resolve("New.java");
    ProviderRouter router = local();
    FileTools.Reads reads = new FileTools.Reads();

    FileTools.of(FileTools.READ_NAME, router, reads).run(json("path", from.toString()), PAYMENTS);
    FileTools.of(FileTools.MOVE_NAME, router, reads)
        .run(json("path", from.toString(), "to", to.toString()), PAYMENTS);
    FileTools.of(FileTools.EDIT_NAME, router, reads)
        .run(json("path", to.toString(), "content", "new\n"), PAYMENTS);

    assertEquals("new\n", Files.readString(to));
  }

  // --- file_roots ----------------------------------------------------------

  @Test
  void file_roots_lists_what_this_job_can_see() {
    store.define("payments", repo, List.of());

    String out = new FileTools.Roots(local()).run("", PAYMENTS);

    assertTrue(out.contains(repo.toString()), out);
  }

  @Test
  void file_roots_reaches_the_absence_sentence_rather_than_saying_you_have_no_roots() {
    // The gap task 3 left open on purpose. A provider can have no root for
    // several distinct reasons, each a different person's fix, and
    // LocalProvider's class javadoc is where they are enumerated — with the
    // qualifier that matters here, that one of them raises rather than
    // answering empty and so never reaches this tool. Rendering "you have no
    // roots" for the rest is the collapse Excalibur split NO_ROOTS and
    // NO_WORKSPACE apart to stop.
    //
    // The list is not copied here, and it was: this comment said "roots()
    // answers an empty list in five states" and enumerated all five, which
    // is the measured-false version — in the fifth, roots() does not answer
    // at all. Four answer empty, out of five in which there is no root.
    String out = new FileTools.Roots(local()).run("", PAYMENTS);

    assertTrue(out.contains("no workspace is defined for project 'payments'"), out);
  }

  @Test
  void file_roots_in_the_global_tier_says_which_state_that_is() {
    // A second of the states LocalProvider enumerates, so that the test
    // above cannot be satisfied by one hardcoded sentence.
    String out = new FileTools.Roots(local()).run("", Home.global());

    assertTrue(out.contains("global tier"), out);
    assertFalse(out.contains("no workspace is defined"), out);
  }

  @Test
  void file_roots_names_each_provider_so_two_machines_are_told_apart() {
    Fake laptop = new Fake("remote").covering(Path.of("/laptop/repo"));
    Fake server = new Fake("local").covering(Path.of("/srv/repo"));

    String out = new FileTools.Roots(over(laptop, server)).run("", PAYMENTS);

    assertTrue(out.contains("remote") && out.contains("local"), out);
    assertTrue(out.contains("/laptop/repo") && out.contains("/srv/repo"), out);
  }

  @Test
  void file_roots_over_a_vanished_workspace_ends_the_run() throws IOException {
    // Not an empty list dressed as an answer: "you can see nothing" about a
    // workspace that has been deleted is the sentence that sends an agent
    // to do something else for the rest of its turns.
    store.define("payments", repo, List.of());
    Files.delete(repo);

    assertThrows(
        WorkspaceUnavailableException.class, () -> new FileTools.Roots(local()).run("", PAYMENTS));
  }

  @Test
  void file_roots_takes_no_arguments_and_still_refuses_ones_that_are_not_an_object() {
    // A model that sends an array has misunderstood the schema, and saying
    // so costs one turn where silently ignoring it costs the turn plus
    // whatever it concludes from an answer to a question it did not ask.
    String out = new FileTools.Roots(local()).run("[1, 2]", PAYMENTS);

    assertTrue(out.contains("file_roots"), out);
    assertTrue(out.contains("object"), out);
  }

  @Test
  void a_job_with_no_provider_at_all_is_told_that_rather_than_handed_a_blank() {
    String out = new FileTools.Roots(over()).run("", PAYMENTS);

    assertFalse(out.isBlank());
    assertTrue(out.contains("no filesystem"), out);
  }

  // --- the fake ------------------------------------------------------------

  /**
   * A provider for the rules that need two of them, which no single {@link LocalProvider} can be
   * put into.
   */
  private static final class Fake implements FileProvider {

    private final String name;
    private List<Path> roots = List.of();
    private List<Path> hits = List.of();
    private Found found = Found.of(List.of(), false);
    private Span window;
    private RuntimeException globFail;
    private boolean edits;
    private io.aeyer.plowshare.server.files.Changed edited;

    /** Which kind of whole-file change was asked of this fake, in order. */
    private final List<String> writes = new ArrayList<>();

    Fake(String name) {
      this.name = name;
    }

    Fake covering(Path... paths) {
      this.roots = List.of(paths);
      return this;
    }

    Fake matching(Path... paths) {
      this.roots = List.of(Path.of("/laptop"));
      this.hits = List.of(paths);
      return this;
    }

    Fake explaining(String absence) {
      this.globFail = new WorkspaceRefusedException(absence);
      return this;
    }

    Fake finding(Found found) {
      this.found = found;
      return this;
    }

    /**
     * The span this provider hands back for any read, whatever window is asked for.
     *
     * <p><b>Whatever window is asked for is the point.</b> A local provider cannot produce a span
     * wider than {@link Window#MAX_WINDOW_BYTES} — its cut refuses the one line that used to get
     * past — so the only thing left that can is a client on the far side of a wire, built against
     * another release or simply wrong, whose reply {@code RemoteProvider} passes through uncut.
     * This is that client.
     */
    Fake returning(Span span) {
      this.window = span;
      return this;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<Path> roots() {
      return roots;
    }

    @Override
    public Span read(Path path, Window asked) {
      if (window == null) {
        throw new UnsupportedOperationException(
            "this fake was not given a span to"
                + " return; call returning(...) if a read is meant to reach it");
      }
      // `asked` is deliberately ignored rather than applied. A fake that
      // re-cut here would be a second correct provider and could not
      // express the thing it exists for, which is a far side that did not.
      return window;
    }

    @Override
    public Span stat(Path path) {
      throw new UnsupportedOperationException("no test here stats through a fake");
    }

    @Override
    public List<Path> glob(String pattern) {
      if (globFail != null) {
        throw globFail;
      }
      return new ArrayList<>(hits);
    }

    @Override
    public Found grep(Needle needle, Path path) {
      // The same failure a glob gets, from the same field: a provider that
      // cannot be searched cannot be searched either way, and two fields
      // would let a fake be refused for one and not the other.
      if (globFail != null) {
        throw globFail;
      }
      return found;
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed write(Path path, String content) {
      if (!edits) {
        throw new UnsupportedOperationException("no test here writes through a fake");
      }
      writes.add("write");
      return io.aeyer.plowshare.server.files.Changed.fromOldClient(null);
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed create(Path path, String content) {
      if (!edits) {
        throw new UnsupportedOperationException("no test here writes through a fake");
      }
      writes.add("create");
      return io.aeyer.plowshare.server.files.Changed.fromOldClient(null);
    }

    /**
     * What an edit through this fake hands back: an old client's words — a client built before the
     * view answers with none, and a far side can hand back anything.
     */
    Fake editing(String view) {
      return editing(io.aeyer.plowshare.server.files.Changed.fromOldClient(view));
    }

    /** What an edit through this fake hands back, as a current client's facts or not. */
    Fake editing(io.aeyer.plowshare.server.files.Changed answer) {
      this.edited = answer;
      this.edits = true;
      return this;
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed edit(Path path, String old, String replacement) {
      if (!edits) {
        throw new UnsupportedOperationException("no test here edits through a fake");
      }
      return edited;
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed delete(Path path) {
      throw new UnsupportedOperationException("no test here deletes through a fake");
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed move(Path from, Path to) {
      throw new UnsupportedOperationException(
          "a move between two fakes is refused before" + " either is asked");
    }

    @Override
    public io.aeyer.plowshare.protocol.CommandRunner.Outcome run(
        Path cwd,
        List<String> argv,
        io.aeyer.plowshare.protocol.EnvironmentFile.Side side,
        java.time.Duration timeout,
        java.util.function.BooleanSupplier cancelled) {
      throw new UnsupportedOperationException("no test here runs a command through a fake");
    }
  }
}
