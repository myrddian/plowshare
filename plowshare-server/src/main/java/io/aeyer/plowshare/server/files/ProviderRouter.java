package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Home;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which filesystem a path belongs to, across every provider a job can reach.
 *
 * <p>Resolution in this slice is two-layered and <b>the layers have different rules</b>. Within a
 * provider, {@link FileAccess#permits} applies Excalibur's longest-match with exclusions. Across
 * providers there is no tiebreak at all: two providers covering one path is refused with both
 * named, because the same string on two machines is two different files. {@link
 * AmbiguousPathException} carries that argument.
 *
 * <h2>The providers are asked for per {@link Home}, not held</h2>
 *
 * <p>One of these is shared by every job on this server, because {@code AgentTool} requires a tool
 * to be safe to call from several threads at once and the file tools hold this. A provider is not
 * shareable that way — {@link LocalProvider} carries one job's tier and one agent's grants — so
 * what this class holds is a {@link Providers}, asked afresh on every call.
 *
 * <p>That is also why {@link #providerFor} takes a {@code Home}: the tier is what selects the set.
 * A router holding a fixed list would answer for whatever job happened to build it, which for a
 * shared object is any job at all, and the containment this whole slice is about would come apart
 * at the one seam nothing downstream re-checks. {@code the_home_is_what_selects_the_providers} is
 * what holds it there.
 *
 * <h2>The problem this class exists to solve: a dead provider is not the run's death unless it
 * could have changed the answer</h2>
 *
 * <p>{@link FileProvider#roots()} raises {@link WorkspaceUnavailableException} when a root has
 * vanished. That is right for a job whose own workspace has disappeared and <b>wrong for a
 * router</b>: polling every provider in order to route one path would end a run over a dead
 * <em>local</em> workspace even for a path belonging plainly to the remote provider. Task 3 named
 * the problem here rather than leaving it to be discovered.
 *
 * <p><b>The rule: a provider that could not be asked is fatal only when no provider that could be
 * asked covers the path.</b> A provider that cannot be reached cannot serve the request either way,
 * so its candidacy is moot as long as somebody else can answer. When nothing that answered covers
 * the path, the unavailability propagates instead, because "outside every root" would then be a
 * claim about roots nobody could read — the confident wrong answer, in the one shape this project
 * keeps meeting it.
 *
 * <p><b>The rule weakens the ambiguity refusal in one case, and that is argued in full at the line
 * that does it</b> — the {@code covering.size() == 1} return in {@link #providerFor}, which is
 * where the remembered unavailability is discarded. It is not restated here, because the whole
 * argument existed twice for one commit and the next edit to either copy would have left the other
 * stale, on the single load-bearing claim in this class.
 *
 * <p>The pair {@code a_dead_provider_does_not_end_a_run_over_a_path_that_belongs_to_another} and
 * {@code a_dead_provider_ends_the_run_when_nothing_that_answered_covers_the_path} holds both halves
 * of the rule; either one alone is satisfied by a router that always propagates, or by one that
 * always ignores.
 *
 * <p>They pin the <em>behaviour</em> of the weakened case too — the covering provider is returned,
 * and the mutant that drops that return dies. <b>What no test can express is the counterfactual
 * harm</b>: whether the provider that could not be asked would also have covered the path. That is
 * unknowable to the router at run time, so it is unknowable to a fixture as well, which is not a
 * limitation of the fake but the thing itself.
 *
 * <h2>Canonicalising is not optional here, and it is done once</h2>
 *
 * <p>A provider advertises canonical roots — {@link LocalProvider}'s come out of {@link
 * FileAccess}, which real-paths them — so a candidate compared as it was typed is outside every
 * root on any host where the tree is reached through a symlink, which is every host whose temp
 * directory is under {@code /tmp} or {@code /var}.
 *
 * <p><b>The path handed on to the provider is the one the caller typed, not the canonical form</b>,
 * and that is deliberate rather than sloppy. {@link FileAccess#canonical} is not idempotent past
 * its hop budget, so canonicalising here and again in the provider would resolve two different
 * distances along a long link chain and could route on one file and open another. Passing the
 * original means both computations are {@code canonical(p)} of the same {@code p} and cannot
 * disagree. The rule that javadoc states — canonicalise once, ask about the answer, open the answer
 * — is upheld inside each of the two, which is where it has to hold.
 */
public final class ProviderRouter {

  /**
   * The trace task 4 recorded as owed, and this is it.
   *
   * <p><b>Written on exactly one line</b> — the {@code covering.size() == 1} return in {@link
   * #providerFor}, where a provider that could not be asked is dropped from candidacy. That comment
   * argues why the rule is right and what it costs; what it left open is that nothing anywhere said
   * it had happened, so an operator reading a job that behaved oddly had no way to see that a
   * second filesystem was never consulted.
   *
   * <p>Task 4 declined to add this then, on the grounds that a per-call log line on a hot path is
   * the wiring task's decision. <b>It is not on the hot path.</b> It fires only when some provider
   * raised {@link WorkspaceUnavailableException} while another covered the path — a client that has
   * gone, a workspace that has been deleted, a project name no row can ever be defined for — so an
   * ordinary routing call writes nothing at all.
   *
   * <p>{@code warn} and not {@code debug}, because <b>the run looks healthy afterwards</b>. The
   * model gets a file, the tool succeeds, the job answers, and this line is the only record
   * anywhere that the answer came from one of two machines that might both have had that path. A
   * level nobody has on by default would be the same silence with a different name.
   */
  private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

  /**
   * The providers a job in one tier can reach.
   *
   * <p>An interface rather than a {@code Function} so the parameter has a name at the call site and
   * so wiring can implement it as a class. The list may be empty — a job with no filesystem is an
   * ordinary state — and it is re-asked on every call, because a job's providers can change during
   * a run when task 7's client connects or goes away.
   */
  @FunctionalInterface
  public interface Providers {

    /** The providers for one tier, in the order they should be reported. */
    List<FileProvider> forHome(Home home);
  }

  /**
   * The pattern the absence probe searches with.
   *
   * <p>Any valid relative pattern does. It has to be <em>valid</em> — a blank or absolute pattern
   * is refused by the provider on its own account, and the sentence coming back would be about the
   * pattern rather than about the workspace, which is trap 7 with the tool's own hand on it.
   *
   * <p><b>Usually free, and not guaranteed to be.</b> The probe runs only against a provider that
   * has just advertised no roots, so on {@link LocalProvider} there is no tree to walk. But {@link
   * FileProvider#roots()} says explicitly that roots may grow between two calls, so for {@code
   * RemoteProvider} this is a real round trip and could reach a real tree that appeared in between.
   * The answer is still correct — that is what the branch below the {@code catch} is for — but
   * "costs nothing" was an absolute an earlier version of this sentence had no business making
   * about a provider that lives on another machine.
   */
  private static final String PROBE = "*";

  private final Providers providers;

  public ProviderRouter(Providers providers) {
    this.providers = Objects.requireNonNull(providers, "providers");
  }

  /**
   * Every provider this tier can reach, for the caller that asks all of them rather than routing
   * one path — {@code file_roots}.
   */
  public List<FileProvider> providersFor(Home home) {
    Objects.requireNonNull(home, "home");
    return List.copyOf(providers.forHome(home));
  }

  /**
   * The one provider that covers this path.
   *
   * @param home the tier the job runs in, which selects the provider set. Not an argument any model
   *     chooses; see {@code AgentTool}
   * @param path the path as the caller spelled it. Canonicalised here for the comparison and handed
   *     on unchanged; the class javadoc says why
   * @throws AmbiguousPathException if two providers cover it
   * @throws WorkspaceRefusedException if none does and every provider was able to say so — a
   *     caller's mistake, carrying each provider's own account of what it does cover, or of why it
   *     covers nothing
   * @throws WorkspaceUnavailableException if none does and some provider could not be asked at all,
   *     in which case nothing can be concluded
   */
  public FileProvider providerFor(Home home, Path path) {
    // No null checks of its own. Both arguments are dereferenced a line or
    // two down — `canonical` on the path, `providersFor` on the home, which
    // does check — so a requireNonNull here is a line no mutant can kill,
    // and this project removes those rather than keeping them for the shape.
    // The construction-time check on `providers` is the one that earns its
    // place: without it the wiring bug surfaces at the first file call
    // instead of at the frame that made it.
    Path candidate = FileAccess.canonical(path);

    List<FileProvider> all = providersFor(home);
    List<FileProvider> covering = new ArrayList<>();
    List<FileProvider> answered = new ArrayList<>();
    List<List<Path>> rootsOf = new ArrayList<>();
    WorkspaceUnavailableException unreachable = null;

    for (FileProvider provider : all) {
      List<Path> roots;
      try {
        roots = provider.roots();
      } catch (WorkspaceUnavailableException gone) {
        // Remembered rather than rethrown, and only the first: two
        // sentences merged into one would be a report whose content
        // depends on iteration order, and the caller who reads a job's
        // ending needs the same sentence every time it happens.
        //
        // FIRST-WINS NOW DECIDES MORE THAN A SENTENCE. Since task 8 this
        // type has a subtype with an ending of its own, so which of two
        // dead providers is remembered picks SESSION_GONE or
        // UNAVAILABLE. The argument for it is unchanged and is at the
        // `throw unreachable` below rather than copied here;
        // which_of_two_dead_providers_is_reported_decides_the_ending_as_well
        // holds both directions.
        if (unreachable == null) {
          unreachable = gone;
        }
        continue;
      }
      answered.add(provider);
      rootsOf.add(roots);
      if (covers(roots, candidate)) {
        covering.add(provider);
      }
    }

    if (covering.size() > 1) {
      throw new AmbiguousPathException(
          "path "
              + path
              + " is covered by more than one"
              + " filesystem — "
              + named(covering)
              + " — and the same path on two"
              + " machines is two different files, so this will not be guessed at."
              + " Name a path that only one of them covers.");
    }
    if (covering.size() == 1) {
      // THE ONE PLACE THE AMBIGUITY REFUSAL IS WEAKENED, and the whole
      // argument lives here rather than in the class javadoc, because this
      // is the line that does it.
      //
      // `unreachable` is discarded. If the provider that could not be
      // asked would ALSO have covered this path, the model is handed one
      // machine's file where the spec says both should have been named,
      // and nothing anywhere says so.
      //
      // An earlier javadoc denied this, on the argument that "an
      // unreachable provider hands back nothing". That is a claim about
      // this request's mechanics; the spec's harm model is about WHICH
      // file the model ends up reading, and it reads the other machine's.
      //
      // Not exotic, either: LocalProvider raises for a workspace that is
      // no longer a directory and for a project name no row can ever be
      // defined for, not only for a deleted tree — while the spec's own
      // worked example is two providers holding one absolute path across
      // two machines.
      //
      // LEFT OPEN BECAUSE EVERY STRICTER RULE IS WORSE. An unreachable
      // provider's coverage is unknowable — that is what unreachable
      // means — so the only alternative is to treat any unavailability as
      // fatal, which collapses back into "a dead local workspace kills
      // every path lookup", the behaviour this rule exists to avoid.
      // There is no third option that does not require knowing the roots
      // we just failed to read.
      //
      // WHAT IS OWED INSTEAD IS A TRACE, AND IT IS TASK 7'S. A provider
      // dropped from candidacy should leave one, so an operator reading a
      // job that behaved oddly can see that a second filesystem was never
      // consulted. IT IS HERE NOW, on the line below: `log`'s javadoc
      // argues the level and why this is not the hot path task 4 was
      // worried about.
      if (unreachable != null) {
        log.warn(
            "Routing {} to the '{}' filesystem while another on this run could"
                + " not be asked ({}). If the one that could not be asked also"
                + " covered this path, the answer came from one of two machines and"
                + " nothing else will say so.",
            path,
            covering.get(0).name(),
            unreachable.getMessage());
      }
      return covering.get(0);
    }
    if (unreachable != null) {
      // Its own sentence and not one built here: the reason travels with
      // the ending, and inventing a second one would leave an operator
      // reading about a router when the thing that broke is a disk.
      //
      // AND ITS OWN INSTANCE, WHICH IS NOW LOAD-BEARING. Task 8 narrowed
      // this type: SessionGoneException extends it and JobRuntime reads
      // the subtype to reach SESSION_GONE, so rethrowing a fresh
      // WorkspaceUnavailableException here would downgrade the ending
      // where nothing could see it. Note also that only the FIRST
      // unavailability is remembered, so a local disk that failed before a
      // remote session went is the one reported — the same
      // iteration-order rule the comment above sets out, and a bounded
      // cost, since either way the run stops and says what could not be
      // asked.
      throw unreachable;
    }
    throw outOfScope(path, answered, rootsOf);
  }

  /**
   * The provider's own sentence for advertising no roots.
   *
   * <p><b>How {@code file_roots} reaches the sentence, and why {@link FileProvider} needed no new
   * method for it.</b> A provider can have no root for several distinct reasons, each a different
   * person's fix, and {@link LocalProvider} enumerates them with the one qualifier that matters
   * here — which of them ends the run instead of answering empty. Rendering a bare "you have no
   * roots" for all of them is the collapse Excalibur split {@code NO_ROOTS} and {@code
   * NO_WORKSPACE} apart to stop.
   *
   * <p><b>The list is not repeated here, and this javadoc did repeat it.</b> It said "five states"
   * and dropped the qualifier the owner carries, which made it wrong: in the fifth — a tier whose
   * name no row can ever be defined for — {@code roots()} does not answer empty at all, it raises,
   * so this method is never reached and promises one distinction more than it can deliver. Four
   * copies of that sentence were made and all four lost the qualifier, while {@code
   * ROOTS_DESCRIPTION}, the prose a model reads, had it right. That is the one-owner rule and this
   * is how it broke, so what is left here is a pointer.
   *
   * <p>The interface already promises what is needed: an empty result is reserved for <em>searched,
   * and there was nothing</em>, so a provider with no roots <em>raises</em> rather than answering
   * empty, with the sentence saying which state it is in. Asking it to search is therefore asking
   * it which state it is in. That is derived from the published contract rather than from any
   * implementation's ordering, and it costs task 7 no extra message on the wire.
   *
   * <p>This is the single owner of that trick. {@link #providerFor}'s own refusal and {@code
   * FileTools.Roots} both come through here, so the sentence a model reads is the same one in both
   * places.
   *
   * @throws WorkspaceUnavailableException if the provider could not be asked at all, which is not
   *     an absence and must not be rendered as one
   */
  public static String absence(FileProvider provider) {
    try {
      provider.glob(PROBE);
    } catch (WorkspaceRefusedException named) {
      return named.getMessage();
    }
    // Only reachable through a provider that breaks FileProvider's contract
    // by answering a search it has no root to run — task 7's remote provider
    // is the one that could, since its answer is assembled on another
    // machine. Reported rather than left blank: a file_roots result with a
    // hole in it reads to a model as a tool that does not work.
    return provider.name() + " advertises no root and gave no reason when asked to search";
  }

  /**
   * Whether any of these roots contains the candidate.
   *
   * <p>{@code startsWith} and never a string comparison: measured, it is by path element and
   * reflexive, so {@code /w/repo} contains itself and does not contain {@code /w/repository}. A
   * textual prefix would route a sibling directory's files into a workspace nobody granted.
   *
   * <p>A root covering the candidate is not the same as the provider permitting it — an exclusion
   * inside the root still refuses, and that refusal is the provider's to make with its own
   * sentence. Enforcement stays doubled by design: this narrows, and the provider refuses
   * regardless of what was asked.
   */
  private static boolean covers(List<Path> roots, Path candidate) {
    for (Path root : roots) {
      if (candidate.startsWith(root)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The refusal for a path nothing covers, carrying what each provider does cover.
   *
   * <p>Two sentences and not one, because <b>"outside every root" names a situation that does not
   * hold when there is no root to be outside of</b> — trap 7, at the exact place the absence states
   * {@link LocalProvider} enumerates would otherwise collapse into one shrug. When something has a
   * root, the roots are listed: a refusal that also says where to look is the one that turns into
   * the model's next move rather than into another guess.
   */
  private static WorkspaceRefusedException outOfScope(
      Path path, List<FileProvider> answered, List<List<Path>> rootsOf) {

    if (answered.isEmpty()) {
      return new WorkspaceRefusedException(
          "this job has no filesystem at all, so there is"
              + " nothing at "
              + path
              + " or anywhere else for it to read; a job reaches"
              + " files through the project it runs in");
    }
    boolean anyRoot = rootsOf.stream().anyMatch(roots -> !roots.isEmpty());
    StringJoiner accounts = new StringJoiner("; ");
    for (int i = 0; i < answered.size(); i++) {
      FileProvider provider = answered.get(i);
      List<Path> roots = rootsOf.get(i);
      accounts.add(provider.name() + ": " + (roots.isEmpty() ? absence(provider) : join(roots)));
    }
    if (!anyRoot) {
      return new WorkspaceRefusedException(
          "no root is reachable on this run, so there is"
              + " nothing at "
              + path
              + " or anywhere else to reach — "
              + accounts);
    }
    return new WorkspaceRefusedException(
        "path "
            + path
            + " is outside every root this job"
            + " can reach. What it can reach — "
            + accounts
            + ". Ask for the roots you"
            + " have rather than guessing at paths.");
  }

  private static String named(List<FileProvider> providers) {
    StringJoiner names = new StringJoiner(" and ");
    for (FileProvider provider : providers) {
      names.add(provider.name());
    }
    return names.toString();
  }

  private static String join(List<Path> roots) {
    StringJoiner joined = new StringJoiner(", ");
    for (Path root : roots) {
      joined.add(root.toString());
    }
    return joined.toString();
  }
}
