# DEPLOY-01 — Production read-provider completion

Implementer: `/root`. Independent reviewer: `/root/ha_deployment_reviewer`.

The user authorized the separate read provider, supplied a dedicated HA credential
through a private local file, and selected the current 68 Assist-exposed entities.
The provider now runs as a separate nonroot container using the verified existing
Java 21 runtime image. No Plowshare server or HA image/configuration update or
restart was performed in this continuation. The dedicated Application, service
principal, project-scoped Contributor token and house_reader grants are unchanged.

The fixed policy admits exactly those 68 entity IDs with state and the implicit
unit attribute. Explicit attribute lists are empty; actions and subscriptions are
empty, and the control-provider block is absent. New Assist exposure does not
expand this allowlist automatically. The four exact read-tool grants remain
assigned only to house_reader; house_task has no dynamic tools. HA entity exposure
and token authority do not independently grant Plowshare tool access.

The HA token authenticated successfully against the actual Core 2026.9.4 instance.
It carries its HA user's authority; read-only enforcement belongs to the provider
policy and Application grants, not an invented read-only token scope. Credentials
are private mounted files, absent from source, reports and Docker environment
configuration. The launcher exports them only inside the provider process. The
existing Plowshare credential retains its previously recorded expiry/rotation
requirement. No additional credential is currently needed.

The initial provider discovery worked, but the model encoded reference-only
revision fields as strings. The original job settled with an honest unavailable
answer; it was not cancelled or replayed. The independently approved
[HA-FIX-02](home-assistant-ha-fix-02-v2-review.md) inlines the same strict object
schema, including cursors, without coercion or core/SDK changes. Its six focused
tests, all 185 HA Java tests, formatting/default repository check and eight fresh
installed-consumer checks passed. All 4,042 protected source hashes are unchanged.

The [conditional replacement review](home-assistant-read-provider-v2-rollout-review.md)
binds the exact procedure. After the original job was terminal, one normal SIGTERM
stopped the old owner. Its catalogue withdrawal was confirmed, zero receipts were
unresolved, and no force kill occurred. Its code, configuration, container and
journal remain retained; the old journal's final hash is unchanged. Because the
schema changes the catalogue fingerprint, the replacement uses a separately owned
journal rather than rewriting the old identity. Both 56-file distributions and
all prior candidates/receipts remain distinct.

The new provider's installed hashes, offline configuration, actual stopped-container
boundaries and guarded single start were verified. It runs with a read-only root
filesystem, dropped capabilities, no new privileges, no published ports and no
server data/configuration or Docker-socket mount. Only its separate state mount is
writable. Private receipts record the exact runtime image/container/source/config
identities. Postflight confirms the new catalogue, zero unresolved intents or
receipts, durable replay fences, a running owner with zero restarts and unchanged
old journal. This is actual public-protocol evidence, not container health alone.

A fresh HA Conversation API request through conversation.house_reader completed
with a current selected CO2 reading and unit. Its separate retained Production job
is DONE/ANSWERED with four model calls and four tools: three discovery calls and
one successful read. The revision was an object; HA returned AVAILABLE, nonstale
state and only unit_of_measurement despite additional attributes being requested.
No control tool ran. The numeric reading, private entity identifiers and raw
responses remain outside source. This was new acceptance against the corrected
catalogue, not a repeat of the initial failed job.

DEPLOY-01 installation/configuration is now ready for final independent acceptance.
HA-FIX-02 has scoped deployment/read validation. The broader LIVE-01 voice,
follow-up/isolation and AI Task matrix remains uncompleted; no physical controls
are enabled or tested. The provider has no automatic restart or SDK reconnect:
after failure or host restart, inspect the retained original owner and receipts
before an explicit resume. Do not delete journals, force kill an unsettled owner
or automatically replay publication/read work to recover availability.
