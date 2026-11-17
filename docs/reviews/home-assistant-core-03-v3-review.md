# CORE-03 v3 affected SDK boundary review

Reviewer: `/root/ha_core_facade_reviewer`  
Implementer: `/root/ha_sdk_implementer`  
Verdict: **CHANGES_REQUIRED**

Candidate: `docs/reviews/home-assistant-core-03-v3-candidate.json`  
Candidate SHA-256: `b58423769ba70418fcabfe407e3a8d1a4843ee44e39ab185be23e852bb0e0e38`

## CORE03-PARITY-R1 — P2: portable locator predicates accept a trailing LF

In `scripts/native-sdk-constraints.mjs`, the Ex project edge predicate disables
`trimmed` and ends with `$`. The source and externalConversation ASCII predicates
also use `$` without an independent whole-value edge check. Python's `re.search`
can match `$` immediately before one final LF. Its owning codec consequently
accepts an invalid locator and allows submission, while the full canonical
TypeScript request decoder rejects it. .NET's existing Regex.IsMatch boundary
uses the same relevant dollar-anchor behavior; do not assume RE2's strict end
semantics apply to all three codecs.

Independently reproduced through the existing public Python request DTO and
`encode_request`, and through compiled TypeScript `decodeRequest`, without any
network calls. For **both** conversation.open.ex and agent.run.ex:

| Locator field | Invalid value | Python | Full TypeScript decoder |
| --- | --- | --- | --- |
| project | `house` followed by LF | accepts | rejects |
| source | `external` followed by LF | accepts | rejects |
| externalConversation | `session` followed by LF | accepts | rejects |

Also checked CR, CRLF, U+2028 and U+2029 suffixes for all three fields and both
operations: both tested boundaries reject those cases. The demonstrated gap is
the single trailing LF, not a proposed change to valid free-form text.

**Required correction:** make the three request locator checks reject all values
outside their exact declared grammar across the owning native codecs. Do not
strip or normalize the supplied selector. A narrow non-transforming input edge
predicate is suitable if its equivalence is verified against the existing valid
sets; avoid unsupported RE2 lookahead or a Python-version-specific end anchor.
Add shared fixtures for a final LF in each locator field on both Ex operations,
then regenerate and run the relevant native boundary/conformance gates. Preserve
padded agent/task/session and named reply text that the canonical decoder accepts.
This is one affected-boundary finding, including the older source/external-ID
anchor cases in the same owning constraint slice.

## Other review results

The exact candidate and all **11 bound hashes** (ten authored paths plus frozen
ledger snapshot) matched. Verified its compressed diff digest and accepted
CORE-03A dependency manifest. No implementation or ledger files were edited.

The private result definitions otherwise preserve the intended typed boundary.
They are deterministic `$defs` references, cloned before adding Ex-only checks.
The native generator binds their Python identities to the existing canonical
object DTOs; it refuses unknown/non-object/dictionary bindings and a changed
structural field/type graph. Its structural comparison resolves references and
checks required properties, alternatives, element structure and declared value
types. There is no runtime codec modification or untyped mapping fallback added
by this slice. The focused Python regression exercises actual typed Open/Run
results, absent optional agent metadata, ignored future fields and invalid fields.

Reconstructed the prior native schema JSON from the immutable v2 tracked diff
for an independent preservation comparison. All ordinary input and result roots
are unchanged. The only existing definitions changed are the two Ex input roots;
the only added definitions are the two private Ex result roots. The three current
native schema resources are identical. Public generated DTO signatures remain
owned by the canonical graph; the Python model-binding additions are private
registry aliases.

The canonical Ex reply decoder now invokes the existing ConversationView semantic
checker and the intended nonblank run ID predicate. Tests cover required fields,
nullable/zero/large-safe counters, fractional/negative/unsafe refusals, preserved
named text and unrestricted title prose. Exact ECMAScript nonblank predicates
avoid native-language whitespace assumptions for agent/task/present-session and
reply names without rewriting text. Ordinary decoder policy is explicitly kept
separate. The locator LF finding above still prevents approval.

## Verification and limits

The coordinator reported successful owning generation and 978 canonical fixture
self-checks, the direct Python typed-result regression, and current `sdkCheck`
(**2 minutes 7 seconds**, 20 tasks), including controlled Ex WebSocket conformance
for Node, Python, .NET and Go. The current repository check and subsequent package
check remain separate evidence at this report's freeze. Passing suites do not
waive the independently demonstrated selector acceptance gap.

The reviewer ran only read-only local Python/compiled-TypeScript boundary probes
and source/hash/schema inspections. No Gradle, generation, installation,
database, network, model or live device calls were made. The earlier server v2
approval remains historical evidence for unchanged server behavior; this report
neither approves CORE-04 as a whole nor changes prompt, merge, deployment or live
validation gates.
