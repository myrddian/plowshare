# Projects on the server and in a checkout

A project scopes work in the Plowshare agent framework: agents, skills,
conversations, memory, information, and working files. The administrator creates
server projects; accounts granted project membership can use them. Personal
spaces remain account-owned.

## Applications and Projects

An Application has a valid version 1 `plowshare.json` at its root. The manifest
sets its identity, runtime settings, messaging boundary and account access.
Existing definition directories stay underneath that root:

```text
mychatbot/
  plowshare.json
  .plowshare/
    agents/
    bots/
    skills/
    hooks/
    relay/
```

Older markers and `.plowshare/` definitions alone remain Externals. Remote file
access and coding-agent work appear under **Projects**. Desktop, web console, CLI
and TUI group authorized **Applications** separately from **Projects**. Managed
server projects belong under Applications; they require a valid manifest and
explicit grants, including for earlier registrations. They cannot fall back to
external project access when their manifest is absent.

```json
{
  "version": 1,
  "name": "mychatbot",
  "access": {
    "accounts": [
      {"handle": "reader", "role": "VIEWER"},
      {"handle": "builder", "role": "CONTRIBUTOR"},
      {"handle": "owner", "role": "MANAGER"}
    ]
  },
  "routing": {"sendTo": ["notifications"], "acceptFrom": ["scheduler"]}
}
```

Account handles must match authenticated accounts exactly. Grants use the existing
`VIEWER`, `CONTRIBUTOR` and `MANAGER` roles and cap the account's durable server
membership; they cannot create membership or raise its role. Machine tokens
use their service account owner's manifest grant and retain their token role ceiling. Omitted `access` or
an empty `accounts` list hides the Application from ordinary use. No implicit
public access or wildcard grants exist. Server administration remains a separate
management capability; administrators need a manifest grant for ordinary use too.

The server reads its deployed manifest on each access decision. Client checkouts
cannot override deployed access. Unauthorized Applications are omitted from
project listings, and direct requests and project events use the same effective
role checks. Invalid, unreadable, linked, oversized or missing adopted manifests
close access. Adoption is recorded durably, so deleting the manifest or restarting
the server cannot turn the Application back into a legacy External. Repair the
manifest through its authorized source-management workflow. Agents cannot create,
edit, delete or move the root `plowshare.json` through workspace file tools.

## FileStores, Application roots and writable areas

A **Workspace** is a client-facing view of filesystem locations and operations
the account may use. It can include unrelated directories and does not identify
an Application. A **FileStore** is a host filesystem location with a stable alias.
An **Application root** is a directory inside a FileStore containing its valid
root `plowshare.json`. **Writable areas** are separately admitted locations inside
one or more FileStores. MANAGED or DISJOINT describes source lifecycle ownership,
independently of placement and write admission.

Set `PLOWSHARE_FILESTORES_CONFIG_FILE` to an absolute path to the server's private
`filestore.js`. The version 1 registry uses the same alias/root structure as the
client registry, with optional server account grants for direct file access:

```js
export default {
  version: 1,
  defaultStore: 'applications',
  fileStores: {
    applications: {
      root: '/srv/applications',
      access: { accounts: [{ handle: 'builder', role: 'CONTRIBUTOR' }] },
    },
    outputs: {
      root: '/srv/outputs',
      access: { accounts: [{ handle: 'builder', role: 'CONTRIBUTOR' }] },
    },
  },
};
```

These paths are illustrative operator configuration, not runtime defaults. Store
roots must be existing directories on the server host or inside its container.
The JavaScript default export is evaluated without host, filesystem, network or
import access, with a two-second deadline and a 64 KiB limit. Unknown fields,
aliases, invalid definitions and linked locations are refused. The registry is
private configuration and excluded from agent and user file operations.

