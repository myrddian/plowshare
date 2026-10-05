# Plowshare coding standards

These rules apply to production code, tests and contributions from coding agents.
They complement [AGENTS.md](../AGENTS.md). Existing violations must be corrected in
the responsibility being changed; they are not examples to copy. Broad migrations
should be separate, reviewable changes rather than hidden inside a feature.

## Formatting and readability

Java follows [Google Java Style](https://google.github.io/styleguide/javaguide.html).
Gradle uses pinned [Spotless](https://github.com/diffplug/spotless/tree/main/plugin-gradle)
and [google-java-format](https://github.com/google/google-java-format) versions in
the root build. The default Google formatter uses two-space indentation; do not
select AOSP style or disable formatting to preserve a preferred layout.

```sh
./gradlew format                         # All Java and TypeScript source and tests.
./gradlew formatCheck                    # All modules, without rewriting files.
./gradlew :plowshare-server:spotlessApply # One module while iterating.
./gradlew :plowshare-server:check         # Includes that module's formatting check.
./gradlew check                          # Repository verification, including formatting.
```

Formatting targets `src/*/java/**/*.java` in every Gradle subproject. Build outputs,
resource templates and historical evidence are excluded. `check` fails on a
formatting difference and never applies edits. CI already runs `check`, so the
same requirement applies locally and in pull requests. Keep mass formatting
separate from behavior changes. Formatter version upgrades require their own
reviewable pass.

TypeScript follows the mandatory [TypeScript standard](typescript-standards.md),
with shared strict compiler settings, typed ESLint checks and pinned Prettier.
The Java formatter does not format Python, SQL or Gradle Kotlin files.
Native SDKs follow the mandatory [native SDK standard](native-sdk-standards.md).
Follow the surrounding language conventions there. In every language, use clear
names, cohesive methods and explicit error handling. Avoid compressed one-line
classes, deeply nested control flow, boolean-heavy APIs and clever expressions
that conceal lifecycle or authorization decisions.

## Interfaces first at system boundaries

Define the capability a caller needs before writing its implementation. Domain
services, repositories, transports, providers and external adapters expose narrow
interfaces. Constructors and method parameters at these boundaries take the
interface, and Spring configuration/composition roots select the implementation.
Use constructor injection and make dependencies explicit.

Keep the interface in the owning domain/module. Its API describes domain actions
and typed results, not a vendor SDK, JDBC operations or implementation fields.
Document ownership, side effects, idempotency, blocking behavior and failures
where they affect callers. Return immutable values or defensive copies.

Do not mechanically invent an interface for each record, enum, immutable value,
private helper or pure utility. An interface earns its place by representing a
system contract. Do not expose a concrete service and an identical interface
while continuing to inject the service everywhere. Test against the contract;
use a small fake when that gives a clearer test than a mock framework.

## Deployment values must be configurable

Do not embed environment-specific values in runtime code, SDK constructors,
adapters, generated clients or production configuration defaults. This includes
`localhost` and other loopback addresses, machine names, deployment ports, server
and provider URLs, advertised callback/Agent Card URLs, local filesystem paths,
account identities and credentials. Wrapping a literal in a constant does not
make it configuration. A Docker container's loopback address refers to that
container; an SDK consumer must not assume Plowshare runs beside it.

Accept deployment values through the owning application's configuration file,
environment or explicit arguments. Pass validated, typed configuration into the
SDK or adapter at its composition boundary. Keep connection origins, listener
bind addresses and publicly advertised addresses distinct. Validate required
values before connecting, listening or advertising; missing or invalid values
must produce an actionable error. Never fall back silently to a local endpoint,
developer directory, account or credential.

Local addresses are acceptable in clearly labelled development examples and
isolated test fixtures. Tests should obtain listener ports and temporary paths
from their fixtures. Those values must not leak into shipped SDK behavior,
production defaults or generated contracts. Genuine protocol constants and
documented deployment-independent limits are distinct from local deployment
values; they do not justify hard-coded endpoints or paths.

## SDK integrations must preserve the core boundary

All SDK variants belong under `sdk/`. External-system adapters and their shared
integration runtime belong under `integrations/`. Reserve `extensions/` for
services that extend Plowshare capabilities, such as search providers. Keep
published package coordinates and Gradle task identities independent of source
locations, and update build, generator, deployment and manual paths together.

A2A adapters and other SDK-based integrations are external consumers of the
platform. Implement their protocol translation, configuration, routing and
adapter-owned state in their own module/process using the public SDK and existing
server contracts. Configure the Plowshare origin, authentication and project scope
explicitly. Never assume a local server or a shared filesystem, host or container.

Adapter work must not modify Plowshare's core runtime, server APIs, tools or
database schema to accommodate that adapter. It must not import server classes,
call internal services, access core tables directly, or bypass the SDK's transport,
authorization and lifecycle contracts. An SDK abstraction that wraps a hard-coded
local connection or a core dependency still violates this boundary.

If the public SDK cannot express a required operation, identify the exact missing
capability and document the limitation. Do not conceal incomplete adapter support
with a core patch or an internal-access workaround. Any core/API/SDK capability
extension must be separately authorized and scoped as platform work, with shared
contracts and affected clients updated together. Adapter acceptance must be
verified through its public SDK boundary against a configured server, including
deployment where the adapter and server run on different hosts or containers.

An integration that cannot work without changing core is an unsuccessful
integration task. Report the missing public capability; do not silently expand
scope. Any exception requires explicit authorization for a separate platform
change and a recorded design decision explaining why the capability belongs in
Plowshare independently of the adapter, the SDK-only alternatives considered,
and the security, lifecycle, compatibility and test consequences. Adapter
convenience, deadline pressure and making an integration test pass are not
sufficient reasons. An approval to build an integration is not approval to
extend core. See [ADR 0001](decisions/0001-integration-boundary.md).

`integrationBoundaryCheck`, included in `check`, verifies production classpaths
in both directions. Test-only dependencies may exercise actual server loaders;
they must not enter shipped adapters. Dependency checks complement review of
core changes and public capability gaps; they cannot establish the business
justification for a platform change.

## Persistence belongs to specialist repositories

Use Spring `JdbcTemplate` or `NamedParameterJdbcTemplate` in specialist repository
implementations. Each repository owns a cohesive persistence responsibility and
the queries, row mappings and atomic operations supporting it. Prefer names such
as `TaskRepository` / `JdbcTaskRepository`; existing `*Store` names are acceptable
when the class actually fulfils this role.

Controllers, WebSocket handlers, tools, adapters, hooks and domain/application
services call repository interfaces. They must not inject JDBC templates,
`DataSource` or database connections, write SQL, or query a table directly.
Composition roots may construct and wire repositories; schema migrations and
database infrastructure may manage the database itself. Those exceptions do not
permit business queries in configuration or infrastructure classes.

Do not introduce JPA/Hibernate, manual `DriverManager` connections, or a generic
`executeSql(String)` escape hatch. Do not pass `ResultSet`, `RowMapper`, SQL strings
or table names across the repository boundary. Expose typed domain operations,
including bounded reads and atomic state transitions. Tests may use JDBC to set
up fixtures and inspect persisted results; production callers may not.

The following illustrative boundary keeps authorization in the service and SQL
in the repository. `Caller`, `ProjectId` and `Task` stand for validated domain
types; they are not new framework classes to add indiscriminately.

```java
/** Reads tasks in one project. Results never include tasks owned by another project. */
public interface TaskRepository {
  /** Returns at most limit tasks; limit must be between 1 and 100 inclusive. */
  List<Task> findByProject(ProjectId projectId, int limit);
}

final class TaskService {
  private final ProjectAccess access;
  private final TaskRepository tasks;

  TaskService(ProjectAccess access, TaskRepository tasks) {
    this.access = access;
    this.tasks = tasks;
  }

  List<Task> list(Caller caller, ProjectId projectId, int limit) {
    access.requireRead(caller, projectId);
    return tasks.findByProject(projectId, limit);
  }
}

final class JdbcTaskRepository implements TaskRepository {
  private final JdbcTemplate jdbc;

  JdbcTaskRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Task> findByProject(ProjectId projectId, int limit) {
    Objects.requireNonNull(projectId, "projectId");
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100");
    }
    // Stable ordering keeps pagination deterministic when creation times tie.
    return List.copyOf(
        jdbc.query(
            "SELECT id, title FROM tasks WHERE project_id = ? ORDER BY id LIMIT ?",
            (row, rowNumber) -> new Task(row.getLong("id"), row.getString("title")),
            projectId.value(),
            limit));
  }
}
```

Put a transaction around the unit of work that must commit or roll back together.
The application service may coordinate typed repository calls within that
transaction; an atomic repository operation owns its necessary database locks,
conditional updates and result interpretation. Explain the invariant protected,
the expected concurrency behavior and retry rules. Check affected-row counts for
state transitions. Do not call remote services while holding a database
transaction or publish a successful external effect before its durable state is
committed. Use the system's existing durable delivery mechanisms.

## Typed DTOs and secure conversion

Raw JSON representations are confined to boundary parsing and serialization.
Do not pass `JsonObject`, `JSONObject`, `JsonNode`, generic object payloads or
`Map<String, Object>` through internal application/domain interfaces. This rule
also applies to SDK consumers, adapters, configuration loading and stored JSON:
decode at the owning boundary and pass explicit typed DTOs or domain values onward.
Serialize typed results at the output boundary. Typed collections with defined
element/value contracts are appropriate; arbitrary property bags are not DTOs.

Define a DTO for each supported request, response or persisted shape, with explicit
field types, required/optional/null semantics and bounded collections. Conversion
must validate and sanitize all fields, including nested objects and collection
elements, before constructing an accepted DTO or invoking business logic. Enforce
the declared field allowlist, reject unsupported fields according to the versioned
contract, and reject wrong types, invalid enum values, malformed identifiers,
forbidden characters, excessive lengths and out-of-range numbers. Do not coerce
malformed values into defaults, return partially validated DTOs or defer raw JSON
lookups to downstream services.

Sanitization is field-specific: apply the permitted normalization and character
rules for identifiers, and the owning URL/path policy for addresses and filesystem
values. Preserve free-form text according to its contract; indiscriminate stripping
or a universal escaping function is not secure conversion. A JSON library's
successful deserialization, a cast or a DTO constructor without validation does
not establish that input is safe. Report conversion failures through the boundary's
normal error contract before any side effect.

Derive caller identity and authority from authenticated context rather than
trusting payload fields. Converted DTOs still require authorization checks,
parameterized SQL and destination-specific escaping when used. Validation and
sanitization at conversion do not make arbitrary HTML, SQL or shell interpolation
safe.

## Strict validation and safe inputs

Validate untrusted input when it enters the server or adapter, before it becomes
a command or reaches persistence. Use existing typed identifiers and validation
helpers. Repositories enforce their own contract preconditions so alternate
callers cannot bypass bounds or persist an invalid state.

- Define accepted fields, types, enum values and required/optional/null semantics
  for the operation. Reject malformed values and unexpected fields according to
  the versioned contract; never silently reinterpret a malformed request.
- Enforce embedding input limits with the served model's matching, pinned tokenizer,
  counting the complete preprocessed input and special tokens without truncation.
  Chat token estimates, character/word ratios and safety margins are not hard bounds.
  Shared chunks must fit every encoder that consumes them; recheck before submission.
- Enforce length, byte-size, list-count, numeric-range and pagination limits.
  Distinguish user text from structured identifiers. Normalize only where the
  contract defines it; do not strip characters from arbitrary text and call it
  safe. Validation rejects invalid data, while contextual escaping protects a
  specific output sink.
- Derive caller identity and authority from authenticated context. Resolve project
  membership, resource ownership and tool grants before performing the action.
  An ID supplied in a request is not proof of access. Scope repository queries
  and updates by the owning project/resource where applicable.
- Bind all SQL values with JDBC parameters. Never concatenate request values
  into SQL. Column names, sort directions and other identifiers cannot be bind
  parameters: select them from an explicit code-owned allowlist. Do not expose
  arbitrary SQL fragments through an API.
- Define whether search text uses literal or pattern semantics. Escape `%` and
  `_` and specify an SQL `ESCAPE` character when a `LIKE` operand is literal.
- Apply the owning subsystem's filesystem containment, URL/network policy and
  command argument rules. Do not bypass them with string prefix checks, unchecked
  paths, shell interpolation or an unrestricted HTTP client.
- Escape/render data for its actual destination. Avoid logging credentials,
  complete untrusted payloads or private filesystem details in validation errors.

Return a typed, actionable failure through the existing error contract. Do not
convert database failures into empty results, catch exceptions and continue with
partially committed work, or report success for an operation that was refused.

## Comments explain contracts and reasoning

Write code that can be maintained without the conversation that produced it.
Document externally consumed interfaces and public operations with Javadoc:
purpose, meaningful preconditions, ownership, side effects, results and failure
semantics. Document non-obvious internal contracts as well. Focus on information
the signature does not already communicate.

Use implementation comments where a reader needs to understand why: an invariant,
trust boundary, race, transaction ordering, compatibility constraint or recovery
decision. Put the comment next to the relevant code. For a workaround, describe
the underlying restriction and reference an issue or test when useful.

Preserve accurate existing comments. When behavior changes, update comments,
Javadoc and current manuals in the same change. Replace stale host-specific
measurements, plan/task references and historical narratives in active guidance
with the current contract and durable rationale. Keep historical evidence in its
own documents. Never invent a measured fact or leave a broken `{@link}`.

Do not narrate assignments, add ornamental section banners, pad every getter with
boilerplate, or hide unexplained logic behind a large prose block. The formatter
standardizes layout; it cannot supply design reasoning. Existing Javadoc checks
validate main-source references, not the accuracy or completeness of comments.

## Verification and review

Run focused tests while iterating and `./gradlew check` before handing off broad
changes. Mock database dependencies by default, using repository interfaces or
small in-memory fakes for service and tool decisions. Real PostgreSQL tests must
use the existing Testcontainers fixtures, declare `@Tag("full-db")` and require
the explicit `-PfullDb` flag. Ordinary `test` and `check` runs exclude them.

Never enable real database tests unless the current change requires PostgreSQL
verification or the user explicitly requests it. Flyway migration changes are a
typical reason; SQL constraints, row mapping, transaction rollback, locking and
concurrent persistence may also require real database evidence. State the reason
before running and select the smallest relevant test set with `--tests`.
Routine builds and unrelated changes must remain in the default mode. An entire
database suite or database-backed smoke run needs a reason that requires that
breadth, or an explicit user request.

Cover validation, project isolation and failure behavior relevant to the change
in the default tests. When database verification is required, cover the relevant
row mapping, rollback, affected-row, race, replay and recovery semantics against
PostgreSQL; mocks alone cannot prove those semantics.

Keep Flyway migrations append-only, update shared contracts and regenerate derived
catalogs with their owning tools. Preserve explicit permissions and lifecycle
semantics rather than adding client-only safeguards. A review must verify the
interface/repository boundary, input handling and useful comments as well as
behavior. Gradle currently enforces formatting, compilation warnings and Javadoc
references; architecture and comment quality still require code review.

Internal model processing uses the server's SYSTEM principal for accounting.
SYSTEM is never a login, administrator role, permission bypass or fallback for
missing user attribution. Keep the admitted owner and scope responsible for data
access, lifecycle fences, allowances, hooks, cancellation and log visibility.
Document processing models have no tools, model-directed delegation, filesystem
grants, skills or other capability acquisition. Reject a definition that would
widen that ceiling before inference. Preserve existing usage attribution rather
than relabeling historical calls as SYSTEM.
