# RECOVER-01 operator v3 preflight — independent review

Verdict: **APPROVE**.

Scope: The exact same-container restart-policy update and idle provider-only
application-exit/network-fault checks previously reviewed, with corrected mount
identity comparison. Actual recovery/read acceptance remains separate.

Implementer: `/root`.
Reviewer: `/root/ha_deployment_reviewer`.
Private candidate: `/private/tmp/ha-provider-recovery-20261123/ops-v3/ops-candidate.json`.
Candidate SHA-256:
`224f2f74078530f025864648677bed3eb7a2995c7cefacf1deb1d911e2ceb484`.

I verified all five bound file hashes and read the exact three script diffs from
approved ops-v2. The only executable change replaces ordered mount-list equality
with equality keyed by unique destination, preserving exact source and read/write
values. Duplicate actual destinations are refused; the frozen expected mount set
also has distinct destinations. This corrects an ordering-sensitive identity test
without weakening any mount boundary.

The bound actual refusal receipt records no policy-attempt file, restart policy
still `no`, the original provider running and the same semantic mount set in a
different order. The v2 verification happens before attempt creation or Docker
update, so this was a known pre-mutation refusal, not an uncertain mutation to
replay. Original v1/v2 files, candidates and reviews remain immutable.

All other approved safeguards remain: same container/image/configuration/launcher
and 56 artifact hashes; same journal owner/identity and nondecreasing original
replay fences; no control, actions, subscriptions, replacement or server changes.
Recovery still requires a new process, changed catalogue publication ID, publication
time no earlier than that process start, an unexpired lease and settled receipts.
The policy update itself must not restart the current process.

The one-shot attempt records and finite observation budgets are unchanged.
Application exit signals only the provider's PID 1. Network injection affects only
its recorded bridge attachment and restores that attachment in finally. Unknown
outcomes are inspected rather than blindly repeated; no forced kill, journal
reset, Docker-daemon/main-server/HA/host restart or credential change is introduced.
Host boot remains configured-policy evidence rather than an actual reboot claim.

No blocking preflight finding remains. The exact v3 operations may proceed within
the user's authorized scope. Final RECOVER-01 approval still requires frozen test
and broad-check evidence plus actual policy, recovery and fresh read receipts.
I made no live call/mutation, read no secret file and changed no implementation or
ledger; this review is my only new file.
