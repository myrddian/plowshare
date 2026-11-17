# CORE-04 preparation

Prepared by `/root/ha_sdk_implementer` from the current owning sources. This is a
read-only implementation proposal, not a completed deliverable or approval.
CORE-03 is still implementing; no runtime/source files, generators, packages or
delivery-ledger state were changed by this preparation.

## Existing public surfaces

The canonical TypeScript catalogue already contains the exact
`ConversationOpenRequestEx` and `AgentRunRequestEx` names, both operation mappings,
input validation and ordinary reply projections. TypeScript/Node callers can use
the existing typed `request` method; a second convenience layer is unnecessary.
The package root currently exports `Payloads`, while the named Ex DTOs are
available from the public catalogue subpath. Re-exporting these named types at
the TypeScript and Node roots is a useful, small discoverability improvement.

The native generator already supplies Python's two generated request classes,
Go's `ConversationOpenEx`/`AgentRunEx` methods and .NET's
`ConversationOpenExAsync`/`AgentRunExAsync` extensions. No new native wrappers are
needed. Java has ordinary conversation/run helpers and a package-private typed
exchange owner, but no public typed Ex capability yet.

Current native input schemas already carry the project, source, external-ID,
positive-integer limit, conflicting-cap and empty-image constraints. Their owning
validators count string lengths in UTF-16 units. Source and external IDs are
ASCII-only, so their character and UTF-8 byte bounds coincide. Validation must
refuse other alphabets rather than normalize or encode them implicitly.

## Canonical dependency correction

The existing shared native DTO fixture contains no rows for either Ex operation.
The generated `agent.run.ex` result currently reuses `ObservedStartedJob`, whose
only required field is `id`. The TypeScript `resultOf` path additionally requires
an `ACCEPTED` outcome and a conversation beginning with `cnv_`; standalone
`decodeReply` and native typed clients do not yet establish that same contract.
The generic success handling also accepts a non-`OK` success code for Open-Ex.

The coordinator and CORE-03 implementer have been informed. CORE-03 owns the
canonical correction before its candidate freeze: an Ex-only result with a
required non-null conversation, the corresponding decoder/portable constraints,
Open-Ex status semantics and owning regenerated outputs. Any strengthened native
result root must be private to Ex rather than mutating the shared ordinary-run
definition. CORE-04 consumes that accepted contract; it must not silently change
ordinary run, resume or other operation policies.

## Proposed CORE-04 outputs and ownership

1. Add a public Java `ExternalConversationClient` over an existing authenticated
   `Plowshare` connection, with exact typed `ConversationOpenRequestEx` and
   `AgentRunRequestEx` records, synchronous/asynchronous calls and explicit
   `RequestOptions`. Reuse ordinary `ServerClient.Conversation` and
   `ServerClient.StartedJob` results. Keep raw JSON conversion inside the owning
   SDK boundary. A small package-private typed exchange overload may carry an
   expected success code if needed; no public string-operation escape hatch.
   Proposed files are the three new classes under
   `sdk/java/src/main/java/io/aeyer/plowshare/sdk/`, a narrow `Plowshare.java`
   exchange seam if required, and `ExternalConversationClientTest.java`.
2. Re-export the named locator/Ex DTO types in
   `sdk/typescript/src/operations/sdk.ts` and `sdk/node/src/sdk.ts`. Retain existing
   typed request methods and one-shot command mappings. Add a focused public
   import/type check; no CLI parser redesign or new transport.
3. Add Ex-only expected-success validation in the existing native typed owners:
   Python `Client.request`, Go `requestTyped`, and .NET `RequestTypedAsync`.
   Preserve true refusals, existing unknown-delivery/cancellation semantics and
   ordinary operations. A wrong success code is `INVALID_RESPONSE`, never a
   reason to reconnect, fall back or replay.
4. Extend `scripts/sdk-dto-fixtures.mjs`, then have the coordinator run its owning
   generator for `test-support/contracts/sdk-dto-conformance.json`. Cover the
   accepted Ex requests/results through the already shared Python/Go/.NET
   contract suites. Generated catalogues and schemas remain CORE-03-owned unless
   an approved dependency correction reopens that node.
5. Extend the existing shared WebSocket fixture in
   `scripts/sdk-conformance.mjs` and its Node, Python, Go and .NET drivers. Drive
   the actual public typed Ex calls, not a raw-operation substitute. Keep each
   driver source where the current package check already copies/runs it.
6. Extend `scripts/sdk-package-check.py` with fresh-consumer Ex imports/types and
   a Java binary-consumer compilation. Its existing installed conformance path
   can repeat the new native scenarios without a parallel harness. Update the
   SDK manual's planned-status text only after dependency acceptance and record
   current package-specific usage and limits.

The exact Java names and any internal exchange seam should be confirmed by the
coordinator before implementation. These outputs are disjoint from CORE-03's
canonical contracts, constraints and generator ownership. No server, database,
adapter, prompt-role or prompt-transformation work belongs to CORE-04.

## Required focused evidence

- A valid Open-Ex and Run-Ex each sends one application frame containing the exact
  external selectors, with no lookup RPC. Run-Ex returns the real internal
  conversation/job handle, rather than a model result.
- Invalid/missing/null selectors, owner/internal routing fields, conflicting caps,
  invalid limit types/ranges, source grammar, external-ID length/Unicode/control
  characters and nonempty images fail before transport. Boundary examples also
  preserve case, punctuation and task whitespace without rewriting.
- Omitted options and explicit nullable session stay distinct. An omitted creation
  budget accepts the ordinary configured-zero reply; explicit request zero is
  refused. Reopening semantics remain server evidence, not an SDK simulation.
- Open-Ex requires its accepted ordinary view/status; Run-Ex requires `ACCEPTED`
  and a valid internal conversation. Missing/null/wrong-prefix conversation and
  wrong success codes are invalid responses. Extra future response fields are
  projected away. Ordinary open/run fixtures remain compatible.
- Real socket refusal, malformed result, timeout and disconnect cases verify
  one submission and no mutation replay. Pre-submission cancellation remains
  distinguishable from unknown delivery after transmission. Tests use finite
  fixture budgets and original request correlation; no model/device work.
- Source labels and external IDs appear only in intentional request assertions,
  never credential-bearing diagnostics, metrics labels or shared-runtime policy.

The coordinator owns serialized generation/format/build commands. Required final
evidence is the affected focused suites, generator drift checks, repository
`check`, `sdkCheck` and fresh-consumer `sdkPackageCheck`. No PostgreSQL test is
needed for SDK DTOs or fake-transport conformance. Missing toolchains or failed
checks remain unresolved evidence. An independent reviewer must approve the
frozen CORE-04 source/evidence before the node closes; this proposal does not
claim merge, deployment or live validation.