Aliases resolve only on the addressed host. A laptop's `filestore.js` never
supplies a missing server alias. Applications persist alias/relative-path
references, resolving them again for access; a missing store or directory cannot
fall back to an old absolute path. Unavailable Applications are omitted from
listings until their locations are restored. Relative references use canonical
slash-separated paths without traversal, Git metadata or symbolic links. An
empty path names the store root.

## Create or adopt an alias-based Application

An administrator creates an Application with explicit placement:

```sh
bin/plowshare-cli application create '{"name":"mychatbot","applicationRoot":{"store":"applications","path":"mychatbot"},"writableAreas":[{"store":"outputs","path":"mychatbot/reports"}]}'
```

Use the same command with `/` in the TUI. In Desktop **Add Application**, select
**Use server FileStore aliases**, enter `applications/mychatbot` as the source,
and comma-separated `alias/path` writable areas. Blank writable areas are read
only. This new `application.create` operation safely refuses on older servers.
MANAGED provisions its source and valid manifest; DISJOINT requires an existing
source with a valid manifest and leaves it unchanged. All writable-area
directories must already exist; creation never provisions unrelated output trees.
The source is writable only when explicitly included in `writableAreas`.

Existing registrations retain `workspace` as their primary source and
`writePaths` as source-relative write admission. Adoption is explicit:

```sh
bin/plowshare-cli application storage set '{"project":"mychatbot","applicationRoot":{"store":"applications","path":"mychatbot"},"writableAreas":[]}'
```

The alias must resolve to the current Application root. This operation updates
placement and write admission atomically; it does not relocate source. It clears
legacy lending and write-path grants. Subsequent changes use the same operation;
legacy define, workspace and lending mutations are refused for adopted records.
Changing a host alias deliberately changes where its Applications resolve.
`project forget` revokes filesystem registration and alias write admission while
retaining the adopted Application boundary and durable work. Re-registration is
deliberate; forgetting cannot turn an adopted Application into an External.

Direct user browsing requires both effective Application VIEWER membership and
a FileStore VIEWER grant. Saving additionally requires CONTRIBUTOR in both and
an admitted writable area; configuration files still require Application MANAGER.
Omitted FileStore account grants deny direct access, including to administrators.
Runtime writes instead intersect admitted writable areas with agent grants and
mandatory exclusions. Neither user grants nor runtime admission imply the other.
Unsandboxed subprocess commands are disabled for alias-based Applications because
a working directory cannot enforce these filesystem boundaries.

## Legacy server project creation

CLI and TUI commands use the same authenticated WebSocket operation,
`project.create`. In the TUI, prefix the command with `/`. In the desktop,
connect as an administrator and choose **Add Application**.

A `MANAGED` project provisions a server workspace and writes `plowshare.json` with an explicit `MANAGER` grant for the creator:

```sh
bin/plowshare-cli project create '{"name":"home-assistant"}'
```

The server chooses a directory under the explicitly configured absolute
`PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY`. Specify `workspace` to
choose a server directory. Keep this directory separate from private server
state. Docker deployments mount it at `/var/lib/plowshare-workspaces`.

A `DISJOINT` project registers an existing directory managed independently,
such as a pipeline checkout. Creation does not write a marker or any other
file into that checkout:

```sh
bin/plowshare-cli project create '{"name":"home-assistant","type":"DISJOINT","workspace":"/srv/pipeline/home-assistant","writePaths":["generated","reports"]}'
```

The pipeline can update the directory while Plowshare retains the project's
framework state. Neither server project type participates in client union sync.
`DISJOINT` makes independent lifecycle ownership explicit. It can register an
Application with a valid existing `plowshare.json`, or an External without one.
An existing Application manifest is validated and never rewritten; the source
owner supplies its account grants. A managed server project is an Application
whose source lifecycle Plowshare controls. `project define`
continues to register existing workspaces with the earlier project behavior;
client-rooted projects can still opt into their existing union sync workflow.
Creation refuses an existing project rather than overwriting it. After an
uncertain response, inspect `project list`; a creation is never automatically
replayed.

