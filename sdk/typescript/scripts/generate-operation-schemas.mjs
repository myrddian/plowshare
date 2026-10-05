import ts from 'typescript';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
const desktop = process.argv.includes('--desktop');
const consoleMode = process.argv.includes('--console');
const consoleRoot = resolve(root, '../../plowshare-console');
const desktopRoot = resolve(root, '../../plowshare-desktop');
const sources = consoleMode
  ? [resolve(consoleRoot, 'src/transport.ts')]
  : desktop
    ? [resolve(desktopRoot, 'src/shared.ts')]
    : [
        resolve(root, 'src/operations/direct.ts'),
        resolve(root, 'src/operations/replies.ts'),
        resolve(root, 'src/operations/push.ts'),
      ];
const program = ts.createProgram(sources, {
  strict: true,
  exactOptionalPropertyTypes: true,
  target: ts.ScriptTarget.ES2022,
  module: consoleMode ? ts.ModuleKind.ESNext : ts.ModuleKind.NodeNext,
  moduleResolution: consoleMode
    ? ts.ModuleResolutionKind.Bundler
    : ts.ModuleResolutionKind.NodeNext,
  skipLibCheck: true,
  baseUrl: resolve(root, '../..'),
  // Resolve canonical sources without relying on another client's installed
  // package links or build outputs. NodeNext ESM needs the explicit extension.
  paths: {
    'plowshare-client-ts/*': ['sdk/typescript/src/*.ts'],
    'plowshare-client-node/*': ['sdk/node/src/*.ts'],
  },
});
const checker = program.getTypeChecker(),
  definitions = {},
  memo = new Map();
