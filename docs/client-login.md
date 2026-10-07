# Shared local login

Build the server and clients, then sign in once to your existing admin account:

```sh
bin/plowshare-cli --server https://plowshare.example.com login
bin/plowshare-cli --server https://plowshare.example.com memory index
bin/plowshare-talk --url https://plowshare.example.com
bin/plowshare-mcp --url https://plowshare.example.com
```

Only `login` asks for a handle and password. Password entry is hidden. If the
server requires an initial password change, interactive login asks for a new
password twice, changes it server-side, and signs in again. Ordinary operations
and MCP never prompt. Login allows five minutes by default; `--timeout-ms`
overrides that deadline. Other commands retain their existing deadlines.

Desktop, CLI and TUI share named connections. Save a connection before logging in:

```sh
bin/plowshare-cli connection add "Home" https://plowshare.example.com alice
bin/plowshare-cli --connection "Home" login
bin/plowshare-cli --connection "Home" memory index
bin/plowshare-talk --connection "Home"
bin/plowshare-talk connection list
bin/plowshare-cli connection rename "Home" "Personal server"
bin/plowshare-cli connection select "Personal server"
```

The desktop connection button opens a dropdown with saved connections and the
current selection. Use **Add / manage connection** to enter a name, server and
handle, rename the selected connection, or remove its saved entry. Arrow keys,
Home/End, Tab and Enter navigate the dropdown; Escape closes it. Switching closes
local sockets, event subscriptions, file channels, commands and union sync before
opening the selected account. Admitted jobs remain on their original server;
returning restores receipts through server APIs without replaying submissions.

Arguments override the corresponding environment value. `--connection NAME`
overrides `PLOWSHARE_CONNECTION`; `--server`/`--url` overrides `PLOWSHARE_URL`,
and `--account` overrides `PLOWSHARE_ACCOUNT`. With neither an explicit name nor
server/account, clients use the saved selection. An explicit origin/account
bypasses the saved default. A named selection must agree with all explicit
server/account values, including `PLOWSHARE_HANDLE`; conflicting or missing names
are errors. Multiple accounts at one origin require a name or explicit account.
There is no fallback to another connection when credentials expire or a server is
offline. Help, version and offline validation need no configured server.

Desktop sign-in remembers reconnect intent per connection. Restart reconnects the
selected account with its saved session. **Disconnect** or **Use demo** disables
startup reconnect for that connection while retaining its session. Leave the
password blank to reconnect; an expired or interrupted session requires login.
The desktop clears password fields immediately and keeps tokens out of renderer
state. CLI/TUI selection changes apply to the next invocation; end the current TUI
before switching. Named user connections cannot be combined with service tokens.

Multiple client instances may connect to the same account and serve the same
project directory on the same machine. The first file presence remains the routing
choice; closing it leaves a surviving presence available for later routing.
Outstanding requests on the closed socket fail without being replayed, since a
write may already have reached disk. Account authentication, project membership,
tool grants and workspace fences still apply to every client.

A second directory claiming that same project remains a conflict, even on the
same machine. Separate configuration roots can produce separate Personal
directories, so clients sharing an account should use the same configuration root.
After a file attachment is refused, desktop navigation and polling retain the
error; use **Connect files** to retry explicitly after resolving the location or
access conflict. CLI failures expose `FILES_UNAVAILABLE` without submitting the
requested operation. Local union synchronization holds
`.plowshare/sync.lock` in the project directory while reconciling, so multiple
clients cannot interleave Git operations on one shadow repository. Stop clients
and inspect an interrupted sync before removing a stale lock; locks are never
stolen from another writer.

Local state now lives under `~/.plowshare`, with this versioned layout:

```text
.plowshare/
  config.json                         # names, immutable keys, selected key
  connections/<server-account-key>/
    credentials/<origin-digest>.json  # private rotating session
    personal/                        # checkout, .git and union metadata
    credential-migration.json        # prevents legacy-session resurrection after logout
    desktop-projects.json            # bookmarks for this identity
    desktop-jobs.json                # known and uncertain work receipts
    desktop-view.json                # drafts and view selection
```

