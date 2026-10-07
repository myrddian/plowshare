# Projects and workspaces

## A project is more than a folder

A project scopes conversations, knowledge, definitions, tools and access. It can
have server files, client-provided files or both through supported modes. A folder
name alone does not create membership, make a server project or register a local
file channel. Projects persist independently of a client's current navigation.

Personal is private to its account. Ordinary server projects can have explicit
members. Server administration and project membership have different roles; an
administrator's management access must not be mistaken for a document audience
grant or another account's conversation ownership.

Project roles are `VIEWER` (read), `CONTRIBUTOR` (read and start/update work), and
`MANAGER` (also manage definitions and membership). Server administrators have
Manager authority in legacy Externals. Applications also require an explicit
manifest grant for ordinary use. Inspect `project access` before
starting work; Viewer membership alone cannot run an agent or send a message.
See [account and role administration](../server-administration.md) for grants,
role changes, recovery and service-token ceilings.

## Applications and Projects

An Application is a folder with a valid root `plowshare.json`, using the existing
`.plowshare/` conventions for skills, agents, bots, relay and hooks underneath it.
Older identity formats and `.plowshare/` alone remain Externals; remote file
access and coding-agent work appear under **Projects** in the GUI, CLI and TUI.
Managed server projects appear under **Applications**, with a valid manifest and
explicit grants required even for earlier registrations. They cannot fall back to
external project access. The client lists only Applications that the authenticated
account can use.

```json
{"version":1,"name":"mychatbot","access":{"accounts":[{"handle":"reader","role":"VIEWER"}]}}
```

`access.accounts` grants an exact account handle one of `VIEWER`, `CONTRIBUTOR`
or `MANAGER`. It caps existing server membership and never creates or raises it.
Absent or empty grants hide the Application from ordinary use, including direct
requests. The deployed server manifest is authoritative. Invalid, unreadable or
missing adopted manifests close access, including after restart; deleting the
manifest cannot restore legacy access. Server administration remains separate.

Managed server projects are Applications Plowshare owns and manages. Creation
writes a manifest with an explicit MANAGER grant for the creator. A DISJOINT
registration can be an Application or an External, depending on its root
manifest; another system owns that directory's lifecycle. Plowshare validates an
existing Application manifest without rewriting it. Source writes default off,
with runtime state kept separately. See [the project guide](../projects.md) for
bounded write areas, manifest validation and routing.

## Choose the right filesystem ownership

A Workspace is a client-facing view of allowed filesystem operations, potentially
over unrelated directories. A FileStore is a host location with a stable alias.
An Application root resides in one FileStore; separate writable areas can reside
in several. The server's private `filestore.js` resolves aliases on that host.
Local client FileStores cannot substitute for server definitions.

Use `application create` with explicit `applicationRoot` and `writableAreas`, or
`application storage set` to adopt an existing Application at its current root.
Empty writable areas make it read only; source writes also need explicit admission.
The desktop creation form offers server FileStore aliases, and file views select
source or an admitted writable area. User FileStore account grants and Application
runtime write admission are separate. See [FileStore setup and adoption](../projects.md#filestores-application-roots-and-writable-areas)
for configuration, permissions, command examples and older-server behavior.

Legacy `project create` retains its existing primary-source and source-relative
write-path meaning until explicit adoption:

| Mode | Files belong to | Setup and behavior |
| --- | --- | --- |
| Managed server project | Plowshare server workspace | Administrator provisions files; writes default to the permitted workspace |
| Server `DISJOINT` project | Independently managed server directory | Administrator registers existing files; no marker creation or client sync; writes default off |
| Rooted client checkout | Connected client's filesystem | Client explicitly serves a fenced root; presence lasts with that connection |
| Client-only manifest project | That authenticated attachment | Discovered locally, isolated routing identity; not an account-wide server project |
| Union synchronization | Explicitly enabled Git-backed copies | Synchronization and conflict management are separate from attachment |

Use a server `DISJOINT` workspace for a pipeline-owned checkout. Its writable
areas can allow generated artifacts without giving an agent ownership of the
entire repository:

```sh
bin/plowshare-cli project create '{"name":"pipeline","type":"DISJOINT","workspace":"/srv/pipeline","writePaths":["generated","reports"]}'
```

That path must exist on the server, including inside the server container. It is
not interpreted on your laptop. An empty `writePaths` array is read-only; `["."]`
permits writes throughout the allowed root, still subject to other exclusions.
File moves check both the source and destination policies.

## Attach a local checkout deliberately

The project identity marker supports versioned JSON:

```json
{"version":1,"name":"my-project"}
```

Discovery first checks the root Application manifest `plowshare.json`. It must
contain versioned JSON; invalid Applications never fall back to older markers.
Legacy External locations include `.plowshare/plowshare`, the legacy
`.plowshare/project` path, and a root-level `plowshare` manifest. Nearest-project
discovery and local-marker precedence avoid an ancestor's identity swallowing a
nested project. Plain first-line names remain compatible. Malformed or linked
markers are refused, not rewritten into something the client guesses.

In a source checkout, an explicit CLI attachment is:

```sh
bin/plowshare-cli --project my-project --root /absolute/path/to/checkout client root
```

The root is served until the process disconnects. For a one-shot operation,
`--root` serves files for that invocation. The desktop's folder selection and the
TUI's explicit attachment path use the same file containment contract. A local
manifest can describe a client-only project rather than publish a server project.
Consult the detailed project guide before combining explicit `--project` with a
discovered client-only manifest.

## File tools and command policy

File discovery and reads are bounded and path-scoped. Tools such as `file_roots`,
`file_glob`, `file_grep`, `file_stat`, `file_read` and `code_map` expose different
views. `code_map` is navigation over retained syntax; abbreviated signatures and
symbol matches are not resolved semantic references or substitute evidence for
the exact file text.

Edits, deletion and moves require write grants, valid containment and current
preconditions. Symlinks and traversal cannot widen the root. Private server
configuration and data remain excluded from agent workspace access. Reading
files over a client channel is not unrestricted filesystem ingestion into the
information catalogue.

Command execution has separate local/server modes and policy: off, gated, ask or
open where supported. It also has environment, shell, timeout and output limits.
A command's working directory does not confine arbitrary subprocess writes.
The server refuses commands for projects with restricted writable areas because
those commands cannot honor the policy without an appropriate filesystem sandbox.
Do not try another execution side to evade a denied command.

## Runtime settings and overrides

The project manifest can carry caps, command policy, selected default bot and
per-skill discovery overrides. Supported JSON keys are versioned contracts; extra
application data does not execute itself. Legacy settings files have compatibility
rules and precedence described in the project manual.

Caps include step, allowance, auto-continuation, time and failed-check behavior.
An explicit zero, a missing value and an inherited default can mean different
things. Inspect the effective settings rather than inferring them from one file
or silently accepting a malformed override.

## Synchronization and conflicts

Union synchronization uses Git-backed state to exchange permitted working files.
Attaching a project is not consent to synchronize it. Hidden paths and server-owned
trees remain controlled by the sync rules. Native Git byte transport is an HTTP
boundary; operational union controls use their authenticated contracts.

Review each conflict and choose mine, theirs, a merged result or done according to
the supported workflow. The reviewed file and conflict must still match before
resolution applies. A local edit after preview makes that preview stale. Leaving
or withdrawing file presence does not mean resolving every pending conflict.
