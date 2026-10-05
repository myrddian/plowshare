package io.aeyer.plowshare.server.harness;

import java.util.*;

/** Validated, immutable profiles supplied to the harness after configuration binding. */
public record HarnessConfiguration(
    Map<String, List<Harness.Configured>> profiles,
    Map<String, String> assignments,
    String defaultProfile) {
  public HarnessConfiguration {
    Objects.requireNonNull(profiles, "profiles");
    Objects.requireNonNull(assignments, "assignments");
    if (profiles.size() > 256 || assignments.size() > 10000)
      throw new IllegalArgumentException("Harness configuration exceeds its bounds");
    profiles.forEach(
        (name, hooks) -> {
          identity(name, "profile");
          if (hooks == null || hooks.size() > 256)
            throw new IllegalArgumentException("Profile requires at most 256 hooks");
        });
    assignments.forEach(
        (model, profile) -> {
          identity(model, "model");
          identity(profile, "assigned profile");
        });
    if (defaultProfile != null) identity(defaultProfile, "default profile");
    Map<String, List<Harness.Configured>> immutable = new LinkedHashMap<>();
    profiles.forEach((name, hooks) -> immutable.put(name, List.copyOf(hooks)));
    profiles = Map.copyOf(immutable);
    assignments = Map.copyOf(assignments);
    if (defaultProfile != null && !profiles.containsKey(defaultProfile))
      throw new IllegalArgumentException("unknown default harness profile");
    for (String profile : assignments.values())
      if (!profiles.containsKey(profile))
        throw new IllegalArgumentException("unknown assigned harness profile");
  }

  /** Binds profile entries against the hook registry before any harness run can begin. */
  public static HarnessConfiguration decode(
      HarnessProperties properties,
      Map<String, String> assignments,
      List<HarnessHookFactory> factories) {
    Map<String, HarnessHookFactory> known = new LinkedHashMap<>();
    for (HarnessHookFactory factory : factories) {
      identity(factory.name(), "hook");
      if (known.putIfAbsent(factory.name(), factory) != null)
        throw new IllegalStateException("Duplicate harness hook factory: " + factory.name());
    }
    Map<String, List<Harness.Configured>> built = new LinkedHashMap<>();
    properties
        .getProfiles()
        .forEach(
            (name, hooks) -> {
              List<Harness.Configured> configured = new ArrayList<>();
              for (Map<String, Object> entry :
                  hooks == null ? List.<Map<String, Object>>of() : hooks) {
                Object named = entry.get("hook");
                if (!(named instanceof String hook))
                  throw new IllegalStateException("Harness hook must be a nonblank string");
                identity(hook, "hook");
                HarnessHookFactory factory = known.get(hook);
                if (factory == null) {
                  throw new IllegalStateException(
                      "plowshare.harness.profiles."
                          + name
                          + " names the hook '"
                          + named
                          + "', which this server does not have;"
                          + " it has "
                          + known.keySet());
                }
                Map<String, Object> given = new LinkedHashMap<>(entry);
                given.remove("hook");
                configured.add(
                    new Harness.Configured(
                        factory,
                        ParameterValues.decode(factory.name(), factory.parameters(), given)));
              }
              built.put(name, List.copyOf(configured));
            });
    String fallback = properties.getDefaultProfile();
    if (!fallback.isEmpty() && !built.containsKey(fallback)) {
      throw new IllegalStateException(
          "plowshare.harness.default-profile is '"
              + fallback
              + "', and no profile is called that; there are "
              + built.keySet());
    }
    assignments.forEach(
        (model, profile) -> {
          if (!built.containsKey(profile)) {
            throw new IllegalStateException(
                "the model '"
                    + model
                    + "' is assigned the harness"
                    + " profile '"
                    + profile
                    + "', and no profile is called that; there are "
                    + built.keySet());
          }
        });
    return new HarnessConfiguration(built, assignments, fallback.isEmpty() ? null : fallback);
  }

  private static void identity(String value, String field) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException("Invalid harness " + field);
  }
}
