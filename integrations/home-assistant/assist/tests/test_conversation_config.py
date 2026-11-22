"""Profile routing and retained configuration-probe ownership contracts."""

import asyncio
import gc
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import support  # noqa: F401
from custom_components.plowshare._vendor.plowshare import Refusal
from homeassistant.config_entries import ConfigEntries
from homeassistant.core import HomeAssistant

from custom_components.plowshare.config import configured_profiles, settings_from_data
from custom_components.plowshare.config_flow import PlowshareConfigFlow
from custom_components.plowshare.const import MAX_VALIDATION_PROBES
from custom_components.plowshare.validation import (
    ValidationBusyError,
    ValidationProbePool,
    ValidationUnavailableError,
)

DATA = {
    "url": "https://plowshare.example.invalid",
    "api_key": "fixture-token",
    "project": "house",
    "read_only_agent": "house_reader",
    "agent": "house_controller",
    "task_agent": "supplied_data",
}


class ConfigurationTests(unittest.TestCase):
    def test_enabled_profiles_never_inherit_another_agents_route(self):
        profiles = configured_profiles({**DATA, "ai_task_enabled": True})
        self.assertEqual(profiles.conversation.agent, "house_reader")
        self.assertEqual(profiles.task.agent, "supplied_data")
        self.assertEqual(profiles.required_agents, ("house_reader", "supplied_data"))
        self.assertFalse(profiles.conversation_options.control_enabled)
        control = configured_profiles({**DATA, "control_enabled": True})
        self.assertEqual(control.conversation.agent, "house_controller")
        self.assertEqual(control.required_agents, ("house_controller",))
        task_only = configured_profiles(
            {
                "url": DATA["url"],
                "api_key": DATA["api_key"],
                "project": DATA["project"],
                "conversation_enabled": False,
                "ai_task_enabled": True,
                "task_agent": "supplied_data",
            }
        )
        self.assertIsNone(task_only.conversation)
        self.assertEqual(task_only.primary.agent, "supplied_data")
        self.assertEqual(
            settings_from_data({**DATA, "ai_task_enabled": True}, "task").agent,
            "supplied_data",
        )

    def test_disabled_profiles_and_missing_explicit_agent_do_not_fall_back(self):
        cases = [
            {key: value for key, value in DATA.items() if key != "read_only_agent"},
            {**DATA, "control_enabled": True, "agent": None},
            {**DATA, "ai_task_enabled": True, "task_agent": None},
            {**DATA, "conversation_enabled": False, "ai_task_enabled": False},
        ]
        for data in cases:
            with self.subTest(data=data), self.assertRaises(ValueError):
                configured_profiles(data)
        with self.assertRaises(ValueError):
            settings_from_data(DATA, "task")

    def test_malformed_configuration_rejects_every_present_field(self):
        for key, value in (
            ("conversation_enabled", 1),
            ("ai_task_enabled", "true"),
            ("control_enabled", None),
            ("allow_anonymous", 1),
            ("request_timeout", True),
            ("response_timeout", float("inf")),
            ("task_agent", []),
            ("prompt", ""),
            ("prompt", "\ud800"),
            ("prompt", "bad\x00template"),
            ("llm_api", "assist"),
            ("unknown", "not supported"),
        ):
            with self.subTest(key=key), self.assertRaises(ValueError):
                configured_profiles({**DATA, key: value})

    def test_prompt_template_whitespace_and_unicode_are_not_normalized(self):
        prompt = "  λ {{ user_name }}\nsecond line  "
        self.assertEqual(
            configured_profiles({**DATA, "prompt": prompt}).conversation_options.prompt,
            prompt,
        )
        same = configured_profiles(
            {**DATA, "ai_task_enabled": True, "task_agent": "house_reader"}
        )
        self.assertEqual(same.required_agents, ("house_reader",))


class Probe:
    def __init__(self, *, closed=True, failure=None, blocked=None):
        self.closed = closed
        self.failure = failure
        self.blocked = blocked
        self.close_blocked = None
        self.close_failure = None
        self.names = []
        self.close_calls = 0
        self.entered = asyncio.Event()

    async def validate(self, required_agents=()):
        self.names.append(required_agents)
        self.entered.set()
        if self.blocked is not None:
            await self.blocked.wait()
        if self.failure is not None:
            raise self.failure

    async def async_close(self):
        self.close_calls += 1
        if self.close_blocked is not None:
            await self.close_blocked.wait()
        if self.close_failure is not None:
            raise self.close_failure
        return self.closed


