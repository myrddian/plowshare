#!/bin/sh
set -eu

# Compose mounts these as files; they never become image layers or command args.
if [ -n "${PLOWSHARE_DB_PASSWORD_FILE:-}" ]; then
    PLOWSHARE_DB_PASSWORD=$(cat "$PLOWSHARE_DB_PASSWORD_FILE")
    export PLOWSHARE_DB_PASSWORD
fi
if [ -n "${PLOWSHARE_ADMIN_PASSWORD_FILE:-}" ]; then
    PLOWSHARE_ADMIN_PASSWORD=$(cat "$PLOWSHARE_ADMIN_PASSWORD_FILE")
    export PLOWSHARE_ADMIN_PASSWORD
fi
: "${PLOWSHARE_DB_PASSWORD:?Provide a database password file or environment variable.}"
: "${PLOWSHARE_DATA_DIR:?Set a persistent server data directory.}"
test -d "$PLOWSHARE_DATA_DIR" && test -w "$PLOWSHARE_DATA_DIR" || {
    echo "PLOWSHARE_DATA_DIR must be an existing directory writable by container UID 1000." >&2
    exit 1
}
# Numeric container users have no passwd home. GraalJS needs a writable home
# for its native runtime cache; keep that disposable cache on the exec tmpfs.
mkdir -p /tmp/plowshare-runtime
# Native tokenizer files are packaged, but extraction needs a writable cache for UID 1000.
# Derive it from the configured data directory rather than assuming a writable home.
export DJL_CACHE_DIR="${DJL_CACHE_DIR:-${PLOWSHARE_DATA_DIR:?}/tokenizer-runtime}"
export OPT_OUT_TRACKING=true
exec java -Duser.home=/tmp/plowshare-runtime -jar /opt/plowshare/server.jar "$@"
