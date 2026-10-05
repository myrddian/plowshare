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
    encode_request,
)


class ContractsTest(unittest.TestCase):
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
        from plowshare.contracts import ConversationOpenRequest

        self.assertNotIn(
            "project", _object(encode_request(ConversationOpenRequest())[1])
        )
        self.assertIn(
            "project", _object(encode_request(ConversationOpenRequest(project=None))[1])
        )


if __name__ == "__main__":
    unittest.main()
