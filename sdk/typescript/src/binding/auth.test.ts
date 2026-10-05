import { isList } from './values.ts';
import { describe, expect, it } from 'vitest';

/** The id every door in this file listens under. Named rather than random so
 *  an assertion can read it back off the socket URL. */
const LISTENING_AS = 'a-listening-session';

import {
  MustChangePassword,
  SignInRefused,
  openFiles,
  openSocket,
  openServiceSocket,
  refresh,
  signIn,
  standing,
  ticket,
} from './auth.ts';
import type { Answer, Door, Sent, Tokens } from './auth.ts';
import type { Arrival, Socket } from './connection.ts';
import { CURRENT_VERSION } from './envelope.ts';

/**
 * The door, driven against a fake `fetch` — the same bargain `connection.test.ts`
 * makes with the socket, for the same reason.
 *
 * <b>What is asserted here is mostly order and shape rather than outcome.</b> A
 * test that only checks `openSocket` returns a connection passes for a client
 * that tickets first and refreshes when that fails, which is the loop this task
 * exists to prevent. So {@link calls} records every request in order and the
 * assertions read that record.
 */

/** One request the code under test made, flattened for assertion. */
interface Call {
  readonly path: string;
  readonly method: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: unknown;
}

/** What a scripted endpoint answers with. */
interface Scripted {
  readonly status: number;
  readonly body?: unknown;
  readonly headers?: Readonly<Record<string, string>>;
  readonly cookies?: readonly string[];
}

/** The base every test uses; nothing depends on the port. */
const BASE = 'http://127.0.0.1:8080';

/**
 * A door whose `fetch` answers from a script and records what it was asked.
 *
 * The script is keyed by path and may hold a queue, so that "the second refresh
 * rotates again" is expressible — which is what a reconnect does.
 */
function doorway(script: Readonly<Record<string, Scripted | Scripted[]>>): {
  door: Door;
  calls: Call[];
} {
  const calls: Call[] = [];
  const queues = new Map<string, Scripted[]>();
  for (const [path, answer] of Object.entries(script)) {
    queues.set(path, isList(answer) ? [...answer] : [answer]);
  }
  const fetching = (url: string, sent: Sent): Promise<Answer> => {
    // The origin off the front, rather than `BASE.length` off it: one test
    // hands the same script a different base to prove the socket scheme
    // follows it.
    const path = url.replace(/^https?:\/\/[^/]+/, '');
    calls.push({
      path,
      method: sent.method,
      headers: sent.headers,
      body: sent.body === undefined ? undefined : JSON.parse(sent.body),
    });
    const queued = queues.get(path) ?? [];
    const answer = queued.length > 1 ? queued.shift() : queued[0];
    if (answer === undefined) {
      throw new Error(`nothing scripted for ${path}`);
    }
    return Promise.resolve(answering(answer));
  };
  return { door: { base: BASE, fetch: fetching }, calls };
}

function answering(scripted: Scripted): Answer {
  const headers = scripted.headers ?? {};
  return {
    status: scripted.status,
    headers: {
      get: (name: string) => headers[name.toLowerCase()] ?? null,
      getSetCookie: () => [...(scripted.cookies ?? [])],
    },
    json: () => Promise.resolve(scripted.body),
  };
}

/** The `Set-Cookie` a successful refresh writes, in the server's own shape. */
function issued(access: string, refreshing: string): string[] {
  return [
    `ps_access=${access}; Path=/v1; Max-Age=900; HttpOnly; SameSite=Strict`,
    `ps_refresh=${refreshing}; Path=/v1/auth; Max-Age=1209600; HttpOnly; SameSite=Strict`,
  ];
}

/** Four members, because `Socket` is four members. */
class FakeSocket implements Socket {
  readonly sent: string[] = [];
  closed = false;
  private messaged: ((event: Arrival) => void) | undefined;

  send(frame: string): void {
    this.sent.push(frame);
    const asked = JSON.parse(frame) as { id: string; type: string };
    this.messaged?.({
      data: JSON.stringify({
        id: asked.id,
        type: asked.type,
        protocol_version: CURRENT_VERSION,
        payload: { code: 'OK' },
      }),
    });
  }

