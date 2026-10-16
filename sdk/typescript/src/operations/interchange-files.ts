import type { PacketCodec } from '../binding/packets.ts';
import type {
  PortableBundle,
  PortableDescriptor,
  PortableLink,
  PortableReadable,
} from './information-interchange.ts';
import { decodeReply } from './schema.ts';
import { portablePath } from './interchange-validation.ts';

/** Caller-owned bytes and relative display paths. These helpers never access a filesystem. */
export interface PortableFile {
  readonly path: string;
  readonly bytes: Uint8Array;
}
export interface PortableManifest {
  readonly format: 'plowshare-information-bundle/1';
  readonly id: string;
  readonly documents: readonly PortableDescriptor[];
  readonly relationships: readonly PortableLink[];
  readonly relationshipCoverage: 'included-endpoints-only';
}
export type PortableCodec = Readonly<
  Pick<PacketCodec, 'encode' | 'base64' | 'unbase64' | 'hash'>
>;
/** Verify hashes before returning bytes to a caller that may choose to write them. */
export async function unpackPortableBundle(
  source: PortableBundle,
  codec: PortableCodec,
): Promise<{
  readonly manifest: PortableManifest;
  readonly files: readonly PortableFile[];
}> {
  const bundle = decodeReply('information.interchange.export', source);
  const files: PortableFile[] = [];
  for (const doc of bundle.documents) {
    const data = codec.unbase64(doc.bytesBase64);
    if ((await codec.hash(data)) !== doc.descriptor.contentHash)
      throw new Error('Portable original content hash differs');
    files.push({ path: portablePath(doc.descriptor.path), bytes: data });
  }
  return {
    manifest: {
      format: bundle.format,
      id: bundle.id,
      documents: bundle.documents.map((d) => d.descriptor),
      relationships: bundle.relationships,
      relationshipCoverage: bundle.relationshipCoverage,
    },
    files,
  };
}
/**
 * Repack explicitly edited files. Preserve sourceHash and foreign identities as unverified origin;
 * contentHash describes the replacement bytes. Import still needs an explicit destination plan.
 */
export async function packEditedPortableBundle(
  manifest: PortableManifest,
  files: readonly PortableFile[],
  codec: PortableCodec,
): Promise<PortableBundle> {
  if (files.length > 16 || manifest.documents.length > 16)
    throw new Error('Portable record bound');
  const byPath = new Map<string, Uint8Array>();
  let total = 0;
  for (const file of files) {
    const path = portablePath(file.path);
    if (byPath.has(path)) throw new Error('Duplicate portable file');
    const owned = new Uint8Array(file.bytes);
    total += owned.length;
    if (total > 2097152)
      throw new Error('Portable original bytes exceed 2 MiB');
    byPath.set(path, owned);
  }
  const documents = [];
  for (const descriptor of manifest.documents) {
    const data = byPath.get(portablePath(descriptor.path));
    if (!data) throw new Error('Portable file missing');
    byPath.delete(descriptor.path);
    documents.push({
      descriptor: { ...descriptor, contentHash: await codec.hash(data) },
      bytesBase64: codec.base64(data),
    });
  }
  if (byPath.size) throw new Error('Portable files not present in manifest');
  return decodeReply('information.interchange.export', {
    format: manifest.format,
    id: manifest.id,
    documents,
    relationships: manifest.relationships,
    relationshipCoverage: manifest.relationshipCoverage,
  });
}
/** An extraction is labelled in the sidecar and never packaged as original bytes. */
export async function readablePortableFiles(
  source: PortableReadable,
  codec: PortableCodec,
): Promise<readonly PortableFile[]> {
  const readable = decodeReply('information.interchange.readable', source),
    data = codec.encode(readable.markdown);
  if ((await codec.hash(data)) !== readable.textHash)
    throw new Error('Readable export text hash differs');
  return [
    { path: readable.path, bytes: data },
    {
      path: portablePath(readable.path + '.json'),
      bytes: codec.encode(
        JSON.stringify(
          {
            format: readable.format,
            representation: readable.representation,
            textHash: readable.textHash,
            descriptor: readable.sidecar,
          },
          null,
          2,
        ) + '\n',
      ),
    },
  ];
}
