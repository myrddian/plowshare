import { open, rename, rm } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { randomUUID } from 'node:crypto';
import { decodeImportRecovery } from './import-recovery-contract.ts';
import type { ImportRecovery } from './import-recovery-contract.ts';

/** Main-process capability: persist a recovery record at an explicitly user-selected file. */
export interface ImportRecoveryFiles {
  save(file: string, recovery: ImportRecovery): Promise<void>;
}
/** Private atomic replacement. Resolution means the complete file was written and synced. */
export class PrivateImportRecoveryFiles implements ImportRecoveryFiles {
  async save(file: string, recovery: ImportRecovery): Promise<void> {
    if (!isAbsolute(file) || file.includes('\0') || file.length > 4096)
      throw new Error('Choose an absolute recovery-file destination.');
    const text = JSON.stringify(decodeImportRecovery(recovery)) + '\n';
    if (Buffer.byteLength(text) > 8388608)
      throw new Error('Import recovery file exceeds 8 MiB.');
    const temporary = file + '.' + randomUUID();
    try {
      const handle = await open(temporary, 'wx', 0o600);
      try {
        await handle.writeFile(text);
        await handle.sync();
      } finally {
        await handle.close();
      }
      // Rename replaces the chosen directory entry; it never follows a destination symlink.
      await rename(temporary, file);
    } finally {
      await rm(temporary, { force: true });
    }
  }
}
