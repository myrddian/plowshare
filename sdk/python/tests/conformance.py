"""Exercise the published typed API against the shared deterministic WebSocket fixture."""

import asyncio
import json
import os
import sys
from pathlib import Path

from plowshare import (
    OPERATIONS,
    ActingSelection,
    CancelledRequest,
    Client,
    Refusal,
    TransportError,
)
from plowshare.contracts import (
    ConversationTurnsRequest,
    JobStatusRequest,
    ProjectListRequest,
    ProviderConnectionListRequest,
    RelayConsumeRequest,
    RelayPublishRequest,
    ToolScopeHostListRequest,
)

# The connection-level timeout every backpressure case is opened with, apart from
# capacity-refuse-always, whose whole point is a short deadline ending the
# resubmission loop.
#
# It is deliberately long, and 5s rather than anything shorter, because
# capacity-deadline-survives-waiting tells "the request carries its own timeout,
# armed at transmit" apart from "it silently fell back to the connection's
# timeout" by probing at waitingProbeMs. A request wrongly running on this
# connection's deadline expires at holdMs + 5000ms, well past the probe; a shorter
# connection timeout would move that expiry inside the probe window and the case
# would pass for an implementation that dropped per-request timeouts entirely.
CAPACITY_CONNECTION_TIMEOUT = 5.0


def milliseconds(value: object) -> float:
    assert isinstance(value, int), "fixture timing must be an integer millisecond count"
    return value / 1000


async def settled(task: "asyncio.Task[object]", pause: float) -> bool:
    """Report whether a request settled within ``pause``, leaving it running.

    A false return is what distinguishes a parked request from a locally refused
    one: the non-blocking form settles in the same instant, so a request still
    pending after a pause taken from inside the fixture's hold cannot have been
    refused locally.
    """
    done, _ = await asyncio.wait({task}, timeout=max(pause, 0))
    return bool(done)


