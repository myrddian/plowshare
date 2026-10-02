package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.server.hooks.Hooks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every profile, validated, and which profile each model runs.
 *
 * <p><b>Guidance is tuned to the model.</b> A small model needs earlier traps and a
 * second opinion; a large one does not, and tokens spent guiding it are waste.
 */
public final class Harness {

    /** No profiles and no assignments: every run gets no harness hooks. */
    public static final Harness NONE = new Harness(new HarnessProperties(), Map.of(), List.of());

    /** One configured hook of a profile. */
    record Configured(HarnessHookFactory factory, Parameters parameters) {}

    private final Map<String, List<Configured>> profiles;
    private final Map<String, String> assignments;
    private final String defaultProfile;

    /**
     * @param assignments wire model → profile name, from every pool's
     *     {@code harness-profiles}
     * @throws IllegalStateException for any profile mistake; see {@code HarnessTest}
     */
    public Harness(HarnessProperties properties, Map<String, String> assignments,
            List<HarnessHookFactory> factories) {
        Map<String, HarnessHookFactory> known = new LinkedHashMap<>();
        for (HarnessHookFactory factory : factories) {
            known.put(factory.name(), factory);
        }
        Map<String, List<Configured>> built = new LinkedHashMap<>();
        properties.getProfiles().forEach((name, hooks) -> {
            List<Configured> configured = new ArrayList<>();
            for (Map<String, Object> entry : hooks == null ? List.<Map<String, Object>>of() : hooks) {
                Object named = entry.get("hook");
                HarnessHookFactory factory = named == null ? null : known.get(String.valueOf(named));
                if (factory == null) {
                    throw new IllegalStateException("plowshare.harness.profiles." + name
                            + " names the hook '" + named + "', which this server does not have;"
                            + " it has " + known.keySet());
                }
                Map<String, Object> given = new LinkedHashMap<>(entry);
                given.remove("hook");
                configured.add(new Configured(factory,
                        Parameters.read(factory.name(), factory.parameters(), given)));
            }
            built.put(name, List.copyOf(configured));
        });
        String fallback = properties.getDefaultProfile();
        if (!fallback.isEmpty() && !built.containsKey(fallback)) {
            throw new IllegalStateException("plowshare.harness.default-profile is '" + fallback
                    + "', and no profile is called that; there are " + built.keySet());
        }
        assignments.forEach((model, profile) -> {
            if (!built.containsKey(profile)) {
                throw new IllegalStateException("the model '" + model + "' is assigned the harness"
                        + " profile '" + profile + "', and no profile is called that; there are "
                        + built.keySet());
            }
        });
        this.profiles = Map.copyOf(built);
        this.assignments = Map.copyOf(assignments);
        this.defaultProfile = fallback.isEmpty() ? null : fallback;
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
     * @throws IllegalStateException when two pools give one model different profiles:
     *     which the model ran would depend on which pool a call was routed to
     */
    public static Map<String, String> assignments(List<io.aeyer.plowshare.server.llm.PoolProperties> pools) {
        Map<String, String> all = new LinkedHashMap<>();
        for (io.aeyer.plowshare.server.llm.PoolProperties pool : pools) {
            pool.getHarnessProfiles().forEach((model, profile) -> {
                String earlier = all.putIfAbsent(model, profile);
                if (earlier != null && !earlier.equals(profile)) {
                    throw new IllegalStateException("the model '" + model + "' is given the harness"
                            + " profile '" + earlier + "' by one pool and '" + profile + "' by pool '"
                            + pool.getName() + "'; a model runs one profile wherever it is served");
                }
            });
        }
        return all;
    }
}
