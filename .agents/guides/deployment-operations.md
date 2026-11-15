# Deployment and operator work

Read this for packaging, release switching, live configuration and operator
actions. Apply it only within the user's authorized environment and action scope.
For capacity tuning or saturation diagnosis, read [backpressure](backpressure.md);
limits are separate process-local controls, not a measured fleet capacity promise.

## Mental model

Source, packaged artifact, installed client, running server, schema and live
configuration are separate identities. Establish them before making claims.
Deployments preserve durable work, configuration and migration history; live
checks exercise the supported public boundary rather than bypassing the system.

## Start in code and instructions

- [Docker deployment](../../deploy/docker/README.md) owns compose, configuration,
  health and companion-service setup; [deployment scripts](../../deploy/docker)
  and their tests describe the shipped operations.
- [Distributions](../../docs/distributions.md) owns artifact and Desktop packaging
  checks. [Server administration](../../docs/server-administration.md) describes
  supported operator controls; [CLI](../../plowshare-cli/README.md) documents commands.
- For schema/history work also read [persistence and cache](persistence-cache.md).
  For pricing/usage read [runtime and inference](runtime-and-inference.md).

## Rules

- Inspect dirty work, source revision, artifact identity, actual runtime/schema,
  configuration and active jobs before a switch. Never assume a branch name,
  receipt or old report proves current deployment state.
- Use the configured installed Plowshare CLI for supported authenticated operator
  actions, selecting the intended saved connection explicitly. Do not substitute
  a Python SDK script or extract credentials when the configured CLI supports it.
  Shell/host tooling remains appropriate for packaging, backups and container work.
- Initial setup credentials are bootstrap material, not ongoing account login.
  Keep saved tokens and secrets private; don't print environments or credential files.
- Preserve effective configuration, durable volumes, companion services and
  resource settings unless their change is explicitly part of the task.
  Deployment addresses and paths belong in private configuration, not source.
- Respect deployment locks and pending releases. Wait for active durable work
  to settle unless interruption is authorized; do not cancel it merely to obtain
  a clean deployment window. Graceful shutdown and durable recovery still apply.
- Validate required backups and describe the actual validation performed. Archive
  listing or reading a dump is not a restore rehearsal. Keep compatible rollback
  artifacts; do not prune backups as an incidental deployment step.
- Never edit shipped migration files or falsify migration history. Divergent
  deployed history needs an explicitly reviewed, rehearsed reconciliation with
  original evidence preserved; do not blindly repair/relabel it. A code rollback
  must support the resulting schema, accounting journal and durable data.
- For an authorized BETA→PROD rollout, validate BETA's artifact, migrations,
  configuration and public behavior before proceeding to PROD. Re-check the
  target's actual state; BETA proof does not substitute for PROD proof.
- Live checks use bounded intent and stable request identities. Inspect after
  uncertain delivery; never replay a model call or mutation to get cleaner evidence.

## Bounded work

Apply the [bounded-work rule](../../docs/coding-standards.md#bound-active-work-in-every-subsystem):
short operator reads/controls have a configurable two-second default ceiling;
builds, backups, restores, migration work and release switches need explicit
finite budgets appropriate to each phase. Define limits for lock acquisition,
transfers, graceful shutdown, readiness probes and validation before starting.
Expiry stops further steps; inspect actual release/work state before recovery.
Do not force-kill durable user work or blindly rerun a timed-out deployment.

Current packaging and deployment tools have separate phase-specific controls;
no universal two-second operator-command abort is configured. A terminal/SSH
timeout can leave remote work running. Retain deployment locks and receipts until
the owning process physically settles or its documented recovery takes ownership;
never mark the release validated from a caller timeout or partial health result.

## Legacy traps

Historical release scripts can pin obsolete migrations, image IDs and host
assumptions. Do not reuse them without inspecting their guards and compatibility.
Container health does not prove login, API behavior, served assets, usage capture
or that an installed Desktop bundle contains the new code.

## Checks and reporting

Use the owning packaging/configuration checks for the change. Record exact
artifact/source identity, schema, preserved configuration and relevant public
functional results without secrets or raw private model content. Record server
and client updates separately. State remaining limits explicitly.

For SQL performance compare bounded, attributable workload windows; for accounting
verify actual provider attempts and field coverage. Do not label a smoke call a
capacity benchmark, or a known zero price as complete provider token reporting.
