import ts from 'typescript'
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
const root = fileURLToPath(new URL('../', import.meta.url))
const program = ts.createProgram([resolve(root, 'src/operations/direct.ts'), resolve(root, 'src/operations/replies.ts')], {
    strict: true, target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.NodeNext,
    moduleResolution: ts.ModuleResolutionKind.NodeNext, skipLibCheck: true,
})
const checker = program.getTypeChecker(), definitions = {}, memo = new Map()
function schema(type) {
    if (memo.has(type.id)) return { $ref: '#/$defs/' + memo.get(type.id) }
    const key = 'shape' + memo.size
    memo.set(type.id, key)
    definitions[key] = body(type)
    return { $ref: '#/$defs/' + key }
}
function body(type) {
    const f = ts.TypeFlags
    if (type.isUnion()) {
        const types = type.types.filter(t => !(t.flags & f.Undefined))
        return types.length === 1 ? schema(types[0]) : { anyOf: types.map(schema) }
    }
    if (type.flags & f.StringLiteral) return { const: type.value }
    if (type.flags & f.NumberLiteral) return { const: type.value }
    if (type.flags & f.BooleanLiteral) return { const: type.intrinsicName === 'true' }
    if (type.flags & f.Null) return { type: 'null' }
    if (type.flags & f.StringLike) return { type: 'string' }
    if (type.flags & f.NumberLike) return { type: 'number' }
    if (type.flags & f.BooleanLike) return { type: 'boolean' }
    if (type.flags & (f.Unknown | f.Any | f.Undefined | f.Never)) return {}
    const element = checker.getIndexTypeOfType(type, ts.IndexKind.Number)
    if (element) return { type: 'array', items: schema(element) }
    const properties = {}, required = []
    for (const prop of type.getProperties().sort((a, b) => a.name.localeCompare(b.name))) {
        properties[prop.name] = schema(checker.getTypeOfSymbolAtLocation(prop, prop.valueDeclaration ?? prop.declarations[0]))
        if (!(prop.flags & ts.SymbolFlags.Optional)) required.push(prop.name)
    }
    const index = checker.getIndexTypeOfType(type, ts.IndexKind.String)
    return { type: 'object', properties, ...(required.length ? { required } : {}), additionalProperties: index ? schema(index) : true }
}
function contract(file, name) {
    const source = program.getSourceFile(resolve(root, 'src/operations/' + file + '.ts'))
    const decl = source.statements.find(s => ts.isInterfaceDeclaration(s) && s.name.text === name)
    const type = checker.getTypeAtLocation(decl)
    return Object.fromEntries(type.getProperties().sort((a,b) => a.name.localeCompare(b.name)).map(prop => [prop.name,
        schema(checker.getTypeOfSymbolAtLocation(prop, decl))]))
}
const result = { inputs: contract('direct', 'Payloads'), results: contract('replies', 'Replies'), $defs: definitions }
const output = '// Generated from Payloads and Replies; run node scripts/generate-operation-schemas.mjs.\n'
    + 'export const OPERATION_SCHEMAS: { inputs: Record<string, Record<string, unknown>>; results: Record<string, Record<string, unknown>>; $defs: Record<string, Record<string, unknown>> } = '
    + '{\n' + Object.entries(result).map(([name, values]) => '  ' + JSON.stringify(name) + ': {\n' + Object.entries(values).map(([key, value]) => '    ' + JSON.stringify(key) + ': ' + JSON.stringify(value)).join(',\n') + '\n  }').join(',\n') + '\n}\n'
const target = resolve(root, 'src/operations/operation-schemas.ts')
if (process.argv.includes('--check')) {
    if (readFileSync(target, 'utf8') !== output) throw new Error('Operation schemas are stale; regenerate from the canonical contracts')
} else writeFileSync(target, output)
