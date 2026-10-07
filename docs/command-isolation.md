# Linux command isolation

Set `commands.local.isolation` or `commands.server.isolation` to `bubblewrap` in
the executing machine's project configuration to use Linux namespaces for admitted
commands. Command modes, agent write grants, approvals and ownership checks still
apply. The default remains `none`, and server command mode remains `off`.

Bubblewrap must be installed by the operator. Plowshare requires support for
`--unshare-user`, `--disable-userns`, `--size`, `--die-with-parent` and
`--new-session`; the [bubblewrap manual](https://github.com/containers/bubblewrap/blob/main/bwrap.xml)
describes these namespace and descriptor options. The acceptance fixture uses Ubuntu 24.04's bubblewrap 0.9.0.
The host must permit unprivileged user namespaces and the required mount/PID
namespaces. Configuration or probe failure refuses the command before user code
starts. There is no unisolated retry. macOS and Windows refuse `bubblewrap`.

## Configure the executing machine

The server binds operator-owned Spring configuration:

```yaml
plowshare:
  command-isolation:
    bubblewrap: /usr/bin/bwrap
    launcher: /bin/sh
    scratch-root: /var/lib/plowshare/isolation-scratch
    runtime-roots:
      - /usr
      - /bin
      - /lib
```

These are Linux installation examples, not defaults. Supply absolute executable
and launcher paths outside every workspace, and an explicit list of read-only
runtime directories appropriate to the distribution. Include dynamic loaders,
interpreters and libraries; for example an x86 installation may also need
`/lib64`. Do not include secrets, private service data or broad filesystem roots.
Runtime roots are trusted operator authority: commands can read their contents.
The launcher must be a trusted POSIX shell visible through the configured mounts.
A fixed host script opens a private argument descriptor and execs bubblewrap; no
command text executes there. The same shell runs a no-op probe inside the complete
namespace/mount policy on every invocation.

Node-based clients (CLI, TUI, desktop and Node SDK file serving) use their own
host environment, separate from a command's environment:

```sh
export PLOWSHARE_BUBBLEWRAP=/usr/bin/bwrap
export PLOWSHARE_SANDBOX_LAUNCHER=/bin/sh
export PLOWSHARE_SANDBOX_RUNTIME=/usr:/bin:/lib
export PLOWSHARE_SANDBOX_SCRATCH=/var/lib/plowshare/isolation-scratch
```

Set all four together. Create the scratch directory beforehand, owned by the executing
account with mode `0700`, separate from workspace and runtime mounts. Use one
directory per installation and PID namespace; never share it across independent
containers or hosts. The Node SDK also exports the typed `CommandIsolation`
interface and `Bubblewrap` implementation at `plowshare-client-node/isolation`
for explicit composition. Project configuration cannot choose backend binaries
or runtime mounts. Only the executing machine's local policy enables isolation;
a server request cannot enable it on a client.

Isolated file-channel commands use the distinct `run_isolated` operation. Older
clients refuse an unknown operation; they cannot silently discard an isolation
field and execute raw `run`. A client whose own policy requests isolation also
isolates ordinary `run` requests. Delivery/cancellation retain existing file
channel semantics, with no transport fallback or mutation replay.

## Boundary and supported workspaces

Each command gets an empty filesystem with read-only runtime mounts, a private
`/proc`, minimal `/dev`, an 8 MiB root tmpfs and a 64 MiB `/tmp` tmpfs. Workspace
roots are mounted read-only; granted writable directories are overlaid read/write.
Directories for writable areas must already exist. Hidden and excluded entries
are covered with inaccessible placeholders. This includes `.git`, `.env` and
`.plowshare`; Git metadata operations are not supported in this first backend.
Protected root manifests remain read-only. The command can see masked entry
names, but cannot read their contents.

Before mounting, the backend inspects visible workspace entries without following
links. Symlink mountpoints, visible hard links and special files refuse startup.
Hidden directories are masked without traversing their contents. Inspection is
bounded at 100,000 entries and mount arguments at 128 KiB. A fully writable root
must already contain a regular `plowshare.json` and a `.plowshare` directory,
providing protected mountpoints and preventing creation of overriding project
configuration. Legacy workspaces need to initialize these before enabling full
root writes; they may instead grant existing subdirectories.

The read/write snapshot does not revoke mounts mid-command. Workspace topology
and trusted runtime installations must not be concurrently changed by another
host process during startup. This backend does not protect against a hostile
host operator, kernel vulnerabilities or malicious operator-provided binaries.

The process has private network, PID, IPC, UTS and user namespaces, dropped
capabilities, a new session and disabled nested user namespaces. Host network
services and host processes are inaccessible. Normal exit, cancellation, deadline
and executing-process death tear down the PID namespace, including detached
descendants. The environment retains only explicitly configured/inherited names.
Bubblewrap itself starts with an empty environment and installs command variables
inside the boundary, so loader variables cannot inject code during host startup.
Mount options and environment variables reach bubblewrap through a private mode-0600 file
descriptor, keeping environment values out of host command lines and preserving
command stdin. The descriptor is closed before user code runs; the file is
removed afterward. Before each launch, recovery removes private files belonging
to dead owners in the configured scratch directory. Boot identity and process
start ticks distinguish a dead owner from a reused PID; live owners are retained.
Recovery never follows links or recursively removes trees, and preserves unknown
contents. The directory inventory is bounded to 1,024 entries; failed recovery
refuses startup. Command environment values must not be logged.
Existing input/output bounds and deadlines still apply. Cleanup failures emit an
operator warning while preserving the delivered command's result.

CPU, memory and process-count quotas require deployment cgroups and are not
provided by this backend. Writable workspace storage also retains its host quota.
Do not treat the tmpfs and output bounds as limits on total resource consumption.
The [backend decision](decisions/0002-linux-command-isolation.md) records this
explicit deferral; untrusted resource-intensive work requires a deployment cgroup
with memory, CPU and PID budgets.

## Verification

Ordinary protocol, server and Node policy tests require no Linux sandbox or
database. Explicit Linux process acceptance checks live in
`test-support/isolation`; see its [README](../test-support/isolation/README.md).
They exercise real mounts, network denial, protected configuration, environment
filtering, loader-injection refusal, scratch-space exhaustion, cancellation,
deadlines, owner death, restart cleanup, live-owner preservation and PID reuse.
The server acceptance fixture uses the actual run gate and local provider with
mocked persistence, proving denied approvals/grants do not reach process startup.
