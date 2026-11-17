# PLAN-00 amendment independent review

Verdict: **APPROVE**

Node: `PLAN-00`. Candidate: `ha-plan-00-v2`.
Reviewer: `/root/ha_plan_reviewer`.
Implementer: `/root/ha_sql_budget_implementer`; coordinator: `/root`.
Reviewed on 17 November 2026. The reviewer authored none of the amended plan,
SQL design, ledger snapshot or runtime material.

## Exact candidate

[Candidate manifest](home-assistant-plan-00-v2-candidate.json) SHA-256:
`554444e229c006158750509e680304783d34167eae3c8851325c2f6dcd17b092`.
Base commit: `2d95d9ce4fb75e906b61e8f2afa940d04cf9bf2e`, plus the identified
dirty working tree. Independently recomputed hashes match:

| Artifact | SHA-256 |
| --- | --- |
| `docs/home-assistant-delivery-plan.md` | `5fc474f2d9f24ce9e7b0dc4919fe7a41ab99bcf53155682be345dbf8a32bea91` |
| `docs/reviews/home-assistant-ex-sql-budget-design.md` | `da6a8e49bd3f92b5cb71d0a2bd9b96bfa20b3ce26732c6ee0cba57f21d257d18` |
| `docs/reviews/home-assistant-plan-00-v2-state.json` | `fc2b50acc53578174fc746edd15916387d0b63d309bf5c91ce20afabdd1b3bc5` |
| `docs/reviews/home-assistant-plan-00-v2.diff.gz` | `d61c026ab409d7e5564a4c707f36c03f214f5f20d350ac886c3630742611cca3` |

The compressed tracked diff was hash-verified and successfully decompressed.
Untracked planning artifacts are identified by their separate file hashes.
The historical v1 review and structural evidence in the manifest do not approve
v2; the checks and verdict below apply to v2 itself.

## Review and evidence

Read the amended graph, complete scoped SQL design, candidate and frozen/current
ledgers, delivery policy, persistence/cache guide and backpressure guide. Inspected
the owning pool/projection composition, identity queries, caller access, logical
read deadline, inline cache-fill contract, current Ex facade and synchronous
log-open/hook deadline sources to assess the stated gap. This is design review,
not approval of the in-progress facade implementation.

Independent structural checks passed:

- 24 unique nodes and 41 dependency edges; all references resolve and the graph
  is acyclic. Mermaid edges, table dependencies/roles and all deliverable headings
  match the frozen ledger. Live graph, role, gate and ownership fields match the
  frozen snapshot.
- `CORE-03A` depends on accepted `CORE-02`; `CORE-03` now depends on `CORE-03A`.
  The new node is planned with no implementation, approval or operational
  completion claimed. The already dispatched CORE-03 semantic work remains
  unaccepted; accepted CORE-03A is required before its new wiring and closure.
- Implementer/reviewer roles remain distinct. Exact-candidate independent
  approval, invalidation on material change and root's inability to waive a
  rejection remain intact. Current PLAN-00 review bookkeeping correctly identifies
  v2 as in review with no verdict; the snapshot retains v1's historical digest.
- The prompt-fidelity, backend-measurement, deployment and live-acceptance gates
  remain unsatisfied. No prompt API, prompt rewrite or deployment permission is
  added by this amendment.
- Local Markdown links, fence balance, trailing whitespace and
  `git diff --check` passed.

The design is an appropriate internal dependency of the authorized generic Ex
facades and existing completion policy. Actual identity queries and authority
checks occur outside the alias repository's private transaction watchdog;
ReadDeadline alone does not physically cancel them. Covering both main and
independent projection leases is necessary. The proposal keeps SQL in repository
owners and budget infrastructure in composition, retains the raw primary pool,
shares the decorated source identity between template and transaction manager,
and explicitly covers admission-disabled composition. It creates no facade-wide
transaction or new public capability.

Cache ownership is addressed explicitly: original authorized warm hits remain
reusable, existing compatible shared fills may be joined without request-local
cancellation reaching their owner, and an atomic existing-read operation cannot
elect a new shared loader. Only actual Ex misses use the existing uncached path
under request ownership. Unscoped behavior remains unchanged. Acceptance requires
authority/freshness invariants, candidate/confirmation paths, joiner isolation and
the additional query-count cost to be tested; private misses are not presented
as a performance improvement.

The required evidence covers a shared finite operation deadline, query completion
through result materialization, acquisition interruption, transaction and lease
reuse, cancellation/close fences, existing interrupts, bounded physical occupancy
and cleanup, shutdown, driver assumptions, uncertain commits and no replay.
Metrics must reflect these actual boundaries with bounded labels. Mock/barrier
tests are the default; only selected tagged PostgreSQL assertions requiring real
lock/stream cancellation, rollback, acquisition or pool reuse justify `-PfullDb`.
These are implementation acceptance obligations, not results claimed by this
planning review.

Whole-call completion remains separate: SQL cancellation cannot establish finite
hook loading, file access or arbitrary callback completion. CORE-03 must verify
those owning phases under their remaining budgets or retain a precise unresolved
prerequisite. The plan forbids treating this SQL design as universal enforcement,
skipping log.open, replaying uncertain work or changing a shared background
lifecycle to obtain a passing deadline claim.

## Findings and closure

No unresolved findings for this planning amendment. **APPROVE** applies only to
the exact v2 graph/design candidate above. Root may record PLAN-00 acceptance for
that candidate and dispatch CORE-03A under its stated boundaries.

No SQL-budget runtime implementation exists in this reviewed deliverable. No
Gradle, database, provider, HA or live-device checks were run. This verdict does
not approve unreviewed CORE-03 code, establish global deadline enforcement or
close CORE-03A. Its implementation needs its own actual evidence and independent
review; CORE-03 then needs accepted integration and its own complete-path review.
If implementation requires broader behavior changes or lacks an owning
capability, report the exact gap before claiming either node complete.
