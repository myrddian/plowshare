import { utf8Length } from '../binding/files.ts';
import { isList } from '../binding/values.ts';
import { fileStoreReference } from '../binding/filestores.ts';
import { record, list, named, nullable } from './wire-checks.ts';

/** Source packages contain portable UTF-8 text; runtime data is admitted separately. */
export interface ApplicationSourceFile {
  readonly path: string;
  readonly text: string;
}
export interface ApplicationRelease {
  readonly revision: string;
  readonly digest: string;
  readonly fileCount: number;
}
export interface ApplicationDeploymentReceipt {
  readonly requestId: string;
  readonly project: string;
  readonly release: ApplicationRelease;
}
export interface ApplicationDeploymentStatus {
  readonly project: string;
  readonly activeRevision: string | null;
  readonly releases: readonly ApplicationRelease[];
}
export const deploymentUuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  value.length === 36 &&
  /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(value);
export function applicationFilesProblem(value: unknown): string | undefined {
  if (!isList(value) || value.length === 0 || value.length > 128)
    return 'Application package needs 1–128 text files';
  const paths = new Set<string>();
  let bytes = 0;
  for (const file of value) {
    if (
      typeof file !== 'object' ||
      file === null ||
      isList(file) ||
      Object.keys(file).sort().join(',') !== 'path,text'
    )
      return 'Application files need exactly path and text';
    const row = file as Record<string, unknown>;
    const path = row['path'],
      text = row['text'];
    if (
      typeof path !== 'string' ||
      path.length > 512 ||
      /[\r\n\u2028\u2029]/.test(path) ||
      path.split('/').length > 17 ||
      !/^[A-Za-z0-9_-][A-Za-z0-9_.-]*(?:\/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*$/.test(
        path,
      ) ||
      path
        .split('/')
        .some((segment) =>
          ['.', '..', 'node_modules', 'build', '__pycache__'].includes(segment),
        ) ||
      paths.has(path.toLowerCase())
    )
      return 'Application paths must be unique portable relative source paths';
    if (
      typeof text !== 'string' ||
      text.includes('\0') ||
      Array.from(text).some((char) => {
        const point = char.codePointAt(0)!;
        return point >= 0xd800 && point <= 0xdfff;
      }) ||
      utf8Length(text) > 65536
    )
      return 'Application files need UTF-8 text of at most 64 KiB without NUL';
    paths.add(path.toLowerCase());
    bytes += utf8Length(text);
  }
  if (
    bytes > 131072 ||
    !value.some(
      (file) =>
        typeof file === 'object' &&
        file !== null &&
        'path' in file &&
        file.path === 'plowshare.json',
    )
  )
    return 'Application package needs root plowshare.json and at most 128 KiB of text';
  for (const path of paths)
    for (const other of paths)
      if (other.startsWith(`${path}/`))
        return 'Application package has a file/directory collision';
  return undefined;
}
export function deploymentProblem(
  type: string,
  body: Record<string, unknown>,
): string | undefined {
  if (
    typeof body['project'] !== 'string' ||
    !body['project'].trim() ||
    body['project'] !== body['project'].trim() ||
    body['project'].length > 512
  )
    return 'Deployment needs an Application project';
  if (type === 'application.deployment.status') return undefined;
  if (!deploymentUuid(body['requestId']))
    return 'Retain a UUID requestId for receipt recovery';
  if (type === 'application.deployment.receipt') return undefined;
  if (
    !(type === 'application.deploy' && body['expectedRevision'] === null) &&
    !deploymentUuid(body['expectedRevision'])
  )
    return 'Use null for first deployment or the reviewed active revision UUID';
  if (type === 'application.activate')
    return deploymentUuid(body['revision'])
      ? undefined
      : 'Choose a retained revision UUID';
  const destination = body['destination'],
    areas = body['writableAreas'];
  if (
    !fileStoreReference(destination) ||
    destination.path === '' ||
    !isList(areas) ||
    areas.length > 100 ||
    !areas.every(fileStoreReference) ||
    new Set(areas.map((area) => `${area.store}\0${area.path}`)).size !==
      areas.length
  )
    return 'Use an explicit destination and distinct writableAreas';
  return applicationFilesProblem(body['files']);
}
const releaseCheck = record({
  revision: deploymentUuid,
  digest: (value) =>
    typeof value === 'string' &&
    value.length === 64 &&
    /^[a-f0-9]{64}$/.test(value),
  fileCount: (value) =>
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 1 &&
    value <= 128,
});
export const deploymentReceiptCheck = record({
  requestId: deploymentUuid,
  project: named,
  release: releaseCheck,
});
export const deploymentStatusCheck = record({
  project: named,
  activeRevision: nullable(deploymentUuid),
  releases: list(releaseCheck),
});

/** These semantic bounds supplement the generated structural response graph. */
export function validateDeploymentReply(type: string, value: unknown): void {
  if (
    [
      'application.deploy',
      'application.activate',
      'application.deployment.receipt',
    ].includes(type) &&
    !deploymentReceiptCheck(value)
  )
    throw new Error('Invalid Application deployment receipt');
  if (
    type === 'application.deployment.status' &&
    (!deploymentStatusCheck(value) ||
      typeof value !== 'object' ||
      value === null ||
      !('releases' in value) ||
      !Array.isArray(value.releases) ||
      value.releases.length > 100)
  )
    throw new Error('Invalid Application deployment status');
}
