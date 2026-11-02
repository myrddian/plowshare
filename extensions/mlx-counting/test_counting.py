"""Default tests use standard-library fakes; no MLX, model or database needed."""

import base64
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from contract import Count, ImagePart, Refused, parse_request
from runtime import Binding, LmStudioBindingReader, MlxCounter, Settings
from service import CountServer


class ParserTest(unittest.TestCase):
    def body(self, **fields):
        return json.dumps(
            {
                "model": "vision-fixture",
                "messages": [{"role": "user", "content": "question"}],
                **fields,
            }
        ).encode()

    def test_images_remain_ordered_immutable_bytes_and_repeated_occurrences_survive(
        self,
    ):
        for mime in ["image/png", "image/jpeg", "image/webp"]:
            image = {
                "type": "image_url",
                "image_url": {"url": "data:" + mime + ";base64,AQID"},
            }
            request = parse_request(
                self.body(
                    messages=[
                        {
                            "role": "user",
                            "content": [
                                image,
                                {"type": "text", "text": "middle"},
                                image,
                            ],
                        }
                    ]
                )
            )
            self.assertEqual(
                (request.messages[0].parts[0], request.messages[0].parts[2]),
                (ImagePart(mime, b"\x01\x02\x03"),) * 2,
            )
            self.assertTrue(request.messages[0].array_content)

    def test_tool_arguments_are_parsed_and_correlated_without_losing_system_shape(self):
        request = parse_request(
            self.body(
                messages=[
                    {"role": "system", "content": "instructions"},
                    {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "c1",
                                "type": "function",
                                "function": {
                                    "name": "inspect",
                                    "arguments": '{"size":256}',
                                },
                            }
                        ],
                    },
                    {"role": "tool", "content": "result", "tool_call_id": "c1"},
                ]
            )
        )
        self.assertFalse(request.messages[0].array_content)
        self.assertEqual("inspect", request.messages[2].name)
        self.assertEqual('{"size":256}', request.messages[1].calls[0].arguments_json)

    def test_unsupported_prompt_fields_and_malformed_values_fail_before_counting(self):
        for fields in [
            {"reasoning_effort": "low"},
            {"response_format": {"type": "json_object"}},
            {"tool_choice": "required"},
            {"chat_template_kwargs": {"unknown": True}},
            {"chat_template_kwargs": {"enable_thinking": 1}},
            {"temperature": True},
            {"max_tokens": -1},
            {"stream": True},
            {"messages": []},
            {"messages": [{"role": "tool", "content": "x", "tool_call_id": "missing"}]},
            {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": "https://invalid.example/image.png"
                                },
                            }
                        ],
                    }
                ]
            },
            {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "image_url",
                                "image_url": {"url": "data:image/png;base64,?"},
                            }
                        ],
                    }
                ]
            },
        ]:
            with self.subTest(fields=fields), self.assertRaises(Refused):
                parse_request(self.body(**fields))
        for raw in [
            b'{"model":"one","model":"two","messages":[]}',
            b'{"model":NaN}',
            b"{",
        ]:
            with self.assertRaises(Refused):
                parse_request(raw)

    def test_schema_complexity_and_image_occurrences_are_bounded(self):
        image = {
            "type": "image_url",
            "image_url": {"url": "data:image/png;base64,AQID"},
        }
        with self.assertRaises(Refused):
            parse_request(
                self.body(messages=[{"role": "user", "content": [image] * 17}])
            )
        deep = {}
        for _ in range(30):
            deep = {"nested": deep}
        with self.assertRaises(Refused):
            parse_request(
                self.body(
                    tools=[
                        {
                            "type": "function",
                            "function": {"name": "x", "parameters": deep},
                        }
                    ]
                )
            )


class FakeCounter:
    def __init__(self):
        self.requests = []
        self.failure = None

    def count(self, request):
        self.requests.append(request)
        if self.failure:
            raise self.failure
        return Count(request.model, "a" * 64, 272)


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.counter = FakeCounter()
        self.server = CountServer(("127.0.0.1", 0), self.counter, "fixture-secret")
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.body = json.dumps(
            {"model": "fixture", "messages": [{"role": "user", "content": "x"}]}
        ).encode()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def post(self, body=None, headers=None, path="/v1/chat/completions/input_tokens"):
        client = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        client.request(
            "POST",
            path,
            self.body if body is None else body,
            headers
            or {
                "Authorization": "Bearer fixture-secret",
                "Content-Type": "application/json",
            },
        )
        response = client.getresponse()
        result = response.status, json.loads(response.read())
        client.close()
        return result

    def test_success_is_a_typed_revision_bound_count_without_content(self):
        status, value = self.post()
        self.assertEqual(200, status)
        self.assertEqual(
            {
                "object": "response.input_tokens",
                "model": "fixture",
                "revision": "a" * 64,
                "input_tokens": 272,
            },
            value,
        )

    def test_authentication_and_boundary_validation_precede_counter(self):
        self.assertEqual(
            401, self.post(headers={"Content-Type": "application/json"})[0]
        )
        self.assertEqual(404, self.post(path="/v1/chat/completions")[0])
        self.assertEqual(
            422, self.post(body=b'{"model":"x","messages":[],"extra":"secret"}')[0]
        )
        self.assertEqual(
            400,
            self.post(
                headers={
                    "Authorization": "Bearer fixture-secret",
                    "Content-Type": "text/plain",
                }
            )[0],
        )
        self.assertEqual([], self.counter.requests)

    def test_failure_never_returns_zero_or_provider_exception_text(self):
        self.counter.failure = RuntimeError("private fixture provider text")
        self.assertEqual((503, {"error": "counter_unavailable"}), self.post())
        self.counter.failure = Refused("binding_changed")
        self.assertEqual((422, {"error": "binding_changed"}), self.post())

    def test_connection_capacity_is_enforced_before_thread_creation(self):
        self.server.slots.acquire()
        self.server.slots.acquire()
        try:
            client = http.client.HTTPConnection(*self.server.server_address, timeout=2)
            client.request("POST", "/v1/chat/completions/input_tokens", self.body)
            self.assertEqual(503, client.getresponse().status)
            client.close()
            self.assertEqual([], self.counter.requests)
        finally:
            self.server.slots.release()
            self.server.slots.release()


