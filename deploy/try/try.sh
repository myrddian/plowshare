#!/bin/sh
# One-command local trial. Runtime settings stay outside the release bundle.
set -eu
here=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
state=${PLOWSHARE_TRY_STATE:-${XDG_DATA_HOME:-$HOME/.local/share}/plowshare-try}
action=${1:-start}
case "$action" in
  help|--help|-h)
    echo 'Usage: ./try.sh [start|configure|stop|status]'
    echo 'Set PLOWSHARE_TRY_STATE to select an external settings directory.'
    echo 'First start asks for model settings and an administrator login.'
    echo 'Requires Docker with Compose 2.30+, curl and a SHA-256 utility.'
    exit 0 ;;
  start|configure|stop|status) ;;
  *) echo 'Choose start, configure, stop or status; see --help.' >&2; exit 2 ;;
esac
case "$state" in /*) ;; *) echo 'PLOWSHARE_TRY_STATE must be absolute.' >&2; exit 2 ;; esac
case "$state/" in "$here/"*) echo 'Keep trial settings outside the bundle.' >&2; exit 2 ;; esac
test -f "$here/images.env" || { echo 'Use a released bundle with images.env.' >&2; exit 2; }
command -v docker >/dev/null || { echo 'Install Docker with Compose first.' >&2; exit 2; }
version=$(docker compose version --short)
if ! printf '%s\n' "$version" | awk -F. '{sub(/^v/, "", $1); exit !($1 > 2 || ($1 == 2 && $2 >= 30))}'; then
  echo 'Docker Compose 2.30+ is required.' >&2; exit 2
fi
# Prevent inherited deployment variables from overriding the released image/port.
unset PLOWSHARE_SERVER_IMAGE COMPOSE_FILE COMPOSE_PROJECT_NAME
image=$(sed -n 's/^PLOWSHARE_SERVER_IMAGE=//p' "$here/images.env")
if ! printf '%s\n' "$image" | LC_ALL=C grep -Eq '^ghcr\.io/myrddian/plowshare-server@sha256:[0-9a-f]{64}$'; then
  echo 'Use a released bundle with an immutable GHCR server digest.' >&2; exit 2
fi
umask 077
mkdir -p "$state/config"
state=$(CDPATH= cd -P -- "$state" && pwd -P)
case "$state/" in "$here/"*) echo 'Keep trial settings outside the bundle.' >&2; exit 2 ;; esac
# Mounted application configuration must be traversable by container UID 1000.
# Provider/admin secrets are separate owner-readable env files, never in config/.
chmod 755 "$state/config"
export PLOWSHARE_TRY_STATE="$state"
# Stable per-settings-directory project: different trials cannot select production
# volumes. stop preserves every volume and credential; start never deletes them.
project="plowshare-try-$(printf '%s' "$state" | cksum | cut -d ' ' -f 1)"
compose() { docker compose --project-name "$project" --env-file "$here/images.env" \
  --env-file "$state/compose.env" -f "$here/compose.yaml" "$@"; }
ask() {
  name=$1; prompt=$2
  eval 'value=${'"$name"':-}'
  if [ -z "$value" ]; then
    test -t 0 || { echo "Supply $name for noninteractive setup." >&2; exit 2; }
    printf '%s: ' "$prompt" >&2
    IFS= read -r value
  fi
  test -n "$value" || { echo "$name is required." >&2; exit 2; }
  # Only the fixed variable name is evaluated, never the user's value.
  export "$name=$value"
}
secret() {
  name=$1; prompt=$2
  eval 'value=${'"$name"':-}'
  if [ -z "$value" ] && [ -t 0 ]; then
    printf '%s: ' "$prompt" >&2
    saved_tty=$(stty -g)
    trap 'stty "$saved_tty"' EXIT HUP INT TERM
    stty -echo
    IFS= read -r value
    stty "$saved_tty"
    trap - EXIT HUP INT TERM
    printf '\n' >&2
  fi
  export "$name=$value"
}
if { [ "$action" = start ] || [ "$action" = configure ]; } && [ ! -f "$state/compose.env" ]; then
  test ! -e "$state/server.env" && test ! -e "$state/database.env" || {
    echo 'Incomplete existing setup: restore compose.env; settings were not overwritten.' >&2; exit 2;
  }
  ask LLM_BASE_URL 'OpenAI-compatible URL reachable from Docker (including API path)'
  case "$LLM_BASE_URL" in http://*|https://*) ;; *) echo 'Use an explicit HTTP(S) model URL.' >&2; exit 2 ;; esac
  case "$LLM_BASE_URL" in *'@'*|*'?'*|*'#'*|*[[:space:]]*) echo 'Keep credentials in the API-key field.' >&2; exit 2 ;; esac
  ask LLM_CHAT_MODEL 'Served chat model ID'
  ask LLM_EMBEDDING_MODEL 'Served embedding model ID (legacy trial requires 768 dimensions)'
  secret LLM_API_KEY 'Model API key (Enter if the endpoint needs none)'
  ask PLOWSHARE_ADMIN_HANDLE 'Administrator username'
  case "$PLOWSHARE_ADMIN_HANDLE" in *[!a-zA-Z0-9_.-]*) echo 'Use a plain administrator username.' >&2; exit 2 ;; esac
  secret PLOWSHARE_ADMIN_PASSWORD 'Initial administrator password (at least 12 characters)'
  test ${#PLOWSHARE_ADMIN_PASSWORD} -ge 12 || { echo 'Choose a password of at least 12 characters.' >&2; exit 2; }
  PLOWSHARE_TRY_PORT=${PLOWSHARE_TRY_PORT:-8091}
  case "$PLOWSHARE_TRY_PORT" in ''|*[!0-9]*) echo 'Choose a numeric local port.' >&2; exit 2 ;; esac
  test "$PLOWSHARE_TRY_PORT" -ge 1 && test "$PLOWSHARE_TRY_PORT" -le 65535 || { echo 'Local port must be 1–65535.' >&2; exit 2; }
  # Environment files use Compose's raw format: quotes and dollars in keys and
  # passwords stay literal. Newlines are not valid in this format.
  for value in "$LLM_BASE_URL" "$LLM_CHAT_MODEL" "$LLM_EMBEDDING_MODEL" "$LLM_API_KEY" "$PLOWSHARE_ADMIN_PASSWORD"; do
    case "$value" in *'
'*|*'
'*) echo 'Settings must be single-line values.' >&2; exit 2 ;; esac
  done
  password=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  (set -C; printf 'POSTGRES_PASSWORD=%s\n' "$password" > "$state/database.env")
  (set -C; printf '%s\n' "PLOWSHARE_DB_PASSWORD=$password" \
    "PLOWSHARE_ADMIN_HANDLE=$PLOWSHARE_ADMIN_HANDLE" "PLOWSHARE_ADMIN_PASSWORD=$PLOWSHARE_ADMIN_PASSWORD" \
    "LLM_BASE_URL=$LLM_BASE_URL" "LLM_CHAT_MODEL=$LLM_CHAT_MODEL" "LLM_EMBEDDING_MODEL=$LLM_EMBEDDING_MODEL" \
    "LLM_API_KEY=$LLM_API_KEY" 'LLM_CHAT_SLOTS=1' 'LLM_EMBEDDING_SLOTS=1' 'LLM_SWARM_SLOTS=0' > "$state/server.env")
  (set -C; printf 'PLOWSHARE_TRY_PORT=%s\n' "$PLOWSHARE_TRY_PORT" > "$state/compose.env")
fi
test -f "$state/compose.env" && test -f "$state/server.env" && test -f "$state/database.env" || {
  echo 'Run start once to configure this trial.' >&2; exit 2;
}
for file in compose.env server.env database.env; do
  test ! -L "$state/$file" || { echo 'Settings files must not be symlinks.' >&2; exit 2; }
  chmod 600 "$state/$file"
done
compose config --quiet
case "$action" in
  configure) echo 'Trial configured; run ./try.sh to start it.' ;;
  start)
    compose pull
    compose run --rm -T init-state
    compose up -d --no-build --wait --wait-timeout 300
    port=$(sed -n 's/^PLOWSHARE_TRY_PORT=//p' "$state/compose.env")
    echo "Open http://127.0.0.1:$port and log in with your chosen administrator account."
    echo 'The console requires you to change the initial password.'
    echo 'Add your matching embedding tokenizer to enable document/memory embedding; see README.md.' ;;
  stop) compose stop ;;
  status) compose ps ;;
esac
