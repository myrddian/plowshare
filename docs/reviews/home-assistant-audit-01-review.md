# AUDIT-01 independent review

Verdict: **APPROVE**. No blocking findings.

- Node: `AUDIT-01`.
- Implementer: `/root/ha_audit_implementer`.
- Independent reviewer: `/root/ha_audit_reviewer`.
- Candidate: `ha-audit-01-v1`.
- Manifest: `docs/reviews/home-assistant-audit-01-v1-candidate.json`.
- Manifest SHA-256: `3519fc68795ed2997aeb7d6ffed4511e0e36894939571c4974d2353b7aa0a855`.
- Reviewed audit SHA-256: `33dd279ab7290d76e3fbda4f3e3cc9499a5970ade18a7b212c6825684b7e7093`.
- Date: 17 November 2026.

## Scope and evidence checked

Reviewed the AUDIT-01 acceptance contract, frozen ledger snapshot, audit, tracked
dirty-tree diff and relevant owning implementation sources. Independently
verified the candidate manifest digest, all three manifest file digests, the
frozen tracked diff digest and all 43 repository/installed-HA source hashes in
the audit. The implementer and reviewer are distinct actual agents; this reviewer
did not author the audit or prototype.

Source inspection confirms the important boundary claims: current adapter reads
and fixed-profile actions use typed existing payloads; discovery/catalogue and
named provider capabilities remain absent; the Assist entity forwards only user
text and reports CONTROL unconditionally; its local session map fences uncertain
submissions; current public Python DTOs have neither Ex selectors nor system-role
input. The normal run/open owners, post-commit log.open contract and runtime
system-message folding agree with the audit. Installed HA Conversation/AI Task
preparation and MCP source support the version-specific prompt/lifetime and
transport distinctions. The primitive/fixed-target action subset and native
WebSocket primary/MCP opt-in decision are explicit, conservative implementation
contracts rather than claims of existing broader support.

Independently ran on the unchanged candidate sources:

- Ruff check: PASS.
- Ruff format check: PASS, 14 files already formatted.
- `unittest discover -s tests -p test_agent.py -v`: PASS, 16 tests.
- `unittest discover -s tests -p test_home_assistant.py -v`: PASS, five tests
  using the installed HA APIs and staged unchanged public SDK.
- Installed package metadata: HA 2026.9.4, websockets 17.4, Ruff 0.16.10.
- AI Task entity import: reproduces the documented missing `numpy` dependency.
- `git diff --check`: PASS.

Inspected the two socket fixtures and the implementer's report of their separate
successful rerun after the initial sandbox listener failure. This reviewer did
not rerun those fixtures. The audit accurately records the initial combined
command as nonzero and distinguishes 23 successful test cases across invocations
from a single passing full check command. It does not import historical Gradle
results as current evidence.

## Limits and disposition

No runtime feature is accepted by this audit approval. Java adapter tests, full AI
Task runtime compatibility, live interoperability, model behavior, SQL behavior
and cache performance remain unverified as stated. No database, Gradle, live
service, model or device operation was run during this review. The prompt-role
public capability gate remains unsatisfied; approval of an honest gap report
cannot satisfy it. Deployment and live effects retain their separate gates.

Findings: none. AUDIT-01 satisfies its baseline/investigation deliverable and may
be accepted by the coordinator for this exact candidate.
