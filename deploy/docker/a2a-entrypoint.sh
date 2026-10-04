#!/bin/sh
set -eu
if [ -n "${PLOWSHARE_TOKEN_FILE:-}" ]; then
    PLOWSHARE_TOKEN=$(cat "$PLOWSHARE_TOKEN_FILE")
    export PLOWSHARE_TOKEN
fi
if [ -n "${PLOWSHARE_PASSWORD_FILE:-}" ]; then
    PLOWSHARE_PASSWORD=$(cat "$PLOWSHARE_PASSWORD_FILE")
    export PLOWSHARE_PASSWORD
fi
if [ -n "${A2A_CALLER_TOKEN_FILE:-}" ]; then
    A2A_CALLER_TOKEN=$(cat "$A2A_CALLER_TOKEN_FILE")
    export A2A_CALLER_TOKEN
fi
if [ -z "${PLOWSHARE_TOKEN:-}" ]; then
    : "${PLOWSHARE_HANDLE:?Set the existing Plowshare account handle.}"
    : "${PLOWSHARE_PASSWORD:?Provide the account password through a secret file.}"
fi
: "${A2A_CALLER_TOKEN:?Provide a separate external caller bearer token.}"
test "$#" -eq 1 && test -r "$1" || { echo "Provide one readable A2A configuration file." >&2; exit 1; }
exec java -cp '/opt/plowshare/lib/*' io.aeyer.plowshare.a2a.Main "$@"
