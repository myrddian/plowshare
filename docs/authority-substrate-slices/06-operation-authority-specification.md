# Slice 06 operation authority specification

Written before enforcement changes. This slice uses existing conversation/run/job
operations, role catalogue, project/token/Application ceilings and retained acting
checks. It adds no execution endpoint or recoverable closure runtime.

Ordinary authenticated operations resolve a registered Project or the account's
Personal project before admission. Global knowledge is not a public execution
home. Empty conversation creation checks CONVERSATION_CREATE independently of
RUN_MODEL. An ordinary model run requires READ, WRITE and RUN_MODEL; creating its
log also requires CONVERSATION_CREATE. Native task work checks its actual work
permissions without inventing EXECUTE_JOB. Existing lifecycle/owner rules still
apply to controls and disclosure.

Admission retains immutable account/scope UUIDs, the fixed home and required
actions, and the exact supporting grant IDs before queued execution. Live token
and Application ceilings intersect those grants on every check. A replacement or
new grant cannot repair a revoked supporting grant. Retained records are evidence,
not permission or a new resume API. Old work without a supported retained envelope
cannot acquire fresh authority by recovery.

Checks run before dispatch, on queue release, around protected tools and before
result disclosure. A read already in progress may have consumed input, and an
in-flight model may already incur cost; denial prevents further effects and
answer disclosure without discarding accounting. Bounded model answers are delivered
whole after the final check; delta sinks do not perform database IO. Tool/file/provider capability
checks remain independent. Only reviewed server resource selectors are decoded
for retained target checks; opaque provider arguments are never interpreted as
account authorization fields. Information/file tools retain their own bound-home
and capability checks. Acting retains its narrower supported-consumer set,
connection-bound proof and no-fallback rule; ordinary admission cannot replace it.

Child work retains its parent's fixed scope/action bounds. This does not activate
agent role inheritance or independent orchestration recovery (slice 11). Operation
budgets and non-chat side calls are the next slice; trusted SYSTEM service
purposes are classified in slice 08. Unknown authority or targets fail closed.
