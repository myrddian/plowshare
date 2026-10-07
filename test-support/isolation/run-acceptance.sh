#!/bin/sh
# Explicit process fixtures only; no server boot, database, credentials or privileged mounts.
set -eu
if [ "$(id -u)" = 0 ]; then
  echo 'Run acceptance as an ordinary account' >&2
  exit 1
fi
/usr/bin/bwrap --version
java -version
/usr/bin/node --version
set -- /usr/bin/bwrap /bin/sh /usr /bin /lib
if [ -d /lib64 ]; then
  set -- "$@" /lib64
fi
java -cp plowshare-protocol/build/classes/java/main:plowshare-protocol/build/classes/java/test \
  io.aeyer.plowshare.protocol.BubblewrapAcceptance "$@"
/usr/bin/node test-support/isolation/node-acceptance.mjs "$@"
java -XX:+EnableDynamicAgentLoading \
  -cp 'plowshare-server/build/isolation-acceptance/classes:plowshare-server/build/isolation-acceptance/lib/*' \
  io.aeyer.plowshare.server.agents.CommandIsolationAcceptance "$@"
