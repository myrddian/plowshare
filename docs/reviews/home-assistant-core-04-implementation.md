# CORE-04 implementation evidence

Implementer: `/root/ha_sdk_implementer`.
Native coauthor: `/root/ha_sdk_implementer/native_ex`.
Status: frozen implementation candidate for independent review. Final combined
repository/package checks remain pending. Root owns the delivery ledger and
serialized verification; supplemental results must not rewrite this frozen report.

The implementation consumes the independently accepted CORE-03 Ex operations.
TypeScript and Node use their existing typed request method and now re-export the
exact named locator/Ex request types from package roots. Python, Go and .NET use
their existing generated typed classes/methods; no redundant convenience wrappers
or local mapping cache is added.

Java adds `ExternalConversationClient`, `ConversationOpenRequestEx` and
`AgentRunRequestEx`. Each public operation submits one request over the existing
authenticated connection. The typed exchange seam checks Ex success status and
classifies malformed results as invalid responses with uncertain completion.
Open-Ex returns its exact eight-field `ConversationView`; nullable safe-integer
counters use `Long`, configured zero and explicit null remain valid, and original
id/project/title text is preserved. Run-Ex returns an exact three-field
`StartedJob` projection with required internal conversation and optional agent.
Private SDK boundary decoding accepts omitted agent but rejects explicit null;
future fields such as revision are not exposed. Ordinary Java conversation/job
DTOs and all ordinary operation policies are unchanged.

These result types correct the historical preparation proposal to reuse ordinary
Java results: those older types have different requiredness, title/counter bounds
and optional metadata semantics. The coordinator authorized the Ex-specific
types after independent preflight. No ordinary DTO was weakened to fit the new
facade. An explicit nullable Java Session value distinguishes omitted selection
from JSON null; request strings are never rewritten.

The native coauthor owns only the Python/Go/.NET typed success-status checks and
their existing three conformance drivers. Ex Open accepts `OK`; Ex Run accepts
`ACCEPTED`. Genuine refusals remain explicit; wrong success statuses are invalid
responses. The transport retains existing correlation/cancellation/unknown
delivery semantics and never substitutes a reconnect, fallback or replacement
mutation. No server, database, Home Assistant or prompt capability is changed by
this node.

## Boundary and transport fixtures

The owning DTO-fixture source adds Ex requests/results for missing/unknown/wrong
selectors, ASCII source/external-ID bounds, Unicode/control input, exact text,
nullable session, empty images, conflicting/invalid caps, configured-zero replies,
required output presence and future-field projection. The fixture JSON is derived
only by its owning generator. The coordinator-authorized boundary/model-binding
and locator corrections received independent CORE-03 v4 approval before this
node's review. The [locator correction note](home-assistant-core-03-sdk-locator-implementation.md)
and delivery ledger retain the exact scope and evidence; this is not a scope waiver.

The existing shared socket harness and all four existing native/Node drivers now
exercise public Ex calls. The shared contract defines twelve scenarios: Open/Run
success; omitted/null agent metadata; wrong success status; absent/null/wrong
internal conversation; explicit NOT_FOUND; timeout; and disconnect. The harness
checks the exact request fields and one application submission per scenario, with
no preliminary resolve RPC. Invalid selectors must emit no application frame.
Open success carries a configured zero-call budget. Generic native cancellation
conformance remains, while Java also tests pre-cancellation and cancellation after
observed submission explicitly.

Java socket tests additionally cover complete nullable output presence, exact
session serialization, long title/task text, safe-integer counts above Java's old
Integer range and integral JSON numeric spellings. They distinguish explicit
refusal, invalid response, NOT_SUBMITTED and UNKNOWN without replay.

The fresh-consumer package script imports the named Ex types from both installed
TypeScript/Node roots, imports the generated Python classes and compiles a Java
binary consumer against packaged jars. Existing fresh native conformance repeats
the new Ex socket scenarios for Python, Go, .NET and Node. The Java package step
establishes binary consumer compatibility; its Ex transport evidence comes from
the actual public Java SDK socket tests, not an invented installed runtime check.

## Verification

The coordinator completed these checks on the implementation:

- Focused Java: all 34 invocations across three classes passed, including 21 Ex
  cases, ten unchanged selector/reply regressions and three unchanged request
  validation cases. Saved XML/logs are under `/private/tmp/core04-verification-evidence`.
- Owning native/DTO generation and drift checks passed with 1,012 canonical
  boundary cases. The Python typed-binding regression passed, proving both
  generated Ex result models and their projections.
- Full `sdkCheck` passed in 2m7s. Node, Python, .NET and Go real WebSocket fixture
  conformance all passed, including twelve Ex scenarios each and zero submissions
  for invalid selectors. Log: `/private/tmp/core04-sdk-check-final.log`.
- Direct public Python encoding rejected all 30 locator terminator cases before
  transport. A full TypeScript decoder probe rejected the same
  matrix. Valid padded agent/task/session and named reply text remain preserved.
- Independent CORE-03 v4 review approved the complete affected boundary slice;
  the coordinator accepted dependency candidate
  `221c9e73088c09d71d637ec0e6281f3b25e8920d53b5a2414cd450809f194ac2`.
- Repository `check` passed in 44s/141 tasks on the locator-corrected source.
  A subsequent documentation-only Javadoc/manual clarification qualifies uncertain
  Open recovery by unchanged authenticated stable project namespace. Final bytes
  were formatted, and separate `formatCheck` passed.

The combined final `check sdkPackageCheck` is running at freeze time in
`/private/tmp/core04-package-and-repository-check.log`. Its current final-source
repository result and fresh-consumer package results remain explicitly pending;
the coordinator must attach actual completion evidence before node acceptance.
This frozen report must not be treated as a package pass. Failed interim checks
and their corrections remain in the coordinator's history and affected reviews;
they are not substituted for the successful current checks above.

The implementers ran only static syntax/whitespace and read-only local helper
probes; root ran generation, compilers, formatting, test gates and local package
installation. No real database was required for this SDK slice. These are isolated
SDK/fixture results, not live server/model/device validation. No package was
published, merged or deployed, and prompt-dependent features remain gated.
Independent CORE-04 reviewer approval is still required before node acceptance.
