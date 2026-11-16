import type { SchemaDefinition, ToolDeclaration } from 'plowshare-client-ts';
import { validateToolSchema } from 'plowshare-client-ts';
import type { BrowserToolName } from './browser-control.ts';
import type { NativeToolName } from './native-control.ts';
import type { BrowserCatalogueBudgets } from './browser-tool-catalogue.ts';
import {
  browserObserveDeclaration,
  browserToolDeclarations,
} from './browser-tool-catalogue.ts';
import { checkedBrowserTools } from './browser-tool-arguments.ts';
import { checkedNativeTools } from './native-tool-arguments.ts';

const descriptions: Readonly<Record<NativeToolName, string>> = {
  windows:
    'List bounded window or tab references belonging to the selected controlled session. Native metadata and permission support are reported explicitly.',
  focus:
    'Focus an explicitly returned window or tab using a fresh windows reference, then observe it. Focus delivery does not prove application success.',
  pointer:
    'One bounded move, click, double_click or drag using a fresh frame or exact element reference. Input is never repeated after uncertain delivery.',
  type: 'Insert or explicitly replace at most 4096 UTF-16 units in the exact observed, focused editable element. No clipboard, credential channel or arbitrary accessibility action.',
  key: 'One allowlisted editing/navigation chord with a fresh frame and exact expected focus. No raw held inputs, clipboard, file or application-launch shortcuts.',
  scroll:
    'Scroll a fresh frame or exact element target with explicit pixels or lines. Report the native or browser conversion separately from application success.',
};

/** Remove only the known top-level browser dialog extension, including union arms. */
function withoutBrowserDialog(schema: SchemaDefinition): SchemaDefinition {
  const { properties, oneOf, ...other } = schema;
  const selected =
    properties === undefined
      ? undefined
      : Object.fromEntries(
          Object.entries(properties).filter(
            ([name]) => name !== 'dialogDecision',
          ),
        );
  return {
    ...other,
    ...(selected === undefined ? {} : { properties: selected }),
    ...(oneOf === undefined ? {} : { oneOf: oneOf.map(withoutBrowserDialog) }),
  };
}

function nativeSchema(
  name: NativeToolName,
  schema: SchemaDefinition,
): SchemaDefinition {
  const shape = withoutBrowserDialog(schema);
  if (name === 'scroll') {
    if (shape.oneOf === undefined)
      throw new Error('Native scroll requires explicit unit variants');
    return {
      ...shape,
      oneOf: shape.oneOf.map((arm) => ({
        ...arm,
        properties: {
          ...arm.properties,
          dx: { ...arm.properties?.['dx'], type: 'integer' },
          dy: { ...arm.properties?.['dy'], type: 'integer' },
        },
      })),
    };
  }
  if (name !== 'key') return shape;
  const properties = shape.properties;
  if (properties === undefined)
    throw new Error('Native key schema requires explicit properties');
  const plain = [
    'Enter',
    'Tab',
    'Escape',
    'Backspace',
    'Delete',
    'Home',
    'End',
    'PageUp',
    'PageDown',
    'ArrowUp',
    'ArrowDown',
    'ArrowLeft',
    'ArrowRight',
    'Space',
  ];
  const shifted = plain.filter(
    (key) => !['Escape', 'Backspace', 'Delete'].includes(key),
  );
  const chord = (
    keys: readonly string[],
    modifiers: readonly string[],
    count: number,
  ): SchemaDefinition => ({
    ...shape,
    properties: {
      ...properties,
      key: { enum: keys },
      modifiers: {
        type: 'array',
        items: { enum: modifiers.length === 0 ? ['Meta', 'Shift'] : modifiers },
        minItems: count,
        maxItems: count,
        uniqueItems: true,
      },
    },
    required: [
      ...(shape.required ?? []),
      ...(count === 0 ? [] : ['modifiers']),
    ],
  });
  return {
    type: 'object',
    oneOf: [
      chord(plain, [], 0),
      chord(shifted, ['Shift'], 1),
      chord(['a', 'z'], ['Meta'], 1),
      chord(['z'], ['Meta', 'Shift'], 2),
    ],
  };
}

/**
 * One catalogue for the shared provider. A shared tool name retains browser
 * dialog syntax only when a configured browser offers that tool; the native
 * boundary still refuses it. Local target policy narrows the server tool grant.
 * With no native tools this returns the original browser declarations unchanged.
 */
export function controlledToolDeclarations(
  prefix: string,
  budgets: BrowserCatalogueBudgets,
  browserTools: readonly BrowserToolName[],
  nativeTools: readonly NativeToolName[],
): readonly ToolDeclaration[] {
  const browser = checkedBrowserTools(browserTools);
  const native = checkedNativeTools(nativeTools);
  if (native.length === 0)
    return browserToolDeclarations(prefix, budgets, browser);
  const union = checkedBrowserTools([...new Set([...browser, ...native])]);
  return Object.freeze(
    browserToolDeclarations(prefix, budgets, union).map((declaration) => {
      const name = union.find((name) => declaration.name === prefix + name);
      if (name === undefined || name === 'navigate' || !native.includes(name))
        return declaration;
      const schema = declaration.inputSchema;
      if (schema === undefined)
        throw new Error('Controlled tool requires a recursive schema');
      return Object.freeze({
        ...declaration,
        description: descriptions[name],
        inputSchema: browser.includes(name)
          ? schema
          : validateToolSchema(nativeSchema(name, schema)),
      });
    }),
  );
}

export function controlledObserveDeclaration(
  prefix: string,
  budget: number,
  nativeEnabled: boolean,
): ToolDeclaration {
  const declaration = browserObserveDeclaration(prefix, budget);
  return !nativeEnabled
    ? declaration
    : Object.freeze({
        ...declaration,
        description:
          'Observe the selected owned surface with fresh pixels and optional bounded DOM or accessibility structure. An optional subtree root must be an exact previously emitted element. Unsupported structure is refused explicitly.',
      });
}
