# Assist publication gate independent review

Reviewer: `/root/ha_publication_gate_reviewer` (independent of both named implementers).
Date: 17 November 2026.
Decision: **APPROVE** the publication correction only.

## Exact candidate

- Manifest: `docs/reviews/home-assistant-publication-gate-candidate.json`
- Manifest SHA-256: `aad3fe80235aafba44d59b2f8dc256e9049cf4eb25808847cb2a8f254ff83b24`
- Implementation report SHA-256: `8ebb78e809e6921e93e9d16ab058c476c16a0a7c7841399006a0c42028788e81`

I independently recomputed and verified all eleven file hashes in that manifest,
the manifest hash, and the implementation report hash. Approval binds those bytes.
I inspected the actual source, configuration translations, tests, documentation,
entry-platform wiring and unchanged SDK agent import boundary. No source or ledger
was modified during review.

## Findings

No blocking findings.

The source capability remains false, with no environment, configuration or CLI
switch. Normal HA entry setup refuses with a non-retrying `ConfigEntryError`
before constructing storage, interpreting configuration, constructing an SDK
agent or forwarding the conversation platform. Module imports do not connect the
SDK. The initial/reconfiguration steps abort before credentials are validated or
a reconfiguration entry is looked up. The setup gate leaves existing correlation
bytes untouched, and unload still delegates the ordinary platform cleanup.

The packager loads only the dependency-free availability module and refuses
before creating an output directory or replacing an existing artifact. The named
test fixture helper stages isolated imports and unchanged SDK bytes; it does not
remove the installed runtime gate. Lower-level prototype tests deliberately
construct entities and ports directly, which does not enable HA setup or provide
a production bypass.

The real HA tests cover refusal before mocked SDK, storage and forwarding work;
retained-state preservation; all initial/reconfiguration input variants; unload;
subprocess packaging refusal for absent and existing outputs; and bundled SDK
byte equality. I read the coordinator's retained check log at
`/private/tmp/ha-publication-gate-check.log`: Ruff lint and format checks pass,
and all 25 tests pass in 0.767 seconds. I did not rerun Gradle or database tests.
The README and shared integration guide clearly distinguish unavailable Assist
intake from the supported outbound adapter.

This approval does not satisfy prompt fidelity, approve a system-context API,
close any prompt-dependent graph node, or establish a live HA/voice integration.
It authorizes inclusion of the fail-closed correction in the requested PR while
all existing prompt, release and live gates remain unsatisfied.
