# Installing the packaged clients and server

The initial native target is **macOS Apple Silicon**. The `plowshare` executable contains
the CLI, stdio MCP server, TUI and Node runtime: no separate Node installation is required.
Desktop bundles Electron and requires macOS 13 or later; it needs neither Node nor Java
installed separately. The server archive requires
**Java 21** and PostgreSQL. These are separate installations: clients can connect to an
already running remote server.

## Build and verify

From a checkout on macOS Apple Silicon, with Java 21, Node 22.12+, pnpm, Python 3,
curl, tar and codesign available:

```sh
./gradlew --no-daemon clientDistributions serverDistribution
./gradlew --no-daemon distributionCheck
./gradlew --no-daemon desktopDistributionCheck
```

The last command opens an isolated native application on a macOS Apple Silicon host.
Ordinary `check` stays headless. Frozen pnpm lockfiles and the pinned Electron/packager
versions determine dependencies. Downloads require network access on the first build.
The desktop uses [Electron Packager](https://packages.electronjs.org/packager/v20.0.4/interfaces/Options.html).
The terminal executable uses [Node single-executable applications](https://nodejs.org/api/single-executable-applications.html),
with the official Node 26.7.0 runtime pinned by archive SHA-256 in
`scripts/distribution-runtime.json`. The verified runtime is cached under `build/runtime/`.
Using the official runtime avoids external package-manager libraries on users' machines.
Archives and SHA-256 sidecars are written to `build/distributions/`; staging is under
`build/package/`. Only compiled application payloads and the executable server jar are staged.
Profiles, credentials, deployment configuration and project data are excluded. Each archive
contains `build-info.json` with source revision, tracked-change status and runtime requirements.
Headless tar/gzip metadata is normalized, so unchanged payloads produce identical archives.
Native signing and server jar metadata may vary between rebuilds; checksums identify the
specific artifact. Run release builds from a clean checkout.

## Single executable: CLI, TUI and MCP

`build/distributions/plowshare-0.1.0-darwin-arm64` is the complete executable. Verify its
SHA-256 sidecar, copy it to a directory on PATH as `plowshare`, and make it executable.
It can be moved on its own: there are no sibling runtime, JavaScript, JSON or WASM files
to install. Runtime and dependency notices are embedded; `plowshare --licenses` prints them.
`plowshare --version` reports its source revision and embedded runtime version.

```sh
shasum -a 256 -c plowshare-0.1.0-darwin-arm64.sha256
mkdir -p "$HOME/.local/bin"
cp plowshare-0.1.0-darwin-arm64 "$HOME/.local/bin/plowshare"
chmod +x "$HOME/.local/bin/plowshare"
export PATH="$HOME/.local/bin:$PATH"
plowshare --version
plowshare --server https://plowshare.example.com login
plowshare talk --url http://127.0.0.1:8091
```

`plowshare [CLI command]` runs the CLI, `plowshare talk` opens the TUI, and
`plowshare mcp` runs stdio MCP. Each mode supports `--help`; MCP help goes to stderr.
`plowshare cli` is an explicit alias for CLI mode. Select its server with
`plowshare cli --server ORIGIN <command>` or `PLOWSHARE_URL`; `--url` remains
an alias. Online CLI commands require an explicit HTTP(S) origin and have no
default endpoint. Help, version and offline validation need no server. The executable
retains the caller's working directory for project discovery, and stores credentials/configuration outside
the installation. No source checkout, TypeScript, pnpm, Node, Bun or Java is needed
to run the clients. Java and PostgreSQL are server requirements only.

The convenience archive also includes documentation, license files and legacy-name symlinks.
Verify it, unpack it into a versioned directory, and add its `bin` directory to PATH:

```sh
shasum -a 256 -c plowshare-clients-0.1.0-darwin-arm64.tar.gz.sha256
tar -xzf plowshare-clients-0.1.0-darwin-arm64.tar.gz
export PATH="$PWD/plowshare-clients-0.1.0-darwin-arm64/bin:$PATH"
plowshare --help
```

The symlinks `plowshare-cli`, `plowshare-talk` and `plowshare-mcp` select their corresponding
modes of the same executable. The earlier portable JavaScript archive requiring external
Node is superseded by this native package. Intel, Linux and Windows native clients are
follow-up platform work; this executable is specifically macOS Apple Silicon.
It has a verified local ad-hoc signature; Developer ID signing and notarization remain
public-release work, as for the desktop below.

For an MCP host, configure stdio with an **absolute path** to `plowshare` and arguments
`mcp`, `--url`, followed by the server URL. The archive's `bin/plowshare-mcp` symlink also
works with `--url` and the URL. MCP stdout contains
only protocol messages; diagnostics and help go to stderr. Do not put passwords in host
configuration. Sign in using the CLI or desktop once for the same server origin.

All clients share saved sessions under `~/.config/plowshare/credentials` by default;
`XDG_CONFIG_HOME` or `PLOWSHARE_CONFIG_DIR` can select another location. Existing saved
sessions continue to work. `PLOWSHARE_URL` selects the server for headless clients.
Account/password environment overrides remain supported for automation. Credential
refresh and WS tickets use the authentication bootstrap; operational requests use WS.

## Desktop

Verify and extract `plowshare-desktop-0.1.0-darwin-arm64.tar.gz`, then copy `Plowshare.app`
to `/Applications` or `~/Applications`. Open it and select the server/account or sign in.
Saved login details are shared with the headless clients. The native Chromium profile lives
under `~/Library/Application Support/Plowshare`; desktop connection/project/job preferences
live in the existing Plowshare configuration directory, outside the application bundle.
`PLOWSHARE_DESKTOP_PROFILE` and `PLOWSHARE_DESKTOP_CONFIG` retain their isolated-profile
overrides for testing.

This initial artifact has a verified local ad-hoc signature. It has no Developer ID signature or Apple
notarization. macOS may require explicit approval in System Settings for an application
downloaded from elsewhere. Public release signing/notarization and Intel/Linux/Windows
desktop packages are follow-up release work; they have not been validated by this package.
See [Electron's signing guidance](https://www.electronjs.org/docs/latest/tutorial/code-signing) for the public release process.

## Server

For a new Debian 13 or 12 Docker host, `deploy/docker/bootstrap-host.sh` installs
Docker Engine, Buildx and Compose from Docker's official Debian repository. Run
the reviewed script as root, passing the existing non-root deployment username:

```sh
sudo sh deploy/docker/bootstrap-host.sh YOUR_DEPLOYMENT_USER
```

The script creates `/srv/plowshare/data`, `/srv/plowshare/config` and
`/srv/plowshare/deployment` on ordinary host storage, preserving existing directory
ownership. It does not partition or format disks. Mount a dedicated data disk at
`/srv/plowshare` before running it when using a separate disk. It grants the account
Docker group membership, which provides root-equivalent host control; reconnect
SSH before using that membership. An existing Docker repository file is left
unchanged and requires manual review. The script verifies installation with
`hello-world`; it prepares the host and does not deploy Plowshare containers.

For persistent Plowshare/PostgreSQL containers with a bundled Java runtime,
see [the Docker deployment guide](../deploy/docker/README.md). Search is layered:
the SearXNG adapter can connect to an existing service, while a local SearXNG
engine requires an explicit extra Compose file. The default deployment does
not start a second search engine.

Verify and extract `plowshare-server-0.1.0.tar.gz`. Supply deployment configuration outside
the installation, typically `~/.config/plowshare-server/application.yml`, containing database,
model/provider and account settings. Existing `application-local.yml` can be used by setting
`SPRING_PROFILES_ACTIVE=local`. Set `PLOWSHARE_SERVER_CONFIG` to the directory containing
your configuration; never put credentials inside a distributable archive.

```sh
export PLOWSHARE_SERVER_CONFIG="$HOME/.config/plowshare-server"
export PLOWSHARE_DB_URL=jdbc:postgresql://localhost:5432/plowshare
plowshare-server-0.1.0/bin/plowshare-server
```

The packaged launcher runs the executable jar without Gradle or a source checkout.
It uses `~/.local/share/plowshare-server` as its working/data directory (override with
`PLOWSHARE_SERVER_DATA`) and an immutable content-addressed jar in
`~/.cache/plowshare/server` (override with `PLOWSHARE_RUNTIME_DIR`). XDG data/cache overrides
are supported. This preserves a running server's classes when an installed artifact is
replaced. Existing deployments should explicitly preserve their current data directory,
PostgreSQL database and provider configuration when switching launchers. Restart for an
upgrade; bootstrap/account seeding and migrations retain existing server behavior.

## Migration and acceptance limits

Keep versioned install directories. Point PATH/MCP host settings at the selected version,
and restart the desktop/client process after an upgrade. Saved credentials and project data
remain outside installation directories. The repository `bin/` scripts continue to support
development builds, and the Java client remains available during migration. No Java consumer
is retired by this packaging change.
The repository's `bin/plowshare` remains the developer server launcher. The native client
uses `plowshare` on PATH; the separately packaged server uses `plowshare-server`.

Packaging checks use real local HTTP/WS protocol
fixtures from freshly unpacked artifacts, including shared login, CLI, MCP, TUI and native
desktop behavior. They do not establish model quality or orchestration-builder acceptance.
Live model-backed builder completion remains open and deferred.
