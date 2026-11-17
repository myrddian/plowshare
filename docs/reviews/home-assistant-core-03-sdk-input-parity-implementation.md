# Affected CORE-03 SDK boundary-parity correction

Author: `/root/ha_sdk_implementer`, during authorized CORE-04 implementation.
Status: source correction authored; coordinator generation, checks and affected
independent CORE-03 reapproval are pending. Earlier frozen evidence is unchanged.

The coordinator authorized an Ex-only portable input correction after SDK review
identified a mismatch with the canonical TypeScript request gate. That gate uses
ECMAScript whitespace semantics: a task must contain a non-whitespace character,
and an Ex project must not have edge whitespace. Native language `strip`/`Trim`
functions use different character sets. In particular FEFF is ECMAScript
whitespace, while NEL and U+001C are not. Applying a generic language nonblank flag
would reject some valid task text and accept a BOM-only task.

`scripts/native-sdk-constraints.mjs` now gives Ex project properties an exact
portable edge-whitespace/control pattern and disables their generic `trimmed`
flag. It gives only `agent.run.ex` agent/task/present-session fields an exact
portable nonblank pattern rather than a language `nonblank` flag or request-project
edge rule. These three strings preserve padded text and accept controls/NUL where
the full canonical public decoder accepts them. Their Ex-private property metadata
uses the existing 50Mi UTF-16-unit codec resource ceiling and disables inherited
generic `trimmed`/`noNul`/language-nonblank flags; the nullable session branch is
retained. No text is changed and no 32K prose cutoff is introduced.
Source/external-ID ASCII rules, limits, empty images and ordinary operation
policies remain unchanged. Generic native whitespace validators are untouched.

The coordinator subsequently authorized the matching Ex-only reply correction.
Open-Ex clones the ordinary view root before adding exact ECMAScript nonblank
checks to id/nullable project and nonnegative safe-integer constraints to its
three nullable counters. These output names retain surrounding text: they are
not request selectors. Configured zero and explicit null remain valid. Run-Ex's
already-private result root adds the canonical nonblank id check while retaining
its required `cnv_` conversation. No title/prose limit or agent normalization is
introduced. Ordinary result roots/properties are not changed.

Real Python socket conformance exposed that inline validation clones lose the
generated model binding: the runtime binds object DTOs by definition identity.
Both constrained Ex result clones now live under deterministic private `$defs`
keys, with the operation retaining a `$ref`. The owning native generator adds
private Python `_MODELS` aliases to the original canonical object DTO classes.
Generation fails for an unknown/non-object model or a changed declared field/type
graph. Public result class names/signatures, ordinary definitions and runtime
codecs remain unchanged. A focused Python regression asserts typed Open/Run
results, omitted agent and ignored future fields, and refusal of invalid fields.

Canonical fixture generation also exposed a decoder wiring gap: the existing
ConversationView semantic checker was registered for Open-Ex but the public
`decodeReply` path did not invoke it. `sdk/typescript/src/operations/schema.ts`
now invokes that existing check only for Open-Ex and validates Run-Ex's id with
the accepted-job nonblank predicate. No ordinary decode policy changes. Focused
`conversation-external.test.ts` cases exercise the public decoder for required
fields, nullable/zero/large-safe/integral counts, negative/fractional/unsafe
refusals, exact named text, unrestricted title text and Run-Ex blank ids.

The owning `scripts/generate-sdk-contracts.mjs` regenerates the derived portable
schema resources and private Python model registry:

- `sdk/python/src/plowshare/_schemas.json`
- `sdk/go/schemas.json`
- `sdk/dotnet/Plowshare.Sdk/Schemas.json`
- `sdk/python/src/plowshare/contracts.py`

The declared public field/type graph and server behavior are unchanged by this
correction. Generated public class names/signatures are preserved; the Python
contract delta is only its private model registry aliases. The coordinator must
inspect actual generated contracts as well as schemas and include these four
derived files in affected review. No generated file is edited by hand.

CORE-04's owning DTO-fixture generator adds project NBSP/FEFF edge refusals,
Unicode length bounds and task BOM/Unicode-whitespace cases. Valid NEL, U+001C,
NUL, long task text and original surrounding whitespace are preserved according
to the canonical request gate. Existing ordinary open/run fixtures remain in the
shared corpus. The required evidence is owning generation/drift checks and the
shared native boundary/conformance suites on the final source. Coordinator checks
and affected independent reapproval remain pending for this frozen note; their
exact-source results belong in the candidate/ledger evidence. This note claims
no final SDK/full/package pass, node approval, merge, deployment or live validation.
