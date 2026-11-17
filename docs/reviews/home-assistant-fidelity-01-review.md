# FIDELITY-01 independent review

Verdict: **APPROVE** for the exact investigation/report candidate below. No blocking findings.
This approves the public capability-gap finding and the successor proposal as a concrete authorization target; it does not approve an implemented prompt path, authorize platform work, or satisfy `prompt_public_path_verified`.

- Reviewer: `/root/ha_fidelity_reviewer`.
- Implementer: `/root/ha_fidelity_implementer`.
- Candidate: `ha-fidelity-01-v1`.
- Manifest: `docs/reviews/home-assistant-fidelity-01-v1-candidate.json`.
- Manifest SHA-256: `e6b2632070a658f3260ea146985ffacfb954f7cc67adf386a89d124135271f8c`.
- Report under review: `docs/reviews/home-assistant-prompt-fidelity-decision.md`, SHA-256 `c3b6b7890e92c8f64e361bbcc9b08bfe0aaca9c3228f3b01678553ef6e2a775d`.
- Dependency: accepted AUDIT-01 candidate `3519fc68795ed2997aeb7d6ffed4511e0e36894939571c4974d2353b7aa0a855`.

## Scope and evidence

Independently verified the manifest digest, all four manifest-bound file hashes, frozen tracked diff hash, and all 17 repository/installed-HA/probe source hashes in the investigation. Read the actual public Python request DTOs, server run request/handler, prompt assembly, hook contracts, fixed opening, historical projection and transcript source. The public API has task text but no changing native system-context field; `prompt.pre` additions are USER content and `log.open` contributes only a fixed opening. The report correctly distinguishes copied characters from native role preservation and does not treat an agent rewrite, fresh conversation, or arbitrary history injection as a valid workaround.

Inspected installed HA 2026.9.4 ConversationEntity, ChatLog prompt rendering, LLMContext, AI Task entity/helper and session handling. Conversation prompt preparation is adapter work; AI Task supplies a prepared log and fresh per-invocation session. The report preserves this distinction and rejects unsupported advertised tool APIs before submission. Read the current official [Conversation documentation](https://contributors.home-assistant.io/core/entities/conversation/) and [AI Task documentation](https://contributors.home-assistant.io/core/entities/ai-task/) as entry-point references; installed baseline source remains authoritative where newer feature/schema documentation differs.

Independently reran `/private/tmp/plowshare-ha-venv/bin/python /private/tmp/ha_prompt_impact_probe.py`: PASS, all six observations reproduced, with unchanged results digest `dfbf54d43aa8c8763d518e990ebd77381d32ff2337980d698181e1d5a2291930`. Clock changes shorten the common character prefix; the fixture date/time tool suppresses that particular rendering change; custom state/extra prompt changes occur at their respective positions. These remain character/helper observations, not token counts, cache-hit measurements or latency claims. No fixture tool was executed.

Independently checked package metadata and imported the AI Task entity: HA version is 2026.9.4; import fails on absent optional `numpy` through camera/stream. The limitation is accurately recorded, and no full AI Task pipeline compatibility is claimed. No Gradle, database, live HA, model, device or deployment operation was run in this review.

## Proposal assessment and remaining gates

The proposed single typed external system-context block is narrower than arbitrary message injection. It preserves source content while retaining selected-agent admission, current server authorization, provenance as untrusted metadata, durable per-run snapshots, next-utterance refresh, original accounting and uncertain-submission behavior. Default-off profile admission and bounded input are appropriate explicit successor requirements. Merging into the first system message is a documented packaging adaptation, not a guarantee of enforceable priority between system paragraphs.

The successor ADR must settle exact wire names/bounds, omitted-context inheritance versus clearing, admission policy, and resume/refresh details before implementation. Those are declared successor contract work, not current public API promises. Its implementation and all-SDK/HA verification must pass separate independent review before the prompt gate can change. Prefix analysis must use the original HA text and actual backend evidence; no cache optimization rewrite is approved here.

The shared design document is being updated separately under CORE-01 ownership. It is not bound into this candidate. Root must obtain a final consistency check of the frozen CORE-01 design summary/link before closing FIDELITY-01. Its existing prompt-preservation and public-role limitation sections are consistent with this report; any later substantive change requires review of that changed candidate.

Findings: none. Runtime implementation, prompt-path verification, merge, deployment and live validation remain separate and unapproved by this report.

## Final shared-design consistency supplement

Verdict: **APPROVE** the completed FIDELITY-01 gap-decision scope at the following exact pair of artifacts:

- Investigation report SHA-256: `c3b6b7890e92c8f64e361bbcc9b08bfe0aaca9c3228f3b01678553ef6e2a775d`.
- Shared design `docs/home-assistant-integration-design-and-plan.md` SHA-256: `e305ff76fb6fc52530bbfb4ce03f989071562fd879964631565664aed4463166`.

Independently recomputed both hashes and rechecked the original candidate manifest digest `e6b2632070a658f3260ea146985ffacfb954f7cc67adf386a89d124135271f8c`. Read the shared design's prompt-preservation, public SDK limitation, new FIDELITY-01 summary/link and adjacent cache-impact sections. The summary accurately records no current refreshed native-system route, USER-role prompt.pre, fixed log.open, a still-unsatisfied prompt gate, and an unauthorized/unimplemented externalContext successor. It keeps Ex identity resolution distinct, permits independent outbound HA work and forbids hidden fields/provider bypass or agent mutation workarounds. Its original-prompt/cache-impact requirement is consistent with the investigation and does not infer actual cache reuse from helper character measurements.

The previous pending shared-design consistency check is now complete. No findings remain for this investigation node. Root may accept the gap-decision deliverable after recording this supplement; `prompt_public_path_verified` must remain unsatisfied. This supplement neither authorizes nor approves the proposed platform extension or any operational step. No source, runtime or ledger changes were made by this reviewer.