The key hashes the canonical origin and account together; names never become
filesystem paths. Rename preserves the key and all files. `connection remove NAME`
removes selection metadata and disconnects it in the desktop, preserving local
content and sessions. Re-adding that same identity restores its key. Use **logout**
to revoke only the selected account's session before removing an entry. No command
implicitly removes another account or cancels its jobs.

`PLOWSHARE_CONFIG_DIR` supplies an absolute root for all local clients. The older
`PLOWSHARE_DESKTOP_CONFIG` supplies the shared root when the new override is absent;
when both are set, the new override wins and the old desktop directory is a migration source. XDG configuration directories are legacy migration sources, not a new
profile's fallback. POSIX directories are mode 0700 and session/config files mode
0600. OS keychain encryption is not implemented. Passwords are never saved;
`PLOWSHARE_HANDLE` plus `PLOWSHARE_PASSWORD` remain ephemeral automation overrides
unless you run `login` explicitly.

Legacy preferences and scoped bookmarks/receipts are imported without deleting
their sources. A legacy session moves under the credential locks only when its
origin and account match. In the default profile, a legacy `~/.plowshare/personal` checkout moves as a whole,
including Git history, only when `.plowshare/personal.json` proves the matching
server/account/project. Foreign ownership stays intact and gets a separate mount.
Missing/malformed ownership and duplicate legacy/scoped checkouts leave legacy
data untouched. Personal opens the selected connection's store automatically;
an existing scoped checkout takes precedence. No folder selection is required.
Restore proven original ownership before a future migration or recover old data
explicitly; never change its owner to the account you want to connect.
If Personal file access fails, **Retry** reconnects the same default store without
opening a folder picker. Legacy renderer preferences remain available as a
migration source; the selected identity's drafts are subsequently saved in its scope.

If the selected connection's Personal directory is structurally damaged or its
ownership metadata is missing, malformed or unreadable, desktop and TUI preserve
it at `connections/<key>/personal-connection-recovery-<UTC datetime>-<unique suffix>/personal`
and recreate `connections/<key>/personal`. The warning names the preserved path
and remains available after restart. Unsynced files and Git history stay in the
preserved store; the replacement uses normal authenticated synchronization.
Credentials, bookmarks, drafts and other connections are unaffected. Valid
foreign ownership is refused automatically. Desktop Project files offers
**Recreate Personal store** for deliberate recovery of a valid but unusable local
replica, preserving it before starting fresh. Active/interrupted sync locks require
explicit resolution; authentication, network and server errors never trigger an
automatic reset.
Recovery and synchronization share a lock outside the Personal directory. After
replacement, an older sync runtime refuses further work until file access is
reconnected, so it cannot carry cached Git state into the new store.

Every refresh holds a cross-process lock, reloads the latest tokens, and writes
the new pair atomically before requesting a WS ticket. A failed ticket or socket
upgrade therefore leaves a usable saved pair. A pending marker is written before
refresh: if its outcome is uncertain, the next client requires a fresh login
rather than spending a potentially retired refresh token again. No application
operation is replayed during authentication or reconnect.

The lock waits at most ten seconds. A process killed while holding it may leave
an account's `.json.lock` directory. Stop clients using that account before removing
that specific stale lock directory; do not remove a lock held by a live client.
If a refresh was interrupted, sign in again. Automatic stale-lock reclamation is
not implemented because it could race a client still rotating credentials.

```sh
bin/plowshare-cli --server https://plowshare.example.com logout
```

Logout renews and revokes the shared session on the server, then deletes the local
file. If server revocation fails, credentials remain available for retry. Logout
requires the server to be reachable; it does not cancel running jobs. Existing
connections retain the server's existing connection-lifetime behavior. A saved
session is shared across local clients, so revoking its chain prevents them from
renewing or opening new authenticated connections.

Migration `V81__durable_login_sessions.sql` persists account session chains and
access/refresh token digests in Postgres. Refresh and revocation serialize on the
chain row, so session state and reuse detection survive server restarts. Raw
tokens never enter the database. Default access lifetime is 15 minutes and refresh
lifetime is seven days; expired or revoked sessions require another login.
Bootstrap/operator tokens and single-use WS tickets retain their ephemeral behavior.

Authentication, refresh, ticket acquisition and logout use HTTP. Supported
application operations and file bytes continue over WS; PDF/image conversion
continues on the server. The existing Git-object HTTP exception is unchanged.

