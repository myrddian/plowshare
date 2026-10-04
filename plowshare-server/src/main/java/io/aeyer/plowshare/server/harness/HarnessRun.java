package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.server.hooks.HarnessHook;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One run's harness hooks. Built per profile the first time a model running it is met, and kept for
 * the run, so a hook's fields are its state for this run alone.
 *
 * <h2>A hook that cannot be built or cannot finish is not the run's problem</h2>
 *
 * <p>{@code Hooks}' own contract says an implementation must not throw, and {@code JobRuntime}'s
 * guards exist because a hook nobody trusted less than that still might. The same is true one step
 * earlier and one step later, at the two places outside that contract where a hook's own code runs
 * unguarded by anything else: {@link HarnessHookFactory#create} and {@link HarnessHook#finish}.
 * Both are guarded here, on the same terms — a failure is turned into a {@code failed} {@link
 * HookRecord} naming the hook, and the turn (or the run) goes on without it.
 */
public final class HarnessRun {

  private final Harness harness;
  private final Map<String, Hooks> byProfile = new LinkedHashMap<>();
  private final List<Built> built = new ArrayList<>();
  private final List<HookRecord> buildFailures = new ArrayList<>();
  private boolean finished;

  /** What the person said this run, once the run has fired prompt.pre; null before. */
  private HookContext spokeIn;

  private String utterance;

  /** One hook this run actually built, kept with its declared name for {@link #finish}. */
  private record Built(String name, HarnessHook hook) {}

  HarnessRun(Harness harness) {
    this.harness = harness;
  }

  /**
   * The run has fired prompt.pre with the person's words, through whatever profile its first model
   * runs.
   *
   * <p><b>A profile met later never saw that stage.</b> prompt.pre fires once, before the first
   * call; a reroute to a fallback with a different profile builds its hooks steps later, and a hook
   * that learns the question there — the stuck trap's brief opens with it — would otherwise know
   * nothing. So the utterance is kept, and every profile built after this is shown it once as it is
   * built. Profiles built before this call got the real stage through the chain, so they are not
   * shown it twice.
   */
  public synchronized void spoke(HookContext context, String utterance) {
    if (this.utterance == null) {
      this.spokeIn = context;
      this.utterance = utterance;
    }
  }

  /**
   * The harness hooks for the profile {@code wireModel} runs, chained in order.
   *
   * <p><b>Memoized per profile, and that is what keeps a hook that failed to build from being
   * retried.</b> {@code computeIfAbsent} calls {@link #buildProfile} at most once per profile name
   * for the life of this run, whether every hook in it built cleanly, one of them threw, or all of
   * them did — the chain (possibly {@link Hooks#NONE}) and the failures already happened are both
   * settled the first time and simply returned after that.
   */
  public synchronized Hooks forModel(String wireModel) {
    String profile = harness.profileFor(wireModel);
    if (profile == null) {
      return Hooks.NONE;
    }
    return byProfile.computeIfAbsent(profile, this::buildProfile);
  }

  private Hooks buildProfile(String name) {
    List<Harness.Configured> configured = harness.configured(name);
    if (configured.isEmpty()) {
      return Hooks.NONE;
    }
    List<Hooks> layers = new ArrayList<>();
    for (Harness.Configured one : configured) {
      String hookName = one.factory().name();
      try {
        HarnessHook hook = one.factory().create(one.parameters());
        built.add(new Built(hookName, hook));
        layers.add(hook);
        heard(hookName, hook);
      } catch (RuntimeException broken) {
        // Left out of the chain rather than let this profile carry a hook
        // that already proved it cannot be trusted to run. PROMPT_PRE: the
        // earliest stage a run has, and the honest one to name here --
        // this failure happened before any stage was ever served, and
        // forModel carries no stage of its own to be more specific with.
        buildFailures.add(
            new HookRecord(
                hookName,
                null,
                Tier.HARNESS,
                Stage.PROMPT_PRE,
                null,
                HookRecord.FAILED,
                describe(broken),
                null,
                null,
                0));
      }
    }
    if (layers.isEmpty()) {
      return Hooks.NONE;
    }
    return layers.size() == 1 ? layers.get(0) : Hooks.chain(layers.toArray(new Hooks[0]));
  }

  /**
   * A hook built after prompt.pre is shown the utterance it missed, and what it returns is dropped:
   * the prompt was sent steps ago, and a late profile must not add text to it mid-run. Guarded like
   * {@code create}: a hook that throws here is a {@code failed} record, and it stays in the chain —
   * the stage failed, not the build, and a live prompt.pre that throws keeps its hook too.
   */
  private void heard(String hookName, HarnessHook hook) {
    if (utterance == null) {
      return;
    }
    try {
      hook.promptPre(spokeIn, utterance);
    } catch (RuntimeException broken) {
      buildFailures.add(
          new HookRecord(
              hookName,
              null,
              Tier.HARNESS,
              Stage.PROMPT_PRE,
              null,
              HookRecord.FAILED,
              describe(broken),
              null,
              null,
              0));
    }
  }

  /**
   * The run is over: every built hook finishes once, and says what to record.
   *
   * <p>One hook's {@code finish()} throwing does not cost the others theirs: each is guarded on its
   * own, so a run with three built hooks and one broken {@code finish} still gets the other two's
   * records back, plus one {@code failed} record naming the one that broke.
   */
  public synchronized List<HookRecord> finish() {
    if (finished) {
      return List.of();
    }
    finished = true;
    List<HookRecord> records = new ArrayList<>(buildFailures);
    for (Built one : built) {
      try {
        records.addAll(one.hook().finish());
      } catch (RuntimeException broken) {
        // STEP_POST, matching every fixture's own finish() record in this
        // codebase (HarnessTest.Counting, HookedRunTest.Noting): it is
        // what a hook's account of the whole run is filed under, and a
        // failed account belongs beside a reported one.
        records.add(
            new HookRecord(
                one.name(),
                null,
                Tier.HARNESS,
                Stage.STEP_POST,
                null,
                HookRecord.FAILED,
                describe(broken),
                null,
                null,
                0));
      }
    }
    return records;
  }

  /**
   * The failure's type and the first line of its message, and never the rest. {@code
   * JobRuntime.describe} argues the same rule at length; it cannot be shared because it lives a
   * package away and this class must not depend on {@code agents} to say the one sentence it needs
   * here.
   */
  private static String describe(RuntimeException failed) {
    String message = failed.getMessage();
    String first = message == null ? "" : message.lines().findFirst().orElse("");
    return first.isBlank()
        ? failed.getClass().getSimpleName()
        : failed.getClass().getSimpleName() + ": " + first;
  }
}
