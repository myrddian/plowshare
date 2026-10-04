# First steps

## What you need

You need a running Plowshare server, its origin such as
`http://127.0.0.1:8091`, and an account. The server needs PostgreSQL with pgvector,
persistent server storage and configured model providers for model-backed work.
The desktop, terminal and CLI are clients of that server. Installing a client
does not start a database or make the configured model available.

If someone operates the server for you, get its address and your account from
them. If you operate it yourself, follow the server administration chapter and
the Docker/deployment guide first. Use an origin without a path, query or embedded
credentials. Loopback refers to the machine running the client; it does not name
a remote Docker host.

## Finish the first administrator setup

On an unconfigured server, startup prints a temporary `admin` password. This
temporary account can complete setup rather than perform normal agent work.
From a built source checkout:

```sh
bin/plowshare-cli setup --url http://127.0.0.1:8091
```

Enter the temporary credentials, then choose your administrator handle and a
password of at least 12 characters. The desktop connection dialog also offers
first administrator setup. Successful setup consumes the temporary account and
saves the new login. An unfinished setup rotates its temporary password on
restart; completed setup does not create another temporary account.

For an existing account:

```sh
bin/plowshare-cli login --url http://127.0.0.1:8091
```

Complete any required password change through an interactive client. A background
integration cannot answer that human setup step. CLI, TUI and desktop share saved
sessions for the same server origin. They store tokens, not your login password;
ephemeral environment credentials remain available for configured automation.

## Start in Personal

Personal is your private server project. It has its own files, definitions,
conversations and knowledge. You can use it for general questions, plans, personal
notes or an evolving second brain. It is not the global server namespace and it
is not another account's Personal project.

Connect in the desktop or start the TUI:

```sh
bin/plowshare-talk --url http://127.0.0.1:8091
```

Choose Personal and a bot. Start with a small, concrete request: explain a topic,
help draft a plan, or review some text you provide. The bot's available tools and
delegates determine what else it can do. If a capability is absent, granting it
or configuring its provider is an explicit setup task, not something the bot can
solve by guessing another tool name.

## Understand the first response

A request can create a durable job. **Accepted** means the server admitted the
work, not that it answered. Watch progress, then inspect its terminal outcome.
An answered run, an incomplete result, a refusal and a cancelled job are different
states. The last text shown on screen is not always the job's authoritative result.

If the client disconnects, keep the returned job and conversation IDs. Reconnect
and read their status rather than repeating the original request. Some server
work continues independently of the client; work needing its local file channel
can fail when that channel disappears.

## Add a project when the work has a home

Use a project for a codebase, research collection, integration or ongoing area of
work. Projects separate definitions, permissions and history. An administrator
can create a managed server workspace:

```sh
bin/plowshare-cli project create '{"name":"my-research"}'
```

Creation does not make every account a member. Grant the intended accounts
membership, then select the project in your client. To work with a local checkout,
explicitly attach its folder rather than entering your laptop's path as though
it were a server directory. Read the projects chapter before enabling commands
or synchronization.

## Save a useful source and find it again

In the desktop Library, choose the appropriate source collection and **Add
source**. Give it a recognizable name and paste text, or supply a public URL.
Processing retains a version and prepares readable text and retrieval projections.
Check its status; source intake can be accepted while processing is still pending.

Select the source to read or ask about it. An evidence quotation identifies the
retained revision and text coordinates, so it remains tied to the text you read.
Saving a source, remembering a lesson, generating a report and sharing information
are separate actions. A private draft does not become shared merely because the
bot discussed it.

## A useful progression

1. Have an ordinary conversation in Personal.
2. Save one source and read it from the Library.
3. Use a project for a distinct body of work.
4. Attach files deliberately when the task needs them.
5. Add a specialist, skill or workflow only when it solves a repeated need.
6. Inspect usage and outcomes as you increase research or automation budgets.

The detailed capability guides explain the configuration for each step. The
shipped bots and workflows are starting examples built from these primitives;
they are not a guarantee that every deployment grants every capability.
