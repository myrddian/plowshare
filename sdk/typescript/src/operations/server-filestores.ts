import { fileStoreReference } from '../binding/filestores.ts';
import { list, record } from './wire-checks.ts';

export interface FileStoreOption {
  readonly alias: string;
  readonly role: 'VIEWER' | 'CONTRIBUTOR' | 'MANAGER';
}

/** Caller-visible server storage. Host roots and other accounts' grants stay private. */
export interface FileStoreCatalog {
  readonly stores: readonly FileStoreOption[];
}

export const fileStoreCatalogCheck = record(
  {
    stores: list(
      record({
        alias: (value) => fileStoreReference({ store: value, path: '' }),
        role: (value) =>
          value === 'VIEWER' || value === 'CONTRIBUTOR' || value === 'MANAGER',
      }),
    ),
  },
  (row) => {
    // record/list above establish the bounded DTO's field types before this semantic check.
    return Array.isArray(row['stores']) && row['stores'].length <= 100;
  },
);
