# HA Assist and read-provider recovery publication — independent review

**Verdict: APPROVE** for committing the reviewed HA changes, pushing the branch
and opening the requested PR. This approval does not authorize merging the PR or
additional live effects.

Reviewer: `/root/ha_deployment_reviewer`, independent of coordinator/runtime
implementer `/root` and the recovery-test implementer.

The exact staged publication candidate is revision **v2**, SHA-256
`8994f5d7d7f88b1a6026ca41d4233a72b2bc33f9c5a1966bc05c5a7f8532e529`.
It binds 224 changed paths and index tree
`a6f0193484bb6cc0e920b1f4b6ffecc030edc0f0`, on parent
`7394f5ef01ce5c1b488eb77c146cd2e1ea862407`. The parent contains only the separately
approved PLAT-SQL-01 mock-test correction above validated main
`4a3ed34eea2d7176e972e5fb08a96f2420b931d6`. Later observed remote main
`872e051aa7d3784bcb417618ee153e5fd1e86e97` is explicitly outside this validation.
The superseded private v1 candidate had inconsistent baseline metadata; v2 fixes
that metadata and the public report wording without changing runtime source.

## Evidence checked

- Independently compared every staged blob's SHA-256, byte count and Git object
  ID with the candidate, checked the exact staged path set and compared the index
  to its recorded tree. All matched, including the recorded obsolete-file
  deletion. Only this new reviewer-authored report may be appended afterward;
  the coordinator must recheck the original staged content before committing.
- The [publication evidence](home-assistant-assist-recovery-publication-evidence.json),
  SHA-256 `c64529d6acfe9a677a44ba685c670ec1fd05ee39765c1cc10dbabf3ef815761f`,
  matches all 130 current source hashes, 31 accepted candidate/review bindings,
  56 installed outbound files and the retained Assist 0.1.1 archive hash. Accepted
  nodes have independent implementer/reviewer identities and review approval;
  the two remaining gate nodes remain open. Historical frozen artifacts are
  retained, with later fixes explicitly superseding earlier source snapshots.
- Inspected the actual full-check log matching its published digest: default
  repository formatting/check and installDist passed in 90 seconds, 144 tasks.
  The retained exact-source Assist log matches its published digest and reports
  Ruff plus 141 Python cases passing. The current HA Java XML reports 189 cases
  without failures or errors. The subsequent owning-module check and fresh
  installed-package report/log match their evidence digests; all eight package
  checks passed. No full-database test run is claimed or required here.
- Read-only deployment and recovery claims are bounded by the independently
  approved [DEPLOY-01 review](home-assistant-deploy-01-v3-review.md) and
  [RECOVER-01 review](home-assistant-recover-01-v1-review.md). Their hashes still
  match the publication evidence. This publication review made no new live calls.

## Scope, privacy and behavior

The staged HA commit contains adapter source, profiles, tests, documentation and
sanitized review provenance. The separate parent changes only the approved mock
test fixture. There is no new production core, SDK, shared API or schema change.
The current plan and ledger accurately record commit/push/PR authorization,
completed read-only deployment and recovery, and no merge authorization.

Control defaults false in configuration parsing and setup. Read-only, task-only
and opt-in control profiles remain separate; task-only profiles have no dynamic
tool grants, and read-only profiles expose only the approved read tools. The
deployed provider's fixed 68-entity state/unit policy remains control-free. HA
prompt/context uses existing SDK task/user semantics without native system-role
equivalence or prefix-cache rewriting claims.

Targeted privacy scans over all staged UTF-8 content found no private LAN address,
credential/JWT/private-key, known private deployment-identity, private-domain or
secret-response-file matches, and no binary files. No raw credentials, private
configuration, live readings or diagnostic dumps are staged. These scans support
the inspected sanitized evidence; they are not a universal secrecy guarantee.

The public handoff correctly separates observed process/network recovery from an
actual host reboot, which was not performed. Microphone/STT/TTS, AI Task generation,
the broader follow-up/isolation/control LIVE matrix and optional cache measurement
remain outside the accepted live scope. Opening a PR does not establish merge
acceptance or add those claims.

No remaining finding blocks this bounded publication.
