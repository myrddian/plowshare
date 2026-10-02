#!/bin/sh
# Run as the deployment user, not root. Existing state and credentials survive.
set -eu
state=${1:-/srv/plowshare}
case "$state" in /*) ;; *) echo "Use an absolute state directory." >&2; exit 1 ;; esac
test "$(id -u)" = 1000 || {
    echo "This package runs as UID 1000; prepare its bind mounts as that deployment user." >&2
    exit 1
}
umask 077
mkdir -p "$state/data" "$state/config" "$state/secrets"
for name in database-password admin-password; do
    if [ ! -e "$state/secrets/$name" ]; then
        # noclobber also protects against concurrent initializers.
        (set -C; { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; printf '\n'; } > "$state/secrets/$name")
    fi
    test -s "$state/secrets/$name" || {
        echo "$state/secrets/$name is empty; supply a credential before deploying." >&2
        exit 1
    }
    chmod 600 "$state/secrets/$name"
done
if [ ! -e "$state/config/models.env" ]; then
    (set -C; printf '%s\n' '# Set provider endpoints and API keys here; this file stays outside Git.' > "$state/config/models.env")
fi
echo "Prepared $state. Initial admin password is in secrets/admin-password; it must be changed at first login."
