# DIAG-02 independent review

Date: 24 November 2026. Reviewer: `/root/ha_diagnostics_reviewer`.
Implementer: `/root/ha_diagnostics_download_implementer`.
Baseline: `f33a10daeaf09f84189edfba1039ee98c0b8ec9e`.
Verdict: **APPROVE** for source implementation and verification. This does not
establish publication, installation or live diagnostics availability.

The actual component diff and owning SDK gateway, runtime, configuration and
storage contracts were reviewed. The native hook constructs its data from an
explicit allowlist, reads the existing retained-owner registry without creating
one, and performs no network, storage or model operation. Failed or absent setup
remains reportable. Per-request connection evidence is historical and timestamped;
the separate provider/device state is explicitly unobserved. Counts disclose no
correlation identities. Exceptions contribute fixed categories without their
messages. Existing request delivery, durable fences, cleanup and uncertainty
behavior remain unchanged. Control and entity/attribute policy are unchanged.

Review refinements are resolved: native HA envelope/filename privacy is documented
separately from integration-owned data, and normally owned platforms are named
`owned_platforms`, avoiding an implication that active platforms are stuck.
No unresolved actionable finding remains.

Evidence inspected: full component check (Ruff check/format and 150 tests), log
SHA-256 `a435f1a0f78a7e9dd37e751b736b78dbfd84fa540f4c7870775e1633242fa6d9`.
The reviewer separately ran the nine diagnostics tests against installed HA
2026.9.4; all passed. Tests cover absent/failed setup, sensitive sentinel exclusion,
retained cleanup and shared counts, historical SDK success, transport/refusal/IO
categories, accepted pending work, unknown delivery and storage fencing without
diagnostic replay. Native download discovery in a running HA instance remains a
later live check; mocked transport and hook tests do not establish it.

Approved source hashes (paths relative to `integrations/home-assistant/assist/`):

| Path | SHA-256 |
| --- | --- |
| `README.md` | `77d4f61175c4617906852abee7c670d5bf54dce32af87ff965b0b790f7fece7a` |
| `custom_components/plowshare/agent.py` | `dfeb69e32b8fa44b71cc57c92148b76c80f418c86f16052094fefe220dac0051` |
| `custom_components/plowshare/const.py` | `efa562a954999259dd45b28970c8d7d197d65719afb5e16749921af34c8b6601` |
| `custom_components/plowshare/manifest.json` | `7d01329632e393be487aa9c7a926a1debe8d780a2134f6002a8e919a938cf2d7` |
| `custom_components/plowshare/runtime.py` | `22bc9b7e27917da0bfef3bdb2b0ab76194e67a22d0e0c2d5dc8bc72d04475b00` |
| `custom_components/plowshare/diagnostic_models.py` | `4a858b1e4d94dfa9dd22e4c125b95759b8de50e3d86ef484b551d81fb527f6d0` |
| `custom_components/plowshare/diagnostics.py` | `91f964843a401d622ef7ef313bc2e61174381bf5d3189e7a0e052222c25a1796` |
| `tests/test_diagnostics.py` | `46e0cf90e1a00b313ea5ad1ed4730794d26fb78aa23d0c123eb6fbe718e1cda1` |
