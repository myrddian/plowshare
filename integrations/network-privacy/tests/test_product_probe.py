"""Fixed read-only probes cannot become an arbitrary device HTTP client."""

from __future__ import annotations

import json
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from plowshare_privacy.product_probe import MAX_BYTES, HttpProductProbe, sony_response


class ProductProbeTest(unittest.IsolatedAsyncioTestCase):
    async def test_only_fixed_requests_without_redirects_or_cookies(self) -> None:
        response = MagicMock()
        response.status = 200
        response.content.read = AsyncMock(
            side_effect=[
                b"<html>TV</html>",
                b"",
                b'{"id":1,"result":[{"status":"active"}]}',
                b"",
            ]
        )
        session = MagicMock()
        session.request.return_value.__aenter__ = AsyncMock(return_value=response)
        session.request.return_value.__aexit__ = AsyncMock(return_value=False)
        session.__aenter__ = AsyncMock(return_value=session)
        session.__aexit__ = AsyncMock(return_value=False)
        with patch(
            "plowshare_privacy.product_probe.ClientSession", return_value=session
        ) as constructor:
            observations = await HttpProductProbe().inspect("192.168.8.20", True)
        self.assertFalse(constructor.call_args.kwargs["trust_env"])
        self.assertEqual(
            ["http_response", "information_without_credentials"],
            [p.outcome for p in observations],
        )
        root, api = session.request.call_args_list
        self.assertEqual(("GET", "http://192.168.8.20:80/"), root.args)
        self.assertEqual(("POST", "http://192.168.8.20:80/sony/system"), api.args)
        self.assertFalse(api.kwargs["allow_redirects"])
        self.assertEqual("getPowerStatus", api.kwargs["json"]["method"])
        self.assertNotIn("headers", api.kwargs)
        self.assertNotIn("192.168.8.20", repr(observations))

    async def test_non_sony_probe_never_sends_sony_request_and_caps_fragments(
        self,
    ) -> None:
        response = MagicMock(status=200)
        response.content.read = AsyncMock(
            side_effect=[b"a" * 4000, b"b" * (MAX_BYTES - 3999)]
        )
        session = MagicMock()
        session.__aenter__ = AsyncMock(return_value=session)
        session.__aexit__ = AsyncMock(return_value=False)
        session.request.return_value.__aenter__ = AsyncMock(return_value=response)
        session.request.return_value.__aexit__ = AsyncMock(return_value=False)
        with patch(
            "plowshare_privacy.product_probe.ClientSession", return_value=session
        ):
            observations = await HttpProductProbe().inspect("192.168.8.20", False)
        session.request.assert_called_once()
        self.assertEqual("oversize", observations[0].outcome)

    async def test_out_of_scope_address_shapes_fail_before_network(self) -> None:
        with patch("plowshare_privacy.product_probe.ClientSession") as session:
            for address in (
                "127.0.0.1",
                "8.8.8.8",
                "tv.local",
                "::1",
                "192.168.8.20/path",
            ):
                with self.subTest(address=address), self.assertRaises(ValueError):
                    await HttpProductProbe().inspect(address, True)
            session.assert_not_called()

    def test_api_error_and_information_are_not_control_access(self) -> None:
        for body, expected in (
            ({"id": 1, "error": [403, "Forbidden"]}, "authentication_required"),
            ({"id": 1, "error": [501, "Not implemented"]}, "api_error"),
            ({"id": 1, "result": [{}]}, "http_response"),
            ({"id": 2, "result": [{"status": "active"}]}, "http_response"),
        ):
            with self.subTest(body=body):
                self.assertEqual(
                    expected, sony_response(200, json.dumps(body).encode())
                )
