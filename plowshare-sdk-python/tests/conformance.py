import asyncio
import json
import os
from pathlib import Path
import sys
from plowshare import Client, TransportError, Refusal, OPERATIONS, CancelledRequest

async def main():
    fixture = json.loads(Path(os.environ["PLOWSHARE_SDK_FIXTURES"]).read_text())
    catalog = json.loads(Path(os.environ["PLOWSHARE_SDK_CATALOG"]).read_text())
    assert OPERATIONS == frozenset(catalog["operations"])
    origin = sys.argv[1]
    try:
        await Client.connect(origin + "/wrong", "sdk-fixture-token")
        raise AssertionError("invalid origin was accepted")
    except ValueError:
        pass
    try:
        await Client.connect(origin, "sdk-redirect-fixture", session="python-redirect", timeout=0.3)
        raise AssertionError("redirect was followed")
    except TransportError as error:
        assert error.delivery == "NOT_SUBMITTED"
    async with await Client.connect(origin, "sdk-fixture-token", session="python", timeout=0.3) as client:
        one, two = await asyncio.gather(client.request("project.list", {"scenario": "multiplex-one"}), client.request("project.list", {"scenario": "multiplex-two"}))
        assert one.require_payload()["sequence"] == 1 and two.require_payload()["sequence"] == 2
        for test in fixture["cases"]:
            try:
                reply = await client.request("project.list", {"scenario": test["name"]})
                assert "delivery" not in test
                assert reply.outcome == test["response"] and reply.raw["futureEnvelope"] is True
                if test["name"] == "success":
                    assert reply.require_payload()["nullable"] is None
                if test["name"] == "refusal":
                    try:
                        reply.require_payload()
                        raise AssertionError("refusal became success")
                    except Refusal:
                        pass
            except TransportError as error:
                assert error.delivery == test["delivery"], (test["name"], error.delivery)
        assert (await client.next_push())["kind"] == "fixture-push"
        assert (await client.next_push())["type"] == "usage.snapshot"
        try:
            await client.request("project.list", {})
            raise AssertionError("closed client submitted work")
        except TransportError as error:
            assert error.delivery == "NOT_SUBMITTED"
    async with await Client.connect(origin, "sdk-fixture-token", session="python-cancel") as client:
        task = asyncio.create_task(client.request("project.list", {"scenario": "cancel"}))
        assert (await client.next_push())["kind"] == "fixture-submitted"
        task.cancel()
        try:
            await task
            raise AssertionError("cancelled request returned success")
        except CancelledRequest as error:
            assert error.delivery == "UNKNOWN"
    print("Python SDK conformance passed")

asyncio.run(main())