  close(): void {
    this.closed = true;
  }

  /** A bare `JobEvent`, which is what `/v1/events` publishes: no envelope. */
  deliver(push: unknown): void {
    this.messaged?.({ data: JSON.stringify(push) });
  }

  addEventListener(type: 'message', listener: (event: Arrival) => void): void;
  addEventListener(type: 'close', listener: (event: unknown) => void): void;
  addEventListener(type: string, listener: (event: Arrival) => void): void {
    if (type === 'message') {
      this.messaged = listener;
    }
  }
}

/** An opener that records the URLs it was handed and the fakes it handed back. */
function openings(): {
  open: (url: string) => Promise<Socket>;
  urls: string[];
  sockets: FakeSocket[];
} {
  const urls: string[] = [];
  const sockets: FakeSocket[] = [];
  return {
    urls,
    sockets,
    open: (url: string) => {
      urls.push(url);
      const socket = new FakeSocket();
      sockets.push(socket);
      return Promise.resolve(socket);
    },
  };
}

/** A live pair, the ordinary case. */
const PAIR: Tokens = { access: 'access-1', refresh: 'refresh-1' };

/** What a flagged admin holds: an access token and nothing to renew it with. */
const FLAGGED: Tokens = { access: 'access-flagged' };

describe('signing in asks for the pair in the body, because there is no cookie jar', () => {
  it('sends the non-safelisted header the server invented for exactly this', async () => {
    const { door, calls } = doorway({
      '/v1/auth/login': {
        status: 200,
        body: { access: 'a', refresh: 'r', mustChangePassword: false },
      },
    });
    await signIn(door, 'admin', 'hunter2');
    expect(calls).toHaveLength(1);
    expect(calls[0]?.path).toBe('/v1/auth/login');
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.headers['X-Plowshare-Token-Delivery']).toBe('body');
    expect(calls[0]?.headers['Content-Type']).toBe('application/json');
    expect(calls[0]?.body).toEqual({ handle: 'admin', password: 'hunter2' });
  });

  it('returns the pair the body carried', async () => {
    const { door } = doorway({
      '/v1/auth/login': {
        status: 200,
        body: { access: 'a-1', refresh: 'r-1', mustChangePassword: false },
      },
    });
    const session = await signIn(door, 'admin', 'hunter2');
    expect(session.tokens).toEqual({ access: 'a-1', refresh: 'r-1' });
    expect(session.mustChangePassword).toBe(false);
  });

  it('never puts the password anywhere but the body', async () => {
    const { door, calls } = doorway({
      '/v1/auth/login': {
        status: 200,
        body: { access: 'a', refresh: 'r', mustChangePassword: false },
      },
    });
    await signIn(door, 'admin', 'hunter2');
    expect(calls[0]?.path).toBe('/v1/auth/login');
    expect(JSON.stringify(calls[0]?.headers)).not.toContain('hunter2');
  });
});

