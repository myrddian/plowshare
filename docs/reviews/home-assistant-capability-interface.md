# Capability Java interface

This adapter-local interface refines the independently approved capability
contract. All native JSON conversion finishes before these immutable DTOs cross
the source boundary. Public SDK/core/shared-runtime contracts are unchanged.

`HomeAssistantResourceCapabilities extends AutoCloseable` exposes:

```java
Resolution resolve(Query query, OperationBudget budget) throws IOException;
Description describe(String resourceId, String revision, int offset, int limit,
    OperationBudget budget) throws IOException;
ReadResult read(FieldReadRequest request, OperationBudget budget) throws IOException;
void close() throws IOException;
```

DTOs are nested in `HomeAssistantCapabilities`:

```java
Query(String text, Optional<String> domain, Optional<String> area,
    Optional<String> capability, Optional<FieldRole> role, int offset, int limit,
    Optional<String> revision)
Resolution(ResolutionStatus status, String revision, Instant observedAt,
    List<ResourceSummary> candidates, Integer nextOffset)
ResourceSummary(String resourceId, String entityId, String name,
    List<String> aliases, Optional<String> device, Optional<String> area,
    Availability availability, IdentityKind identity,
    List<FieldDescription> matchedFields)
FieldDescription(String id, String name, ValueType type, Optional<String> unit,
    String capability, FieldRole role, FieldSupport support)
Description(String revision, Instant observedAt, ResourceSummary resource,
    List<FieldDescription> fields, Integer nextOffset)
FieldReadRequest(String revision, List<FieldSelection> selections)
FieldSelection(String resourceId, List<String> fields)
ReadResult(String revision, Instant observedAt, List<FieldReading> readings)
FieldReading(String resourceId, String entityId, FieldDescription field,
    FieldStatus status, Optional<Parameter> value,
    Optional<Instant> sourceUpdatedAt, Instant observedAt, boolean stale)
```

Enums:

- `ResolutionStatus`: `NO_MATCH`, `RESOLVED`, `AMBIGUOUS`.
- `ValueType`: `TEXT`, `NUMBER`, `BOOLEAN`, `UNKNOWN`, `UNSUPPORTED`.
- `FieldRole`: `CURRENT`, `TARGET`, `STATUS`, `UNKNOWN`.
- `FieldSupport`: `SUPPORTED`, `UNKNOWN`, `UNSUPPORTED`, `MISSING`.
- `FieldStatus`: `AVAILABLE`, `UNKNOWN`, `UNAVAILABLE`, `MISSING`, `INVALID`,
  `UNSUPPORTED`, `NOT_VISIBLE`.
- `IdentityKind`: `REGISTRY`, `LOCATOR_ONLY`, `MISSING`, `MISMATCH`.
- Resource `Availability` reuses the existing adapter enum.

Whole-result ambiguity counts matching resource/field pairs before pagination.
Results contain at most 20 candidates, descriptions at most 16 fields per page,
and reads at most 20 fields total across at most 20 distinct resources. Candidate
matching fields are bounded; pages may repeat a resource while retaining pair
ambiguity. Reads reject malformed/duplicate references and unknown fields before
returning values. Hidden resources share one sanitized unavailable error.

Opaque resource IDs persist from an identity-store namespace bound to configured
HA origin plus binding and a registry anchor. Connection epoch and credential
generation do not enter that stable identity. Schema revisions do include the
current epoch and projected policy/metadata/field shapes, never current values.
Established field types/units remain through temporary unavailable/missing data;
observable changes in an available entity invalidate shape as appropriate.
Registry-less `LOCATOR_ONLY` references are epoch-scoped and never claim
continuity across reconnect/restart. An anchored fixed selector that encounters
a replacement reports `MISMATCH` using only retained/configured metadata and
unavailable fields; replacement names, fields and values are not disclosed under
the old anchor.

The optional strict `discovery` provider config has `mode` (`fixed` or
`assist_exposed`), `profile` (`explicit` or `standard_measurements`),
`excludedEntities`, `excludedFields`, and explicit private `identityJournal`.
`Discovery.fingerprint(HomeAssistantSettings)` covers these settings plus frozen
selectors/attribute ceilings. Host composition joins that digest with the ordinary
policy revision before deriving its provider owner fingerprint. The identity store
has its own nonblocking lock and bounded atomic fsync snapshots. The host retains
its close owner immediately and releases it only after SDK providers settle.

Factory:

```java
HomeAssistantCapabilityCatalog.open(HomeAssistantAdapter adapter, String bindingName,
    HomeAssistantProviderConfiguration.Discovery configuration,
    HomeAssistantSettings settings, String ownerFingerprint)
```

It returns `HomeAssistantResourceCapabilities`. Absent discovery configuration
preserves the original six declarations and frozen semantics. Discovery mode
rejects a control block. Follow mode calls only the companion's versioned
`plowshare/discovery/selection` command for effective exposure and compares the
selected set before and after collection, sharing the finite operation budget.
Missing, refused or malformed support fails explicitly, with no fallback.
