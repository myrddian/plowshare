# Publication guard for archived review evidence

Implementer: `/root/ha_pr_integration_implementer`. Independent reviewer:
`/root/ha_pr_integration_reviewer` (approval is recorded separately).

The first publication-wide `check` failed the tracked-tree text guard because
seventeen frozen independent-review diffs are intentionally gzip archives. The
guard previously named only the Gradle wrapper and four application icons as
permitted binary assets. Its existing policy explicitly requires naming binary
additions. These archives are immutable evidence referenced by accepted review
candidates; deleting, reencoding or excluding their whole directory would lose
that evidence or weaken the source guard.

`SourceIsTextTest` now names those seventeen exact archive paths with literal
SHA-256 digests. The wider tracked-tree guard still requires exact equality
between detected binary files and the explicitly named assets. The original
wrapper/icon set is unchanged. No directory, extension or new binary file is
implicitly admitted; module source walks remain unchanged.

A separate test verifies each archive's fixed digest before decompressing it,
limits decoded bytes to 8 MiB, rejects NULs, requires valid UTF-8 and checks the
Git text-diff header. Existing archives expand to less than 5 MiB each. This keeps
the evidence readable and bounds decompression rather than trusting gzip size.
The hashes are fixed source constants, not expectations discovered from the
runtime filesystem. All seventeen constants were cross-checked against the
corresponding immutable candidate's `working_tree_diff.sha256`, in addition to
the current archive bytes; the new candidate manifest binds that provenance.

Validation: `format`, `formatCheck` and the four `SourceIsTextTest` cases passed
in 10 seconds. `git diff --check` passed. No archive or previously frozen bridge
source was modified. The coordinator owns the final full repository and SDK
checks against these frozen bytes. No database, live Home Assistant, prompt,
runtime, packaging or deployment capability is changed; prompt-dependent gates
remain in force.
