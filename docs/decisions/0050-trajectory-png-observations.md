# 0050: Entry-owned PNG observations for the active model loop

Current admission policy: [Decision 0053](0053-bounded-image-context-admission.md)
replaces the historical mandatory exact-count gate with bounded local reservations;
the counter/sidecar is optional. Other lifecycle and format contracts remain intact.

Status: implemented; deployment/backend acceptance remains separate.

Negotiated Node tool results already retain exact admitted PNG references, but the
agent loop used a string fallback and lost native observations at restart. Relay
retention cannot own conversation evidence. A completed invocation also needs an
independent lifetime once its result becomes a Trajectory entry.

Keep the existing tool envelope and admission authority. Add typed content to the
agent-tool seam and immutable entry metadata, with a specialist repository that
validates exact producing invocation/run/call provenance and acquires independent
entry blob claims in the append transaction. No new byte store, execution journal,
Relay behavior or upload contract is introduced. Ejection releases only entry claims.

Preserve correlated text tool replies. Derive a user-image message after the whole
tool-result batch, using the existing native serializer. Retain only entry/slot/ID
references in standing history, and resolve through the owning entry claim just
before dispatch. Original provider content is distinct from the effective
post-hook observation. Redaction/withholding excludes pixels; output-only content
remains a descriptor. No hook image editing protocol is added.

Require a verified complete image-aware context count on the selected route and
reserve explicit output capacity before inference. Reuse the existing counter,
with an explicit disabled-by-default deployment assertion for image token
inclusion and a pinned revision. Format declarations cannot replace that evidence.
Existing text routes retain their original invocation path.

Provider recapture, typed result redemption, new public media readers/viewers,
other producer SDKs, audio/video and native generated-image model outputs remain
outside this slice. Missing/corrupt/revoked input must fail rather than rerun a tool
or silently drop pixels. See the [implementation contract](../trajectory-image-observations.md)
for lifecycle, bounds, configuration and verification limits.

Verification on 23 October 2026: `./gradlew format` and
`./gradlew formatCheck check` passed. The focused PostgreSQL gate
`./gradlew :plowshare-server:test -PfullDb --tests '*TrajectoryArtifactsPersistenceTest' --tests '*EntryStoreTest'`
passed 107 tests. It exercises real claim/append transactions, retained accounting
runs distinct from jobs, upgrade/ejection triggers, invocation-deletion locking,
restart reconstruction and final-claim collection. The default gate includes
controlled loop/dispatcher acceptance, post-hook redaction, durable-write failure,
strict metadata decoding and counter serialization/compatibility tests. No live
capture provider or deployed vision backend/counter was verified. Changes remain
uncommitted and undeployed by this task.
