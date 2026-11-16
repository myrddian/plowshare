import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { test } from 'node:test';
import { createBrowserBackend } from './browser.ts';
import { OperationDeadline } from '../deadline.ts';

const executablePath = process.env['PLOWSHARE_COMPUTER_BROWSER_EXECUTABLE'];
await test(
  'explicit browser captures changing controlled canvas pixels',
  { skip: executablePath === undefined },
  async () => {
    if (executablePath === undefined)
      throw new Error('Explicit executable required');
    const server = createServer((_request, reply) => {
      reply.setHeader('Content-Type', 'text/html');
      reply.end(
        '<!doctype html><canvas width="320" height="160"></canvas><script>let n=0;const c=document.querySelector("canvas").getContext("2d");function draw(){c.fillStyle="white";c.fillRect(0,0,320,160);c.fillStyle="black";c.font="40px sans-serif";c.fillText("FRAME "+(++n),20,80)}draw();setInterval(draw,50)</script>',
      );
    });
    await new Promise<void>((resolve) =>
      server.listen(0, '127.0.0.1', resolve),
    );
    const address = server.address();
    assert.ok(address !== null && typeof address !== 'string');
    const deadline = new OperationDeadline(10_000);
    try {
      const handle = await createBrowserBackend(
        {
          executablePath,
          headless: true,
          targets: [
            {
              targetId: 'fixture',
              label: 'Controlled canvas',
              url: `http://127.0.0.1:${address.port}/`,
              viewport: { width: 320, height: 160 },
            },
          ],
        },
        deadline,
      );
      try {
        const first = await handle.backend.capture(
          { targetId: 'fixture', surfaceId: 'viewport' },
          deadline,
        );
        await new Promise((resolve) => setTimeout(resolve, 100));
        const second = await handle.backend.capture(
          { targetId: 'fixture', surfaceId: 'viewport' },
          deadline,
        );
        assert.equal(first.width, 320);
        assert.equal(first.height, 160);
        assert.equal(
          new DataView(first.bytes.buffer, first.bytes.byteOffset).getUint32(
            16,
          ),
          320,
        );
        assert.equal(
          new DataView(first.bytes.buffer, first.bytes.byteOffset).getUint32(
            20,
          ),
          160,
        );
        assert.notDeepEqual(first.bytes, second.bytes);
      } finally {
        const cleanup = new OperationDeadline(2000);
        try {
          await handle.close(cleanup);
        } finally {
          cleanup.dispose();
        }
      }
    } finally {
      deadline.dispose();
      await new Promise<void>((resolve, reject) =>
        server.close((error) =>
          error === undefined ? resolve() : reject(error),
        ),
      );
    }
  },
);
