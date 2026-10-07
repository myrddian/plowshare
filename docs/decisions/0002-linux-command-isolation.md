# 0002: Minimal Linux command isolation

Status: accepted for the first optional backend.

## Context

Path fences alone constrain file tools and command working directories; they do
not constrain an admitted command's filesystem, network or descendants. The
execution boundary must retain existing grants, approval, ownership and delivery
rules and fail closed when the requested backend is unavailable.

## Decision

Use bubblewrap on Linux with an empty mount tree, explicitly trusted read-only
runtime mounts, workspace reads, writable areas, masked protected entries,
private network/PID/user namespaces and disabled nested user namespaces. No
unisolated fallback is permitted. Operators opt in per executing environment;
macOS and Windows support is deferred. Containers were considered, but introduce
image/daemon authority and a separate deployment lifecycle for this narrow
command capability. Bubblewrap admits the existing argv and stdin contract
without a durable second runtime.

The supported resource bounds are command deadline, bounded input/output, an
8 MiB root tmpfs, a 64 MiB temporary tmpfs, bounded policy inspection and launch
arguments. CPU, total memory, PID count and workspace disk quotas are explicitly
deferred to deployment cgroups/filesystem quotas. Bubblewrap does not provide
those quotas. Production untrusted work must execute inside an operator-owned
cgroup with finite memory and PID limits and a CPU budget. Per-command cgroup
allocation needs an explicit delegated hierarchy, quota policy and lifecycle
contract; this backend neither acquires privileged cgroup authority nor treats
an installation-wide limit as a per-command guarantee.

Cancellation, deadlines, normal exit and owner death destroy the namespace,
including detached descendants. Private launch files use a dedicated mode-0700
installation directory. Next-launch recovery identifies dead owners by boot ID,
PID and start ticks, skips live owners and deletes only known files without
following links or removing trees. Each PID namespace requires its own directory.

## Consequences and acceptance

See [the supported boundary and configuration](../command-isolation.md).
Visible symlinks/hard links/special files and Git metadata are unsupported;
workspace and runtime topology must not be changed concurrently on the host.
Runtime mount content is trusted operator authority. The boundary cannot protect
against its host operator or kernel vulnerabilities.

The explicit Linux Java and Node fixtures verify real filesystem/network denial,
temporary-space bounds, startup refusal, cancellation/deadlines, owner death and
restart recovery including live-owner preservation and PID reuse. A separate
server fixture verifies admission and approval consumption through the real run
provider, with persistence mocked. Ordinary policy/transport tests require no
Linux backend or database. Resource quota claims require separate deployment
verification; passing namespace fixtures is not evidence of CPU/RAM/PID quotas.
