# Local MCP acceptance instance

This opt-in recipe starts a separate authenticated Plowshare server, a dedicated
Docker PostgreSQL container/volume, and loopback synthetic model/MCP services. It
uses the current built server and public SDKs. It does not run the database test
suite, use an installed deployment, download a model or incur inference charges.
The explicit runtime instance has a real database because acceptance must cross
actual authenticated server operations, retained accounts, jobs and receipts.

Requirements: Java 21, Node 22.13+, pnpm, Python 3 and a local Docker daemon. Build:

```sh
./gradlew :plowshare-server:bootJar :plowshare-mcp-integration:mcpIntegrationBuild
```

Choose a new absolute private state directory outside the checkout. This example
path is local test configuration, not a production default. Start and provision:

```sh
python3 test-support/mcp/instance.py start /absolute/private/mcp-test
node test-support/mcp/server-acceptance.mjs provision /absolute/private/mcp-test
python3 test-support/mcp/instance.py stop /absolute/private/mcp-test
python3 test-support/mcp/instance.py start /absolute/private/mcp-test
NODE_EXTRA_CA_CERTS="$PWD/integrations/mcp/src/fixtures/mcp-test-cert.pem" \
  node test-support/mcp/server-acceptance.mjs exercise /absolute/private/mcp-test
```

Provisioning creates two human users, an ordinary credential project, a separately
scoped executor token, and an Application-owned service principal through public
SDK operations. The restart loads that exact executor into the test server's
allowlist. Exercise configures shared per-user grants and an Application-local
service grant against the same MCP endpoint, publishes hosted tools and runs
one agent job per identity. Each identity uses two independent SDK connections.
A verified job result and the endpoint's per-subject effect count must agree.
Original accounting reads are a separate required dimension. The current server
blocks them through response-contract mismatch for human reads and operation
policy for service reads; the harness retains that failure and exits 2 with
`blocked-accounting`, never a passed acceptance status. No credentials are printed. `acceptance-report.json` is the
redacted result; `acceptance-private.json` retains sensitive provision intents,
credentials, original jobs and usage results for inspection.

`origin.txt` contains the console URL. After provisioning, `LOGIN.txt` contains
the administrator and two human test-user logins. `instance.json` and
`acceptance-private.json` also retain private setup credentials.
These files are private and must never enter source or shared diagnostic output.
The UI and source are the actual Plowshare build; model replies are deterministic
fixtures, so this is a functional test environment rather than a general assistant.
The fixture's synthetic model capability declaration keeps the shipped agent graph
loadable; it does not prove image/model-provider interoperability.

The server uses the default proxy configuration. An earlier test run exposed a
final-repository class-proxy failure; current main fixes it independently in
`2292a30`. This recipe adds no core source workaround. Its private working
directory, server data, Application source and explicit
FileStore roots are separate so workspace fences remain enforced. PostgreSQL is
bound only on a fresh loopback port. Normal authentication stays enabled.

The test certificate/key are intentionally public fixture material. Only the
acceptance Node process trusts that certificate; TLS/hostname checking stays on.
Never use this key, synthetic bearer material or trust setting for deployment.
The synthetic services never forward requests to another endpoint.

Read-only inspection after an interrupted exercise or a blocked accounting result
rechecks original jobs, bindings and credential metadata without starting an MCP
session, provider or new agent job:

```sh
node test-support/mcp/server-acceptance.mjs inspect /absolute/private/mcp-test
```

It requires all original job IDs to have been retained. Keep the same fixture
process lifetime when checking its in-memory effect counts; restarting the synthetic
endpoint resets its observations and is not restart/late-result evidence. Server
restart persistence and fixture effect observations are distinct facts.

Stop/status retain the named database volume and private state:

```sh
python3 test-support/mcp/instance.py status /absolute/private/mcp-test
python3 test-support/mcp/instance.py stop /absolute/private/mcp-test
```

Start after stop preserves the server accounts/keys and fixture ports. The current
built jar is copied into the private runtime directory before launch; record its
SHA/revision when publishing acceptance evidence. A running instance is never
replaced in place. Stop checks a retained PID's command before signalling it;
inspect stale or reused PIDs rather than killing another process.

Provision and exercise are deliberately once-only. A marker is saved before
mutation and every UUID is retained before sending. Failure or unknown delivery
must be inspected; rerunning the same acceptance pass refuses. Starting a new
run requires a new private state directory. Inspect original worker/journal/job
receipts through public operations; do not delete evidence or resend a tool effect.
The harness starts no automatic background provider after the pass; server tools
become unavailable when its test executor exits. Server management remains usable.

For cleanup, first stop the instance, then inspect the exact container name from
`instance.json` (`plowshare-mcp-test-` plus its random `id`) and its
`plowshare.mcp-test.state` label. Remove only that container and its identically
named volume when its private evidence is no longer needed. There is no automatic
volume deletion or global Docker cleanup command.

This is controlled local acceptance. Organization OAuth, UI interactions,
separate-host deployment, third-party tools and native platform/distribution
acceptance remain separate checks in the [interoperability runbook](../../docs/mcp-interoperability.md).
