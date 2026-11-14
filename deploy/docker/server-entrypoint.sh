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
. "$(dirname "$0")/server-filestores.sh"
# Numeric container users have no passwd home. GraalJS needs a writable home
# for its native runtime cache; keep that disposable cache on the exec tmpfs.
mkdir -p /tmp/plowshare-runtime
# Native tokenizer files are packaged, but extraction needs a writable cache for UID 1000.
# Derive it from the configured data directory rather than assuming a writable home.
export DJL_CACHE_DIR="${DJL_CACHE_DIR:-${PLOWSHARE_DATA_DIR:?}/tokenizer-runtime}"
export OPT_OUT_TRACKING=true

# Optional Prometheus listener. Both values are required together: the
# endpoint is unauthenticated, so there is no default address or port, and a
# half-configured listener must stop the container rather than fall back to
# the public API port.
if [ -n "${PLOWSHARE_MANAGEMENT_PORT:-}" ] || [ -n "${PLOWSHARE_MANAGEMENT_BIND:-}" ]; then
    if [ -z "${PLOWSHARE_MANAGEMENT_PORT:-}" ] || [ -z "${PLOWSHARE_MANAGEMENT_BIND:-}" ]; then
        echo "Set PLOWSHARE_MANAGEMENT_PORT and PLOWSHARE_MANAGEMENT_BIND together, or neither." >&2
        exit 1
    fi
    set -- \
        "--management.server.port=$PLOWSHARE_MANAGEMENT_PORT" \
        "--management.server.address=$PLOWSHARE_MANAGEMENT_BIND" \
        "--management.endpoints.web.exposure.include=health,info,metrics,prometheus" \
        "$@"
fi

# Optional continuous flight recording, written under a persistent directory the
# operator mounts. Unset means no recording; the directory is never created here.
if [ -n "${PLOWSHARE_JFR_DIR:-}" ]; then
    test -d "$PLOWSHARE_JFR_DIR" && test -w "$PLOWSHARE_JFR_DIR" || {
        echo "PLOWSHARE_JFR_DIR must be an existing directory writable by container UID 1000." >&2
        exit 1
    }
    exec java -Duser.home=/tmp/plowshare-runtime \
        "-XX:StartFlightRecording=settings=/opt/plowshare/plowshare.jfc,filename=${PLOWSHARE_JFR_DIR}/plowshare-%t.jfr,maxage=6h,maxsize=512m,dumponexit=true" \
        -jar /opt/plowshare/server.jar "$@"
fi
exec java -Duser.home=/tmp/plowshare-runtime -jar /opt/plowshare/server.jar "$@"
