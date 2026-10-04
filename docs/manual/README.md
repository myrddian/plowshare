# Plowshare manual

Plowshare gives you a persistent place to talk to assistants, work in projects,
retain knowledge, research questions and coordinate workflows. The server keeps
the work and its history. You can return through a different client without
moving the agent runtime onto that client.

This manual covers using, configuring and extending the current
`0.1.0-SNAPSHOT` system. It describes implemented behavior and its limits; a
capability still needs its configured models, permissions and tools. An installed
manual is a retained snapshot of the Markdown in the checkout used to install it.
It is not fetched from the internet or silently updated when the server restarts.

## Choose a starting point

| You want to… | Read |
| --- | --- |
| Connect and have your first useful conversation | [First steps](01-first-steps.md) |
| Find work in the desktop, terminal or CLI | [Clients and everyday work](02-clients-and-work.md) |
| Understand what an agent, bot, project or job means | [Architecture and concepts](03-architecture.md) |
| Build assistants, add skills and control what they can do | [Agents, bots and permissions](04-agents-and-permissions.md) |
| Work safely with local or server files | [Projects and workspaces](05-projects-and-workspaces.md) |
| Develop a personal wiki, memory and research collection | [Knowledge and research](06-knowledge-and-research.md) |
| Delegate, exchange messages or convene a swarm | [Coordination and workflows](07-coordination-and-workflows.md) |
| Run recurring work or connect external services | [Automation and integrations](08-automation-and-integrations.md) |
| Operate, update and recover a server | [Server administration](09-server-administration.md) |
| Diagnose a refusal, stalled job or missing result | [Troubleshooting and reference](10-troubleshooting-and-reference.md) |
| Use an existing procedure or build and validate your own | [Use and build orchestrations](11-orchestration-authoring.md) |
| Place a hook and handle a lifecycle or named event | [Hooks: placement, events and authoring](12-hooks.md) |
| Send between projects, configure routes and follow replies | [Messaging and project routing](13-messaging-and-routing.md) |
| Configure a discussion team and recover its work | [Use and configure the swarm board](14-swarm-board.md) |
| Build a checkout or add a new capability | [Build and extend Plowshare](15-building-and-extending.md) |
| Install these chapters into the server | [Library installation](installation.md) |

Read the first two chapters before configuring a custom agent. Read architecture
before building an adapter. Most everyday work starts with a conversation in
Personal or an existing project; you do not need to author an orchestration to
ask a question or save a source.

## Detailed capability manuals

The installation also includes the existing, more detailed capability guides as
chapters. These are the canonical references rather than duplicated copies:

- [Projects and runtime settings](../projects.md)
- [Personal space and starter skills](../personal-space.md)
- [Accounts, project roles and service tokens](../server-administration.md)
- [Skills and agent rules](../skills-and-agent-rules.md)
- [Information, evidence and document lifecycle](../information-system.md)
- [Research pipeline and report inspection](../scripted-research.md)
- [JavaScript orchestration authoring](../scripted-orchestrations.md)
- [Hook type contract and runtime behavior](../../plowshare-hooks/README.md)
- [Agent messaging and instance lifecycle](../agent-messaging.md)
- [Internal project routing](../internal-messaging.md)
- [Conversation retrieval](../conversation-retrieval.md)
- [Memory digests](../memory-digests.md)
- [Model usage and accounting](../model-usage-accounting.md)
- [SDKs](../sdks.md)
- [A2A receiving](../a2a-receiving.md) and [A2A sending](../a2a-sending.md)
- [Integration runtime](../../plowshare-integrations/README.md)
- [Home Assistant](../../plowshare-integration-home-assistant/README.md)
- [Distributions](../distributions.md) and [Docker deployment](../../deploy/docker/README.md)

## Read inside Plowshare

After an operator runs the [installer](installation.md) with `--share`, click the
desktop **Help** question-mark icon. **Library → Manual** opens the index; follow
chapter links to navigate. Chapters use stable supplied names such as
`Plowshare manual / 13-hooks.md`; the reader resolves each link to its current
shared revision UUID. A personal installation is visible in **Library → Documents
→ Manage document sources and imports** by filtering **Your tags** with
`plowshare-manual`.

In the TUI, list the shared chapters with:

```text
/information list --scope shared {"filter":{"tags":["plowshare-manual"]},"limit":100}
```

Read a returned revision with:

```text
/information read --scope shared {"revision":"<revision-uuid>","offset":0,"limit":8192}
```

Continue with the returned end offset to read the rest of a long chapter. The
information API and ordinary source reader use bounded windows; one response is
not necessarily the whole chapter. The Manual tab assembles these windows into a
complete chapter for reading. CLI examples use `bin/plowshare-cli` from a source
checkout; packaged clients can use the equivalent `plowshare` command.

Agents granted `information_read` can discover the same manual with
`{"operation":"list","filter":{"tags":["plowshare-manual"]},"limit":100}`,
then read the returned revisions. Their current account and project supply
permissions. Shared manual chapters are available in ordinary scopes that include
shared information. The manual is reference material; its examples do not confer
tool grants or instruct an agent to execute every command it contains.

Scoped service tokens cannot read the shared catalogue. The current installer
publishes through a human account's information collection; project-scoped manual
publication for service-account agents is a separate, unfinished path.

The desktop Help entry and Manual tab are the primary GUI reading path. The web
console, CLI and TUI have different capability coverage; this installer does not
add a TUI `/manual` command or unauthenticated documentation endpoint.