describe('a refusal says only that sign-in failed', () => {
  /**
   * The server answers an unknown handle and a wrong password identically —
   * same status, same empty body, and the same Argon2id cost — so that
   * neither confirms which handles exist. A client that turned one of them
   * into "no such user" would undo that property from this side, which is
   * the only side a person ever reads.
   */
  it('says the same thing for an unknown handle as for a wrong password', async () => {
    const unknown = doorway({ '/v1/auth/login': { status: 401 } });
    const wrong = doorway({ '/v1/auth/login': { status: 401 } });
    const first = await signIn(unknown.door, 'nobody', 'whatever').catch(
      (no: unknown) => no,
    );
    const second = await signIn(wrong.door, 'admin', 'wrong').catch(
      (no: unknown) => no,
    );
    expect(first).toBeInstanceOf(SignInRefused);
    expect(second).toBeInstanceOf(SignInRefused);
    expect((first as Error).message).toBe((second as Error).message);
  });

  it('does not name an account or accuse either half, but does say where to look', async () => {
    const { door } = doorway({ '/v1/auth/login': { status: 401 } });
    const refused = await signIn(door, 'admin', 'wrong').catch(
      (no: unknown) => no,
    );
    const said = (refused as Error).message;

    // Leak-SHAPED language, which is the thing that would undo the
    // server's property. This used to also forbid the bare words
    // "handle" and "password", which was a proxy: it caught a message
    // that accused one of them AND a message that only named the two
    // environment variables to check. The structural property -- that
    // the sentence is identical either way -- is asserted by the test
    // above this one, which is where it belongs, because it compares
    // two real refusals instead of pattern-matching one.
    expect(said).not.toMatch(
      /no such|not found|does not exist|unknown|incorrect/i,
    );

    // Never anything derived from what was typed.
    expect(said).not.toContain('admin');

    // <b>And it must stay useful.</b> Running this against a real
    // server printed "sign-in failed" and nothing else -- true, and
    // leaving a person with nowhere to look, one branch away from the
    // unreachable case that names the address it tried. Naming both
    // variables narrows nothing: the sentence is the same whichever
    // was wrong.
    expect(said).toContain('PLOWSHARE_HANDLE');
    expect(said).toContain('PLOWSHARE_PASSWORD');
  });

  it('is the same refusal when the throttle is what refused, since 401 is all there is', async () => {
    const { door } = doorway({ '/v1/auth/login': { status: 401 } });
    await expect(signIn(door, 'admin', 'hunter2')).rejects.toBeInstanceOf(
      SignInRefused,
    );
  });
});

describe('a flagged admin has no refresh token at all', () => {
  it('keeps refresh absent rather than present and null', async () => {
    const { door } = doorway({
      '/v1/auth/login': {
        status: 200,
        body: { access: 'a-1', refresh: null, mustChangePassword: true },
      },
    });
    const session = await signIn(door, 'admin', 'seeded');
    expect(session.mustChangePassword).toBe(true);
    expect('refresh' in session.tokens).toBe(false);
    expect(session.tokens).toEqual({ access: 'a-1' });
  });

  it('refuses to renew such a session without asking the server anything', async () => {
    const { door, calls } = doorway({});
    await expect(refresh(door, FLAGGED)).rejects.toBeInstanceOf(
      MustChangePassword,
    );
    expect(calls).toEqual([]);
  });

  it('refuses to open a socket for it, before any request goes out', async () => {
    const { door, calls } = doorway({});
    const { open, urls } = openings();
    await expect(
      openSocket({ ...door, open, session: LISTENING_AS }, FLAGGED),
    ).rejects.toBeInstanceOf(MustChangePassword);
    expect(calls).toEqual([]);
    expect(urls).toEqual([]);
  });

  it('says to change the password rather than reporting a refusal', async () => {
    const { door } = doorway({});
    const refused = await refresh(door, FLAGGED).catch((no: unknown) => no);
    expect((refused as Error).message).toMatch(/password/i);
  });

  it('reads a 403 from the ticket endpoint as the same thing', async () => {
    // `AuthController.ticket` answers 403 for a restricted chain and for
    // nothing else, so it needs no second probe to be read correctly.
    const { door } = doorway({ '/v1/auth/ticket': { status: 403 } });
    await expect(ticket(door, PAIR)).rejects.toBeInstanceOf(MustChangePassword);
  });
});

