# HA-11 v1 independent review

Verdict: **APPROVE**.

Reviewer: `/root/ha_packaging_sdk_reviewer`.
Implementer: `/root/ha_packaging_sdk_implementer`.
Candidate: `docs/reviews/home-assistant-ha-11-v1-candidate.json`.
Candidate SHA-256:
`df6d5565452fc38b805a471e232de624b4749f9878f1b8de6e387723c726d774`.

Approval binds the candidate's exact 17 existing-file hashes and the explicit
deletion of `custom_components/plowshare/availability.py`. I independently
verified the file, deletion, frozen diff, ledger snapshot and four dependency
manifest identities. I read the implementation report, actual packaging/tests,
operator documentation, readiness changes and coordinator evidence. I authored
no implementation, modified no ledger and ran no builds or tests during review.

## Findings and resolution

The initial preflight identified unsafe staging/source overlap, output paths
that could overwrite package input, direct documentation/license copies lacking
file admission checks, hidden/runtime contamination and a stale outbound guide
gate statement. The frozen candidate resolves these findings. No blocking
findings remain.

- Staging rejects source ancestor/descendant overlap before copying. Packaging
  allows checkout output only under the Assist build directory, rejects existing
  source vendor trees and refuses symlinks, special files, hidden inputs and
  unexpected file types. Generated Python caches/bytecode are excluded.
- The complete current public Python SDK source/resource set and LICENSE are
  copied unchanged into the private component namespace. Manifest requirements
  must exactly equal the SDK dependency declaration. Relative SDK imports and
  resource loading remain valid under that namespace; no SDK patch or new API
  is introduced.
- The standard component topology, artifact-local install/profile guides, three
  unchanged Application roots and contents manifest are assembled in isolation.
  Sorted paths and fixed ZIP metadata are deterministic for identical inputs.
  Staging and ZIP completion precede atomic replacement; staging/write failure
  preserves the earlier artifact and removes temporary output.
- I reconstructed the accepted HA-07 setup and HA-06 config-flow sources from
  their immutable candidate diffs and compared them with this candidate. The
  differences are exactly the obsolete readiness imports/checks and associated
  comments. Journal, gateway, platform ownership, source identity and actual
  configuration validation behavior are preserved. Tests remove obsolete guard
  bypasses rather than replacing them with another availability override.
- The root-coauthored integration guide and HA operator guides accurately state
  ordinary SDK task/user semantics. They make no native system-role, special
  compaction, cache correction or cache-hit promise. Configuration fields and
  profile grants remain distinct; only server/Application authority grants tool
  access. The optional task profile remains provider-free.
- Documentation covers new chat ownership, foreign/unknown/expired ID refusal,
  in-process reload versus process-restart limits, retained pending work,
  credential/routing fences, legacy journal quarantine and rollback without
  stale receipt replacement. The HA 2026.9.4/Python 3.14 baseline is explicit;
  installed target compatibility and live behavior are not claimed.

## Verification inspected

The coordinator-owned full Assist check passed Ruff lint, Ruff formatting and
134 tests in 2.240 seconds. I read the actual log and independently verified:

- `/private/tmp/ha-11-full-v1.log`, SHA-256
  `b5e212c918b0e2796664276b1c474c9fcf5e29117bbe2824b910e22513b89203`.
- `/private/tmp/ha-11-full-v1-source.json`, SHA-256
  `291a90d28838e963a1f871bb1f948f0e52b3f6061dc6cde51c326d8a8fb5478e`.
- All 29 captured shared source/test files still match that checked snapshot.

The inspected tests build and extract a real CLI ZIP outside the checkout,
compare complete SDK/resource/license and profile bytes, verify archive path and
contents-manifest integrity, resolve artifact-local documentation links and run
a fresh isolated Python consumer. That consumer imports real HA entities and
uses the bundled Ex codec/resources, opens the actual offline config form and
rejects unsupported input without opening correlation storage or an SDK
connection. Additional tests exercise dependency mismatch, source/output
contamination and failed stage/write preservation. Actual HA reconfiguration
updates credentials/timeouts while preserving receipt bytes; route changes are
refused before validation/reload. Existing real HA lifecycle, session isolation,
gateway, structured result, uncertainty and recovery suites remain enabled.

All 4,042 protected core, protocol, SDK and shared integration runtime source
hashes match the start-of-task snapshot. HA-11 introduces no core/shared-contract,
SDK, persistence-schema or runtime extension. No PostgreSQL test was needed.

## Acceptance limits

This approval establishes verified and independently reviewed adapter packaging
and readiness source for the declared SDK contract. Final repository/distribution
verification and release-readiness evidence remain their own delivery nodes.
It does not establish publication, merge, deployment, actual HA version, live
voice behavior, grants, model/device success or backend cache measurements. No
live HA/provider/model/device actions or operational restart were performed.
