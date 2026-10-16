import type { PortableCodec } from 'plowshare-client-ts/operations/interchange-files';
import { nodePacketCodec } from './packets.js';
export {
  unpackPortableBundle,
  packEditedPortableBundle,
  readablePortableFiles,
} from 'plowshare-client-ts/operations/interchange-files';
export type {
  PortableFile,
  PortableManifest,
} from 'plowshare-client-ts/operations/interchange-files';
/** Reuse the Node transport's byte/crypto capability without accessing a filesystem. */
export const portableCodec: PortableCodec = nodePacketCodec;
