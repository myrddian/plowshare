# Relay-backed external tools

Status: accepted for implementation, 2026-10-08. The user explicitly authorized
this general core extension after reviewing the external Python integration.

An external SDK consumer should expose a named capability to an agent without
asking the model to construct a Relay envelope or an outgoing A2A message. This
applies to scanners, home automation, business services and future MCP adapters.
The capability is a platform tool façade; the external program keeps its own
deployment and execution lifecycle.

Existing SDK-only alternatives are Relay ingress/egress and the outgoing work
API. Both remain supported. They can transport an integration operation but do
not offer its individual name, description and input schema as a model tool. The
new façade uses Relay ingress/egress, with no additional socket transport.

The first version uses operator-installed `plowshare.relay.tools.bindings`.
All six SDK façades export this configuration; declaring a handler never grants
permission or changes a running server. Each binding fixes project, provider,
provider account, tool schema and deadline. Java, TypeScript/Node, Python, Go and
.NET share declarations, typed handlers, durable receipts and read-only reconciliation. Agent definitions must explicitly
grant the tool name. Server membership and personal-space ownership are checked
again at execution. Provider port authority is limited to that binding's request
egress and result ingress. Topics include provider and tool inside the project's
broker scope; invocation identities live in envelopes.

Input schemas deliberately support a closed object of string, number, integer
and boolean parameters. Unknown fields, coercion, nulls and nested values fail
before publication. A later recursive schema vocabulary or dynamic registration
requires its own compatible contract. Results are bounded text, including JSON
text when the integration has a richer result. Remote text is untrusted tool
data. MCP consumption can translate into this same path later; it is not part of
this change.

A new specialist invocation repository retains owning account, project, run,
step, model call ID, schema/binding fingerprint, request and result. Submission
and Relay publication commit in one database transaction. That record outlives
broker retention: finding no retained event never permits another execution.
The request UUID is derived from the authenticated execution and model call;
reusing it with changed work fails. Provider results must use the deterministic
result UUID, authenticated provider publisher, correlation and parent request.
The request inherits conversation ancestry after matching retained accounting
conversation/turn/run ownership; accounting run IDs are independent of job IDs.
Unknown or exhausted ancestry refuses publication. The result consumes the next
hop rather than resetting the effect budget. The repository records the first valid result. Waiting holds no transaction or
database connection. Model accounting and tool hooks remain in the existing job
runtime.

Cancellation stops waiting and produces UNKNOWN after submission; it does not
claim to undo external effects. Requests carry a deadline, which providers check
before execution and enforce while waiting for an asynchronous handler. Provider
intake must be durably recorded before acknowledgement and execution. Restarted
execution intent becomes UNKNOWN and is never rerun. An uncertain result publish
is reconciled through read-only Relay inspection, never automatically replayed.
Timeout, cancellation, invalid result and expired evidence are not proof of
failure. A subsequent read of the same invocation may retain a late valid result.

Migration V132 adds only the invocation ledger. Disabling all tool bindings
removes the façade while leaving its records and existing Relay APIs intact.
Rolling back application code leaves the additive table unused; do not delete
the ledger to recover a tool. Tests cover input refusal, ownership and provider
fencing, repeat identity, cancellation/timeout and uncertain result handling.
Focused PostgreSQL coverage verifies atomic publication, conflicts and row
mapping; all other behavior uses mocked interfaces. SDK and application examples
exercise the unchanged public Relay operation contracts.
