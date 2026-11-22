# OUT-PROFILE-01 v2 independent review

Verdict: **APPROVE**.
Reviewer: `/root/ha_outbound_profiles_reviewer`.
Implementer: `/root/ha_outbound_profiles_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Node: `OUT-PROFILE-01`.

Candidate: `docs/reviews/home-assistant-out-profile-01-v2-candidate.json`.
Candidate SHA-256:
`6698b07861e88453ef17242f62bd56313e322a2911ff1d91ef686d9d961e3c10`.
New implementation report SHA-256:
`51f5a8501b1eadb5ae2b8db469bdf4d5331de03ad87e48e8f720cedd730b3724`.
Artifact amendment evidence SHA-256:
`c7c2ec0ea5b6f12f2222e07b567632f5c1da190ffd21c6528125069a714ca706`.

## Exact amendment and independent checks

The independent installed-artifact validation found three source-relative README
links that did not work in the distribution. The original implementer changed
only their targets to canonical repository URLs for the Assist gate, provider
scopes and observation/feedback limitation. The local packaged-host link remains
`../../PROVIDER-HOST.md`. I did not author those changes or modify source, the
ledger, earlier reports or evidence. Only this new review is authored here; I ran
no Gradle, database, network, model or device operation.

I verified all twelve candidate file hashes, its frozen ledger snapshot/diff and
five dependency manifest digests. Reconstructing the original README from its v1
frozen diff reproduced its approved hash, and exactly the three intended URL
replacements reproduced the complete v2 README bytes. All nine other profile
source/example/test hashes are identical to v1. The earlier v1 review bytes also
retain their recorded hash. The frozen roles remain separate actual agents, and
all eleven original gated nodes and all four unsatisfied gates remain unchanged.

The three canonical URL repository paths exist in the current owning source; the
feedback anchor names the existing retention/causal-feedback section. This is
repository-path verification, not a claim that an HTTP link check was performed.
The README's authority, credential, action uncertainty and prompt-gate wording
has not otherwise changed. There are no blocking findings in this amendment.

## Installed artifact evidence

I verified the original package evidence digest
`cae02526993143cb906ddfef5cc554b1324df951ea965e08a714a4b11f2e8d60`
and compared its complete forty-four-file inventory with the refreshed install
root. There are no added or missing files. Only examples/outbound/README.md has
changed; every other recorded file's hash, byte count and permission mode remains
identical. The installed README matches source SHA-256
`117368f2fc745bcc821f045bf78229d5087d2c84ba091b2b9f5e4b9d7061bc8e`.

All four local Markdown links across installed PROVIDER-HOST.md and the outbound
README resolve to actual files inside that distribution, including the
packaged-host link left local. This resolves the local host-document handoff
obligation from my v1 review and the three additional broken artifact links
identified by OUT-VERIFY-01.

The coordinator's refreshed installDist log has SHA-256
`9041664aea27fc97a2435e6646984458f0d00d84f81fde663fce2efeb4e7a152`.
I verified those exact log bytes and BUILD SUCCESSFUL in 508 ms (eleven tasks;
one executed and ten up-to-date). This is inspection of coordinator evidence,
not a claim that this reviewer reran the build.

## Acceptance limits

This is a documentation/artifact amendment only. Configuration, agent grants,
Java tests and production binaries are unchanged, so the earlier scoped profile
approval and compiled twelve-profile-case evidence remain applicable to those
unchanged bytes. Repeating broad runtime tests is unnecessary for these three
URL substitutions. OUT-VERIFY-01 still owns fresh integrated public-SDK/installed
consumer fixtures and its independent approval; this review does not close that
node or establish live grant issuance, model availability, device effects,
deployment or any prompt-dependent feature.

V2 supersedes v1's README bytes and adds the installed-link evidence. Historical
reports/manifests remain preserved, and all original prompt/operational gates
remain unsatisfied.
