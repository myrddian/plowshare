#!/bin/sh
# Merge driver for the committed generated contract catalogs listed in
# .gitattributes. Register it once per clone:
#
#   sh scripts/git-merge-generated.sh --install
#
# Why these files need a driver at all: the generators render each catalog
# wholesale, and the rendering is positional. Adding one operation or one DTO
# field anywhere renumbers shapes across every output, so two branches that
# both touched protocol DTOs produce overlapping rewrites of all 17 files. A
# textual three-way merge of two such renderings is meaningless even when it
# succeeds; the only correct resolution is to re-render from the merged sources.
#
# Why the driver does not re-render: generate-sdk-contracts.mjs imports
# sdk/typescript/build, so regeneration needs a TypeScript build first, and the
# working tree is not yet in its merged state while a merge driver runs. Doing
# it here would be both slow and reading the wrong inputs.
#
# So the driver resolves to our side and leaves the output stale. That staleness
# is not silent: generate-operation-schemas.mjs --check and
# generate-sdk-contracts.mjs --check both fail on a stale output, and
# sdkContractCheck runs them inside ./gradlew check. Regenerate per docs/sdks.md
# before building, and the correct content is restored from the merged sources.
#
# Git invokes this with %O %A %B %P: ancestor, ours, theirs, pathname. Git seeds
# %A with our version, which is the result we want, so the file is left as it
# is; exiting 0 reports the conflict resolved.
set -eu

if [ "${1-}" = '--install' ]; then
    repository=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
    git -C "$repository" config merge.plowshare-generated.name \
        'keep ours; generated contracts are re-rendered, never merged'
    git -C "$repository" config merge.plowshare-generated.driver \
        'sh scripts/git-merge-generated.sh %O %A %B %P'
    printf '%s\n' 'Registered the plowshare-generated merge driver for this clone.'
    printf '%s\n' 'Worktrees share this configuration; a fresh clone needs it again.'
    exit 0
fi

printf '%s\n' "generated contract kept at our version and now stale: ${4-${2-}}" >&2
printf '%s\n' 'regenerate before building: see "Regenerate catalogs" in docs/sdks.md' >&2
exit 0
