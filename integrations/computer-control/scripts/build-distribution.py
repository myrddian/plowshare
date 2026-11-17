#!/usr/bin/env python3
"""Package prebuilt public artifacts and an explicit six-package runtime closure offline."""
import importlib.util
from contextlib import contextmanager
import fnmatch
import io
import gzip
import json
import os
from pathlib import Path
import sys
import stat
import tarfile
import tempfile

spec = importlib.util.spec_from_file_location(
    "computer_distribution", Path(__file__).with_name("distribution.py")
)
dist = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = dist
spec.loader.exec_module(dist)
ROOT = Path(__file__).resolve().parents[3]
ADAPTER = ROOT / "integrations/computer-control"
MAX_DISCOVERY_ENTRIES = 2 * dist.MAX_FILES
MAX_DISCOVERY_DEPTH = 64


@contextmanager
def candidate_paths(root, mode, patterns, deadline):
    """Stream directory entries and close every iterator on early refusal.

    pathlib globbing materializes directory listings internally. This narrowly
    owns scandir iterators instead; every visited entry shares one package cap.
    SDK selectors have literal parents and leaf matching only.
    """
    visited = 0
    excluded = {"node_modules", ".git", ".local-browsers"}

    def literal_parent(relative):
        current = root
        for part in ("", *relative.parts):
            current = current / part
            deadline.remaining()
            try:
                info = current.lstat()
            except FileNotFoundError:
                return None
            if stat.S_ISLNK(info.st_mode):
                raise dist.Failure("PACKAGE_LINK")
            if not stat.S_ISDIR(info.st_mode):
                raise dist.Failure("INVALID_PACKAGE")
        return current

    def scan(directory, recursive, leaf, depth):
        nonlocal visited
        deadline.remaining()
        if depth > MAX_DISCOVERY_DEPTH:
            raise dist.Failure("FILE_LIMIT")
        with os.scandir(directory) as entries:
            for entry in entries:
                deadline.remaining()
                visited += 1
                if visited > MAX_DISCOVERY_ENTRIES:
                    raise dist.Failure("FILE_LIMIT")
                if entry.name in excluded:
                    continue
                if entry.is_symlink():
                    raise dist.Failure("PACKAGE_LINK")
                path = Path(entry.path)
                if entry.is_dir(follow_symlinks=False):
                    if recursive:
                        yield from scan(path, True, None, depth + 1)
                elif leaf is None or fnmatch.fnmatchcase(entry.name, leaf):
                    yield path

    def discover():
        if mode == "sdk":
            for pattern in patterns:
                selector = Path(pattern)
                parent = literal_parent(selector.parent)
                if parent is not None:
                    yield from scan(parent, False, selector.name, 0)
        else:
            parent = literal_parent(
                Path("build/computer") if mode == "provider" else Path()
            )
            if parent is not None:
                yield from scan(parent, True, None, 0)

    iterator = discover()
    try:
        yield iterator
    finally:
        iterator.close()


SMOKE = b"""import assert from 'node:assert/strict';
import * as provider from 'plowshare-computer-control';
import { main } from 'plowshare-computer-control/main';
import { checkedTransport } from 'plowshare-client-ts/operations/transport';
import { Credentials } from 'plowshare-client-node/credentials';
assert.equal(typeof checkedTransport, 'function');
assert.equal(typeof Credentials, 'function');
for (const name of ['createComputerProvider', 'createBrowserControlBackend', 'createMacOSControlBackend']) assert.equal(typeof provider[name], 'function');
assert.equal(provider.computerDeclarations().length, 4);
const output=[];
const ports={configReader:{read:async()=>{throw new Error('smoke must not read');},drain:async()=>{}}, diagnostics:{emit:e=>output.push(e)}, authenticate:async()=>{throw new Error('smoke must not authenticate');},runHost:async()=>{throw new Error('smoke must not start');},subscribeStop:()=>()=>{}};
await assert.rejects(main([], {}, ports));
assert.deepEqual(output,[{stage:'config',code:'CONFIG_FAILED'}]);
"""


