#!/bin/sh
# Sourced by the image entrypoint. Private Spring configuration and an explicit
# registry keep precedence over this image-only default. Generate it once so
# later starts cannot replace operator edits or widen grants.
configure_default_filestores() {
    if [ -n "${PLOWSHARE_FILESTORES_CONFIG_FILE:-}" ]; then
        return
    fi
    # Spring treats an exported empty value as present. Clear it so the packaged
    # placeholder can select the image default, without overriding a Spring file.
    unset PLOWSHARE_FILESTORES_CONFIG_FILE
    : "${PLOWSHARE_DATA_DIR:?Set a persistent server data directory.}"
    case "$PLOWSHARE_DATA_DIR" in
        /*) ;;
        *) echo "PLOWSHARE_DATA_DIR must be absolute." >&2; return 1 ;;
    esac
    PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE="$PLOWSHARE_DATA_DIR/filestore.js"
    export PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE
    if [ -e "$PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE" ] || [ -L "$PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE" ]; then
        test -f "$PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE" &&
            test ! -L "$PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE" &&
            test -r "$PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE" || {
            echo "The persistent FileStore registry must be a readable regular file." >&2
            return 1
        }
        return
    fi
    : "${PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY:?Set the server workspace directory before initializing FileStores.}"
    case "$PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY" in
        /*) ;;
        *) echo "PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY must be absolute." >&2; return 1 ;;
    esac
    for filestore_directory in "$PLOWSHARE_DATA_DIR" "$PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY"; do
        test -d "$filestore_directory" && test ! -L "$filestore_directory" && test -w "$filestore_directory" || {
            echo "FileStore initialization requires existing writable data and workspace directories without links." >&2
            return 1
        }
    done
    filestore_data=$(cd "$PLOWSHARE_DATA_DIR" && pwd -P) || return 1
    filestore_workspace=$(cd "$PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY" && pwd -P) || return 1
    case "$filestore_workspace/" in
        "$filestore_data/"*) echo "Application FileStores must be outside private server data." >&2; return 1 ;;
    esac
    case "$filestore_data/" in
        "$filestore_workspace/applications/"*) echo "Application FileStores must not contain private server data." >&2; return 1 ;;
    esac
    # Validate before filesystem effects. ENVIRON avoids awk -v interpreting
    # backslashes in operator values; JSON escaping prevents source injection.
    filestore_definition=$(
        PLOWSHARE_FILESTORE_ROOT="$filestore_workspace/applications" \
        PLOWSHARE_FILESTORE_MANAGER="${PLOWSHARE_FILESTORES_MANAGER_HANDLE-${PLOWSHARE_ADMIN_HANDLE:-}}" \
        LC_ALL=C awk '
        function quote(value, result, character, position) {
            result = "\""
            for (position = 1; position <= length(value); position++) {
                character = substr(value, position, 1)
                if (character == "\\" || character == "\"") result = result "\\"
                result = result character
            }
            return result "\""
        }
        BEGIN {
            root = ENVIRON["PLOWSHARE_FILESTORE_ROOT"]
            manager = ENVIRON["PLOWSHARE_FILESTORE_MANAGER"]
            config = ENVIRON["PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE"]
            if (length(root) > 4096 || root ~ /[[:cntrl:]]/ ||
                length(config) > 4096 || config ~ /[[:cntrl:]]/ ||
                (manager != "" && (length(manager) > 64 ||
                    manager !~ /^[A-Za-z0-9][A-Za-z0-9_.-]*$/))) exit 1
            accounts = manager == "" ? "[]" : "[{\"handle\":" quote(manager) ",\"role\":\"MANAGER\"}]"
            print "export default {\"version\":1,\"defaultStore\":\"applications\",\"fileStores\":{\"applications\":{\"root\":" quote(root) ",\"access\":{\"accounts\":" accounts "}}}};"
        }'
    ) || {
        echo "Invalid FileStore workspace path or manager handle." >&2
        return 1
    }
    test ! -L "$filestore_workspace/applications" || {
        echo "The applications directory must not be a link." >&2
        return 1
    }
    # Permissions apply only to new paths. Existing deployment files, directory
    # modes and ownership are never rewritten by an image upgrade.
    (umask 077; mkdir -p "$filestore_workspace/applications") || return 1
    test -d "$filestore_workspace/applications" && test -w "$filestore_workspace/applications" || {
        echo "The applications directory must be writable by the server." >&2
        return 1
    }
    (umask 077; set -C; printf '%s\n' "$filestore_definition" > "$PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE") || return 1
    if [ "${PLOWSHARE_FILESTORES_MANAGER_HANDLE-${PLOWSHARE_ADMIN_HANDLE:-}}" = "" ]; then
        echo "Initialized the applications FileStore without account grants; configure a MANAGER grant before deployment." >&2
    fi
}

configure_default_filestores
