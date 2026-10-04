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

Desktop sign-in saves the same session and remembers the server/account in
`desktop-connection.json`. On its next launch it reconnects with the shared tokens,
without another password login. If no desktop preference exists and exactly one
saved server is available, it discovers that login (including one created by the
CLI). With multiple saved servers, choose the server explicitly. Explicit Disconnect
or Use demo disables automatic startup reconnect while retaining saved tokens.
Leave the password blank to connect manually using the saved login. Desktop clears the
password form immediately and keeps tokens out of renderer state. The CLI requires
`--server ORIGIN`, its `--url` alias, or `PLOWSHARE_URL` for online commands; it has
no default endpoint. Flags override the environment and may appear before or after
the command. Help, version and offline validation need no server. Select the same
origin in each client to share its login. Origins, including ports, have separate
saved credentials.

Credentials live in `$XDG_CONFIG_HOME/plowshare/credentials`, or
`~/.config/plowshare/credentials` when XDG is unset. `PLOWSHARE_CONFIG_DIR`
overrides the Plowshare configuration directory for isolated profiles and tests.
Desktop connection/project preferences use that same config directory, unless
`PLOWSHARE_DESKTOP_CONFIG` overrides their location; tokens still use the shared
credential directory. Failed persistence is reported in the GUI. The connection
preference contains server/account and reconnect intent only, never passwords or
tokens. Each origin uses one digest-named JSON file. The directory is mode 0700 and the
files are mode 0600 on POSIX systems. This is permission-protected local storage;
OS keychain encryption is not implemented. Passwords are never saved. Explicit
`PLOWSHARE_HANDLE` and `PLOWSHARE_PASSWORD` remain ephemeral overrides for CLI,
TUI and MCP automation; run `login` explicitly to persist environment credentials.

Every refresh holds a cross-process lock, reloads the latest tokens, and writes
the new pair atomically before requesting a WS ticket. A failed ticket or socket
upgrade therefore leaves a usable saved pair. A pending marker is written before
refresh: if its outcome is uncertain, the next client requires a fresh login
rather than spending a potentially retired refresh token again. No application
operation is replayed during authentication or reconnect.

The lock waits at most ten seconds. A process killed while holding it may leave
an origin's `.json.lock` directory. Stop clients using that origin before removing
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