def package_files(root, mode, deadline, file_budget, byte_budget) -> dist.PackageFiles:
    metadata = dist.strict_json(
        dist.read_regular(root / "package.json", 65536, deadline)
    )
    if (
        type(metadata) is not dict
        or type(metadata.get("name")) is not str
        or metadata["name"] not in dist.PACKAGES
        or type(metadata.get("version")) is not str
        or not metadata["version"]
        or len(metadata["version"]) > 64
    ):
        raise dist.Failure("INVALID_PACKAGE")
    selected = {"package.json": json.dumps(metadata, indent=2).encode() + b"\n"}
    patterns = ()
    if mode == "provider":
        if type(metadata.get("exports")) is not dict:
            raise dist.Failure("INVALID_PACKAGE")
        metadata["private"] = False
        metadata["dependencies"] = {
            "playwright": "1.63.0",
            "plowshare-client-ts": "0.1.0",
            "plowshare-client-node": "0.1.0",
        }
        metadata["exports"]["./main"] = {
            "types": "./build/computer/main.d.ts",
            "import": "./build/computer/main.js",
        }
        metadata["bin"] = {"plowshare-computer-control": "./build/computer/main.js"}
        selected["package.json"] = json.dumps(metadata, indent=2).encode() + b"\n"
    elif mode == "sdk":
        raw_patterns = metadata.get("files")
        if (
            type(raw_patterns) is not list
            or not raw_patterns
            or len(raw_patterns) > 256
        ):
            raise dist.Failure("INVALID_PACKAGE")
        for pattern in raw_patterns:
            dist.relative(pattern)
            # Current public SDK manifests use literal parent directories and
            # leaf globs. Recursive discovery is not this package contract.
            if any(c in str(Path(pattern).parent) for c in "*?[]"):
                raise dist.Failure("INVALID_PACKAGE")
        patterns = tuple(raw_patterns)
    with candidate_paths(root, mode, patterns, deadline) as candidates:
        for path in candidates:
            deadline.remaining()
            relative = path.relative_to(root).as_posix()
            if any(
                part in ("node_modules", ".git", ".local-browsers")
                for part in path.relative_to(root).parts
            ):
                continue
            if path.is_symlink():
                raise dist.Failure("PACKAGE_LINK")
            if path.is_dir():
                continue
            if relative == "package.json":
                continue
            if mode in ("provider", "sdk") and any(
                marker in relative for marker in (".test.", ".test-support.", ".live.")
            ):
                continue
            if mode == "provider" and (
                not relative.endswith((".js", ".d.ts"))
                or ".test." in relative
                or ".test-support." in relative
                or ".live." in relative
                or path.name.startswith("acceptance.")
            ):
                continue
            data = dist.read_regular(path, dist.MAX_FILE, deadline)
            if (
                data[:4]
                in (
                    b"\x7fELF",
                    b"\xcf\xfa\xed\xfe",
                    b"\xfe\xed\xfa\xcf",
                    b"\xce\xfa\xed\xfe",
                    b"\xfe\xed\xfa\xce",
                )
                or data[:2] == b"MZ"
            ):
                raise dist.Failure("EXECUTABLE_PAYLOAD_NOT_SUPPORTED")
            dist.relative(relative)
            selected[relative] = data
            if (
                len(selected) > file_budget
                or sum(map(len, selected.values())) > byte_budget
            ):
                raise dist.Failure("FILE_LIMIT")
    for name in ("LICENSE", "NOTICE", "README.md"):
        path = root / name
        if path.is_file():
            selected[name] = dist.read_regular(path, dist.MAX_FILE, deadline)
    if mode in ("provider", "sdk"):
        for name in ("LICENSE", "NOTICE"):
            selected.setdefault(
                name, dist.read_regular(ROOT / name, dist.MAX_FILE, deadline)
            )
    if mode == "provider" and not all(
        name in selected
        for name in (
            "build/computer/index.js",
            "build/computer/index.d.ts",
            "build/computer/main.js",
        )
    ):
        raise dist.Failure("BUILD_REQUIRED")
    if len(selected) > file_budget or sum(map(len, selected.values())) > byte_budget:
        raise dist.Failure("FILE_LIMIT")
    return dist.package_files_value(metadata["name"], metadata["version"], selected)


