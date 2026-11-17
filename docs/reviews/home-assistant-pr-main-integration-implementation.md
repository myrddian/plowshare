# Main integration: owned read deadlines

Implementer: `/root/ha_pr_integration_implementer`. Independent reviewer:
`/root/ha_pr_integration_reviewer` (approval is recorded separately).

The publication branch incorporates main through `abef4b11`. Main introduced
millisecond PostgreSQL read cancellation through `JdbcReadDeadline.apply` and
typed `ArchiveQueryCancelledException`. The scoped Ex SQL infrastructure refuses
native JDBC unwrap to preserve connection, statement and materialization
ownership. A search composed within that scope would otherwise fail when the
new helper attempted to unwrap `PgStatement`.

The helper now recognizes a narrow statement deadline capability. Its only
operation tightens an absolute read deadline; it returns no JDBC handle. Scoped
statement proxies implement it using an application class loader and retain
their refusal to unwrap native driver interfaces. Each subsequent statement
operation and execution inherits the earlier applied ceiling, enclosing scope,
parent query and configured query limit. Statement reuse cannot renew that
ceiling. The resulting owned query retains its watchdog, server limit, network
deadline and result materialization fence. Applying the capability during an
active result is refused.

At execution, the infrastructure applies the driver helper to its private,
still-owned native statement when that statement advertises `PgStatement`.
This preserves an already shorter millisecond driver timeout. Other statements
retain the existing whole-second fallback together with their earlier owned
cancellation fence. Unscoped helper behavior is unchanged. Neither the capability
nor the merge adds mutation retry or releases physical capacity early.

The new cancellation subtype still passes through the Ex uncertainty mapper.
Both open and run retain the possibility of earlier commit/admission, hide raw
dependency details and forbid automatic replay. Only main's owning search path
may classify its completed read cancellation as recoverable read unavailability.

## Validation

`format`, `formatCheck` and twelve selected non-database server test classes
passed in the final focused Gradle invocation. The selected tests cover the
driver helper, scoped SQL composition and cleanup, archive classification,
conversation search deadlines and ranking, Ex service uncertainty, project
admission, routing budget, completion channel and target disclosure. New cases
exercise native unwrap refusal, a shorter 125 ms driver timeout, the nonvendor
fallback, repeated statement use without deadline extension and cancellation
during result materialization with retained physical ownership.

The initial invocation stopped at test compilation because warnings are errors:
two new try-with-resources variables were unused. Those tests now assert the
retained statement identity; the final invocation passed. Local logs are not
published because they contain machine-specific diagnostics. Their digest and
the exact selected commands/results are bound by the candidate manifest.

No PostgreSQL test was required for this change: its assertions concern proxy
dispatch, deadline ownership and exception translation through mocked JDBC
interfaces. This does not replace earlier selected PostgreSQL evidence for the
alias migration or scoped backend cancellation. Full repository and SDK checks
remain the coordinator's publication checks against the frozen source.

The Home Assistant prompt-fidelity, Conversation, AI Task, packaging and live
acceptance gates remain in force. No new prompt API, prompt rewriting, live
Home Assistant operation, deployment or merge authorization is introduced.
