import { isObject } from 'plowshare-client-ts/binding/values';

/** A declared live key. Pinned values are restored from deployment configuration on restart. */
export interface RuntimeSetting {
  readonly key: string;
  readonly value: string | null;
  readonly pinned: boolean;
  readonly updatedAt: string | null;
  readonly updatedBy: string | null;
}

/** Existing runtime-config HTTP boundary; writes are explicit and never retried. */
export interface RuntimeConfiguration {
  list(): Promise<readonly RuntimeSetting[]>;
  set(key: string, value: string): Promise<RuntimeSetting>;
}

export function configurationKey(value: string): string {
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,255}$/.test(value))
    throw new Error('Choose a valid live configuration key.');
  return value;
}

export function configurationValue(value: string): string {
  if (value.length > 65536 || value.includes('\0'))
    throw new Error(
      'Configuration values must be at most 65536 characters without NUL.',
    );
  return value;
}

/** Validate and project the HTTP response before it reaches a renderer or application contract. */
export function decodeRuntimeSetting(value: unknown): RuntimeSetting {
  if (
    !isObject(value) ||
    typeof value['key'] !== 'string' ||
    typeof value['pinned'] !== 'boolean'
  )
    throw new Error('Unreadable live configuration entry.');
  const optional = (name: string): string | null => {
    const field = value[name];
    if (field === undefined || field === null) return null;
    if (typeof field !== 'string')
      throw new Error('Unreadable live configuration field.');
    return configurationValue(field);
  };
  return {
    key: configurationKey(value['key']),
    pinned: value['pinned'],
    value: optional('value'),
    updatedAt: optional('updatedAt'),
    updatedBy: optional('updatedBy'),
  };
}

/** Credentials stay in the main process. Redirects and automatic HTTP mutation replay are forbidden. */
export class HttpRuntimeConfiguration implements RuntimeConfiguration {
  private readonly base: string;
  private readonly bearer: () => Promise<string>;
  private readonly http: (url: string, init: RequestInit) => Promise<Response>;
  constructor(
    base: string,
    bearer: () => Promise<string>,
    http: (url: string, init: RequestInit) => Promise<Response>,
  ) {
    const origin = new URL(base);
    if (
      !['http:', 'https:'].includes(origin.protocol) ||
      origin.username ||
      origin.password ||
      origin.search ||
      origin.hash ||
      origin.pathname !== '/'
    )
      throw new Error(
        'Configure an explicit HTTP or HTTPS server origin without credentials or a path.',
      );
    this.base = origin.origin;
    this.bearer = bearer;
    this.http = http;
  }

  private async request(path: string, body?: string): Promise<unknown> {
    const token = await this.bearer();
    const response = await this.http(this.base + path, {
      method: body === undefined ? 'GET' : 'PUT',
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body === undefined
          ? {}
          : { 'Content-Type': 'text/plain; charset=utf-8' }),
      },
      ...(body === undefined ? {} : { body }),
      redirect: 'error',
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok)
      throw new Error(
        `Live configuration request refused (${response.status}).${body === undefined ? '' : ' The write was not retried; reload to inspect its outcome.'}`,
      );
    return response.json() as Promise<unknown>;
  }

  async list(): Promise<readonly RuntimeSetting[]> {
    const value = await this.request('/v1/config');
    if (!Array.isArray(value) || value.length > 10000)
      throw new Error('Unreadable live configuration listing.');
    return value.map(decodeRuntimeSetting);
  }

  async set(key: string, value: string): Promise<RuntimeSetting> {
    configurationKey(key);
    configurationValue(value);
    let response: unknown;
    try {
      response = await this.request(
        `/v1/config/${encodeURIComponent(key)}`,
        value,
      );
    } catch (reason) {
      throw new Error(
        'Configuration write failed or is unconfirmed. It was not retried; reload the original server to inspect its outcome.',
        { cause: reason },
      );
    }
    const result = decodeRuntimeSetting(response);
    if (result.key !== key)
      throw new Error(
        'Configuration reply names a different key. Reload to inspect the write.',
      );
    return result;
  }
}