class ProbePoolTests(unittest.IsolatedAsyncioTestCase):
    async def test_success_checks_all_agents_and_releases_only_settled_owner(self):
        pool = ValidationProbePool()
        probe = Probe()
        profiles = configured_profiles({**DATA, "ai_task_enabled": True})
        await pool.async_validate(
            profiles.primary, profiles.required_agents, lambda *_: probe
        )
        self.assertEqual(probe.names, [("house_reader", "supplied_data")])
        self.assertEqual(probe.close_calls, 1)
        self.assertEqual(len(pool._owners), 0)

    async def test_false_cleanup_retains_bounded_owners_and_prevents_success(self):
        pool = ValidationProbePool()
        created = []

        def factory(*_):
            probe = Probe(closed=False)
            created.append(probe)
            return probe

        profiles = configured_profiles(DATA)
        for _ in range(MAX_VALIDATION_PROBES):
            with self.assertRaises(ValidationUnavailableError):
                await pool.async_validate(
                    profiles.primary, profiles.required_agents, factory
                )
        with self.assertRaises(ValidationBusyError):
            await pool.async_validate(
                profiles.primary, profiles.required_agents, factory
            )
        self.assertEqual(len(created), MAX_VALIDATION_PROBES)
        self.assertEqual(len(pool._owners), MAX_VALIDATION_PROBES)
        for probe in created:
            probe.closed = True
        await pool.async_validate(
            profiles.primary, profiles.required_agents, factory=lambda *_: Probe()
        )
        self.assertEqual(len(pool._owners), 0)

    async def test_another_flow_cannot_close_a_still_validating_owner(self):
        pool = ValidationProbePool()
        release = asyncio.Event()
        first = Probe(blocked=release)
        profiles = configured_profiles(DATA)
        pending = asyncio.create_task(
            pool.async_validate(
                profiles.primary, profiles.required_agents, lambda *_: first
            )
        )
        await asyncio.wait_for(first.entered.wait(), 1)
        await pool.async_validate(
            profiles.primary, profiles.required_agents, lambda *_: Probe()
        )
        self.assertEqual(first.close_calls, 0)
        release.set()
        await asyncio.wait_for(pending, 1)
        self.assertEqual(first.close_calls, 1)

    async def test_failed_or_cancelled_probe_still_retains_unclosed_owner(self):
        profiles = configured_profiles(DATA)
        pool = ValidationProbePool()
        failed = Probe(closed=False, failure=ValueError("fixture invalid agent"))
        with self.assertRaises(ValueError):
            await pool.async_validate(
                profiles.primary, profiles.required_agents, lambda *_: failed
            )
        self.assertEqual(len(pool._owners), 1)
        blocked = Probe(closed=False, blocked=asyncio.Event())
        pending = asyncio.create_task(
            pool.async_validate(
                profiles.primary, profiles.required_agents, lambda *_: blocked
            )
        )
        await asyncio.wait_for(blocked.entered.wait(), 1)
        pending.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await pending
        self.assertEqual(len(pool._owners), 2)

    async def test_stalled_close_has_finite_wait_and_retains_its_owner(self):
        profiles = configured_profiles(DATA)
        pool = ValidationProbePool()
        probe = Probe()
        probe.close_blocked = asyncio.Event()
        with patch("custom_components.plowshare.validation.CLEANUP_TIMEOUT", 0.02):
            with self.assertRaises(ValidationUnavailableError):
                await asyncio.wait_for(
                    pool.async_validate(
                        profiles.primary, profiles.required_agents, lambda *_: probe
                    ),
                    1,
                )
            await asyncio.sleep(0)
        self.assertEqual(len(pool._owners), 1)
        probe.close_blocked.set()
        await pool.async_validate(
            profiles.primary, profiles.required_agents, lambda *_: Probe()
        )
        self.assertEqual(len(pool._owners), 0)

    async def test_stale_failed_callback_is_observed_without_releasing_new_owner(self):
        pool = ValidationProbePool()
        profiles = configured_profiles(DATA)
        with self.assertRaises(ValidationUnavailableError):
            await pool.async_validate(
                profiles.primary,
                profiles.required_agents,
                lambda *_: Probe(closed=False),
            )
        identity, record = next(iter(pool._owners.items()))
        observed = []
        loop = asyncio.get_running_loop()
        previous_handler = loop.get_exception_handler()
        loop.set_exception_handler(
            lambda _loop, context: observed.append(context["message"])
        )

        async def fail():
            raise OSError("fixture old close failed")

        try:
            failed = asyncio.create_task(fail())
            await asyncio.sleep(0)
            newer = asyncio.create_task(asyncio.sleep(0, result=False))
            record.close_task = newer
            pool._closed(identity, failed)
            self.assertIs(pool._owners[identity], record)
            self.assertIs(record.close_task, newer)
            del failed
            gc.collect()
            await asyncio.sleep(0)
            await newer
            self.assertEqual(observed, [])
        finally:
            loop.set_exception_handler(previous_handler)

    async def test_failed_cleanup_after_wait_expiry_has_no_unobserved_future(self):
        profiles = configured_profiles(DATA)
        pool = ValidationProbePool()
        probe = Probe()
        probe.close_blocked = asyncio.Event()
        observed = []
        loop = asyncio.get_running_loop()
        previous_handler = loop.get_exception_handler()
        loop.set_exception_handler(
            lambda _loop, context: observed.append(context["message"])
        )
        try:
            with patch("custom_components.plowshare.validation.CLEANUP_TIMEOUT", 0.01):
                with self.assertRaises(ValidationUnavailableError):
                    await pool.async_validate(
                        profiles.primary, profiles.required_agents, lambda *_: probe
                    )
                await asyncio.sleep(0)
                await asyncio.sleep(0)
            self.assertEqual(len(pool._owners), 1)
            self.assertEqual(observed, [])
        finally:
            probe.close_blocked.set()
            loop.set_exception_handler(previous_handler)

    async def test_probe_cannot_persist_a_submission_intent(self):
        pool = ValidationProbePool()
        profiles = configured_profiles(DATA)
        saved = []

        def factory(settings, sessions, save):
            saved.append(save)
            return Probe()

        await pool.async_validate(profiles.primary, profiles.required_agents, factory)
        with self.assertRaisesRegex(RuntimeError, "cannot persist"):
            await saved[0]()


class ConfigFlowTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)
        self.hass.config_entries = ConfigEntries(self.hass, {})

    async def asyncTearDown(self):
        await self.hass.async_stop()
        self.directory.cleanup()

    def flow(self):
        flow = PlowshareConfigFlow()
        flow.hass = self.hass
        flow.context = {"source": "user"}
        return flow

    async def test_actual_user_flow_creates_only_after_one_all_profile_validation(self):
        probe = Probe()
        with (
            patch(
                "custom_components.plowshare.config_flow.SdkConversationAgent",
                return_value=probe,
            ) as factory,
        ):
            result = await self.flow().async_step_user(
                {**DATA, "ai_task_enabled": True}
            )
        self.assertEqual(result["type"], "create_entry")
        self.assertEqual(probe.names, [("house_reader", "supplied_data")])
        self.assertEqual(probe.close_calls, 1)
        factory.assert_called_once()

    async def test_failed_cleanup_prevents_create_entry_and_is_retained_across_flows(
        self,
    ):
        created = []

        def factory(*_):
            probe = Probe(closed=False)
            created.append(probe)
            return probe

        with (
            patch(
                "custom_components.plowshare.config_flow.SdkConversationAgent",
                side_effect=factory,
            ),
        ):
            for _ in range(MAX_VALIDATION_PROBES):
                result = await self.flow().async_step_user(DATA)
                self.assertEqual(result["type"], "form")
                self.assertEqual(result["errors"], {"base": "cannot_connect"})
            full = await self.flow().async_step_user(DATA)
        self.assertEqual(full["errors"], {"base": "validation_busy"})
        self.assertEqual(len(created), MAX_VALIDATION_PROBES)

    async def test_service_refusals_are_not_misreported_as_authentication_failure(self):
        for step in ("user", "reconfigure"):
            for code, expected in (
                ("CAPACITY_EXHAUSTED", "validation_busy"),
                ("ARCHIVE_UNAVAILABLE", "cannot_connect"),
                ("CONFIG_UNAVAILABLE", "cannot_connect"),
                ("BAD_REQUEST", "cannot_connect"),
                ("NOT_FOUND", "cannot_connect"),
            ):
                with self.subTest(step=step, code=code):
                    probe = Probe(failure=Refusal(code, "fixture refusal"))
                    flow = self.flow()
                    with (
                        patch(
                            "custom_components.plowshare.config_flow.SdkConversationAgent",
                            return_value=probe,
                        ),
                        patch.object(
                            flow,
                            "_get_reconfigure_entry",
                            return_value=SimpleNamespace(data=DATA),
                        ),
                    ):
                        result = await getattr(flow, "async_step_" + step)(DATA)
                    self.assertEqual(result["errors"], {"base": expected})
                    self.assertEqual(probe.close_calls, 1)

    async def test_bad_profile_or_tool_option_refuses_before_constructor(self):
        with (
            patch(
                "custom_components.plowshare.config_flow.SdkConversationAgent"
            ) as sdk,
        ):
            result = await self.flow().async_step_user({**DATA, "llm_api": "assist"})
        self.assertEqual(result["errors"], {"base": "invalid_config"})
        sdk.assert_not_called()
