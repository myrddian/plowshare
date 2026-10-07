import { createHash, randomUUID } from 'node:crypto';
import type { ToolHost } from 'plowshare-client-ts';
export {
  ToolProvider,
  toolDeploymentConfig,
  validateToolArguments,
  decodeToolCall,
  toolResultRequestId,
} from 'plowshare-client-ts';
export type {
  ToolArguments,
  ToolBinding,
  ToolCall,
  ToolDeclaration,
  ToolHost,
  ToolJournal,
  ToolParameter,
  ToolReceipt,
  ToolResult,
  ToolScalar,
  ToolState,
  RegisteredTool,
} from 'plowshare-client-ts';

/** Node host facilities for the same platform-neutral provider used by TypeScript clients. */
export const nodeToolHost: ToolHost = {
  uuid: randomUUID,
  sha256(value) {
    return Promise.resolve(
      createHash('sha256').update(value, 'utf8').digest('hex'),
    );
  },
  now() {
    return new Date();
  },
  async execute(handler, call, deadline) {
    const remaining = deadline.getTime() - Date.now();
    if (remaining <= 0) throw new Error('Tool handler deadline expired');
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        handler(call),
        new Promise<never>((_resolve, reject) => {
          timer = setTimeout(() => {
            reject(
              new Error(
                'Tool handler deadline expired; external effects may have occurred',
              ),
            );
          }, remaining);
        }),
      ]);
    } finally {
      if (timer !== undefined) clearTimeout(timer);
    }
  },
};
