import {
  NativeHostCommands,
  NativeMcpSessions,
  decodeNativeCommand,
  decodeNativeInvocation,
  readNativeConfig,
  nativeProviderConnection,
} from 'plowshare-mcp-integration';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { authenticateConfigured } from './platform.js';
import type { IO } from './run.js';
import type {
  NativeCommand,
  NativeInvocation,
} from 'plowshare-mcp-integration';

/** Explicit local foreground workflow. Direct calls never authenticate or publish to Plowshare.
 * Share is a separate action using the selected authenticated socket and server grants.
 */
export async function nativeMcp(
  parts: readonly string[],
  io: IO,
  signal: AbortSignal,
  sharingTimeoutMs: number,
): Promise<number> {
  const action = parts[2],
    file = parts[3];
  if (
    parts.length !== 4 ||
    file === undefined ||
    !['inspect', 'start', 'call', 'share'].includes(action ?? '')
  )
    throw new Error(
      'Use mcp local inspect|start|call|share /absolute/private/profile.json',
    );
  const config = await readNativeConfig(file);
  let sharing: NativeCommand | undefined;
  let invocation: NativeInvocation | undefined;
  if (action === 'call' || action === 'share') {
    const input = await io.stdin(signal);
    if (Buffer.byteLength(input) > 32768)
      throw new Error('Native MCP input exceeds the request envelope');
    const raw: unknown = JSON.parse(input);
    if (action === 'call') invocation = decodeNativeInvocation(raw);
    else {
      sharing = decodeNativeCommand(raw);
      if (
        sharing.action !== 'share' ||
        sharing.file !== file ||
        !sharing.consent ||
        sharing.scope.project !== config.project ||
        sharing.scope.prefix !== config.prefix
      )
        throw new Error(
          'Share requires a matching, consented policy from stdin',
        );
    }
  }

  const lifetime = new AbortController();
  const stop = () => lifetime.abort();
  signal.addEventListener('abort', stop, { once: true });
  if (signal.aborted) stop();
  const socket =
    action === 'share'
      ? await authenticateConfigured(
          config.owner.origin,
          io.env,
          lifetime.signal,
          () => undefined,
          { onClose: stop },
        )
      : undefined;
  if (socket !== undefined && socket.handle !== config.owner.account) {
    socket.close();
    throw new Error(
      'Native MCP profile belongs to another authenticated account',
    );
  }
  const commands = new NativeHostCommands(
    config.owner,
    new NativeMcpSessions(io.env),
    socket === undefined
      ? undefined
      : nativeProviderConnection(checkedTransport(socket), () =>
          socket.close(),
        ),
  );
  const emit = (value: unknown) => io.stdout(JSON.stringify(value) + '\n');
  try {
    if (action === 'inspect') {
      emit(await commands.execute({ action: 'inspect', file }));
      return 0;
    }
    if (action === 'share') {
      if (sharing === undefined)
        throw new Error('Missing validated sharing policy');
      emit({ status: 'serving', host: await commands.execute(sharing) });
      const timer = setTimeout(stop, sharingTimeoutMs);
      try {
        await new Promise<void>((resolve) => {
          if (lifetime.signal.aborted) resolve();
          else
            lifetime.signal.addEventListener('abort', () => resolve(), {
              once: true,
            });
        });
      } finally {
        clearTimeout(timer);
      }
      return 0;
    }
    const status = await commands.execute({
      action: 'start',
      file,
      consent: true,
    });
    if (action === 'call') {
      if (invocation === undefined)
        throw new Error('Missing validated native invocation');
      const outcome = await commands.execute({
        action: 'call',
        instance: config.instance,
        profile: config.profile,
        invocation,
      });
      emit(outcome);
      return 'state' in outcome && outcome.state === 'COMPLETED' ? 0 : 1;
    }
    emit({ status: 'serving', host: status });
    // NDJSON input belongs to this foreground process. EOF/signal ends availability.
    if (io.nativeInput !== undefined) {
      for await (const line of io.nativeInput(lifetime.signal)) {
        if (Buffer.byteLength(line) > 32768) {
          emit({
            status: 'refused',
            said: 'Native MCP input exceeds the request envelope',
          });
          continue;
        }
        try {
          const raw: unknown = JSON.parse(line);
          emit(
            await commands.execute({
              action: 'call',
              instance: config.instance,
              profile: config.profile,
              invocation: decodeNativeInvocation(raw),
            }),
          );
        } catch {
          emit({
            status: 'refused',
            said: 'Native MCP call was refused; inspect the original private receipt before retrying',
          });
        }
        if (
          !commands.hosts.host(config.instance, config.profile).status().active
        )
          break;
      }
    } else
      await new Promise<void>((resolve) => {
        if (lifetime.signal.aborted) resolve();
        else
          lifetime.signal.addEventListener('abort', () => resolve(), {
            once: true,
          });
      });
    return 0;
  } finally {
    signal.removeEventListener('abort', stop);
    try {
      await commands.close();
    } finally {
      socket?.close();
    }
  }
}
