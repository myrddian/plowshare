package io.aeyer.plowshare.server.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every profile, validated, and which profile each model runs.
 *
 * <p><b>Guidance is tuned to the model.</b> A small model needs earlier traps and a second opinion;
 * a large one does not, and tokens spent guiding it are waste.
 */
public final class Harness {

  /** No profiles and no assignments: every run gets no harness hooks. */
  public static final Harness NONE =
      new Harness(new HarnessConfiguration(Map.of(), Map.of(), null));

  /** One configured hook of a profile. */
  public record Configured(HarnessHookFactory factory, Parameters parameters) {
    public Configured {
      Objects.requireNonNull(factory);
      Objects.requireNonNull(parameters);
    }
  }

  private final Map<String, List<Configured>> profiles;
  private final Map<String, String> assignments;
  private final String defaultProfile;

  /**
   * @param configuration validated profiles and model assignments supplied by configuration
   * @throws IllegalStateException for any profile mistake; see {@code HarnessTest}
   */
  public Harness(HarnessConfiguration configuration) {
    this.profiles = configuration.profiles();
    this.assignments = configuration.assignments();
    this.defaultProfile = configuration.defaultProfile();
  }

  /** The profile a model runs, or null for no harness hooks. */
  public String profileFor(String wireModel) {
    String assigned = wireModel == null ? null : assignments.get(wireModel);
    return assigned != null ? assigned : defaultProfile;
  }

  /** A run's harness: its hook instances, built as its models are met. */
  public HarnessRun begin() {
    return new HarnessRun(this);
  }

  List<Configured> configured(String profile) {
    return profile == null ? List.of() : Objects.requireNonNull(profiles.get(profile));
  }

  /**
   * Every pool's model assignments as one map.
   *
   * @throws IllegalStateException when two pools give one model different profiles: which the model
   *     ran would depend on which pool a call was routed to
   */
  public static Map<String, String> assignments(
      List<io.aeyer.plowshare.server.llm.PoolProperties> pools) {
    Map<String, String> all = new LinkedHashMap<>();
    for (io.aeyer.plowshare.server.llm.PoolProperties pool : pools) {
      pool.getHarnessProfiles()
          .forEach(
              (model, profile) -> {
                String earlier = all.putIfAbsent(model, profile);
                if (earlier != null && !earlier.equals(profile)) {
                  throw new IllegalStateException(
                      "the model '"
                          + model
                          + "' is given the harness"
                          + " profile '"
                          + earlier
                          + "' by one pool and '"
                          + profile
                          + "' by pool '"
                          + pool.getName()
                          + "'; a model runs one profile wherever it is served");
                }
              });
    }
    return all;
  }
}