def artifact(root, mode, output, deadline, file_budget, byte_budget) -> dist.Package:
    source = package_files(root, mode, deadline, file_budget, byte_budget)
    members = []
    with output.open("xb") as compressed:
        with gzip.GzipFile(
            filename="", mode="wb", fileobj=compressed, mtime=0
        ) as zipped:
            with tarfile.open(
                fileobj=zipped, mode="w", format=tarfile.USTAR_FORMAT
            ) as archive:
                for path, data in sorted(source.files.items()):
                    deadline.remaining()
                    mode = 0o755 if path == "build/computer/main.js" else 0o644
                    item = tarfile.TarInfo("package/" + path)
                    item.size = len(data)
                    item.mode = mode
                    item.mtime = 0
                    archive.addfile(item, io.BytesIO(data))
                    members.append(
                        dist.Member(path, dist.digest(data), len(data), mode)
                    )
    data = dist.read_regular(output, dist.MAX_ARCHIVE, deadline)
    return dist.Package(
        source.name,
        source.version,
        "artifacts/" + source.name + ".tgz",
        dist.digest(data),
        len(data),
        tuple(members),
    )


def build(output, node, deadline):
    dist.supported_platform()
    output = dist.private_destination(str(output))
    # Node's public resolver finds installed dependencies; no discovery of browsers/helpers or network.
    code = "const {createRequire}=require('node:module'); const p=require('node:path'); const a=createRequire(process.argv[1]); const n=createRequire(process.argv[2]); const play=a.resolve('playwright/package.json'); const r=createRequire(play); console.log(JSON.stringify([p.dirname(play),p.dirname(r.resolve('playwright-core/package.json')),p.dirname(n.resolve('ws/package.json'))]));"
    resolved = dist.strict_json(
        dist.run(
            [
                str(node),
                "-e",
                code,
                str(ADAPTER / "package.json"),
                str(ROOT / "sdk/node/package.json"),
            ],
            ROOT,
            deadline,
        )
    )
    if (
        type(resolved) is not list
        or len(resolved) != 3
        or any(type(v) is not str for v in resolved)
    ):
        raise dist.Failure("DEPENDENCIES_REQUIRED")
    roots = [
        (ADAPTER, "provider"),
        (ROOT / "sdk/typescript", "sdk"),
        (ROOT / "sdk/node", "sdk"),
        *((Path(value), "vendor") for value in resolved),
    ]
    revision = dist.run(["git", "rev-parse", "HEAD"], ROOT, deadline).decode().strip()
    dirty = bool(dist.run(["git", "status", "--porcelain"], ROOT, deadline))
    stage = Path(tempfile.mkdtemp(prefix=".computer-pack-", dir=output.parent))
    os.chmod(stage, 0o700)
    safe_cleanup = True
    try:
        artifact_dir = stage / "artifacts"
        artifact_dir.mkdir()
        packages = []
        remaining_files = dist.MAX_FILES
        remaining_bytes = dist.MAX_TOTAL
        for (root, mode), name in zip(roots, dist.PACKAGES):
            package = artifact(
                root,
                mode,
                artifact_dir / (name + ".tgz"),
                deadline,
                remaining_files,
                remaining_bytes,
            )
            if package.name != name:
                raise dist.Failure("DEPENDENCY_MISMATCH")
            if (
                name in ("playwright", "playwright-core")
                and package.version != "1.63.0"
            ):
                raise dist.Failure("DEPENDENCY_MISMATCH")
            remaining_files -= len(package.files)
            remaining_bytes -= sum(item.byte_length for item in package.files)
            if remaining_files < 0 or remaining_bytes < 0:
                raise dist.Failure("FILE_LIMIT")
            packages.append(package)
        support = []
        for name, data in [
            ("install.py", Path(__file__).with_name("distribution.py").read_bytes()),
            ("smoke.mjs", SMOKE),
        ]:
            (stage / name).write_bytes(data)
            support.append(dist.Support(name, dist.digest(data), len(data)))
        data = dist.encode_manifest(
            dist.Manifest(revision, dirty, tuple(packages), tuple(support))
        )
        (stage / "manifest.json").write_bytes(data)
        deadline.remaining()
        dist.publish(stage, output)
    except dist.CleanupUncertain:
        safe_cleanup = False
        raise
    finally:
        if safe_cleanup and stage.exists():
            dist.cleanup(stage)


def main():
    parser = dist.Parser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--node", required=True)
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    try:
        node = Path(args.node)
        if not node.is_absolute():
            raise dist.Failure("NODE_PREREQUISITE_REQUIRED")
        build(args.output, node, dist.Deadline(args.timeout))
        print(
            "PASS: offline provider/public SDK distribution built from local artifacts. No browser/helper binaries or credentials included."
        )
        return 0
    except (dist.Failure, OSError, ValueError, tarfile.TarError):
        print(
            "FAIL: offline distribution build failed; inspect prerequisites without replaying work.",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
