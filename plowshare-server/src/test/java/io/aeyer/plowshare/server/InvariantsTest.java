package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The four containment invariants run on every build instead of when somebody remembers them.
 *
 * <h2>Why this is a test and not a habit</h2>
 *
 * <p>Slice 3b wrote four invariants down as shell commands in a plan file: the client module holds
 * no Spring; an HTTP client is held by exactly three files in {@code main}; the reference inference
 * box's address appears in no tracked file outside {@code docs/}; no API key appears under {@code
 * src}. Nothing in {@code check} ran any of them. Between the occasions a person remembered, all
 * four could regress with a green build, because <b>the evidence of compliance and the evidence of
 * absence are the same empty output</b> — the shape {@code SourceIsTextTest} and {@code
 * MigrationsAreImmutableTest} both exist to break, one directory over.
 *
 * <p>{@code SourceIsTextTest} is the neighbour and is deliberately not this. It protects the
 * <em>visibility</em> of these files to {@code grep} — a NUL byte makes {@code grep} skip a file
 * and report nothing, with a zero exit status — and it stops there. It never runs an invariant.
 * This class is the other half, and the two are not redundant: a tree can be perfectly readable and
 * still be carrying a key.
 *
 * <h2>Three of the four patterns are assembled rather than written out</h2>
 *
 * <p>A guard that trips its own invariant is the fault {@code plowshare-server/build.gradle.kts}
 * already names about the synthetic fixture's address, and that {@code SourceIsTextTest} declines
 * to commit by not quoting either pattern at all. This class cannot decline — it has to hold the
 * patterns to search for them — so it builds each one from pieces instead. Measured on this file
 * rather than assumed:
 *
 * <ul>
 *   <li>the API-key prefix written literally <b>does</b> trip its own invariant: that grep walks
 *       the filesystem under {@code plowshare-*&#47;src}, and this file lives there;
 *   <li>the synthetic fixture's address written literally trips its own invariant once this file is
 *       <em>tracked</em> — that grep is fed by {@code git ls-files}, so while the file was still
 *       untracked the same literal went unreported. Which is the limitation stated below,
 *       demonstrating itself;
 *   <li>the {@code OkHttpClient} import written literally is not a violation — this is a test and
 *       the count below is scoped to {@code main} — but it would make this file another hit in the
 *       wider numbers the scope note reports, so the class would be describing itself.
 * </ul>
 *
 * <p>{@code org.springframework} is the one pattern written out plainly, because this class is in
 * the server module and that invariant only looks at the client's. The reader gets one needle they
 * can actually read.
 *
 * <p>The assembly is only sound if it produces the string it meant to, and a mistyped needle would
 * make every assertion here pass over zero hits. So {@link
 * #the_guard_can_see_the_files_it_asserts_over()} requires every one of the four needles to match
 * somewhere the invariant deliberately allows.
 *
 * <h2>The {@code OkHttpClient} count's scope, because it was unwritten</h2>
 *
 * <p>"Exactly seven" is a {@code main}-only claim, and there is more than one wider answer. Over
 * every tracked {@code .java} file the same import is also held by the tests that drive the real
 * client against a real server, which legitimately need one. Over the <b>whole tracked tree</b> it
 * is higher again: the slice plans quote the import in prose, and no {@code .java} scan reads a
 * Markdown file. All three are correct answers to "how many", differing only by a scope that until
 * now lived in an operator's head, so a bare "seven" told a reader nothing they could check.
 *
 * <p><b>This paragraph has gone stale twice, and holds no count of the tree at all as a result.</b>
 * It first said "five" for the whole tracked tree where the answer was six — the same fault the
 * paragraph is about, committed inside it. The numbers were then computed in the assertion message
 * and the sentence above kept its <em>own</em> copies anyway, so "five, the two extra being
 * EndToEndTest and FileChannelTest" was wrong again within the slice that wrote it.
 *
 * <p><b>And the correction that removed those copies left one of its own behind</b>: a count of the
 * holders <em>outside</em> {@code main}, sitting in the sentence that says no count here survives.
 * It was accurate when written and is still accurate now — which is the point. A correct number in
 * a paragraph arguing that this file keeps none is simply the next one to go stale, and it would
 * have gone stale silently, because nothing computes it and nothing asserts it. It is gone for the
 * same reason the others are.
 *
 * <p><b>Measured, by tripping this assertion on purpose:</b> the message computes every wider
 * number and lists every file, so the rule here is to say nothing that the failure does not already
 * say better. The one number this file still writes down is the invariant's own — <b>seven, in
 * {@code main}</b> — because that is the thing being asserted rather than a fact about the tree,
 * and changing it is changing the rule. It was three until the search feature added {@code
 * RemoteSearchProvider}, {@code SearchConfig} and {@code SearchRegistrar}, sharing one client
 * between them — {@code HTTP_CLIENT_HOLDERS}' own javadoc carries the argument for why three files
 * around one shared client is not the same claim as three separate files each configuring their
 * own, which is what this invariant exists to catch. It went to seven when the fetch slice added
 * {@code BuiltinFetcher}, which is not a repeat of that same argument: it is built to take an
 * injected client rather than configure one of its own, the same shape the search three already
 * use, and {@code FetchConfig#pageFetcher} is what hands it the search package's one client, in
 * {@code fetch}'s own configuration class rather than in {@code SearchConfig} — see {@code
 * HTTP_CLIENT_HOLDERS}' own javadoc, updated again, for why a fourth class of endpoint (an
 * arbitrary origin an agent names at request time, rather than one this server or an operator
 * already knew) is the shape of that seventh holder's claim, and for why the wiring landing in a
 * class this test does not name — {@code FetchConfig} carried it three commits later, not {@code
 * SearchConfig}. This paragraph and the two it points to predicted the wiring rather than reporting
 * it, and then went stale the moment the prediction was overtaken and nothing came back to correct
 * the prose. Fixed here rather than left as the tripwire it had become.
 *
 * <p>{@link #an_http_client_is_held_by_exactly_seven_files_in_main()} therefore names its scope in
 * the assertion message and <b>computes</b> every wider number beside it, rather than this javadoc
 * keeping a copy of any of them. It also asserts the seven by name and not by count, so a swap —
 * one holder deleted, a different file gaining the import — cannot net out to seven and pass.
 *
 * <h2>What this does not cover</h2>
 *
 * <p><b>Tracked files only.</b> Every scan here is fed by {@code git ls-files}, so <b>a file
 * carrying a key that has not been added to the index is outside this guard entirely</b> and this
 * class will report a clean tree. That is a real hole and not a theoretical one: three of the
 * original four greps walked the filesystem instead — only the address one was fed by {@code git
 * ls-files} — so all three would have caught such a file where this does not. It is a deliberate
 * narrowing, accepted because the alternative — walking the working tree — sweeps in build outputs,
 * IDE directories and whatever else a developer happens to be holding, and a guard that cries wolf
 * about untracked scratch files is a guard that gets muted. The commit that would leak the key is
 * the commit that puts the file in the index, and from that commit on this guard sees it.
 *
 * <p><b>The {@code OkHttpClient} needle is one spelling, and only one.</b> It is the literal {@code
 * import} statement naming {@code okhttp3.OkHttpClient} — spelled in two pieces here for the reason
 * the whole section above gives, and this paragraph did write it in one and made this file a holder
 * of it until the count caught the file out — so a fourth holder reaching the same class by {@code
 * import okhttp3.*} or by naming {@code okhttp3.OkHttpClient} fully qualified at the use site is
 * <b>not seen by this assertion at all</b> — it is a containment guard against the ordinary way of
 * acquiring a dependency, not against someone working around it. The other three needles do not
 * share the weakness for their own reasons and it is worth saying which: {@code
 * org.springframework} is a package prefix, so every import form and every qualified use contains
 * it; the address and the key prefix are the literal secrets themselves, and there is no other
 * spelling of those to find. Widening this one to the bare class name was considered and declined —
 * {@code OkHttpClient} alone matches the prose of every plan and javadoc that discusses the rule,
 * including this file — so the narrow needle is a deliberate trade and not an oversight.
 *
 * <p><b>The Spring and {@code OkHttpClient} scans are {@code .java} only</b>, because the shell
 * commands they replace were. A {@code plowshare-client/src/main/resources} file naming Spring is
 * not covered. The two scans that were not restricted to Java — the address and the key — are not
 * restricted here either.
 *
 * <p><b>It searches bytes in this process rather than lines through {@code grep}</b>, which matters
 * more than it sounds. {@code grep}'s handling of a file containing a NUL varies by implementation,
 * and it was measured varying on this host: with the address planted after a NUL byte, {@code
 * /usr/bin/grep -l} reported the file, while the {@code ugrep} wrapper first on this developer's
 * {@code PATH} reported nothing and exited 1. So which of the two the person running the original
 * shell invariant happened to have decided whether it saw that file at all. This class found it
 * both times, because it compares bytes and never asks a tool. What it gives up is regular
 * expressions, which none of the four invariants needed.
 *
 * <h2>When this runs, which was the same fault one level up</h2>
 *
 * <p>This class asserts over the whole repository, and the build originally had no idea that was
 * its subject. <b>Measured on the commit that first shipped this file</b>, with a violating comment
 * sitting in {@code plowshare-client/src/main} and nothing else changed:
 *
 * <pre>    &gt; Task :plowshare-server:test UP-TO-DATE
 *     BUILD SUCCESSFUL in 1s</pre>
 *
 * <p>{@code InvariantsTest} did not appear in that output at all. So the guard was an instrument
 * that could not record what it was cited for — <b>the exact fault it was written to close,
 * relocated from the source layer to the build layer</b>. It re-ran only by classpath accident,
 * when a violation happened to change bytecode, and <b>that accident does not happen for three of
 * the four invariants in their most likely shape</b>: Spring cannot reach the client module as a
 * real import at all, so the only possible violation there is a comment; the box's address arrives
 * in build files, scripts and READMEs that are on no test's classpath; and a pasted key most
 * plausibly lands in a comment or a resource, which is the paste tripwire the invariant exists to
 * be.
 *
 * <p>The repository tree is now a declared input of a dedicated {@code
 * :plowshare-server:invariants} task that {@code check} depends on. {@code
 * plowshare-server/build.gradle.kts} carries the reasoning, the exclusions and why each one is
 * safe. Same violation, same {@code ./gradlew check}, after:
 *
 * <pre>    &gt; Task :plowshare-server:invariants FAILED
 *     InvariantsTest &gt; the_client_module_holds_no_spring() FAILED
 *     BUILD FAILED in 1s</pre>
 *
 * <p>Measured alongside it, because a guard that never caches gets disabled by whoever is waiting
 * on it: an unchanged tree leaves the task up to date; an edit under {@code docs/} re-runs it in
 * about a second and leaves the ~50s {@code :plowshare-server:test} up to date; a write under
 * {@code build/} re-runs neither.
 *
 * <p><b>What that still does not cover.</b> The declared inputs are the working tree, while the
 * scans read the git index. {@code git add} of an already-present untracked file changes what this
 * class sees without changing any file, so it does not re-run the task — the tracked-only
 * limitation above, meeting the build layer. Stated rather than fixed: {@code .git/index} churns on
 * ordinary read-only git commands and wiring it in would re-run this constantly.
 */
class InvariantsTest {

  /**
   * The repository root, asked for rather than assumed.
   *
   * <p>Measured: JUnit runs here with the working directory set to the <em>module</em> directory,
   * {@code plowshare-server}, which is what {@code MigrationsAreImmutableTest} relies on one file
   * over. {@code git rev-parse --show-toplevel} answers from anywhere inside the working tree, so
   * this needs no {@code ".."} that a moved module would silently invalidate.
   */
  private static final Path ROOT = repositoryRoot();

  /**
   * Every file the index holds, relative to {@link #ROOT}.
   *
   * <p>Measured, and the reason {@link #tracked()} sets the child process's directory explicitly
   * rather than inheriting this one: {@code git ls-files} run from {@code plowshare-server} lists
   * only the paths under {@code plowshare-server}, relative to it, and says nothing at all about
   * the rest of the repository. A guard fed that list would report the {@code docs/} tree, the root
   * build files and the other two modules as clean without ever having opened them — and {@link
   * #the_guard_can_see_the_files_it_asserts_over()} is what notices, because three of the four
   * assertions below survive a wrong root looking exactly as green as they do on a clean tree.
   * Measured, by pointing {@link #ROOT} one directory too deep: only the {@code OkHttpClient} count
   * and the companion failed.
   */
  private static final List<String> TRACKED = tracked();

  private static final Pattern CLIENT_MAIN_JAVA =
      Pattern.compile("^plowshare-client/src/main/.*\\.java$");
  private static final Pattern SERVER_MAIN_JAVA =
      Pattern.compile("^plowshare-server/src/main/.*\\.java$");
  private static final Pattern MAIN_JAVA = Pattern.compile("^plowshare-[^/]+/src/main/.*\\.java$");
  private static final Pattern ANY_JAVA = Pattern.compile("^plowshare-[^/]+/src/.*\\.java$");
  private static final Pattern UNDER_SRC = Pattern.compile("^plowshare-[^/]+/src/.*");

  /**
   * Server {@code main} sources outside {@code api/} itself.
   *
   * <p>The {@code (?!api/)} excludes nothing today and is not dead: a class in {@code api/} has no
   * need to import its own package, so none does — but a same-package import is legal Java that
   * compiles and that an IDE will happily insert, and one would otherwise be counted as a holder.
   * The exclusion keeps the count a statement about code outside the HTTP surface rather than about
   * anybody's import organiser.
   */
  private static final Pattern SERVER_MAIN_OUTSIDE_API =
      Pattern.compile(
          "^plowshare-server/src/main/java/io/aeyer/plowshare/server/" + "(?!api/).*\\.java$");

  /**
   * Controllers in {@code api/}, by their own file — the scope {@link #BAD_REQUEST_THROW_COUNTS} is
   * pinned over.
   *
   * <p>Named by the {@code *Controller.java} suffix rather than by an enumerated list, so a new
   * controller is in scope — and asserted to hold zero inline {@code BadRequestException} throws —
   * from the commit that adds it, with no second edit here required to bring it under the guard.
   */
  private static final Pattern API_CONTROLLER_JAVA =
      Pattern.compile(
          "^plowshare-server/src/main/java/io/aeyer/plowshare/server/api/"
              + "[A-Za-z]+Controller\\.java$");

  private static final String SPRING = "org.springframework";
  private static final String HTTP_CLIENT = "import okhttp3." + "OkHttpClient";
  private static final String CALLER_FAULT =
      "import io.aeyer.plowshare.server.api.BadRequestException";

  /**
   * The wildcard spelling of {@link #CALLER_FAULT}'s single-type import.
   *
   * <p>A needle that matches one spelling of an import and not the other is a guard a wildcard
   * walks straight past: {@code import io.aeyer.plowshare.server.api.*;} brings {@code
   * BadRequestException} into scope exactly as the single-type form does, and this module already
   * uses wildcard imports in several places, so this is not a hypothetical evasion to rule out
   * later. A guard that can be walked around is a guard that reports compliance it did not check,
   * and folding this in beside {@link #CALLER_FAULT} is what keeps that from being true of this
   * one.
   */
  private static final String CALLER_FAULT_WILDCARD = "import io.aeyer.plowshare.server.api.*";

  /**
   * The literal an inline refusal is thrown with, distinct from {@link #CALLER_FAULT} which is an
   * import statement. A count of this needle is a count of decisions still living in the controller
   * rather than in a service method, which is exactly what {@link #BAD_REQUEST_THROW_COUNTS} pins.
   */
  private static final String BAD_REQUEST_THROW = "throw new BadRequestException";

  private static final String REFERENCE_BOX = "198.51.100." + "122";
  private static final String KEY_PREFIX = "sk" + "-lm-";

  /**
   * The seven files in {@code main} allowed to hold an HTTP client, by simple name rather than by
   * path.
   *
   * <p>By name because that is how the invariant is stated, and because a package move is not the
   * regression this is watching for; an unargued eighth file is. Equality against the whole list
   * and not a size check, so a swap — one of these seven deleted and a different file gaining the
   * import — cannot net out to seven and pass.
   *
   * <p><b>Three, not one, of the search names on this list share a single client.</b> This used to
   * be a list of three, one file per client, until the search feature added {@code
   * RemoteSearchProvider}, {@code SearchConfig} and {@code SearchRegistrar}. That is not a fourth
   * (and fifth, and sixth) instance of "a file configures its own timeouts, retries and connection
   * pool for the endpoints it calls" — the thing the assertion message below asks an author to
   * argue against before adding a holder. It is one client, configured once in {@code
   * SearchConfig.searchHttpClient()}, injected into both {@code RemoteSearchProvider} and {@code
   * SearchRegistrar}, and it dials a different class of endpoint than either of the original two
   * anyway: the LLM vendor ({@code OpenAiTransport}), this server's own file and event sockets
   * ({@code ChannelClient}, {@code HttpServerClient}), and now, third, whatever provider process an
   * operator has registered — a destination this server does not know the shape of in advance,
   * unlike the first two. {@code SearchConfig} and {@code SearchRegistrar} each import the type —
   * one to build the shared bean, one to receive it and apply its own probe timeout per call
   * through {@code Call#timeout()} — which is why the count for search is three files for one
   * client rather than one file for one client with two callers holding no import of their own.
   *
   * <p><b>The seventh, {@code BuiltinFetcher}, is a fourth class of endpoint, not a second client
   * for the search feature's class of endpoint.</b> {@code RemoteSearchProvider} and {@code
   * SearchRegistrar} both dial a provider process an operator registered in advance — addresses
   * this server already knows before the call. {@code BuiltinFetcher} dials whatever {@code url} an
   * agent hands it at request time, which can be any origin on the open web; nothing an operator
   * configured in advance names it. That is the same distinction that already separates the LLM
   * vendor and this server's own sockets from each other and from the registered-provider case, so
   * it is a fourth bucket rather than a restatement of the third.
   *
   * <p><b>Built to share, and wired.</b> {@code BuiltinFetcher}'s constructor takes an {@code
   * OkHttpClient} as an argument rather than building one — the same shape {@code
   * RemoteSearchProvider} and {@code SearchRegistrar} already use — and {@code
   * FetchConfig#pageFetcher} builds it through {@code FetchConfig.guardedFetcher(http, allowlist,
   * props.getTimeout())}, which derives fetch's strict and per-allowlist-entry clients once, at
   * boot, from {@code http}, the {@code OkHttpClient} parameter {@code
   * SearchConfig#searchHttpClient} supplies. {@code FetchConfig} spells okhttp's types fully
   * qualified, so the derivation adds no holder. It landed in {@code FetchConfig}, not in {@code
   * SearchConfig} as an earlier draft of this paragraph predicted — naming the wrong class was this
   * paragraph's own mistake, not a fact about where the wiring belonged. This paragraph used to say
   * the wiring had not happened yet and named a later task in this slice as the one expected to do
   * it; that task landed three commits after this paragraph was written and nobody came back to
   * update the prose, which is exactly the stale-guard shape this file exists to catch in other
   * files. What this invariant actually needs to be true, independent of the wiring's state, is
   * narrower: the import here exists only to declare a constructor parameter and to call {@link
   * okhttp3.Call#timeout()} on the one call {@link
   * io.aeyer.plowshare.server.fetch.BuiltinFetcher#fetch} makes — {@code BuiltinFetcher} itself
   * builds no client. {@code FetchConfig} derives fetch's clients — one strict, one per allowlist
   * entry — from the shared bean, each with its own {@code ConnectionPool} (spec
   * 2026-09-28-fetch-stays-on-the-public-web §2.6: OkHttp 4.12's {@code Address} does not compare
   * the socket factory, so a shared pool would not keep the strict and per-entry clients apart).
   * That derivation lives in {@code FetchConfig}, which is why it does not add an eighth holder
   * below.
   */
  /**
   * Where the authorisation rule for presence is written. A path and not a scan: the rule lives in
   * exactly one place on purpose, and an invariant that searched the tree for it would go green on
   * a copy in a comment somewhere.
   */
  private static final String PRESENCE_REGISTRY =
      "plowshare-server/src/main/java/io/aeyer/plowshare/server/session/" + "PresenceRegistry.java";

  private static final List<String> HTTP_CLIENT_HOLDERS =
      List.of(
          "BuiltinFetcher.java",
          "ChannelClient.java",
          "HttpServerClient.java",
          "OpenAiTransport.java",
          "RemoteSearchProvider.java",
          "SearchConfig.java",
          "SearchRegistrar.java");

  @Test
  void the_client_module_holds_no_spring() throws IOException {
    assertEquals(
        List.of(),
        scan(SPRING, within(CLIENT_MAIN_JAVA)),
        "these files under plowshare-client/src/main name "
            + SPRING
            + ", and the"
            + " client module is deliberately Spring-free — its build file has no"
            + " Spring in it and must not gain any. The client half of the file"
            + " channel is okhttp's WebSocket for exactly this reason while the"
            + " server half is Spring's. Take the dependency out of the client"
            + " rather than out of this assertion.");
  }

  @Test
  void an_http_client_is_held_by_exactly_seven_files_in_main() throws IOException {
    List<String> inMain = scan(HTTP_CLIENT, within(MAIN_JAVA));
    List<String> elsewhere = scan(HTTP_CLIENT, outsideMain());
    // Every tracked file, with no .java restriction, because the sentence
    // below says "the whole tracked tree" and used to report a number that
    // was not it -- inMain + elsewhere counts .java files only, and the
    // needle is also quoted in prose in 3b's plan. Computed rather than
    // written down, on the same rule as the two above.
    List<String> anywhere = scan(HTTP_CLIENT, path -> true);

    assertEquals(
        HTTP_CLIENT_HOLDERS,
        simpleNames(inMain),
        "the scope of this count is plowshare-*/src/main, and nothing wider. Held in"
            + " main by "
            + inMain
            + ". Held under src but outside main by "
            + elsewhere
            + ", which this count excludes on purpose: a test that"
            + " drives the real client against a real server legitimately holds"
            + " one. Over every tracked .java file that is "
            + (inMain.size() + elsewhere.size())
            + "; over the whole tracked tree it"
            + " is "
            + anywhere.size()
            + ", the difference being files this scan's"
            + " .java restriction does not read. Six, not three, because the search"
            + " feature's RemoteSearchProvider, SearchConfig and SearchRegistrar"
            + " share ONE client configured once in SearchConfig.searchHttpClient()"
            + " and dial registered provider processes, not the LLM vendor or this"
            + " server's own sockets the original two already cover. Seven, not six,"
            + " because the fetch slice's BuiltinFetcher addresses a fourth class of"
            + " endpoint again — an arbitrary remote origin an agent names at request"
            + " time, unlike the vendor, this server's own sockets, or a provider"
            + " process an operator registered in advance — and it is built to take"
            + " that one search-package client by injection: BuiltinFetcher builds no"
            + " client of its own. FetchConfig.pageFetcher wires it, deriving fetch's"
            + " strict and per-allowlist-entry clients once from the client"
            + " SearchConfig.searchHttpClient() built, each with its own ConnectionPool"
            + " (spec §2.6: OkHttp 4.12's Address does not compare the socket factory,"
            + " so a shared pool would not keep the strict and per-entry fetch clients"
            + " apart); see HTTP_CLIENT_HOLDERS' own javadoc for the argument in full."
            + " An EIGHTH"
            + " holder is a different claim: either a second client for one of these"
            + " four classes of endpoint, or a client for a fifth kind of endpoint —"
            + " argue for it in the build files before adding it here.");
  }

  /**
   * The one, and the six it used to be.
   *
   * <p><b>{@code RuntimeConfigController} is the only holder left, and it belongs.</b> It is a
   * controller: it sits on the HTTP surface, {@link
   * io.aeyer.plowshare.server.api.ApiExceptionHandler} maps what it throws, and a 400 is what its
   * caller gets. It is outside {@code api/} only because it lives beside the config feature it
   * serves.
   *
   * <p><b>The other five had no HTTP surface at all, which was the finding this guard was written
   * to pin.</b> {@code SearchTool} and {@code FetchTool} are in-process agent tools — an agent
   * calls one, the throw goes to the agent runtime, and the handler never runs. {@code
   * SearchService}, {@code SearchRegistrar} and {@code FetchService} sat under them. So one type
   * was doing two jobs: an HTTP mapping key in one place and a domain signal in another. They have
   * since moved to {@link io.aeyer.plowshare.server.faults.CallerFault} — see that type's own
   * javadoc for the argument in full — which is the domain's own answer to "whose fault is this?"
   * rather than the HTTP surface's, and {@code ApiExceptionHandler} maps both to the identical 400
   * so no caller of either sees a difference.
   *
   * <p><b>Which made one sentence in {@code ApiExceptionHandler} too broad, and still does.</b> It
   * says "The reader of these statuses is a model, and what it does next is decided by them". That
   * is true of the MCP route — {@code HttpServerClient} turns a non-2xx into {@code
   * ServerError(code, detail)} and a tool renders it for a foreign harness's model — and it was
   * never true of the five agent- and service-side holders, where there was no status on the path
   * to be read. {@code BadRequestException}'s own recorded incident is the first kind: a base URL
   * with no scheme made a <em>memory write</em> come back {@code 400 bad_request}, and a memory
   * write is the adapter's route, not an agent's. Correcting that sentence is a separate change
   * from this one; it is recorded here and in the auth spec's §3.3 rather than done as a side
   * effect of this guard shrinking.
   *
   * <p><b>This guard does not say one holder is permanent.</b> It says the set may not widen
   * without somebody arguing for it. A second holder is a claim that some new place sits on the
   * HTTP surface — a controller, or code moved onto it — and needs {@code BadRequestException}
   * specifically rather than {@link io.aeyer.plowshare.server.faults.CallerFault}, which is the
   * answer for everywhere else that used to reach for this type instead.
   *
   * <p><b>A misspelled needle fails this rather than passing it</b>, because the expected list is
   * not empty. That is the one structural advantage this invariant has over the empty-list ones
   * below, which a broken needle satisfies silently — the fault this file's own javadoc worries
   * about, and why {@link #the_guard_can_see_the_files_it_asserts_over()} has to exist for them.
   */
  private static final List<String> CALLER_FAULT_HOLDERS = List.of("RuntimeConfigController.java");

  @Test
  void the_http_surfaces_caller_fault_type_is_held_by_exactly_one_file_outside_it()
      throws IOException {
    // Two needles into one set: a file that reaches BadRequestException
    // through import io.aeyer.plowshare.server.api.*; is exactly as much a
    // holder as one that names it directly, and this module already writes
    // wildcard imports in several places, so a single-type needle alone
    // would let a wildcard rewrite widen the holder set with this test
    // still green. Deduplicated through a Set, since a file naming both
    // forms would otherwise be counted twice.
    List<String> holders =
        simpleNames(
            union(
                scan(CALLER_FAULT, within(SERVER_MAIN_OUTSIDE_API)),
                scan(CALLER_FAULT_WILDCARD, within(SERVER_MAIN_OUTSIDE_API))));

    assertEquals(
        CALLER_FAULT_HOLDERS,
        holders,
        "BadRequestException is api/'s type and ApiExceptionHandler maps it to 400."
            + " RuntimeConfigController is the one holder outside api/ and it"
            + " belongs: it is a controller, living beside the config feature it"
            + " serves. Five other holders -- SearchTool, FetchTool, SearchService,"
            + " SearchRegistrar and FetchService -- used to be here with no HTTP"
            + " surface of their own and have moved to"
            + " io.aeyer.plowshare.server.faults.CallerFault, which"
            + " ApiExceptionHandler maps to the identical 400. If this failed because"
            + " you ADDED a holder, by either import spelling: say why the HTTP"
            + " surface's caller-fault type is the right one there, or throw"
            + " CallerFault and let api/ map it. If it failed because you REMOVED the"
            + " last one, say so in CALLER_FAULT_HOLDERS' javadoc.");
  }

  /**
   * The count of inline {@code throw new BadRequestException(...)} statements left in each {@code
   * api/} controller, after Tasks 1-6 of {@code implementation rationale} moved every decision that
   * had a service method to move into out of the fifteen throws the auth spec named, plus the five
   * ordering-and-default rules that threw nothing at all.
   *
   * <p><b>This was a pin, not a ban, and the pin has run its course.</b> {@code implementation
   * rationale} retired the throws one controller at a time, immediately before that controller's
   * own frame handlers were built — the moment a parity test could prove each decision travelled
   * with it — and the last of them went at Task 6. Nothing is left to retire: <b>all thirteen
   * counts are zero</b>. Pinning the exact count, rather than a ceiling or nothing at all, made
   * every retirement a deliberate edit to a number in this file instead of a change nobody had to
   * notice, and it goes on making a NEW throw fail the build the moment it lands — in {@code
   * DocumentController} as much as in any of the twelve others.
   *
   * <p><b>If a number here needs to go DOWN</b>, that was the breadth plan retiring a throw: lower
   * the count in the same commit that removes it, and say so in the commit message. No count can go
   * down again from here. <b>If this test fails because a number went UP, or a zero became
   * nonzero</b>, that is the fault this invariant exists to catch — an inline rule quietly
   * returning to a controller this plan already cleared it out of. The fix is to move the decision
   * into the service method the controller calls, the way Tasks 1-6 did, not to relocate or
   * catch-and-rethrow the throw within the controller, which would leave the count unchanged while
   * leaving the rule exactly where this plan finds it — and not to raise the number here, which is
   * the one thing this invariant exists to make impossible without an edit someone has to justify.
   *
   * <p><b>Every controller in the directory is pinned at zero</b>, and each is named in this map
   * rather than left out of it: {@code AgentController}, {@code BufferPurgeController}, {@code
   * ConversationController}, {@code DigestController}, {@code DocumentController}, {@code
   * FetchController}, {@code ImageController}, {@code MemoryController}, {@code ProjectController},
   * {@code ProposalController}, {@code RetentionController}, {@code SearchController} and {@code
   * SearchProviderController}. {@code ConversationController} joined them at Task 1 of the breadth
   * plan, which retired its last four: two paging bounds into {@code requests.RequestedWindow} and
   * one blank-question check into {@code requests.RequestedLogQuestion}, both of which are request
   * shape, and the projection's no-agent refusal into {@code archive.Conversations.whoToProjectAs},
   * which is a decision about what the conversation records rather than about a field.
   *
   * <p>{@code DocumentController} is the newest, and was the largest: Task 2 retired
   * <b>fourteen</b> throws, which was the heaviest concentration in the directory and more than the
   * auth spec thought the whole codebase owed. Five blank-prose refusals went to {@code
   * requests.RequestedCorpusQuestion}, five limits and one offset to {@code
   * requests.RequestedCorpusPage}, the citations scope refusal — with the three-armed read it
   * guards — to {@code requests.RequestedCitationScope}, the search mode's parse to {@code
   * requests.RequestedSearchMode}, and the upload's blank {@code name} to {@code
   * requests.RequestedDocumentName}. The last of those has one caller and will keep it, because
   * {@code POST /v1/documents} is ruled to stay on HTTP; it moved anyway, since the rule is a fact
   * about two request fields rather than about transport. Four {@code NotFoundException}s moved in
   * the same commit, to {@code documents.Corpus} — not counted here, and moved for the same reason:
   * a frame handler restating a 404's sentence is the drift a parity test's word-for-word
   * comparison exists to catch.
   *
   * <p>{@code ProposalController} and {@code ImageController} joined them at Task 6, the breadth
   * plan's batch of small controllers. The proposal queue's two — no {@code accept}, no {@code by}
   * — went to {@code requests.RequestedResolution} as request shape, and deliberately not into
   * {@code PromotionQueue}: both surfaces drive a <em>mocked</em> queue in their tests, so a rule
   * inside a mocked method is a rule neither surface actually runs, and a parity test cannot
   * compare a sentence nothing said. The image upload's blank {@code name} went to {@code
   * requests.RequestedImageName}, beside {@code RequestedDocumentName}'s identical rule; its "this
   * server keeps no data directory" went into {@code images.ImageStore.store} itself, which is
   * where that fact lives. Neither has a frame today — {@code POST /v1/images} is ruled to stay on
   * HTTP — and they moved anyway, on {@code RequestedDocumentName}'s own precedent: what keeps that
   * endpoint on HTTP is bytes on the wire, not where its decisions belong.
   *
   * <p>{@code MemoryController} joined them at Task 5, which retired all four of its throws as
   * request shape: the blank {@code question} into {@code requests.RequestedMemoryQuestion}, the
   * blank {@code reason} and {@code by} into {@code requests.RequestedInvalidation}, and the
   * verdict tripwire — the refusal that tells a stale client its {@code verdict} key was not
   * honoured — into {@code requests.RequestedProposal}. The last of those took {@code
   * api.WriteMemoryRequest.namesAVerdict} with it: asking the question on the record and answering
   * it in the controller was one rule in two packages, which is the shape a second surface drifts
   * through. Leaving a zero-count controller out of the map would mean a first throw landing in it
   * changes nothing {@link #the_number_of_inline_bad_request_throws_is_pinned_per_controller()}
   * reads, because {@link #badRequestThrowCounts()} would have nothing to compare that controller's
   * new, nonzero count against.
   *
   * <p>Measured directly against source, not carried over from {@code
   * .superpowers/sdd/owed-rules.md}: that survey was written before six tasks changed the tree, and
   * by the ruling in this plan's own text the survey's arithmetic does not even match its own prose
   * for two of these controllers post-refactor, so it is not a source either count here could
   * safely start from.
   */
  private static final Map<String, Integer> BAD_REQUEST_THROW_COUNTS =
      Map.ofEntries(
          Map.entry("AgentController.java", 0),
          Map.entry("BoardController.java", 0),
          Map.entry("BufferPurgeController.java", 0),
          Map.entry("ConversationController.java", 0),
          Map.entry("DigestController.java", 0),
          Map.entry("DocumentController.java", 0),
          Map.entry("FetchController.java", 0),
          Map.entry("ImageController.java", 0),
          Map.entry("MemoryController.java", 0),
          Map.entry("OrchestrationRecordController.java", 0),
          Map.entry("ProjectController.java", 0),
          Map.entry("ProposalController.java", 0),
          Map.entry("RetentionController.java", 0),
          Map.entry("SearchController.java", 0),
          Map.entry("SearchProviderController.java", 0));

  @Test
  void the_number_of_inline_bad_request_throws_is_pinned_per_controller() throws IOException {
    Map<String, Integer> actual = new TreeMap<>(badRequestThrowCounts());
    Map<String, Integer> expected = new TreeMap<>(BAD_REQUEST_THROW_COUNTS);

    assertEquals(
        expected,
        actual,
        "inline `throw new BadRequestException(...)` counts under api/ changed. See"
            + " BAD_REQUEST_THROW_COUNTS' own javadoc for the full argument; in short:"
            + " a count going DOWN is"
            + " implementation rationale"
            + " retiring a throw as that controller's frames get built, and should be"
            + " matched here in the same commit -- though every count is now zero,"
            + " so nothing is left to retire. A count going UP, which for any of"
            + " the thirteen means a first throw returning, is a rule"
            + " returning to the HTTP surface after"
            + " implementation rationale moved it into"
            + " a service method both HTTP and the socket frame surface call — fix it"
            + " by moving the decision back out, not by raising this number. Expected "
            + expected
            + ". Actual "
            + actual
            + ".");
  }

  /**
   * How many endpoints {@code api/}'s controllers declare between them.
   *
   * <p>Measured from the source at HEAD, not inherited: fifty-seven mapping annotations, of which
   * fifty-four are answered by a frame type and three are not, each of those three with a ruling
   * written out in {@code Capabilities}. A pin on the total is what makes a fifty-eighth endpoint a
   * decision somebody takes rather than a line nobody notices.
   */
  // Board top-up adds one route with a declared board.topup frame and capability.
  private static final int API_ENDPOINTS = 57;

  /**
   * Every spelling an endpoint can be declared with.
   *
   * <p>{@code @RequestMapping} is in the list although {@code api/} uses it nowhere today, neither
   * on a method nor on a class. That is the point: it is the one spelling that could add an
   * endpoint without any of the four verb-shaped annotations appearing, and a guard that only knows
   * the spellings already in use is a guard the next spelling walks past. If a class-level
   * {@code @RequestMapping} is ever added for a base path, it will count as one here and the pin
   * will have to say so out loud.
   */
  private static final List<String> ENDPOINT_MAPPINGS =
      List.of(
          "@GetMapping",
          "@PostMapping",
          "@PutMapping",
          "@DeleteMapping",
          "@PatchMapping",
          "@RequestMapping");

  /**
   * The number of endpoints under {@code api/} is pinned, so an endpoint that appears with no frame
   * beside it cannot appear quietly.
   *
   * <h2>What this catches</h2>
   *
   * <p>One thing: a new route on the HTTP surface. {@code FrameRouterTest} catches a frame type
   * nobody declared and {@code Capability}'s own constructor catches a declaration with neither a
   * frame nor a reason — but an <em>endpoint</em> added with no frame and no register entry at all
   * falls between the two, and the whole-surface plan found eleven of those by hand. Fifty-five is
   * a number source can be counted for; the count going up is the moment to ask the question.
   *
   * <h2>What this does not catch, which is the larger half</h2>
   *
   * <p><b>A missing {@code Capabilities} row.</b> This counts routes and nothing else: it cannot
   * tell which endpoint appeared, whether the register names it, or whether the frame it supposedly
   * has is the frame that answers it. An endpoint added <em>and</em> framed <em>and</em> left out
   * of {@code Capabilities} passes here, because the total moved by one either way. That mapping —
   * every route against its entry — is the endpoint-to-capability census the whole-surface plan
   * adjudicated as a follow-on; it needs a sixth column on fifty-one entries and fifty-five routes
   * hand-mapped, and this pin is deliberately the cheap part of it rather than a stand-in for it.
   *
   * <p>It is also blind to an endpoint removed and another added in the same commit, which nets out
   * to fifty-five.
   */
  @Test
  void the_number_of_endpoints_under_api_is_pinned() throws IOException {
    assertEquals(
        API_ENDPOINTS,
        endpointMappings(),
        "the number of @*Mapping annotations under api/ changed, and each one is an"
            + " endpoint. If it went UP: a socket-only client cannot reach what you"
            + " just added. Give it a frame type -- a handler in its area and a"
            + " spelling in ws.FrameTypes -- and name that type on the endpoint's"
            + " row in Capabilities.ALL; or, if it is ruled to stay on HTTP, say so"
            + " in that row's frameNote in the endpoint's own words, beside the"
            + " uploads and the operational routes that are already ruled that way."
            + " Then raise this number in the same commit."
            + " implementation rationale"
            + " is the plan that built the frame surface and carries both rulings."
            + " If it went DOWN: an endpoint was removed, so drop its row from"
            + " Capabilities.ALL and lower this number. This pin counts routes only"
            + " -- see its javadoc for what it cannot see.");
  }

  /**
   * How many endpoints the controllers {@link #API_CONTROLLER_JAVA} matches declare between them,
   * counted from their bytes.
   */
  private static int endpointMappings() throws IOException {
    int total = 0;
    for (String path : matching(within(API_CONTROLLER_JAVA))) {
      Path file = ROOT.resolve(path);
      if (!Files.isRegularFile(file)) {
        continue;
      }
      byte[] content = Files.readAllBytes(file);
      for (String mapping : ENDPOINT_MAPPINGS) {
        total += occurrences(content, mapping.getBytes(StandardCharsets.UTF_8));
      }
    }
    return total;
  }

  /**
   * Inline {@code throw new BadRequestException(...)} counts per {@code api/} controller, by the
   * controller's own simple file name.
   *
   * <p>Every controller {@link #API_CONTROLLER_JAVA} matches gets an entry, which today means all
   * thirteen at zero: a controller with no throw still needs a zero recorded, or a first throw
   * landing in it would raise no count this method already reports and {@link
   * #the_number_of_inline_bad_request_throws_is_pinned_per_controller()} would have nothing to
   * compare it against.
   */
  private static Map<String, Integer> badRequestThrowCounts() throws IOException {
    byte[] needle = BAD_REQUEST_THROW.getBytes(StandardCharsets.UTF_8);
    Map<String, Integer> counts = new TreeMap<>();
    for (String path : matching(within(API_CONTROLLER_JAVA))) {
      Path file = ROOT.resolve(path);
      int occurrences =
          Files.isRegularFile(file) ? occurrences(Files.readAllBytes(file), needle) : 0;
      counts.put(path.substring(path.lastIndexOf('/') + 1), occurrences);
    }
    return counts;
  }

  /**
   * The number of non-overlapping times {@code needle} appears in {@code content}, found the same
   * way {@link #holds} finds a single one — by comparing bytes directly rather than through a
   * regular expression — because {@link #badRequestThrowCounts()} needs a count and not just a
   * yes-or-no.
   */
  private static int occurrences(byte[] content, byte[] needle) {
    int count = 0;
    int at = 0;
    while (at + needle.length <= content.length) {
      boolean match = true;
      for (int offset = 0; offset < needle.length; offset++) {
        if (content[at + offset] != needle[offset]) {
          match = false;
          break;
        }
      }
      if (match) {
        count++;
        at += needle.length;
      } else {
        at++;
      }
    }
    return count;
  }

  @Test
  void no_source_names_the_reference_box() throws IOException {
    assertEquals(
        List.of(),
        scan(REFERENCE_BOX, path -> !path.startsWith("docs/")),
        "these tracked files outside docs/ hold the synthetic scanner fixture's address"
            + " literally. No test may reach a real model endpoint or a real remote"
            + " machine: an endpoint a test needs is MockWebServer on a loopback"
            + " port it was given. Historical documentation is excluded from"
            + " the source scan; current examples may be anonymized.");
  }

  @Test
  void no_source_carries_an_api_key() throws IOException {
    assertEquals(
        List.of(),
        scan(KEY_PREFIX, within(UNDER_SRC)),
        "these files under plowshare-*/src hold the prefix an LM Studio API key starts"
            + " with. The key belongs in the server's own configuration, which is"
            + " the file no workspace may cover, and it is never committed. If this"
            + " has already been pushed, rotating the key comes before editing the"
            + " file. This message names the files and not what is in them, which"
            + " is the same rule that keeps the pattern above assembled rather than"
            + " written out.");
  }

  /**
   * The rule for who may address a presence is written down, with the condition that retires it.
   *
   * <p><b>The one invariant here that is about a sentence rather than about a string nobody should
   * have committed</b>, and it is here because it guards the same class of failure from the other
   * side. The others say <em>this must not appear</em>; this one says <em>this must not
   * disappear</em>.
   *
   * <p>Presence let a caller cause reads and writes on a machine they are not sitting at. That is a
   * new capability, and the design spec's §4 says what must not happen to it: <em>"What must not
   * happen is that the rule is absent and the behaviour is discovered."</em> The rule is that
   * {@code plowshare.auth} is single-user — one operator, one credential — so the operator may
   * address any presence they have running, and it is an argument from there being exactly one
   * principal rather than a permission anybody granted.
   *
   * <p><b>Nothing else can hold it.</b> The rule's implementation is the <em>absence</em> of a
   * check: {@code AgentsConfig.runProviders} routes on the project and never looks at who asked. No
   * behavioural test can distinguish "we decided one principal needs no check" from "nobody thought
   * about it" — both compile to the same code, and both pass every test in this repository. What
   * separates them is the paragraph, so the paragraph is what is pinned.
   *
   * <p>Three phrases and not one: the <b>rule</b>, the <b>reason</b> it holds, and the <b>condition
   * under which it stops holding</b>. A rewrite that kept the rule and dropped the third would
   * leave the next person to add a second user with nothing telling them this is the sentence to
   * revisit.
   */
  @Test
  void the_presence_registry_states_who_may_address_a_presence() throws IOException {
    String rule = Files.readString(ROOT.resolve(PRESENCE_REGISTRY), StandardCharsets.UTF_8);

    assertTrue(
        rule.contains("the operator may address any presence they have running"),
        PRESENCE_REGISTRY
            + " no longer states the rule for who may address a"
            + " presence. A caller can cause reads and writes on a machine they are"
            + " not sitting at, and the code that permits it is the ABSENCE of a"
            + " check — so nothing but this sentence can tell a reader it was"
            + " decided rather than overlooked.");
    assertTrue(
        rule.contains("single-user"),
        PRESENCE_REGISTRY
            + " states the rule without the reason it holds. 'The"
            + " operator may address any presence' is an argument from"
            + " plowshare.auth being single-user — one operator, one credential —"
            + " and without that it reads as a permission somebody granted.");
    assertTrue(
        rule.contains("MULTI-USER"),
        PRESENCE_REGISTRY
            + " no longer says what retires the rule. The moment there"
            + " are two principals, 'one operator' stops being true and this is the"
            + " paragraph that has to change; nothing else in the system would"
            + " notice, which is why the marker is part of the invariant and not"
            + " part of the prose around it.");
  }

  /**
   * The guard is looking at something, which is the half a loop over an empty set cannot tell you.
   *
   * <p>Four containment assertions all shaped "this scan found nothing" are four assertions that a
   * wrong root, a shallow clone, a mistyped scope pattern or a mistyped needle turns green without
   * reading a file. That is the instrument-that-cannot-record shape this project has been caught by
   * before, and an empty green run is exactly what it looks like from outside.
   *
   * <p>So this asserts the two things each of the four needs and cannot supply for itself: that its
   * <b>scope</b> selected files, and that its <b>needle</b> is spelled correctly — proved by
   * requiring each needle to match somewhere the invariant deliberately permits. Sensitive markers
   * use temporary fixtures in {@link #containment_scans_detect_and_filter_sensitive_markers} so
   * documentation can remove deployment identities without breaking the scanner's positive
   * controls.
   *
   * <p><b>{@link #API_CONTROLLER_JAVA} does not strictly need a line here.</b> {@link
   * #BAD_REQUEST_THROW_COUNTS} is pinned against a non-empty, mostly nonzero map rather than
   * against an empty list, so a wrong root or a mistyped scope pattern already fails that assertion
   * loudly — an empty {@link #badRequestThrowCounts()} cannot equal a thirteen-entry expectation by
   * accident, unlike the four scans above whose expectation <em>is</em> the empty list a broken
   * scope also produces. The check is added anyway, for the same reason a belt is worn with
   * suspenders that already hold: it turns a thirteen-line map diff in a failure message into one
   * sentence naming the scope itself.
   */
  @Test
  void the_guard_can_see_the_files_it_asserts_over() throws IOException {
    assertTrue(
        Files.isRegularFile(ROOT.resolve("settings.gradle.kts")),
        "git named "
            + ROOT
            + " as the repository root and there is no"
            + " settings.gradle.kts in it, so every scan below is walking the wrong"
            + " tree and reporting it clean");
    assertTrue(
        TRACKED.size() > 100,
        "the index holds "
            + TRACKED.size()
            + " files, which is too few to have been"
            + " this repository — a shallow or single-branch clone, or ls-files run"
            + " from somewhere other than "
            + ROOT);

    assertFalse(
        matching(within(CLIENT_MAIN_JAVA)).isEmpty(),
        "no tracked .java file matched plowshare-client/src/main, so the Spring"
            + " invariant asserted over nothing");
    assertFalse(
        matching(within(MAIN_JAVA)).isEmpty(),
        "no tracked .java file matched plowshare-*/src/main, so the HTTP client count"
            + " asserted over nothing");
    assertFalse(
        matching(path -> !path.startsWith("docs/")).isEmpty(),
        "every tracked file is under docs/, so the address invariant asserted over" + " nothing");
    assertFalse(
        matching(within(UNDER_SRC)).isEmpty(),
        "no tracked file matched plowshare-*/src, so the API key invariant asserted"
            + " over nothing");
    assertFalse(
        matching(within(API_CONTROLLER_JAVA)).isEmpty(),
        "no tracked .java file matched the api/ *Controller.java scope, so the"
            + " BadRequestException throw-count invariant asserted over nothing");

    assertFalse(
        scan(SPRING, within(SERVER_MAIN_JAVA)).isEmpty(),
        "the server module names "
            + SPRING
            + " on every other page and this scan"
            + " found it nowhere, so the needle or the file reading is broken and"
            + " the client-module assertion proves nothing. It is also the thing"
            + " the client-only scope exists to exclude: with no hit here, that"
            + " scope is filtering nothing out.");
    assertFalse(
        scan(HTTP_CLIENT, outsideMain()).isEmpty(),
        "the HTTP client import was found in no tracked file outside src/main, so"
            + " either the assembled import needle is misspelled — which would make"
            + " the count of six vacuous — or the two test holders this class"
            + " names in its scope note are gone and the note is now wrong");
  }

  @Test
  void containment_scans_detect_and_filter_sensitive_markers(@TempDir Path root)
      throws IOException {
    String document = "docs/example.md";
    String source = "plowshare-server/src/test/GuardFixture.java";
    String markers =
        String.join(".", "198", "51", "100", "122") + " " + String.join("-", "sk", "lm", "");
    for (String path : List.of(document, source)) {
      Files.createDirectories(root.resolve(path).getParent());
      Files.writeString(root.resolve(path), markers);
    }
    List<String> tracked = List.of(document, source);
    assertEquals(
        List.of(document), scan(root, tracked, REFERENCE_BOX, path -> path.startsWith("docs/")));
    assertEquals(
        List.of(source), scan(root, tracked, REFERENCE_BOX, path -> !path.startsWith("docs/")));
    assertEquals(List.of(source), scan(root, tracked, KEY_PREFIX, within(UNDER_SRC)));
    assertEquals(
        List.of(document),
        scan(root, tracked, KEY_PREFIX, path -> !UNDER_SRC.matcher(path).matches()));
  }

  /**
   * Tracked Java under {@code src} but not under {@code src/main} — the part of the tree the HTTP
   * client count's scope deliberately leaves out.
   */
  private static Predicate<String> outsideMain() {
    return path -> ANY_JAVA.matcher(path).matches() && !MAIN_JAVA.matcher(path).matches();
  }

  /** Tracked paths in {@code scope}. */
  private static List<String> matching(Predicate<String> scope) {
    return TRACKED.stream().filter(scope).sorted().toList();
  }

  /**
   * Tracked files in {@code scope} whose bytes hold {@code needle}, sorted.
   *
   * <p>A path the index holds but the working tree does not is skipped rather than failing: a file
   * that is not there cannot be carrying anything, and making a half-finished {@code git rm} fail
   * this guard would teach people to ignore it.
   */
  private static List<String> scan(String needle, Predicate<String> scope) throws IOException {
    return scan(ROOT, TRACKED, needle, scope);
  }

  private static List<String> scan(
      Path root, List<String> tracked, String needle, Predicate<String> scope) throws IOException {
    byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
    List<String> hits = new ArrayList<>();
    for (String path : tracked) {
      if (!scope.test(path)) {
        continue;
      }
      Path file = root.resolve(path);
      if (Files.isRegularFile(file) && holds(Files.readAllBytes(file), pattern)) {
        hits.add(path);
      }
    }
    return hits.stream().sorted().toList();
  }

  private static Predicate<String> within(Pattern scope) {
    return path -> scope.matcher(path).matches();
  }

  /** The two scans, deduplicated — a file matching both needles is one holder, not two. */
  private static List<String> union(List<String> first, List<String> second) {
    Set<String> combined = new LinkedHashSet<>(first);
    combined.addAll(second);
    return List.copyOf(combined);
  }

  private static List<String> simpleNames(List<String> paths) {
    return paths.stream().map(path -> path.substring(path.lastIndexOf('/') + 1)).sorted().toList();
  }

  private static boolean holds(byte[] content, byte[] needle) {
    candidate:
    for (int at = 0; at + needle.length <= content.length; at++) {
      for (int offset = 0; offset < needle.length; offset++) {
        if (content[at + offset] != needle[offset]) {
          continue candidate;
        }
      }
      return true;
    }
    return false;
  }

  private static Path repositoryRoot() {
    String top = git(null, "rev-parse", "--show-toplevel").strip();
    if (top.isEmpty()) {
      throw new IllegalStateException(
          "`git rev-parse --show-toplevel` said nothing; these invariants are asserted"
              + " over a git working tree and there is not one here");
    }
    return Path.of(top);
  }

  private static List<String> tracked() {
    List<String> paths = new ArrayList<>();
    for (String path : git(ROOT, "ls-files", "-z").split("\0")) {
      if (!path.isEmpty()) {
        paths.add(path);
      }
    }
    return List.copyOf(paths);
  }

  /**
   * {@code git} run in {@code directory} — or the process's own working directory when that is null
   * — with its output as text.
   *
   * <p>A non-zero exit is fatal rather than an empty result, because an empty result is precisely
   * what this whole class must never quietly accept.
   */
  private static String git(Path directory, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      if (directory != null) {
        builder.directory(directory.toFile());
      }
      Process git = builder.start();
      String out;
      try (InputStream stdout = git.getInputStream()) {
        out = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
      }
      String problem = new String(git.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int status = git.waitFor();
      if (status != 0) {
        throw new IllegalStateException(command + " exited " + status + ": " + problem.strip());
      }
      return out;
    } catch (IOException e) {
      throw new IllegalStateException(command + " could not be run", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(command + " was interrupted", e);
    }
  }
}
