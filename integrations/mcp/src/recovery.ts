import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import {
  privateDirectory,
  privateFile,
} from 'plowshare-client-node/connections';
import {
  nodeToolHost,
  ToolProvider,
  toolDeploymentConfig,
} from 'plowshare-client-node/tools';
import type {
  HostedToolRegistration,
  ToolDeclaration,
} from 'plowshare-client-ts';
import { hostedRegistrationCheck } from 'plowshare-client-ts/operations/hosted-tool-scopes';
import {
  decodeToolSchema,
  validateToolSchema,
} from 'plowshare-client-ts/operations/tool-schemas';
import { FileToolJournal, durablePrivateWrite } from './journal.ts';
import { object, text } from './codec.ts';
import { rejected } from './contracts.ts';
import type { ProviderConnection } from './provider.ts';

export interface RecoveryCatalogue {
  readonly identity: string;
  readonly registration: HostedToolRegistration;
  readonly declarations: readonly ToolDeclaration[];
}
export interface RecoveryCatalogues {
  all(): Promise<readonly RecoveryCatalogue[]>;
  save(catalogue: RecoveryCatalogue): Promise<void>;
}
function registration(source: unknown): HostedToolRegistration {
  if (!hostedRegistrationCheck(source))
    throw new Error('Invalid retained MCP registration');
  // The public SDK checker validates the complete registration, nested binding and route.
  return source as HostedToolRegistration;
}
function declaration(source: unknown): ToolDeclaration {
  const raw = object(source);
  if (
    Object.keys(raw).some(
      (key) =>
        ![
          'name',
          'description',
          'parameters',
          'timeoutSeconds',
          'inputSchema',
        ].includes(key),
    ) ||
    !Array.isArray(raw.parameters) ||
    raw.parameters.length !== 0 ||
    typeof raw.timeoutSeconds !== 'number' ||
    !Number.isInteger(raw.timeoutSeconds) ||
    raw.timeoutSeconds < 1 ||
    raw.timeoutSeconds > 300
  )
    throw new Error('Invalid retained MCP declaration');
  const inputSchema = decodeToolSchema(raw.inputSchema);
  validateToolSchema(inputSchema);
  return Object.freeze({
    name: text(raw.name, 64),
    description: text(raw.description, 1024),
    parameters: [],
    timeoutSeconds: raw.timeoutSeconds,
    inputSchema,
  });
}
async function validate(catalogue: RecoveryCatalogue): Promise<void> {
  const route = catalogue.registration.binding.route;
  const config = toolDeploymentConfig(
    {
      project: route.project,
      provider: route.provider,
      account: route.account,
    },
    catalogue.declarations,
  );
  if (catalogue.identity !== (await nodeToolHost.sha256(config)))
    throw new Error('Retained MCP catalogue identity changed');
}
/** Immutable declarations let the SDK validate old receipts after credential or catalogue rotation.
 * This repository holds no MCP credential, cache, session, output data or execution authority.
 */
export class FileRecoveryCatalogues implements RecoveryCatalogues {
  constructor(
    private readonly directory: string,
    private readonly binding: string,
    private readonly maxCatalogues: number = 128,
  ) {}
  async all(): Promise<readonly RecoveryCatalogue[]> {
    await privateDirectory(this.directory);
    const files = (await readdir(this.directory)).filter((file) =>
      /^[0-9a-f]{64}\.deployment\.json$/.test(file),
    );
    if (files.length > this.maxCatalogues)
      throw new Error('MCP recovery catalogue archive exceeds bound');
    const catalogues: RecoveryCatalogue[] = [];
    for (const file of files.sort()) {
      const path = join(this.directory, file);
      await privateFile(path);
      const source: unknown = JSON.parse(await readFile(path, 'utf8'));
      const raw = object(source);
      if (
        !Array.isArray(raw.declarations) ||
        !raw.declarations.length ||
        raw.declarations.length > 128
      )
        throw new Error('Invalid retained MCP catalogue');
      const sources: readonly unknown[] = raw.declarations;
      const catalogue = {
        identity: text(raw.identity, 64),
        registration: registration(raw.registration),
        declarations: Object.freeze(sources.map(declaration)),
      };
      if (
        file !== catalogue.identity + '.deployment.json' ||
        catalogue.registration.binding.binding !== this.binding
      )
        throw new Error('Foreign retained MCP catalogue');
      await validate(catalogue);
      catalogues.push(catalogue);
    }
    return Object.freeze(catalogues);
  }
  async save(catalogue: RecoveryCatalogue): Promise<void> {
    if (catalogue.registration.binding.binding !== this.binding)
      throw new Error('Foreign MCP recovery binding');
    await validate(catalogue);
    await privateDirectory(this.directory);
    const file = join(this.directory, catalogue.identity + '.deployment.json');
    // Only the original registration is retained: each receipt also carries its exact worker fence.
    await privateFile(file);
    try {
      await readFile(file);
      return;
    } catch (error) {
      if (
        !(error instanceof Error) ||
        !('code' in error) ||
        error.code !== 'ENOENT'
      )
        throw error;
    }
    if ((await this.all()).length >= this.maxCatalogues)
      throw new Error(
        'MCP recovery catalogue archive reached configured bound',
      );
    await durablePrivateWrite(file, catalogue);
  }
}
/** Read-only SDK reconciliation for every retained generation. Never poll an old catalogue or open MCP. */
export async function reconcileCatalogues(
  connection: ProviderConnection,
  catalogues: RecoveryCatalogues,
  directory: string,
): Promise<number> {
  let verified = 0;
  for (const catalogue of await catalogues.all()) {
    const route = catalogue.registration.binding.route;
    const journal = await FileToolJournal.open(directory, catalogue.identity);
    const tools = catalogue.declarations.map((declaration) => ({
      declaration,
      handler: () =>
        Promise.resolve(rejected('Retired MCP catalogue cannot execute.')),
    }));
    const provider = await ToolProvider.create(
      connection,
      {
        project: route.project,
        provider: route.provider,
        account: route.account,
      },
      tools,
      journal,
      nodeToolHost,
      catalogue.registration,
    );
    verified += await provider.reconcile();
  }
  return verified;
}
