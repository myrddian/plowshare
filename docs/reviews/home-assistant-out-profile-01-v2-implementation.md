# OUT-PROFILE-01 v2: installed documentation links

Implementer: `/root/ha_outbound_profiles_implementer`.
Status: documentation correction ready for independent review. Installed-artifact
refresh and link verification remain coordinator/OUT-VERIFY-01 work.

The independent installed-artifact review found three README references that
resolved outside the shipped distribution or pointed to unshipped documents.
Only those three Markdown link targets were changed to canonical repository URLs:
the Assist gate explanation, provider scopes, and the observation/feedback
composition limit. The feedback anchor remains specific to its owning section.
The packaged-host link remains local at `../../PROVIDER-HOST.md`, because that
document is shipped beside the installed examples.

No Java, configuration, agent definition, grant, credential reference, runtime or
gate byte changed. The nine remaining source/example hashes still match the
previous approved implementation report. That report and all previous frozen
review evidence were preserved unchanged. Prompt-dependent features remain gated.

## Verification and exact source

A static replacement check confirmed exactly one occurrence of each old target,
only the three intended replacements, preservation of the local packaged-host
link, and unchanged hashes for the nine other profile source/example files.
The previously compiled 12 profile cases passed in the coordinator's 8-second
focused invocation; their source is unchanged. No Gradle, database, network,
model/device call or Git mutation was run for this prose-only correction.
A refreshed installed-artifact check and independent approval are still required
before this reopened node is accepted.

```json
{
  "integrations/home-assistant/examples/outbound/README.md": "117368f2fc745bcc821f045bf78229d5087d2c84ba091b2b9f5e4b9d7061bc8e"
}
```