function schema(type) {
  if (memo.has(type.id)) return { $ref: '#/$defs/' + memo.get(type.id) };
  const key = 'shape' + memo.size;
  memo.set(type.id, key);
  definitions[key] = body(type);
  const title = type.aliasSymbol?.name ?? type.symbol?.name;
  if (
    !desktop &&
    !consoleMode &&
    title &&
    !title.startsWith('__') &&
    definitions[key].type === 'object'
  )
    definitions[key].title = title;
  return { $ref: '#/$defs/' + key };
}
function body(type) {
  const f = ts.TypeFlags;
  if (type.isUnion()) {
    const types = type.types.filter((t) => !(t.flags & f.Undefined));
    return types.length === 1 ? schema(types[0]) : { anyOf: types.map(schema) };
  }
  if (type.flags & f.StringLiteral) return { const: type.value };
  if (type.flags & f.NumberLiteral) return { const: type.value };
  if (type.flags & f.BooleanLiteral)
    return { const: type.intrinsicName === 'true' };
  if (type.flags & f.Null) return { type: 'null' };
  if (type.flags & f.StringLike) return { type: 'string' };
  if (type.flags & f.NumberLike) return { type: 'number' };
  if (type.flags & f.BooleanLike) return { type: 'boolean' };
  if (type.flags & f.Void) return { optional: true };
  if (type.flags & f.Never) return { forbidden: true };
  if (type.flags & f.Undefined) return { forbidden: true };
  if (type.flags & (f.Unknown | f.Any))
    throw new Error('Unspecified operation DTO: ' + checker.typeToString(type));
  if (checker.isTupleType(type))
    return {
      type: 'array',
      prefixItems: checker.getTypeArguments(type).map(schema),
      minItems: type.target.minLength,
      maxItems: type.target.fixedLength,
    };
  const element = checker.getIndexTypeOfType(type, ts.IndexKind.Number);
  if (element) return { type: 'array', items: schema(element) };
  const properties = {},
    required = [];
  for (const prop of type
    .getProperties()
    .sort((a, b) => a.name.localeCompare(b.name))) {
    properties[prop.name] = schema(
      checker.getTypeOfSymbolAtLocation(
        prop,
        prop.valueDeclaration ??
          prop.declarations?.[0] ??
          program.getSourceFile(sources[0]),
      ),
    );
    if (!(prop.flags & ts.SymbolFlags.Optional)) required.push(prop.name);
  }
  const index = checker.getIndexTypeOfType(type, ts.IndexKind.String);
  return {
    type: 'object',
    properties,
    ...(required.length ? { required } : {}),
    additionalProperties: index ? schema(index) : false,
  };
}
function contract(file, name) {
  const source = program.getSourceFile(
    resolve(root, 'src/operations/' + file + '.ts'),
  );
  const decl = source.statements.find(
    (s) => ts.isInterfaceDeclaration(s) && s.name.text === name,
  );
  const type = checker.getTypeAtLocation(decl);
  return Object.fromEntries(
    type
      .getProperties()
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((prop) => [
        prop.name,
        schema(checker.getTypeOfSymbolAtLocation(prop, decl)),
      ]),
  );
}
let result, output, target;
if (consoleMode) {
  const source = program.getSourceFile(
    resolve(consoleRoot, 'src/transport.ts'),
  );
  const declaration = source.statements.find(
    (s) => ts.isInterfaceDeclaration(s) && s.name.text === 'ConsoleReplies',
  );
  const type = checker.getTypeAtLocation(declaration);
  result = {
    results: Object.fromEntries(
      type
        .getProperties()
        .map((prop) => [
          prop.name,
          schema(checker.getTypeOfSymbolAtLocation(prop, declaration)),
        ]),
    ),
    $defs: definitions,
  };
  output =
    "// Generated from ConsoleReplies; run the SDK schema generator with --console.\nimport type {Schema} from '../../sdk/typescript/src/operations/schema.ts';\nexport const CONSOLE_SCHEMAS:{results:Record<string,Schema>;$defs:Record<string,Schema>}= " +
    JSON.stringify(result, null, 2) +
    '\n';
  target = resolve(consoleRoot, 'src/transport-schemas.ts');
} else if (desktop) {
  const source = program.getSourceFile(resolve(desktopRoot, 'src/shared.ts'));
  const declaration = source.statements.find(
    (s) => ts.isTypeAliasDeclaration(s) && s.name.text === 'Request',
  );
  result = {
    request: schema(checker.getTypeAtLocation(declaration)),
    $defs: definitions,
  };
  output =
    '// Generated from Desktop Request; run the SDK schema generator with --desktop.\n' +
    "import type { Schema } from 'plowshare-client-ts/operations/schema'\n" +
    'export const DESKTOP_SCHEMA: {request: Schema; $defs: Record<string,Schema>} = ' +
    JSON.stringify(result, null, 2) +
    '\n';
  target = resolve(desktopRoot, 'src/request-schemas.ts');
} else {
  result = {
    inputs: contract('direct', 'Payloads'),
    results: contract('replies', 'Replies'),
    pushes: (() => {
      const source = program.getSourceFile(
        resolve(root, 'src/operations/push.ts'),
      );
      const declaration = source.statements.find(
        (s) => ts.isTypeAliasDeclaration(s) && s.name.text === 'ServerPush',
      );
      return schema(checker.getTypeAtLocation(declaration));
    })(),
    $defs: definitions,
  };
  output =
    '// Generated from Payloads and Replies; run node scripts/generate-operation-schemas.mjs.\n' +
    "import type { Schema } from './schema.ts'\nexport const OPERATION_SCHEMAS: { inputs: Record<string, Schema>; results: Record<string, Schema>; pushes: Schema; $defs: Record<string, Schema> } = " +
    '{\n' +
    Object.entries(result)
      .map(([name, values]) =>
        name === 'pushes'
          ? '  ' + JSON.stringify(name) + ': ' + JSON.stringify(values)
          : '  ' +
            JSON.stringify(name) +
            ': {\n' +
            Object.entries(values)
              .map(
                ([key, value]) =>
                  '    ' + JSON.stringify(key) + ': ' + JSON.stringify(value),
              )
              .join(',\n') +
            '\n  }',
      )
      .join(',\n') +
    '\n}\n';
  target = resolve(root, 'src/operations/operation-schemas.ts');
}
if (process.argv.includes('--check')) {
  if (readFileSync(target, 'utf8') !== output)
    throw new Error(
      'Operation schemas are stale; regenerate from the canonical contracts',
    );
} else writeFileSync(target, output);
