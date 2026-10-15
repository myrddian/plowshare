# ADR 0026: Service execution on behalf of an account

Status: proposed. Drafted for discussion; implementation is not authorized.
Changing core requires the separate platform authorization described in
[ADR 0001](0001-integration-boundary.md).

This is not agent delegation. `agent_run`, `calls:` and
[ADR 0011](0011-application-scoped-agent-delegation.md) concern one agent starting
another inside an already admitted identity. This decision concerns which account
an authenticated Application may act for. It defines a general platform
capability available to external integrations through the public SDKs.

## Platform problem

Every request has exactly one execution identity: a human account or a service
token principal `@service/<token UUID>`. Authorization, ownership, conversations,
memory and accounting all follow that identity. There is no way to express
"this Application performs this request for that account".

An external integration can serve both ordinary external users and people who
also have a Plowshare account. Ordinary external users can use the integration's
own bounded service identity. A Plowshare user may additionally want their own
Personal space, project roles, conversations and memory. This can arise in a chat
bridge, a support portal, a workflow connector or another external Application.
Discord illustrates a capability available to any such integration.
For that personal access, today's alternatives are insufficient:

- **Run everything as the adapter's service principal.** Every external user shares
  one authority, one conversation owner and one memory scope. Service tokens
  cannot reach Personal space. Plowshare cannot tell which person asked for what.
  The A2A receiver's external client aliases follow this model; it remains useful
  for ordinary external users but does not provide a person's own account scope.
- **Hold each person's unrestricted login credential.** The adapter becomes full
  impersonation: all of the account's authority, every session, no attribution,
  and a single compromised adapter exposes every linked account.

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

### Two ways to use the same integration

The integration authenticates to Plowshare with its own Application token. The
Application and acting-context flow remain separate: a person's acting token is
additional authority for a specific subject, not a replacement Application login.

| User | Execution and authority |
| --- | --- |
| Ordinary external user without a Plowshare account | Requests run under the integration's service identity, within its permissions, the Application's granted capabilities and the selected execution context's restrictions. An external alias identifies the caller but grants no additional access. |
| Plowshare user who provisions an acting token | The same authenticated integration opens an acting context for that account. Requests using that context can reach the subject's explicitly permitted scopes, including Personal, within the acting grant and token limits. |

An integration may constrain an ordinary external user to specific resources or
operations. For example, a Discord bridge may pin a user to a specific conversation
with an agent. That routing restriction narrows what the user can do; it does not
confer ownership of the conversation or access to a Plowshare account. An external
identity, channel, resource ID or choice of agent cannot itself open an acting
context. The authority model does not depend on a chat channel, conversation or
interactive session with an external user.

The initial personal setup is direct provisioning: the Plowshare account owner
places their acting token in the integration's private credential configuration.
A personal bridge is one example. The token is not sent through an external
message or stored as ordinary user content. An integration may be configured for
one account; an integration serving multiple people must keep each person's
credential, context and request routing separate.

### Authority for an acting context

Acting for a Plowshare account needs three parts with distinct ownership:

| Part | Owner | Answers |
| --- | --- | --- |
| **Actor**: the Application's owned service identity | Server administrator, through deployment | Who is asking? |
| **Grant**: a named declaration in `plowshare.json` with an eligibility list | Server administrator, through deployment; eligibility through grant management | What may this Application ever do for anyone, and for whom? |
| **Subject proof**: evidence of a specific account's delegated consent and its limits | The person, or a trusted ticket system acting for them | For whom may this request run, and with what delegated permissions? |

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

Personal access is a separate, explicit capability: the acting grant and the
subject's proof must both permit it, and the subject must be enabled and eligible.
The Application service account does not gain Personal membership. The server
admits access to that subject's Personal scope through the acting context while
retaining the Application as actor. This supplies access unavailable in the
ordinary external-user path without widening the Application's own identity.
For server projects, the intersection above still applies; a person's token does
not bypass the Application service account's project ceiling. Conversation,
agent and tool restrictions continue to apply wherever relevant in either path.

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

