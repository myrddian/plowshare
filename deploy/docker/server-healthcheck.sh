#!/bin/bash
set -euo pipefail
# AuthConfig writes this after ApplicationReadyEvent. Test the real auth gate.
IFS= read -r credential < "${PLOWSHARE_AUTH_TOKEN_FILE:-/var/lib/plowshare/console-token}"
[[ -n "$credential" ]]
exec 3<>/dev/tcp/127.0.0.1/${PLOWSHARE_PORT:-8091}
printf 'GET /v1/auth/session HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer %s\r\nConnection: close\r\n\r\n' "$credential" >&3
read -r protocol status rest <&3
[[ "$status" = 204 ]]