async def backpressure(origin: str, cases: dict[str, dict[str, object]]) -> None:
    """Drive the dispatch backpressure cases the generic case loop skips.

    CAPACITY_EXHAUSTED is flow control a caller never sees as a fault, the
    advertised window is authoritative and waiting rather than refusing, the
    request deadline is armed at transmit and spends nothing on the wait, and a
    refused dispatch negotiation is never resubmitted. Every number comes from the
    fixture; its ``capacityContract`` block says what each of them means.
    """

    async def open_client(
        name: str, timeout: float = CAPACITY_CONNECTION_TIMEOUT
    ) -> Client:
        return await Client.connect(
            origin, "sdk-fixture-token", session="typed-python/" + name, timeout=timeout
        )

    def honest(test: dict[str, object]) -> list[object]:
        # Read directly from the fixture: every delivery value there is already in
        # SDK vocabulary, so nothing is translated. An absent alternates list means
        # the case admits exactly its own delivery.
        alternates = test.get("deliveryAlternates") or []
        assert isinstance(alternates, list)
        return [test["delivery"], *alternates]

    # Every case is driven even when an earlier one fails, and the failures are
    # reported together at the end: these seven fail in related ways, and the first
    # exception alone hides which of the others the same defect also broke.
    failed: dict[str, BaseException] = {}

    # A single refusal is absorbed: the caller sees the answer to the
    # resubmission, not the refusal, and not an error.
    async def refuse_once() -> None:
        client = await open_client("capacity-refuse-once")
        try:
            reply = await client.request(ProjectListRequest())
            assert reply.require_payload() == (), "a refused request lost its answer"
        finally:
            await client.close()

    # Refusals that outlast the deadline armed at first transmit end in the
    # caller's own timeout, never in a surfaced refusal. Both deliveries the
    # fixture admits are accepted: NOT_SUBMITTED is the usual one, because the
    # deadline almost always lands inside a backoff pause, and UNKNOWN is equally
    # truthful in the sub-millisecond window between a transmit and the refusal
    # arriving. The invariant is a deadline expiry, which is why the message is
    # required to name one: a connection teardown must not pass as an expiry.
    async def refuse_always() -> None:
        test = cases["capacity-refuse-always"]
        client = await open_client("capacity-refuse-always", 0.6)
        try:
            await client.request(ProjectListRequest())
            raise AssertionError("repeated refusals became a reply")
        except TransportError as error:
            assert error.delivery in honest(test), (
                "repeated refusals must end in a deadline expiry, "
                "not a surfaced refusal"
            )
            assert "deadline" in str(error), (
                "a connection teardown must not pass as a deadline expiry"
            )
        finally:
            await client.close()

    # At an advertised window of one the second request waits for a slot and is
    # answered once the first frees it, rather than failing NOT_SUBMITTED.
    async def window_one() -> None:
        client = await open_client("capacity-window-one")
        try:
            held = asyncio.create_task(client.request(ProjectListRequest()))
            hint = await client.next_push()
            assert getattr(hint, "kind", None) == "inbox.changed", (
                "window-full hint missing"
            )
            # Submitted after the hint, so the first request is known to hold the
            # only slot and this one can only park. The 100ms probe lives inside the
            # fixture's 250ms hold, which is what makes "still pending" meaningful.
            waiting = asyncio.create_task(client.request(ProjectListRequest()))
            assert not await settled(waiting, 0.1), (
                "a request must wait while the advertised window of one is full"
            )
            assert (await held).require_payload() == (), "the held request failed"
            assert (await waiting).require_payload() == (), (
                "the parked request failed once the window freed"
            )
        finally:
            await client.close()

    # A refusal releases the dispatch count so other admitted work can transmit,
    # but keeps its admission slot, so work parked for admission behind it cannot
    # overtake it: the frame order is A, A, B. Releasing admission on refusal
    # produces A, B, A, which the harness fails.
    async def slot_order() -> None:
        client = await open_client("capacity-slot-order")
        try:
            one = asyncio.create_task(client.request(ProjectListRequest()))
            # Enough for the first request to be transmitted and refused, so the
            # second is submitted while the first is inside its backoff pause --
            # the window in which a client that had released its admission slot
            # would be overtaken. The first backoff is at least half of the 100ms
            # base, so this pause cannot outlast it. The sleep buys that window and
            # nothing more: at maxPending 1 the order is A, A, B however it lands,
            # because B parks for admission until A settles and A is always the
            # first frame.
            await asyncio.sleep(0.025)
            two = asyncio.create_task(client.request(ProjectListRequest()))
            assert (await one).require_payload() == (), (
                "a refused request lost its answer"
            )
            assert (await two).require_payload() == (), (
                "the request behind a refused one lost its answer"
            )
        finally:
            await client.close()

    # The regression test for the originally reported bug. Client-side queue time
    # must not be charged against the request deadline, AND the deadline the
    # request then runs on must be its own, armed when its frame was written.
    #
    # The fixture advertises a window of one, holds the first request for holdMs
    # (900ms) and then answers nothing more (silentAfterWait), so the only thing
    # that can settle the request admitted next is its own deadline. The waiting
    # request carries waitingTimeoutMs (300ms) -- less than a third of the wait it
    # is about to serve -- and a sibling carrying only the connection's 5s parks
    # behind it. The four directions land in different places:
    #   - armed before transmit (charged for the wait): it expires at 300ms, during
    #     the hold, and is never transmitted. The harness sees two frames of three.
    #   - never armed: it never settles, and the probe below expires first.
    #   - armed with the connection's duration: it expires at 900+5000ms, and the
    #     probe below expires first.
    #   - armed at transmit with its own 300ms: it expires at 1200ms. Only this
    #     one passes.
    # The probe is waitingProbeMs (2500ms) from the window filling: 1300ms of
    # scheduler tolerance above the correct expiry, and 3400ms below the expiry of
    # a request wrongly running on the connection's deadline.
    async def deadline_survives_waiting() -> None:
        test = cases["capacity-deadline-survives-waiting"]
        client = await open_client("capacity-deadline-survives-waiting")
        started: list[asyncio.Task[object]] = []
        try:
            held = asyncio.create_task(client.request(ProjectListRequest()))
            hint = await client.next_push()
            assert getattr(hint, "kind", None) == "inbox.changed", (
                "window-full hint missing"
            )
            loop = asyncio.get_running_loop()
            probe = loop.time() + milliseconds(test["waitingProbeMs"])
            waiting = asyncio.create_task(
                client.request(
                    ProjectListRequest(),
                    timeout=milliseconds(test["waitingTimeoutMs"]),
                )
            )
            # The sibling must park behind the waiting request, so it is submitted
            # after it; both pauses live inside the fixture's 900ms hold. Its own
            # settlement comes on close, which is why it is submitted rather than
            # ignored.
            await asyncio.sleep(0.1)
            sibling = asyncio.create_task(client.request(ProjectListRequest()))
            started.append(sibling)
            assert (await held).require_payload() == (), "the held request failed"
            assert await settled(waiting, probe - loop.time()), (
                "the waiting request did not expire on a deadline of its own: it is"
                " carrying no deadline, or the connection's, not one armed at"
                " transmit"
            )
            try:
                await waiting
                raise AssertionError(
                    "the fixture must answer nothing once the hold is over"
                )
            except TransportError as error:
                assert error.delivery == test["waitingDelivery"], (
                    "the waiting request was transmitted, so its expiry may have"
                    " left work done and is UNKNOWN"
                )
                assert "deadline" in str(error), (
                    "a connection teardown must not pass as a deadline expiry"
                )
            # Read only once the waiting request has settled, so this is a state
            # check and not a race: it is the proof that the connection was alive
            # and the fixture's silence deliberate.
            assert not sibling.done(), (
                "the sibling carrying only the connection deadline must still be"
                " pending when the waiting request expires"
            )
            # The sibling is admitted by the waiting request's expiry, and its frame
            # has to reach the fixture before this case closes the connection: the
            # harness counts three frames, and the third is how it knows a request
            # that waited longer than its own timeout was really transmitted. A
            # released caller here is a separate task, so its transmit is one
            # scheduler turn behind the expiry rather than inside it; 300ms is three
            # orders of magnitude above a loop turn and still far below the 5s
            # connection deadline that is the only other thing which could settle
            # it. The assertion is that it stays unanswered, so the pause is a
            # timing allowance and not itself a signal.
            assert not await settled(sibling, 0.3), (
                "the fixture must answer nothing once the hold is over"
            )
        finally:
            await client.close()
            # Collected so the sibling's release-on-close failure is retrieved
            # rather than reported as an unhandled task exception.
            await asyncio.gather(*started, return_exceptions=True)

    # A refused dispatch negotiation is not flow control and is never resubmitted:
    # the server treats a second selection on one connection as a protocol
    # violation, so a retry would turn a refusal into a teardown.
    async def handshake_refuse() -> None:
        test = cases["capacity-handshake-refuse"]
        try:
            await open_client("capacity-handshake-refuse")
            raise AssertionError("a refused dispatch negotiation must fail honestly")
        except TransportError as error:
            assert error.delivery == test["delivery"], (
                "a refused dispatch negotiation must fail honestly"
            )

    # A caller parked for a slot is released when the connection closes, and its
    # delivery is unambiguous because nothing was ever written for it.
    async def close_while_parked() -> None:
        test = cases["capacity-close-while-parked"]
        client = await open_client("capacity-close-while-parked")
        try:
            held = asyncio.create_task(client.request(ProjectListRequest()))
            hint = await client.next_push()
            assert getattr(hint, "kind", None) == "inbox.changed", (
                "window-full hint missing"
            )
            waiting = asyncio.create_task(client.request(ProjectListRequest()))
            # Without this the case proves nothing: one frame on the wire, UNKNOWN
            # for the held caller and NOT_SUBMITTED for the other are all equally
            # true of a client that refuses locally and immediately. The 200ms pause
            # lives inside the fixture's 1000ms hold, which ends this case by
            # closing the socket.
            assert not await settled(waiting, 0.2), (
                "the parked request must wait for a slot, not fail fast"
            )
            for task, delivery, complaint in (
                (
                    held,
                    test["delivery"],
                    "a transmitted request lost its uncertainty on close",
                ),
                (
                    waiting,
                    "NOT_SUBMITTED",
                    "a parked request was released with anything but NOT_SUBMITTED",
                ),
            ):
                try:
                    await task
                    raise AssertionError(complaint)
                except TransportError as error:
                    assert error.delivery == delivery, complaint
        finally:
            await client.close()

    for name, body in (
        ("capacity-refuse-once", refuse_once),
        ("capacity-refuse-always", refuse_always),
        ("capacity-window-one", window_one),
        ("capacity-slot-order", slot_order),
        ("capacity-deadline-survives-waiting", deadline_survives_waiting),
        ("capacity-handshake-refuse", handshake_refuse),
        ("capacity-close-while-parked", close_while_parked),
    ):
        try:
            await body()
        except Exception as error:  # noqa: PERF203 - one report per case
            failed[name] = error
    for name, cause in failed.items():
        print("capacity case failed:", name, "--", repr(cause), file=sys.stderr)
    assert not failed, "dispatch backpressure cases failed: " + ", ".join(failed)


