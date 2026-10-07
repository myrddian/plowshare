# Explicit Linux process acceptance

The container uses Java 21 and Node 22. These tests start real processes, never PostgreSQL. The server fixture exercises run admission/provider code
without starting an application server, with persistence mocked.
Compile the Java test classes and Node SDK first:

```sh
./gradlew :plowshare-protocol:testClasses :plowshare-client-node:nodeBuild :plowshare-server:commandIsolationAcceptanceBundle
docker build -t plowshare-bubblewrap-acceptance:local test-support/isolation
```

On a Linux host with namespace support, run `BubblewrapAcceptance` with the
protocol main/test class directories on the Java classpath, or run
`node test-support/isolation/node-acceptance.mjs`. Each takes the absolute
bubblewrap path, trusted POSIX launcher path and runtime directories as arguments.

Docker's own namespace/masked-proc restrictions need explicit relaxation for this
disposable nested-sandbox fixture. Tests run as UID 1000 without added capabilities.
These flags are test infrastructure, not
production server installation instructions. The repository is read-only, with
no host socket, secrets or host PID/network access mounted:

```sh
docker run --rm --user 1000:1000 --security-opt seccomp=unconfined \
  --security-opt systempaths=unconfined \
  --mount "type=bind,src=$PWD,dst=/source,readonly" \
  plowshare-bubblewrap-acceptance:local \
  java -cp plowshare-protocol/build/classes/java/main:plowshare-protocol/build/classes/java/test \
  io.aeyer.plowshare.protocol.BubblewrapAcceptance /usr/bin/bwrap /bin/sh /usr /bin /lib

docker run --rm --user 1000:1000 --security-opt seccomp=unconfined \
  --security-opt systempaths=unconfined \
  --mount "type=bind,src=$PWD,dst=/source,readonly" \
  plowshare-bubblewrap-acceptance:local \
  node test-support/isolation/node-acceptance.mjs /usr/bin/bwrap /bin/sh /usr /bin /lib
```

On x86 add `/lib64` when the distribution uses that loader path. A successful
process test prints its checks and exits zero. Startup refusals are failures of
the fixture: user code must never run unisolated to make the tests pass.

The bundle copies only public classes and dependency jars for the server fixture:

```sh
docker run --rm --user 1000:1000 --security-opt seccomp=unconfined \
  --security-opt systempaths=unconfined \
  --mount "type=bind,src=$PWD,dst=/source,readonly" \
  plowshare-bubblewrap-acceptance:local \
  java -cp 'plowshare-server/build/isolation-acceptance/classes:plowshare-server/build/isolation-acceptance/lib/*' \
  io.aeyer.plowshare.server.agents.CommandIsolationAcceptance /usr/bin/bwrap /bin/sh /usr /bin /lib
```

Fixtures create their own mode-0700 scratch directories outside workspace/runtime
mounts. Production configuration must supply its dedicated directory explicitly.
Owner death must stop detached descendants; the next launch must remove stale
private policy files while retaining live owners and unrelated entries.

`sh test-support/isolation/run-acceptance.sh` runs all three fixtures and selects
`/lib64` when present. The executing account must be non-root. For target-machine
verification, the same disposable container can have finite deployment budgets
(e.g. Docker `--memory=1g --pids-limit=128 --cpus=1`). Those are container-wide
budgets, not per-command quotas implemented by bubblewrap.

On Linux Docker hosts with AppArmor enabled, the default container profile may
also deny bubblewrap's mount propagation (`Failed to make / slave: Permission
denied`). This disposable fixture additionally needs
`--security-opt apparmor=unconfined` on those hosts. Do not weaken a live server's
profile to run acceptance: production needs a deliberately reviewed deployment
policy. The fixture mounts only public acceptance artifacts and runs as a
non-root account without added capabilities, host secrets or Docker sockets.

After committing and compiling the source, create a portable public-only bundle:

```sh
python3 test-support/isolation/bundle.py /tmp/plowshare-isolation-acceptance.tgz
```

The archive includes Java classes/dependency jars, both compiled public SDK
packages and fixtures, plus the source revision. It excludes operator files and
package caches, strips host metadata, and refuses to replace an existing output.
On the target, extract it into a private temporary directory and mount that
path read-only at `/source` in the disposable acceptance container.
