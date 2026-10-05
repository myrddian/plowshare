package io.aeyer.plowshare.server.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.HarnessHook;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

class HarnessTest {

  /** Counts what it was built with and how often; records nothing unless finished. */
  static final class Counting implements HarnessHookFactory {
    final List<Parameters> built = new ArrayList<>();

    @Override
    public String name() {
      return "harness:counting";
    }

    @Override
    public List<Parameter> parameters() {
      return List.of(
          Parameter.integer("after-steps", 8),
          Parameter.duration("timeout", Duration.ofSeconds(60)),
          Parameter.text("advisor", "system.advisor"));
    }

    @Override
    public HarnessHook create(Parameters parameters) {
      built.add(parameters);
      return new HarnessHook() {
        @Override
        public List<HookRecord> finish() {
          return List.of(
              new HookRecord(
                  "harness:counting",
                  null,
                  Tier.HARNESS,
                  Stage.STEP_POST,
                  null,
                  HookRecord.UNUSED,
                  null,
                  null,
                  null,
                  0));
        }
      };
    }
  }

  /** Throws every time it is asked to build. */
  static final class Failing implements HarnessHookFactory {
    int attempts;

    @Override
    public String name() {
      return "harness:failing";
    }

    @Override
    public List<Parameter> parameters() {
      return List.of();
    }

    @Override
    public HarnessHook create(Parameters parameters) {
      attempts++;
      throw new IllegalStateException("no thanks");
    }
  }

  /** Builds cleanly; throws when asked to finish. */
  static final class FinishFails implements HarnessHookFactory {
    @Override
    public String name() {
      return "harness:finish-fails";
    }

    @Override
    public List<Parameter> parameters() {
      return List.of();
    }

    @Override
    public HarnessHook create(Parameters parameters) {
      return new HarnessHook() {
        @Override
        public List<HookRecord> finish() {
          throw new IllegalStateException("finish broke");
        }
      };
    }
  }

  private static HarnessProperties bound(String yaml) throws Exception {
    StandardEnvironment environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("test", new ByteArrayResource(yaml.getBytes()))
        .forEach(environment.getPropertySources()::addLast);
    return new Binder(ConfigurationPropertySources.get(environment))
        .bind("plowshare.harness", HarnessProperties.class)
        .orElseGet(HarnessProperties::new);
  }

  private static final String PROFILES =
      """
            plowshare:
              harness:
                default-profile: standard
                profiles:
                  guided:
                    - hook: harness:counting
                      after-steps: 4
                      timeout: 5s
                  standard:
                    - hook: harness:counting
                  minimal: []
            """;

