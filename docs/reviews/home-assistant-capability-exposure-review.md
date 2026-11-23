# CAP-EXPOSURE independent review

Date: 24 November 2026. Implementer: `/root`.
Reviewer: `/root/ha_capability_reviewer`. Verdict: **APPROVE** for source
implementation and owning runtime verification. No deployment or live selection
change is established by this review.

The actual native command, component setup, manifest dependency/version and
packaged documentation were reviewed against installed HA 2026.9.4 source. The
command uses HA's canonical effective-exposure predicate, including default rules
and registered/legacy entities. It does not infer exposure from the incomplete
stock preference list. The documented HA-owned default-preference materialization
is retained rather than duplicated as adapter policy.

Authorization and input scope are explicit: the command requires an authenticated
administrator, accepts only native request ID/type and rejects extra assistant,
entity or paging arguments. Registration is once at component setup and does not
open a Plowshare gateway or correlation owner. Missing exposure initialization,
excessive inventory, selected count/bytes and observation expiry return sanitized
errors without a partial selected list. Inventory size is checked before copying
it, and the deadline covers scanning and final DTO validation. No device service,
model/job submission, control grant or Plowshare SDK/core/shared-runtime change
occurs. The separate provider still needs explicit follow-mode configuration.

The implementer's full Python check passed Ruff check/format and all 159 tests
on HA 2026.9.4/Python 3.14. Log SHA-256:
`f1148fd610fa08fd6fd1715df824b3fbdf3481fa60e79d8897fc05fb08fd1b5c`.
The reviewer independently ran all nine exposure tests with the actual installed
HA runtime; all passed. Reviewer log SHA-256:
`5b6a31576d504a00a71f5bffd70730a06ccdfec49c93c30c94dfd1c1c578f764`.
Coverage includes canonical defaults, explicit allow/deny and revocation, legacy
entities, private identity/value exclusion, non-admin callers, malformed scope,
single registration, unavailable initialization, count/byte bounds and expiry
before work and after validation. The final-validation deadline refinement is
resolved. No open actionable finding remains.

Approved source SHA-256 values (relative to `integrations/home-assistant/assist/`):

| Path | SHA-256 |
| --- | --- |
| `custom_components/plowshare/__init__.py` | `e3964802dac7f6879ae47d2076b3a0c5c4dab690312e9071c625f1f5636421e8` |
| `custom_components/plowshare/exposure.py` | `74fa4715bbf90e33989f8ab776782def6468e368e7032e2de80300f2573008e4` |
| `custom_components/plowshare/const.py` | `e3291d777bd3306e4a1c66438b4ca163193f0f300a0991f8858b3b3d46d9de78` |
| `custom_components/plowshare/manifest.json` | `9781310bfc414362839611daf988744543f68827545264d89d6265ca95c9a067` |
| `tests/test_exposure.py` | `97c67c3ff26f0688f9c73f6d048ed5fb5f8fd4b242a97e18b7b07a8df14c9e44` |
| `README.md` | `424f66cf1040389458f366f6d8ab55fc08543fedd3f16e1f910dcedc8aca3b79` |
| `PACKAGE-PROFILES.md` | `7a49a0d5e10f129a0756d1e379e33ee826313d52eee37555200c04a9ff58e250` |

Package composition and the optional native/public-SDK provider path remain
CAP-03 evidence. These tests do not establish live command discovery or current
HA/provider health.
