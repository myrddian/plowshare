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
            await capacity.request(ProjectListRequest())
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
    tests = [(test["name"], test.get("delivery")) for test in fixture["cases"]]
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
    print("Python typed SDK conformance passed")


asyncio.run(main())
