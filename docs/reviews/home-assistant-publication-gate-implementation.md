# Home Assistant Assist publication gate

Implementer: `/root/ha_publication_gate_implementer`.
Date: 17 November 2026. Status: implementation ready for independent review.

The retained Assist prototype previously exposed an active setup path and an
installation packager despite the unsatisfied native system-prompt/context
fidelity requirement. This publication correction enforces the user's decision
to keep prompt-dependent features gated; it does not implement or authorize a
new prompt capability.

A dependency-free availability module records the missing capability as false,
with no configuration, environment or CLI override. HA entry setup converts that
refusal into a non-retrying `ConfigEntryError` before constructing the correlation
store, parsing configuration, creating an SDK agent or forwarding platforms.
Initial and reconfiguration flows abort with a translated explanation before
validation, entry lookup or SDK work. Existing platform unload remains unchanged.
No correlation data is read, rewritten, deleted or migrated by gated setup.

The ordinary packaging CLI consults the same availability module and refuses
before creating an output directory or archive, including preserving an existing
output file. The explicitly named `stage_test_fixture` helper only stages
isolated temporary imports and unchanged public SDK bytes. Manually copying
those fixtures still encounters the HA runtime gate. No installable artifact was
produced or deployed. Current README installation instructions were replaced with
the gate, retained-state guidance and clearly dormant prototype contracts.

Tests exercise the actual installed HA entry-point and configuration APIs with
SDK/storage/platform boundaries mocked. They assert refusal before side effects,
byte preservation of retained correlation, configuration/reconfiguration abort,
existing unload cleanup, subprocess packaging refusal without output creation or
replacement, and exact public SDK fixture bytes. Existing lower-level conversation,
SDK transport, cancellation, uncertain-delivery and Relay tests remain in place.
These isolated checks do not establish a working Assist feature or prompt fidelity.
The coordinator also amended the shared integrations guide to describe the
prototype as unavailable. No server, protocol, SDK, ledger, plan or historical
frozen artifact was modified by this correction. The coordinator owns final check execution and independent
review of the exact source manifest below.

## Verification

The coordinator ran the pinned Ruff formatter on the six changed Python modules
(one file reformatted, five unchanged), then ran:

```sh
/private/tmp/plowshare-ha-venv/bin/python integrations/home-assistant/assist/scripts/check.py
```

Result: Ruff lint and format checks passed; all **25 isolated tests passed** in
0.767 seconds against the installed HA 2026.9.4 / Python 3.14 environment. The
coordinator retained the command log at `/private/tmp/ha-publication-gate-check.log`.
No Gradle, PostgreSQL, network installation, live HA, model or device operation was
needed for this correction. Independent review remains required before acceptance.

## Exact source manifest

The hashes below bind the final checked bytes. The implementer authored the
Assist source/tests and current README changes; the coordinator authored the
narrow shared integrations-guide amendment. Historical source approvals remain
separate; this manifest covers only the publication correction.


```json
{
  "integrations/home-assistant/assist/custom_components/plowshare/availability.py": "9ff0dd3d8b44d56e82382381b84713ed6ad6afa3b4dba5d2e0c3105f353dd398",
  "integrations/home-assistant/assist/custom_components/plowshare/__init__.py": "239eeb59e80b945104496ef0cc629565ba4ad74f72077b5e58e8ca17a6255bf7",
  "integrations/home-assistant/assist/custom_components/plowshare/config_flow.py": "f3b46ad300b8b28a323b52c8df139c3e83032dd3e4f5266a29e9e174c2e041a9",
  "integrations/home-assistant/assist/custom_components/plowshare/strings.json": "7a62c843f41a69c6f5ccca1b4e49638bdc3aa381f386d394ff5bb2ed85d8f658",
  "integrations/home-assistant/assist/custom_components/plowshare/translations/en.json": "7a62c843f41a69c6f5ccca1b4e49638bdc3aa381f386d394ff5bb2ed85d8f658",
  "integrations/home-assistant/assist/scripts/package.py": "9e44e4128ba7188fa568bfa61cf04ac8573dd7f9af4147342f7c65d5476db355",
  "integrations/home-assistant/assist/tests/support.py": "f8a7292ed424dab20b80df3b873df71af5f7e7115faf82da0c8d43ccb5cb7152",
  "integrations/home-assistant/assist/tests/test_home_assistant.py": "470b7abb999b06511f70fb1af845b4513dcadf42665cd41ab2b293743e46afff",
  "integrations/home-assistant/assist/README.md": "1a7dfeff4c56ea662d510353b0987c7ad8e0bbddf471a27c4dafded48e92d295",
  "integrations/home-assistant/README.md": "ca3a64d2b1ce958328a61b4d39f5312dd42617d8e73bcb1fd9126bb6ee4c9778",
  ".agents/guides/integrations.md": "fea9e9a9dd2397892f0d60f4af1abbb013501b163d67a3a92cbe1d556b59720d"
}
```
