# ADR 0024: Service execution on behalf of an account

Status: proposed. Drafted for discussion; implementation is not authorized.
Changing core requires the separate platform authorization described in
[ADR 0001](0001-integration-boundary.md).

This is not agent delegation. `agent_run`, `calls:` and
[ADR 0011](0011-application-scoped-agent-delegation.md) concern one agent starting
another inside an already admitted identity. This decision concerns which account
an authenticated Application may act for.

## Platform problem

Every request has exactly one execution identity: a human account or a service
token principal `@service/<token UUID>`. Authorization, ownership, conversations,
memory and accounting all follow that identity. There is no way to express
"this Application performs this request for that account".

A chat bridge shows the gap. A Discord, Teams or iMessage adapter receives
messages from several people and should let each of them talk to their own bot,
with their own Personal space, project roles, conversations and memory. Today the
adapter has two options, and both fail:

- **Run everything as the adapter's service principal.** Every chat user shares
  one authority, one conversation owner and one memory scope. Service tokens
  cannot reach Personal space. Plowshare cannot tell which person asked for what.
  The A2A receiver's external client aliases follow this model; it suits a public
  entry point, not a personal assistant.
- **Hold each person's own credential.** The adapter becomes full impersonation:
  all of the account's authority, every session, no attribution, and a single
  compromised adapter exposes every linked account.

The same shape appears in [ADR 0019](0019-mcp-provider-hosting.md), gap 3:
Application calls require caller and provider identity equality, and a separate
executor cannot act for a human without impersonation. Two independent consumers
needing one capability is the general platform need ADR 0001 asks for.

## Existing contracts considered

| Contract | Why it is insufficient |
| --- | --- |
| Service tokens with project ceilings | Effective access is already `min(account grant, token ceiling)`, but the identity is the service. Tokens cannot hold Personal scopes or carry a human subject. |
| Per-person service accounts | Admin-issued, no Personal space, no link to the human account; the person's history splits between two owners. |
| A2A external client aliases | An alias identifies a caller inside the adapter's own principal. It carries no authority of its own and cannot select the caller's account. |
| Adapter-side authorization | The server cannot enforce or audit it; trusting the adapter's decision is the impersonation case with extra steps. |
| Application-owned identities ([ADR 0017](0017-application-owned-service-provisioning.md)) | One identity per Application, bounded to VIEWER or CONTRIBUTOR. Still one subject for all users. |
| SYSTEM attribution in usage accounting | Records already distinguish SYSTEM from the admission owner. This is the precedent for dual attribution, not a mechanism for services. |

An SDK-only adapter cannot create a durable, revocable, server-checked binding
between its principal and a human account. Only the server can admit the subject
and enforce the intersection on every later permission check.

## Decision

Acting for an account needs three things, each owned by a different party:

| Part | Owner | Answers |
| --- | --- | --- |
| **Actor**: the Application's owned service identity | Server administrator, through deployment | Who is asking? |
| **Grant**: a named declaration in `plowshare.json` with an eligibility list | Server administrator, through deployment; eligibility through grant management | What may this Application ever do for anyone, and for whom? |
| **Subject proof**: evidence that a specific person is present and consents | The person, or a trusted ticket system acting for them | For whom is this request, and did they agree? |

Authority only ever narrows. For a server project, effective role is

```text
min(subject's current role,
    grant ceiling,
    subject proof's own ceiling,
    actor service account's project grant)
```

and the subject must be on the grant's eligibility list. It is never a union.
The last term lets a project manager remove the Application from a project without
touching any grant or proof.

### One invariant for every proof kind

Every subject proof resolves to **exactly one Plowshare account** before policy
runs. Policy, ownership, accounting and audit use that account and never branch on
how it was proven. Proof kind is recorded for audit and for its recheck rule only.

This is the seam that keeps later ticket systems additive. The first slice ships
one proof kind, the **acting token**. OIDC, Kerberos or another ticket system later
adds a verifier for a new proof kind. It does not change grants, eligibility,
policy, records or the request field.

## Actor

The actor is the Application-owned service identity from ADR 0017,
`@service/<token UUID>`. Manual `executionAccount` Applications cannot declare
acting grants, because their identity is not tied atomically to the manifest
that declares the grant. Token rotation keeps the principal and therefore its
grants and proofs.

