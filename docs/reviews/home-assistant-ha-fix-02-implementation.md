# HA-FIX-02 — Explicit read-tool revision object schemas

Implementer: `/root`. Independent reviewer: `/root/ha_deployment_reviewer`.

The first live provider discovery completed, but the served model repeatedly
encoded the returned revision object as a string. The server correctly refused
those arguments before read execution. The retained original job finished with
an honest unavailable answer; it was neither cancelled nor replayed. Inspection
shows core passes through the declared rich schema. Backend/template reference
handling remains a hypothesis, not an established core defect.

The adapter now inlines the same closed six-field revision object in all four
read-tool positions, including the search cursor, and explicitly describes object
usage. This exposes the shape without requiring schema-reference rendering. No
JSON string coercion or validation relaxation was added. Read handlers,
authorization, revision ownership/freshness and the public SDK remain unchanged.
No core, protocol, SDK, shared-runtime or schema change was made.

Validation passed: six focused read-tool tests, all 185 HA Java tests, formatter
and formatCheck, the default repository check and refreshed installDist. Eight
fresh offline installed-consumer checks passed against all 56 distribution files.
All 4,042 protected core/protocol/SDK/shared-runtime source hashes remain unchanged.
The focused regression checks every offered revision position, accepts typed
objects, rejects their JSON-encoded strings and performs no resource reads.

The new schema changes the catalogue fingerprint. Deployment must first confirm
the original job terminal and the old provider's normal catalogue withdrawal with
no unresolved receipts. Keep its code, config, container and journal. A separate
versioned owner/journal for the changed catalogue is required; it is not a reset
to hide uncertain work. The conditional private rollout sends one normal SIGTERM,
observes the original owner within 45 seconds and never force kills it. It refuses
to stage the replacement without the old withdrawal/settlement proof. Staging,
startup and live read acceptance require their own actual receipts and review;
this source report does not claim those later milestones.
