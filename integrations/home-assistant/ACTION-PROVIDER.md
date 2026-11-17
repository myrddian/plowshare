# Explicit Home Assistant action provider

`HomeAssistantActionTools` supplies `describe_action` and `execute_action` under a
separately configured control prefix. It implements the existing
`HomeAssistantToolHandlers` interface and runs in the same public SDK
`HomeAssistantToolProvider` host described in [the read-provider guide](TOOL-PROVIDER.md).
It introduces no server dependency, second agent runtime, new transport or second
durable journal. The manual alias executable keeps its existing configuration and
behavior. This slice is a Java composition API; it does not install a live control
provider or authorize device actions.

## Configuration and authority

Control requires its own `RelayTools.Binding` provider identity and a prefix that
does not overlap the read-only prefix. Both bindings name the same configured
project. The constructor takes both configurations and refuses accidental provider
reuse or prefix overlap. `HomeAssistantReadTools` still declares exactly its four
read tools. Publishing the control catalogue never adds execution to a read-only
provider wildcard.

Install a separate explicit provider and Application tool scope for control. Grant
only its exact `describe_action` and `execute_action` names to selected agents;
dynamic discovery, read scope and a successful catalogue publication do not grant
control. Keep real origins, accounts, credentials and journal directories in
private host configuration. Configure a separate journal directory and matching
private owner fingerprint for each provider. Server/provider/HA bindings and
credential generations must not share retained receipts.

The public SDK/server validates the current registry, schema, provider lease and
tool grants when admitting an invocation. The adapter cannot perform a new
server-side permission RPC immediately before the eventual native send through
the current SDK. It preserves that existing admission contract rather than
claiming a second server authority check. `HomeAssistantActionPolicy` is an
additional trusted, revocable local ceiling, configured independently of resource
disclosure. `disable()` prevents subsequent local admissions; it cannot retract
an effect already admitted or undo physical work.

The caller composes a configured adapter, its resource catalogue and control host:

```java
var actionPolicy = new HomeAssistantActionPolicy(source.binding(), permittedActions);
var handlers = new HomeAssistantActionTools(
    source.binding(), controlSettings, readSettings,
    resources, resources, actionPolicy, adapter.actionExecutor(bindingName));
var provider = HomeAssistantToolProvider.open(
    controlSettings, sdk, handlers, controlJournalDirectory, controlOwnerFingerprint);
```

Every variable is explicit, validated application configuration. In particular,
`permittedActions` is a configured subset of the adapter's fixed original action
profiles, and `resources` is the same credential-bound
`HomeAssistantResourceCatalog`. Keep the provider's existing configurable finite
request/cleanup budgets (two seconds by default). Connection startup retains its
separate configured budget. Supervision owns bounded polling, explicit catalogue
renewal/withdrawal and native connection maintenance.

## Offers and exact intent

First obtain a resource revision through the read catalogue. `describe_action`
takes its resource alias, exact revision and configured action alias. It returns
the exact service, complete ordered target bundle, full primitive parameter
schema, and a fingerprint over service, targets, parameter types/bounds/enums and
required fields. Discovery only offers profiles whose entire fixed bundle is
disclosable; a hidden target prevents the whole offer.

`execute_action` requires the alias, action alias, revision, exact service, complete
ordered `targets`, `profileFingerprint`, and `parameters`. Those values persist in
the SDK's original invocation intent before acknowledgement or handler work, so
the intended service, target bundle and actual values remain reconstructable after
a configuration change. The fingerprint supplements the public tool declaration's
independent schema fingerprint. A matching alias or policy label alone cannot
reinterpret an old offer.

The initial named-control profile supports 1–20 fixed entity targets and the
existing configured string/number/integer/boolean parameters, bounds, enums and
required fields. This tighter complete-receipt bound is independent of the legacy
manual configuration's larger target limit. A larger bundle is refused whole,
never split or narrowed. Arbitrary service selection, caller target fields,
targetless/global services, area/device selectors, nested parameters and
`returnResponse` are not executable through this profile. Unsupported schemas
fail visibly; they are never weakened or rewritten.

