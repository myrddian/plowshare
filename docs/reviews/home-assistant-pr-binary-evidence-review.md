# Archived evidence publication review

Verdict: **APPROVE**. No blocking findings.

Reviewer: `/root/ha_pr_integration_reviewer`.
Implementer: `/root/ha_pr_integration_implementer`.
The reviewer did not author or modify implementation, tests, archives or ledger.

This approval binds
`docs/reviews/home-assistant-pr-binary-evidence-v1-candidate.json`, SHA-256
`c0d58ba6d4a5f36191def3528d2b6a79681e169cbcdc4b24b987e845d1c377c0`.
The source hash is
`3e615d257d6bab2d5940ee86126e94c36c83ee0c965da83a07e2e8f20122e9e0`;
the implementation-report hash is
`1e46e585e5b2e251cde97d916bbc00379233fbc2a227003d3890a5e12694eddf`.
Both match the frozen candidate and current bytes.

## Policy and scope

The original guard explicitly permits named binary assets. This correction
follows that policy for seventeen immutable gzip review diffs without admitting
a directory or extension. The original wrapper/icon set remains unchanged. The
tracked-tree scan still compares the complete detected binary set to exactly the
named assets, so an unlisted binary fails. Module discovery and the source-tree
NUL scan retain their original coverage. The existing file-byte predicate is
factored into a byte-array overload without changing its behavior.

Each archive has a literal SHA-256 expectation in the test, checked before
decompression. The reviewer independently matched all seventeen source constants
to the new candidate, unchanged archive bytes and each historical candidate's
`working_tree_diff.sha256`. Historical candidate files also match the hashes in
the new manifest. None of those frozen artifacts was rewritten.

The new test reads at most 8 MiB plus one sentinel byte, rejects expansion past
that bound, rejects NULs, decodes UTF-8 with the decoder's strict failure policy
and requires the Git text-diff header. The reviewer independently decompressed
all seventeen archives and verified their exact manifest sizes and those content
properties. The fixed digests bind the known evidence before expansion; no
runtime-discovered expectation can silently approve changed bytes. New or
modified archives require an explicit source/policy review.

## Validation and limits

The focused log hash
`db2eca7f9d8c9135194bbfddca4e1dc5064180998547e0cdf4340df8ad49c3ed`
matches the candidate. The log records `format`, `formatCheck` and all four
`SourceIsTextTest` cases passing in 10 seconds. The reviewer inspected those
results and the actual scoped diff; `git diff --check` passes. Java types use
imports and simple names. No reviewer Gradle or database process was started.

The correction changes only this test policy and its new evidence. The previous
JDBC bridge review remains frozen and unchanged. Full repository/SDK checks were
still running under the coordinator at review time and remain a publication
requirement. This approval does not establish live integration behavior, relax
any prompt-dependent gate, add a runtime capability or authorize deployment or
merging the PR into main.
