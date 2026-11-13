#!/bin/sh
set -eu
if [ -n "${PLOWSHARE_TOKEN_FILE:-}" ]; then
    PLOWSHARE_TOKEN=$(cat "$PLOWSHARE_TOKEN_FILE")
    export PLOWSHARE_TOKEN
fi
if [ -n "${A2A_CALLER_TOKEN_FILE:-}" ]; then
    A2A_CALLER_TOKEN=$(cat "$A2A_CALLER_TOKEN_FILE")
    export A2A_CALLER_TOKEN
fi
if [ -z "${PLOWSHARE_TOKEN:-}" ] || [ -z "${A2A_CALLER_TOKEN:-}" ]; then
    echo 'Provide explicit Plowshare service and external caller credentials through environment or secret files.' >&2
    exit 78
fi
if [ "$#" -lt 1 ] || [ "$#" -gt 3 ] || [ ! -r "$1" ]; then
    echo 'Provide a readable A2A Relay configuration and optional owner/rotate-config command.' >&2
    exit 78
fi
exec java -cp '/opt/plowshare/lib/*' io.aeyer.plowshare.a2a.Main "$@"