describe('refreshing speaks cookies, because that endpoint never learned anything else', () => {
  it('presents the refresh token as the cookie the server reads', async () => {
    const { door, calls } = doorway({
      '/v1/auth/refresh': {
        status: 204,
        cookies: issued('access-2', 'refresh-2'),
      },
    });
    await refresh(door, PAIR);
    expect(calls[0]?.path).toBe('/v1/auth/refresh');
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.headers['Cookie']).toBe('ps_refresh=refresh-1');
  });

  it('reads the rotated pair out of Set-Cookie, since the body carries none', async () => {
    const { door } = doorway({
      '/v1/auth/refresh': {
        status: 204,
        cookies: issued('access-2', 'refresh-2'),
      },
    });
    expect(await refresh(door, PAIR)).toEqual({
      access: 'access-2',
      refresh: 'refresh-2',
    });
  });

  it('fails plainly when the refresh token is refused, which is sign-in again', async () => {
    const { door } = doorway({ '/v1/auth/refresh': { status: 401 } });
    const over = await refresh(door, PAIR).catch((no: unknown) => no);
    expect(over).toBeInstanceOf(Error);
    expect(over).not.toBeInstanceOf(MustChangePassword);
    expect((over as Error).message).toMatch(/sign in/i);
  });
});

describe('a ticket is minted against a live access token', () => {
  it('presents the access token as a bearer, which suppresses any cookie', async () => {
    const { door, calls } = doorway({
      '/v1/auth/ticket': { status: 200, body: { ticket: 't-1' } },
    });
    expect(await ticket(door, PAIR)).toBe('t-1');
    expect(calls[0]?.headers['Authorization']).toBe('Bearer access-1');
  });
});