Named control policy also refuses `conversation.process`, `ai_task.generate_data`
and `ai_task.generate_image`. Those services can select this package's own model
endpoints through parameters or HA defaults; fixed entity targets cannot prove a
nonrecursive destination. No named-control override exists in this slice. Read
discovery may describe an explicitly configured profile, but that is not an
execution permit. The private manual alias contract remains explicit operator
composition. See [observation routes and feedback](OBSERVATION-ROUTES.md) for the
exact suppression scope and the separate SDK/runtime journal boundary.

The handler validates the full intent before entering `ActionExecutor`. The native
executor independently matches the configured alias's original full profile and
credential binding. After waiting for a native command slot, it checks remaining
time, local policy generation and the catalogue's current cached offer. This
final check makes no network request or implicit refresh. Dirty/expired metadata,
changed policy, epoch or state revision, or revoked control prevents submission.
The catalogue samples source state outside its own lock and checks the unchanged
cached policy/snapshot, avoiding adapter/catalogue lock inversion. A connection
epoch change also fences native registration. Concurrent changes after local
admission cannot undo an admitted send.

## Receipts and recovery

The existing exclusive provider journal commits the complete SDK invocation
intent before broker acknowledgement and handler execution. Native HA commands
are sent once. A timeout, cancellation or disconnect after possible transmission
never permits another native send under that invocation. Retain the original
SDK invocation UUID. When available, the structured receipt also preserves the
native numeric request ID paired with its captured connection epoch and HA's
bounded context ID/parent ID; HA user identities are excluded.

| Delivery | Effect evidence | SDK result |
| --- | --- | --- |
| `NOT_SUBMITTED` | `NOT_STARTED` | `REJECTED` |
| `ACKNOWLEDGED` | `NOT_OBSERVED` | `COMPLETED` receipt |
| `REFUSED` | `UNKNOWN` | `UNKNOWN` |
| `UNKNOWN` | `UNKNOWN` | `UNKNOWN` |

`ACKNOWLEDGED` means HA returned a valid service acknowledgement. It does not mean
that a device reached the requested state. A HA service can partially act and
then return an error, so even a native refusal conservatively retains unknown
effect evidence. A malformed acknowledgement also leaves the outcome unknown.
SDK `COMPLETED` means the receipt-producing handler finished; its structured
`effect` remains `NOT_OBSERVED`.

Restarted `EXECUTING` intents become `UNKNOWN` through the existing SDK provider;
they never call the actuator again. Lost broker acknowledgement before the
handler may leave an intent unknown even though no actuator ran. Lost result
publication acknowledgement is reconciled read-only using the exact original
publication, without repeating the effect. UNKNOWN receipts are never retired.
Known completed receipts may use the existing exact broker-position replay
fences; this does not authorize a new invocation or actuation. If interrupted
before the native receipt is durably saved, the original SDK intent remains the
recovery identity and the native numeric ID/context may be unavailable. That gap
does not permit replay or a guessed success.

Caller cancellation is not proof that native work ended. The provider retains its
bounded physical slot until work/cancellation actually settle, and the native
adapter aborts only its captured connection attempt. No replacement worker or
new journal owner runs around uncertain work.

## Separate verification

`ActionVerifier` is an explicit Java API over the retained typed action receipt,
a newly selected resource revision and a bounded map of resource aliases to exact
expected state strings. `HomeAssistantActionVerifier` confirms each selected
resource belongs to the receipt's complete target bundle, performs one fresh
batched state read, and reports `MATCHED`, `NOT_MATCHED` or `UNAVAILABLE`.

Stale, missing, unavailable or pre-receipt observations cannot match. The same
finite budget covers selection checks and that one fresh batch. There is no
polling loop, automatic retry, implicit revision refresh or re-actuation. The
original receipt/context accompanies the observation as correlation evidence;
an observed state match does not prove that this action caused the state. This
slice does not expose a model-facing verification tool or invent a receipt lookup
API; a host retains the exact existing receipt and invokes the explicit verifier.

The focused tests use deterministic fake SDK/native transports and fake effects.
They cover complete intent, schema/credential/policy fences, cancellation before
and after possible effects, lost acknowledgements, private journal restart and
separate fresh observation. They do not establish live grant behavior, physical
device success or deployment readiness.
