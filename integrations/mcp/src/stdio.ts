import { spawn } from 'node:child_process';
import type { ChildProcessWithoutNullStreams } from 'node:child_process';
import { access, stat } from 'node:fs/promises';
import { constants } from 'node:fs';
import { basename } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { Client } from '@modelcontextprotocol/client';
import { JSONRPCMessageSchema } from '@modelcontextprotocol/core';
import type { Transport } from '@modelcontextprotocol/client';
type JSONRPCMessage = Parameters<NonNullable<Transport['onmessage']>>[0];
import type { McpSession } from './contracts.ts';
import type { NativeMcpConfig } from './native-config.ts';
import { nativeEnvironment } from './native-config.ts';
import { decodeMcpPage, decodeMcpReply } from './codec.ts';

/** The official stdio transport merges an inherited environment and fixes its kill timers.
 * This narrow transport uses the same SDK client/protocol, with explicit environment, framing
 * and owned process-group shutdown. Stderr is drained privately and never enters protocol/logs.
 */
class OwnedStdio implements Transport {
  onclose?: () => void;
  onerror?: (error: Error) => void;
  onmessage?: (message: JSONRPCMessage) => void;
  readonly lifetime = new AbortController();
  private child: ChildProcessWithoutNullStreams | undefined;
  private buffered = Buffer.alloc(0);
  private closing: Promise<void> | undefined;
  private reportedCleanup: Promise<void> = Promise.resolve();
  constructor(
    private readonly config: NativeMcpConfig,
    private readonly environment: Readonly<Record<string, string>>,
    private readonly opening: AbortSignal,
  ) {}
  async start(): Promise<void> {
    const transport = this.config.transport;
    if (
      transport.kind !== 'stdio' ||
      this.child !== undefined ||
      this.lifetime.signal.aborted
    )
      throw new Error('Native MCP process is unavailable');
    await access(transport.executable, constants.X_OK);
    if (
      !(await stat(transport.executable)).isFile() ||
      !(await stat(transport.directory)).isDirectory()
    )
      throw new Error('Invalid native MCP executable or directory');
    if (
      process.platform === 'win32' &&
      (transport.treeTerminationExecutable === null ||
        basename(transport.treeTerminationExecutable).toLowerCase() !==
          'taskkill.exe')
    )
      throw new Error(
        'Windows STDIO requires an explicit taskkill.exe path for owned-tree shutdown',
      );
    if (
      process.platform === 'win32' &&
      transport.treeTerminationExecutable !== null
    ) {
      await access(transport.treeTerminationExecutable, constants.X_OK);
      if (!(await stat(transport.treeTerminationExecutable)).isFile())
        throw new Error('Invalid configured process-tree terminator');
    }
    this.opening.throwIfAborted();
    const child = spawn(transport.executable, [...transport.args], {
      cwd: transport.directory,
      env: { ...this.environment },
      shell: false,
      detached: process.platform !== 'win32',
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.child = child;
    child.stderr.resume();
    child.stdout.on('data', (chunk: Buffer) => this.receive(chunk));
    child.stdout.on('error', () => this.fail());
    child.stdin.on('error', () => this.fail());
    child.once('exit', () => {
      this.lifetime.abort();
      this.onclose?.();
    });
    child.on('error', () => this.fail());
    await new Promise<void>((resolve, reject) => {
      child.once('spawn', resolve);
      child.once('error', () =>
        reject(new Error('Native MCP process could not start')),
      );
    });
  }
  private receive(chunk: Buffer): void {
    try {
      // Enforce each complete message, plus the unfinished tail, before growing the buffer.
      let start = 0;
      for (
        let end = chunk.indexOf(10);
        end !== -1;
        end = chunk.indexOf(10, start)
      ) {
        const size = this.buffered.byteLength + end - start;
        if (size > this.config.limits.maxWireBytes)
          throw new Error('MCP frame exceeds operator budget');
        const line = Buffer.concat([this.buffered, chunk.subarray(start, end)]);
        this.buffered = Buffer.alloc(0);
        const raw: unknown = JSON.parse(
          new TextDecoder('utf-8', { fatal: true }).decode(line),
        );
        this.onmessage?.(JSONRPCMessageSchema.parse(raw));
        start = end + 1;
      }
      if (
        this.buffered.byteLength + chunk.byteLength - start >
        this.config.limits.maxWireBytes
      )
        throw new Error('MCP frame exceeds operator budget');
      this.buffered = Buffer.concat([this.buffered, chunk.subarray(start)]);
    } catch {
      this.fail();
    }
  }
  private fail(): void {
    this.onerror?.(new Error('Native MCP protocol transport failed'));
    // Retain the cleanup promise and report failure through the transport owner, never raw stderr.
    this.reportedCleanup = this.close().catch(() =>
      this.onerror?.(new Error('Native MCP process cleanup failed')),
    );
  }
  send(message: JSONRPCMessage): Promise<void> {
    const child = this.child;
    if (
      child === undefined ||
      this.lifetime.signal.aborted ||
      this.closing !== undefined
    )
      return Promise.reject(new Error('Native MCP process is closed'));
    const wire = JSON.stringify(message) + '\n';
    if (Buffer.byteLength(wire) - 1 > this.config.limits.maxWireBytes)
      return Promise.reject(new Error('MCP request exceeds operator budget'));
    return new Promise((resolve, reject) =>
      child.stdin.write(wire, (error) =>
        error
          ? reject(new Error('Native MCP request delivery is uncertain'))
          : resolve(),
      ),
    );
  }
  close(): Promise<void> {
    this.closing ??= this.terminate();
    return Promise.all([this.closing, this.reportedCleanup]).then(
      () => undefined,
    );
  }
  private async terminate(): Promise<void> {
    const child = this.child,
      transport = this.config.transport;
    this.lifetime.abort();
    if (
      child === undefined ||
      child.pid === undefined ||
      transport.kind !== 'stdio'
    )
      return;
    const pid = child.pid;
    const exited =
      child.exitCode !== null || child.signalCode !== null
        ? Promise.resolve()
        : new Promise<void>((resolve) => child.once('exit', () => resolve()));
    child.stdin.end();
    await Promise.race([exited, delay(this.config.shutdownGraceMs)]);
    try {
      if (process.platform === 'win32') {
        if (transport.treeTerminationExecutable === null)
          throw new Error('Missing native process-tree terminator');
        const executable = transport.treeTerminationExecutable;
        await new Promise<void>((resolve, reject) => {
          const terminator = spawn(
            executable,
            ['/PID', String(pid), '/T', '/F'],
            {
              shell: false,
              windowsHide: true,
              stdio: 'ignore',
              env: { ...this.environment },
              timeout: this.config.shutdownGraceMs,
            },
          );
          terminator.once('error', () =>
            reject(new Error('Owned MCP process-tree shutdown failed')),
          );
          terminator.once('exit', (code) =>
            code === 0
              ? resolve()
              : reject(
                  new Error('Owned MCP process-tree shutdown was not verified'),
                ),
          );
        });
      } else {
        await this.signalGroup(pid, 'SIGTERM');
        await Promise.race([exited, delay(this.config.shutdownGraceMs)]);
        // Descendants may still hold pipes even when the original parent has exited.
        await this.signalGroup(pid, 'SIGKILL');
      }
    } finally {
      child.stdin.destroy();
      child.stdout.destroy();
      child.stderr.destroy();
      this.onclose?.();
    }
  }
  private async signalGroup(
    pid: number,
    signal: NodeJS.Signals,
  ): Promise<void> {
    try {
      process.kill(-pid, signal);
    } catch (error) {
      if (error instanceof Error && 'code' in error && error.code === 'ESRCH')
        return;
      const exited =
        this.child !== undefined &&
        (this.child.exitCode !== null || this.child.signalCode !== null);
      if (
        exited &&
        error instanceof Error &&
        'code' in error &&
        error.code === 'EPERM'
      ) {
        // An exited leader's group may be temporarily unsignalable during reaping. One bounded
        // grace retry distinguishes that race from a persistent permission failure, which is visible.
        await delay(this.config.shutdownGraceMs);
        try {
          process.kill(-pid, signal);
          return;
        } catch (retryError) {
          if (
            retryError instanceof Error &&
            'code' in retryError &&
            retryError.code === 'ESRCH'
          )
            return;
          throw new Error('Owned MCP process-group shutdown failed', {
            cause: retryError,
          });
        }
      }
      throw new Error('Owned MCP process-group shutdown failed', {
        cause: error,
      });
    }
  }
}
export async function openStdio(
  config: NativeMcpConfig,
  environment: Readonly<Record<string, string | undefined>>,
  signal: AbortSignal,
): Promise<McpSession> {
  if (config.transport.kind !== 'stdio')
    throw new Error('Expected native STDIO configuration');
  const transport = new OwnedStdio(
    config,
    nativeEnvironment(config.transport.environment, environment),
    signal,
  );
  const client = new Client(config.clientInfo, {
    capabilities: {},
    enforceStrictCapabilities: true,
    versionNegotiation: {
      mode:
        config.protocol === '2026-07-28' ? { pin: config.protocol } : 'legacy',
      probe: { timeoutMs: config.timeoutMs, maxRetries: 0 },
    },
    inputRequired: { autoFulfill: false },
    listMaxPages: config.limits.maxPages,
  });
  try {
    signal.throwIfAborted();
    await client.connect(transport, { signal, timeout: config.timeoutMs });
    if (
      client.getNegotiatedProtocolVersion() !== config.protocol ||
      client.getServerCapabilities()?.tools === undefined
    )
      throw new Error('Unsupported native MCP profile');
  } catch {
    await transport.close();
    throw new Error('Native MCP session negotiation failed');
  }
  return {
    closedSignal: transport.lifetime.signal,
    async page(cursor, pageSignal) {
      return decodeMcpPage(
        await client.request(
          { method: 'tools/list', params: cursor === null ? {} : { cursor } },
          { signal: pageSignal, timeout: config.timeoutMs },
        ),
        config.limits,
      );
    },
    async call(tool, arguments_, callSignal) {
      return decodeMcpReply(
        await client.request(
          {
            method: 'tools/call',
            params: { name: tool.name, arguments: arguments_ },
          },
          { signal: callSignal, timeout: config.timeoutMs },
        ),
        config.limits,
      );
    },
    async close() {
      try {
        await client.close();
      } finally {
        await transport.close();
      }
    },
  };
}