async def main() -> None:
    fixture = json.loads(Path(os.environ["PLOWSHARE_SDK_FIXTURES"]).read_text())
    catalog = json.loads(Path(os.environ["PLOWSHARE_SDK_CATALOG"]).read_text())
    assert OPERATIONS == frozenset(catalog["operations"])
    origin = sys.argv[1]
    try:
        await Client.connect(origin + "/wrong", "sdk-fixture-token")
        raise AssertionError("invalid origin accepted")
    except ValueError:
        pass
    try:
        await Client.connect(
            origin, "sdk-redirect-fixture", session="python-redirect", timeout=0.3
        )
        raise AssertionError("redirect followed")
    except TransportError as error:
        assert error.delivery == "NOT_SUBMITTED"
    try:
        await Client.connect(
            origin, "sdk-legacy-fixture", session="python-legacy-refusal"
        )
        raise AssertionError("unsupported transport accepted")
    except TransportError as error:
        assert error.delivery == "NOT_SUBMITTED"
    async with await Client.connect(
        origin, "sdk-fixture-token", session="typed-python/long-session"
    ) as long_session:
        for _ in range(4200):
            reply = await long_session.request(ProjectListRequest())
            assert reply.require_payload()[0].name == "long-session"
    async with await Client.connect(
        origin, "sdk-fixture-token", session="typed-python/acting-selection"
    ) as selected:
        for context in [
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
        ]:
            await selected.request(
                ConversationTurnsRequest(conversation="fixture"),
                acting_for=ActingSelection(context),
            )
        await selected.request(ConversationTurnsRequest(conversation="fixture"))
    async with await Client.connect(
        origin, "sdk-fixture-token", session="typed-python/multiplex"
    ) as client:
        first = asyncio.create_task(client.request(ProjectListRequest()))
        assert getattr(await client.next_push(), "kind", None) == "inbox.changed"
        second = await client.request(ProjectListRequest())
        assert second.require_payload()[0].name == "second"
        assert not first.done(), "B must complete while A remains held"
        third = await client.request(ProjectListRequest())
        assert third.require_payload()[0].name == "third"
        assert (await first).require_payload()[0].name == "first"
    async with await Client.connect(
        origin, "sdk-fixture-token", session="typed-python/capacity", timeout=5
    ) as capacity:
        calls = [
            asyncio.create_task(capacity.request(ProjectListRequest()))
            for _ in range(64)
        ]
        for count in range(1, 65):
            hint = await capacity.next_push()
            assert getattr(hint, "unread", None) == count
        try:
            # The non-blocking form: the waiting form is the default now, so a
            # 65th request at a full window would park here instead of failing.
            await capacity.request(ProjectListRequest(), wait_for_capacity=False)
            raise AssertionError("65th request was submitted")
        except TransportError as error:
            assert error.delivery == "NOT_SUBMITTED"
        await asyncio.gather(capacity.close(), capacity.close())
        assert (capacity._admitted, capacity._held) == (0, 0)
        for call in calls:
            try:
                await call
                raise AssertionError("Closed pending request succeeded")
            except TransportError as error:
                assert error.delivery == "UNKNOWN"
    # Dispatch backpressure cases need the negotiated window and several requests,
    # so this generic one-request-per-case loop cannot drive them: backpressure()
    # below drives each one deliberately. Without the skip the loop aborts on the
    # first of them and every unrelated assertion after it stops running.
    tests = [
        (test["name"], test.get("delivery"))
        for test in fixture["cases"]
        if not test.get("capacity")
    ]
    tests += [
        ("malformed-nested", "INVALID_RESPONSE"),
        ("missing-payload", "INVALID_RESPONSE"),
        ("bad-packet", "UNKNOWN"),
    ]
    for name, delivery in tests:
        async with await Client.connect(
            origin, "sdk-fixture-token", session="typed-python/" + name, timeout=0.3
        ) as client:
            try:
                reply = await client.request(ProjectListRequest())
                assert delivery is None
                if name == "refusal":
                    try:
                        reply.require_payload()
                        raise AssertionError("refusal became success")
                    except Refusal as error:
                        assert (
                            error.code == "CONFLICT" and error.said == "fixture refusal"
                        )
                else:
                    project = reply.require_payload()[0]
                    assert project.name == name and not hasattr(project, "future")
            except TransportError as error:
                assert error.delivery == delivery, (name, error.delivery)
            if name == "push":
                bare = await client.next_push()
                assert getattr(bare, "kind", None) == "inbox.changed"
                envelope = await client.next_push()
                assert getattr(envelope, "type_", None) == "usage.closed"
            if name == "disconnect":
                try:
                    await asyncio.wait_for(client.next_push(), 1)
                    raise AssertionError("closed stream returned a hint")
                except TransportError:
                    pass
                try:
                    await client.request(ProjectListRequest())
                    raise AssertionError("closed client submitted work")
                except TransportError as error:
                    assert error.delivery == "NOT_SUBMITTED"
    async with await Client.connect(
        origin, "sdk-fixture-token", session="typed-python/invalid-input"
    ) as client:
        try:
            await client.request(JobStatusRequest(job="\0bad"))
            raise AssertionError("invalid identifier submitted")
        except ValueError:
            pass
    async with await Client.connect(
        origin, "sdk-fixture-token", session="typed-python/provider-owner-compat"
    ) as client:
        for request in (
            ProviderConnectionListRequest(project="fixture"),
            ToolScopeHostListRequest(project="fixture"),
        ):
            management_reply = await client.request(request)
            try:
                management_reply.require_payload()
                raise AssertionError("unsupported management became success")
            except Refusal as error:
                assert error.code == "NOT_FOUND"
                assert (
                    error.said
                    == f'no handler is registered for type "{request.operation}"'
                )
    async with await Client.connect(
        origin, "sdk-fixture-token", session="typed-python/cancel"
    ) as client:
        task = asyncio.create_task(client.request(ProjectListRequest()))
        assert getattr(await client.next_push(), "kind", None) == "inbox.changed"
        task.cancel()
        try:
            await task
            raise AssertionError("cancelled request succeeded")
        except CancelledRequest as error:
            assert error.delivery == "UNKNOWN"
    large_text = "\u0001" * (5 * 1024 * 1024)
    async with await Client.connect(
        origin, "sdk-fixture-token", session="packet-large-python", timeout=30
    ) as client:
        identity = "11111111-1111-1111-1111-111111111111"
        publication = (
            await client.request(
                RelayPublishRequest(
                    request_id=identity,
                    project="fixture",
                    topic="large.events",
                    text=large_text,
                    occurred_at="2026-10-09T00:00:00Z",
                )
            )
        ).require_payload()
        assert publication.position == "1"
        batch = (
            await client.request(
                RelayConsumeRequest(
                    project="fixture",
                    topic="large.events",
                    group="fixture",
                    consumer_id=identity,
                    start="OLDEST_RETAINED",
                )
            )
        ).require_payload()
        assert batch.events[0].payload.text == large_text
    await backpressure(origin, {test["name"]: test for test in fixture["cases"]})
    print("Python typed SDK conformance passed")


asyncio.run(main())
