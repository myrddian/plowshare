# CORE-04 independent SDK review

Reviewer: `/root/ha_sdk_reviewer`  
Primary implementer: `/root/ha_sdk_implementer`  
Native coauthor: `/root/ha_sdk_implementer/native_ex`  
Verdict: **APPROVE**

Candidate: `docs/reviews/home-assistant-core-04-v1-candidate.json`  
Candidate SHA-256: `9a0afecb2fc6d4840119bac0bbda08c6e3e32c475fbc3a966cccc7a1dd910da0`

No blocking findings remain. This approval covers the exact 34 authored paths,
their recorded evidence and the separately hashed frozen ledger snapshot. Both
implementation agents are distinct from this reviewer; the reviewer authored no
implementation being approved.

## Exact candidate and dependency

Independently verified the candidate digest, all **35 bound file hashes**, the
compressed tracked-diff digest and the accepted CORE-03 v4 dependency:

`221c9e73088c09d71d637ec0e6281f3b25e8920d53b5a2414cd450809f194ac2`

Read the actual Java capability, records, checked exchange and socket tests;
TypeScript/Node exports; all three native status owners and conformance drivers;
shared socket harness and fixture sources; owning native generator; generated
Python model aliases and schema resources; fresh-consumer script; manuals and
implementation/correction reports. The frozen source remained unchanged through
the final package check. The new Java declarations use imported simple type
names, and SDK production code introduces no server-internal dependency, SQL,
fallback endpoint or second runtime.

## Findings resolved

- **SDK04-PRE1:** Java now returns exact Ex-specific immutable result types.
  Open requires all eight fields, including nullable values and both booleans;
  it preserves text, configured zero and nullable nonnegative safe-integer counts.
  Run requires the job and resolved `cnv_` conversation, permits omitted agent
  metadata, rejects explicit null agent and omits undeclared future fields.
  Private checked codecs preserve these distinctions without changing ordinary
  Java DTOs. A renamed or nullable reply project is not compared with the original
  request name.
- **SDK04-PRE2:** Java no longer applies the unrelated 32K task limit or silently
  strips strings. Ex request fields follow their actual per-field canonical
  semantics, including exact padded task/agent/session text, explicit session
  null and the shared resource ceiling. The affected native input rules remain
  operation-private. The independent CORE-03 v4 reapproval covers the final
  locator correction: native trim is an equality rejection on the three locator
  fields, not a returned transformation or a rule applied to prose/reply names.
- **SDK04-PRE3:** The Ex-only public reply decoder applies the intended
  ConversationView semantic checks. Negative, fractional and unsafe counters and
  blank required metadata fail, while null, configured zero, integral numeric
  spellings and original text remain valid. Run retains its required internal
  conversation and nonblank job identity. Ordinary decoder policy is unchanged.
- **SDK04-PRE4:** Ex validation roots are private referenced definitions, not
  inline objects that lose Python DTO identity. The owning generator binds them
  to the original generated model classes only after checking their structural
  field/type graph. It rejects unknown, dictionary, non-object or changed model
  bindings. Runtime codecs remain unchanged; the typed Python regression and
  actual WebSocket drivers require DTO attributes rather than accepting raw maps.

The recovery wording note is also resolved. Java Javadoc and the manual qualify
repeating Open by the unchanged authenticated account and stable project
namespace; identical project-name text after rename/reuse is not that proof.

## Transport and compatibility

Each helper uses the existing authenticated connection and submits one application
operation. The shared harness verifies the exact original request and one
submission in each of twelve Ex scenarios for each of Node, Python, .NET and Go.
It detects an extra resolver request or replay. Java tests independently exercise
the actual public helper over a socket, including omission versus explicit
session null, malformed results, refusal, pre-submission cancellation and unknown
delivery after timeout, disconnect or cancellation.

Open accepts only `OK`; Run accepts only `ACCEPTED`. The native gates apply only
to those operations and recognized success outcomes. Real refusals retain their
ordinary handling and cannot expose an unvalidated diagnostic payload. Invalid
responses and uncertain delivery do not reconnect, switch transport, reopen a
conversation or resubmit a mutation. Existing explicit pre-admission capacity
handling remains its separate contract. Alias identity is not utterance
idempotency, and cancellation is not remote job abort.

## Current verification and packages

Inspected the coordinator's saved evidence and independently summed the Java
JUnit files: **34 passing invocations across three classes**, zero failures,
errors or skips. The owning corpus contains **1,012 cases**, including 244 Ex
cases (44 valid and 200 invalid). Current `sdkCheck` passed in **2m7s**, including
native compiler/lint checks and all four real WebSocket conformance clients.
The independent CORE-03 v4 review also records the 30 locator-terminator refusals
and preserved valid interior whitespace/text cases.

The final combined `./gradlew check sdkPackageCheck` completed successfully in
**5m18s**, **153 tasks**. Its log shows the current repository check, fresh
installed Node/Python/.NET/Go WebSocket conformance, five-language authentication
conformance and successful fresh distribution consumers at
`build/sdk-consumer-sqpk5gxp`.

Supplemental package evidence:
`docs/reviews/home-assistant-core-04-package-evidence.json`  
SHA-256: `e166756ba9de2850e201aa72195b8d5e92e68d7c373e977227e09b893f486b3a`

Verified its candidate binding, final log digest and all **11 local artifact
hashes and byte counts**. Additionally verified the wheel's schema, contracts and
client bytes against the frozen source; the three new Java source-jar entries
against their frozen hashes; Ex classes in the binary jar; named type exports in
both npm package roots; and the fresh compiled Java Ex consumer class. Java Ex
socket evidence comes from the public SDK tests; the installed Java step proves
binary consumer compatibility, as the report accurately states.

The immutable author report correctly marked package evidence pending at its
freeze time. The completed supplemental proof above satisfies that prerequisite;
no pending check is waived or represented as an earlier pass.

This is source and local-package acceptance, not merge, publication, deployment
or live Home Assistant/model/device validation. It consumes the approved server
contract rather than claiming these isolated SDK fixtures prove server database
behavior. Prompt-dependent features and operational gates remain unchanged.

The reviewer ran no Gradle, generator, installer, database, network, model or
device calls. Only this new review report was written; implementation files,
older reports and the live ledger were not edited.
