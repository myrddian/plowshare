# Slice 13 existing contract coverage

Recorded before enforcement changes, 18 October 2026. Scope is existing operations
and selectors only. No API, role vocabulary, scope kind or SDK method is added.

## Operation and compatibility inventory

| Existing operations | Existing selector and cutover |
| --- | --- |
| `conversation.open`, HTTP conversation create; `agent.run`, HTTP agent run; `orchestration.start`; HTTP `image.upload` | Omitted/null project selects human Personal or an active Application credential's exact single project. Generic service tokens, disabled/expired identities and ambiguous credentials refuse omission. Explicit project names retain live admission; no first-project choice. Resource-bound continuation keeps its resource-derived home. Ordinary Global execution remains refused. |
| `agent.list`, HTTP agent list, `orchestration.definitions` | Explicit project lookup must succeed before definition lookup. Missing, invalid or unavailable projects cannot become deployment/Personal lookup. Omitted human catalogue context retains existing Personal/deployment layering. Service catalogue callers still need explicit projects. |
| `information.list`, `facets`, `search`, `rank`, `status`, `await`, `read`, `outline`, `symbols`, `evidence.read`, `content.get`, `events` | Existing personal/project/shared selection; omitted selection resolves trusted account context. Explicit Shared/includeShared selects general-readable roots, never membership or private inputs/logs. Actual retained run homes cannot use legacy null as Personal. |
| `document.list`, `detail`, `chunk`, `retrieve`, `rank`, `stance`, `search`, `citations`; HTTP document reads/manual reads | Reuse scoped document readers and SQL root/input policy. Permit authenticated service readers at the existing boundary. HTTP keeps its existing optional project query; it has no new Global UUID selector. Scope-unaware SDK methods are gaps, not permission to add an interface. |
| `information.upload`, `acquire`, `record.report`; HTTP multipart upload | Existing human Personal/Application project defaults and explicit project. No writable Global destination is expressible here. Shared remains a read selection; creation refuses instead of becoming null/Global. Existing request IDs, authored-view validation and receipt rules remain. |
| `information.inventory`, `acquisitions` | Existing intake/acquisition listings retain authenticated admission/receipt privacy and their owning selection checks; GLOBAL_READ does not publish private work. |
| `information.revise`, `replace`, `refresh`, `tags`, `tags.groups`, `finalise`, `link`, `unlink`, `withdraw`, `exclude`, `unexclude`, `restore`, `delete`, `retry`, `rebuild`, `allowance`, `evidence.record`, `ask` | Keep current owning resource, WRITE/lifecycle/input/processing checks and receipts. Shared does not confer content WRITE or inference. Additional Global content-write/processing destinations and missing inference allowances remain gaps. |
| `information.share`, `unshare` | Existing revision plus selection can identify an existing Global-owned resource. Admit audience checks rather than generic content WRITE at the frame boundary; owning root policy still requires human Global ADMIN or home MANAGER, checks inputs and controls every revision. No client label grants privilege. |
| `information.types.capabilities`, `types.list`, `types.get`, `types.validate`, `types.version.create`, `types.activate`, `types.deprecate`, `content.revise` | Existing supporting view, Personal/Project types and exact version/revision checks. No Global schema registry. Typed command selection must agree with the admitted context. |
| `information.navigation.capabilities`, `documents.list`, `documents.facets`, `documents.get`, `relationships.list`, `relationships.create`, `relationships.remove`, `policy.get`, `policy.set`, `views.list`, `views.get`, `views.save`, `views.delete` | Existing explicit typed selections, repository policy and private saved-view ownership; omission is not synthesized into a different command. Existing service policy-management refusals remain. |
| `information.interchange.capabilities`, `interchange.export`, `interchange.readable`, `interchange.origin`, `interchange.import` | Existing explicit typed scope and root/input policy. Export does not confer read access to referenced/private inputs. Import requires its existing owned destination; no new Global destination. |
| `information.migration.list`, `migration.inspect`, `migration.adopt`, `migration.release` | Existing administrative migration surface and existing policy. Do not run legacy classification or migration rehearsal in this slice. |
| Memory/proposal reads and controls, Relay, schedules, jobs, acting requests | Keep existing resource/operation authority and slice-specific fences. No new account UUID field or Global alias in project selectors, no broader acting source, no new controls. Legacy memory/null-home removal and integrated proof remain slice 14. |

## Definition and client boundaries

Deployment/bundle definitions remain definition sources. Their ordinary bots run
under the requesting account; their disk location confers no SYSTEM authority.
SYSTEM workers retain their existing deployment-only resolver and purpose sandbox.
An explicit deleted project or broken authoritative merged definition tier must
refuse, including default-bot/procedure lookup, rather than substituting the root
tier. Per-file disabled definitions still have their existing refusal records.

The existing public project roles remain MANAGER, CONTRIBUTOR and VIEWER. They are
compatibility helpers, not an ADMIN/SYSTEM/GLOBAL_READ assignment API. Existing
TypeScript information selection already supports personal/project/shared and
includeShared. Its generated request/response graph is not extended. CLI/TUI use
that shared surface; Desktop and Console have their own supported subsets. No SDK
parity is assumed and no new UI is required. Legacy null memory homes and operator
definition/schedule directories retain their current storage meaning until slice 14.

## Refusal and verification

Protocol version remains `plowshare-v1`. Unrepresentable targets, unknown fields in
scope objects, conflicting selectors and invalid explicit names return the existing
BAD_REQUEST/refusal contract before effects. Missing explicit project records and
storage outages do not fall back to root; outage errors propagate normally.
Ordinary projects named `global` remain ordinary names. Account UUID text is never
decoded as an account scope by a project selector. Unknown/uncertain mutations are
not downgraded, replayed or retried as another account.

Use mocked token/identity/membership repositories and public HTTP/frame boundaries
for selectors, audience management, general reads and private-scope refusal. No SQL,
persisted selector or schema change is planned, so PostgreSQL is not required.
Regenerate contracts only if the inventory reveals an actual shared graph change;
otherwise report unchanged contracts and the specific unsupported surfaces.
