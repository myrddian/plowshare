# SDK dispatch backpressure — design

**Status:** design, not yet planned or implemented.
**Predecessors:** [#105](https://github.com/myrddian/plowshare/pull/105) (server advertises a real
queue depth and answers every request), [#106](https://github.com/myrddian/plowshare/pull/106)
(database work bounded at its own pool).

## Why

The server used to have two behaviours under congestion, neither of them backpressure: accept a
request silently and queue it behind a global execution limit without ever replying, or tear down
the whole connection. #105 replaced both. The server now advertises a real per-connection queue
depth in the dispatch handshake and answers every request — either it is admitted, or it comes back
`CAPACITY_EXHAUSTED`.

That fixed the server and the TypeScript SDK together, because the TypeScript SDK was what the
failing desktop app used. It left the other SDKs behind, and in a specific way that is worse than
where they started: **the server now applies polite backpressure that four of the six SDKs do not
understand.** In Java, Python, Go and .NET the only occurrence of `CAPACITY_EXHAUSTED` is an entry
in a generated table of valid protocol codes. Nothing acts on it. So a loaded server slows down
correctly and those clients surface a hard error.

Two of them additionally carry the original defect that started all of this. The bug first reported
was a request deadline expiring before the request had been transmitted, so the time a request spent
queued on the client was charged against the time the server was given to answer it. `sdk/go`
arms its deadline in `admit()` (`client.go:120`), before conversion and before transmit, and
`sdk/dotnet` starts a `Stopwatch` when its admission is constructed (`Client.cs:55-61`, whose own
error message reads "Request deadline expired before conversion"). Both can therefore fail a request
for running out of time it never had.

## Goals

- One dispatch contract, the same in all six SDKs, for what happens when the connection's queue is
  full.
- No SDK charges client-side queue time against the request deadline.
- `CAPACITY_EXHAUSTED` is understood as flow control by every SDK, never surfaced as a fault.
- A caller who writes the obvious thing gets correct behaviour under load without having written any
  load-handling code.
- Callers who want to shed load rather than wait keep a way to do that, explicitly.
- A caller waiting for capacity can always get out: cancellation is honoured in every SDK, not just
  the ones where it was convenient.

## Non-goals

- Changing the server, the protocol, or the advertised depth. This is SDK work against the contract
  #105 already shipped.
- Reconnection, retry of application requests, or any replay of a mutation whose delivery is
  unknown. The existing prohibition stands and this design does not touch it: a resubmission after
  `CAPACITY_EXHAUSTED` is safe only because the server refused the work outright, under the same
  request id, having done none of it.
- Unifying anything else about the six SDKs. Their surfaces differ for good reasons and this is not
  an excuse to converge them.

## Anti-goals

Things that would make this a failure even if it technically worked:

- **A per-language default.** Six SDKs with six behaviours under congestion would recreate, on the
  client side, exactly the unpredictability #105 removed from the server.
- **Blocking a caller that cannot afford it,** with no way to opt out.
- **Making every call site handle a load condition.** If the waiting form is not the default, then
  correctness under load depends on every author remembering a case that only appears under load,
  which is the code least likely to be exercised before it ships.
- Silent retry loops with no bound on how long a caller can be held, or lockstep retries that
  synchronise every client onto the same schedule.

## The decision, and who made it

The owner's call, and the reasoning as they gave it: **both behaviours exist; the waiting one is the
default.** Java's fail-fast `NOT_SUBMITTED` was the original intent, and TypeScript's blocking was
adopted for other reasons — the immediate reason being that the desktop app was what was breaking.

The owner's instinct was that the default might reasonably vary by language, by what is idiomatic
there. The resolution, agreed in discussion, separates two things that "idiomatic" was covering:

- **The mechanism for waiting must vary.** Blocking a thread is ordinary in Java and Go. It is
  impossible in JavaScript, where waiting is `await`. .NET waits by continuation, not by holding a
  thread. Python has two execution models and today's SDK is `asyncio`-only.
- **Whether a full queue is an error or a wait must not vary.** That is a protocol semantic, not a
  language idiom. Go is equally idiomatic blocking on a channel send or returning early from a
  `select` with a `default`; the language has no opinion, and we do.

The axis that felt like language turned out to be **caller role** — an interactive client has
nothing useful to do with a refusal except retry, while a batch job under its own deadline may
prefer to shed load. That is a property of the call site, not of the language, and the same SDK
serves both. So it is expressed per call, not per SDK.

## The contract

Every SDK provides two ways to submit a request.

**The waiting form (the default).** The call does not complete until the request has been
transmitted. If the connection's queue is full, the call waits. If the server answers
`CAPACITY_EXHAUSTED`, that is flow control: the SDK waits and resubmits under the same request id,
and the caller never sees the code — it sees a reply, or, if refusals outlast the deadline armed at
first transmit, a timeout. "Never sees the code" means never as a fault to handle, not never
delayed.

Stated as "does not complete until" rather than "blocks" on purpose — a blocked thread and a pending
task both satisfy it, and which one a language uses is its own business.

**The non-blocking form.** Returns immediately with `NOT_SUBMITTED` when the queue is full, and
returns `CAPACITY_EXHAUSTED` to the caller as an outcome rather than absorbing it. This is Java's
and Go's current behaviour, preserved and named rather than deleted.

**In both forms, the request deadline arms when the request is transmitted, and exactly once.** Time
spent waiting for client-side capacity, for the conversion gate, or for a writer is not charged
against it. This is the part that fixes the originally reported bug in Go and .NET.

**Resubmission pacing.** A refusal waits out a jittered exponential backoff before resubmitting:
half fixed and half random, so a client always pauses and clients released together never retry in
lockstep. TypeScript's shipped values are the reference — base 100ms, cap 5000ms, doubling on the
refusal count, randomness injectable so tests are deterministic.

**What bounds the loop.** The backoff exponent is a pacing input, not a bound. The bound is the
deadline armed on the **first** transmit, which is neither renewed nor extended by a resubmission —
so backoff time and the server's own processing time both count against the caller's original
timeout, and a request refused repeatedly eventually fails with a timeout rather than retrying
forever. This is the reference's behaviour and it is deliberate; do not re-arm the deadline per
attempt. An earlier draft of this document said the waiting form has "no retry ceiling", which was
wrong, and the correction matters: it is the difference between a caller that can be held for its
own timeout and one that can be held indefinitely.

**The advertised window is authoritative.** Each SDK takes `maxPending` from the dispatch handshake,
validates it within `[1, 64]`, and never has more than that many requests outstanding. `MAX_PENDING`
is the client's hint and ceiling, not its entitlement.

## Where the six SDKs stand

Verified by reading the current `main` at the time of writing. "Fails fast" below means the SDK
honours the window and refuses locally rather than over-submitting, which is correct as far as it
goes — it is the absence of a waiting option, and of `CAPACITY_EXHAUSTED` handling, that this design
addresses.

| SDK | Window | Queue full | Deadline armed | `CAPACITY_EXHAUSTED` |
| --- | --- | --- | --- | --- |
| `typescript` | `[1,64]` | waits | at transmit, once | flow control, jittered backoff |
| `node` | inherits | inherits | inherits | inherits |
| `java` | `[1,64]` | `NOT_SUBMITTED` | **at submit entry** | unhandled |
| `python` | `1..64` | raises `NOT_SUBMITTED` | **at submit entry** | unhandled |
| `go` | yes | `NotSubmitted` | **at admission** | unhandled |
| `dotnet` | max 64 | `NotSubmitted` | **at admission** | unhandled |

`sdk/node` imports `plowshare-client-ts/binding/connection`, so it inherits the reference behaviour
and needs verification rather than implementation. Four SDKs need work: Java, Python, Go, .NET.

**All four arm the deadline before transmit.** This was checked in each, and it is the originally
reported bug, present in every SDK that has not had #105's treatment:

- `java` — `Plowshare.java:534` starts the clock at the top of the submit call, before the capacity
  check and before serialization. The writer loop then fails a request that is still queued with
  `"Queued request deadline expired"` (`:641`), which names the defect exactly.
- `python` — `client.py:282` computes `end` before the capacity check, and `asyncio.timeout_at(end)`
  (`:330`) wraps acquisition of the send lock, so waiting for the writer consumes the deadline.
- `go` — `client.go:120` arms `deadline` in `admit()`; `convert()` then waits on the converter gate
  against it (`:126-137`).
- `dotnet` — `Client.cs:55` starts a `Stopwatch` when the admission is constructed and `:61` raises
  `"Request deadline expired before conversion"`.

So the deadline-arming fix is not a nicety attached to this work: it is the same fault the original
report described, still live in four SDKs, and it is the reason this is worth doing beyond
consistency.

## Constraints

- `sdk/typescript` owns the shared WebSocket operations and contracts; `sdk/node` adds credentials,
  filesystem and platform support. Changes to shared behaviour belong in `typescript`.
- The reference implementation is `sdk/typescript/src/binding/connection.ts`. Where this document and
  that file disagree about the shipped values, the file is right and this document should be
  corrected.
- Deployment values stay configuration. No hard-coded origins, hosts or ports in any SDK.
- Each SDK keeps its own idioms, test framework and build. This is not a rewrite.

## Left to the builder

- Whether the two forms are two methods, one method with a flag, or one method with an options
  object, in each language. Naming is the implementer's call within each SDK's conventions; the
  contract is what must match.
- Whether Python grows a synchronous entry point. The SDK is `asyncio`-only today, and the contract
  is satisfiable without a sync variant. Adding one is a larger question than backpressure and
  should not be smuggled in here.
- How each SDK tests the waiting path deterministically. TypeScript injects the randomness source;
  the equivalent seam in each other language is the implementer's to choose, but the tests must not
  depend on wall-clock timing to pass.
- Per-SDK ordering. These are four independent pieces of work against one contract, and nothing here
  requires them to land together.

## Resolutions from the builder check

A fresh engineer read this document cold and reported what they would have had to guess. Each answer
below is either taken from the reference implementation or is a call recorded here so four
implementations make it the same way.

**The dispatch handshake never resubmits.** The `control` negotiation request is exempt. The server
treats a second dispatch selection on one connection as a protocol violation, so a retry would
convert a refusal into a teardown. A refused negotiation fails honestly. This matches the reference
and is not a per-SDK choice.

**A refused request releases its dispatch count but keeps its admission slot.** This paragraph
previously said the opposite — that it releases its window slot and rejoins the queue at the back —
which was wrong, and wrong in a way four implementations would have inherited. The reference keeps
two counters: `admitted` with `slotWaiters` for the window, and `dispatched` with `launchWaiters`
for work in flight. A refusal decrements `dispatched` only (`connection.ts:669`); `admitted` is
released at settlement (`:543`, `:566`). So the refused request retains its place in the window and
rejoins the back of the *dispatch* queue, not the admission queue.

The observable consequence, which a scenario pins: with a window of 1, request A refused and
request B parked for admission *before* that refusal, the order is **A, A, B** — B waits through A's
backoff and A's successful retry. An implementation that released admission on refusal would
produce A, B, A.

Keeping admission is also the safer behaviour, not merely the shipped one. Releasing it would let
newly arriving requests overtake a backing-off request repeatedly, which is exactly the starvation
the FIFO rule exists to prevent; retaining it means a refused caller cannot be pushed to the back of
the queue by work that arrived after it. What a refusal must not do is hold the *dispatch* count,
because that would idle capacity the server has explicitly said it can take.

**Every request in these four SDKs has a deadline**, so the question of an unbounded no-deadline
retry loop does not arise here. Each carries a connection-level timeout and falls back to it
(`go` defaults a zero `Timeout` to 30s; `python` falls back to the connection's; `java` and `dotnet`
hold one per client). The reference has a branch that surfaces `CAPACITY_EXHAUSTED` to the caller
when no scheduler and no deadline exist — that is TypeScript's ordered mode and has no analogue in
these four. An implementer who finds a genuine no-deadline path should follow the reference and
surface the code rather than retry unbounded.

**The non-blocking form uses each SDK's existing outcome channel** — an exception in Python, a
returned value in Java, Go and .NET — and does not need a new one. It performs no backoff and no
resubmission at all: it reports and returns. Making its refusals observable through the channel
callers already handle is the point.

**"At transmit" means when the frame is written to the socket**, not when a queued request is
dequeued for consideration and not when the reply arrives. Concretely: after any wait for the
conversion gate, the writer lock or the send lock, and once per request — a resubmission does not
re-arm it. In Java that is inside the writer loop at the write, not at the top of the submit call
(`Plowshare.java:534` is where it must stop being armed). In Python it is after `_send_lock` is
acquired, not before `asyncio.timeout_at` wraps it.

**A gap found in `node` is fixed in `typescript`.** `sdk/typescript` owns the shared WebSocket
operations and contracts, so shared behaviour is corrected there and `node` inherits the fix rather
than carrying its own copy.

## Cancelling the wait for local capacity

**Decided: every SDK must honour cancellation while a request waits for client-side capacity.**

The deadline arms at transmit, so a request waiting for local capacity has no time bound of its own
— the bound in "What bounds the loop" above starts only once the request has been sent at least
once. Without cancellation the waiting form could therefore hold a caller indefinitely whenever the
window never clears, with only a connection close to release it. That is the one case where the
default would be unsafe, so the contract closes it rather than leaving it per-SDK.

What this requires:

- The waiting form accepts, or otherwise observes, its language's existing cancellation signal, and
  returns promptly when it fires while waiting for capacity. Three SDKs already thread one through
  the relevant call: `go` passes a `context.Context` into `convert`, `dotnet` takes a
  `CancellationToken`, `python` is task-cancellable. Java uses interruption.
- A cancelled wait is `NOT_SUBMITTED`. Nothing was written, so this is the one delivery claim that
  is unambiguously safe to make, and callers may act on it without the replay prohibition applying.
- Cancellation is reported in each language's own currency — `ctx.Err()`, an
  `OperationCanceledException`, a `CancelledError`, an `InterruptedException` — not flattened into a
  generic transport fault, so a caller can tell "I cancelled this" from "the server refused it".
- A cancelled wait releases its window slot and leaves the in-flight accounting exactly as it was
  before the call. Cancellation must not be a way to leak capacity.
- Cancelling after transmit is out of scope. Once a frame is on the wire the existing prohibition
  stands: no application request is replayed and no mutation of unknown delivery is retried.
  Cancellation here means abandoning a wait, never recalling a sent request.

## How this gets checked

The contract above is the thing to test, in each SDK, with its own framework:

- A request submitted into a full window completes rather than failing, once capacity frees.
- A `CAPACITY_EXHAUSTED` reply is not visible to a caller using the waiting form.
- The deadline is not consumed by a wait for capacity: a request held at the window bound for longer
  than its own timeout, then admitted, still gets its full timeout from transmit.
- The non-blocking form still returns `NOT_SUBMITTED` immediately on a full window.
- Resubmission pacing always pauses and is never lockstep across callers.
- Repeated refusals end in a timeout rather than retrying forever, and the deadline is not re-armed
  per attempt.
- A request refused and backing off releases its dispatch count, retains its admission slot, and is
  not overtaken by work that parked for admission before the refusal.
- The dispatch handshake is not resubmitted on refusal.
- A wait for local capacity returns promptly when cancelled, reports `NOT_SUBMITTED` in the
  language's own cancellation currency, and leaves in-flight accounting unchanged.

The third of these is the regression test for the originally reported bug, and it is the one no SDK
has today.