The integration's Application token authenticates the socket and supplies the
actor's own permissions. The user's acting token authenticates the subject's
delegation and supplies its permission limits when opening an acting context. In
that sense the user's token carries both AUTH and PERMS for their delegated
account access; it does not authenticate the socket as a different Application
or grant the integration an unrestricted human session. Both credentials remain
at the integration, including at the bridge when the consumer is a chat bridge.

## Grant declaration

Grants are declared in the Application's root manifest, beside `service`:

```json
{
  "version": 1,
  "name": "external-integration",
  "service": { "handle": "external-integration", "role": "CONTRIBUTOR" },
  "acting": [
    {
      "grant": "account-users",
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

An acting token identifies its issuing Plowshare account directly. An integration
configured for that account does not need an external identity-link record
to establish the subject. Administrators manage eligibility, but do not need to
manually map an external user for this personal setup.

External user routing is a separate responsibility. When an integration accepts
external requests, it must authenticate the external caller and apply an explicit
configured binding before choosing a person's credential or acting context. Even
an integration configured for one account must restrict which callers can use its
owner's delegation. Possession of an external user ID or knowledge of a resource
ID is not proof of control of that identity. Ordinary users cannot select another
person's context or inherit the integration owner's token.

If an external identity link is recorded, it has the shape
`{account, kind, issuer, externalSubject}`. For `acting-token`, the issuer is the
Application and the optional external subject identifies the external user. The
account owner establishes that association during provisioning. Self-service
identity linking for a shared integration remains separate design work. A missing
required binding or an ambiguous binding is refused, with no fallback by handle,
email or another person's context. Future federated verifiers may require links
of their own kind, created only by the account owner or an
administrator-configured mapping rule.

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
- The response reveals a credential beginning with `psa_` once. It remains usable
  until expiry or revocation; the server stores its digest only.
- The token is bound to that Application and grant. Presented by any other actor,
  it is refused and audited.
- The person lists and revokes their tokens from any client under **Connected
  services**. Administrators can revoke but never issue them.

The person provisions the credential directly into the integration's private
credential configuration, separate from its Application token and public routing
settings. The credential is held at the integration and passed only to
Plowshare's proof exchange. For a bridge, this means provisioning directly to
the bridge, never through a Discord DM, channel or agent conversation. Other
integrations likewise keep it out of ordinary external messages, tickets,
documents and request content. Credentials must not appear in manifest source,
conversation history, ordinary configuration summaries or logs.

Actor binding means the acting token alone is insufficient without the bound
Application's authentication. The integration intentionally holds both
credentials and is trusted to exercise the delegated permissions until expiry or
revocation. The acting token records continuing consent; it does not prove the
person is present for each later request.

## Using a proof

After authenticating with its Application token, the integration exchanges a
person's proof for an **acting context** once, then refers to the context on each
request for that person:

```text
acting.context.open {
  grant: "account-users",
  proof: { kind: "acting-token", credential: "psa_…" }
}
→ { context: UUID, account, effective: {projects, personal}, validUntil }
```

Requests then carry `actingFor: {"context": "<UUID>"}`. Requests without
`actingFor` keep today's behaviour under the actor's own identity, including the
ordinary external-user path. An invalid or expired acting context is a refusal;
the integration must not silently drop `actingFor` and retry under its service
identity.

- A context belongs to the authenticated socket session that opened it, and
  closing that session ends it. The SDK's shared connection can hold contexts
  for many people at once.
- `validUntil` is the earlier of the proof's validity and a server maximum.
- Proof credentials cross the wire once per context, not once per request.
- An integration configured for one account normally uses one subject context. A
  shared integration may use several, but opening a context for one person does
  not authorize other external users or change the socket's Application
  identity.

The exchange is the only operation that sees proof material. Federated kinds later
arrive at this same operation with a different `proof.kind` and produce the same
context, so request handling, policy and records do not change.

### Refused under an acting context

The first implementation refuses, whatever the ceilings allow:

- server administration, account, password, session and token operations,
  including issuing acting tokens;
- managing grants, eligibility or identity links;
- owner-only information controls: finalization, sharing, deletion and tags;
- content-type management: creating type versions, activation and deprecation;
- explicitly clearing a resource's content type (`clear` typing mode);
- project membership changes;
- approvals, which stay a direct human action;
- creating schedules or triggers.

Approvals and schedules are deliberate follow-ups, not oversights. Approving
through a chat bridge is useful, but it lets a compromised adapter approve
anything within the subject's authority. That needs its own decision.

### Typed information under an acting context

The [authored knowledge design](../authored-knowledge-design.md) proposes content
types and metadata-only revisions. Type management is refused above, consistent
with that design's rule that model tools cannot manage types and service
credentials gain no administrative authority.

Typed admissions and `information.content.revise` are permitted in `inherit` and
`set` modes, within the effective role. The subject owns the resource, and a
metadata-only revision makes no model call, fetches nothing and spends no
allowance. The revision records the acting record as its revising actor, so
history shows the change came through the Application. `clear` is refused because
it discards a resource's declared structure; it stays a direct owner action.

If that design changes its owner or processing rules before acceptance, this
section follows it.

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
- the actor's Application token is still valid and, for server-project access,
  its service account still holds the required project grant;
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
| Integrations | Adoption is optional for any external integration consuming the public SDKs. Existing service-only consumers, including the A2A receiver's alias model, keep their current behavior. Chat bridges, support portals, workflow connectors and a future MCP executor can adopt acting contexts without protocol-specific authority rules. |

Integration adoption keeps Application authentication for both user paths. An
integration configured for one account adds private acting-token provisioning and
subject-context routing through the public SDK. A shared integration additionally
needs external identity linking and user isolation; the first slice does not
require an administrator-managed external user directory or an external-message
credential delivery flow. Each adapter owns its external authentication and
routing; the server owns account admission, permissions and durable lifecycle.

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
- **Building federation first.** It would block direct provisioning to external
  integrations on a much larger trust decision. Acting tokens support integrations
  whose users do not share an enterprise identity provider.

## Verification

- **Intersection matrix:** subject role, grant ceiling, proof ceiling and actor
  project grant, each lowered independently, for every role; eligibility removed
  independently.
- **Personal:** usable only when the grant and the proof both allow it.
- **Actor binding:** an acting token presented by another Application, or for
  another grant, is refused and audited.
- **Provisioning and authentication:** the integration retains its Application
  login; an acting token alone cannot authenticate as the Application. Direct
  personal provisioning opens the issuing account's context without an external
  link; neither credential is exposed through ordinary external user content,
  history, summaries or logs.
- **Two user paths:** ordinary external users stay within service permissions,
  Application grants and conversation/agent restrictions, with no Personal access.
  A Plowshare user's valid context enables only their allowed scopes. Pinning a
  conversation never grants account ownership or additional authority.
- **Routing isolation:** missing or ambiguous required external bindings and
  attempts to use another person's context are refused. An unapproved external
  caller cannot use the configured account owner's token. Invalid delegated
  requests never retry under the service identity or another subject.
- **Integration independence:** exercise both user paths through a chat-bridge
  fixture and a non-chat integration fixture using the same public SDK contracts.
  Admission, permission intersections, ownership and revocation must not depend
  on Discord identifiers, channel metadata or a conversation being present.
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
  grant and proof kind, plus the external subject when configured; historical
  rows read as direct.
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
- How a shared integration proves control of an external identity during
  self-service linking. Direct token provisioning to an integration configured for
  one account does not depend on that ceremony and does not send credentials
  through ordinary external messages or user content.
