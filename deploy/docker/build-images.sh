#!/bin/sh
# Native container build entry point for a CI runner or an operator checkout.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"
./gradlew --no-daemon :plowshare-server:bootJar :extensions:search-searxng:bootJar
revision=${PLOWSHARE_SOURCE_REVISION:-$(git rev-parse HEAD)}
version=${PLOWSHARE_VERSION:-0.1.0}
docker build --build-arg "SOURCE_REVISION=$revision" \
    -f deploy/docker/server.Dockerfile -t "plowshare/server:$version" .
docker build --build-arg "SOURCE_REVISION=$revision" \
    -f deploy/docker/search-searxng.Dockerfile -t "plowshare/search-searxng:$version" .
