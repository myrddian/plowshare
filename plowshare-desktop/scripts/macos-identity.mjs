import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export const macBundleIdentifier = 'io.aeyer.plowshare';

/** Locate the single build UUID in a little-endian 64-bit Mach-O executable. */
export function executableUuidOffset(bytes) {
  if (
    !Buffer.isBuffer(bytes) ||
    bytes.length < 32 ||
    bytes.readUInt32LE(0) !== 0xfeedfacf
  ) {
    throw new Error('Expected a thin little-endian 64-bit Mach-O executable');
  }
  if (bytes.readUInt32LE(12) !== 2) {
    throw new Error('App identity may only be applied to a Mach-O executable');
  }
  const commands = bytes.readUInt32LE(16);
  const commandBytes = bytes.readUInt32LE(20);
  const end = 32 + commandBytes;
  if (end > bytes.length || commands > Math.floor(commandBytes / 8)) {
    throw new Error('Invalid Mach-O load command bounds');
  }
  let cursor = 32;
  let uuidOffset;
  for (let index = 0; index < commands; index++) {
    if (cursor + 8 > end) throw new Error('Truncated Mach-O load command');
    const command = bytes.readUInt32LE(cursor);
    const size = bytes.readUInt32LE(cursor + 4);
    if (size < 8 || size % 8 !== 0 || cursor + size > end) {
      throw new Error('Invalid Mach-O load command size');
    }
    if (command === 0x1b) {
      if (size !== 24 || uuidOffset !== undefined) {
        throw new Error('Expected exactly one valid Mach-O LC_UUID');
      }
      uuidOffset = cursor + 8;
    }
    cursor += size;
  }
  if (cursor !== end || uuidOffset === undefined) {
    throw new Error('Expected a complete Mach-O load table with LC_UUID');
  }
  return uuidOffset;
}

/**
 * Give a fresh Electron launcher its application's build identity before signing.
 *
 * macOS Local Network privacy also uses LC_UUID, so renaming a stock Electron
 * launcher and its plist is insufficient (Apple TN3178/TN3179). Derive a version-8
 * UUID from the app ID, CPU and original Electron build UUID. Fresh copies of the
 * same runtime keep one identity across paths/rebuilds; other apps and runtimes
 * get different identities. This is a single pass over the freshly extracted
 * launcher, never a post-signing edit or a change to the shared Electron cache.
 */
export function withMacAppIdentity(bytes, identifier) {
  if (
    typeof identifier !== 'string' ||
    !/^[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/.test(identifier)
  ) {
    throw new Error('An explicit app bundle identifier is required');
  }
  const offset = executableUuidOffset(bytes);
  const uuid = createHash('sha256')
    .update('Plowshare macOS launcher identity v1\0')
    .update(identifier)
    .update('\0')
    .update(bytes.subarray(4, 12))
    .update(bytes.subarray(offset, offset + 16))
    .digest()
    .subarray(0, 16);
  uuid[6] = (uuid[6] & 0x0f) | 0x80;
  uuid[8] = (uuid[8] & 0x3f) | 0x80;
  const result = Buffer.from(bytes);
  uuid.copy(result, offset);
  return result;
}

/** Packager afterExtract hook: mutate only this build's main launcher before signing. */
export async function prepareMacAppIdentity({ buildPath, platform }) {
  if (platform !== 'darwin')
    throw new Error('The app identity hook requires a macOS target');
  const executable = join(buildPath, 'Electron.app/Contents/MacOS/Electron');
  const bytes = await readFile(executable);
  await writeFile(executable, withMacAppIdentity(bytes, macBundleIdentifier));
}
