import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import type { Server } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { OperationDeadline } from '../deadline.ts';
import { owner } from '../contracts.test-support.ts';
import type {
  BrowserActionResult,
  BrowserElementTarget,
  BrowserObservation,
  BrowserState,
  BrowserStateGuard,
  PreparedBrowserAction,
} from '../browser-control.ts';
import { createBrowserControlBackend } from './browser-control-backend.ts';
import type { BrowserControlBackendHandle } from './browser-control-backend.ts';
import { awaitBrowserWork } from './browser-control-rpc.ts';

const executablePath = process.env['PLOWSHARE_COMPUTER_BROWSER_EXECUTABLE'];
async function listen(server: Server): Promise<string> {
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address !== null && typeof address !== 'string');
  return `http://127.0.0.1:${address.port}`;
}
async function stop(server: Server): Promise<void> {
  if (!server.listening) return;
  await bounded((deadline) => {
    const close = new Promise<void>((resolve, reject) =>
      server.close((error) =>
        error === undefined ? resolve() : reject(error),
      ),
    );
    server.closeAllConnections();
    return awaitBrowserWork(close, deadline);
  });
}
async function bounded<T>(
  operation: (deadline: OperationDeadline) => Promise<T>,
  milliseconds = 2000,
): Promise<T> {
  const deadline = new OperationDeadline(milliseconds);
  try {
    return await operation(deadline);
  } finally {
    deadline.dispose();
  }
}
function guard(state: BrowserState): BrowserStateGuard {
  return {
    surfaceId: state.surfaceId,
    documentId: state.documentId,
    layoutGeneration: state.layoutGeneration,
    focusGeneration: state.focusGeneration,
  };
}
function captured(result: BrowserActionResult): BrowserObservation {
  assert.equal(result.delivery.outcome, 'DISPATCHED');
  assert.equal(result.observation.state, 'CAPTURED');
  if (result.observation.state !== 'CAPTURED')
    throw new Error('Missing controlled fixture observation');
  return result.observation.value;
}
function element(
  observation: BrowserObservation,
  name: string,
  editable = false,
): BrowserElementTarget {
  const snapshot = observation.snapshot;
  assert.ok(snapshot !== null);
  const named = snapshot.nodes.filter(
    (node) => node.name === name && (!editable || node.editable),
  );
  const node = named.find((node) => node.role === 'button') ?? named[0];
  assert.ok(node !== undefined);
  return {
    kind: 'element',
    snapshotId: snapshot.snapshotId,
    elementId: node.elementId,
  };
}
const fixtureHtml = `<!doctype html><meta charset="utf-8"><title>Controlled fixture</title>
<style>body{margin:8px;font:16px sans-serif}button{margin:4px}#scroll{height:80px;overflow:auto;width:300px}#scroll div{height:800px}canvas{display:block;border:0}iframe{display:block;width:120px;height:40px}</style>
<form method="post" action="/submitted"><label>Editor<input name="value"></label><button>Submit</button></form>
<button onclick="document.querySelector('output').textContent=String(confirm('Fixture modal'))">Dialog</button>
<button onclick="window.open('/popup')">Popup</button>
<button onclick="confirm('Fixture modal');confirm('Fixture modal')">Two dialogs</button>
<button onclick="const w=window.open('/popup');setTimeout(()=>w.location='/bad1',0)">Popup race</button>
<button onclick="this.outerHTML='<button>Replace</button>'">Replace</button><output></output>
<label>Secret<input type="password" value="password-private-value"></label><input type="file" aria-label="File chooser">
<div style="display:none">hidden-private-value</div><div id="scroll" aria-label="Scroll area"><div>Scrollable fixture</div></div>
<canvas width="200" height="100" aria-label="Drawing"></canvas><iframe src="/child" title="Unsupported child"></iframe>
<div style="display:flex">
<div aria-label="File container" style="position:relative;width:150px;height:44px"><input type="file" style="position:absolute;inset:0;width:100%;height:100%" onclick="fetch('/sensitive-input')"></div>
<div aria-label="Password container" style="position:relative;width:150px;height:44px"><input type="password" value="nested-sensitive-value" style="position:absolute;inset:0;width:100%;height:100%" onclick="fetch('/sensitive-input')"></div>
<div aria-label="Download container" style="position:relative;width:150px;height:44px"><a href="/download-file" download style="display:block;width:100%;height:100%" onclick="fetch('/sensitive-input')">Download</a></div>
<div aria-label="Frame container" style="width:150px;height:44px" onclick="fetch('/frame-input')"><iframe src="/nested-child" title="Nested unsupported child"></iframe></div>
</div>
<section aria-label="Large group">${'<span style="display:block">Bounded child</span>'.repeat(250)}</section>
<script>const c=document.querySelector('canvas'),ctx=c.getContext('2d');let down=false,n=0;
ctx.fillStyle='white';ctx.fillRect(0,0,200,100);
c.onmousedown=e=>{down=true;n=0;ctx.beginPath();ctx.moveTo(e.offsetX,e.offsetY);fetch('/down')};
c.onmousemove=e=>{if(down){n++;ctx.lineTo(e.offsetX,e.offsetY);ctx.stroke()}};
c.onmouseup=()=>{down=false;fetch('/drag?points='+n)};</script>`;

