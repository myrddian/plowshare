# Server administration

A Plowshare administrator manages accounts and projects on the server. Project
roles determine what a regular account can do in each project. The administrator role
controls server management; granting that role does not automatically grant access
to another account's Personal space. Server administrators have Manager access to
ordinary server projects; private client projects keep their client ownership boundary.

## First administrator

The first server start prints a temporary `admin` login with a random password.
Run `plowshare-cli setup --url http://your-server:8091`, or open the desktop connection
dialog and expand **Set up the first administrator**. Choose your permanent account
name and password. Setup consumes the temporary account and provisions your Personal
space. See [first server setup](../README.md#first-server-setup).

After setup, sign in with `plowshare-cli login --url http://your-server:8091`.
Administrative commands use this saved account's authenticated WebSocket.

## Accounts and roles

List accounts:

```sh
plowshare-cli admin accounts
```

Create a regular user account, or explicitly give a new account administrator privileges:

```sh
plowshare-cli admin account create '{"handle":"sam"}'
plowshare-cli admin account create '{"handle":"second-admin","serverAdmin":true}'
```

Handles contain 1–64 letters, digits, dots, underscores or hyphens and start with a
letter or digit. Handles are unique and case sensitive. Creation provisions the
account's own Personal space, identified as `Personal:<account-name>` for routing.

The response includes a generated `temporaryPassword`. Give it to the account owner.
They sign in using the normal login command, which walks them through the required
password change. A temporary login can change its password but cannot use projects,
admin commands or either WebSocket until that change is complete. The server stores
only a password hash. The response contains the password once; it cannot be read back.

Change a role or disable and re-enable an account:

```sh
plowshare-cli admin account update '{"handle":"sam","serverAdmin":true}'
plowshare-cli admin account update '{"handle":"sam","serverAdmin":false}'
plowshare-cli admin account update '{"handle":"sam","enabled":false}'
plowshare-cli admin account update '{"handle":"sam","enabled":true}'
```

Disabling preserves the account, its Personal space, project memberships, conversations
and other retained work. It blocks login and signs out existing clients. Re-enabling
allows a fresh login; it does not revive the old credentials. Changing an administrator
role also signs that account out, so each client reconnects under its current role.
The server checks the persisted role on every administrative request.

At least one enabled permanent administrator must remain. Before disabling or demoting
the last administrator who has completed password setup, have another administrator
finish their password setup. Concurrent requests cannot bypass these safeguards.

## Password recovery

Reset another account's password:

```sh
plowshare-cli admin account reset '{"handle":"sam"}'
```

This returns a new temporary password, requires a password change at the next login,
and revokes all of the account's sessions. Disabled accounts remain disabled. Enable
the account separately if it should be able to sign in.

Use the normal authenticated password change flow for your own account. Administrators
cannot use this reset command on themselves, because revoking their socket would prevent
reliable delivery of the new credential.

If a create or reset response is lost, inspect `admin accounts` and `admin audit` before
retrying. If the account exists but its temporary password was lost, reset it explicitly
to obtain a new one. Clients do not automatically replay these mutations.

## Service accounts and scoped tokens

A **service account is an identity inside Plowshare** for an integration, SDK client,
or deployment pipeline. It is independent of the operating system account running
that integration and independent of credentials for Home Assistant or another external
service. It has no password login, administrator role or Personal space. Human server
administrators create and manage these identities.

Create the identity and grant its server project access:

```sh
plowshare-cli admin service account create '{"handle":"ha-integration"}'
plowshare-cli project member-add '{"project":"automation","handle":"ha-integration","role":"CONTRIBUTOR"}'
plowshare-cli admin service accounts
```

Issue a named credential with a separate ceiling for each project:

```sh
plowshare-cli admin service token create '{"handle":"ha-integration","name":"production","scopes":[{"project":"automation","role":"CONTRIBUTOR"}],"expiresInDays":30}'
```

The response contains `token` metadata and a one-time `credential` beginning with
`pss_`. Save the credential in your integration's secret store. The server persists
its digest; subsequent listings reveal metadata and scopes, never the credential.
Each token needs 1–100 distinct ordinary server projects. Personal spaces and private
client projects cannot be token scopes. Expiry defaults to 30 days and can be set from
1 to 365 days; credentials do not refresh themselves.

Effective access is the lower of the service account's **current project grant** and
the token's **project ceiling**. A Viewer token cannot run agents even if its account
is a Manager. A Contributor token loses write access immediately when its account is
downgraded to Viewer. Removing the account's project membership removes that token's
access. Adding a new project to the account does not add it to existing tokens.
Existing workspace `writePaths` restrictions still apply.

These limits follow work into queued jobs, information tasks and internal message
routing. Supply an explicit server project or a resource ID belonging to an allowed
project. Service tokens cannot fall back to Personal or global work, read the shared
information catalogue, administer the server, or root/sync a local client checkout.
For information requests, select the project without shared catalogue inclusion:

```json
{"scope":{"kind":"project","project":"automation","includeShared":false}}
```

Each token has a durable execution identity, shown as `principal` in its metadata:
`@service/<token UUID>`. Work, ownership and retained route conversations use this
identity. Different tokens have distinct execution identities; reuse or rotate the
same token when the integration needs to retain its ownership and conversation
continuity. Rotation keeps its ID, scopes and principal while replacing the secret.
Token scopes are fixed: issue a different token to change its scope ceiling.

Inspect, rotate and revoke tokens using the returned token UUID:

```sh
plowshare-cli admin service tokens '{"handle":"ha-integration"}'
plowshare-cli admin service token rotate '{"handle":"ha-integration","id":"<token UUID>","expiresInDays":30}'
plowshare-cli admin service token revoke '{"handle":"ha-integration","id":"<token UUID>"}'
plowshare-cli admin service account update '{"handle":"ha-integration","enabled":false}'
```

Rotation immediately invalidates the previous credential and its outstanding tickets
and socket sessions. Revocation and expiry also remove the execution identity's
project access, including later background work permission checks. Disabling a service
account revokes all its tokens while retaining its history. Re-enabling does not revive
those credentials; issue or explicitly rotate a token after checking its project
grants. An expired or revoked token can be rotated if the account is enabled and its
current grants still allow all the original scopes.

If issuance or rotation loses its response, inspect token metadata first. Credentials
cannot be recovered; rotate the existing token explicitly to obtain a replacement.
Clients never automatically replay a token mutation. Service account and token changes
appear in `admin audit`, with no secrets or digests. Token audit targets identify both
the service account and token UUID.

Use the credential as `Authorization: Bearer <credential>` with an SDK. In the CLI,
supply it as `PLOWSHARE_TOKEN` from your process environment or secret injection, then
run ordinary project commands:

```sh
plowshare-cli --url http://your-server:8091 --project automation agent list
```

With `PLOWSHARE_TOKEN` set, the CLI obtains a short-lived socket ticket using that bearer.
It performs no password login, refresh or credential-file write and does not fall back
to a human login if the token fails. The environment token takes precedence over saved
logins and password environment variables. Clear it before running human administrator
commands. An explicit token that does not begin with `pss_` is refused by this CLI flow.

In the TUI, use the same administrative commands with `/admin service ...`. In the
desktop, open **Server administration → Service accounts**. Create/select an account,
choose a project and role, and select **Grant access**. Add each desired token scope,
choose a token name and expiry, and issue the token. The token list offers rotation and
revocation. The credential field is cleared on dismissal, account changes, refresh or
dialog closure, and credentials are excluded from state snapshots and saved preferences.

## Sessions

Inspect an account's active login sessions and sign it out everywhere:

```sh
plowshare-cli admin sessions '{"handle":"sam"}'
plowshare-cli admin session revoke '{"handle":"sam"}'
```

The list contains opaque login session IDs, creation and expiry times, and whether a
session is restricted to changing its password. It contains no access tokens, refresh
tokens or token digests. Revocation applies to **all login sessions of the named account**;
individual session revocation is not offered by this command.

Revocation invalidates durable access and refresh credentials, outstanding upgrade
tickets, and event and file sockets. Credentials remain revoked after server restart.
The server handling the change closes its sockets after the database commits. Other
server processes sharing the database check socket revocation every second and before
handling an incoming frame. Reconnect and sign in again to establish fresh sessions.
Revocation does not cancel retained server jobs or delete their history.

Explicit server operator/bootstrap tokens are separate deployment credentials, not
account login sessions. They are not included in this list; rotate those credentials
through the server's deployment configuration.

## Audit history

Successful administrative changes are recorded durably with their actor, target,
time and resulting account status. Each mutation and its audit entry commit together.
Passwords, tokens and token digests are excluded.

```sh
plowshare-cli admin audit '{"limit":25}'
plowshare-cli admin audit '{"handle":"sam","limit":25}'
```

Entries are newest first. To read older entries, pass the response's nonzero `before`
cursor unchanged:

```sh
plowshare-cli admin audit '{"handle":"sam","limit":25,"before":42}'
```

Use a limit from 1 to 100. A `before` value of zero means the newest page. A response
cursor of zero means there are no further pages to request. A full final page may
return a cursor whose next page is empty. The audit records `account.create`,
`account.update`, `account.password.reset`, `session.revoke`,
`service.account.create`, `service.account.update`, `service.token.create`,
`service.token.rotate` and `service.token.revoke`. It is an administrative
change history, rather than a login-attempt or agent activity log.

## TUI and desktop

In the TUI, prefix the same commands with `/`, for example:

```text
/admin accounts
/admin account update {"handle":"sam","enabled":false}
/admin sessions {"handle":"sam"}
/admin session revoke {"handle":"sam"}
/admin audit {"limit":25}
```

These commands manage the server directly and do not open a conversation or invoke
an agent. Account creation and password reset reveal their temporary password in the
command result; treat that terminal output as a credential.

In the desktop GUI, connect as an administrator and choose **Server administration**
in the sidebar. Select an account to change its role or enabled status, reset its
password, inspect sessions, or sign it out. The dialog also provides account creation,
audit history, and older audit pages. Temporary passwords are displayed in a transient
field and cleared when dismissed, when the account selection changes, or when the
dialog closes. They are excluded from desktop state snapshots and saved connection
preferences. Nonadministrators cannot open this dialog or call its backing operations.

## Server projects

Administrators can create server projects with `project create`, including MANAGED
projects and externally maintained DISJOINT workspaces with limited writable areas.
Project Managers can manage membership without being server administrators.
See [projects](projects.md) for workspace ownership and discovery and
[internal messaging](internal-messaging.md) for project routes and Personal addresses.

## Project access and Personal scopes

A regular account has no access to an ordinary project until granted a project role.
Each account has one private Personal space, addressed as `Personal:<ACCOUNT_NAME>`.
Its owner can read, run work and manage their own agents there. Other accounts,
including server administrators, cannot enter that Personal space or change its membership.
Personal routing defaults still allow incoming and outgoing routes; sending work across
projects also requires Contributor access to the destination project and the route policy.

| Project role | Permissions |
| --- | --- |
| Viewer | Read project conversations, memories, information and available agents; inspect project access. |
| Contributor | Viewer permissions plus start/resume/cancel work, create conversations, post messages and update project information. |
| Manager | Contributor permissions plus define project agents and manage project membership and roles. |

Server workspace provisioning, project moves, writable-area configuration, account
administration, server-wide schedule creation/event emission and resource maintenance
require a server administrator. Listings of schedules, triggers and firings remain
account-owned; project data in unscoped work listings is filtered by current access.
A retained global schedule stops emitting while its owner is disabled or no longer an
administrator. Regular users may pause or remove their own retained schedules; resuming
a global schedule requires administrator authority.
Per-document ownership/publication and private client-session checks continue to apply.
Roles never widen a workspace's exclusions, read-only setting or permitted `writePaths`.
Existing non-admin memberships migrate to Contributor. Project creators and each
Personal-space owner receive Manager; server admins retain their ordinary-project authority.

Inspect the current account's effective access and explicit grants:

```sh
plowshare-cli project access '{"project":"home-assistant"}'
```

Add a known account (omitting `role` defaults to Contributor), change its role, or
remove it:

```sh
plowshare-cli project member-add '{"project":"home-assistant","handle":"sam","role":"VIEWER"}'
plowshare-cli project member-role '{"project":"home-assistant","handle":"sam","role":"CONTRIBUTOR"}'
plowshare-cli project member-remove '{"project":"home-assistant","handle":"sam"}'
```

Adding an existing member is refused; use `member-role` to change their grant.
A project Manager cannot remove or demote the last enabled project Manager. A server
administrator can recover a project and replace its grants. Grant changes and removals
are saved transactionally with project access history; Managers see the latest 25 entries.
Removing or downgrading a grant applies to subsequent requests, job inspection and live
project delivery. Existing resource IDs do not grant access or override their stored project.
Runs check Contributor access again before obtaining project file providers. Git sync
fetches require Viewer access and pushes require Contributor access, rechecked before
applying a received pack.

In the TUI, prefix these same commands with `/`, for example `/project access`.
When a current project is selected, the command can omit `project` from its JSON.
In the desktop, click **Access** beside a project. All members can inspect their role;
Managers can add accounts, save roles, remove grants and view access history.
Viewer access disables starting conversations and sending messages in that project.
Use the server administration dialog to create regular accounts first; creating an
account alone does not grant access to other projects.

The legacy HTTP surface uses the same project-role admission policy as WebSocket
commands. CLI, TUI and desktop role management uses WebSockets. Remote editing of
provider credentials, URLs and pool settings is not part of this capability; those
remain server configuration until a dedicated configuration workflow is added.

## Model pricing and usage statistics

Server administrators can view and edit server prices over the authenticated WebSocket.
In the desktop GUI, open **Server administration → Pricing**. The editor lists each
served billing route and exact wire model, its pools, configured rate cards and any
active operator override. Rates use decimal text in currency units per million tokens.
Input/output and optional cache read/write rates, input-size tiers and a fee per
upstream attempt are supported. Modes are `TOKEN`, `INCLUDED`, `ZERO_RATE` and
`UNPRICED`; missing prices remain unknown.

CLI example (illustrative operator rates, not a provider quote):

```sh
plowshare-cli admin pricing list --json
plowshare-cli admin pricing set '{"billingRoute":"hosted","model":"deployment-name","expectedVersion":"boot:","mode":"TOKEN","currency":"USD","rates":{"input":"0.40","output":"1.60","cacheRead":"0.10"},"source":"operator"}' --json
```

Copy `billingRoute`, `model` and `version` from the list response; pass that exact
`version` as `expectedVersion`. `boot:` only applies when no startup prices or
operator override exist. A stale editor is refused: refresh, review the current
rates and explicitly resubmit. Price changes commit with an audit entry and persist
across restarts. Overrides take precedence over startup configuration for that
route/model. Changes apply to future admissions. Queued requests, retries and
recorded costs keep the immutable rate card selected when their call was admitted.
An `UNPRICED` override explicitly disables price estimation for future calls.

In the TUI, use `/admin pricing list` and `/admin pricing set {…}` with the same
JSON payload. Pricing administration requires a current server administrator;
project Manager authority alone does not grant it. Regular-user accounts created
with `admin account create` default to no server-admin role. Their Viewer,
Contributor and Manager roles continue to control project resources independently.

Usage statistics already have CLI and TUI queries:

```sh
plowshare-cli usage pools --json
plowshare-cli usage models --json
plowshare-cli usage project '{"project":"automation"}' --json
plowshare-cli usage conversation '{"conversation":"conversation-id"}' --json
```

Use `/usage pools`, `/usage models` or `/usage project automation` in the TUI.
The GUI's **Usage** panel shows recorded tokens, cost estimates, incomplete usage
and pricing coverage; the pricing editor has an **Open usage statistics** button.
`admin.status` only reports the signed-in account’s own role; it does not grant
admin authority. Every other `admin.*` operation passes the central server-admin
gate and the owning service’s authorization check on the server. Account or role
revocation is checked on subsequent actions. Fleet-wide `usage pools` requires server-admin authority and rechecks it on updates.
Regular users can query their permitted project/conversation statistics; foreign
resources remain refused. Pricing is an operator estimate, not a provider invoice.
Local reference comparisons are separate from server prices and booked costs.
