# Decision 0017: Application-owned execution identities

Status: accepted and implemented.

## Platform need and alternatives

Public service-token creation needs an existing project. An SDK-only installer can
deploy, create a token, substitute its principal and deploy again, but cannot own
atomic source activation, schedule ownership, dormant authentication or recovery.
Applications need a server-owned identity lifecycle independent of any collector.
This separately authorized platform extension keeps integrations on public SDKs.

## Declaration and authority

An optional root manifest `service: {handle, role}` requests one Application-owned
service account. Roles are bounded to VIEWER or CONTRIBUTOR; MANAGER is refused.
The handle must be unused. A matching name never adopts an existing human, service
account or token. Server administrator authority is required for deployment,
reservation, issuance and rotation. Ordinary Application membership is insufficient.
Human manager grants remain explicit in the manifest and project membership.

The declaration supplies the service owner's manifest role. Do not repeat that
handle in `access.accounts`. Deployed `executionAccount` resolves to the retained
`@service/<token UUID>` principal. Requested provider, port and worker authority
inherits that principal; it is still explicitly granted by the Application.

## Reservation, activation and recovery

The complete source package and destination are validated before reservation.
Reservation retains one token UUID and public service handle, but discards the
initial secret and sets `credential_issued=false`. Both authentication and queued
account authorization reject this dormant token. Concurrent mutations serialize
under the existing administrator transaction lock before identity and project locks.

Filesystem staging and reservation are separate from database source activation.
A failed activation may leave a dormant reservation and immutable staged source;
neither can run. Source head, release, deployment receipt, project placement,
initial service membership/token scope and schedule enrollment commit together.
First enrollment uses the retained token principal. Updates and rollback preserve
historical schedule owners and never restore removed live membership.

`application.token.issue` requires ACTIVE identity, matching active manifest,
enabled service owner and current role. It returns a bearer secret once and stores
only receipt metadata. A lost reply is reconciled through
`application.token.receipt` with the original request UUID and submitting actor.
The operation never reissues a committed UUID. Explicit rotation uses a new request
UUID and the same retained token UUID, invalidates the old secret, retires session
credentials and publishes session revocation after commit. Status includes the
current credential request UUID so private recovery cannot restore an older secret.

Tokens expire after the explicitly selected 1–365 days. Revoke with the existing
service-token administration operation; an explicit owned-token rotation can
renew its secret if owner, project membership and manifest grants remain valid.
Generic token rotation refuses owned tokens and points to the Application operation.
Deleting/recreating the project cannot rebind its retained identity or restore
authority. The reservation survives deletion; handles and token UUIDs remain owned.
Changing or removing the declaration, including through rollback, is refused.
Manual `executionAccount` Applications remain supported and are not adopted.

## Controls and verification

All SDKs expose status, issuance and receipt operations. CLI and TUI use their
shared command catalog. Desktop and web console reveal secrets only in transient
controls, clear them on dismissal/closure and persist only pending request metadata.
Unknown replies fence further issuance until receipt reconciliation; internal
errors are uncertain, not permission to replay. Python installation uses one
deployment, explicit issuance and private request-linked secret artifacts.

Mock-based tests cover authorization, closed configuration and request ownership.
Focused PostgreSQL tests cover dormant authentication, stable rotation/revocation,
reservation uniqueness, concurrent activation/issuance, rollback and durable receipt
identity. Existing manual deployment/rollback and membership revocation tests remain.
The live Network Privacy Watch installation is not migrated by this change.
