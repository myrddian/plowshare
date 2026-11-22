# HA-06 Conversation Entity implementation

Implementer: `/root/ha_conversation_sdk_implementer`.
Status: coordinator verification passed; ready for independent review, not accepted.

## Contract and ownership

This delivery changes only HA Conversation/configuration code, configuration
strings and its tests. It consumes HA-05's public `GatewayPort`, `ProfileRuntime`
and typed request/result records. It does not modify the SDK, core, shared
runtime, gateway, journal/setup composition or installation-readiness guards.
HA-07 separately owns runtime/journal composition; HA-11 owns final publication.

`configured_profiles` converts all configuration values before SDK work and
returns immutable `EnabledProfiles` and `ConversationOptions`. Enabled platforms
must select explicit agents: `read_only_agent` for control-off Conversation,
`agent` for control-on Conversation, and `task_agent` for AI Task. At least one
platform is enabled. Disabled routes never inherit another platform's agent.
Unknown fields, malformed values and requested HA LLM tool APIs are rejected.
The UI control flag declares frontend behavior; actual permissions remain the
selected agent's server/Application grants.

The entity prepares HA's actual ChatLog through `async_provide_llm_data` with no
HA tool API, configured/default prompt and the current extra system prompt. It
captures the resulting SystemContent unchanged into the existing SDK task/user
envelope; it neither appends the extra prompt twice nor claims a native model
system role. Language and authenticated source identity enter typed context;
identity metadata partitions work and grants no Plowshare authority.

## HA chat ownership and lifecycle

HA stores ChatLog by raw HA conversation ID and can retain an extra prompt.
Partitioning only the SDK request key would allow a foreign caller to inherit
that HA context. The adapter therefore checks a bounded, per-HA-process source
ownership table before calling HA's base Conversation processing:

- First calls omit `conversation_id`; returned HA-generated IDs are owned by the
  configured entry/profile and authenticated user/device/satellite digest.
- Unknown or foreign supplied IDs are explicitly unsupported, before HA log
  mutation or SDK work. Caller-chosen unknown IDs are not silently accepted.
- Same-owner concurrent use is rejected before shared log/prompt mutation.
- Ownership survives entity reload in `hass.data`. Active entries cannot be
  evicted; idle metadata expires after twice HA's ordinary chat-session interval,
  with a finite `MAX_SESSIONS` bound. Metadata eviction never removes SDK receipts.
- If HA expires a known chat and returns a replacement ID, the adapter reports a
  pre-submission error with that ID and retains the earlier work. That replacement
  is fenced against follow-up submission; it is not permission to abandon an old
  pending/uncertain job. The operator may inspect earlier work and start a new chat.
- After a complete HA restart, source ownership is unavailable: supplied unknown
  IDs fail explicitly rather than creating replacement model work.

Successful output updates HA's assistant chat content and speech. Pending output
is a truthful status, with no follow-up continuation claim; refusal, uncertainty,
busy/unavailable and failed outcomes become HA errors. Cancellation retains the
underlying gateway's admitted/uncertain lifecycle. No HA-owned tool loop or
transcript replay is introduced.

## Configuration-probe ownership

Configuration uses one read-only catalogue snapshot for every enabled selected
agent. Catalogue presence is not proof of tool authorization. Current public SDK
negative outcomes do not provide a precise authentication refusal code: capacity
is shown as validation busy and other service refusals as connection unavailability,
never assumed bad credentials. A per-HA pool caps
physical validation owners at four. Successful configuration requires proven
SDK closure. A false, failed, canceled or stalled close retains its owner and
slot beyond the short-lived flow object; later explicit flow attempts can make
one bounded cleanup check. They never close a different active validator, loop
unboundedly, create model work or discard an unresolved owner. A validation save
callback rejects any accidental submission-intent persistence.

Reconfiguration permits credential/wait/prompt changes only while existing routes
remain fixed. Runtime receipt binding still treats changed credentials
conservatively; rotation does not silently continue old work under assumed
identity equivalence. Legacy configuration lacking explicit profile selections
fails clearly without changing the journal. Final migration/operator instructions
belong to HA-07/HA-11.

## Verification prepared

The owned tests cover actual HA prompt rendering and exact effective extra prompt,
same-owner follow-up/reload, user/device/satellite and entry partitioning, unknown
ID rejection, known-ID expiry without replacement work, concurrent ownership,
finite owner bounds, anonymous-source policy, invalid/template failures, truthful
typed outcomes, explicit control/read-only routing and unchanged bundled SDK bytes.
An isolated real registered Conversation Entity test exercises both the actual
`conversation.process` service and public `async_converse` path, including the
internal state-write lifecycle. Only the unrelated HA auth lookup is faked;
ChatLog/template preparation is real. Probe tests cover all-profile validation,
retained false/stalled/failed/canceled cleanup, bounded repeated flows and refusal
before any SDK constructor on unsupported options.

The first coordinator focused run executed 72 cases: 71 passed and the actual
public-registration fixture failed because its entity registry was initialized
before the device registry. The fixture order is corrected. That run also exposed
unobserved cleanup-timeout futures. Cleanup now observes outcomes before stale
identity fencing and represents cleanup failure as explicit false settlement;
Python 3.14 shield logs failed inner futures after caller cancellation even if
another callback later observes them. Added late-callback/expiry observation
regressions preserve the newer owner and require no unobserved loop exception.
Independent preflight also corrected blanket authentication-error classification
and retained HA's Greek-question continuation semantics through its own ChatLog
property. The next coordinator full shared run executed 108 cases: 93 passed and 15
errored. The only HA-06 error was the same real registration seam: this HA
version separates device-registry creation (`async_setup`) from loading. The
fixture now calls that public creation hook before loading device and entity
registries; no registration/state methods are mocked away. Other errors belong
to the concurrently implemented setup/AI Task suite and are tracked by HA-07.
The final corrected full shared suite then passed as recorded below.

Owned-file Ruff lint and formatting passed. No implementer-run tests, Gradle,
PostgreSQL, live HA/model/device calls or dependency installation were performed.
Independent approval of the frozen candidate remains required. These fixtures
do not establish real server grants, deployment or voice-provider setup.


## Final coordinator verification

The coordinator ran the complete shared Assist check after both authors froze
source. Ruff lint and format checks passed, followed by all **113 tests in
1.298 seconds**, with no failures or errors. The source/test hash snapshot was
rechecked after execution and remained unchanged. This includes actual registered
HA Conversation service and `async_converse` calls, exact ChatLog prompt handling,
source ownership and refusal/cleanup regressions, the independently authored AI
Task action/helper and persistence/lifecycle cases, and public SDK transport tests.
The shared suite result does not transfer HA-07 ownership or independently approve
that deliverable; each node retains its own author, candidate and reviewer.

| Evidence | SHA-256 |
| --- | --- |
| `/private/tmp/ha-06-07-full-v3.log` | `f8fc9f4609779aca8afcd51a9bd2c3172f569c7678f6f8be26ec3fe322e0cceb` |
| `/private/tmp/ha-06-07-full-v3-source.json` | `ca33183416f8f0d967cc93951e703fe16da2fea170cceccbe526996334940656` |

No implementation source or tests changed after this successful run. The earlier
72-case and 108-case failed attempts remain evidence of the actual fixture and
lifecycle corrections; they are not rewritten as successful attempts. No Gradle,
PostgreSQL, live HA, model/provider or device operation was used for this check.
