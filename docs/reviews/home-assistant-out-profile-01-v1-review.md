# OUT-PROFILE-01 independent review

Verdict: **APPROVE**.
Reviewer: `/root/ha_outbound_profiles_reviewer`.
Implementer: `/root/ha_outbound_profiles_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Baseline: `6de685f63e060f3003cb93233d2567e004269755`.

Candidate: `docs/reviews/home-assistant-out-profile-01-v1-candidate.json`.
Candidate SHA-256:
`a3b565c0391e21f2df9a5210af0dbef8d9b1096aafc839624b37258af01d6fdc`.
Implementation report SHA-256:
`d4517e721e1d08c934b937bdb59915502f8b629923d945d939d22e07f0a283fc`.

## Exact scope and independence

I independently read the ten authored example/test files and implementation
report, compared them with the approved OUT-PLAN-01 v2 host contract and inspected
the owning Application policy/provider/definition loaders. I did not author or
change the profiles, tests, host, plan, ledger or previous evidence, and did not
run Gradle. Only this new review report is authored by this reviewer.

All eleven candidate file digests (ten example/test files plus the implementation
report), the frozen ledger snapshot, working-tree diff and five dependency
manifest digests match. The frozen ledger assigns different actual implementer
and reviewer agents, marks all required dependencies accepted, and retains all
eleven original gated nodes and all four unsatisfied gates.

## Contract findings

There are no blocking findings in this profile candidate.

- Both complete Application roots have distinct project and local agent names.
  Their configured execution principals match each static provider authority and
  host binding. The control Application's read/control providers share its one
  execution principal and SDK credential reference, while retaining separate
  logical provider names, prefixes and nonnested journal paths. This fits the
  current same-account fence in ApplicationToolRegistry.assigned and the
  executionAccount check in ApplicationToolScopes.permits.
- Read-only policy names exactly the four existing read declarations; control
  additionally names exactly the two existing control declarations. Assignment
  is to the local dynamic agent only. No wildcard, built-in tool, workspace scope,
  delegation, workflow, inference endpoint or Assist/AI Task entry point is
  introduced. The read-only host has no configured native action or control host.
- Empty access.accounts and inert, consistent principal placeholders do not
  provision membership or live grants. Agent exported visibility remains subject
  to ordinary current work authorization. Operator guidance correctly separates
  execution principals, membership handles, provider discovery and grants.
- The control example admits only one fixed, fully disclosed lamp_on target.
  Agent instructions preserve the exact action offer and distinguish delivery,
  physical observation and causality; uncertainty never authorizes replacement
  actuation. The README retains the legacy-feedback composition limit rather
  than promising cross-host loop suppression.
- Origins are nonconnecting example values; credentials are environment-variable
  references. Relative private journals are explicit configuration. No real
  deployment identity, credential, private address or runtime dump was found.

The tests use actual WorkspaceApplicationPolicy, ApplicationServerSettings,
ApplicationToolScopes, AgentRegistry and RuntimeApplicationPackageValidator
parsers. Negative matrices cover unrelated principals/agents/providers/tools,
future tool names, missing assignments, absent/nondynamic assigned agents and
wrong project identity. DefinitionChecks.NONE explicitly excludes model-fleet
validation; the test fixtures start no server, database, inference or device.
Raw JSON is confined to deliberate manifest-input mutation in the test boundary.

## Verification inspected

The coordinator's exact log `/private/tmp/ha-outbound-early-check.log` has SHA-256
`446d2160b3ea97e74742bffc099271e80d5a8e3fd560887f99c37bb93c502490`.
I verified that digest and independently counted twelve passing
OutboundProfilesTest cases and twenty-four passing host-configuration cases, with
BUILD SUCCESSFUL in 8 seconds. The coordinator reports formatting in that same
invocation; the compiled profile test bytes match this candidate after formatting.
These are the coordinator's checks, not a claim that this reviewer reran them.

## Integrated limits and handoff

This approval covers the frozen profile/example/test contract. It does not accept
the parallel OUT-PACK-01 host or establish installed-artifact, public live-server,
model-fleet, actual grant issuance, device or deployment behavior. OUT-VERIFY-01
must bind the accepted final host and profile bytes to the actual distribution.

The README's relative links to the existing Assist gate, tool-scope guide and
observation limit resolve. Its PROVIDER-HOST.md link names the agreed OUT-PACK-01
owned output, which was still being authored at this review. That package-dependent
link must resolve in the integrated artifact; this is an explicit packaging and
OUT-VERIFY handoff obligation, not proof of a completed distribution. Any later
host contract change affecting these profiles requires corresponding revalidation
and review before integrated acceptance.

No prompt gate is satisfied, original gated node closed, or live operation
authorized by this review.
