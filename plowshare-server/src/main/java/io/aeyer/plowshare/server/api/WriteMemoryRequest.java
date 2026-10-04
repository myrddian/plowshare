package io.aeyer.plowshare.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.MemoryProposal;
import java.util.Objects;

/**
 * The body of {@code POST /v1/memories}.
 *
 * <p>Nests the domain record {@code Archive.applyVerdict} takes rather than flattening its fields
 * into this one — the wire shape is then exactly {@link MemoryProposal} plus which tier, so there
 * is only one place ("what is a valid proposal") that decides what a caller must send, and it is
 * the same place that already enforces those rules for every other path into the archive. A missing
 * {@code proposal} key fails at Jackson's own binding — it is required here, and {@link
 * MemoryProposal}'s own compact constructor rejects the null fields inside it — which Spring
 * reports as 400 before this class's constructor ever finishes running.
 *
 * <h2>There is no {@code verdict}, and it did not become optional</h2>
 *
 * <p>It used to carry one, and every caller sent {@code NEW}. The judgement about shape — new, a
 * refinement, or a replacement — is now made server-side by {@code Scribe}, against candidates it
 * retrieves itself. The field is gone rather than optional because the reason it could not stay is
 * not about defaults: a caller that names a target to supersede is retiring a memory it never read,
 * and leaving the field accepted-when-present would keep exactly that door open for whatever sends
 * it next.
 *
 * <p><b>Removing it was not enough, and Task 8 recorded why.</b> Jackson's unknown-property
 * tolerance is on by default under Spring Boot, so a stale client sending {@code "verdict":
 * {"kind": "supersedes", ...}} got a 200 and no complaint — and went away believing it had retired
 * a memory that is still active and still answering recalls. The field was never <em>honoured</em>,
 * so the design held; what failed was that nobody was told. The {@link #verdict} component below is
 * what tells them: it is a tripwire and not a field, it is never read for its value, and {@link
 * io.aeyer.plowshare.server.requests.RequestedProposal#toFile} refuses the request outright when
 * anything but null arrives in it.
 *
 * <p><b>That refusal used to be reachable through a {@code namesAVerdict()} method here, and is not
 * any more.</b> Asking the question on this record and answering it in the controller put the two
 * halves of one rule in two places, which is exactly how a second surface comes to refuse the same
 * body in different words — or not at all. Both halves now live in {@code RequestedProposal}, which
 * both surfaces reach through.
 *
 * <p>Only this one key is refused. An unknown key a <em>newer</em> client sends is still tolerated,
 * because forward compatibility is the thing that tolerance is for and because no other key has
 * ever had a meaning this server acted on. The rule is "a key that used to change what happened
 * must not be ignorable", not "the body is closed".
 *
 * @param project the tier to write into: {@code null} (omitted or explicit) means global, matching
 *     {@link io.aeyer.plowshare.protocol.Home}'s own null-means-global rule. A blank string is
 *     refused rather than folded into global, for the same reason {@code Home.of} refuses it: an
 *     empty string is what an unset form field or a stray default sends, and treating it as
 *     "global" would let that silently promote a project memory into the tier every project reads.
 * @param proposal the claim being proposed
 * @param verdict <b>a tripwire, not a field.</b> Present only so that a stale client's {@code
 *     verdict} key is a refusal instead of a silent drop. Nothing reads its contents; {@code
 *     RequestedProposal.toFile} refuses any value but null. Typed as a {@link JsonNode} so that
 *     whatever shape an old client sends binds rather than failing at Jackson with a message about
 *     enum constants, which would refuse the request for the wrong reason and tell the caller
 *     nothing about why.
 */
public record WriteMemoryRequest(String project, MemoryProposal proposal, JsonNode verdict) {

  public WriteMemoryRequest {
    Objects.requireNonNull(proposal, "proposal");
  }
}