## Grant declaration

Grants are declared in the Application's root manifest, beside `service`:

```json
{
  "version": 1,
  "name": "chat-bridge",
  "service": { "handle": "chat-bridge", "role": "CONTRIBUTOR" },
  "acting": [
    {
      "grant": "chat-users",
      "ceiling": "CONTRIBUTOR",
      "personal": true,
      "proofs": ["acting-token"]
    }
  ]
}
```

- `ceiling` is VIEWER or CONTRIBUTOR. MANAGER and administrator ceilings are
  refused, as in ADR 0017.
- `personal` permits Personal space. It still requires the subject's own proof
  to include Personal.
- `proofs` lists the proof kinds the grant accepts. The first slice accepts only
  `acting-token`. A later federated kind names a trust root configured on the
  server, such as `oidc:corporate`.

**Trust roots never come from a manifest.** An Application can only reference an
issuer, realm or key that a server administrator configured separately. Otherwise
anyone able to deploy an Application could add their own identity provider and
assert any account.

A grant name is a reference, not a secret. The authenticated actor is already the
only identity that can use its Application's grants, so a separate grant
credential held by the same process would add no protection.

Deployment validates the declaration with the rest of the manifest. Removing a
grant, lowering its ceiling or removing a proof kind takes effect at activation.
Rollback follows ADR 0017's rule for the service declaration: rolling back to a
revision without a grant disables that grant, and no rollback can revive a wider
grant than the active revision declares.

### Eligibility

Each declared grant has a server-side eligibility record: the accounts that may be
acted for through it. Entries are explicit accounts or membership rules, for
example "members of project `research`", so administrators do not have to list
every person. Eligibility is managed by server administrators through
`acting.eligibility.set` and is checked when a proof is issued, when it is used,
and on every later recheck. Removing an account or rule ends that use immediately,
without redeploying.

## Subject proof

### Verifier contract

A proof verifier receives `(proof, actor, grant)` and returns either a refusal or
a verified subject:

