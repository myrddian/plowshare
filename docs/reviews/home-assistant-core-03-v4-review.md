# CORE-03 v4 affected SDK boundary re-review

Reviewer: `/root/ha_core_facade_reviewer`  
Implementer: `/root/ha_sdk_implementer`  
Verdict: **APPROVE**

Candidate: `docs/reviews/home-assistant-core-03-v4-candidate.json`  
Candidate SHA-256: `221c9e73088c09d71d637ec0e6281f3b25e8920d53b5a2414cd450809f194ac2`

No blocking findings remain in this affected SDK-only slice. This is reapproval of
the twelve bound source/evidence paths, not whole-CORE-04 or package approval.
The earlier CORE-03 server v2 approval remains unchanged historical evidence.

## CORE03-PARITY-R1 resolved

The portable constraints now apply the existing `trimmed` equality predicate to
only Ex input project, source and externalConversation. All three native codecs
compare original text with their native trim result; they do not return a repaired
or trimmed selector. This rejects the single final LF that Python and .NET dollar
anchors could previously leave unmatched.

The additional predicate preserves the declared valid sets. Source and external
IDs already forbid whitespace under their ASCII grammars. Project retains its
exact ECMAScript edge pattern and C0/C1 prohibition. Independently enumerated
Python whitespace outside ECMAScript's set: only U+001C–U+001F and U+0085, which
are already forbidden project controls. The Go/.NET trim predicates introduce no
permitted locator character beyond the existing whitespace/control exclusions.
Interior NBSP and FEFF remain valid and unchanged. This is rejection at an input
boundary, not normalization or prompt transformation.

Independently repeated the original public Python encoder probe against the
corrected source. Both Ex operations reject every combination of the three
locator fields and LF, CR, CRLF, U+2028 and U+2029 suffixes: **30 refusals** before
transport. The same probe accepts and preserves four interior NBSP/FEFF project
cases, including exact padded agent/task/session strings. The owning shared
fixture generator now records those thirty refusals and four positive cases,
checks expectations through the canonical decoder and produces **1,012 cases**.

Agent/task/session retain their separate ECMAScript nonblank predicates and valid
surrounding text, including canonical NEL, U+001C and NUL cases. Named reply fields
also retain their original text. The locator predicate was not generalized to
those fields or to ordinary operations.

## Full affected parity slice

Compared the native schema JSON with the immutable v3 snapshot. Its entire change
is six locator trim settings: three properties on each of the two Ex input roots.
Private result definitions, public field graphs, result constraints and all
ordinary schemas are unchanged by this correction. The three current native
schema resources are identical.

The earlier reviewed Ex reply corrections remain sound. Open-Ex invokes its
existing ConversationView semantic checker; counters retain null and configured
zero, accept nonnegative safe integers and reject negative/fractional/unsafe
values. Run-Ex requires the intended nonblank job identity and resolved `cnv_`
conversation. Title/prose and named text are not rewritten or given a new 32K
limit. Ordinary reply decoder policy remains unchanged.

Private Ex result clones remain deterministic `$defs` references. Their owning
native generator creates Python model-registry aliases to the existing canonical
object DTO classes, with no runtime codec or untyped mapping fallback. It refuses
unknown/non-object/dictionary aliases and changed structural field/type graphs.
The generator and Python registry bytes are identical to v3. The actual Python
binding regression still covers typed Open/Run objects, absent optional agent
metadata, ignored future fields and malformed known fields. The previous ordinary
schema preservation finding also remains valid: only Ex input roots and the two
private Ex result roots differ from the accepted earlier graph.

## Exact source and checks

Verified the candidate digest above, all **13 bound file hashes** (twelve authored
paths plus frozen ledger snapshot), its compressed diff digest and the accepted
CORE-03A dependency manifest. The v3 author note and review remain immutable.
No server, authentication, SQL, public DTO signature or runtime codec change is
part of this reapproval.

Current coordinator evidence inspected:

- Owning generation/drift and the **1,012** canonical fixture self-checks passed.
- `sdkCheck` passed in **2 minutes 7 seconds**, 20 tasks, including native
  compiler/lint/DTO checks and controlled Ex WebSocket conformance for Node,
  Python, .NET and Go.
- The locator-corrected default `./gradlew check` passed in **44 seconds**, 141
  tasks, without `-PfullDb`.

The current logs are `/private/tmp/core04-sdk-check-final.log` and
`/private/tmp/core04-repository-final-check.log`. These checks and the independent
read-only boundary probes support this exact slice. Package checks and the fresh
reviewer's whole-CORE-04 assessment remain separate requirements before CORE-04
closure; this report does not present them as passed or waive them.

No source, old report or ledger files were edited by this reviewer. The reviewer
ran no Gradle, generator, installation, database, network, model or live device
calls. Only this new review report was written. Prompt-dependent, merge,
deployment and live-validation gates remain unchanged.