## Browser connection recovery

The console checks the current cookie session before opening its workspace.
An explicit authentication refusal offers sign-in. A failed connection, server
error or unexpected session response shows an unavailable state with **Retry
connection**; it does not establish that the account is signed out. Retry reads
session status without renewing a bootstrap token or submitting work.

Automatic cookie rotation requires Web Locks and writable browser storage. The
origin lock serializes participating tabs, and a non-secret uncertainty marker
prevents another tab or reload from repeating a rotation whose reply was lost.
A successful access probe under that lock reconciles the marker. Browsers without
Web Locks (including unsupported or insecure origins) keep existing sessions but
require explicit sign-in after expiry; they never rotate shared cookies through
a per-tab fallback.

A refused single-use bootstrap token cannot be renewed by reloading. Use an
existing account, or ask the operator for the current handoff from the configured
protected token file when setting up a new installation.

Document job reads pause when the Documents view or browser tab is hidden and
reconcile when it becomes visible or its connection reopens. An already admitted
ingest continues on the server. Leaving the view does not cancel or resubmit it.

## Local FileStores

GUI, CLI and TUI use the same host registry at `filestore.js` inside the shared
Plowshare user configuration directory (`PLOWSHARE_CONFIG_DIR`, otherwise the
existing user configuration location). These aliases describe directories on this
computer, independently of named server/account connections. Connection-owned
credentials, Personal replicas and recovery state keep their existing isolation.
Registering a FileStore does not connect to a server or authorize an agent to
access its files.

```sh
bin/plowshare-cli filestore status
bin/plowshare-cli filestore setup applications /absolute/path/to/applications
bin/plowshare-talk filestore status
bin/plowshare-cli filestore resolve applications mychatbot
```

`filestore setup` without arguments prompts on an interactive terminal; a blank
alias skips setup. Noninteractive callers must supply the alias and absolute
root. The TUI also offers setup before mounting its interactive surface when the
registry is absent. The desktop shows **Set up local FileStores**, with an alias,
a directory chooser and reload/error states. **Local FileStores** in the
connection dialog opens the existing definition and its resolved locations.

A registry is a trusted, host-owned JavaScript module, with only a default export:

```js
export default {
  version: 1,
  defaultStore: 'applications',
  fileStores: {
    applications: { root: '/absolute/path/to/applications' },
    data: { root: '/absolute/path/to/data' },
  },
};
```

Aliases start with a lowercase letter, followed by letters, digits, underscores
or hyphens (at most 64 characters). Roots must be absolute directories; symlink
roots, traversal, invalid field types and unknown configuration fields are
refused. Up to 100 stores and 64 KiB of JavaScript are supported. Evaluation has a two-second limit and runs away from the UI event
loop. Modules execute
as local user configuration, not as a sandbox; relative module imports are not
supported. Never obtain this file from an Application or remote project.

All definitions are validated before creating directories. Every load recreates
missing configured directories and rechecks availability. Invalid or unreadable
registries and creation failures stay visible as `unavailable`; existing files
are preserved. There is no fallback to another store or host. After correcting
the definition or directory permissions, reload it.

Successful setup saves `fileStoreDefault: {alias, root}` alongside the existing
connections in `config.json`. This bootstrap policy is deliberately outside the
registry: if `filestore.js` is absent, all clients can recreate it and the default
store. `filestore default ALIAS ABSOLUTE_ROOT` explicitly sets this policy. It does
not overwrite a present registry. Without a configured policy, an absent registry
returns `needs-setup`; Plowshare never guesses a development root. Concurrent
clients serialize initialization and configuration changes; setup refuses to
replace an existing registry. Edit the host definition to add more aliases.

For an explicit local file grant, CLI `--root store:applications/mychatbot`
resolves the alias on this computer before opening the normal fenced file
channel. TUI `PLOWSHARE_HERE=store:applications/mychatbot` selects its starting
location the same way. Relative references must remain inside the canonical
store, including through symlinks. Existing absolute-root workflows still work.
User approvals, project membership, exclusions and local command settings remain
authoritative. Portable server/Application FileStore references and Application
runtime writable areas are a separate server capability.