| Field | Meaning |
| --- | --- |
| `account` | The one Plowshare account the proof identifies |
| `proofKind` | For example `acting-token`, later `oidc:corporate` |
| `proofRecord` | Stable identity of the proof for audit and recheck: a token UUID, or a federated `issuer` + subject + token identifier |
| `ceiling` | Optional narrower role and project set carried by the proof itself |
| `personal` | Whether the proof permits Personal space |
| `validUntil` | When the proof stops being acceptable for new work |
| `recheck` | `REVOCABLE_RECORD` or `ADMISSION_ONLY` (see [Durable work](#durable-work-and-revocation)) |

Every verifier must enforce **actor binding**: a proof is valid only when it was
issued for this Application and grant. A leaked proof must be useless to anything
except the actor it was bound to.

- **Acting token:** bound to the Application and grant when issued.
- **Future OIDC:** the token's audience must be Plowshare and its authorized party
  must be the client registered for this Application. An ID token issued to any
  other client is refused.
- **Future Kerberos:** the service ticket must be issued for Plowshare's service
  principal through constrained delegation by the Application's own principal. A
  forwarded ticket-granting ticket is refused.

### Identity links

The verifier maps an external identity to a Plowshare account through one
identity-link record: `{account, kind, issuer, externalSubject}`. The first slice
writes links of kind `acting-token`, with the issuer being the Application and the
external subject naming the person on the external channel, for example
`discord:123456789`. Future verifiers add links of their own kind to the same
record, created only by the account owner or by an administrator-configured
mapping rule. A missing or ambiguous link is a refusal; there is no fallback by
handle or email.

### First proof kind: acting token

The person creates an acting token in their own signed-in session:

```text
acting.token.issue {
  application, grant, externalSubject?,
  projects: [subset], personal: bool, expiresInDays: 1..365, requestId
}
```

- It is refused unless the person is eligible for the grant, the grant accepts
  `acting-token`, and the requested projects and Personal flag are within the
  grant. The person may choose less than the grant allows, never more.
- The response returns a one-time credential beginning with `psa_`. The server
  stores its digest only.
- The token is bound to that Application and grant. Presented by any other actor,
  it is refused and audited.
- The person lists and revokes their tokens from any client under **Connected
  services**. Administrators can revoke but never issue them.

The person then gives the credential to the integration, for example by pasting it
into a direct message to the bridge. That channel may retain it indefinitely,
which actor binding makes acceptable: the credential grants nothing to anyone who
lacks the Application's own token.

## Using a proof

The integration exchanges a proof for an **acting context** once, then refers to
the context on each request:

```text
acting.context.open {
  grant: "chat-users",
  proof: { kind: "acting-token", credential: "psa_…" }
}
→ { context: UUID, account, effective: {projects, personal}, validUntil }
```

Requests then carry `actingFor: {"context": "<UUID>"}`. Requests without
`actingFor` keep today's behaviour under the actor's own identity.

- A context belongs to the authenticated socket session that opened it, and
  closing that session ends it. The SDK's shared connection can hold contexts for
  many people at once.
- `validUntil` is the earlier of the proof's validity and a server maximum.
- Proof credentials cross the wire once per context, not once per request.

The exchange is the only operation that sees proof material. Federated kinds later
arrive at this same operation with a different `proof.kind` and produce the same
context, so request handling, policy and records do not change.

### Refused under an acting context

The first implementation refuses, whatever the ceilings allow:

- server administration, account, password, session and token operations,
  including issuing acting tokens;
- managing grants, eligibility or identity links;
- owner-only information controls: finalization, sharing, deletion and tags;
- project membership changes;
- approvals, which stay a direct human action;
- creating schedules or triggers.

Approvals and schedules are deliberate follow-ups, not oversights. Approving
through a chat bridge is useful, but it lets a compromised adapter approve
anything within the subject's authority. That needs its own decision.

## Ownership and attribution

- **Owner is the subject.** Conversations, jobs, memory, learned lessons, digests
  and information admissions are owned by the subject and appear in the subject's
  own clients. A conversation started over a chat bridge can be continued in the
  desktop.
- **Acting record.** Each admitted piece of work retains
  `{actor, grant, proofKind, proofRecord, externalSubject}`. Trajectories and
  history views show "via <Application>".
- **Accounting.** Usage is charged to the subject and attributed to the actor,
  following the SYSTEM attribution precedent. Service-account accounting pools
  remain separate work, as ADR 0019 already records.
- **Audit.** `admin audit` records proof issuance, revocation and refusal, context
  opening, refused operations, eligibility changes and grant changes at
  activation. It never records credentials or digests. Subjects can list their
  proofs and the work done under each.

## Durable work and revocation

Jobs, information tasks, message routes and orchestration runs keep their acting
record. Every later permission check that already rechecks current access,
including background work, delayed events, inbox delivery and receipts, also
rechecks:

- the subject account is enabled and still has the access being used;
- the grant is still declared by the active revision and accepts the proof kind;
- the subject is still eligible;
- the actor token is still valid and its service account still holds its grant;
- the proof itself, according to its `recheck` rule.

| Recheck rule | Meaning | Kinds |
| --- | --- | --- |
| `REVOCABLE_RECORD` | The proof is a server record. Revocation or expiry stops further checks immediately. | `acting-token` |
| `ADMISSION_ONLY` | The proof is an external ticket with its own short lifetime, for example one hour. It must be valid when the work is admitted, and the identity link and all the other conditions above are rechecked afterwards. Without this rule, a three-hour job would die when a one-hour ticket expired. Each federated kind's own decision sets its maximum admitted-work duration. | Future federated kinds |

Defining both rules now means a later verifier only declares which one it uses.

A failed recheck behaves like lost membership: pending work is refused at its
next check, and admitted work keeps the existing cancellation and receipt
semantics. Nothing falls back to the actor's own identity or to SYSTEM. The
subject can cancel any work done for them. The actor can cancel work it started
while its context or grant remains valid.

## Idempotency and recovery

`acting.token.issue`, the revocation operations and `acting.eligibility.set` take
stable request UUIDs with retained receipts, following the service-token pattern.
A lost reply is reconciled by reading the receipt; clients never replay
automatically. A changed submission reusing a UUID is refused, and a lost
credential is never reissued under the same token UUID. `acting.context.open`
creates no durable state and can simply be repeated.

## Contract changes and affected clients

| Area | Change |
| --- | --- |
| Manifest | Optional `acting` grant declarations, validated at deployment and versioned with the revision |
| Protocol | `acting.token.issue`, `acting.tokens`, `acting.token.revoke`, `acting.eligibility.set`, `acting.context.open`, `acting.context.close`, and the optional `actingFor` request field |
| Server | Grant, eligibility, acting-token, identity-link and acting-record persistence; a verifier registry with one `acting-token` verifier; the intersection check; acting rechecks in existing permission paths; actor attribution on work, usage and audit records |
| SDKs | All six SDKs expose the acting operations and a typed `actingFor` option on a shared connection |
| CLI, TUI, desktop, web console | Acting-token issuance and **Connected services** for each account; eligibility management for administrators |
| Integrations | None required. The A2A receiver keeps its alias model. Chat bridges and a future MCP executor can adopt acting contexts as SDK consumers. |

### What a later ticket system adds

A federated proof kind such as OIDC or Kerberos adds only:

1. server configuration of the trust root (issuer, signing keys or keytab, realm),
   which administrators own;
2. a verifier implementing the contract above, including actor binding;
3. identity links of its kind, plus the rules for creating them;
4. its `ADMISSION_ONLY` admitted-work duration;
5. a new value accepted in manifest `proofs`.

It does not change the policy intersection, eligibility, acting records,
ownership, accounting, audit shape, refused operations, `actingFor` or any
existing SDK call. Trusting an external identity provider remains its own
platform decision, recorded separately when that work is authorized.

## Migration, rollback and compatibility

New tables hold eligibility, acting-token digests, identity links, receipts and
acting records. Identity links and acting records carry a proof-kind discriminator
from the start, so later kinds need no reshaping migration. Work, usage and audit
records gain nullable acting-record references; historical rows have none and mean
"the owner acted directly". No existing identity, token, membership or ownership
changes meaning.

A server setting disables acting contexts. When disabled, `actingFor` and
`acting.context.open` are refused, records stay inert and existing tokens behave
as today. Rolling back to a build without the feature leaves the new tables and
columns unused. Work already recorded with an acting record keeps its subject
ownership.

## Alternatives rejected

- **Admin-set acting scopes on service tokens.** This was an earlier draft. A
  manifest declaration gives the same administrator control with deployment
  validation, receipts and versioning, and it keeps the grant tied to the
  Application that uses it.
- **A separate grant secret.** The same process would hold it beside the
  Application token, so it adds no protection.
- **Proof material on every request.** It multiplies credential exposure and
  places verification in every request path. The context exchange limits both to
  one operation.
- **Short-lived delegated tokens per subject.** Equivalent authority, but an
  adapter serving many people would need one socket and one renewal loop per
  subject.
- **Building federation first.** It would block the chat-bridge case on a much
  larger trust decision, while Discord and iMessage have no enterprise identity
  provider and need acting tokens regardless.

## Verification

- **Intersection matrix:** subject role, grant ceiling, proof ceiling and actor
  project grant, each lowered independently, for every role; eligibility removed
  independently.
- **Personal:** usable only when the grant and the proof both allow it.
- **Actor binding:** an acting token presented by another Application, or for
  another grant, is refused and audited.
- **Manifest:** an invalid declaration fails deployment; removal or narrowing takes
  effect at activation; rollback never revives a wider grant; a manifest cannot
  name an unconfigured trust root.
- **Revocation during work:** token revocation, subject disablement, eligibility
  removal, grant removal and actor token revocation, each during a running job, a
  queued information task and a delayed event, with no fallback identity.
- **Recheck rules:** a test-only `ADMISSION_ONLY` verifier proves that
  admitted work survives proof expiry, ends on identity-link removal, and needs no
  change outside the verifier registry.
- **Refused operations:** each refused family returns the policy refusal and an
  audit record.
- **Attribution:** ownership, trajectories, usage and audit carry subject, actor,
  grant, proof kind and external subject; historical rows read as direct.
- **Receipts:** lost-reply recovery and refusal of changed resubmissions for every
  acting mutation.
- **Real PostgreSQL tests** for concurrent issue and revoke, and for acting
  rechecks in background permission paths.

## Open questions

- Whether approvals through an actor are ever acceptable, and under what extra
  confirmation.
- Whether schedules created under an acting context should be allowed, bounded by
  the proof's validity.
- Whether ADR 0019's separate MCP executor should adopt acting contexts or keep
  its credential-subject model.
- Which federated kind comes first, and who may create its identity links.
