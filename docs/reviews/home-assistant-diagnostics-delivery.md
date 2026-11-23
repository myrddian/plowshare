# Home Assistant diagnostics delivery

This amendment adds two read-only provider tools and the native HA diagnostics
download. The [contract](home-assistant-diagnostics-contract.md) defines the
disclosure and lifecycle boundaries. The coordinator ledger owns completion and
separate merged, deployed and live-validated milestones.

The provider can explain a confirmed unavailable entity or an unexposed requested
attribute without inferring a physical cause. Its health output describes local
HA connection and catalogue observations plus historical successful public SDK
communication; it does not claim end-to-end reachability from those observations.
An unreachable provider cannot answer its own diagnostic tool. The independent
HA download reports the Assist component's local runtime and communication facts
without probing the provider or starting new model work.

Control and selected entity/attribute scope are unchanged. Provider tools need
their explicit grants. The download constructs its integration-owned data from
an allowlist and excludes payloads, private identities and credentials. HA adds a
native metadata envelope and download filename outside that data section.

The standalone provider and Assist archive remain separate installation units.
New declarations change the provider journal fingerprint; the documented update
preserves and reconciles the earlier owner rather than reusing a mismatched
journal or replaying uncertain work. No core, SDK, shared-runtime or database
change is included. No diagnostics rollout, HA/server restart, live model call,
entity-policy expansion or device mutation has been performed for this amendment.

The [final evidence](home-assistant-diagnostics-evidence.json) binds the source
inputs, check logs and complete package contents. Owning source approval is recorded
in the [provider review](home-assistant-diagnostics-provider-review.md) and
[download review](home-assistant-diagnostics-download-review.md). DIAG-03 requires
separate independent approval of the integrated evidence before ledger acceptance.

| Verification | Observed result |
| --- | --- |
| HA Python check on HA 2026.9.4/Python 3.14 | Ruff check/format and all 150 tests passed |
| Provider Java suite | All 200 tests passed; includes public SDK and native fake HA diagnostic round trip |
| `./gradlew format formatCheck check :plowshare-integration-home-assistant:installDist --max-workers=2` | Passed in 4 minutes 44 seconds; no full-db |
| Final standalone distribution refresh | Passed; final provider guide matches reviewed source |
| Installed outbound package | All eight offline checks passed; 56 artifact files |
| Assist archive | Version 0.1.2; all 53 entries accounted for; complete contents manifest matched; all 19 bundled public SDK files match unchanged source |

The initial provider suite caught an old four-tool count assertion; it was updated
to six and the final suite passed. The first installed-package inspection caught
an earlier guide copy with a non-shipped local documentation link. The source link
was corrected, the distribution refreshed, and the final eight checks passed.
These earlier failures do not describe the final tested candidate.

No diagnostics have been installed or checked against a live HA instance. The
native download hook and installed-consumer fixtures establish offline behavior;
they do not establish live download discovery or current HA/provider health.
