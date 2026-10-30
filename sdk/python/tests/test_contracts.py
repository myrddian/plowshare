"""Boundary validation uses the same cases as Go and .NET, independently of transport."""

import json
import os
import unittest
from pathlib import Path
from typing import cast

from plowshare import contracts
from plowshare._codec import (
    _SCHEMAS,
    _decode,
    _object,
    _schema,
    _wire,
    decode_push,
    decode_reply,
    deployment_matches,
    encode_request,
)


class ContractsTest(unittest.TestCase):
    def test_hosted_coordinates_and_secret_diagnostics(self) -> None:
        path = Path(os.environ["PLOWSHARE_SDK_DTO_FIXTURES"]).with_name(
            "hosted-scope-correlations.json"
        )
        cases = json.loads(path.read_text())
        for row in cases:
            result = decode_reply(row["operation"], row["reply"])
            self.assertEqual(
                deployment_matches(row["operation"], row["request"], result),
                row["valid"],
            )
            if row["operation"] == "tool.scope.worker.credential":
                self.assertNotIn(row["reply"]["value"], repr(result))

    def test_relay_publish_raw_utf8_limit(self) -> None:
        maximum = 50 * 1024 * 1024
        for text in ("x" * maximum, "é" * (maximum // 2)):
            asked = contracts.RelayPublishRequest(
                project="fixture",
                topic="large.events",
                request_id="11111111-1111-1111-1111-111111111111",
                occurred_at="2026-10-09T00:00:00Z",
                text=text,
            )
            encode_request(asked)
            with self.assertRaises(ValueError):
                encode_request(
                    contracts.RelayPublishRequest(
                        project=asked.project,
                        topic=asked.topic,
                        request_id=asked.request_id,
                        occurred_at=asked.occurred_at,
                        text=text + "x",
                    )
                )

    def test_deployment_receipt_coordinates_are_checked(self) -> None:
        asked = {
            "project": "app",
            "requestId": "11111111-1111-1111-1111-111111111111",
            "revision": "22222222-2222-2222-2222-222222222222",
        }
        result = decode_reply(
            "application.activate",
            {
                "project": "app",
                "requestId": asked["requestId"],
                "release": {
                    "revision": asked["revision"],
                    "digest": "a" * 64,
                    "fileCount": 1,
                },
            },
        )
        self.assertTrue(deployment_matches("application.activate", asked, result))
        self.assertFalse(
            deployment_matches(
                "application.activate", {**asked, "project": "other"}, result
            )
        )
        self.assertFalse(
            deployment_matches(
                "application.activate",
                {**asked, "requestId": "33333333-3333-3333-3333-333333333333"},
                result,
            )
        )
        self.assertFalse(
            deployment_matches(
                "application.activate",
                {**asked, "revision": "33333333-3333-3333-3333-333333333333"},
                result,
            )
        )

    def test_contained_replies_must_be_about_the_child_asked_of(self) -> None:
        child = "11111111-1111-1111-1111-111111111111"
        foreign = "33333333-3333-3333-3333-333333333333"
        asked = {"conversation": "c", "child": child}
        ceiling = decode_reply("application.child.ceiling", {"child": child})
        self.assertTrue(deployment_matches("application.child.ceiling", asked, ceiling))
        self.assertFalse(
            deployment_matches(
                "application.child.ceiling", {**asked, "child": foreign}, ceiling
            )
        )
        for collected, sources, expected in (
            ([foreign, child], 2, True),
            ([foreign], 1, False),
            ([child], 2, False),
        ):
            destroyed = decode_reply(
                "application.child.destroy",
                {"child": child, "collected": collected, "sources": sources},
            )
            self.assertIs(
                deployment_matches("application.child.destroy", asked, destroyed),
                expected,
            )
        self.assertFalse(
            deployment_matches(
                "application.child.destroy",
                {**asked, "child": foreign},
                decode_reply(
                    "application.child.destroy",
                    {"child": child, "collected": [child], "sources": 1},
                ),
            )
        )

    def test_application_credential_is_redacted_and_correlated(self) -> None:
        receipt = {
            "project": "app",
            "requestId": "11111111-1111-1111-1111-111111111111",
            "tokenId": "22222222-2222-2222-2222-222222222222",
            "principal": "@service/22222222-2222-2222-2222-222222222222",
            "expiresAt": "2026-11-01T00:00:00Z",
        }
        secret = "pss_" + "1" * 48
        issued = decode_reply(
            "application.token.issue", {"receipt": receipt, "credential": secret}
        )
        self.assertNotIn(secret, repr(issued))
        asked = {
            "project": "app",
            "requestId": receipt["requestId"],
            "expiresInDays": 30,
            "rotate": False,
        }
        self.assertTrue(deployment_matches("application.token.issue", asked, issued))
        self.assertFalse(
            deployment_matches(
                "application.token.issue", {**asked, "project": "foreign"}, issued
            )
        )
        self.assertFalse(
            deployment_matches(
                "application.token.issue",
                {**asked, "requestId": receipt["tokenId"]},
                issued,
            )
        )

    def test_selection_request_wrappers_emit_the_selected_operation_payload(
        self,
    ) -> None:
        samples = (
            (
                contracts.ConversationFollowRequest(
                    selection=contracts.ConversationFollowPayloadVariant1Dto(
                        conversation="cnv_fixture"
                    )
                ),
                {"conversation": "cnv_fixture"},
            ),
            (
                contracts.RelayLogRequest(
                    selection=contracts.RelayLogPayloadVariant1Dto(
                        project="fixture",
                        topic="tool.scanner.inspect.result",
                        system=False,
                    )
                ),
                {
                    "project": "fixture",
                    "topic": "tool.scanner.inspect.result",
                    "system": False,
                },
            ),
            (
                contracts.RelayTopicsRequest(
                    selection=contracts.RelayTopicsPayloadVariant1Dto(
                        project="fixture", system=False
                    )
                ),
                {"project": "fixture", "system": False},
            ),
        )
        for request, expected in samples:
            with self.subTest(operation=request.operation):
                self.assertEqual((request.operation, expected), encode_request(request))

    def test_shared_boundary_cases(self) -> None:
        path = Path(os.environ["PLOWSHARE_SDK_DTO_FIXTURES"])
        cases = cast(list[dict[str, object]], json.loads(path.read_text()))
        for test in cases:
            with self.subTest(
                boundary=test["boundary"],
                operation=test.get("operation"),
                value=test["value"],
            ):
                accepted = True
                try:
                    if test["boundary"] == "input":
                        _decode(
                            _schema(
                                _object(_SCHEMAS["inputs"])[
                                    cast(str, test["operation"])
                                ]
                            ),
                            test["value"],
                            True,
                        )
                    elif test["boundary"] == "result":
                        value = decode_reply(
                            cast(str, test["operation"]), test["value"]
                        )
                        # Construction of nested dataclasses and conversion back to wire
                        # must retain every declared required/optional field.
                        decode_reply(cast(str, test["operation"]), _wire(value))
                    else:
                        decode_push(test["value"])
                except ValueError:
                    accepted = False
                self.assertEqual(accepted, test["valid"])

    def test_generated_requests_only(self) -> None:
        self.assertEqual(
            set(contracts._REQUESTS.values()),
            set(_object(_SCHEMAS["inputs"])),
        )

        class Forged(contracts.Request[None]):
            operation = "project.list"

        with self.assertRaises(ValueError):
            encode_request(Forged())
        with self.assertRaises(ValueError):
            encode_request(contracts.JobStatusRequest(job="\0bad"))

    def test_omitted_and_explicit_null(self) -> None:
        from plowshare.contracts import ConversationOpenRequest, MemoryIndexRequest

        with self.assertRaises(ValueError):
            encode_request(ConversationOpenRequest())
        with self.assertRaises(ValueError):
            encode_request(ConversationOpenRequest(project=None))
        self.assertNotIn("project", _object(encode_request(MemoryIndexRequest())[1]))
        self.assertIn(
            "project", _object(encode_request(MemoryIndexRequest(project=None))[1])
        )


if __name__ == "__main__":
    unittest.main()
