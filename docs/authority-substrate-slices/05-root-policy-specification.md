# Slice 05 root policy specification

Written before enforcement changes. Existing selectors and commands remain the
public contract; no new audience editor, group directory or execution route is added.

## Legacy classification

The new resource policy is authoritative for every revision, including pinned
history. Migration retains all per-revision policy rows as evidence. It never
unions historical audiences. Consistent, explicitly Shared revision policies
retain a `LEGACY_SHARED` audience, available only to their previously eligible
human readers. This is not GLOBAL_READ. A new authorized share command is the
explicit review that selects GLOBAL_READ; unshare selects the resource's home.

Mixed Shared/home flags narrow to HOME and retain a review marker. Conflicting
private subjects or unclassified/quarantined homes stay quarantined. Existing
Personal/private eligibility is recorded as an explicit account UUID, separately
from created_by. The live ownerOnly setting consults this reconciled privacy
principal; it must never compare the caller with created_by. Mandatory privacy,
inputs and retained reference obligations continue to constrain disclosure.
GLOBAL_READ cannot be added while a private/ownerOnly restriction applies.

New content revisions inherit the same resource policy without changing its
version or audience. Initial resource policy is HOME; mapped Global-account
knowledge uses its explicit deployment-wide coverage. Assignment of quarantined
legacy data uses the existing audited administrative operation and establishes
the root home policy (or human-only LEGACY_SHARED when that existing review
explicitly selects Shared), without manufacturing creator provenance. The existing
configured migration operator remains its admission boundary. Policy changes
have immutable version history and actual actor attribution.

## Reading and selection

GLOBAL_READ is contextual resource READ held by every active authenticated human,
Application/service account and service credential. It is not project membership,
WRITE, control, log visibility or a scope-wide READ grant. There are no individual
exclusions. Disabled/bootstrap accounts and expired/revoked credentials are not
eligible. Private scopes still require their existing effective memberships and
Application/token ceilings.

Existing Shared/includeShared selectors explicitly select general-read sources.
Service credentials may use these read selectors for GLOBAL_READ resources;
LEGACY_SHARED remains unavailable to them. Personal remains unavailable to service
credentials. Project-only queries do not silently append general-read material;
internal service-run selection continues to omit includeShared. Acting proof and
retained operation/tool restrictions are not widened by this slice.

Every input and retained reference checks its own current root policy and
lifecycle. Queries filter before counts/paging/facets. Root restrictions apply to
history, export, evidence and events as well as the latest body. Public knowledge
does not make its processing conversations, prompts or receipts public.

## Control and concurrency

Existing share/unshare changes the root audience under source MANAGER authority;
ordinary CONTRIBUTOR writes cannot change permissions, including through revision
creation or old per-revision writers. Global-account control requires human ADMIN
over that knowledge scope; a project manager cannot transfer control into Global.
No Global creation/move selector is added. Existing navigation-policy management
uses live scoped control, retaining mandatory-policy release restrictions. Tag
facets and tag groups are not security audiences; no arbitrary security-group
mechanism has been verified or added.

Root audience changes take the existing exclusive navigation-control lock before
home/grant/resource locks. Content writes retain the shared lock. Versioned root
updates and audit append commit together; account-bound command receipts remain
idempotent. Reads use current committed policy in their query snapshot; an already
running read may complete under its earlier snapshot, while subsequent reads of
any revision observe revocation. No replay after an uncertain effect is added.

## Curation boundary

A confident model judgement files a proposal, not an automatic Global promotion.
Existing human acceptance must prove authenticated source MANAGER/release and
human Global ADMIN destination authority; the request's `by` remains provenance,
never proof. Existing source-information safety checks remain. Rejection and
proposal/receipt recovery retain their current contracts. This is admission to
the existing copy-and-retire operation, not a new grant/copy/transfer product or
SYSTEM service authority model.

## Rollout boundaries

V155 is additive; V154 and earlier migrations are immutable. Old per-revision
share writers fail closed. Historical flags and policy versions remain available
for reconciliation. Downgrade cannot restore a historical permissive revision as
an authorization fallback. Full tenant entities, arbitrary group grants, Global
selectors/type registries and runtime/SYSTEM/acting cutovers remain later slices.
