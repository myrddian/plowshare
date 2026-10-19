# Shared readability function work

Status: proposed; no SQL or authorization changes. Baseline:
`fc36351f239b62984363ababaa1de1139402aafb`.

This work connects the catalogue, navigation, document, event, inbox and authority
plans. It is not an additional persistence owner. A single JDBC statement can
repeat substantial database work through these functions.

## Current call graph

| Entry point or function | Current work to preserve and reduce |
| --- | --- |
| [Catalogue facets](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java#L274) | Eight named dimensions, plus graph and total queries; typed views add two dimensions. Thus 10 ordinary or 12 typed aggregate statements reuse the same selected SQL, not the same evaluated relation. |
| [Event admission and recipients](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java#L645), [dispatch readability](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationEventRepository.java#L63) | Test global, personal and selected project contexts repeatedly for a revision/account. Recipient insertion multiplies this work by candidate accounts. |
| [V172 readability](../../plowshare-server/src/main/resources/db/migration/V172__substrate_knowledge_reads.sql#L34) | Root readability checks revision eligibility and selection; complete readability also checks a recursive dependency closure. Every dependency uses revision readability again. |
| [V172 scope readability](../../plowshare-server/src/main/resources/db/migration/V172__substrate_knowledge_reads.sql#L9) | Repeats global eligibility, account/global action and project/service role resolution inside resource checks. |
| [V156 role resolution](../../plowshare-server/src/main/resources/db/migration/V156__scoped_rbac_grants.sql#L296) and [live grants](../../plowshare-server/src/main/resources/db/migration/V156__scoped_rbac_grants.sql#L116) | Project roles join a bounded recursive live-grant relation. Service roles also resolve their owner's project role. |
| [V163 global action](../../plowshare-server/src/main/resources/db/migration/V163__global_information_destination.sql#L2), [V158 eligibility](../../plowshare-server/src/main/resources/db/migration/V158__current_root_policy_and_global_read.sql#L16), [V138 active account](../../plowshare-server/src/main/resources/db/migration/V138__application_owned_identities.sql#L31) | Recheck account lifecycle and, where applicable, service credential issuance, revocation, expiry and owner state. |

These are the relevant definitions at this baseline, not permission rules to
simplify by assumption. Earlier measurements showed repeated calls to active
account, role and revision checks; see the [measurement context](README.md#measured-priorities).

## Implementation sequence

1. Profile complete candidate queries with representative cardinalities. Count
   nested function calls, recursive rows and buffers as well as statement time.
   Compare authorized result sets and plan shape under the same snapshot.
2. Create a typed, bounded authorized selection capability behind the owning
   repository. Evaluate whether one SQL statement can materialize actor eligibility,
   live grants and applicable role/scope facts once, then join candidate revisions
   and dependencies. A table-returning function may package this work; merely
   wrapping the current scalar function loop will not reduce it.
3. Feed facet aggregates from evaluated authorized candidates, preserving empty
   results, limits, deterministic ordering, tag graph and typed-view semantics.
   Avoid one final count query that evaluates all permission predicates again.
4. For events, share revision/dependency facts across the required scope tests and
   batch account evaluation where safe. Retained recipient rows determine discovery,
   not permanent permission: recheck current disclosure authority at delivery.
5. Inspect the existing grant recipient/parent indexes and project/account/scope
   keys before proposing new indexes. Only measured remaining scans justify new
   access paths. Keep replacements in new Flyway migrations and validate old/new
   authorization equivalence before switching callers.

Preserve recursive cycle termination, the dependency limit of 1024 with its
fail-closed overflow sentinel, live-grant lineage depth limits, ancestor revocation,
service-owner restrictions, root audience/privacy, restricted scopes, account-home
selection and linked-project semantics. SQL `STABLE` is not result memoization;
statement/transaction snapshots still need a declared freshness contract.

## Cache boundary and verification

Reuse actor and revision facts within one valid operation snapshot. Cache immutable
document content separately from these mutable permission decisions. A shared
descriptor or dependency closure requires source versions and every-writer
invalidation for grants, memberships, accounts, token lifecycle, scope/project
changes, root policies, navigation heads, links and input obligations. Token/grant
expiry is time-driven, so notification delivery alone cannot keep decisions fresh.
Do not serve a stale allow decision while waiting for LISTEN/NOTIFY.

Use the [shared tracked DTO and delivery contracts](delivery.md) for any admitted
projection. Focused mock tests count repeated repository work; later focused
PostgreSQL tests must establish equivalent selection across actor kinds, scopes,
revoked ancestors, expired credentials, dependency cycles/overflow and concurrent
policy changes. Establish representative cold-query improvement independently of
any warm cache. No database tests are required for this planning document.
