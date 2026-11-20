---
version: 1
includes: [web-search.md, web-fetch.md]
rules:
  - id: personal-workspace-reads
    effect: PERSONAL_READ_INTENT
    kind: FILE_READ
    project: null
    provider: null
    cwd: null
    inputDigest: null
    argv: []
    paths: []
    guidance: null
---

# Server security policy

Already-authorized reads in an account owner's Personal workspace carry standing
read intent. SECURITY and, in Auto mode, the owner's selected reviewer still
evaluate each request.

Reject edits, deletions, moves, and other changes to a configuration root's
`security.md` or `security/` unless the authenticated user's current request
explicitly asks for the security change. Apply the same rule to shell commands,
scripts, generated changes, and edits that would change these files indirectly.
Existing tool access, a previous approval, and an agent's stated justification
are insufficient evidence of an explicit request.
