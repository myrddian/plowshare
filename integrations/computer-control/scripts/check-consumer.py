#!/usr/bin/env python3
"""Compile and import a fresh consumer of the exact offline public artifacts, without npm/network."""
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile

spec = importlib.util.spec_from_file_location(
    "computer_distribution_builder", Path(__file__).with_name("build-distribution.py")
)
builder = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = builder
spec.loader.exec_module(builder)
dist = builder.dist
ROOT = Path(__file__).resolve().parents[3]
CONSUMER = """import { createComputerProvider, computerDeclarations, createBrowserControlBackend, createMacOSControlBackend, FileComputerConfigReader, createHostDiagnostics } from 'plowshare-computer-control';
import type { ComputerProviderOptions, NativeArmingBackend, ComputerConfigReader, HostDiagnostics } from 'plowshare-computer-control';
import { main } from 'plowshare-computer-control/main';
import type { ComputerMainPorts } from 'plowshare-computer-control/main';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
const reader:ComputerConfigReader=new FileComputerConfigReader();
const diagnostics:HostDiagnostics=createHostDiagnostics(()=>{});
export {createComputerProvider,computerDeclarations,createBrowserControlBackend,createMacOSControlBackend,main,checkedTransport,reader,diagnostics};
export type { ComputerProviderOptions, NativeArmingBackend, ComputerMainPorts };
"""


def main():
    parser = dist.Parser(description=__doc__)
    parser.add_argument("--node")
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    try:
        selected = args.node or shutil.which("node")
        if selected is None or not Path(selected).is_absolute():
            raise dist.Failure("NODE_PREREQUISITE_REQUIRED")
        node = Path(selected)
        deadline = dist.Deadline(args.timeout)
        # This is a build verification fixture, not production connection/path discovery.
        root = Path(tempfile.mkdtemp(prefix="computer-offline-consumer-")).resolve()
        safe_cleanup = True
        try:
            distribution = root / "distribution"
            consumer = root / "consumer"
            builder.build(distribution, node, deadline)
            dist.install(distribution, consumer, node, deadline)
            (consumer / "package.json").write_text(
                json.dumps({"private": True, "type": "module"})
            )
            (consumer / "consumer.ts").write_text(CONSUMER)
            dist.run(
                [
                    str(node),
                    str(ROOT / "node_modules/typescript/bin/tsc"),
                    "--noEmit",
                    "--strict",
                    "--skipLibCheck",
                    "--target",
                    "ES2022",
                    "--module",
                    "NodeNext",
                    "--moduleResolution",
                    "NodeNext",
                    "consumer.ts",
                ],
                consumer,
                deadline,
            )
        except dist.CleanupUncertain:
            safe_cleanup = False
            raise
        finally:
            if safe_cleanup:
                dist.cleanup(root)
        print(
            "PASS: fresh offline installed provider/public SDK runtime, CLI and typed consumer. No npm, network, browser or helper launch."
        )
        return 0
    except (dist.Failure, OSError, ValueError):
        print(
            "FAIL: offline public consumer check failed; no live work attempted.",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
