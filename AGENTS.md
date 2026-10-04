# Working on Plowshare

These instructions apply to the whole repository. Read the
[coding standards](docs/coding-standards.md) before changing code. User instructions
take precedence; more specific repository instructions apply within their scope.

## Understand the system first

Plowshare is a persistent, server-first agent platform. Clients and adapters expose
server capabilities; they do not create a second durable runtime.

- `plowshare-server`: Java 21 / Spring Boot runtime, JDBC persistence, scheduling,
  agents, information, memory, messaging and orchestration.
- `plowshare-protocol`: shared Java contracts.
- `plowshare-sdk`: Java WebSocket SDK; `plowshare-a2a` adapts external A2A work.
- `plowshare-client`: Java client tools and file/transport support.
- `plowshare-client-ts`: shared TypeScript WebSocket operations and contracts.
- `plowshare-client-node`: Node credentials, filesystem and platform support.
- `plowshare-cli`, `plowshare-mcp`, `plowshare-tui`, `plowshare-console`,
  `plowshare-desktop`: user-facing clients.
- `plowshare-integrations`, `plowshare-integration-home-assistant`: external
  integration runtime and Home Assistant adapter.
- `extensions/`: search providers running as separate services.

Start with the relevant implementation, tests and current manuals in `docs/`.
Design plans and historical evidence describe intent at a point in time; verify
their claims against current code. Do not copy old module names, host details,
test counts or obsolete transport assumptions into new documentation.

## Code quality is part of the change

- **Interfaces first.** Define a narrow contract for a system capability, then
  implement it. Services consume interfaces through constructor injection.
  Concrete implementations belong in composition/configuration code. Records,
  immutable values and private helpers do not need artificial interfaces.
- **Repositories own persistence.** Only specialist repositories/stores and
  database configuration may use JDBC infrastructure. Controllers, tools,
  adapters, orchestration and domain services must not execute SQL, receive a
  `JdbcTemplate`, or expose tables/`ResultSet` to callers. Use Spring JDBC templates;
  do not introduce JPA, Hibernate, ad-hoc connections or generic SQL helpers.
- **Validate inputs strictly.** Validate external values at the boundary, bind SQL
  parameters, allowlist dynamic SQL identifiers and enforce authenticated
  ownership. Repository contracts also defend their domain invariants. Never
  confuse SQL parameterisation with permission checks.
- **Typed DTOs internally.** Raw JSON types (`JsonObject`, `JSONObject`, `JsonNode`),
  generic object payloads and `Map<String, Object>` must not cross into internal
  application/domain contracts. Convert them to explicit DTOs at the transport,
  SDK, configuration or persistence boundary. Conversion must validate and
  sanitize every field according to its contract, reject invalid inputs and
  complete before business logic runs. Deserialization alone is not validation;
  a DTO does not replace authorization, SQL binding or output escaping.
- **Explain the design.** Document interface contracts and non-obvious invariants,
  transaction boundaries, ordering, retries and failure behavior. Preserve useful
  existing comments and update them with the implementation. Dense, uncommented
  code is unfinished; line-by-line narration is not a substitute for reasoning.
- **Google Java Style is mandatory.** Use the Gradle formatter, including for
  tests. Do not hand-align code or use AOSP formatting. See the guide for commands
  and examples of service/repository boundaries.
- **Deployment values are configuration.** Do not hard-code `localhost`, loopback
  addresses, machine names, deployment ports, service URLs, filesystem paths,
  account identities or credentials into runtime code or SDKs. Supply and validate
  them through explicit configuration; missing required values must fail clearly,
  never silently connect to a local server. Local examples and isolated test
  fixtures must not become production defaults or fallback behavior.
- When changing a legacy implementation that violates these boundaries, move the
  affected responsibility behind a typed interface/repository. Existing direct
  database access is migration work, not precedent for new code. Keep unrelated
  architectural refactors separate from the requested change.

## Preserve the platform's contracts

- **SDK adapters stay outside core.** Build A2A and other integrations against the
  public SDK and existing server contracts. Do not change the core runtime, API,
  tools or database to make an adapter work, import server internals, or bypass
  the SDK with direct database/internal-service access. Report a missing public
  capability as a gap; a platform extension requires separately authorized scope.
  SDK consumers must configure server origins and adapter bind/advertised addresses
  explicitly, with no assumption that the server shares their host or container.
- Use WebSocket operations for client/server work where supported. Document
  required HTTP boundaries such as login, uploads, Git and external protocols;
  do not add silent HTTP fallback or replay a mutation after uncertain delivery.
- Keep authentication, project membership, tool grants, approvals and workspace
  fences explicit. Client-side checks do not replace server authorization.
- Preserve durable lifecycle, idempotency, accounting, cancellation and recovery
  behavior. Messaging, delegation, swarm deliberation and external work have
  distinct authority and lifecycle contracts; reuse their owning APIs.
- Keep schema changes in new Flyway migrations. Never edit a shipped migration.
- Update shared operation contracts and affected clients together. Regenerate
  derived catalogs through their owning scripts rather than editing outputs.
- Keep credentials, private addresses, local configuration, runtime data and
  diagnostic dumps out of source, examples and logs.

## Work and verify

Inspect the working tree before editing and preserve unrelated work. Prefer small,
cohesive changes and existing lifecycle/access primitives over new frameworks.
Do not add speculative abstractions, swallowing catches, unchecked casts, or
nullable success values to conceal an error.

### Database test policy

- **Mock database dependencies by default.** Test service and tool behavior
  through mocked repository interfaces; small in-memory fakes are appropriate
  when they make the contract clearer. Do not start PostgreSQL for behavior that
  can be verified without it.
- Real database tests are exceptions: place them behind `@Tag("full-db")` and
  the explicit `-PfullDb` flag. Use them only when the assertion requires actual
  PostgreSQL behavior, such as Flyway migrations, SQL constraints or row mapping,
  transaction rollback, locking or concurrent persistence.
- **Never run real database tests unless the current change requires them or
  the user explicitly requests them.** Routine builds, unrelated changes and
  general confidence checks are not reasons to enable `-PfullDb`. State the
  database-specific reason before running them and select the smallest relevant
  test set with `--tests`. Run the entire database suite only when its breadth is
  necessary for the change or explicitly requested. This rule also applies to
  the database-backed `dockerSmoke` task and manual CI verification.

```sh
./gradlew format                 # Apply Google Java formatting to all modules.
./gradlew formatCheck            # Read-only formatting check.
./gradlew :plowshare-server:test --tests 'fully.qualified.RelevantTest'
./gradlew check                  # Java, Javadoc, formatting and client checks; no Docker tests.
./gradlew check -PfullDb         # Full DB coverage only when required or explicitly requested.
```

The build requires Java 21, Node.js 22.12+, pnpm and Python 3. Database tests require
the explicit `-PfullDb` flag and Docker/Testcontainers. Default runs use the
non-database tests, including mocks and in-memory fakes. Native SDK and
distribution checks have additional toolchains and are explicit entry points; see [SDKs](docs/sdks.md) and
[distributions](docs/distributions.md).

Run meaningful tests for changed behavior, including invalid inputs and relevant
authorization, persistence or concurrency cases. After Java edits, run `format`
and `formatCheck`; before handing off a repository-wide change, run `check`.
Report what changed, what actually passed and any remaining failures. Do not claim
that formatting proves architectural compliance or that mocked tests prove a live
integration works.
