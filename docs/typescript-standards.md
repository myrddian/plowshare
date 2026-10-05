# TypeScript standard

This standard applies to every active TypeScript module, including tests:
`sdk/typescript`, `sdk/node`, `plowshare-cli`, `plowshare-mcp`,
`plowshare-tui`, `plowshare-console` and `plowshare-desktop`. It supplements
[coding standards](coding-standards.md); the architecture, authorization,
configuration and lifecycle requirements there apply equally to TypeScript.

## Compiler and automated enforcement

The SDK directory names are source locations; published package names remain
`plowshare-client-ts` and `plowshare-client-node`.

All projects inherit `tsconfig.strict.json`. Do not override its checks locally.
It requires `strict`, `noEmitOnError`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`,
`noImplicitOverride`, unused-local/parameter checks, switch fallthrough checks,
isolated modules, explicit type imports and consistent filename casing. Runtime
libraries, module resolution and emission remain specific to each module. Keep
neutral libraries free of Node/DOM dependencies; a test project's Node globals
must not leak into its production compiler project.

The root package pins ESLint, typescript-eslint, TypeScript and Prettier. Building
requires Node 22.13+ (or a supported newer major), pnpm and the Java build's tools.
Runtime client requirements remain in each published package. Dependency updates
must review changed diagnostics and run the complete verification gate.

Use ESLint's recommended type-checked correctness rules plus Plowshare's stricter
checks for unsafe string conversion, spread, catch variables, exhaustive switches
and type imports. A switch must cover its variants or have an explicit default that defines rejection or intentional handling. This is an explicit correctness policy, rather than every
opinionated rule in a changing preset. Defensive runtime checks must remain even
when a static type says a value is valid. Promise-returning test stubs may omit
`await`; production functions may not pretend synchronous work is asynchronous.
Tests otherwise receive the same unsafe-value and promise checks.

No error baseline, ignored source directory or blanket lint suppression is
permitted. Generated operation and IPC schemas are checked by their generator, not edited
or independently reformatted. Dependencies and build output are excluded.
A narrow exception requires an adjacent explanation of the invariant and review;
it must not hide invalid input, unsafe casts or unowned asynchronous work.

```sh
pnpm install --frozen-lockfile
pnpm lint
pnpm format
pnpm format:check
./gradlew format
./gradlew formatCheck
./gradlew check
```

`format` applies Java and TypeScript formatting. `formatCheck` never edits files.
`check` runs formatting, typed linting, compiler checks and existing client/server
verification without database opt-in. Tests and public package declarations must
be checked as well as production implementation files. A successful formatter or
compiler run does not certify architectural compliance.

## Readability

Follow [Google TypeScript conventions](https://google.github.io/styleguide/tsguide.html)
for naming, modules and maintainable code. The checked-in Prettier configuration
owns whitespace and punctuation: two spaces, single quotes, semicolons and trailing
commas. Do not hand-align code or compress multiple actions into a one-line method.
Keep functions cohesive; separate decoding, execution and rendering when a switch
or controller mixes those responsibilities. Prefer named DTOs to long anonymous
shapes. Explain ownership, validation, ordering, cancellation and uncertain
completion where those contracts affect callers.

## Typed boundaries and SDK contracts

`unknown` is appropriate for untrusted JSON, network messages and configuration
at their owning boundary. `any` is prohibited. Raw objects and arbitrary
`Record<string, unknown>` values must not become application/service contracts.
Decode every required, optional and nested field before application logic. Reject
wrong types, unknown input fields, invalid variants, nonfinite/out-of-range
numbers and excessive collections. Never repair malformed input with `String`,
`Number`, defaults or unchecked casts. Preserve free-form text; normalize only
according to the field's contract. Validation never replaces authorization,
workspace fences or escaping at an output destination.

An SDK operation pairs its request DTO with its response DTO. Its public methods
return validated, operation-specific results and explicit refusals, not raw wire
objects or `Promise<unknown>`. Transport correlation/envelope parsing stays inside
the transport. Responses may tolerate additional protocol fields for forward
compatibility, but must validate and project the fields exposed by their DTO.
Input field allowlists are strict. Existing socket owners use `checkedTransport`
when passing operations to application capabilities; the low-level wire outcome
remains inside that adapter. Genuine extensible protocol data needs a named,
documented contract with validated JSON values; it is not an excuse to erase a
known request or response shape.

Do not use `as never` or `as unknown as T` to bypass validation. A narrowly scoped
assertion after complete boundary validation or for a documented mapped-union
compiler limitation is acceptable. Non-null assertions require an established
invariant; use explicit narrowing for external or indexed values. Tests should
fail clearly when a fixture element is absent rather than disguise missing data.

## Capabilities, asynchronous work and configuration

Interfaces describe system capabilities: transport, credentials, filesystem,
process execution and application operations. Constructors depend on those
contracts; composition roots choose implementations. Pure helpers and immutable
values do not need artificial interfaces. UI components consume typed operations;
they do not interpret wire property bags or own a second durable runtime.

Await asynchronous work or assign it to an owner that handles rejection and
cleanup. A detached promise requires an explicit error handler; `void` alone is
not rejection handling. The `no-floating-promises` rule uses `ignoreVoid: false`. Do not pass an async function to a synchronous event API
without an owning adapter. Avoid swallowing catches and preserve error causes
without exposing credentials or complete untrusted payloads to users.

Configure server origins, bind addresses, advertised addresses and local roots
explicitly. Missing configuration must fail before connecting. Never silently
select a loopback server. Use WebSocket operations where supported and document
HTTP exceptions such as authentication and external protocols. Never fall back
or replay a mutation after uncertain delivery. Preserve durable jobs, accounting,
approvals, cancellation and recovery through their existing owning APIs.

## Verification

Test real boundary behavior: malformed nested inputs, unsupported fields, missing
configuration, malformed responses, valid variants and refusal/uncertain delivery.
Assert that invalid inputs cause no transport side effect and that uncertain
mutations are not replayed. Use mocks/fakes for repository and transport behavior;
the database policy in coding standards still applies. Review DTO boundaries and
capability ownership independently of lint results.

## Console transport boundaries

Console screens use typed capabilities on the tab's existing WebSocket. Historical
route strings are local compatibility keys and are mapped to shared operations;
they never trigger an HTTP fallback. SDK decoding runs before a display-specific
DTO projection. Unknown response fields are omitted, malformed snapshots fail
without replacing the previous selection, and mutations are never replayed.

HTTP remains explicit for authentication/password/bootstrap flows, multipart
document uploads and live runtime configuration, which has no shared WebSocket
operation. These boundaries must validate their own DTOs. The browser derives its
server origin from the page; development proxying requires `PLOWSHARE_URL`.