class BindingFenceTest(unittest.TestCase):
    def test_changed_binding_refuses_before_processor_and_lock_is_released(self):
        # Instantiate without vendor imports: exercise the real freshness boundary.
        counter = object.__new__(MlxCounter)
        counter.settings = type("Settings", (), {"model": "fixture"})()
        counter.lock = threading.Lock()
        counter.binding = Binding(
            "template", "model", "mlx", "one", "digest", "instance"
        )
        counter.reader = type(
            "Reader",
            (),
            {
                "read": lambda _: Binding(
                    "changed", "model", "mlx", "one", "digest", "instance"
                )
            },
        )()
        counter.files = ()
        counter.stamps = ()
        request = parse_request(
            json.dumps(
                {"model": "fixture", "messages": [{"role": "user", "content": "x"}]}
            ).encode()
        )
        for _ in range(2):
            with self.assertRaisesRegex(Refused, "binding_changed"):
                counter.count(request)
        self.assertTrue(counter.lock.acquire(blocking=False))
        counter.lock.release()


class SettingsAndBindingTest(unittest.TestCase):
    def test_settings_require_explicit_paths_and_configured_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "publisher" / "model"
            model.mkdir(parents=True)
            manifest = root / "manifest.json"
            manifest.write_text(
                json.dumps(
                    {"engine": "mlx-llm", "name": "fixture-engine", "version": "one"}
                )
            )
            value = {
                "listen_host": "127.0.0.1",
                "listen_port": 0,
                "model": "fixture",
                "model_path": str(model),
                "engine_manifest": str(manifest),
                "upstream_ws_url": "ws://fixture.invalid/llm",
                "token_env": "FIXTURE_MLX_TOKEN",
                "enable_thinking": False,
            }
            credential = "sk" + "-lm-" + "A" * 8 + ":" + "B" * 20
            with patch.dict("os.environ", {"FIXTURE_MLX_TOKEN": credential}):
                settings = Settings.parse(value)
                self.assertNotIn(credential, repr(settings))
                for extra in [
                    {"model_path": "relative"},
                    {"listen_port": -1},
                    {"upstream_ws_url": "ws://user:secret@fixture.invalid/llm"},
                    {"enable_thinking": "false"},
                    {"revision": "unverified"},
                ]:
                    with self.subTest(extra=extra), self.assertRaises(Refused):
                        Settings.parse({**value, **extra})
                reader = LmStudioBindingReader(settings)
                info = {
                    "identifier": "fixture",
                    "vision": True,
                    "path": "publisher/model",
                    "instanceReference": "instance-one",
                }
                config = {
                    "fields": [
                        {
                            "key": "llm.prediction.promptTemplate",
                            "value": {
                                "jinjaPromptTemplate": {"template": "fixture template"}
                            },
                        }
                    ]
                }
                selected = {
                    "json": [["MLX", {"name": "fixture-engine", "version": "one"}]]
                }
                reader.exchange = lambda calls, namespace="llm": (
                    [selected] if namespace == "runtime" else [info, config]
                )
                self.assertEqual("publisher/model", reader.read().model_path)
                selected["json"][0][1]["version"] = "changed"
                with self.assertRaisesRegex(Refused, "selected_engine_mismatch"):
                    reader.read()
            with patch.dict("os.environ", {"FIXTURE_MLX_TOKEN": ""}):
                with self.assertRaisesRegex(
                    Refused, "configured_lmstudio_token_required"
                ):
                    Settings.parse(value)

    def test_count_dto_rejects_invalid_numeric_values(self):
        for value in [-1, True, 1.5, 2**63]:
            with self.assertRaises(Refused):
                Count("fixture", "a" * 64, value)


if __name__ == "__main__":
    unittest.main()