## Read access and writable areas

`writePaths` is a project-level allowlist for server workspace mutations. Paths
are relative to the workspace; they name files or directory subtrees, without
wildcards or traversal. Agent grants and mandatory server exclusions further
restrict this allowlist.

| Policy | Server workspace behavior |
| --- | --- |
| `[]` | Read access only; no workspace mutations |
| `["generated", "reports"]` | Read permitted project files; write only inside those areas |
| `["."]` | Write throughout the permitted workspace |

`MANAGED` defaults to `["."]`; `DISJOINT` defaults to `[]`. The server rechecks
policy on each write, edit, delete and move, including both ends of a move.
Symlinks cannot expand a writable area beyond the workspace. Private server
configuration and data remain excluded. This policy applies to workspace file
operations; SDK requests to change framework definitions retain their separate
administrative and project permissions.

Command execution is refused when the project's writable areas are restricted.
A working directory alone cannot prevent a command writing elsewhere, so
commands need a filesystem sandbox before they can honor that policy.

## Recognize a project from its files

Discovery first checks a root `plowshare.json` Application manifest. It must be
versioned JSON; invalid Applications never fall back to a legacy marker.
A legacy External uses a marker at `.plowshare/plowshare` or the existing
`.plowshare/project` path. Local markers take precedence over a root-level
`plowshare` file in the same directory. Discovery walks upward and uses the
nearest project identity.

For a legacy client-only checkout, put a `plowshare` file directly in the project root.
The preferred format is versioned JSON:

```json
{"version": 1, "name": "home-assistant"}
```

The version 1 schema defines identity, routing and project runtime settings. Other JSON properties
are retained for application and SDK configuration; they do not execute code by
themselves.
A plain first-line name remains supported for compatibility:

```text
home-assistant
```

Unknown versions, malformed JSON, linked or unreadable manifests are refused.
Neither format is automatically rewritten. A CLI executable with a shebang
is not a project manifest.

Before interpreting a root manifest as `DISJOINT`, clients check whether it
already identifies a registered project. Application manifests can resolve a
registered MANAGED or DISJOINT Application; legacy roots retain local or UNION
identity matching. Otherwise, it attaches
as a **client-only DISJOINT project**. No `.plowshare` directory, marker, Git
repository, mirror or sync configuration is created. Its files are served only
for that client, with the ordinary file containment and agent permissions.
Sync and export are refused.

The project appears normally in that client's navigation with its manifest
name. Its routing identity is isolated to that authenticated project connection;
it never appears in account-wide project lists or on a different client,
including another simultaneous client on the same account. The GUI keeps this
attachment in memory rather than saving it as a shared project-folder entry.
A new client connection must explicitly discover or open the directory again.
Project and conversation access stays tied to the originating client connection.
The manifest contains no credentials, server address or server-generated routing key.

This client-only mode differs from an administrator's `project create` with
`type: "DISJOINT"`: that command intentionally registers a shared server project
whose membership controls access. A root manifest alone never performs that
registration.

A `.plowshare/` directory containing only definitions does not identify a
project. Clients scan from the chosen working directory upward and use the
nearest marker, so nested projects take precedence. Linked or unreadable
markers are refused rather than silently identifying a different project.

- **TUI:** starting in a marked checkout discovers and attaches that project.
  An explicit `--project` selects server context without automatic attachment;
  `/here` explicitly attaches the working directory.
- **Desktop:** **Add project folder** discovers the marker in the selected
  directory or its ancestors. A checkout keeps the same project identity even
  when its name or path differs from the server workspace.
- **CLI:** `--root` discovers the marker when `--project` is omitted:

```sh
bin/plowshare-cli --root /home/me/home-assistant memory index
bin/plowshare-cli --root /home/me/home-assistant client root
```

