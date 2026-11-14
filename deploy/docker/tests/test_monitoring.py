"""Check database and server telemetry wiring without Docker, a database or a real JVM."""

import json
import os
import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MONITORING = ROOT.parents[0] / "monitoring"


def service_block(name):
    """Return the text of one top-level compose service (indent-based, no YAML dependency)."""
    lines = (ROOT / "compose.yaml").read_text().splitlines()
    start = lines.index(f"  {name}:")
    block = []
    for line in lines[start + 1 :]:
        if line and not line.startswith("    "):
            break
        block.append(line)
    return "\n".join(block)


class ComposeTelemetryTests(unittest.TestCase):
    def test_postgres_preloads_statement_statistics_and_auto_explain(self):
        command = service_block("postgres")
        self.assertIn("shared_preload_libraries=pg_stat_statements,auto_explain", command)
        self.assertIn("track_io_timing=on", command)
        self.assertIn("pg_stat_statements.track=top", command)

    def test_statement_logging_is_off_by_default_and_never_logs_parameters(self):
        command = service_block("postgres")
        self.assertIn("log_min_duration_statement=${PLOWSHARE_PG_SLOW_MS:--1}", command)
        self.assertIn("auto_explain.log_min_duration=${PLOWSHARE_PG_AUTO_EXPLAIN_MS:--1}", command)
        for setting in (
            "log_parameter_max_length=0",
            "log_parameter_max_length_on_error=0",
            "auto_explain.log_parameter_max_length=0",
        ):
            self.assertRegex(command, rf"(?m)^      - {re.escape(setting)}$")

    def test_exporter_uses_its_own_least_privilege_role_and_secret(self):
        block = service_block("postgres-exporter")
        self.assertNotIn("database-password", block.replace("monitoring-database-password", ""))
        self.assertIn("monitoring-database-password", block)
        self.assertNotRegex(block, r"DATA_SOURCE_USER: plowshare\s*$")
        self.assertIn("PLOWSHARE_MONITORING_DB_USER:-plowshare_monitor", block)

    def test_exporter_is_opt_in_unpublished_and_pinned(self):
        block = service_block("postgres-exporter")
        self.assertRegex(block, r"profiles: \[monitoring\]")
        self.assertNotIn("ports:", block)
        self.assertRegex(block, r"(?m)^    image: \S+:v\d+\.\d+\.\d+(@\S+)?$")
        # Compose interpolates the whole file even with the profile off.
        self.assertNotIn(":?", block)

    def test_exporter_queries_never_expose_statement_text(self):
        queries = (MONITORING / "postgres-exporter-queries.yaml").read_text()
        self.assertIn("queryid", queries)
        self.assertNotRegex(queries, r"\bquery\s*(,|AS|FROM)")
        self.assertNotRegex(queries, r"pg_stat_statements\.query\b|\bs\.query\b")

    def test_dashboard_is_valid_json_with_the_new_meters(self):
        dashboard = json.loads((MONITORING / "grafana-plowshare.json").read_text())
        text = json.dumps(dashboard)
        for meter in (
            "plowshare_jdbc_statements_seconds_count",
            "plowshare_authority_admission_checks_total",
            "plowshare_operation_statements_bucket",
        ):
            self.assertIn(meter, text)

    def test_read_rejected_is_a_gauge_not_a_counter(self):
        text = (MONITORING / "grafana-plowshare.json").read_text()
        self.assertNotIn("plowshare_read_rejected_total", text)
        self.assertRegex(text, r"plowshare_read_rejected\b")

    def test_jfr_settings_enable_the_jdbc_event(self):
        settings = (MONITORING / "plowshare.jfc").read_text()
        match = re.search(r'<event name="plowshare.JdbcExecute">(.*?)</event>', settings, re.S)
        self.assertIsNotNone(match)
        self.assertIn('name="enabled">true<', match.group(1))
        self.assertIn('name="stackTrace">true<', match.group(1))
        self.assertIn('name="threshold">0 ms<', match.group(1))


class EntrypointTelemetryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.data = self.root / "data"
        self.data.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        java = self.bin / "java"
        java.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
        java.chmod(java.stat().st_mode | stat.S_IEXEC)
        self.environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("PLOWSHARE_")
        }
        self.environment.update(
            PATH=f"{self.bin}:{os.environ['PATH']}",
            PLOWSHARE_DATA_DIR=str(self.data),
            PLOWSHARE_DB_PASSWORD="test-only",
            PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY=str(self.root),
        )

    def launch(self, **values):
        return subprocess.run(
            ["sh", str(ROOT / "server-entrypoint.sh")],
            env={**self.environment, **values},
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )

    def test_without_telemetry_settings_no_extra_flags(self):
        result = self.launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("management.server", result.stdout)
        self.assertNotIn("prometheus", result.stdout)
        self.assertNotIn("StartFlightRecording", result.stdout)

    def test_management_listener_needs_both_port_and_bind(self):
        for values in (
            {"PLOWSHARE_MANAGEMENT_PORT": "9999"},
            {"PLOWSHARE_MANAGEMENT_BIND": "192.0.2.1"},
        ):
            result = self.launch(**values)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("PLOWSHARE_MANAGEMENT_PORT", result.stderr)
            self.assertIn("PLOWSHARE_MANAGEMENT_BIND", result.stderr)
            self.assertEqual(result.stdout, "")

    def test_management_listener_exposes_prometheus_only_when_configured(self):
        result = self.launch(
            PLOWSHARE_MANAGEMENT_PORT="9999", PLOWSHARE_MANAGEMENT_BIND="192.0.2.1"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--management.server.port=9999", result.stdout)
        self.assertIn("--management.server.address=192.0.2.1", result.stdout)
        self.assertIn(
            "--management.endpoints.web.exposure.include=health,info,metrics,prometheus",
            result.stdout,
        )

    def test_flight_recording_only_when_directory_is_set(self):
        jfr = self.root / "jfr"
        jfr.mkdir()
        result = self.launch(PLOWSHARE_JFR_DIR=str(jfr))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("-XX:StartFlightRecording=", result.stdout)
        self.assertIn("settings=/opt/plowshare/plowshare.jfc", result.stdout)
        self.assertIn(f"filename={jfr}/plowshare-%t.jfr", result.stdout)

    def test_flight_recording_requires_an_existing_writable_directory(self):
        result = self.launch(PLOWSHARE_JFR_DIR=str(self.root / "missing"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PLOWSHARE_JFR_DIR", result.stderr)


if __name__ == "__main__":
    unittest.main()
