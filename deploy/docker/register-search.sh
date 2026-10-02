#!/bin/sh
# Existing operator bootstrap API; user/model operations continue to use WS.
set -eu
server=${PLOWSHARE_URL:-http://127.0.0.1:8091}
state=${PLOWSHARE_STATE_ROOT:-/srv/plowshare}
IFS= read -r credential < "$state/data/console-token"
test -n "$credential"
umask 077
header=$(mktemp)
response=$(mktemp)
trap 'rm -f "$header" "$response"' EXIT HUP INT TERM
printf 'Authorization: Bearer %s\n' "$credential" > "$header"
status=$(curl -sS --max-time 20 -o "$response" -w '%{http_code}' \
    -H "@$header" -H 'Content-Type: application/json' \
    --data '{"baseUrl":"http://searxng-provider:8086"}' \
    "$server/v1/search/providers")
case "$status" in
    2??) echo "Registered the SearXNG adapter. Set PLOWSHARE_SEARCH_LADDER=searxng in the deployment environment." ;;
    *) echo "Search registration failed (HTTP $status)." >&2; head -c 2000 "$response" >&2; exit 1 ;;
esac
