import type { Socket } from 'plowshare-client-ts/binding/connection';
import type { Fetching } from 'plowshare-client-ts/binding/auth';

/**
 * The one place in this module where the platform's own types are named, and
 * the whole of what it does is assert that they fit the structural interfaces
 * `binding/` declares.
 *
 * <h2>Why this exists: task 4 reasoned, and nothing measured</h2>
 *
 * <p>`connection.ts` declares {@link Socket} — four members — and says a real
 * `WebSocket` satisfies it structurally. Task 4 never handed it one: its own
 * report records that the claim "is reasoning, not measurement". `auth.ts`
 * makes the same claim about `fetch` for {@link Fetching}. Both claims are the
 * hinge of the injected-dependency ruling, and both were unverified until this
 * file, which turns each into a line the compiler either accepts or refuses.
 *
 * <p><b>Nothing imports this file and nothing ever should.</b> It emits no
 * runtime code — every declaration is a type — and it is not a fourth layer of
 * the module. It is an assertion that happens to be written in TypeScript,
 * compiled by `tsc -b` because the solution file names it, and the two
 * assignments below are the entire content.
 *
 * <h2>Why a project of its own, with `lib: DOM` in a module that forbids DOM</h2>
 *
 * <p>`WebSocket` and `fetch` have to be nameable for this check to exist, and
 * this repository has exactly two ways to name them: install `@types/node`, or
 * add `"DOM"` to a `lib`. <b>The first is refused</b> — `src/neutrality.test.ts`
 * names installing `@types/node` as the first of the four edits that quietly
 * undo the guards, and `binding/tsconfig.json`'s `types: []` is load-bearing
 * for exactly that reason. The second is confined here: this project is
 * referenced by nothing, references `../binding` one way, and so cannot lend
 * its `lib` to any file that ships. `logic/` and `binding/` compile exactly as
 * they did.
 *
 * <p><b>What this proves and what it does not.</b> `lib.dom.d.ts` declares the
 * WHATWG `WebSocket` and `fetch` — the specifications Node's globals
 * implement — so a pass here says a spec-conformant implementation satisfies
 * these interfaces. It does not say that *this* Node's classes do, because
 * their declarations (`@types/node`, via `undici-types`) are not installed and
 * will not be. That second half is measured at runtime instead, by
 * `src/real-socket.test.ts`, which drives `connect()` over an actual
 * `new WebSocket(...)` against a real loopback server. Two halves, because
 * neither one alone is the claim being made.
 */

/**
 * <b>A real `WebSocket` is a {@link Socket}.</b>
 *
 * Written as the opener `openSocket` actually takes rather than as a bare
 * instance, since that is the shape the view will inject: the day a member is
 * added to {@link Socket} that a `WebSocket` does not have, this line stops
 * compiling.
 */
export type RealSocketFits = (url: string) => Socket;

export const opener: RealSocketFits = (url) => new WebSocket(url);

/**
 * <b>A real `fetch` is a {@link Fetching}.</b>
 *
 * The interesting half is contravariant: what `auth.ts` sends — a method, a
 * flat header record, a string body — has to be something a real `fetch`
 * accepts, and what it reads back — `status`, `headers.get`,
 * `headers.getSetCookie`, `json()` — has to be something a real `Response`
 * provides. `getSetCookie` is the member that matters: `POST /v1/auth/refresh`
 * answers 204 with `Set-Cookie` and no body at all, so a client with no cookie
 * jar has nowhere else to read the rotated pair from.
 */
export const fetching: Fetching = fetch;
