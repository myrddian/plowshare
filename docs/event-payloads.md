# Typed event payloads and deployment compatibility

Events reach application logic as explicit immutable DTOs. `Intake` accepts an
`EventPayload`; `FiringStore` accepts and returns typed firings. The JDBC repository
owns JSONB decoding. Board seats and direct-message continuations consume already
validated values before queue claims, leases or model execution.

## Manual and system contracts

`event.fire` accepts an omitted/null `data`, `{}`, or `{ "text": "An observation" }`.
Text must be nonblank, at most 1,048,576 characters and NUL-free. The event name
must be nonblank, unpadded, at most 1,024 characters and free of control characters.
Unsupported fields, nested arbitrary objects and wrong types fail before insertion.
Authentication and existing event/trigger authorization remain mandatory.

```json
{"event":"release.observed","data":{"text":"The configured release check completed."}}
```

Cron content has the existing `{schedule, fire_at}` shape, with an ISO instant
matching the firing's schedule and time. Board seat wakes retain their established
reason, message, author and optional positive retry limit. An old empty seat wake
still means opened. Direct-message wakes retain message identity and the defined
approval/delegate continuation fields. Continuation validation happens before
claiming or running work. Manual input cannot manufacture these internal families.

The JSONB column and existing queue SQL, indexes, tick uniqueness, target claim
fencing, per-trigger caps and wake coalescing are retained. Typed reads do not
rewrite stored JSONB. `event.fire` and `firing.list` retain the v1 firing fields,
including a JSON **string** in `data`; the transport view serializes the DTO.
Whitespace in serialized JSON is not an identity or authorization guarantee.
No mutation is replayed because its result could not be decoded.

## Required preflight before deployment

Inventory a restored copy of the deployment database before deploying typed reads.
Configure all three values explicitly through the environment or a secret manager:

- `PLOWSHARE_EVENT_PREFLIGHT_DB_URL`: the PostgreSQL JDBC URL of the restored copy.
- `PLOWSHARE_EVENT_PREFLIGHT_DB_USER`: a role allowed to read `firings`.
- `PLOWSHARE_EVENT_PREFLIGHT_DB_PASSWORD`: its password; keep it out of command
  arguments, source files and shared terminal transcripts.

Then run:

```sh
./gradlew :plowshare-server:eventDtoPreflight
```

This standalone command starts no server, runs no Flyway migration or recovery,
and opens connections with `default_transaction_read_only=on`. It scans **all**
retained firings, including terminal ones, with keyset paging. Output contains
counts, firing IDs and fixed error codes; at most 100 problem IDs are printed.
It never prints payloads or credentials. A nonzero exit blocks cutover.

Every historical family needs an explicit DTO and codec mapping. Unknown shapes
remain unchanged and block cutover; do not drop them, wrap serialized JSON as text,
coerce fields, quarantine work or retry a paid step. Add the actual missing contract
and round-trip fixtures, then repeat the inventory. The copy must reflect the
cutover dataset; repeat the check if old writers add further rows afterward.

Application composition repeats the read-only gate after database initialization,
before event transport, ticker or recovery can claim work. Repository claim/recovery
paths also refuse invalid persisted content before changing firing state. An
unsupported deployment stops startup with a compatibility error; there is no raw
fallback. This repository task has not inventoried or changed a deployment database.

## Verification

Default tests cover codecs, invalid inputs, the unchanged wire shape and read-only
connection configuration with mocks and pure values. Selected `full-db` tests
exercise PostgreSQL JSONB mapping, ID/tick collisions, concurrent claim constraints,
queue caps, wake coalescing and approval/delegation/recovery persistence. Unknown
queued/terminal shapes are tested to leave stored rows and paid-job identity intact.
