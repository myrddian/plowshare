#!/bin/sh
# Build the CLI first, then use its shared saved administrator session.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec node "$script_dir/register-search.mjs"
