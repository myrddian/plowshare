"""Exercise the published typed API against the shared deterministic WebSocket fixture."""

import asyncio
import json
import os
import sys
from pathlib import Path

from plowshare import OPERATIONS, CancelledRequest, Client, Refusal, TransportError
from plowshare.contracts import JobStatusRequest, ProjectListRequest


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
    async with await Client.connect(
        origin, "sdk-fixture-token", session="typed-python/multiplex"
    ) as client:
        one, two = await asyncio.gather(
            client.request(ProjectListRequest()), client.request(ProjectListRequest())
        )
        assert one.require_payload()[0].name == "first"
        assert two.require_payload()[0].name == "second"
    tests = [(test["name"], test.get("delivery")) for test in fixture["cases"]]
    tests += [
        ("malformed-nested", "INVALID_RESPONSE"),
        ("missing-payload", "INVALID_RESPONSE"),
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
    print("Python typed SDK conformance passed")


asyncio.run(main())
