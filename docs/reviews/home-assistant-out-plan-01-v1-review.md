# OUT-PLAN-01 v1 independent review

Verdict: **CHANGES_REQUIRED**.

Reviewer: `/root/ha_outbound_plan_reviewer`.
Implementer: `/root`.
Candidate: `docs/reviews/home-assistant-out-plan-01-v1-candidate.json`.
Candidate SHA-256: `4e6bc52eb7d37172d576c5ce6d799b144879a22c6f34b0c547df1e1d72d0f314`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

The reviewer inspected the actual frozen plan/host contract, ledger snapshot,
working-tree diff, owning HA provider/adapter and SDK lifecycle APIs, and current
Application provider authorization owners. The checks below initially established
structural consistency; the final owning-authority check found the blocking
principal incompatibility described next. Runtime source dispatch is not approved.

## Findings

### OUTPLAN-R1 — P1: distinct provider principals make the combined control profile unusable

Locations: `docs/reviews/home-assistant-outbound-host-contract.md:88–92` and
`docs/home-assistant-delivery-plan.md:537–538`.

The common contract requires distinct read/control provider principals and token
references, but proposes both providers in one control Application with one
`executionAccount` and one controller agent. Current
`ApplicationToolRegistry.assigned` requires the invoking account to equal the
registry entry's provider account before consulting the Application policy
(`plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/ApplicationToolRegistry.java:240–245`).
`ApplicationToolScopes.permits` also requires that account to equal the manifest's
single execution account
(`plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationToolScopes.java:67–75`).
Consequently no single admitted Application execution principal can use both
configured providers when their principals differ. Picking the read principal
refuses control; picking the control principal refuses discovery/read, including
the resource revision needed for a control offer. Successful configuration parsing
or declared exact scopes cannot override this current server ownership fence.

Correct the contract/profiles to use the same explicit execution principal for
read and control providers within the control Application, allowing the same
credential environment reference while retaining separate SDK connections if
useful. Keep distinct logical provider names, nonoverlapping prefixes, exact
agent grants, private journals and local control policy. Different read-only and
control Applications may use different execution principals. Existing
`HomeAssistantActionTools` requires separate logical providers/prefixes and does
not require different principals; this correction needs no core/SDK extension.
An alternative is acceptable only with demonstrated support through existing
public authorization contracts. Do not patch the server to accommodate this host.

Acceptance for the revision should explicitly test provider-binding account /
Application execution-account equality, exact read-only versus control grants,
and refusal for a mismatched principal through current authorization owners or
fixtures that faithfully exercise them, rather than manifest syntax alone.

Disposition: unresolved; revised frozen candidate and independent review required.

### OUTPLAN-R2 — P3: distinguish empty access grants from nonempty tool policy

Location: `docs/reviews/home-assistant-outbound-host-contract.md:94–97`.

“Empty access/manifest grants in examples” is ambiguous because the profiles must
contain exact nonempty `toolScopes` and `toolGrants`. State that `access.accounts`
is empty, exact tool policy is declared, and placeholders/declarations alone do
not provision authenticated membership or effective server authority. This is a
clarity correction to make the intended current policy reviewable.

Disposition: clarify alongside R1 in the next candidate.

## Checks completed

- Verified manifest digest, both authored-file hashes, frozen ledger snapshot and
  diff hashes against the candidate.
- Independently checked 28 nodes and 55 edges, dependency references and acyclicity,
  distinct implementer/reviewer roles, and four new outbound-only node assignments.
- Compared the frozen ledger with the committed baseline: all eleven original
  `awaiting_gate` nodes and all four gate objects are unchanged; historical original
  candidate revisions, evidence and reviews are retained unchanged.
- Verified PR #300 merge `16dec24b` is an ancestor of the current baseline. Thirteen
  accepted nodes gain merge evidence without acquiring deployment/live milestones
  or claiming reapproval of later source revisions.
- Confirmed current plan SQL wording agrees with actual SQL-unit limits and
  warning-only accepted workflow duration, preserving historical reports.
- Confirmed public HA composition APIs expose maintenance, catalogue renewal,
  read-only reconciliation, polling, withdrawal and bounded close; production
  adapters remain outside core and SDK changes.
- Checked explicit prompt/context gates, no Assist enablement, no live-operation
  authorization, named-host separation from legacy feedback workflows, and
  default mocked/local-transport validation with truthful proof limits.
- Validated new local plan links and `git diff --check`.

No Gradle, PostgreSQL, model, live HA, device, publication or deployment operation
was run. No plan, ledger, contract or historical artifact was edited by the reviewer.
The only authored file is this review. Structure passes do not approve the
incompatible principal contract; source dispatch must wait for R1 correction and
independent approval of the exact revised candidate.
