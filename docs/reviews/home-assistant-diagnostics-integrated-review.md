# DIAG-03 independent integrated review

Date: 24 November 2026. Reviewer: `/root/ha_diagnostics_reviewer`.
Implementer/coordinator: `/root`.
Verdict: **APPROVE** for source/package verification and delivery records.

Approved candidate: `ha-diagnostics-v1`, baseline
`f33a10daeaf09f84189edfba1039ee98c0b8ec9e`.
The [evidence manifest](home-assistant-diagnostics-evidence.json) has SHA-256
`262389b13889a7e40129d4274b16926384451ed566ba5bbe8eb8846445bbcd69`.
Its 135-file source snapshot has canonical compact sorted-JSON SHA-256
`45a63f0cc51a2f17362677c8d55460596a7237319dd451cc724fb4e409f6ffd0`.

The reviewer checked every listed source byte hash, all four check-log hashes,
the archive hash and every listed archive/distribution file against actual local
artifacts. The source snapshot matches the two independently approved source
nodes. Mutable ledger closure metadata and these independent review reports are
explicitly excluded from the snapshot to avoid recursive hashes; they remain
separately identified in the ledger. The evidence's pre-review milestone remains
false by design; this report supplies the later independent decision.

Observed validation: Ruff and all 150 Python tests passed on the installed HA
2026.9.4 runtime; final HA Java XML contains 200 tests, zero failures/errors/skips
across 24 suites. The repository `format formatCheck check` and provider
`installDist` completed successfully in 4 minutes 44 seconds. The final standalone
distribution refresh and all eight installed outbound offline checks passed.
The Assist 0.1.2 archive has 53 accounted entries, including the diagnostic hook,
and unchanged public SDK bytes; the outbound distribution has 56 accounted files.
Earlier assertion/link failures were corrected and final evidence reflects the
corrected source. No PostgreSQL test was required or run.

The plan, contract, delivery explanation, graph and ledger clearly separate source
acceptance from publication, installation and live validation. The graph is acyclic
with independent implementer/reviewer assignments. New tool grants are exact;
control and the selected entity/attribute policy stay unchanged. Source changes
remain within the HA adapter and its delivery documentation; protected core,
protocol, SDK and shared integration runtime are unchanged. The catalogue identity
change and old-owner/journal preservation are documented. Native HA envelope
privacy and historical local observations are accurately limited, without claimed
end-to-end availability or inferred physical causes.

No unresolved actionable finding remains. This approval does not claim a live HA
download/provider test, deployment, merge, publication, host/HA/server restart,
new model work or device action. Those milestones are false in this candidate.
