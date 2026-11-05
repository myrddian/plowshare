"""Installed-runtime boundary. No model kit, generator or weights are loaded."""

from __future__ import annotations

import base64
import hashlib
import importlib.metadata
import io
import json
import os
import re
import sys
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol
from urllib.parse import urlsplit, urlunsplit

from contract import (
    Count,
    CountRequest,
    ImagePart,
    Refused,
    TextPart,
    object_fields,
    text,
)


def digest(value):
    return hashlib.sha256(
        json.dumps(
            value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode()
    ).hexdigest()


@dataclass(frozen=True)
class Settings:
    listen_host: str
    listen_port: int
    model: str
    model_path: Path
    engine_manifest: Path
    upstream_ws_url: str
    token: str = field(repr=False)
    enable_thinking: bool
    revision: str | None

    @classmethod
    def parse(cls, raw):
        value = object_fields(
            raw,
            {
                "listen_host",
                "listen_port",
                "model",
                "model_path",
                "engine_manifest",
                "upstream_ws_url",
                "token_env",
                "enable_thinking",
                "revision",
            },
            {
                "listen_host",
                "listen_port",
                "model",
                "model_path",
                "engine_manifest",
                "upstream_ws_url",
                "token_env",
                "enable_thinking",
            },
        )
        host = text(value["listen_host"], 253, identifier=True)
        port = value["listen_port"]
        if type(port) is not int or not 0 <= port <= 65535:
            raise Refused("invalid_listen_port")
        paths = [
            Path(text(value[key], 4096, identifier=True))
            for key in ["model_path", "engine_manifest"]
        ]
        if (
            any(not p.is_absolute() for p in paths)
            or not paths[0].is_dir()
            or not paths[1].is_file()
        ):
            raise Refused("explicit_existing_paths_required")
        url = text(value["upstream_ws_url"], 2048, identifier=True)
        parsed = urlsplit(url)
        if (
            parsed.scheme not in ("ws", "wss")
            or not parsed.hostname
            or parsed.path != "/llm"
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
        ):
            raise Refused("invalid_upstream_ws_url")
        env = text(value["token_env"], 256, identifier=True)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", env):
            raise Refused("invalid_token_env")
        token = os.environ.get(env, "")
        if not re.fullmatch(r"sk-lm-[A-Za-z0-9]{8}:[A-Za-z0-9]{20}", token):
            raise Refused("configured_lmstudio_token_required")
        thinking = value["enable_thinking"]
        if type(thinking) is not bool:
            raise Refused("invalid_enable_thinking")
        revision = value.get("revision")
        if revision is not None and (
            not isinstance(revision, str) or not re.fullmatch("[a-f0-9]{64}", revision)
        ):
            raise Refused("invalid_revision")
        return cls(
            host,
            port,
            text(value["model"], 1024, identifier=True),
            paths[0].resolve(),
            paths[1].resolve(),
            url,
            token,
            thinking,
            revision,
        )


@dataclass(frozen=True)
class Binding:
    template: str
    model_path: str
    engine_name: str
    engine_version: str
    config_digest: str
    instance: str


class BindingReader(Protocol):
    """Read-only snapshot of the *loaded* model and effective server configuration."""

    def read(self) -> Binding: ...
    def render_probe(self) -> str: ...


class LmStudioBindingReader:
    """Small published SDK RPC projection; never calls getOrLoad/predict/upload.

    Each exchange owns its connection, a two-second total deadline and no retry.
    The token uses the same authentication handshake as Plowshare's LM Studio
    transport. Raw provider JSON and exception text stay inside this boundary.
    """

    def __init__(self, settings: Settings):
        self.settings = settings

    def exchange(self, calls, namespace="llm"):
        import asyncio
        import aiohttp

        async def call():
            match = re.fullmatch(
                r"sk-lm-([A-Za-z0-9]{8}):([A-Za-z0-9]{20})", self.settings.token
            )
            url = urlsplit(self.settings.upstream_ws_url)
            target = urlunsplit((url.scheme, url.netloc, "/" + namespace, "", ""))
            # aiohttp is already bundled with the selected MLX environment.
            # The outer deadline also bounds receive/control frames and closure.
            async with asyncio.timeout(2):
                async with aiohttp.ClientSession(
                    timeout=aiohttp.ClientTimeout(total=2), trust_env=False
                ) as session:
                    async with session.ws_connect(
                        target, max_msg_size=1024 * 1024
                    ) as socket:
                        await socket.send_json(
                            {
                                "authVersion": 1,
                                "clientIdentifier": match[1],
                                "clientPasskey": match[2],
                            }
                        )
                        if (await socket.receive_json()).get("success") is not True:
                            raise Refused("binding_authentication_refused")
                        results = []
                        for index, (endpoint, parameter) in enumerate(calls, 1):
                            await socket.send_json(
                                {
                                    "type": "rpcCall",
                                    "endpoint": endpoint,
                                    "callId": index,
                                    "parameter": parameter,
                                }
                            )
                            response = await socket.receive_json()
                            if (
                                response.get("type") != "rpcResult"
                                or response.get("callId") != index
                            ):
                                raise Refused("binding_rpc_refused")
                            results.append(response.get("result"))
                        return results

        try:
            return asyncio.run(call())
        except Refused:
            raise
        except Exception as error:
            raise Refused("binding_unavailable") from error

    def read(self):
        specifier = {"type": "query", "query": {"identifier": self.settings.model}}
        try:
            info, config = self.exchange(
                [
                    ("getModelInfo", {"specifier": specifier, "throwIfNotFound": True}),
                    ("getBasePredictionConfig", {"specifier": specifier}),
                ]
            )
            if info["identifier"] != self.settings.model or info["vision"] is not True:
                raise Refused("loaded_model_mismatch")
            path = text(info["path"], 4096, identifier=True)
            if (
                Path(path).is_absolute()
                or ".." in Path(path).parts
                or tuple(self.settings.model_path.parts[-len(Path(path).parts) :])
                != Path(path).parts
            ):
                raise Refused("loaded_model_path_mismatch")
            fields = {item["key"]: item["value"] for item in config["fields"]}
            if len(fields) != len(config["fields"]):
                raise Refused("invalid_binding")
            template = text(
                fields["llm.prediction.promptTemplate"]["jinjaPromptTemplate"][
                    "template"
                ]
            )
            manifest = json.loads(self.settings.engine_manifest.read_text())
            if manifest["engine"] != "mlx-llm":
                raise Refused("mlx_manifest_required")
            (selections,) = self.exchange(
                [
                    (
                        "getEngineSelections",
                        {"json": None, "meta": {"values": ["undefined"], "v": 1}},
                    )
                ],
                "runtime",
            )
            selected = dict(selections["json"])["MLX"]
            if (selected["name"], selected["version"]) != (
                manifest["name"],
                manifest["version"],
            ):
                raise Refused("selected_engine_mismatch")
            return Binding(
                template,
                path,
                text(manifest["name"], 256, identifier=True),
                text(manifest["version"], 256, identifier=True),
                digest(config),
                text(info["instanceReference"], 1024, identifier=True),
            )
        except Refused:
            raise
        except (ValueError, OSError, KeyError, TypeError) as error:
            raise Refused("invalid_binding") from error

    def render_probe(self):
        (result,) = self.exchange(
            [
                (
                    "applyPromptTemplate",
                    {
                        "specifier": {
                            "type": "query",
                            "query": {"identifier": self.settings.model},
                        },
                        "history": {
                            "messages": [
                                {
                                    "role": "user",
                                    "content": [
                                        {
                                            "type": "text",
                                            "text": "Count readiness probe.",
                                        }
                                    ],
                                }
                            ]
                        },
                        "predictionConfigStack": {"layers": []},
                        "opts": {},
                    },
                )
            ]
        )
        try:
            return text(result["formatted"])
        except (KeyError, TypeError) as error:
            raise Refused("invalid_template_probe") from error


class MlxCounter:
    """One warm processor; one request at a time, without a second model runtime.

    Revision binds metadata bytes, installed processor/engine code, package
    versions and live serving settings. File replacement or live binding changes
    fail closed. Java disables its response cache for this protocol so every
    admission receives these freshness checks. There is no count-side media store.
    """

    def __init__(self, settings: Settings, reader: BindingReader):
        self.settings, self.reader = settings, reader
        self.lock = threading.Lock()
        sys.dont_write_bytecode = True
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        os.environ["TOKENIZERS_PARALLELISM"] = "false"

        # An accidental future model loader must not turn this into inference.
        def no_weights(event, args):
            if (
                event == "open"
                and isinstance(args[0], (str, bytes))
                and os.fsdecode(args[0]).endswith(".safetensors")
            ):
                raise Refused("weight_access_forbidden")

        sys.addaudithook(no_weights)
        import mlx.core as mx
        import mlx_vlm.utils as utils
        from mlx_lm.tokenizer_utils import load
        from mlx_engine.model_kit.batched_vision.prompt_inputs import (
            prepare_prompt_inputs,
        )
        from PIL import Image

        mx.set_default_device(mx.cpu)
        self.image_library, self.prepare = Image, prepare_prompt_inputs
        self.config = utils.load_config(
            settings.model_path, trust_remote_code=False, local_files_only=True
        )
        if self.config.get("model_type") != "gemma4":
            raise Refused("unsupported_model_architecture")
        eos = self.config.get("eos_token_id") or self.config.get("text_config", {}).get(
            "eos_token_id"
        )
        self.tokenizer = load(
            settings.model_path,
            tokenizer_config_extra={
                "local_files_only": True,
                "trust_remote_code": False,
            },
            eos_token_ids=eos,
        )
        self.processor = utils.load_processor(
            settings.model_path,
            True,
            eos_token_ids=eos,
            trust_remote_code=False,
            local_files_only=True,
        )
        image_processor = utils.load_image_processor(
            settings.model_path, trust_remote_code=False, local_files_only=True
        )
        if image_processor is not None:
            self.processor.image_processor = image_processor
        self.binding = reader.read()
        paths = [
            settings.model_path / name
            for name in [
                "config.json",
                "generation_config.json",
                "tokenizer.json",
                "tokenizer_config.json",
                "processor_config.json",
                "chat_template.jinja",
            ]
        ]
        paths += [
            settings.engine_manifest,
            Path(__file__),
            Path(__file__).with_name("contract.py"),
        ]
        for module_name in [
            "mlx_engine.generate",
            "mlx_engine.model_kit.batched_vision.model_kit",
            "mlx_engine.model_kit.batched_vision.prompt_inputs",
            "mlx_engine.utils.image_utils",
            "mlx_vlm.utils",
            "mlx_vlm.models.gemma4.processing_gemma4",
            "mlx_lm.tokenizer_utils",
        ]:
            module = __import__(module_name, fromlist=["__file__"])
            paths.append(Path(module.__file__))
        self.files = tuple(paths)
        self.stamps = self.file_stamps()
        self.revision = digest(
            {
                "contract": 1,
                "files": [hashlib.sha256(p.read_bytes()).hexdigest() for p in paths],
                "packages": {
                    name: importlib.metadata.version(name)
                    for name in [
                        "mlx",
                        "mlx-vlm",
                        "mlx-lm",
                        "transformers",
                        "tokenizers",
                        "pillow",
                        "aiohttp",
                    ]
                },
                "model": settings.model,
                "binding": self.binding_key(self.binding),
                "enable_thinking": settings.enable_thinking,
            }
        )
        if settings.revision is not None and settings.revision != self.revision:
            raise Refused("revision_mismatch")
        probe = self.render(
            [{"role": "user", "content": "Count readiness probe."}], [], None
        )
        if probe != reader.render_probe():
            raise Refused("default_template_settings_mismatch")

    def file_stamps(self):
        return tuple(
            (p.stat().st_ino, p.stat().st_size, p.stat().st_mtime_ns)
            for p in self.files
        )

    @staticmethod
    def binding_key(binding):
        # Reloading the identical artifacts need not change the revision, but a
        # reload during one count is separately refused by the instance fence.
        return (
            binding.template,
            binding.model_path,
            binding.engine_name,
            binding.engine_version,
            binding.config_digest,
        )

    def render(self, messages, tools, thinking):
        return self.tokenizer._tokenizer.apply_chat_template(
            messages,
            chat_template=self.binding.template,
            tools=tools,
            enable_thinking=(
                self.settings.enable_thinking if thinking is None else thinking
            ),
            tokenize=False,
            add_generation_prompt=True,
        )

    def count(self, request: CountRequest) -> Count:
        if request.model != self.settings.model:
            raise Refused("model_mismatch")
        if not self.lock.acquire(blocking=False):
            raise Refused("counter_busy")
        try:
            before = self.reader.read()
            if self.file_stamps() != self.stamps or self.binding_key(
                before
            ) != self.binding_key(self.binding):
                raise Refused("binding_changed")
            messages, images, tools = [], [], []
            for message in request.messages:
                parts = []
                for part in message.parts:
                    if isinstance(part, TextPart):
                        parts.append({"type": "text", "text": part.value})
                    else:
                        with self.image_library.open(io.BytesIO(part.data)) as image:
                            expected = {
                                "image/png": "PNG",
                                "image/jpeg": "JPEG",
                                "image/webp": "WEBP",
                            }[part.mime]
                            if (
                                image.format != expected
                                or getattr(image, "n_frames", 1) != 1
                                or max(image.size) > 8192
                                or image.width * image.height > 16 * 1024 * 1024
                            ):
                                raise Refused("unsupported_image_layout")
                            image.verify()
                        parts.append({"type": "image"})
                        images.append(base64.b64encode(part.data).decode("ascii"))
                content = (
                    parts
                    if message.array_content
                    else "".join(p["text"] for p in parts)
                )
                wire = {"role": message.role, "content": content}
                if message.calls:
                    wire["tool_calls"] = [
                        {
                            "id": call.identifier,
                            "type": "function",
                            "function": {
                                "name": call.name,
                                "arguments": json.loads(call.arguments_json),
                            },
                        }
                        for call in message.calls
                    ]
                if message.tool_call_id:
                    wire.update(tool_call_id=message.tool_call_id, name=message.name)
                messages.append(wire)
            for tool in request.tools:
                tools.append(
                    {
                        "type": "function",
                        "function": {
                            "name": tool.name,
                            "description": tool.description,
                            "parameters": json.loads(tool.parameters_json),
                        },
                    }
                )
            prompt = self.render(messages, tools, request.enable_thinking)
            tokens = self.tokenizer.convert_tokens_to_ids(
                self.tokenizer.tokenize(prompt)
            )
            prepared = self.prepare(
                prompt_tokens=tokens,
                images_b64=images,
                tokenizer=self.tokenizer,
                processor=self.processor,
                config=self.config,
            )
            after = self.reader.read()
            if before != after or self.file_stamps() != self.stamps:
                raise Refused("binding_changed_during_count")
            return Count(request.model, self.revision, len(prepared.prompt_input_ids))
        except Refused:
            raise
        except Exception as error:
            raise Refused("processor_unavailable") from error
        finally:
            self.lock.release()
