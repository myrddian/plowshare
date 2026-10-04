package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.api.WriteMemoryRequest;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.Validation;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedProposal;
import java.util.Map;
import java.util.Objects;

/**
 * {@code memory.write} — judge a proposal's shape, then file it. The frame equivalent of {@code
 * POST /v1/memories}.
 *
 * <h2>Validated before it is judged, and that order is the endpoint's</h2>
 *
 * <p>{@code Validation.check} runs before {@code Scribe.judge} here for the reason the endpoint
 * gives: judging first meant a malformed proposal — of which an oversized body is the most
 * expensive prompt of the lot — cost an embedding call and a model call before coming back 422. A
 * handler that called the same two methods in the other order would agree with its endpoint about
 * every answer and disagree with it about every bill.
 *
 * <h2>The tier comes from the payload, which is the only place it can</h2>
 *
 * <p>{@link Asking} carries a session and never a project, so {@code project} is read off this
 * frame's own payload exactly as the endpoint reads it off its body — through {@link
 * RequestedHome}, which is where "null means global, and blank is refused rather than folded into
 * it" lives for both surfaces. A write judged against the wrong tier would be judged against the
 * wrong candidates, and would answer perfectly well-formed JSON while doing it.
 */
public final class MemoryWriteHandler implements FrameHandler {

  private final Archive archive;
  private final Scribe scribe;

  /**
   * @param archive the one archive both surfaces file a proposal into
   * @param scribe the one judge both surfaces ask what shape a proposal is
   */
  public MemoryWriteHandler(Archive archive, Scribe scribe) {
    this.archive = Objects.requireNonNull(archive, "archive");
    this.scribe = Objects.requireNonNull(scribe, "scribe");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    WriteMemoryRequest request =
        Payloads.as(payload, WriteMemoryRequest.class, FrameTypes.MEMORY_WRITE);
    MemoryProposal proposal = RequestedProposal.toFile(request.proposal(), request.verdict());
    Home home = RequestedHome.in(request.project());
    Validation.check(proposal, archive.maxBodyChars());

    var owner = archive.usage(home, asking.handle(), UsageAttribution.Operation.REVIEW);
    Scribe.Judgement judged =
        archive.accountingEnabled()
            ? scribe.judge(proposal, home, owner)
            : scribe.judge(proposal, home);
    return Outcome.ok(
        archive.accountingEnabled()
            ? archive.applyVerdict(
                proposal, judged.verdict(), home, judged.embedding(), id -> {}, owner)
            : archive.applyVerdict(proposal, judged.verdict(), home, judged.embedding()));
  }
}
