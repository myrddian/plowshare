package io.aeyer.plowshare.server.agents;

import java.util.List;
import java.util.Objects;

/**
 * One loaded orchestration: the agent that conducts it, the stages the harness will hold it to, and
 * what identifies this exact file. Spec §4.1. Nothing here runs; slice 3 does.
 *
 * @param conductor the file read as an agent definition: its prompt is the body
 * @param stages in order, never empty
 * @param maxReturns how many times a later stage may send the conductor back, from 1
 * @param artifacts a workspace directory template, or {@code null}
 * @param triggers phrases and commands the harness notices in an utterance (slice 5)
 * @param hash {@code sha256:<hex>} of the file's text, so a run's record names the exact file
 * @param source the file's text, whole, so an engine can re-parse it after a restart without
 *     re-resolving a tier that may have changed or, for a laptop session, gone
 * @param origin where the file was read from, as {@link DefinitionSource.Definition#origin()}
 * @param tier the layer this file was read from
 * @param checker the agent its {@code checker:} names — the acceptance checker the harness runs
 *     against this orchestration's acceptance (spec 2026-10-01) — or {@code null} for none
 */
public record OrchestrationDefinition(
    AgentDefinition conductor,
    List<Stage> stages,
    int maxReturns,
    String artifacts,
    List<Trigger> triggers,
    String hash,
    String source,
    String origin,
    Tier tier,
    String checker) {

  /** A stage whose done move reads spec.md's {@code ## Acceptance} (spec 2026-09-29 §1b). */
  public static final String ACCEPTANCE_WRITTEN = "written";

  /** A stage whose done move runs the acceptance commands (spec 2026-09-29 §1b). */
  public static final String ACCEPTANCE_REQUIRED = "required";

  public OrchestrationDefinition {
    Objects.requireNonNull(conductor, "conductor");
    stages = List.copyOf(stages);
    triggers = List.copyOf(triggers);
    Objects.requireNonNull(hash, "hash");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(tier, "tier");
  }

  /** An orchestration with no acceptance checker. */
  public OrchestrationDefinition(
      AgentDefinition conductor,
      List<Stage> stages,
      int maxReturns,
      String artifacts,
      List<Trigger> triggers,
      String hash,
      String source,
      String origin,
      Tier tier) {
    this(conductor, stages, maxReturns, artifacts, triggers, hash, source, origin, tier, null);
  }

  /**
   * The same orchestration with its conductor replaced, as {@link DefinitionChecks} hands one back
   * with its sampling resolved. The hash still names the file, which did not change.
   */
  public OrchestrationDefinition withConductor(AgentDefinition replaced) {
    return new OrchestrationDefinition(
        replaced, stages, maxReturns, artifacts, triggers, hash, source, origin, tier, checker);
  }

  public String name() {
    return conductor.name();
  }

  public String description() {
    return conductor.description();
  }

  /**
   * @param doneWhen guidance shown to the conductor, or {@code null}; never evaluated
   * @param checked the harness runs the run's check whenever this stage is marked done — spec
   *     2026-09-26
   * @param acceptance {@link #ACCEPTANCE_WRITTEN} on the stage whose done move reads spec.md's
   *     {@code ## Acceptance} section, {@link #ACCEPTANCE_REQUIRED} on the stage whose done move
   *     runs it, or {@code null} for a stage that is neither — spec 2026-09-29 §1b
   * @param holdsPhases {@code children: phases}: this stage's child todos are the phases the
   *     conductor starts runs under, so a child todo left under any other stage can be pointed
   *     here. At most one stage, and only in a definition with an {@code orchestrations:} grant.
   */
  public record Stage(
      String id,
      String doneWhen,
      List<String> mayReturnTo,
      boolean checked,
      String acceptance,
      boolean holdsPhases) {
    public Stage {
      mayReturnTo = List.copyOf(mayReturnTo);
    }

    /** A stage that does not hold phases. */
    public Stage(
        String id, String doneWhen, List<String> mayReturnTo, boolean checked, String acceptance) {
      this(id, doneWhen, mayReturnTo, checked, acceptance, false);
    }

    /** A stage with no acceptance role. */
    public Stage(String id, String doneWhen, List<String> mayReturnTo, boolean checked) {
      this(id, doneWhen, mayReturnTo, checked, null);
    }

    /** A stage no command decides — every stage before `check:` existed. */
    public Stage(String id, String doneWhen, List<String> mayReturnTo) {
      this(id, doneWhen, mayReturnTo, false);
    }
  }

  /**
   * @param command {@code true} for a {@code /command}, matched only at an utterance's start
   */
  public record Trigger(String text, boolean command) {}

  /** Where a definition was read from, most specific first. Spec §5.1 records it on the run. */
  public enum Tier {
    PROJECT,
    SESSION,
    PERSONAL,
    GLOBAL,
    SHIPPED
  }
}
