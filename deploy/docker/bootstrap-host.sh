#!/bin/sh
# Install the Docker host prerequisites; this does not deploy Plowshare services.
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root: sudo sh bootstrap-host.sh YOUR_DEPLOYMENT_USER" >&2
    exit 1
fi
deployment_user=${1:?Pass the existing non-root deployment username}
deployment_uid=$(id -u "$deployment_user")
deployment_group=$(id -gn "$deployment_user")
if [ "$deployment_uid" -eq 0 ]; then
    echo "Choose a non-root deployment account." >&2
    exit 1
fi
. /etc/os-release
if [ "$ID" != debian ] || { [ "$VERSION_CODENAME" != trixie ] && [ "$VERSION_CODENAME" != bookworm ]; }; then
    echo "This bootstrap supports Debian 13 (trixie) and 12 (bookworm)." >&2
    exit 1
fi
architecture=$(dpkg --print-architecture)
case "$architecture" in
    amd64|arm64) ;;
    *) echo "Plowshare container targets are amd64 and arm64." >&2; exit 1 ;;
esac

# Preserve an operator's existing Docker repository configuration.
if [ -e /etc/apt/sources.list.d/docker.list ] || [ -e /etc/apt/sources.list.d/docker.sources ]; then
    echo "A Docker repository file already exists. Use Docker's Debian installation guide to update it; no file was changed." >&2
    exit 1
fi
apt-get update
apt-get install -y ca-certificates curl
install -d -m 0755 /etc/apt/keyrings
temporary=$(mktemp)
trap 'rm -f "$temporary"' EXIT HUP INT TERM
curl --fail --silent --show-error --location https://download.docker.com/linux/debian/gpg -o "$temporary"
install -m 0644 "$temporary" /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/debian
Suites: $VERSION_CODENAME
Components: stable
Architectures: $architecture
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker

# This account administers Docker, which grants root-equivalent host access.
usermod -aG docker "$deployment_user"

# Ordinary disk paths persist across container replacement. Existing directory
# ownership is preserved, and no disks are partitioned or formatted here.
for directory in /srv/plowshare /srv/plowshare/data /srv/plowshare/config /srv/plowshare/deployment; do
    if [ ! -e "$directory" ]; then
        install -d -m 0750 -o "$deployment_user" -g "$deployment_group" "$directory"
    elif [ ! -d "$directory" ]; then
        echo "$directory exists but is not a directory." >&2
        exit 1
    fi
done

docker version
docker compose version
docker run --rm hello-world
echo "Docker is ready. Reconnect SSH to activate $deployment_user's Docker group membership."
echo "Persistent Plowshare files will use /srv/plowshare/data."
