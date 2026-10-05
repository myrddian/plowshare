#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${PLOWSHARE_CI_SHARED_ROOT:?Use a shared directory visible to the isolated Docker daemon}"
: "${DOCKER_HOST:?Select the isolated build daemon}"
: "${PLOWSHARE_VERSION:?Supply the tested image tag}"
if docker ps --format '{{.Names}}' | rg -q '^plowshare-(server|postgres|searxng-provider)-1$'; then
  echo 'Refusing to smoke-test against the production Docker daemon.' >&2; exit 1
fi
fixture=$(mktemp -d "$PLOWSHARE_CI_SHARED_ROOT/smoke.XXXXXXXX")
export PLOWSHARE_STATE_ROOT="$fixture/state"
export PLOWSHARE_CI_FIXTURE_SCRIPT="$fixture/fixtures.mjs"
export PLOWSHARE_LISTEN_ADDRESS=0.0.0.0 PLOWSHARE_PUBLISHED_PORT=0
# This disposable fixture exercises a pre-provisioned administrator. A blank
# handle selects temporary first-run setup, which cannot register providers.
export PLOWSHARE_ADMIN_HANDLE=admin
export PLOWSHARE_SEARCH_LADDER=searxng SEARXNG_BASE_URL=http://fixtures:8080
project="plowshare-ci-$(basename "$fixture" | tr '[:upper:].' '[:lower:]-')"
mkdir -p build/ci-reports
compose() { docker compose --project-name "$project" -f deploy/docker/compose.yaml -f deploy/docker/compose.search.yaml -f deploy/docker/ci/compose.yaml "$@"; }
cleanup() {
  status=$?
  trap - EXIT
  compose logs --no-color 2>&1 | python3 scripts/ci/redact.py > build/ci-reports/docker-smoke.log || true
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  # PostgreSQL owns its bind directory as UID 999; clean it through the isolated daemon.
  docker run --rm --network none --user 0:0 -v "$fixture:/fixture" \
    node:26.7.0-bookworm@sha256:e929171d35b9df7773a3ec5b068e387fa109441dc90f91e6560af5d39b7e9bf1 \
    sh -c 'rm -rf /fixture/state' >/dev/null 2>&1 || true
  rm -rf "$fixture"
  exit "$status"
}
trap cleanup EXIT
cp scripts/ci/fixtures.mjs "$PLOWSHARE_CI_FIXTURE_SCRIPT"
python3 - <<'PY'
import os,pathlib,secrets,hashlib,shutil
state=pathlib.Path(os.environ['PLOWSHARE_STATE_ROOT'])
for name in ('data','config','secrets','postgres'): (state/name).mkdir(parents=True)
for name in ('database-password','admin-password','changed-password'):
 path=state/'secrets'/name;path.write_text(secrets.token_hex(32)+'\n');path.chmod(0o600)
tokenizer=state/'config'/'tokenizers'/'fixture.json'
tokenizer.parent.mkdir()
shutil.copyfile('plowshare-server/src/test/resources/tokenizers/fixture.json',tokenizer)
checksum=hashlib.sha256(tokenizer.read_bytes()).hexdigest()
(state/'config'/'application.yml').write_text('plowshare:\n  llm:\n    embedding-tokenizer:\n      file: /etc/plowshare/tokenizers/fixture.json\n      sha256: '+checksum+'\n')
(state/'config'/'models.env').write_text('LLM_BASE_URL=http://fixtures:8080/v1\nLLM_CHAT_MODEL=fixture-chat\nLLM_EMBEDDING_MODEL=fixture-embedding\nLLM_API_KEY=ci-fixture\nLLM_CHAT_SLOTS=2\nLLM_SWARM_SLOTS=1\n')
PY
chown -R 1000:1000 "$PLOWSHARE_STATE_ROOT"
compose config --quiet
compose up -d --no-build --wait --wait-timeout 240
port=$(compose port server 8091)
export PLOWSHARE_URL="http://${TESTCONTAINERS_HOST_OVERRIDE:-127.0.0.1}:${port##*:}"
export PLOWSHARE_SEARCH_PROVIDER_URL=http://searxng-provider:8086
export PLOWSHARE_PASSWORD_FILE="$PLOWSHARE_STATE_ROOT/secrets/admin-password"
export PLOWSHARE_NEXT_PASSWORD_FILE="$PLOWSHARE_STATE_ROOT/secrets/changed-password"
export PLOWSHARE_CI_EXPECT_FIXTURES=true
node scripts/ci/runtime-smoke.mjs --initial
expected=$(python3 -c 'import pathlib; print(len(list(pathlib.Path("plowshare-server/src/main/resources/db/migration").glob("V*__*.sql"))))')
sql="select count(*) from flyway_schema_history where success; select count(*) from flyway_schema_history where not success; select count(*) from search_providers; select count(*) from admins where handle='admin' and not must_change_password;"
before=$(compose exec -T postgres psql -U plowshare -d plowshare -Atc "$sql")
test "$before" = "$(printf '%s\n0\n1\n1' "$expected")"
compose exec -T server sh -c 'printf persistence-ok > /var/lib/plowshare/.ci-persistence'
compose up -d --no-build --force-recreate --wait --wait-timeout 240
after=$(compose exec -T postgres psql -U plowshare -d plowshare -Atc "$sql")
test "$before" = "$after"
test "$(compose exec -T server cat /var/lib/plowshare/.ci-persistence)" = persistence-ok
port=$(compose port server 8091)
export PLOWSHARE_URL="http://${TESTCONTAINERS_HOST_OVERRIDE:-127.0.0.1}:${port##*:}"
export PLOWSHARE_PASSWORD_FILE="$PLOWSHARE_NEXT_PASSWORD_FILE"
node scripts/ci/runtime-smoke.mjs
printf '{"status":"passed","migrations":%s,"persistence":true}\n' "$expected" > build/ci-reports/docker-smoke.json
