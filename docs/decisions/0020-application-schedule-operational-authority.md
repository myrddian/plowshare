# ADR 0020: Application execution accounts control their own schedules

Status: accepted. The user explicitly authorized this platform authorization fix
and the independent dashboard update after reviewing the manager-password gap.

## Platform need

Applications run under separate execution accounts with scoped Contributor access.
Their operational pause/resume controls must use that identity, independently of
who installed them. This applies to every Application and every SDK/control surface,
including historical sources registered under the deploying human. The original
pause override in ADR 0014 conflated operational control with definition editing.

## Decision

Keep the public `schedule.pause` contract, immutable releases, registered source
owner, action grants and atomic runtime override. Resolve managed provenance by
internal name before authorization. For server-owned Application schedules, admit
its explicitly declared root `executionAccount` with current effective Contributor
access, or a current project Manager. A declared service handle also admits its
scoped token principals; a declared principal admits that exact principal. Token
role ceilings, membership and manifest revocation apply on every call. Merely being
a Contributor, a registrar or an administrator outside the manifest is insufficient.
Global, workspace and other Applications' schedules receive no delegated access.

WebSocket admission allows the existing `schedule.pause` operation for service
tokens only when durable schedule provenance resolves to a granted ordinary
project with effective Contributor access. Caller-supplied project/conversation
references cannot supply that scope. Global and unknown schedules remain blocked
for services; global human resume still requires server administration. The
scheduling service supplies the stricter Application identity/Manager decision
and retains Manager requirements for mutable source rewriting.

Authorize before source IO and recheck after reconciliation, immediately before
control. Reconciliation/action validation still runs under the source's registered
execution owner; changing the control actor never transfers execution ownership.
Invalid/unavailable sources, removed grants and changed effective definitions still
refuse. A managed refusal must propagate without a legacy-store fallback.
Definition authoring, enrollment, deployment, deletion and permission changes keep
their existing management boundaries. Operational access grants none of those rights.

The manifest execution binding and effective Contributor grant are the explicit
operational permission. No redundant account field or per-language permission
facade is introduced. All SDK languages already expose the same typed boolean
operation. Historical manifests without an execution binding grant no new worker
control; current managers retain Application operational control.

## Alternatives and lifecycle

An SDK-only workaround requires a temporary human login, persistent human credentials
or elevating the worker to Manager. Each obscures the application's actual authority
or grants unrelated rights. Local collector pausing cannot stop the server's schedule.
A source deployment can change its initial pause flag but is definition authoring,
not operational control. Those alternatives do not supply the missing platform boundary.

No migration or wire-contract change is required. Existing owner-scoped mutable and
legacy controls remain unchanged. Resume still selects the next future occurrence;
missed ticks are not replayed and existing runs retain their lifecycle. No inference,
accounting, Relay or credential mutation is added. Unknown delivery is reconciled
through fresh scoped reads, never automatic resubmission.

The dashboard uses its authenticated Application SDK connection, including replacement
connections after recovery. It retains its local uncertain-control fence but never
opens a human login for monitoring. Frequency/deployment editing remains separate.
Deploy the server authorization fix before switching that dashboard. Rolling back
the server restores the former authorization restriction; it does not change stored
pause overrides or grant the worker manager access.

## Verification

Mocked server tests cover execution/manager access, foreign Contributors, token ceilings,
manifest revocation, invalid resources, historical registrar ownership, revocation during
IO, no authoring/removal authority and no legacy fallback. One focused PostgreSQL test
checks the new provenance lookup's actual row mapping and retained owner fence. Existing
projection persistence semantics are unchanged. Dashboard tests cover authenticated and
same-origin service control, strict target binding, no password transport, once-only
calls and recovery without replay. Production remains paused until explicit activation.
