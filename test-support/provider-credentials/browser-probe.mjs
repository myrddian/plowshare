/** Manual browser acceptance using the actual HTML/CSP exported by ProviderOAuthControllerTest. */
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
const [fixturePath, playwrightPath, executablePath] = process.argv.slice(2);
if (!fixturePath || !playwrightPath || !executablePath)
  throw new Error(
    'Provide callback.json, the Playwright module path and Chromium executable path.',
  );
const fixture = JSON.parse(await readFile(fixturePath, 'utf8'));
assert.equal(typeof fixture.html, 'string');
assert.equal(typeof fixture.csp, 'string');
const { chromium } = await import(pathToFileURL(playwrightPath).href);
let current;
const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://fixture.test');
  if (url.pathname === '/login') {
    response.setHeader(
      'Set-Cookie',
      'ps_access=fixture-login; SameSite=Strict; HttpOnly; Path=/',
    );
    response.end('Signed in for the fixture.');
  } else if (url.pathname === '/issuer') {
    response.setHeader('Content-Type', 'text/html');
    response.end('<a href="/authorize">Authorize</a>');
  } else if (url.pathname === '/authorize') {
    response.writeHead(302, { Location: current.redirect });
    response.end();
  } else if (url.pathname.endsWith('/provider-credentials/oauth/callback')) {
    current.landingCookie = request.headers.cookie;
    response.writeHead(200, {
      'Content-Type': 'text/html;charset=UTF-8',
      'Content-Security-Policy': fixture.csp,
      'Cache-Control': 'no-store',
      'Referrer-Policy': 'no-referrer',
    });
    response.end(fixture.html);
  } else if (url.pathname.endsWith('/v1/provider-credentials/oauth/complete')) {
    current.sends++;
    current.completionPath = url.pathname;
    current.completionCookie = request.headers.cookie;
    const chunks = [];
    for await (const part of request) chunks.push(part);
    current.body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    assert.deepEqual(current.body, {
      state: 'fixture-state',
      issuer: 'https://issuer.example',
      code: 'fixture-code',
    });
    if (current.sends > 1) {
      response.writeHead(409);
      response.end();
    } else if (current.lost) {
      current.effects++;
      request.socket.destroy();
    } else {
      if (current.status === 200) current.effects++;
      response.writeHead(current.status, {
        'Content-Type': 'application/json',
      });
      response.end('{}');
    }
  } else {
    response.writeHead(404);
    response.end();
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
const own = `http://plowshare.test:${port}`;
const foreign = `http://issuer.test:${port}`;
let browser;
try {
  browser = await chromium.launch({
    executablePath,
    headless: true,
    args: ['--no-proxy-server', '--host-resolver-rules=MAP *.test 127.0.0.1'],
  });
  for (const scenario of [
    {
      name: 'Strict-cookie cross-site redirect',
      prefix: '',
      status: 200,
      signedIn: true,
      ending: 'Authorization completed.',
    },
    {
      name: 'Servlet context path',
      prefix: '/nested',
      status: 200,
      signedIn: true,
      ending: 'Authorization completed.',
    },
    {
      name: 'Missing login',
      prefix: '',
      status: 401,
      signedIn: false,
      ending: 'Authorization completion was not confirmed.',
    },
    {
      name: 'Revoked grant',
      prefix: '',
      status: 400,
      signedIn: true,
      ending: 'Authorization completion was not confirmed.',
    },
    {
      name: 'Lost completion reply',
      prefix: '',
      status: 200,
      signedIn: true,
      lost: true,
      ending:
        /Authorization (delivery is uncertain|completion was not confirmed)/,
    },
    {
      name: 'Duplicate callback parameter',
      prefix: '',
      status: 200,
      signedIn: true,
      duplicate: true,
      ending: 'Authorization was not completed.',
    },
  ]) {
    current = {
      ...scenario,
      sends: 0,
      effects: 0,
      redirect: `${own}${scenario.prefix}/provider-credentials/oauth/callback?state=fixture-state&iss=https%3A%2F%2Fissuer.example&code=fixture-code${scenario.duplicate ? '&code=duplicate' : ''}`,
    };
    const context = await browser.newContext();
    const page = await context.newPage();
    if (scenario.signedIn) await page.goto(`${own}/login`);
    await page.goto(`${foreign}/issuer`);
    await page.getByRole('link', { name: 'Authorize', exact: true }).click();
    await page.getByText(scenario.ending, { exact: false }).waitFor();
    assert.equal(
      current.effects,
      scenario.status === 200 && !scenario.duplicate ? 1 : 0,
    );
    if (!scenario.lost) assert.equal(current.sends, scenario.duplicate ? 0 : 1);
    else
      assert.ok(
        current.sends >= 1 && current.sends <= 2,
        'The browser may transparently retry transport, never the retained exchange.',
      );
    assert.equal(
      current.landingCookie,
      undefined,
      'Strict login must be absent on the cross-site redirect.',
    );
    if (!scenario.duplicate) {
      assert.equal(
        current.completionCookie,
        scenario.signedIn ? 'ps_access=fixture-login' : undefined,
      );
      assert.equal(
        current.completionPath,
        `${scenario.prefix}/v1/provider-credentials/oauth/complete`,
      );
    }
    assert.equal(
      new URL(page.url()).search,
      '',
      'OAuth query must be removed from browser history.',
    );
    const visible = await page.locator('body').innerText();
    assert.ok(
      !visible.includes('fixture-code') && !visible.includes('fixture-state'),
    );
    console.log(`PASS ${scenario.name}`);
    await context.close();
  }
} finally {
  await browser?.close();
  await new Promise((resolve) => server.close(resolve));
}
