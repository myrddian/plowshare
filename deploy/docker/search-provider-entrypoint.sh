#!/bin/sh
set -eu
: "${SEARXNG_BASE_URL:?Set SEARXNG_BASE_URL to the existing SearXNG service URL or use the local search overlay.}"
case "$SEARXNG_BASE_URL" in
    http://*|https://*) ;;
    *) echo "SEARXNG_BASE_URL must start with http:// or https://." >&2; exit 1 ;;
esac
exec java -jar /opt/plowshare/search-provider.jar "$@"
