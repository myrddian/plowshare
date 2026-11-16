import type { SchemaDefinition, ToolDeclaration } from 'plowshare-client-ts';
import { validateToolSchema } from 'plowshare-client-ts';
import type { BrowserToolName } from './browser-control.ts';
import { checkedBrowserTools } from './browser-tool-arguments.ts';
import { positiveBudget } from './deadline.ts';

const id: SchemaDefinition = { type: 'string', minLength: 1, maxLength: 256 };
const session = { sessionId: id, generation: id };
function object(
  properties: Readonly<Record<string, SchemaDefinition>>,
  required: readonly string[],
): SchemaDefinition {
  return { type: 'object', properties, required, additionalProperties: false };
}
const element = object(
  { kind: { const: 'element' }, snapshotId: id, elementId: id },
  ['kind', 'snapshotId', 'elementId'],
);
const point = object(
  {
    kind: { const: 'point' },
    frameId: id,
    x: { type: 'integer', minimum: 0, maximum: 32767 },
    y: { type: 'integer', minimum: 0, maximum: 32767 },
  },
  ['kind', 'frameId', 'x', 'y'],
);
const target: SchemaDefinition = { oneOf: [point, element] };
const modifiers: SchemaDefinition = {
  type: 'array',
  items: { type: 'string', enum: ['Alt', 'Control', 'Meta', 'Shift'] },
  maxItems: 4,
  uniqueItems: true,
};
const button: SchemaDefinition = {
  type: 'string',
  enum: ['left', 'middle', 'right'],
  default: 'left',
};
const exactMessage: SchemaDefinition = { type: 'string', maxLength: 1024 };
/** No generic accept-all: one optional decision belongs to its initiating action only. */
const dialog: SchemaDefinition = {
  oneOf: [
    object(
      {
        expectedType: { enum: ['alert', 'confirm', 'beforeunload'] },
        decision: { enum: ['accept', 'dismiss'] },
        exactMessage,
      },
      ['expectedType', 'decision'],
    ),
    object(
      {
        expectedType: { const: 'prompt' },
        decision: { const: 'accept' },
        exactMessage,
        promptText: { type: 'string', maxLength: 4096 },
      },
      ['expectedType', 'decision'],
    ),
    object(
      {
        expectedType: { const: 'prompt' },
        decision: { const: 'dismiss' },
        exactMessage,
      },
      ['expectedType', 'decision'],
    ),
  ],
};
const withDialog = { ...session, dialogDecision: dialog };
const pointer: SchemaDefinition = {
  type: 'object',
  oneOf: [
    object({ ...withDialog, action: { const: 'move' }, target }, [
      'sessionId',
      'generation',
      'action',
      'target',
    ]),
    object(
      {
        ...withDialog,
        action: { enum: ['click', 'double_click'] },
        target,
        button,
        modifiers,
      },
      ['sessionId', 'generation', 'action', 'target'],
    ),
    object(
      {
        ...withDialog,
        action: { const: 'drag' },
        frameId: id,
        button,
        modifiers,
        points: {
          type: 'array',
          minItems: 2,
          maxItems: 128,
          items: object(
            {
              x: { type: 'integer', minimum: 0, maximum: 32767 },
              y: { type: 'integer', minimum: 0, maximum: 32767 },
              atMs: { type: 'integer', minimum: 0, maximum: 1000 },
            },
            ['x', 'y', 'atMs'],
          ),
        },
      },
      ['sessionId', 'generation', 'action', 'frameId', 'points'],
    ),
  ],
};
const navigationReference: SchemaDefinition = {
  oneOf: [
    object({ kind: { const: 'frame' }, frameId: id }, ['kind', 'frameId']),
    object({ kind: { const: 'window' }, windowsId: id, surfaceId: id }, [
      'kind',
      'windowsId',
      'surfaceId',
    ]),
  ],
};
function wheel(units: 'pixels' | 'lines', bound: number): SchemaDefinition {
  return {
    ...object(
      {
        ...withDialog,
        target,
        dx: { type: 'number', minimum: -bound, maximum: bound },
        dy: { type: 'number', minimum: -bound, maximum: bound },
        units: { const: units },
      },
      ['sessionId', 'generation', 'target', 'dx', 'dy', 'units'],
    ),
    not: { properties: { dx: { const: 0 }, dy: { const: 0 } } },
  };
}

