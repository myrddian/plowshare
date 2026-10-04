package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * The guard on the guard: this module's deadlock bound is in force, and JUnit says so rather than a
 * file saying so.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Slice 3c's task 4 put a 120-second default timeout in a {@code junit-platform.properties} at
 * each module's test classpath root, and measured two things that together leave the bound able to
 * vanish in silence. A malformed value — {@code 120 seconds} instead of {@code 120 s} — is ignored
 * with a {@code WARNING: Ignored invalid timeout} on the <b>test worker's stderr</b>, and there is
 * <b>no timeout at all</b> with the suite still green; and Gradle does not surface worker stderr at
 * its default log level, so that warning is invisible to the person running {@code check}.
 *
 * <p>That task's own annotation then declined to mechanise anything, on the grounds that "a
 * divergence changes one module's bound, not the correctness of any test". Those two findings
 * disagree. A divergent <em>value</em> changes a bound; a <b>malformed</b> value, a deleted file,
 * or a file that is on disk but not on this module's test classpath removes the bound
 * <b>entirely</b> — which is the whole difference between a deadlock failing the build and hanging
 * it. The evidence of compliance and the evidence of absence were identical, which is the fault
 * this slice exists to close.
 *
 * <h2>Why the configuration parameter and not the file</h2>
 *
 * <p>What is asserted is what <b>JUnit resolved</b>, through {@link
 * ExtensionContext#getConfigurationParameter(String)}, and not the bytes of a file. Reading the
 * file back would prove a file exists somewhere a classloader can see; it would not prove the
 * platform read <em>that</em> file, and the wrong-classpath case is precisely the one where those
 * two answers differ. One question — "what bound is this module actually running under?" — asked of
 * the only component that knows.
 *
 * <h2>Why three copies of this class, when task 5 refused thirteen</h2>
 *
 * <p>The two decisions look opposite and are not, and the difference is worth stating because this
 * slice made both. Task 5 declined to put one paragraph of <em>prose</em> on thirteen container
 * declarations: nothing would have checked the thirteen against each other, so they were thirteen
 * independent chances to rot. These three copies are <em>executable</em> and each asserts the same
 * two literals, so a divergence between the three modules fails a build rather than sitting in a
 * file. <b>Duplication is affordable exactly when a mechanism checks that the copies still
 * agree</b>, and that is the rule this slice should be read as having settled on.
 *
 * <p>Three and not one because a configuration parameter is a property of the engine run in
 * <em>one</em> module's classpath; a class in the server module cannot see whether the client's
 * copy survived.
 *
 * <p><b>What this still does not guard</b>, said rather than left implied: the fifty lines of
 * comment above the two properties are prose, and nothing checks that the three copies of
 * <em>those</em> agree. Only the values are held here.
 */
@ExtendWith(TimeoutConfiguredTest.Configuration.class)
class TimeoutConfiguredTest {

  /**
   * The bound every module runs under. One literal, asserted in three modules, so a bound changed
   * in one place fails in the other two.
   */
  private static final String EXPECTED_TIMEOUT = "120 s";

  /**
   * Without this the bound is an instrument that cannot record a real deadlock: {@code SAME_THREAD}
   * interrupts, and an interrupt is a request a wedged thread declines. Measured at task 4: 20.58s
   * against 2.59s.
   */
  private static final String EXPECTED_THREAD_MODE = "SEPARATE_THREAD";

  private static final String TIMEOUT_KEY = "junit.jupiter.execution.timeout.default";

  private static final String THREAD_MODE_KEY =
      "junit.jupiter.execution.timeout.thread.mode.default";

  /**
   * JUnit's own grammar for a timeout, from its documentation: a number, then an optional unit. A
   * value outside it is discarded with a warning on a stream nobody reads, which is the failure
   * this class exists for — so it is asserted separately from the value, to tell "somebody changed
   * the bound" apart from "somebody wrote something JUnit silently threw away".
   */
  private static final Pattern WELL_FORMED =
      Pattern.compile("^\\s*\\d+\\s*(ns|μs|ms|s|m|h|d)?\\s*$");

  @Test
  void this_module_runs_under_the_deadlock_bound_junit_actually_resolved(ExtensionContext context) {
    Optional<String> configured = context.getConfigurationParameter(TIMEOUT_KEY);

    assertTrue(
        configured.isPresent(),
        "JUnit resolved no "
            + TIMEOUT_KEY
            + " for this module, so nothing in it is"
            + " bounded and a wedged test hangs the build instead of failing it."
            + " The value lives in this module's own"
            + " src/test/resources/junit-platform.properties, read from its test"
            + " classpath root: a deleted file and a file that is present but not"
            + " on the classpath both land here.");

    String value = configured.get();
    assertTrue(
        WELL_FORMED.matcher(value).matches(),
        "JUnit resolved "
            + TIMEOUT_KEY
            + "=\""
            + value
            + "\", which is not <number>"
            + " [ns|μs|ms|s|m|h|d]. A malformed value is DISCARDED -- the run"
            + " gets no timeout at all, the suite stays green, and the only notice"
            + " is a WARNING on the test worker's stderr that Gradle does not show"
            + " at its default log level. \"120 seconds\" is the spelling that does"
            + " this; \"120 s\" is the one that works.");

    assertEquals(
        EXPECTED_TIMEOUT,
        value,
        "this module's deadlock bound is \""
            + value
            + "\" and the other two modules"
            + " assert \""
            + EXPECTED_TIMEOUT
            + "\". The three"
            + " junit-platform.properties files are meant to be byte-identical;"
            + " change all three or none.");
  }

  @Test
  void the_bound_abandons_a_wedged_thread_rather_than_asking_it_to_stop(ExtensionContext context) {
    Optional<String> configured = context.getConfigurationParameter(THREAD_MODE_KEY);

    assertTrue(
        configured.isPresent(),
        "JUnit resolved no "
            + THREAD_MODE_KEY
            + " for this module, so the timeout above"
            + " runs in SAME_THREAD, JUnit's default. SAME_THREAD interrupts the"
            + " test thread and then waits for it, and a real deadlock is exactly"
            + " the case that declines an interrupt -- so the bound would report a"
            + " hang only once the hang ended on its own.");

    assertEquals(
        EXPECTED_THREAD_MODE,
        configured.get(),
        "this module resolves "
            + THREAD_MODE_KEY
            + "=\""
            + configured.get()
            + "\"; the"
            + " bound only fails a genuine deadlock under "
            + EXPECTED_THREAD_MODE
            + ", which abandons the wedged thread rather than asking it to stop.");
  }

  /**
   * Hands the running {@link ExtensionContext} to a test method.
   *
   * <p>JUnit resolves {@code TestInfo}, {@code TestReporter} and {@code RepetitionInfo} out of the
   * box and <b>not</b> {@code ExtensionContext}, which is offered to extensions rather than to
   * tests. Six lines here is the whole cost of asking the platform what it resolved instead of
   * asking the filesystem what it holds.
   */
  static final class Configuration implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
      return parameter.getParameter().getType() == ExtensionContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
      return context;
    }
  }
}
