# CORE-03 v4 locator correction

Author: `/root/ha_sdk_implementer`.
Status: narrow correction ready for coordinator generation/verification and
independent re-review. The rejected v3 note/candidate/review remain immutable.

CORE03-PARITY-R1 identified a native regex difference: Python and .NET `$` may
match immediately before a final LF. The Ex project, source and external-ID
patterns therefore needed an additional exact-value rejection guard. The public
TypeScript decoder rejects these values. A read-only probe of the full canonical
decoder confirmed rejection of final LF, CR, CRLF, LS and PS for each of the three
locator fields on both Ex operations (30 cases).

The correction enables the existing `trimmed` equality check on only those three
Ex locator fields. The check rejects a value whose native trim differs; it never
returns a trimmed value. Project's existing pattern still handles ECMAScript
FEFF/NBSP edge semantics and rejects every C0/C1 control anywhere. Exhaustive
Python whitespace enumeration found the native characters outside ECMAScript's
set to be U+001C–U+001F and U+0085, all already prohibited by that project set.
The Go/.NET native whitespace sets similarly add no permitted project character
beyond those controls; their existing validators compare original text with
`TrimSpace`/`Trim`, without mutation. Source and external IDs permit only their
configured ASCII grammar without whitespace, so the added guard does not narrow
any valid locator. Interior NBSP and FEFF in otherwise valid project names remain
unchanged and accepted.

Agent/task/session retain their exact ECMAScript nonblank checks and valid padded
text. Named reply fields retain surrounding text. Ordinary operations, generic
codecs, public signatures, private result model aliases and all other v3 changes
are unchanged. This is an input-locator correction, not normalization or a prompt
transformation.

The shared DTO-fixture source adds all 30 trailing-separator refusals and valid
interior NBSP/FEFF project examples. The coordinator owns regeneration of the
shared fixture and three native schema resources, current native/public-boundary
checks, generator drift and source hashes. The full previously unapproved v3 SDK
slice remains in scope for v4 reapproval together with this fixture source/output
and note. Passing earlier SDK/repository checks did not waive the finding; no
current final SDK/package/review pass or deployment is claimed here.
