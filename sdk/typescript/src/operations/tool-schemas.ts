/** Compatibility facade; the shared bounded engine is independent of tool invocation. */
export {
  SCHEMA_DIALECT as TOOL_SCHEMA_DIALECT,
  portableSchemaNumber as portableToolNumber,
  decodeSchemaInstance as decodeToolValue,
  decodeSchemaDefinition as decodeToolSchema,
  validateSchemaDefinition as validateToolSchema,
  validateSchemaArguments,
  matchesSchemaFingerprint as matchesToolSchemaFingerprint,
  canonicalSchemaDefinition as canonicalToolSchema,
} from './schema-instances.ts';
export type {
  SchemaScalar as ToolScalar,
  SchemaInstance as ToolValue,
  SchemaObject as ToolObject,
} from './schema-instances.ts';
