# MCP acceptance evidence — 17 October 2026

This record distinguishes independent fixtures from a configured authenticated
server. It contains no deployment endpoint, credential, session token or private
request/result payload.

| Evidence | Observed result |
| --- | --- |
| Independent HTTP/STDIO/adapter/recovery fixtures | 100 passed across the two pinned protocol profiles; 28 new HTTP interoperability assertions. |
| Packed public SDK consumer | Same fixture suite compiled and passed against installed public SDK artifacts. |
| Repository gate | `formatCheck check` passed in default non-database mode. |
| Configured instance | Current built server, a new dedicated PostgreSQL database, authentication enabled, loopback synthetic model and independent TLS MCP endpoint. |
| Users and clients | Two distinct human accounts; each sees its own binding and READY grant through two independent public SDK connections. |
| Application | Deployed Application-owned service token retains its own binding/credential subject, separate from the configured executor. Its job completes with one service-subject MCP effect. |
| Effects | Exactly one MCP effect for each of the two humans and service identity, three total. Other subjects' counters do not advance during each call. |
| Original-job inspection | Three retained completed jobs reread without starting a provider/MCP session or another agent job; effect counters unchanged. |
| Cross-user boundaries | Server refuses another owner's binding; public SDK refuses another human's credential selector before sending it. Metadata replies retain the authenticated subject. |
| Human accounting | **BLOCKED:** both original `usage.calls` replies fail public SDK validation. Omitted nullable fields include model family, price version, optional ancestry, preflight observation and cursor. Conversation accounting also fails SDK validation. |
| Service accounting | **BLOCKED:** current service-token operation policy refuses the accounting read, including an explicit project filter. No internal read substitutes for it. |
| Default server startup | **BLOCKED:** final `JdbcApplicationRegistrations` cannot be class-proxied. The isolated instance starts with explicit Spring interface proxies. |
| Organization OAuth / third-party MCP | **NOT RUN:** no configured organization test endpoint/accounts supplied. |
| Browser/native distributions and separate hosts | **NOT RUN:** controlled SDK acceptance is not UI or deployment acceptance. |

The overall configured acceptance result is **blocked-accounting**, with exit code
2. Execution and identity checks pass independently. This does not complete all
server interoperability acceptance criteria, prove general monetary spending
pools or implement external-tool human approval.

The PostgreSQL container is an explicitly requested local runtime instance, not
a `full-db`/Testcontainers or `dockerSmoke` test run. No database test suite ran.
Known cancellation, schema-change, revocation, disabled-account, deadline and
uncertain-result behavior is covered by the independent fixtures and existing
mocked server suites described in the runbook; those assertions are not presented
as organization-endpoint fault-injection evidence.

Use the private instance's `acceptance-report.json` for its redacted result and
`acceptance-private.json` plus SDK journals for original job/invocation coordinates.
Keep the latter private. Failed provision/exercise attempts retain their intents;
none were automatically replayed. A fresh disposable run is new test work, not
recovery of an uncertain effect.
