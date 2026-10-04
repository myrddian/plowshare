package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.files.ClientEnforcer;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * The doubled enforcement at the search: one tree, one needle, two halves of a wire, one {@link
 * Found}.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>{@code WindowAgreementTest}'s reason, at the other tool. Every javadoc in this slice says that
 * {@link Needle} is in {@code plowshare-protocol} so that a remote search and a local one cannot
 * disagree — {@code FileProvider.grep} says both implementations match with it and neither writes
 * the comparison out, {@code LocalProvider} and {@code ClientEnforcer} each say it about
 * themselves. None of that is a test. Each half is measured against {@code Needle} inside its own
 * module, and the two could each be right about {@code Needle} and still be reached through code
 * that walked, skipped or capped differently before {@code find} ever ran — <b>which is precisely
 * where the two halves have their own code</b>, since reading a file and choosing which files to
 * read is what an implementation keeps.
 *
 * <h2>The fixtures have to be able to express a disagreement</h2>
 *
 * <p>The windowing slice's lesson, applied before the mistake rather than after: a mutant that
 * replaced {@code Window.cut} with a hand-rolled {@code subList} passed 45 tests, because every
 * fixture had short lines and the byte ceiling never fired. So the tree here is built so that
 * <b>every branch on which the two halves could differ is reached</b>:
 *
 * <ul>
 *   <li><b>{@link #much}</b> and its siblings hold more matches than {@link Needle#MAX_MATCHES} and
 *       hold them <em>across several files</em>, so a half that capped per file, or capped at a
 *       different number, or walked the tree in a different order, returns a different set;
 *   <li><b>{@link #bundle}</b> has one line far past {@link Needle#MAX_LINE_CHARS}, so a half that
 *       did not truncate returns a different line and a different {@code truncated} flag;
 *   <li><b>{@link #outside}</b> is reached by a symlink from inside the tree, so a half that walked
 *       out of the workspace returns a line neither may read. <b>Two guards stand behind that
 *       one</b> and this file cannot isolate either — {@link
 *       #no_line_from_outside_the_tree_is_in_either_answer} says which, and says where the
 *       containment filter's own instrument lives;
 *   <li><b>{@link #binary}</b> is not UTF-8, so a half that refused instead of skipping returns a
 *       refusal where the other returns matches;
 *   <li>and one search folds case, which is the branch where a machine's default locale could
 *       otherwise get in — {@code NeedleTest} measures the Turkish fold that makes that a real
 *       difference rather than a theoretical one.
 * </ul>
 *
 * <p>The sweep asserts it reached both of {@link Found#stoppedBy()}'s values and both states of
 * {@code truncated}, rather than trusting that the searches it lists reach them: a fixture that
 * quietly stopped capping would leave every assertion here passing and this file measuring what
 * {@code NeedleTest} already measures.
 *
 * <h2>What this file deliberately does not assert</h2>
 *
 * <p><b>That either half can read a file the other cannot.</b> {@code LocalProvider.MAX_FILE_BYTES}
 * and {@code ClientEnforcer.MAX_FILE_BYTES} are each machine's own by design — a laptop and a
 * server may spend different amounts of their own memory — so a tree holding a file between two
 * different ceilings is a tree the two would legitimately search differently. That is a property of
 * the architecture rather than a defect, and a fixture asserting agreement across it would be
 * asserting the opposite of what the design says.
 *
 * <h2>Why there is a database in a test about matching</h2>
 *
 * <p>{@code WindowAgreementTest}'s answer, unchanged: {@link LocalProvider} cannot be built without
 * one, because its leash comes from {@link ProjectStore}, which is a final class over a {@code
 * JdbcTemplate} with no seam to stub.
 */
@Tag("full-db")
@Testcontainers
class GrepAgreementTest {

  /**
   * The pgvector image, as its neighbours use: V1's first line is CREATE EXTENSION vector and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String PROJECT = "payments";

  /**
   * The word every file in the tree is built around, so that the fixtures differ in the dimension
   * under test and in nothing else.
   */
  private static final String WANTED = "workspace";

  private static JdbcTemplate jdbc;

  @TempDir Path tmp;

  /**
   * The one tree both halves are pointed at. Real-pathed, because the server canonicalises its
   * roots and this host's temp directory is reached through a symlink — an expectation written as
   * {@code @TempDir} spells it would be about this host and not about either provider.
   */
  private Path repo;

  /**
   * More matches than the allowance, spread over more than one file so that walk order is part of
   * the answer.
   */
  private Path much;

  /** One line far past the line allowance. */
  private Path bundle;

  /** A file neither half may read, reachable from inside the tree only through a link. */
  private Path outside;

  /** Bytes that are not UTF-8, with the needle among them. */
  private Path binary;

  private LocalProvider local;
  private ClientEnforcer client;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void twoHalvesOverOneTree() throws IOException {
    // CASCADE since V14: `memories` and `conversations` reference this
    // table now, so Postgres refuses a plain TRUNCATE of it whether or not
    // they hold anything. Nothing here holds a memory or a conversation.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, projects CASCADE");
    repo = Files.createDirectory(tmp.resolve("repo")).toRealPath();
    Path elsewhere = Files.createDirectory(tmp.resolve("elsewhere"));
    Path server = Files.createDirectory(tmp.resolve("srv"));
    Path configFile = Files.writeString(server.resolve("plowshare.yml"), "a fixture, not a key");
    Path samplingDir = Files.createDirectory(server.resolve("profiles"));

    Files.createDirectory(repo.resolve("src"));
    write("notes.md", "one", "the " + WANTED + " is here", "three");
    write("src/A.java", "class A {}", "// the " + WANTED + " again");
    write("src/quiet.txt", "nothing anybody asked about");
    write("SHOUTING.md", WANTED.toUpperCase(java.util.Locale.ROOT) + " in capitals");

    // Split across two files on purpose: a half that gave each file its own
    // allowance, or that reached the files in a different order, returns a
    // different set of matches with the same count.
    much = manyMatches("much.log", Needle.MAX_MATCHES);
    manyMatches("src/more.log", Needle.MAX_MATCHES);

    bundle =
        write("bundle.min.js", "var a=1;" + "z".repeat(Needle.MAX_LINE_CHARS * 3) + WANTED + ";");

    outside = write(elsewhere.resolve("theirs.md"), "the " + WANTED + " of somebody else");
    Files.createSymbolicLink(repo.resolve("link.md"), outside);

    binary = repo.resolve("logo.bin");
    Files.write(
        binary, new byte[] {(byte) 0xff, (byte) 0xfe, 'w', 'o', 'r', 'k', 's', 'p', 'a', 'c', 'e'});

    ProjectStore store =
        new ProjectStore(
            jdbc,
            configFile,
            samplingDir,
            configFile.resolveSibling("console-token"),
            configFile.resolveSibling("exports"),
            configFile.resolveSibling("data"));
    store.define(PROJECT, repo, List.of());
    local =
        new LocalProvider(store, Home.of(PROJECT), List.of(new Grant(Scope.WORKSPACE, Mode.READ)));

    Workspace workspace = new Workspace();
    workspace.set(List.of(repo));
    client = new ClientEnforcer(workspace);
  }

  /**
   * The property the whole tool rests on, written as an assertion rather than as a paragraph in
   * five files.
   *
   * <p>Several searches, because a half that walked differently, or capped differently, or skipped
   * a different file agrees with the other on some single search by accident. The needles are
   * chosen to land on a tree that caps, a tree that does not, a fold of case, and a needle that is
   * nowhere — that last one being the answer a caller is most likely to be handed wrongly, since an
   * empty result looks the same however it was arrived at.
   */
  @Test
  void the_same_needle_over_the_same_tree_is_the_same_answer_on_both_machines() {
    Set<String> reached = new HashSet<>();
    Set<Boolean> truncations = new HashSet<>();

    for (Needle needle : needles()) {
      Found here = local.grep(needle, null);
      Found there = found(FileRequest.grep("g", null, needle));

      assertEquals(
          here,
          there,
          "the two halves searched for "
              + needle
              + " differently, so what a file"
              + " contains depends on which machine the job happened to run on");
      reached.add(here.stoppedBy());
      here.matches().forEach(match -> truncations.add(match.truncated()));
    }

    // The sweep says what it exercised rather than being trusted to.
    assertEquals(
        Set.of(Found.MATCHES, Found.END),
        reached,
        "both reasons a search can stop were actually reached — " + reached);
    assertEquals(
        Set.of(true, false), truncations, "and both sides of the line allowance — " + truncations);
  }

  /**
   * No line from outside the tree is in either answer.
   *
   * <p>Equality alone would not catch a leak: two halves that both leaked the linked file agree
   * perfectly, and the answer is still a read tool with no boundary. So this asserts the absence
   * directly, on both halves, and checks that the file really is reachable and really does match —
   * a fixture whose link was broken would pass by being empty.
   *
   * <p><b>What it cannot isolate, measured rather than assumed.</b> Two guards keep this line out:
   * the containment filter {@code FileSearch.eachFile} applies to each resolved candidate, and the
   * NOFOLLOW attribute read each provider does before opening, which refuses a symlink as not a
   * regular file. With the containment filter mutated out of the shared walk, this test still
   * passed — {@code LocalProviderTest.an_excluded_file_is_absent_from_a_search_that_names_no_path}
   * is that filter's instrument, because an exclusion there covers an ordinary file inside the root
   * with nothing else standing behind it. <b>That instrument cannot live in this file at all</b>: a
   * server's exclusions come from its {@code projects} row and a client's workspace has none, so a
   * tree with an excluded file in it is one the two halves would legitimately answer differently.
   */
  @Test
  void no_line_from_outside_the_tree_is_in_either_answer() {
    Needle needle = new Needle(WANTED, false);
    assertTrue(
        new Needle(WANTED, false).matches(readLine(outside)),
        "the fixture outside the tree really does hold the needle, so its absence"
            + " below is the leash and not an empty file");

    Found here = local.grep(needle, null);
    Found there = found(FileRequest.grep("g", null, needle));

    for (Found found : List.of(here, there)) {
      assertFalse(
          found.matches().stream().anyMatch(match -> match.path().equals(outside.toString())),
          "a search that checks containment once per call rather than once per"
              + " file hands back the contents of files a read of them refuses"
              + " — "
              + found.matches());
      assertFalse(
          found.matches().stream().anyMatch(match -> match.line().contains("somebody else")),
          "and the line itself is what leaks, which is worse than the name a"
              + " glob would have leaked — "
              + found.matches());
    }
  }

  /**
   * The pair a half that refused instead of skipping fails, and the fixture is checked for really
   * being unreadable rather than assumed to be.
   */
  @Test
  void a_file_that_is_not_text_is_skipped_by_both_halves_and_refused_by_both_when_named() {
    Needle needle = new Needle(WANTED, false);

    assertEquals(local.grep(needle, null), found(FileRequest.grep("g", null, needle)));
    assertTrue(
        local.grep(needle, null).matches().stream()
            .noneMatch(match -> match.path().equals(binary.toString())),
        "the bytes in it spell the needle and it is still not searched");

    WorkspaceRefusedException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            WorkspaceRefusedException.class, () -> local.grep(needle, binary));
    FileReply clientSays = client.answer(FileRequest.grep("g", binary.toString(), needle));

    assertTrue(refused.getMessage().contains("not UTF-8 text"), refused.getMessage());
    assertEquals(FileReply.REFUSED, clientSays.outcome());
    assertNull(clientSays.sentence(), "the client reports facts; the server words them");
    assertEquals(
        refused.getMessage(),
        FileWords.said(clientSays),
        "and worded, the two halves say one thing");
  }

  /**
   * The one a half that skipped the line allowance fails, named separately because the difference
   * is not cosmetic: an untruncated match is a whole minified bundle in a result that was supposed
   * to be small.
   */
  @Test
  void the_line_allowance_cuts_the_same_line_on_both_halves_and_says_it_did() {
    Needle needle = new Needle("var a=1", false);

    Found here = local.grep(needle, bundle);
    Found there = found(FileRequest.grep("g", bundle.toString(), needle));

    assertEquals(1, here.matches().size(), here.matches().toString());
    assertTrue(
        here.matches().get(0).truncated(), "the fixture really is longer than the allowance");
    assertEquals(Needle.MAX_LINE_CHARS, here.matches().get(0).line().length());
    assertEquals(here, there);
  }

  /**
   * The one a half that capped per file, or at its own number, or in its own order fails.
   *
   * <p>A count alone would not catch the order: two halves that each returned {@link
   * Needle#MAX_MATCHES} matches from different files agree about the size of the answer and about
   * nothing else. Equality of the whole list is what makes walk order part of what is asserted.
   */
  @Test
  void the_match_allowance_stops_both_halves_at_the_same_match_and_says_so() {
    Needle needle = new Needle("hit", false);

    Found here = local.grep(needle, null);
    Found there = found(FileRequest.grep("g", null, needle));

    assertEquals(
        Found.MATCHES,
        here.stoppedBy(),
        "the allowance really was what stopped this, and not the end of the tree");
    assertEquals(Needle.MAX_MATCHES, here.matches().size());
    assertEquals(here, there);
    assertTrue(
        here.matches().stream().anyMatch(match -> match.path().equals(much.toString()))
            || here.matches().stream().anyMatch(match -> match.path().endsWith("more.log")),
        "and the matches came from the files that hold them — " + here.matches());
  }

  /**
   * The offset a match carries opens the window that holds the line, on both halves — which is the
   * whole claim of the tool and the one number a one-based reading would break invisibly.
   */
  @Test
  void the_offset_in_a_match_is_the_offset_a_read_of_the_same_file_takes() {
    Needle needle = new Needle(WANTED, false);

    for (Found.Match match : local.grep(needle, repo.resolve("notes.md")).matches()) {
      Window at = Window.of(match.offset(), 1);
      assertEquals(List.of(match.line()), local.read(Path.of(match.path()), at).lines());
      assertEquals(
          local.read(Path.of(match.path()), at), span(FileRequest.read("r", match.path(), at)));
    }
  }

  /**
   * The needles the sweep asks for: one that caps, one that does not, one that folds case, and one
   * that is nowhere at all.
   */
  private static List<Needle> needles() {
    return List.of(
        new Needle(WANTED, false),
        new Needle(WANTED, true),
        new Needle("hit", false),
        new Needle("nothing named this", false));
  }

  /**
   * One answer out of the client, with the outcome checked so that a refusal fails here rather than
   * as a null result three lines later.
   */
  private Found found(FileRequest request) {
    FileReply reply = client.answer(request);
    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    return reply.found();
  }

  private io.aeyer.plowshare.protocol.Span span(FileRequest request) {
    FileReply reply = client.answer(request);
    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    return reply.span();
  }

  private static String readLine(Path file) {
    try {
      return Files.readString(file).lines().findFirst().orElse("");
    } catch (IOException unreadable) {
      throw new IllegalStateException("the fixture could not be read", unreadable);
    }
  }

  private Path manyMatches(String name, int count) throws IOException {
    String[] lines = new String[count];
    for (int at = 0; at < count; at++) {
      lines[at] = "hit " + at;
    }
    return write(name, lines);
  }

  private Path write(String name, String... lines) throws IOException {
    return write(repo.resolve(name), lines);
  }

  private static Path write(Path file, String... lines) throws IOException {
    return Files.writeString(file, String.join("\n", lines) + "\n");
  }
}
