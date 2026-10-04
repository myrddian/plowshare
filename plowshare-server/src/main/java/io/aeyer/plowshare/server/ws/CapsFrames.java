package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.ProjectCaps;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.CapsSource;
import io.aeyer.plowshare.server.orchestrations.Orchestrations;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code orchestration.caps} (spec 2026-09-29 §2): a project's caps as the server reads them now —
 * each value and where it came from — applied to this account's live runs in it. The TUI sends it
 * after {@code /cap} writes the project's file, and for {@code /cap} alone.
 *
 * <p><b>Reading and applying are one frame</b> because {@code /cap} writes the file on the person's
 * machine and the server has no watch on it: the frame is the moment the server learns the file
 * changed, and a read that did not also apply would leave live runs on the old numbers until their
 * next rebuilt turn. Applying the same caps twice changes nothing, so {@code /cap} alone applying
 * them too costs nothing.
 *
 * <p><b>Both collaborators arrive through an {@link ObjectProvider}</b>, on {@link
 * OrchestrationFrames}' reason: a deployment without the engine still answers what its files say,
 * applied to no run.
 */
@Component
public class CapsFrames implements FrameArea {

  private final ObjectProvider<CapsSource> caps;
  private final ObjectProvider<Orchestrations> orchestrations;

  /**
   * @param caps where a project's caps are read; absent reads none
   * @param orchestrations the engine the caps are applied through; absent applies them to nothing
   */
  public CapsFrames(
      ObjectProvider<CapsSource> caps, ObjectProvider<Orchestrations> orchestrations) {
    this.caps = Objects.requireNonNull(caps, "caps");
    this.orchestrations = Objects.requireNonNull(orchestrations, "orchestrations");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.ORCHESTRATION_CAPS, this::caps);
  }

  record CapsBody(String project) {}

  /**
   * One cap: its value, absent when unset, and where it came from.
   *
   * @param value the number, or null when no file sets it
   * @param source {@code .plowshare/environment.yml}, {@code the server's environment.yml}, {@code
   *     definition} or {@code default}
   */
  public record SettingView(Integer value, String source) {}

  /** An opt-in setting and the file that supplied it; null means unset. */
  public record BooleanSettingView(Boolean value, String source) {}

  /**
   * The caps, how many live runs they were applied to, and why a file was not read.
   *
   * @param project the project asked about
   * @param steps steps per turn
   * @param budget model calls per run
   * @param autoContinue caps a run passes without asking — never a failed-checks question's
   * @param time minutes a run goes before it asks; no value is no time cap
   * @param failedChecks check failures a run takes before it asks; {@code default} 5 when no file
   *     sets it
   * @param autoIncrease authorization to renew step and model-call allowances automatically
   * @param applied how many of this account's live runs in the project they reached
   * @param said why a file was not read, or null
   */
  public record CapsView(
      String project,
      SettingView steps,
      SettingView budget,
      SettingView autoContinue,
      SettingView time,
      SettingView failedChecks,
      BooleanSettingView autoIncrease,
      int applied,
      String said) {}

  Outcome caps(Map<String, Object> payload, Asking asking) {
    CapsBody body = Payloads.as(payload, CapsBody.class, FrameTypes.ORCHESTRATION_CAPS);
    String handle = asking.requireHandle(FrameTypes.ORCHESTRATION_CAPS);
    if (body.project() == null || body.project().isBlank()) {
      throw new CallerFault(
          "orchestration.caps needs the project whose caps to read;" + " nothing was read.");
    }
    ProjectCaps found = caps.getIfAvailable(() -> CapsSource.NONE).capsFor(body.project());
    Orchestrations engine = orchestrations.getIfAvailable();
    int applied = engine == null ? 0 : engine.applyCaps(body.project(), handle, found);
    return Outcome.ok(
        new CapsView(
            body.project(),
            view(found.steps()),
            view(found.budget()),
            view(found.autoContinue()),
            view(found.time()),
            view(found.failedChecks()),
            new BooleanSettingView(found.autoIncrease().value(), found.autoIncrease().source()),
            applied,
            found.unreadable()));
  }

  private static SettingView view(ProjectCaps.Setting setting) {
    return new SettingView(setting.value(), setting.source());
  }
}