  @Test
  void a_model_gets_its_assigned_profile_and_an_unassigned_one_the_default() throws Exception {
    Harness harness =
        new Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                bound(PROFILES), Map.of("openai/gpt-oss-120b", "guided"), List.of(new Counting())));

    assertEquals("guided", harness.profileFor("openai/gpt-oss-120b"));
    assertEquals("standard", harness.profileFor("qwen3-coder"));
    assertEquals("standard", harness.profileFor(null));
  }

  @Test
  void parameters_are_typed_and_defaulted_and_built_once_per_run_per_profile() throws Exception {
    Counting counting = new Counting();
    Harness harness =
        new Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                bound(PROFILES), Map.of("openai/gpt-oss-120b", "guided"), List.of(counting)));

    HarnessRun run = harness.begin();
    Hooks first = run.forModel("openai/gpt-oss-120b");
    Hooks again = run.forModel("openai/gpt-oss-120b");

    assertSame(first, again, "one instance per profile per run");
    assertEquals(1, counting.built.size());
    assertEquals(4, counting.built.get(0).integer("after-steps"));
    assertEquals(Duration.ofSeconds(5), counting.built.get(0).duration("timeout"));
    assertEquals("system.advisor", counting.built.get(0).text("advisor"));
    assertEquals(1, run.finish().size(), "finish reports what each built hook reports");
    harness.begin().forModel("openai/gpt-oss-120b");
    assertEquals(2, counting.built.size(), "a new run builds its own");
  }

  @Test
  void an_empty_profile_runs_no_harness_hooks() throws Exception {
    Counting counting = new Counting();
    Harness harness =
        new Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                bound(PROFILES), Map.of("small", "minimal"), List.of(counting)));

    harness.begin().forModel("small");

    assertEquals(0, counting.built.size());
  }

  @Test
  void no_default_profile_and_no_assignment_is_no_harness() throws Exception {
    Harness harness =
        new Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                bound(
                    """
                plowshare:
                  harness:
                    profiles:
                      guided:
                        - hook: harness:counting
                """),
                Map.of(),
                List.of(new Counting())));

    assertNull(harness.profileFor("anything"));
    assertSame(Hooks.NONE, harness.begin().forModel("anything"));
  }

  @Test
  void every_mistake_a_profile_can_make_refuses_to_boot_naming_the_key() throws Exception {
    List<HarnessHookFactory> known = List.of(new Counting());
    IllegalStateException unknownHook =
        assertThrows(
            IllegalStateException.class,
            () ->
                new Harness(
                    io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                        bound(
                            """
                plowshare:
                  harness:
                    profiles:
                      guided:
                        - hook: harness:nope
                """),
                        Map.of(),
                        known)));
    assertTrue(unknownHook.getMessage().contains("harness:nope"), unknownHook.getMessage());
    assertTrue(unknownHook.getMessage().contains("harness:counting"), unknownHook.getMessage());

    IllegalStateException unknownParameter =
        assertThrows(
            IllegalStateException.class,
            () ->
                new Harness(
                    io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                        bound(
                            """
                plowshare:
                  harness:
                    profiles:
                      guided:
                        - hook: harness:counting
                          after-stepz: 4
                """),
                        Map.of(),
                        known)));
    assertTrue(
        unknownParameter.getMessage().contains("after-stepz"), unknownParameter.getMessage());

    IllegalStateException wrongType =
        assertThrows(
            IllegalStateException.class,
            () ->
                new Harness(
                    io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                        bound(
                            """
                plowshare:
                  harness:
                    profiles:
                      guided:
                        - hook: harness:counting
                          after-steps: soon
                """),
                        Map.of(),
                        known)));
    assertTrue(wrongType.getMessage().contains("after-steps"), wrongType.getMessage());

    IllegalStateException noDefault =
        assertThrows(
            IllegalStateException.class,
            () ->
                new Harness(
                    io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                        bound(
                            """
                        plowshare:
                          harness:
                            default-profile: missing
                        """),
                        Map.of(),
                        known)));
    assertTrue(noDefault.getMessage().contains("default-profile"), noDefault.getMessage());

    IllegalStateException noAssigned =
        assertThrows(
            IllegalStateException.class,
            () ->
                new Harness(
                    io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                        bound(PROFILES), Map.of("m", "missing"), known)));
    assertTrue(noAssigned.getMessage().contains("'m'"), noAssigned.getMessage());
  }

  @Test
  void two_pools_assigning_one_model_different_profiles_refuses_to_boot() {
    io.aeyer.plowshare.server.llm.PoolProperties a =
        new io.aeyer.plowshare.server.llm.PoolProperties();
    a.setName("spark");
    a.setHarnessProfiles(Map.of("m", "guided"));
    io.aeyer.plowshare.server.llm.PoolProperties b =
        new io.aeyer.plowshare.server.llm.PoolProperties();
    b.setName("studio");
    b.setHarnessProfiles(Map.of("m", "standard"));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> Harness.assignments(List.of(a, b)));

    assertTrue(refused.getMessage().contains("'m'"), refused.getMessage());
    assertEquals(Map.of("m", "guided"), Harness.assignments(List.of(a)));
  }

  // --- a hook that cannot be built or cannot finish is not the run's problem --------------

  @Test
  void a_hook_that_fails_to_build_is_left_out_of_the_chain_and_never_retried() throws Exception {
    Counting counting = new Counting();
    Failing failing = new Failing();
    HarnessProperties properties =
        bound(
            """
                plowshare:
                  harness:
                    profiles:
                      guided:
                        - hook: harness:counting
                        - hook: harness:failing
                """);
    Harness harness =
        new Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                properties, Map.of("m", "guided"), List.of(counting, failing)));
    HarnessRun run = harness.begin();

    Hooks first = run.forModel("m");
    Hooks again = run.forModel("m");

    assertSame(first, again, "still memoized per profile per run, failure or not");
    assertEquals(1, counting.built.size(), "the hook beside it built normally");
    assertEquals(
        1,
        failing.attempts,
        "asked once; a second forModel call for the same profile must not retry it");
    List<HookRecord> records = run.finish();
    assertEquals(2, records.size(), "the counting hook's own record, and the failing one's");
    assertTrue(
        records.stream()
            .anyMatch(
                r ->
                    r.hook().equals("harness:failing")
                        && r.tier() == Tier.HARNESS
                        && r.decision().equals(HookRecord.FAILED)
                        && r.reason() != null
                        && r.reason().contains("no thanks")),
        "names the hook that failed to build, and why");
    assertTrue(
        records.stream()
            .anyMatch(
                r -> r.hook().equals("harness:counting") && r.decision().equals(HookRecord.UNUSED)),
        "the other hook still reports");
  }

  @Test
  void a_hook_whose_finish_throws_does_not_cost_the_other_hooks_their_records() throws Exception {
    Counting counting = new Counting();
    FinishFails finishFails = new FinishFails();
    HarnessProperties properties =
        bound(
            """
                plowshare:
                  harness:
                    profiles:
                      guided:
                        - hook: harness:counting
                        - hook: harness:finish-fails
                """);
    Harness harness =
        new Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                properties, Map.of("m", "guided"), List.of(counting, finishFails)));
    HarnessRun run = harness.begin();
    run.forModel("m");

    List<HookRecord> records = run.finish();

    assertEquals(2, records.size(), "both hooks built, so both owe a record");
    assertTrue(
        records.stream()
            .anyMatch(
                r -> r.hook().equals("harness:counting") && r.decision().equals(HookRecord.UNUSED)),
        "the hook that finished cleanly");
    assertTrue(
        records.stream()
            .anyMatch(
                r ->
                    r.hook().equals("harness:finish-fails")
                        && r.tier() == Tier.HARNESS
                        && r.decision().equals(HookRecord.FAILED)
                        && r.reason() != null
                        && r.reason().contains("finish broke")),
        "the one whose finish() threw, turned into a failed record instead of lost");
  }
}
