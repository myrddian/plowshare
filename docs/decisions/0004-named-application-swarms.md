# Named Application swarm types and retained topic selections

Status: accepted for implementation following the application deployment design.

A project can support different teams for different work. A single `swarm.md`
restricts that choice and allows later file edits to change an existing topic's
participants. The user authorized replacing that convention while there are no
other deployed projects to migrate.

Applications expose `swarm/<name>.md` or `.json` under their registered root.
Server-owned External/Personal tiers and the shipped default use named `swarm/`
directories too. This replaces the old single-file convention; it does not scan
both conventions or silently choose one of several types. Duplicate names across
formats, invalid sources and workspace escapes are refused. The catalog is an
authenticated project read (`swarm.types`); opening remains contributor work.
SDK-only selection cannot enforce retained membership or recovery, so the owning
board runtime and repository must implement this general platform capability.

A root opening stores an immutable definition snapshot atomically with its
message, participant seats and wakes. It includes the type name, source revision,
description, usable members and configured budget. Subtopics inherit it. Posts,
retries and recovery resolve membership from that retained record rather than
current configuration. Description text grants no instructions or permissions.
Agents remain ordinary definitions: live grants, approvals, cancellations,
accounting and model-pool availability retain their existing owners.

Opening receipts include the requested selector in their identity. An identical
retry reads its retained receipt before looking at current files. Changing the
type with the same request UUID is refused. Historical public topics with no
snapshot stay readable and cannot invent a selection; private messaging keeps
its distinct lifecycle and carries no public swarm.

The shared operation/DTO graph generates equivalent Java, TypeScript/Node,
Python, Go and .NET contracts. Coverage includes configuration validation,
workspace fences, selection ambiguity, retained membership, JSONB mapping,
child inheritance, explicit retry and receipt recovery after source removal.

This decision does not deploy an external Python service or introduce an
Application deployment operation. The root layout gives that separate deployment
capability an explicit resource to validate and install.
