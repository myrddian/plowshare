---
name: agile-board
description: Read and maintain tickets and overlapping epic groups in Excalibur through its CLI or granted Information tools.
---

Use the configured Excalibur Application. Its tickets and epics are typed
Information documents; assignment is planning metadata and grants no authority.
The four statuses are backlog, ready, in-progress and done. Reopening is allowed.

For an external coding agent, use `excalibur --help` and its subcommand
help. Obtain the private configuration path from the user or task environment;
never guess a server or request their bearer in chat. Run CLI mutations only
within the user's authorized task and configured identity's rights.

CLI agent mode is the default and requires an explicit scoped service token.
Do not add `--is-user` or reuse a person's saved login to gain missing authority.
That flag is for an explicitly authorized user session; it uses the public Python SDK
credential store and retains recovery under the selected account across renewal.
`--account` and `--connection` select that user identity and require `--is-user`.

List with `tickets list` or `epics list`; inspect a selected resource with
`ticket show <UUID>` and optionally `--body`. Outputs are JSON. Use full UUIDs
in commands. Several `--epic` filters intersect, and a ticket can belong to
several epics without duplication.

Before creating a ticket, turn the request into a short description and observable
acceptance criteria. Retain these in a local Markdown file and use
`ticket create --title ... --body-file ... --request-id <fresh UUID>`. Do not
represent your proposed classification or completion as a human decision.

For updates, read first and supply the observed `--expected-revision` with a
fresh retained request UUID. `ticket update --fields-file` takes all six ticket
metadata fields. `ticket move` changes only status. A conflict requires a fresh
read and review of the intervening changes, not an automatic overwrite.

On uncertain delivery, stop mutations for that intent. Inspect `request show`
and use `request recover` only when recovery is authorized. Never repeat a create
under a new request UUID. A retained admission receipt is not a finished
investigation or evidence that extraction has completed.

Plowshare-native agents use their granted `information_read`/`information_write`
operations with installed type identities and exact revision fencing. Schema
management and owner-authored tags remain human controls in those tools. Do not
route through a CLI or shared service token to evade that restriction. Propose
epic changes for a person when that authority is unavailable.

AI can draft, compare and investigate. Keep a ticket's exact input revision,
findings and limitations explicit, and link retained evidence/run identities
when available. Do not mark Done merely because an agent run ended.