await test(
  'isolated owned browser edits, verifies, quarantines popups and guards every redirect',
  { skip: executablePath === undefined },
  async () => {
    if (executablePath === undefined)
      throw new Error('Explicit browser executable required');
    let forbiddenHits = 0,
      dragPoints = 0,
      submitted = '';
    let downWake: (() => void) | undefined;
    const initialCookies: string[] = [];
    const hits = new Map<string, number>();
    const forbidden = createServer((_request, response) => {
      forbiddenHits++;
      response.end('Forbidden fixture');
    });
    let fixtureServer: Server | undefined,
      ownedBackend: BrowserControlBackendHandle | undefined,
      ownedDirectory: string | undefined;
    const failures: unknown[] = [];
    try {
      const forbiddenOrigin = await listen(forbidden);
      const server = createServer((request, response) => {
        const path = (request.url ?? '/').split('?')[0] ?? '/';
        hits.set(path, (hits.get(path) ?? 0) + 1);
        if (path === '/') {
          initialCookies.push(request.headers.cookie ?? '');
          response.setHeader('Set-Cookie', 'fixture_cookie=present');
        }
        if (path === '/down') {
          downWake?.();
          response.end('Recorded');
          return;
        }
        if (
          path === '/bad1' ||
          path === '/hop1' ||
          path === '/hop2' ||
          path === '/redirect'
        ) {
          const location =
            path === '/bad1'
              ? '/redirect'
              : path === '/redirect'
                ? `${forbiddenOrigin}/`
                : path === '/hop1'
                  ? '/hop2'
                  : '/terminal';
          response.writeHead(302, { Location: location });
          response.end();
          return;
        }
        if (path === '/drag') {
          dragPoints = Number(
            new URL(
              request.url ?? '/',
              'http://fixture.invalid',
            ).searchParams.get('points'),
          );
          response.end('Recorded');
          return;
        }
        if (path === '/submitted') {
          const chunks: Buffer[] = [];
          let size = 0;
          request.on('data', (chunk: Buffer) => {
            size += chunk.byteLength;
            if (size > 4096) request.destroy();
            else chunks.push(chunk);
          });
          request.on('end', () => {
            submitted = Buffer.concat(chunks).toString('utf8');
            response.setHeader('Content-Type', 'text/html');
            response.end('<!doctype html><p>Submitted</p>');
          });
          return;
        }
        if (path === '/noise') {
          response.setHeader('Content-Type', 'text/html');
          response.end(
            `<!doctype html><style>body{margin:0}button{position:absolute;top:10px;left:10px}</style><canvas width="800" height="700"></canvas><button onclick="paint()">Noise</button><script>function paint(){const c=document.querySelector('canvas').getContext('2d'),d=c.createImageData(800,700);for(let i=0;i<d.data.length;i+=32768)crypto.getRandomValues(d.data.subarray(i,i+32768));for(let i=3;i<d.data.length;i+=4)d.data[i]=255;c.putImageData(d,0,0);fetch('/noise-painted')}</script>`,
          );
          return;
        }
        if (path === '/truncated-focus') {
          response.setHeader('Content-Type', 'text/html');
          response.end(
            `<!doctype html><div>${'<span style="display:block">Earlier node</span>'.repeat(250)}</div><input autofocus aria-label="Omitted focused editor" value="unreported-value" style="position:fixed;top:20px;left:20px">`,
          );
          return;
        }
        response.setHeader('Content-Type', 'text/html');
        response.end(fixtureHtml);
      });
      fixtureServer = server;
      const origin = await listen(server);
      const directory = await mkdtemp(join(tmpdir(), 'computer-p2-live-'));
      ownedDirectory = directory;
      const handle = await bounded(
        (deadline) =>
          createBrowserControlBackend(
            {
              executablePath,
              headless: true,
              ownershipDirectory: join(directory, 'ownership'),
              targets: [
                {
                  targetId: 'fixture',
                  label: 'Owned fixture',
                  url: origin,
                  viewport: { width: 800, height: 700 },
                },
              ],
              control: {
                enabled: true,
                tools: [
                  'windows',
                  'focus',
                  'navigate',
                  'pointer',
                  'type',
                  'key',
                  'scroll',
                ],
                allowedOrigins: [origin],
              },
            },
            deadline,
          ),
        10000,
      );
      ownedBackend = handle;
      const control = handle.backend.browserControl;
      let serial = 0;
      const acquire = () =>
        bounded(
          (deadline) =>
            control.acquire(
              {
                targetId: 'fixture',
                owner,
                session: {
                  sessionId: `fixture-${++serial}`,
                  generation: 'fixture',
                },
              },
              deadline,
            ),
          5000,
        );
      let lease = await acquire();
      let observation = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: true },
          deadline,
        ),
      );
      assert.ok(observation.snapshot?.truncated);
      assert.equal(observation.snapshot?.kind, 'dom-semantic');
      assert.ok(
        !JSON.stringify(observation.snapshot).includes('hidden-private-value'),
      );
      assert.ok(
        !JSON.stringify(observation.snapshot).includes(
          'password-private-value',
        ),
      );
      assert.equal(hits.get('/child') ?? 0, 0);
      const beforeHash = guard(observation.state);
      observation = captured(
        await bounded((deadline) =>
          control.navigate(
            lease,
            {
              expected: beforeHash,
              url: `${origin}/#epoch`,
              observationReserveMs: 500,
            },
            deadline,
          ),
        ),
      );
      assert.notEqual(observation.state.documentId, beforeHash.documentId);
      const oldDocument = await bounded((deadline) =>
        control.perform(
          lease,
          {
            expected: beforeHash,
            action: {
              kind: 'key',
              expectedFocus: null,
              key: 'Enter',
              modifiers: [],
            },
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      assert.deepEqual(oldDocument.delivery, {
        outcome: 'NOT_DISPATCHED',
        physicalSettlement: 'settled',
        code: 'STALE_OBSERVATION',
      });
      const perform = (action: PreparedBrowserAction) =>
        bounded((deadline) =>
          control.perform(
            lease,
            {
              expected: guard(observation.state),
              action,
              observationReserveMs: 500,
            },
            deadline,
          ),
        );
      const click = (name: string, editable = false) =>
        perform({
          kind: 'click',
          target: element(observation, name, editable),
          button: 'left',
          modifiers: [],
        });
      const initialEditor = element(observation, 'Editor', true);
      observation = captured(await click('Editor', true));
      const mismatch = await perform({
        kind: 'key',
        expectedFocus: null,
        key: 'Enter',
        modifiers: [],
      });
      assert.deepEqual(mismatch.delivery, {
        outcome: 'NOT_DISPATCHED',
        physicalSettlement: 'settled',
        code: 'FOCUS_MISMATCH',
      });
      const stale = await perform({
        kind: 'type',
        target: initialEditor,
        mode: 'replace',
        text: 'Must not insert',
      });
      assert.deepEqual(stale.delivery, {
        outcome: 'NOT_DISPATCHED',
        physicalSettlement: 'settled',
        code: 'STALE_OBSERVATION',
      });
      observation = captured(
        await perform({
          kind: 'type',
          target: element(observation, 'Editor', true),
          mode: 'replace',
          text: 'Héllo 🌱',
        }),
      );
      observation = captured(
        await perform({
          kind: 'type',
          target: element(observation, 'Editor', true),
          mode: 'insert',
          text: '!',
        }),
      );
      const dialog = await bounded((deadline) =>
        control.perform(
          lease,
          {
            expected: guard(observation.state),
            action: {
              kind: 'click',
              target: element(observation, 'Dialog'),
              button: 'left',
              modifiers: [],
            },
            observationReserveMs: 500,
            dialogDecision: {
              expectedType: 'confirm',
              exactMessage: 'Fixture modal',
              decision: 'accept',
            },
          },
          deadline,
        ),
      );
      assert.equal(dialog.dialog?.decision, 'accept');
      observation = captured(dialog);
      const hiddenInput = await perform({
        kind: 'click',
        target: element(observation, 'File chooser'),
        button: 'left',
        modifiers: [],
      });
      assert.equal(hiddenInput.delivery.outcome, 'NOT_DISPATCHED');
      if (hiddenInput.delivery.outcome === 'NOT_DISPATCHED')
        assert.equal(hiddenInput.delivery.code, 'SENSITIVE_TARGET');
      const frame = observation.snapshot?.nodes.find(
        (node) => node.role === 'generic' && node.bounds?.width === 124,
      );
      assert.ok(frame?.bounds !== null && frame?.bounds !== undefined);
      const frameHit = await perform({
        kind: 'click',
        target: {
          kind: 'point',
          x: frame.bounds.x + 10,
          y: frame.bounds.y + 10,
        },
        button: 'left',
        modifiers: [],
      });
      assert.equal(frameHit.delivery.outcome, 'NOT_DISPATCHED');
      if (frameHit.delivery.outcome === 'NOT_DISPATCHED')
        assert.equal(frameHit.delivery.code, 'UNSUPPORTED_FRAME');
      for (const name of [
        'File container',
        'Password container',
        'Download container',
        'Frame container',
      ]) {
        const hit = await click(name);
        assert.equal(hit.delivery.outcome, 'NOT_DISPATCHED');
        if (hit.delivery.outcome === 'NOT_DISPATCHED')
          assert.equal(
            hit.delivery.code,
            name === 'Frame container'
              ? 'UNSUPPORTED_FRAME'
              : 'SENSITIVE_TARGET',
          );
      }
      assert.equal(hits.get('/sensitive-input') ?? 0, 0);
      assert.equal(hits.get('/frame-input') ?? 0, 0);
      assert.equal(hits.get('/nested-child') ?? 0, 0);
      const drawing = observation.snapshot?.nodes.find(
        (node) => node.role === 'canvas',
      );
      assert.ok(drawing?.bounds);
      const beforeDrawing = createHash('sha256')
        .update(observation.image.bytes)
        .digest('hex');
      observation = captured(
        await perform({
          kind: 'drag',
          button: 'left',
          modifiers: [],
          points: [
            { x: drawing.bounds.x + 10, y: drawing.bounds.y + 10, atMs: 0 },
            { x: drawing.bounds.x + 60, y: drawing.bounds.y + 40, atMs: 50 },
            { x: drawing.bounds.x + 100, y: drawing.bounds.y + 70, atMs: 100 },
          ],
        }),
      );
      assert.ok(dragPoints >= 2);
      assert.notEqual(
        createHash('sha256').update(observation.image.bytes).digest('hex'),
        beforeDrawing,
      );
      const scroll = await perform({
        kind: 'scroll',
        target: element(observation, 'Scroll area'),
        dx: 0,
        dy: 2,
        units: 'lines',
      });
      assert.equal(scroll.delivery.outcome, 'DISPATCHED');
      if (
        scroll.delivery.outcome === 'DISPATCHED' &&
        scroll.delivery.mechanism === 'wheel'
      )
        assert.equal(scroll.delivery.scroll.dy, 80);
      observation = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: true },
          deadline,
        ),
      );
      const subtreeRoot = element(observation, 'Large group');
      const subtree = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: true, root: subtreeRoot },
          deadline,
        ),
      );
      assert.equal(subtree.snapshot?.rootElementId, subtreeRoot.elementId);
      assert.ok(subtree.snapshot?.truncated);
      assert.ok((subtree.snapshot?.nodes.length ?? 0) <= 200);
      observation = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: true },
          deadline,
        ),
      );
      observation = captured(await click('Submit'));
      assert.equal(new URLSearchParams(submitted).get('value'), 'Héllo 🌱!');
      assert.equal(observation.state.url, `${origin}/submitted`);
      assert.equal(
        (await bounded((deadline) => control.release(lease, deadline)))
          .physicalSettlement,
        'settled',
      );
      lease = await acquire();
      observation = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: true },
          deadline,
        ),
      );
      const popupResult = await click('Popup');
      assert.equal(popupResult.delivery.outcome, 'DISPATCHED');
      await new Promise((resolve) => setTimeout(resolve, 150));
      let windows = await bounded((deadline) =>
        control.windows(lease, deadline),
      );
      const popup = windows.tabs.find(
        (tab) => tab.state.surfaceId !== 'viewport',
      );
      assert.ok(popup !== undefined);
      assert.equal(popup.state.readiness, 'quarantined');
      assert.ok(popup.state.guardInstalled);
      assert.equal(hits.get('/popup') ?? 0, 0);
      assert.equal(hits.get('/bad1') ?? 0, 0);
      assert.equal(forbiddenHits, 0);
      const premature = await bounded((deadline) =>
        control.perform(
          lease,
          {
            expected: guard(popup.state),
            action: {
              kind: 'key',
              expectedFocus: null,
              key: 'Enter',
              modifiers: [],
            },
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      assert.equal(premature.delivery.outcome, 'NOT_DISPATCHED');
      const focused = await bounded((deadline) =>
        control.focus(
          lease,
          {
            windowsId: windows.windowsId,
            surfaceId: popup.state.surfaceId,
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      assert.equal(focused.delivery.outcome, 'DISPATCHED');
      assert.equal(focused.observation.state, 'FAILED');
      windows = await bounded((deadline) => control.windows(lease, deadline));
      const focusedPopup = windows.tabs.find(
        (tab) => tab.state.surfaceId === popup.state.surfaceId,
      );
      assert.ok(focusedPopup);
      const admitted = await bounded((deadline) =>
        control.navigate(
          lease,
          {
            expected: guard(focusedPopup.state),
            url: `${origin}/hop1`,
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      observation = captured(admitted);
      assert.equal(observation.state.url, `${origin}/terminal`);
      assert.equal(hits.get('/hop1'), 1);
      assert.equal(hits.get('/hop2'), 1);
      const forbiddenResult = await bounded((deadline) =>
        control.navigate(
          lease,
          {
            expected: guard(observation.state),
            url: `${origin}/bad1`,
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      assert.equal(forbiddenResult.delivery.outcome, 'UNCERTAIN');
      assert.equal(forbiddenHits, 0);
      assert.equal(hits.get('/bad1'), 1);
      assert.equal(hits.get('/redirect'), 1);
      await assert.rejects(
        bounded((deadline) =>
          control.observe(
            lease,
            { surfaceId: 'viewport', structured: false },
            deadline,
          ),
        ),
        { code: 'FENCED' },
      );
      assert.equal(
        (await bounded((deadline) => control.release(lease, deadline)))
          .physicalSettlement,
        'settled',
      );
      lease = await acquire();
      observation = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: true },
          deadline,
        ),
      );
      const popupBefore = hits.get('/popup') ?? 0,
        badBefore = hits.get('/bad1') ?? 0;
      const raced = await click('Popup race');
      assert.ok(
        raced.delivery.outcome === 'DISPATCHED' ||
          raced.delivery.outcome === 'UNCERTAIN',
      );
      await new Promise((resolve) => setTimeout(resolve, 150));
      assert.equal(hits.get('/popup') ?? 0, popupBefore);
      assert.equal(hits.get('/bad1') ?? 0, badBefore);
      assert.equal(forbiddenHits, 0);
      assert.equal(
        (await bounded((deadline) => control.release(lease, deadline)))
          .physicalSettlement,
        'settled',
      );
      for (const modal of ['unexpected', 'mismatch', 'second']) {
        lease = await acquire();
        observation = await bounded((deadline) =>
          control.observe(
            lease,
            { surfaceId: 'viewport', structured: true },
            deadline,
          ),
        );
        const result = await bounded((deadline) =>
          control.perform(
            lease,
            {
              expected: guard(observation.state),
              action: {
                kind: 'click',
                target: element(
                  observation,
                  modal === 'second' ? 'Two dialogs' : 'Dialog',
                ),
                button: 'left',
                modifiers: ['Shift'],
              },
              observationReserveMs: 500,
              ...(modal !== 'unexpected'
                ? {
                    dialogDecision: {
                      expectedType: 'confirm' as const,
                      exactMessage:
                        modal === 'mismatch'
                          ? 'Different message'
                          : 'Fixture modal',
                      decision: 'accept' as const,
                    },
                  }
                : {}),
            },
            deadline,
          ),
        );
        assert.equal(result.delivery.outcome, 'UNCERTAIN');
        if (result.delivery.outcome === 'UNCERTAIN') {
          assert.equal(
            result.delivery.code,
            modal === 'mismatch' ? 'DIALOG_MISMATCH' : 'UNEXPECTED_DIALOG',
          );
          assert.equal(result.delivery.physicalSettlement, 'settled');
        }
        assert.equal(
          (await bounded((deadline) => control.release(lease, deadline)))
            .physicalSettlement,
          'settled',
        );
      }
      lease = await acquire();
      observation = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: true },
          deadline,
        ),
      );
      const canvas = observation.snapshot?.nodes.find(
        (node) => node.role === 'canvas',
      );
      assert.ok(canvas?.bounds);
      const canvasBounds = canvas.bounds;
      const down = new Promise<void>((resolve) => {
        downWake = resolve;
      });
      const downBefore = hits.get('/down') ?? 0;
      const dragging = bounded((deadline) =>
        control.perform(
          lease,
          {
            expected: guard(observation.state),
            observationReserveMs: 500,
            action: {
              kind: 'drag',
              button: 'left',
              modifiers: ['Shift'],
              points: [
                { x: canvasBounds.x + 10, y: canvasBounds.y + 10, atMs: 0 },
                {
                  x: canvasBounds.x + 100,
                  y: canvasBounds.y + 50,
                  atMs: 900,
                },
              ],
            },
          },
          deadline,
        ),
      );
      await bounded((deadline) => awaitBrowserWork(down, deadline));
      assert.equal(
        (await bounded((deadline) => control.release(lease, deadline)))
          .physicalSettlement,
        'settled',
      );
      const stopped = await dragging;
      assert.equal(stopped.delivery.outcome, 'UNCERTAIN');
      assert.equal(hits.get('/down'), downBefore + 1);
      assert.ok(
        initialCookies.length >= 5 &&
          initialCookies.every((value) => value === ''),
      );
      lease = await acquire();
      observation = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: true },
          deadline,
        ),
      );
      const white = await bounded((deadline) =>
        control.navigate(
          lease,
          {
            expected: guard(observation.state),
            url: `${origin}/noise`,
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      observation = captured(white);
      const noisy = await bounded((deadline) =>
        control.perform(
          lease,
          {
            expected: guard(observation.state),
            action: {
              kind: 'click',
              target: element(observation, 'Noise'),
              button: 'left',
              modifiers: [],
            },
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      assert.equal(noisy.delivery.outcome, 'DISPATCHED');
      assert.equal(hits.get('/noise-painted'), 1);
      assert.equal(noisy.observation.state, 'FAILED');
      const windowAfterNoise = await bounded((deadline) =>
        control.windows(lease, deadline),
      );
      const noisyTab = windowAfterNoise.tabs.find(
        (tab) => tab.state.surfaceId === 'viewport',
      );
      assert.ok(noisyTab);
      const omitted = await bounded((deadline) =>
        control.navigate(
          lease,
          {
            expected: guard(noisyTab.state),
            url: `${origin}/truncated-focus`,
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      const omittedObservation = captured(omitted);
      const focusedId = omittedObservation.state.focusedElementId;
      const omittedSnapshot = omittedObservation.snapshot;
      assert.ok(
        focusedId !== null &&
          omittedSnapshot !== null &&
          omittedSnapshot.truncated,
      );
      assert.ok(
        !omittedSnapshot.nodes.some((node) => node.elementId === focusedId),
      );
      const invented = await bounded((deadline) =>
        control.perform(
          lease,
          {
            expected: guard(omittedObservation.state),
            action: {
              kind: 'key',
              key: 'Enter',
              modifiers: [],
              expectedFocus: {
                kind: 'element',
                snapshotId: omittedSnapshot.snapshotId,
                elementId: focusedId,
              },
            },
            observationReserveMs: 500,
          },
          deadline,
        ),
      );
      assert.deepEqual(invented.delivery, {
        outcome: 'NOT_DISPATCHED',
        physicalSettlement: 'settled',
        code: 'STALE_OBSERVATION',
      });
      const pixels = await bounded((deadline) =>
        control.observe(
          lease,
          { surfaceId: 'viewport', structured: false },
          deadline,
        ),
      );
      assert.equal(pixels.snapshot, null);
      assert.equal(pixels.state.focusedElementId, focusedId);
      const omittedWindows = await bounded((deadline) =>
        control.windows(lease, deadline),
      );
      assert.equal(omittedWindows.tabs[0]?.state.focusedElementId, focusedId);
      assert.equal(
        (await bounded((deadline) => control.release(lease, deadline)))
          .physicalSettlement,
        'settled',
      );
    } catch (error) {
      failures.push(error);
    } finally {
      const cleanup: Promise<void>[] = [];
      let physicallyClosed = ownedBackend === undefined;
      if (ownedBackend !== undefined) {
        const handle = ownedBackend;
        cleanup.push(
          bounded((deadline) => handle.close(deadline), 5000).then(() => {
            physicallyClosed = true;
          }),
        );
      }
      if (fixtureServer !== undefined) cleanup.push(stop(fixtureServer));
      cleanup.push(stop(forbidden));
      const results = await Promise.allSettled(cleanup);
      // Preserve durable evidence when physical browser cleanup is uncertain.
      if (ownedDirectory !== undefined && physicallyClosed) {
        try {
          await rm(ownedDirectory, { recursive: true, force: true });
        } catch (error) {
          failures.push(error);
        }
      }
      failures.push(
        ...results
          .filter((result) => result.status === 'rejected')
          .map((result): unknown => result.reason),
      );
    }
    if (failures.length > 0)
      throw new AggregateError(failures, 'Controlled browser fixture failed');
  },
);
