# Scripted report save recovery

The user authorized platform fixes and save-only recovery for a retained
reviewed draft.

A scripted report can finish evidence gathering and review before Information
admission refuses its write. Its read selection may include shared sources, while
a service credential can write only to an authorized project destination.
Using one selection for both decisions rejects valid report writes. Allowing
a wider destination would grant authority the report does not need.

Check a report's project destination with the shared-read flag off. Keep the
original read selection and boundary for the dependency checks, and retain
every input on the report so later readers remain subject to its transitive
source policy. The destination role and current source access still apply.

The platform owns the reviewed command, the write gate, and the run's recovery
transaction. An SDK adapter can submit a new report, but cannot obtain the exact
review and inherited dependency snapshot or safely decide whether to repeat a
started command. Reuse the existing orchestration Resume operation and its
caller, project, and request identity fences. A report refusal with a matching
blocked gate and no admitted revision yields a structured command diagnostic and
an actionable run failure.

When the owner explicitly resumes, atomically append a new report command from
the retained step and mark the original request as its retry provenance. Keep
the failed command and its receipt intact. The appended command uses a fresh
request ID, the same reviewed bytes, and the same dependency set. Run the normal
hooks and authorization again. A hook that changes the retained request or a
changed dependency set prevents the save. The retry consumes no analyst or
reviewer calls. The pinned script receives the successful save result and
continues through its remaining stage gates.

There is no new operation or schema migration. Older completed refusals qualify
through their original gate and command receipts. An older server can run
untouched historical commands. It cannot drive an appended report retry and
fails closed on its new journal metadata. A started command without a durable
execution receipt stays fenced pending reconciliation. Verify journal append,
write-gate proof and source/destination checks on PostgreSQL, and exercise the
pinned script with counted scan, analyst and reviewer effects.