export interface BrowserCatalogueBudgets {
  readonly controlBudgetMs: number;
  readonly observeBudgetMs: number;
}
interface BrowserToolSpec {
  readonly description: string;
  readonly inputSchema: SchemaDefinition;
  readonly readOnly: boolean;
}
const specs: Readonly<Record<BrowserToolName, BrowserToolSpec>> = {
  windows: {
    description:
      'List this owned browser session’s bounded tab references, readiness and quarantine state. A quarantined popup needs a new explicit navigation.',
    inputSchema: object(session, ['sessionId', 'generation']),
    readOnly: true,
  },
  focus: {
    description:
      'Select an owned tab from a fresh windows snapshot and observe it. Quarantined tabs may have no pixels until explicitly navigated.',
    inputSchema: object({ ...session, windowsId: id, surfaceId: id }, [
      'sessionId',
      'generation',
      'windowsId',
      'surfaceId',
    ]),
    readOnly: false,
  },
  navigate: {
    description:
      'Navigate an owned tab within its configured HTTP(S) origin policy, using a fresh frame or windows reference. Never retries an uncertain navigation.',
    inputSchema: object(
      {
        ...withDialog,
        observation: navigationReference,
        url: { type: 'string', minLength: 1, maxLength: 4096 },
      },
      ['sessionId', 'generation', 'observation', 'url'],
    ),
    readOnly: false,
  },
  pointer: {
    description:
      'One move, click, double_click or drag against a fresh frame/element reference. Drag points start at zero and increase through at most 1000 ms. Observe the returned outcome; delivery does not prove application success.',
    inputSchema: pointer,
    readOnly: false,
  },
  type: {
    description:
      'Insert or explicitly replace at most 4096 UTF-16 units in an observed editable element. Both modes require that exact element to hold focus. No clipboard or secret-input channel.',
    inputSchema: object(
      {
        ...withDialog,
        target: element,
        mode: { enum: ['insert', 'replace'] },
        text: { type: 'string', maxLength: 4096 },
      },
      ['sessionId', 'generation', 'target', 'mode', 'text'],
    ),
    readOnly: false,
  },
  key: {
    description:
      'One allowlisted editing/navigation key chord using fresh frame and expected focused element; null means observed body focus, never a wildcard. Clipboard, file and browser-chrome shortcuts are refused.',
    inputSchema: object(
      {
        ...withDialog,
        frameId: id,
        expectedFocus: { oneOf: [element, { type: 'null' }] },
        key: {
          enum: [
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
            'a',
            'z',
            'y',
          ],
        },
        modifiers,
      },
      ['sessionId', 'generation', 'frameId', 'expectedFocus', 'key'],
    ),
    readOnly: false,
  },
  scroll: {
    description:
      'Scroll a fresh frame/element target using explicit pixels or lines. The result reports CSS-pixel conversion; observation is separate from established input delivery.',
    inputSchema: {
      type: 'object',
      oneOf: [wheel('pixels', 32768), wheel('lines', 128)],
    },
    readOnly: false,
  },
};

function declaration(
  name: string,
  spec: BrowserToolSpec,
  budget: number,
): ToolDeclaration {
  positiveBudget(budget);
  return Object.freeze({
    name,
    description: spec.description,
    parameters: Object.freeze([]),
    inputSchema: validateToolSchema(spec.inputSchema),
    resultProfile: 'content/1',
    timeoutSeconds: Math.ceil(budget / 1000),
  });
}
/** Only explicitly enabled names are advertised; server Relay grants remain independent. */
function checkedPrefix(prefix: string): void {
  if (!/^[a-z][a-z0-9_]*_$/u.test(prefix) || prefix.length > 200)
    throw new Error('Invalid browser tool prefix');
}
export function browserToolDeclarations(
  prefix: string,
  budgets: BrowserCatalogueBudgets,
  tools: readonly BrowserToolName[],
): readonly ToolDeclaration[] {
  checkedPrefix(prefix);
  positiveBudget(budgets.controlBudgetMs);
  positiveBudget(budgets.observeBudgetMs);
  return Object.freeze(
    checkedBrowserTools(tools).map((name) =>
      declaration(
        prefix + name,
        specs[name],
        specs[name].readOnly
          ? budgets.controlBudgetMs
          : budgets.observeBudgetMs,
      ),
    ),
  );
}
/** Controlled observation uses the same image path with optional bounded semantic structure. */
export function browserObserveDeclaration(
  prefix: string,
  budget: number,
): ToolDeclaration {
  checkedPrefix(prefix);
  const shape = object(
    {
      ...session,
      surfaceId: id,
      structured: { type: 'boolean', default: true },
      root: element,
    },
    ['sessionId', 'generation', 'surfaceId'],
  );
  return declaration(
    prefix + 'observe',
    {
      description:
        'Observe a selected owned surface with fresh pixels and optional bounded semantic DOM structure. A root must be an exact previously observed element; root requires structured observation.',
      readOnly: true,
      inputSchema: {
        ...shape,
        allOf: [
          {
            if: { type: 'object', required: ['root'] },
            then: { properties: { structured: { const: true } } },
          },
        ],
      },
    },
    budget,
  );
}