The first command serves the checkout's files for that invocation. `client root`
keeps serving them until disconnected. Without `--root`, use `--project home-assistant` for a registered server project.
A client-only manifest does not publish that name on the server. An explicit project conflicting
with a checkout marker is refused.

Attaching a `MANAGED` or `DISJOINT` checkout does not move or replace the server
workspace, and does not enable sync. Runs submitted from that attached client
use the checkout's files. Background tasks, pipelines and other sessions keep
using the server workspace. Local changes reach the deployed project through
its external workflow, such as a Git commit and pipeline deployment.

## Internal message routing

See the [Internal project messaging manual](internal-messaging.md) for a complete
setup, address reference, Personal defaults and troubleshooting.

Registered server projects can exchange agent messages using the existing
durable message transport. Between ordinary projects, each new cross-project message requires both the
source's `sendTo` and destination's `acceptFrom` to list the other project by
its exact registered name. Missing lists and empty lists permit no cross-project
messages. The sending account must also have access to both projects; messages
never cross accounts. Personal projects are identified as `Personal:<account-name>`
and default to sending and receiving across their account's accessible projects,
without requiring those allowlist entries. The defaults are independently
configurable on the server; see [Personal routing](personal-space.md#internal-message-routing).

For a server DISJOINT workspace, its root `plowshare` manifest can contain:

```json
{
  "version": 1,
  "name": "home-assistant",
  "routing": {
    "acceptFrom": ["scheduler"],
    "sendTo": ["notifications"],
    "routeFiles": ["routes/internal.json"]
  },
  "homeAssistant": {"entities": ["light.office"]}
}
```

The `notifications` project needs its own manifest with
`"routing": {"acceptFrom": ["home-assistant"]}`. For an Application, put routing in its root `plowshare.json`, preserving its
explicit account grants. Legacy Externals can retain their existing identity
files. Root Application precedence applies to configuration as well as identity. The manifest name must match the
registered server project.

Route files are versioned JSON, with names unique across the project's files:

```json
{
  "version": 1,
  "routes": [
    {"name": "notify", "project": "notifications", "agent": "notifier"}
  ]
}
```

An agent can call `send_message` using either a named route or an explicit
destination:

```json
{"route": "notify", "body": "Office light changed", "reply_expected": true}
```

```json
{"to_project": "notifications", "to": "notifier", "body": "Office light changed"}
```

Routes use the shared account/project/agent default conversation unless configured
otherwise. Add `retainConversation: true` to create one dedicated log on first use
and reuse it after restarts, or `conversation: "cnv_..."` to select an existing
active account-owned conversation in the destination project. These options are
mutually exclusive and refuse `lifetime: "task"`. A dedicated binding is scoped
to the account, source project and route name; stopping or archiving it never
silently creates a replacement. See [conversation retention](internal-messaging.md#keep-a-routes-conversation)
for configuration examples and destination requirements.

Route aliases select a destination; they cannot expand either project's
allowlist. The receiver runs in its own project with its own definition,
tools, file permissions and history. Cross-project messages do not inherit the
sender's document selections. A correlated `reply_to` uses the original return
address, including after routing policy revocation; this does not authorize
unsolicited reverse messages. Incoming message data includes `from_project`
and `to_project`.

The server reads routing configuration from its own registered workspace on
each new send. Pipeline updates take effect without restarting. An attached
checkout cannot change deployed routing. Client-only DISJOINT projects retain
their private scope and cannot exchange messages with other projects, even if
their manifest contains routing fields.

Route files must stay within permitted, visible areas of the server workspace;
absolute paths, traversal, links, excluded paths, duplicate aliases, missing
files and malformed configuration are refused. Manifests and each route file
are limited to 64 KiB, with at most 256 file references and 256 aliases and a
1 MiB total configuration limit. Files are configuration, not executable skills.
See the route file schema and
[Agent and bot messaging](agent-messaging.md) for delivery and lifecycle details.

## Project definitions and skills

Client checkouts can carry definitions under `.plowshare/agents/`,
`.plowshare/bots/`, `.plowshare/skills/<name>/SKILL.md`,
`.plowshare/orchestrations/`, and `.plowshare/environment.yml`. Attaching the
checkout makes its permitted sources available to the server resolver.

Server project definitions live under the private server data tree at
`projects/<project-id>/`, including `skills/<name>/SKILL.md`. They are separate
from the workspace and keep their existing resolution authority. See
[Skills and agent rules](skills-and-agent-rules.md) for package format, precedence,
explicit grants and execution.

## Project runtime configuration

The version 1 project JSON file is the preferred place for project settings:

```json
{
  "version": 1,
  "name": "home-assistant",
  "defaultBot": "interlocutor",
  "caps": {
    "steps": 40,
    "budget": 200,
    "autoIncrease": true,
    "time": 90,
    "failedChecks": 5
  },
  "commands": {
    "local": {"mode": "ask", "shells": false},
    "server": {"mode": "off"}
  },
  "skills": {
    "research": {"agentVisible": true}
  },
  "routing": {
    "acceptFrom": ["scheduler"],
    "sendTo": ["notifications"]
  }
}
```

Settings apply from the selected identity file: root `plowshare.json`, then
`.plowshare/plowshare`, `.plowshare/project`, then root-level `plowshare`. The first identity wins, including
an existing plain-name marker. New marked and managed projects write JSON. Existing
identity files are preserved until explicitly edited. To upgrade a plain marker,
replace it with `{"version": 1, "name": "its-existing-name"}` and add settings.

| Setting | Purpose |
| --- | --- |
| `caps` | `steps`, `budget`, `autoContinue`, `time`, `failedChecks`, `autoIncrease` |
| `commands.local`, `commands.server` | Command `mode`, `shells`, `inherit`, `env`, `timeout`, `output`, `isolation` |
| `skills.<name>.agentVisible` | Skill discovery visibility, without granting the skill or changing its package |
| `defaultBot` | Bot selected when the caller does not name one |
| `routing` | Existing cross-project message policy and named route files |
| `access.accounts` | Explicit Application account grants, capped by server membership |

Command modes are `off`, `gated`, `ask` and `open`. `timeout` uses seconds or minutes
(e.g. `90s`), `output` uses `KiB` or `MiB`, and `isolation` currently accepts only
`none`. `inherit` is a list of host variable names; `env` maps names to strings.
A client manifest's `commands.server` never authorizes server commands. Server-owned
configuration controls that side, and each client rechecks its own local policy
before running a command. Agent file mutations cannot change a root project
manifest; user-facing settings controls edit it directly. A root `plowshare` CLI
executable with a shebang remains an ordinary file.

Existing `environment.yml`, `skills.yml` and `bots/default` configuration remains
readable for compatibility. Within each location, explicitly set JSON fields
supersede the corresponding legacy values. Unset fields retain their legacy or
framework defaults. Caps merge local over server, key by key. Skill discovery and
default-bot selection retain their server project, rooted session, Personal and
shared-default authority order. Malformed command policy refuses command execution;
malformed skill policy refuses discovery.

The desktop caps editor and TUI `/cap` and `/always` commands update the selected
JSON manifest when present, preserving routing and application fields. Older
plain-marker projects retain their existing YAML editing behavior. A root-only
JSON checkout gains no `.plowshare` directory when its settings are edited.
Server registration, membership, writable areas, message-instance state and global
provider settings remain under their existing administrative controls. Agents,
skills, orchestrations, hooks and rules remain separate source files.

## Automatic execution allowance increases

Enable `caps.autoIncrease` in the project JSON file:

```json
{"version": 1, "name": "home-assistant", "caps": {"autoIncrease": true}}
```

`autoIncrease` is a strict boolean and defaults to disabled. The harness approves
another finite chunk when the step or model-call allowance is exhausted. Chunks use
`steps` and `budget` when configured, otherwise the running agent's definition
values. Spending remains cumulative; delegates spend the same parent allowance.
Model-call increases for ordinary conversations are committed to the owning
conversation before additional calls; an exhausted chat can accept its next
utterance without a manual top-up. In orchestration runs the engine records and
continues the capped conductor through its existing continuation path, including
script-driven conductors.

Enable or disable it through the desktop project caps control (**Automatically
increase steps and budget**) or the TUI's `/cap auto-increase on` and
`/cap auto-increase off`. `/cap` shows the effective setting and its source. A local
explicit `false` overrides a server project's `true`. The server retains the last
successfully read local caps during disconnects. Disabling prevents further
automatic grants; capacity already granted remains available. Legacy YAML uses
`caps.auto-increase` with the same semantics.

This setting covers execution steps and model-call budgets. Command approvals,
cancellation, failed-check/stuck decisions, time-limit policy and separately funded
board/message leases keep their existing controls. Finite `autoContinue` remains
available for orchestration caps when automatic increases are off. Grants never
reset spending or lift a ceiling entirely; integer accounting bounds still stop
further growth. Each approved model-call chunk permits additional model use.

## Project roles

Regular accounts receive Viewer, Contributor or Manager access per project. Personal
spaces remain private to their account owner. See [server administration](server-administration.md#project-access-and-personal-scopes) for the permission matrix, membership commands and desktop access dialog. Workspace write restrictions still apply to every role.

## Deployed Application files

An authorized Application can be inspected without connecting a local folder. In
Desktop, select it under **Applications** to open its workspace. **Conversations**
returns to your scoped conversations; **Files** browses the deployed source.
**Runtime definitions**, **Memory** and **Boards** open existing views with the
Application selected. Conversation histories retain their account ownership.
The web console exposes **Files** on each Application card.

CLI and TUI use the same WebSocket operations:

```text
application files {"project":"mychatbot"}
application files {"project":"mychatbot","path":".plowshare/agents"}
application read {"project":"mychatbot","path":"notes.md"}
application save {"project":"mychatbot","path":"notes.md","text":"Updated notes","revision":"<revision returned by read>"}
```

CLI `--project` and the TUI's current project supply the project when omitted.
`application.files`, `application.file.read` and `application.file.save` are also
available through the shared SDK operation contracts. No local file claim, union
or checkout is needed. This capability applies to registered server Applications;
external Projects continue to use their existing file access path.

The browser lists up to 200 entries per directory. An explicit relative path can
open files or directories beyond that listing. Reads and edits require existing
regular UTF-8 text files no larger than 256 KiB. Linked paths, Git metadata and
excluded paths cannot be accessed. Each request rechecks effective Application
membership, the root manifest and the filesystem fence. Write access also requires
CONTRIBUTOR or MANAGER and a matching admitted writable area (legacy registrations
use `writePaths`). Editing `plowshare.json`,
`.plowshare/` definitions or `Relay/` definitions requires MANAGER; an edited root
manifest must remain valid.

A save carries the SHA-256 content revision returned by its read. The server
checks it before replacing the file atomically and preserves POSIX file modes
where supported. Concurrent external source owners remain independent: this is
not a filesystem transaction with their writers. A failed or unreadable save
reply is never replayed. Read and review the current file before saving again;
the graphical editors retain an unsaved draft while reconciling changes.

For alias-based Applications, the graphical file views offer Application source
and each additional writable area. Drafts stay separate by location and path.
CLI, TUI and SDK requests select an exact admitted location with `location`:

```text
application files {"project":"mychatbot","location":{"store":"outputs","path":"mychatbot/reports"}}
application read {"project":"mychatbot","location":{"store":"outputs","path":"mychatbot/reports"},"path":"summary.md"}
```

Omitting `location` selects the Application source. Arbitrary unadmitted locations
are refused. Replies echo the selector; clients refuse crossed or missing
selectors rather than silently opening the source directory.
