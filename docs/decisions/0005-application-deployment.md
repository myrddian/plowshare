# Server-owned Application source deployment

Status: accepted following the user's authorization to implement Application deployment.

An Application is a workspace root with a valid version 1 `plowshare.json` and
resource directories directly in that root. Applications have no `.plowshare/`
directory. Previously, creation admitted a source
location but did not transfer a complete Application or retain release history.
DISJOINT source placement continues to belong to an external deployment workflow.
The new deployment lifecycle owns MANAGED Application source on the server.
The Python collector and its web interface remain separately operated integrations.

This is a general platform capability. An SDK-only copy script cannot atomically
switch server placement, retain a receipt and enroll server schedule reconciliation.
The server therefore provides `application.deploy`, `application.activate`,
`application.deployment.status` and `application.deployment.receipt` over the
existing authenticated WebSocket. Java, TypeScript/Node, Python, Go and .NET
expose equivalent typed contracts. CLI and Desktop package a selected local folder;
packing does not enable filesystem lending, start processes or execute install hooks.

Packages contain at most 128 regular UTF-8 text files, 64 KiB per file, at most 16 directory levels and 128 KiB
in total. Portable paths exclude traversal, links, local hidden state, build output
and case-insensitive collisions. Hidden `.plowshare/` source is refused, never
silently packed or used as a fallback. The root manifest must match the project.
Existing runtime parsers validate agents, skills, orchestrations, named swarms,
hooks and Relay configuration before publication. Hook validation only parses
syntax; it does not invoke hooks or execute scheduled work.

Deployment requires a server administrator and destination FileStore MANAGER
access. The destination is a dedicated relative directory. Runtime writable
areas must be separate from retained source. Manifest grants, project membership,
agent tool grants, approvals and Relay port authorization retain their owners;
the existing first-creator membership rule applies to first deployment only.
Ordinary Application access still needs its explicit manifest grant, and updates
do not add members. Tool and Relay grants remain explicit. Missing or invalid manifests fail
closed and cannot expose a lower client tier.

Source stages under `destination/revisions/<revision UUID>` before the database
transaction. The project row lock compares `expectedRevision`, changes only
source placement, stores the retained release and immutable receipt, and enrolls
the first server schedule source in one transaction. Existing project identity,
membership, conversations, information, memory and running work are preserved.
Updates keep the admitted destination and writable areas. First deployment refuses
an existing client/server source; adoption and DISJOINT workflows are separate.

Receipt identity includes account, project, request UUID and the exact submitted
content and placement. An identical explicit request reads its receipt before
checking current files; a changed submission using that UUID is refused. Delivery
uncertainty requires receipt inspection and never causes automatic replay.
Concurrent updates with the same expected revision cannot both activate.
Activation of a retained revision checks its digest and validates current runtime
contracts again. It switches source for future resolution; it does not undo work
already admitted or executed. Resource caches include source directory identity,
so changing revisions invalidates them even when file timestamps and sizes match.

FileStore configuration and external filesystem operators remain trusted source
owners. Retained directories are excluded from runtime write admission; an operator
can still change them outside Plowshare. Activation detects modified retained source.
Filesystem staging cannot participate in PostgreSQL rollback: rejected/crashed
stages can remain on disk, and this implementation does not garbage-collect them.
Only durable committed placement is active. Status lists the most recent 100
releases with the active release always included; receipts remain individually
addressable. Operators must back up source files together with the database.

Verification includes malformed and unauthorized packages, private workspace
fences, immutable source/write separation, offline resource resolution, client
replacement refusal, SDK conformance, CLI lost delivery, Desktop reload recovery
and compact layout. Focused PostgreSQL tests cover migration, retained row mapping,
project identity preservation, concurrent activation and transaction rollback.
