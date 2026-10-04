#!/bin/bash
set -euo pipefail
# Readiness becomes available only after startup runners (including account setup) finish.
exec 3<>/dev/tcp/127.0.0.1/${PLOWSHARE_PORT:-8091}
printf 'GET /ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
read -r protocol status rest <&3
[[ "$status" = 204 ]]
