package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/proposals/{id}/resolve}.
 *
 * @param accept true to approve — which promotes the memory — false to reject. Required, with no
 *     default: a resolution that guessed would settle a proposal one way over a key somebody forgot
 *     to send, and a settled proposal is never re-opened.
 * @param reason why. Carried onto the row, and it is the whole of what a later reader sees; a
 *     rejection's reason is what stops the curator asking again.
 * @param by who decided. Required — and a <b>deviation</b> from the spec, whose signature is {@code
 *     memory_resolve(id, decision, reason)} with no such field. It is here because {@code
 *     Proposal.resolvedBy} exists and is the only thing that distinguishes a person's decision from
 *     the curator's own when it settles its own question under {@code Curator.BY}: without it,
 *     every row read months later says "curator" or says nothing. Required rather than defaulted,
 *     because a default would put a fiction in the one column that answers "who decided this?".
 */
public record ResolveProposalRequest(Boolean accept, String reason, String by) {}
