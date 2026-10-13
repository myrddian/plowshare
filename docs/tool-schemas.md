# Shared tool schemas

Model dispatch and external provider declarations use the same immutable
`SchemaDefinition` vocabulary owned by `plowshare-protocol`. Every SDK exposes its
native typed equivalent. SDK declarations reach the existing live tool registry
through provider catalogues and Relay; there is no second tool runtime.

The scalar `parameters` convenience API remains supported. To declare recursive
inputs, supply `inputSchema` and an empty parameter list. Supplying both is an
error. The schema's root type must be `object`; missing object members and explicit
JSON `null` have different meanings.

## Executable profile

The profile follows the supported keyword semantics in JSON Schema
[2020-12 core](https://json-schema.org/draft/2020-12/json-schema-core) and
[validation](https://json-schema.org/draft/2020-12/json-schema-validation).
`$schema`, when present, must be
`https://json-schema.org/draft/2020-12/schema`. This is an explicitly bounded
profile, not a claim of full JSON Schema support.

| Feature | Behavior |
| --- | --- |
| `type` | Object, array, string, number, integer, boolean, null; scalar or nonempty distinct type union |
| Objects | `properties`, `required`, boolean or schema `additionalProperties`, `minProperties`, `maxProperties` |
| Arrays | `items`, `prefixItems`, `contains`, `uniqueItems`, `minItems`, `maxItems` |
| Literals | Scalar `enum` and `const`, including explicit null; numeric equality ignores decimal spelling |
| Numbers | `minimum`, `maximum`, exclusive bounds, positive `multipleOf`; decimal divisibility rather than floating point remainder |
| Strings | `minLength`, `maxLength` count Unicode code points |
| Composition | Nonempty `allOf`, `anyOf`, `oneOf`; `not`; `if` with `then`/`else` |
| References | Root `#/$defs/<key>` only, including JSON Pointer `~0`/`~1` escapes; dangling, external and cyclic references refused |
| Annotations | `title`, `description`, `default`, scalar `examples`, `format`, `readOnly`, `writeOnly`, `deprecated`; preserved without coercion or inserted defaults |

The shared vocabulary also represents model-facing `pattern`, `patternProperties`,
`$id` and `$anchor`. The executable provider profile refuses these features.
Unknown keywords, other dialects, boolean schemas and object/array literals are
refused. `format` is an annotation and does not validate addresses or dates.
Unsupported declarations fail visibly before publication; constraints are never
silently removed to accommodate an SDK or model backend.

Type-specific constraints apply only to that type. For example, `required` does
not imply an object type; declare the type when it matters. Backend-specific
model compatibility remains the model dispatch boundary's responsibility.

## Bounds and ownership

Arguments are immutable typed trees of string, portable number, boolean, explicit
null, array and object. Their structural depth is at most 32, with at most 10,000
visited values and 1,024 entries per collection. Strings are at most 4,096 UTF-16
units. Object keys contain 1–1,024 units and no control characters. Null is a
variant, never a nullable success value in Java, Go or .NET.

Numbers must be finite, have absolute value at most 9,007,199,254,740,991,
normalized decimal scale at most 18 and an exact decimal round trip through the
SDK double representation. Integer constraints require an integral value. Wire
codecs reject numeric spellings that would require rounding; they do not repair
them. Boolean values are not numbers.

Schema structural/reference expansion and validation are limited to depth 64 and
10,000 work steps. Schema collections have at most 10,000 entries; executable
scalar constants/defaults/examples follow argument scalar bounds. Schema text
annotations retain their vocabulary bounds: description 32,768 units, other text
4,096. Catalogue snapshots permit at most 128 tools and use the Relay TEXT payload
contract (50 MiB portable UTF-8 ceiling; deployment admission defaults to 5 MiB).
The 64 KiB transport chunk size does not limit the complete catalogue;
requests retain their 32,768-unit envelope limit. These limits are cumulative:
a structurally valid schema or argument may still exceed its enclosing envelope.
No network reference loading or regular expression execution occurs.

Provider construction copies/freezes declarations. The server validates arguments
against the offered declaration before recording/submitting the invocation. SDKs
validate the version, fingerprint and argument tree again before saving execution
intent or calling the handler. Validation grants no membership, tool permission,
service account authority or accounting ownership.

## Wire versions and recovery

Scalar declarations publish `plowshare-tool-catalog/1`; catalogues containing any
`inputSchema` publish `plowshare-tool-catalog/2`. The server reads both. Application
`server/tools.json` retains its version 1 container and accepts optional typed
`inputSchema` objects on declarations. Old declarations omit the new field.

Scalar requests remain `plowshare-tool/1`, with their original exact field set.
Recursive-schema requests use `plowshare-tool/2` and a required lower-case SHA-256
`schemaFingerprint`. Results retain the independent text result profile
`plowshare-tool/1` for both request versions. Historical scalar receipts remain
readable, and no uncertain execution is replayed.

The fingerprint is over the complete schema, including annotations, so a changed
catalogue cannot reinterpret an offered call. Request version and routing authority
are pinned separately in the retained invocation and checked by the provider.
Canonical tokens are UTF-8: null `z;`, booleans `b0;`/`b1;`, strings
`s<lowercase UTF-8 hex>;`, numbers `n<normalized coefficient>e<exponent>;` (zero
`n0e0;`), arrays bracket their concatenated tokens, and objects brace sorted
UTF-8-key tokens followed by value tokens. Numeric `1` and `1.0` have the same
fingerprint; scalar `type` and a type union preserve their distinct wire spelling.

Retained invocation records keep the exact declaration and request. Existing
exclusive journals, acknowledgement fences, publication identities, recovery,
leases, grants and Relay ancestry remain authoritative. Upgrading a provider does
not authorize retrying an unresolved call. Before rollback, withdraw nested
catalogues and reconcile their work with a supporting SDK: older SDKs refuse v2
requests, and older servers cannot read retained recursive declarations.

## SDK use

```typescript
import type { ToolDeclaration } from 'plowshare-client-ts';
const tool: ToolDeclaration = {
  name: 'inspect_targets', description: 'Inspect selected targets',
  parameters: [], timeoutSeconds: 30,
  inputSchema: {
    type: 'object',
    properties: {
      targets: { type: 'array', items: { type: 'string' }, minItems: 1 },
      note: { type: ['string', 'null'] },
    },
    required: ['targets', 'note'], additionalProperties: false,
  },
};
// A handler receives { targets: ['example.test'], note: null } unchanged.
```

| SDK | Schema declaration | Recursive values and validation |
| --- | --- | --- |
| Java | Shared `SchemaDefinition`; `RelayTools.Declaration(name, description, schema, timeout)` | `ToolValue.ObjectValue`, `Array`, `Null`, existing scalar parameter records; `ToolSchemas.validate` |
| TypeScript/Node | `SchemaDefinition`, `ToolDeclaration.inputSchema` | `ToolValue`/`ToolObject`; frozen arrays and objects; `validateSchemaArguments` |
| Python | Frozen `SchemaDefinition`, `ToolDeclaration.input_schema` | `ToolValue`; immutable tuples/mappings; `validate_schema_arguments` |
| Go | `SchemaDefinition`, `ToolDeclaration.InputSchema` | `ToolText`, `ToolNumber`, `ToolBoolean`, `ToolNull`, `ToolArray`, `ToolObject`; `ValidateSchemaArguments` |
| .NET | `SchemaDefinition`, `ToolDeclaration.InputSchema` | `ToolValue` variants; `ToolSchemas.ValidateArguments` |

Raw schema parsing belongs to each SDK codec boundary. Native schemas have typed
fields and do not expose arbitrary JSON bags to handlers. Use keyed Go struct
literals when adding `InputSchema`; old unkeyed `ToolDeclaration` literals need
the new field because Go struct arity is source-visible.

SDK deployment exporters produce operator-installed Spring configuration. Only in
this property format, `inputSchema` is JSON **text** so Spring's property binder
can convert it into `SchemaDefinition` before creating a tool binding. Live
catalogues and Application declarations use an actual schema object; invocation
arguments always retain their recursive values. Use the exporter rather than
flattening schema keys into Spring properties. Duplicate JSON fields, unknown
keywords and invalid schemas refuse startup.

The Java record owns keyword names; `scripts/tool-schema-fields.json` records
native field kinds. `node scripts/generate-sdk-contracts.mjs` checks vocabulary
agreement and regenerates the TypeScript, Python, Go and .NET schema contracts.
Shared fixtures in `test-support/contracts/tool-schemas.json` verify acceptance,
round trips and fingerprints independently in all SDKs. See the
[decision](decisions/0018-shared-tool-schemas.md) and [Relay tools](relay-tools.md).
