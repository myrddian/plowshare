# Account-scoped orchestration receipt authorization

Status: accepted

## Platform need and authorization

A client can lose the reply to an accepted `orchestration.start`. Its safe recovery
operation is `orchestration.receipt` with the original request UUID; the client
may not yet know the run ID. Application service accounts must be able to read
that retained receipt within their current project ceiling, just like interactive
accounts. Central authorization previously recognized only run IDs and explicit
projects, so it refused service receipt reads before resolving the owning run.

The user separately authorized this server authorization fix. It addresses the
platform's existing start/recovery contract for every SDK consumer, independently
of any particular integration.

## Decision

Treat a receipt request UUID as a distinct authorization reference. A narrow
resource-repository operation resolves its project using both the authenticated
account and the request UUID, joining the committed receipt to its retained run.
It reads the run's actual project, not an untrusted payload or its old start input.
Unknown and foreign receipts give the same refusal.

Then apply the existing read permission to that project. Service tokens remain
unable to read global work; expired, revoked or out-of-scope credentials do not
acquire access through receipt ownership. The receipt handler retains its account
ownership and information-log visibility checks. Repository failures fail closed.
Input conversion rejects absent, malformed and shortened request UUIDs before
repository access.

This is read-only recovery. No start, resume, inference, accounting or delivery
operation runs while checking a receipt. The existing `account/requestId` start
identity and atomic receipt/run commit remain unchanged.

## Alternatives and compatibility

Clients cannot inspect status without the lost run ID. Supplying a project does
not fit the existing receipt request contract and would not prove the run's actual
scope. Replaying a start to discover its result is not a read-only recovery policy.
Bypassing central authorization would lose the token/project ceiling. Adapter-side
SQL or an internal endpoint would violate the public integration boundary.

There is no wire, SDK, migration or data-format change. All SDK languages keep
using the existing `orchestration.receipt` request with a UUID. Human receipt reads
now also check current membership in the retained run's project. Global human
reads keep their existing ownership/information policy. Rolling back restores the
previous service-token refusal without changing retained data.

## Verification

Mocked routed tests cover account-bound recovery, repeated reads without new work,
revoked project scope, global work, unavailable persistence and retained run/evidence
visibility. Decoder tests cover malformed inputs. A focused PostgreSQL test proves
UUID binding, same-UUID isolation across accounts and null/global project mapping.
Repository checks run without the broader database suite. Live recovery requires
a server deployment of this change and must inspect the original receipt only.
