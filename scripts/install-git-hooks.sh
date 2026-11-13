#!/bin/sh
# core.hooksPath is repository-local and shared by linked worktrees. Never
# silently replace hooks maintained by a contributor or another tool.
set -eu
cd "$(git rev-parse --show-toplevel)"
configured=$(git config --get core.hooksPath || test "$?" -eq 1)
if [ "$configured" = '.githooks' ]; then
    echo 'Repository Git hooks are already enabled.'
    exit 0
fi
if [ -n "$configured" ]; then
    echo 'Refusing to replace an existing core.hooksPath. Integrate .githooks/pre-push into your current hooks.' >&2
    exit 1
fi
if [ -x "$(git rev-parse --git-path hooks/pre-push)" ]; then
    echo 'Refusing to replace an existing pre-push hook. Integrate .githooks/pre-push into your current hook.' >&2
    exit 1
fi
if [ ! -x .githooks/pre-push ]; then
    echo 'The tracked .githooks/pre-push must be executable before installing.' >&2
    exit 1
fi
git config --local core.hooksPath .githooks
echo 'Enabled pre-push formatting verification.'
