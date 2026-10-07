import type { FileStoreReference } from 'plowshare-client-ts/binding/filestores';
import { errorMessage } from 'plowshare-client-ts/binding/values';
import { decodeReply } from 'plowshare-client-ts/operations/schema';
import {
  request,
  type Request as WsRequest,
} from 'plowshare-client-ts/operations/direct';
import type { CheckedAnswer } from 'plowshare-client-ts/operations/response';
import type { DesktopState } from './shared.ts';

/** Server source reads are independent of local file claims. A lost save reply requires a read. */
export class ApplicationFilesClient {
  private epoch = 0;
  private state: () => DesktopState;
  private send: (ask: WsRequest) => Promise<CheckedAnswer>;
  private emit: () => void;
  constructor(
    state: () => DesktopState,
    send: (ask: WsRequest) => Promise<CheckedAnswer>,
    emit: () => void,
  ) {
    this.state = state;
    this.send = send;
    this.emit = emit;
  }
  reset() {
    this.epoch++;
    delete this.state().applicationFiles;
  }
  private available(project: string) {
    if (
      !this.state().connected ||
      !this.state().projects.some(
        (row) => row.name === project && row.kind === 'application',
      )
    )
      throw new Error('Choose an available server Application.');
  }
  async list(project: string, path = '', location?: FileStoreReference) {
    this.available(project);
    if (this.state().applicationFiles?.saving)
      throw new Error('Wait for the current save before browsing.');
    const epoch = ++this.epoch;
    const value = {
      project,
      ...(location ? { location } : {}),
      loading: true,
      saving: false,
    };
    this.state().applicationFiles = value;
    this.emit();
    try {
      const answer = await this.send(
        request('application.files', {
          project,
          path,
          ...(location ? { location } : {}),
        }),
      );
      if (epoch !== this.epoch) return;
      this.available(project);
      if (answer.code !== 'OK')
        throw new Error(answer.said ?? 'The directory could not be read.');
      this.state().applicationFiles = {
        ...value,
        loading: false,
        listing: decodeReply('application.files', answer.payload),
      };
    } catch (reason) {
      if (epoch === this.epoch)
        this.state().applicationFiles = {
          ...value,
          loading: false,
          error: errorMessage(reason),
        };
    } finally {
      if (epoch === this.epoch) this.emit();
    }
  }
  async read(project: string, path: string, location?: FileStoreReference) {
    this.available(project);
    const previous = this.state().applicationFiles;
    if (previous?.saving)
      throw new Error('Wait for the current save before reading another file.');
    const epoch = ++this.epoch;
    const value = {
      project,
      ...(location ? { location } : {}),
      loading: true,
      saving: false,
      ...(previous?.project === project &&
      sameLocation(previous.location, location) &&
      previous.listing
        ? { listing: previous.listing }
        : {}),
    };
    this.state().applicationFiles = value;
    this.emit();
    try {
      const answer = await this.send(
        request('application.file.read', {
          project,
          path,
          ...(location ? { location } : {}),
        }),
      );
      if (epoch !== this.epoch) return;
      this.available(project);
      if (answer.code !== 'OK')
        throw new Error(answer.said ?? 'The file could not be read.');
      this.state().applicationFiles = {
        ...value,
        loading: false,
        document: decodeReply('application.file.read', answer.payload),
      };
    } catch (reason) {
      if (epoch === this.epoch)
        this.state().applicationFiles = {
          ...value,
          loading: false,
          error: errorMessage(reason),
        };
    } finally {
      if (epoch === this.epoch) this.emit();
    }
  }
  async save(
    project: string,
    path: string,
    text: string,
    revision: string,
    location?: FileStoreReference,
  ) {
    this.available(project);
    const value = this.state().applicationFiles;
    if (
      !value ||
      value.project !== project ||
      !sameLocation(value.location, location) ||
      value.loading ||
      value.saving ||
      value.uncertain ||
      !value.document?.writable ||
      value.document.path !== path ||
      value.document.revision !== revision
    )
      throw new Error(
        'Read the writable file and review its current revision before saving.',
      );
    const epoch = this.epoch;
    value.saving = true;
    delete value.error;
    this.emit();
    try {
      const answer = await this.send(
        request('application.file.save', {
          project,
          path,
          text,
          revision,
          ...(location ? { location } : {}),
        }),
      );
      if (epoch !== this.epoch) return;
      this.available(project);
      if (answer.code !== 'OK')
        throw new Error(answer.said ?? 'The save was refused.');
      value.document = decodeReply('application.file.save', answer.payload);
    } catch (reason) {
      if (epoch === this.epoch) {
        value.uncertain = true;
        value.error = `${errorMessage(reason)} Read the file before another save; this edit will not be replayed.`;
      }
    } finally {
      if (epoch === this.epoch) {
        value.saving = false;
        this.emit();
      }
    }
  }
}

function sameLocation(
  a: FileStoreReference | undefined,
  b: FileStoreReference | undefined,
) {
  return a?.store === b?.store && a?.path === b?.path;
}
