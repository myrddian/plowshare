package io.aeyer.plowshare.server.orchestrations;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * What one row of the orchestration record is — spec 2026-09-28, the orchestration record §2. Every
 * kind but {@link #TOOL_CALL} is a milestone; the wire name is what the column holds, what {@code
 * orchestration.record}'s {@code kinds} names and what a client reads.
 */
public enum RecordKind {
  RUN_STARTED("run_started"),
  RUN_ENDED("run_ended"),
  RUN_RESUMED("run_resumed"),
  STAGE_MOVED("stage_moved"),
  PHASE_STARTED("phase_started"),
  PHASE_ENDED("phase_ended"),
  CHECK_RAN("check_ran"),
  APPROVAL_ASKED("approval_asked"),
  APPROVAL_ANSWERED("approval_answered"),
  QUESTION_ASKED("question_asked"),
  QUESTION_ANSWERED("question_answered"),
  STALLED("stalled"),
  CALL_FAILURE("call_failure"),
  DELEGATED("delegated"),
  DELEGATE_RETURNED("delegate_returned"),
  ACCEPTANCE_RAN("acceptance_ran"),
  CAP_CONTINUED("cap_continued"),
  /**
   * The acceptance checker's work (V77): a concern raised, a WHY put to the conductor and its
   * answer, a verdict, the person's answer.
   */
  CONCERN("concern"),
  TOOL_CALL("tool_call");

  /** Every kind: a reading narrowed by nothing. */
  public static final Set<RecordKind> EVERY =
      Collections.unmodifiableSet(EnumSet.allOf(RecordKind.class));

  /** Every kind but the tool lines. */
  public static final Set<RecordKind> MILESTONES =
      Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(TOOL_CALL)));

  private final String wire;

  RecordKind(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }

  public static Optional<RecordKind> of(String wire) {
    return Arrays.stream(values()).filter(kind -> kind.wire.equals(wire)).findFirst();
  }
}