describe('openSocket refreshes before it tickets, and that is the whole point', () => {
  function ready(): ReturnType<typeof doorway> {
    return doorway({
      '/v1/auth/refresh': {
        status: 204,
        cookies: issued('access-2', 'refresh-2'),
      },
      '/v1/auth/ticket': [
        { status: 200, body: { ticket: 't-1' } },
        { status: 200, body: { ticket: 't-2' } },
      ],
    });
  }

  it('keeps refresh rotation when a project event socket fails after authentication', async () => {
    const { door } = ready();
    const kept: Tokens[] = [];
    const refusing = (): Promise<Socket> =>
      Promise.reject(new Error('could not open'));
    await expect(
      openSocket(
        { ...door, open: refusing, session: LISTENING_AS },
        PAIR,
        (pair) => kept.push(pair),
      ),
    ).rejects.toThrow('could not open');
    expect(kept).toEqual([{ access: 'access-2', refresh: 'refresh-2' }]);
  });

  it('asks the two endpoints in that order, not the other one', async () => {
    const { door, calls } = ready();
    const { open } = openings();
    await openSocket({ ...door, open, session: LISTENING_AS }, PAIR);
    // The order, not the outcome. A client that tickets first and refreshes
    // when that fails also ends up with a socket, and loops on the day the
    // access token is stale — which is every reconnect after an idle
    // period, and the one thing this function exists to own.
    expect(calls.map((call) => call.path)).toEqual([
      '/v1/auth/refresh',
      '/v1/auth/ticket',
    ]);
  });

  it('tickets with the rotated access token rather than the stale one', async () => {
    const { door, calls } = ready();
    const { open } = openings();
    await openSocket({ ...door, open, session: LISTENING_AS }, PAIR);
    expect(calls[1]?.headers['Authorization']).toBe('Bearer access-2');
  });

  it('opens the events socket with the ticket it just minted', async () => {
    const { door } = ready();
    const { open, urls } = openings();
    await openSocket({ ...door, open, session: LISTENING_AS }, PAIR);
    expect(urls).toEqual([
      'ws://127.0.0.1:8080/v1/events?ticket=t-1&session=a-listening-session',
    ]);
  });

  it('speaks wss when the door is https', async () => {
    const { door } = ready();
    const { open, urls } = openings();
    await openSocket(
      {
        ...door,
        base: 'https://plowshare.example',
        open,
        session: LISTENING_AS,
      },
      PAIR,
    );
    expect(urls[0]).toBe(
      'wss://plowshare.example/v1/events?ticket=t-1&session=a-listening-session',
    );
  });

  it('refuses a base that is not an http origin, before spending a request', async () => {
    const { door, calls } = ready();
    const { open } = openings();
    await expect(
      openSocket(
        { ...door, base: 'ws://127.0.0.1:8080', open, session: LISTENING_AS },
        PAIR,
      ),
    ).rejects.toThrow(/http:\/\/ or https:\/\//);
    expect(calls).toEqual([]);
  });

  it('escapes the ticket rather than trusting it to be URL-safe', async () => {
    const { door } = doorway({
      '/v1/auth/refresh': {
        status: 204,
        cookies: issued('access-2', 'refresh-2'),
      },
      '/v1/auth/ticket': { status: 200, body: { ticket: 'a+b/c=' } },
    });
    const { open, urls } = openings();
    await openSocket({ ...door, open, session: LISTENING_AS }, PAIR);
    expect(urls[0]).toBe(
      'ws://127.0.0.1:8080/v1/events?ticket=a%2Bb%2Fc%3D&session=a-listening-session',
    );
  });

  it('hands back the rotated pair, so a reconnect does not present a spent one', async () => {
    const { door, calls } = doorway({
      '/v1/auth/refresh': [
        { status: 204, cookies: issued('access-2', 'refresh-2') },
        { status: 204, cookies: issued('access-3', 'refresh-3') },
      ],
      '/v1/auth/ticket': [
        { status: 200, body: { ticket: 't-1' } },
        { status: 200, body: { ticket: 't-2' } },
      ],
    });
    const { open, urls } = openings();
    const first = await openSocket(
      { ...door, open, session: LISTENING_AS },
      PAIR,
    );
    expect(first.tokens).toEqual({ access: 'access-2', refresh: 'refresh-2' });
    const second = await openSocket(
      { ...door, open, session: LISTENING_AS },
      first.tokens,
    );
    // The second refresh presents what the first one rotated to. Holding
    // the original would present a retired token, which this server's
    // reuse detection reads as theft and answers by killing the chain.
    expect(calls[2]?.headers['Cookie']).toBe('ps_refresh=refresh-2');
    expect(second.tokens).toEqual({ access: 'access-3', refresh: 'refresh-3' });
    // And a ticket is single-use, so the second connection gets its own.
    expect(urls).toEqual([
      'ws://127.0.0.1:8080/v1/events?ticket=t-1&session=a-listening-session',
      'ws://127.0.0.1:8080/v1/events?ticket=t-2&session=a-listening-session',
    ]);
  });

  it('hands back a connection that speaks frames on the socket it opened', async () => {
    const { door } = ready();
    const { open } = openings();
    const { connection } = await openSocket(
      { ...door, open, session: LISTENING_AS },
      PAIR,
    );
    expect(await connection.ask('conversation.list')).toEqual({ code: 'OK' });
  });

  it('wires the push listener through to the socket it opened', async () => {
    const { door } = ready();
    const { open, sockets } = openings();
    const pushes: unknown[] = [];
    await openSocket(
      {
        ...door,
        open,
        session: LISTENING_AS,
        onPush: (push) => {
          pushes.push(push);
        },
      },
      PAIR,
    );
    sockets[0]?.deliver({ job: 'j-1', kind: 'started' });
    expect(pushes).toEqual([{ job: 'j-1', kind: 'started' }]);
  });
});

describe('openFiles is openSocket for the other socket, with a claim on it', () => {
  const CLAIM = {
    project: 'ledger',
    machine: 'bench.local',
    root: '/Users/someone/research & notes',
  };

  function ready(): ReturnType<typeof doorway> {
    return doorway({
      '/v1/auth/refresh': {
        status: 204,
        cookies: issued('access-2', 'refresh-2'),
      },
      '/v1/auth/ticket': { status: 200, body: { ticket: 't-1' } },
    });
  }

  it('refreshes, tickets, and opens /v1/files with every part of the claim escaped', async () => {
    const { door, calls } = ready();
    const { open, urls } = openings();
    const opened = await openFiles(
      { ...door, open, session: LISTENING_AS },
      PAIR,
      CLAIM,
    );

    expect(calls.map((call) => call.path)).toEqual([
      '/v1/auth/refresh',
      '/v1/auth/ticket',
    ]);
    expect(urls).toEqual([
      'ws://127.0.0.1:8080/v1/files?ticket=t-1&session=a-listening-session' +
        '&project=ledger&machine=bench.local' +
        '&root=%2FUsers%2Fsomeone%2Fresearch%20%26%20notes&ready=1&source=1',
    ]);
    // THE ROTATED PAIR COMES BACK, because the one passed in is now retired.
    expect(opened.tokens.refresh).toBe('refresh-2');
  });

  it('hands over the rotated pair even when the open after the refresh fails', async () => {
    const { door } = ready();
    const kept: Tokens[] = [];
    const refusing = (): Promise<Socket> =>
      Promise.reject(new Error('could not open'));
    await expect(
      openFiles(
        { ...door, open: refusing, session: LISTENING_AS },
        PAIR,
        CLAIM,
        (pair) => kept.push(pair),
      ),
    ).rejects.toThrow('could not open');

    expect(kept).toEqual([{ access: 'access-2', refresh: 'refresh-2' }]);
  });

  it('refuses before the wire for a session that cannot refresh', async () => {
    const { door, calls } = ready();
    const { open } = openings();
    await expect(
      openFiles(
        { ...door, open, session: LISTENING_AS },
        { access: 'a' },
        CLAIM,
      ),
    ).rejects.toThrow(MustChangePassword);
    expect(calls).toEqual([]);
  });
});

describe('the session endpoint answers both of the questions a client has', () => {
  it('reads the must-change header off a live session', async () => {
    const { door, calls } = doorway({
      '/v1/auth/session': {
        status: 204,
        headers: { 'x-plowshare-must-change-password': 'true' },
      },
    });
    expect(await standing(door, PAIR)).toEqual({
      signedIn: true,
      mustChangePassword: true,
    });
    expect(calls[0]?.method).toBe('GET');
    expect(calls[0]?.headers['Authorization']).toBe('Bearer access-1');
  });

  it('reads false as false rather than as absence', async () => {
    const { door } = doorway({
      '/v1/auth/session': {
        status: 204,
        headers: { 'x-plowshare-must-change-password': 'false' },
      },
    });
    expect(await standing(door, PAIR)).toEqual({
      signedIn: true,
      mustChangePassword: false,
    });
  });

  it('reads a 401 as no session at all, which is the filter answering', async () => {
    const { door } = doorway({ '/v1/auth/session': { status: 401 } });
    expect(await standing(door, PAIR)).toEqual({
      signedIn: false,
      mustChangePassword: false,
    });
  });
});

describe('service credential sockets', () => {
  it('tickets the bearer directly without login, refresh, persistence or password setup', async () => {
    const { door, calls } = doorway({
      '/v1/auth/ticket': { status: 200, body: { ticket: 'machine-ticket' } },
    });
    let url = '';
    const opened = await openServiceSocket(
      {
        ...door,
        session: LISTENING_AS,
        open: async (value) => {
          url = value;
          return new FakeSocket();
        },
      },
      'pss_machine-test',
    );
    expect(calls.map((row) => row.path)).toEqual(['/v1/auth/ticket']);
    expect(calls[0]!.headers['Authorization']).toBe('Bearer pss_machine-test');
    expect(new URL(url).searchParams.get('ticket')).toBe('machine-ticket');
    expect(url).not.toContain('pss_machine-test');
    expect(opened.tokens).toEqual({ access: 'pss_machine-test' });
    opened.connection.close();
  });
  it('rejects invalid or revoked credentials without retrying or opening a socket', async () => {
    const { door, calls } = doorway({ '/v1/auth/ticket': { status: 401 } });
    let opened = false;
    const options = {
      ...door,
      session: LISTENING_AS,
      open: async () => {
        opened = true;
        return new FakeSocket();
      },
    };
    await expect(openServiceSocket(options, 'human-access')).rejects.toThrow(
      'service credential',
    );
    expect(calls).toHaveLength(0);
    await expect(openServiceSocket(options, 'pss_revoked')).rejects.toThrow();
    expect(calls).toHaveLength(1);
    expect(opened).toBe(false);
  });
});
