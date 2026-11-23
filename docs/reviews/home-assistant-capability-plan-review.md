# CAP-PLAN independent review

Date: 24 November 2026. Reviewer: `/root/ha_capability_reviewer`.
Implementer: `/root`. Verdict: **APPROVE** for the documented design, graph and
behavioral contract. This approves source implementation within that contract;
it does not establish source completion, publication, installation or live use.

The current design separates resource identity from names and locators, and
requires persistent verified registry anchors for rename continuity across
restart. Replacements and absent stable evidence cannot inherit identity from a
name. Discoverable typed fields retain current/target distinctions, units and
per-field provenance. Candidate ambiguity is computed before pagination and
availability cannot silently select a target. Policy precedes metadata, counts
and value disclosure, with fresh effective exposure checks in optional follow
mode. Frozen selection and its six declarations remain the default.

The optional native HA endpoint solves a verified compatibility gap rather than
duplicating exposure policy. HA 2026.9.4's stock exposure-list command reports
stored positive preferences, while the canonical helper also applies defaults.
The contract requires the helper-backed admin-only versioned command, complete
bounded output and no broader or stock-command fallback. Its normal HA-owned
default-preference materialization is documented. No model/device operation,
control grant, Plowshare core/SDK/shared-runtime or database change is authorized.

Ownership is disjoint, each implementer has an independent reviewer, acceptance
requires dependency/source review and operational milestones remain separate.
The reviewer checked all 41 graph nodes for unique identities, existing
dependencies and acyclicity, the five new independent role assignments and false
merge/deploy/live milestones, and local documentation links. All passed. The
earlier diagnostics review artifacts remain immutable historical evidence.

Review corrections are resolved: current design status no longer claims shipped
discovery/AI Task features are absent; original proposed tool names are marked
historical; effective exposure does not rely on the incomplete stock preference
list; provider ownership includes discovery configuration; stable resource handles
are not connection epochs. Detailed Java seam validation and implementation
failure/race evidence belong to subsequent source-node reviews.

Reviewed file SHA-256 values:

| Path | SHA-256 |
| --- | --- |
| `docs/home-assistant-integration-design-and-plan.md` | `ac42f267dd65798aab8749587b867b1a0d2872fd84d6e0547e22d748cd0c692e` |
| `docs/home-assistant-delivery-plan.md` | `e22f0e6d4cfecdd9813e764a6f808a1bab3617f8872821a7f8c1b6e9b67fc5f7` |
| `docs/reviews/home-assistant-capability-contract.md` | `2806ff6f8461982907b639f202e9b4562feff426abcddf5661c036d45a2ffc66` |

The ledger is mutable coordinator closure metadata and is reviewed structurally,
not included in a recursive source/report hash. No Gradle or database run is
needed to approve documentation; source acceptance requires the planned tests.
